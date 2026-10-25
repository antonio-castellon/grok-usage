package ch.castellon.grok.usage.session

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.castellon.grok.usage.BotMeter
import ch.castellon.grok.usage.BotRead
import ch.castellon.grok.usage.BotUsage
import ch.castellon.grok.usage.GrokLinks
import ch.castellon.grok.usage.ParsedUsage
import ch.castellon.grok.usage.UsageNotifier
import ch.castellon.grok.usage.UsageParser
import ch.castellon.grok.usage.UsageRefresh
import ch.castellon.grok.usage.UsageSnapshot
import ch.castellon.grok.usage.UsageStore
import ch.castellon.grok.usage.UsageWidgets
import ch.castellon.grok.usage.backgroundMayNavigate
import ch.castellon.grok.usage.decodeJsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Reads the usage page with the stored session in an off-screen WebView (no Activity),
 * using the same page scripts and parser as the app. A failed read keeps the last
 * figures and tries again.
 */
internal enum class PullResult { Idle, Updated, Failed }

/**
 * One usage read. Safe to call from the short refresh service or from the worker.
 * A read already in flight is left alone.
 */
internal suspend fun pullUsage(context: Context): PullResult {
    val app = context.applicationContext
    val store = UsageStore(app)
    if (store.load() == null || !UsageRefresh.hasStoredSession()) {
        android.util.Log.i("GrokUsage", "background read idle")
        return PullResult.Idle
    }
    if (!pulling.compareAndSet(false, true)) {
        android.util.Log.i("GrokUsage", "background read busy")
        return PullResult.Idle
    }
    try {
        val read = withContext(Dispatchers.Main) { withTimeoutOrNull(READ_LIMIT_MS) { readOnce(app) } }
        val snap = when {
            read?.parsed != null -> UsageSnapshot.from(read.parsed, read.bot, System.currentTimeMillis())
            read?.bot != null -> store.load()?.copy(
                botPercent = read.bot.percent,
                botResetRaw = read.bot.resetRaw,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            else -> null
        }
        if (snap == null) {
            android.util.Log.i("GrokUsage", "background read skipped")
            return PullResult.Failed
        }
        store.save(snap)
        UsageWidgets.refresh(app, snap.overallPercent, snap.botPercent)
        if (store.loadNotify() && UsageNotifier.canPost(app)) {
            UsageNotifier.show(app, snap.overallPercent, snap.botPercent)
        }
        android.util.Log.i("GrokUsage", "background read ok")
        return PullResult.Updated
    } finally {
        pulling.set(false)
    }
}

private val pulling = AtomicBoolean(false)
private const val READ_LIMIT_MS = 90_000L
private const val PAGE_WAIT_MS = 35_000L
private const val LAYOUT_WAIT_MS = 8_000L
private const val BOT_WAIT_MS = 9_000L
private const val VIEW_W = 1080
private const val VIEW_H = 1920

class UsageRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (UsageRefresh.launch(applicationContext)) return Result.success()
        return when (pullUsage(applicationContext)) {
            PullResult.Failed -> if (runAttemptCount < 2) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private suspend fun readOnce(context: Context): Read? {
        val bot = CompletableDeferred<BotMeter?>()
        val loaded = CompletableDeferred<Unit>()
        val hostWindow = PageHost(context)
        val web = hostWindow.open()
        try {
            if (web == null) return null
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(web, true)
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.loadsImagesAutomatically = true
            web.settings.useWideViewPort = true
            web.settings.userAgentString = mobileUserAgent(web.settings.userAgentString)
            hideAppIdentity(web.settings)
            web.onResume()
            web.resumeTimers()
            web.addJavascriptInterface(
                BotRelay { payload ->
                    when (val r = BotUsage.parse(payload)) {
                        is BotRead.Meter -> if (!bot.isCompleted) bot.complete(r.meter)
                        else -> Unit
                    }
                },
                "GrokBotRelay",
            )
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    // Follow the session hop back to grok.com. Anything else is not the usage page.
                    return !backgroundMayNavigate(request.url.host.orEmpty())
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    val host = url?.let { hostOf(it) }.orEmpty()
                    if (host.isNotEmpty()) android.util.Log.i("GrokUsage", "background nav $host")
                    if (!url.isNullOrBlank() && url != "about:blank" && !loaded.isCompleted) loaded.complete(Unit)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url.isNullOrBlank() || url == "about:blank") return
                    if (!loaded.isCompleted) loaded.complete(Unit)
                }
            }
            withTimeoutOrNull(LAYOUT_WAIT_MS) { web.awaitLayout() }
            if (web.width == 0 || web.height == 0) {
                android.util.Log.i("GrokUsage", "background read no layout")
            }
            web.loadUrl(GrokLinks.USAGE)
            if (withTimeoutOrNull(PAGE_WAIT_MS) { loaded.await() } == null) {
                android.util.Log.i("GrokUsage", "background read no page")
                return null
            }
            var parsed: ParsedUsage? = null
            var host = ""
            var len = 0
            for (attempt in 0 until 12) {
                host = web.url?.let { hostOf(it) }.orEmpty()
                if (host.isNotEmpty() && !backgroundMayNavigate(host)) {
                    android.util.Log.i("GrokUsage", "background read blocked host=$host")
                    return null
                }
                if (isGrokHost(host)) {
                    val text = decodeJsResult(web.eval(EXTRACT))
                    len = text.length
                    parsed = runCatching { UsageParser.parse(text) }.getOrNull()
                    if (parsed != null) break
                }
                delay(1_500L)
            }
            android.util.Log.i(
                "GrokUsage",
                "background read host=$host len=$len parsed=${parsed != null}",
            )
            val meter = if (parsed?.botPercent != null || !isGrokHost(host)) {
                null
            } else {
                var found: BotMeter? = null
                for (attempt in 0 until 3) {
                    if (bot.isCompleted) {
                        found = runCatching { bot.getCompleted() }.getOrNull()
                        break
                    }
                    web.evaluateJavascript(BOT_ASK.replace("__GEN__", attempt.toString()), null)
                    found = withTimeoutOrNull(BOT_WAIT_MS) { bot.await() }
                    if (found != null) break
                }
                found
            }
            if (parsed == null && meter == null) return null
            return Read(parsed, meter)
        } finally {
            CookieManager.getInstance().flush()
            hostWindow.close()
        }
    }

private suspend fun WebView.eval(script: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { cont.resume(it) }
}

private class Read(val parsed: ParsedUsage?, val bot: BotMeter?)

private suspend fun WebView.awaitLayout() {
    if (width > 0 && height > 0) return
    suspendCancellableCoroutine { cont ->
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0 && cont.isActive) cont.resume(Unit)
        }
        addOnLayoutChangeListener(listener)
        cont.invokeOnCancellation { removeOnLayoutChangeListener(listener) }
    }
}

/** A private virtual display gives the background page a real window without covering the phone. */
private class PageHost(private val context: Context) {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null

    fun open(): WebView? {
        val manager = context.getSystemService(DisplayManager::class.java)
        val density = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        val created = runCatching {
            manager?.createVirtualDisplay(
                "usage-refresh",
                VIEW_W,
                VIEW_H,
                density,
                null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            )
        }.onFailure {
            android.util.Log.i("GrokUsage", "background read display ${it.javaClass.simpleName}")
        }.getOrNull()
        if (created == null) return null
        display = created
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
        val window = Presentation(themed, created.display)
        val page = WebView(window.context)
        window.setContentView(
            page,
            FrameLayout.LayoutParams(VIEW_W, VIEW_H),
        )
        val shown = runCatching { window.show() }
        if (shown.isFailure) {
            android.util.Log.i("GrokUsage", "background read window ${shown.exceptionOrNull()?.javaClass?.simpleName}")
            return null
        }
        presentation = window
        web = page
        page.post {
            if (page.width == 0 || page.height == 0) {
                page.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, VIEW_W, VIEW_H)
            }
        }
        android.util.Log.i("GrokUsage", "background read window ok")
        return page
    }

    fun close() {
        val page = web
        runCatching { presentation?.dismiss() }
        runCatching {
            page?.stopLoading()
            (page?.parent as? ViewGroup)?.removeView(page)
            page?.destroy()
        }
        runCatching { display?.release() }
    }
}