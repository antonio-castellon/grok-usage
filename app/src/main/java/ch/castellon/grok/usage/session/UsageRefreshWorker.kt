package ch.castellon.grok.usage.session

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
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
import ch.castellon.grok.usage.decodeJsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the usage page with the stored session in an off-screen WebView (no Activity),
 * using the same page scripts and parser as the app. Failures keep the last figures
 * and wait for the next period; there is no retry.
 */
class UsageRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val store = UsageStore(app)
        if (store.load() == null || !UsageRefresh.hasStoredSession()) return Result.success()
        val read = withContext(Dispatchers.Main) { withTimeoutOrNull(READ_LIMIT_MS) { readOnce(app) } }
        if (read == null) {
            android.util.Log.i("GrokUsage", "background read skipped")
            return Result.success()
        }
        val snap = UsageSnapshot.from(read.first, read.second, System.currentTimeMillis())
        store.save(snap)
        UsageWidgets.refresh(app, snap.overallPercent, snap.botPercent)
        if (store.loadNotify() && UsageNotifier.canPost(app)) {
            UsageNotifier.show(app, snap.overallPercent, snap.botPercent)
        }
        return Result.success()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readOnce(context: Context): Pair<ParsedUsage, BotMeter?>? {
        val bot = CompletableDeferred<BotMeter?>()
        val loaded = CompletableDeferred<Unit>()
        val web = WebView(context)
        try {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.loadsImagesAutomatically = false
            web.settings.userAgentString = mobileUserAgent(web.settings.userAgentString)
            hideAppIdentity(web.settings)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            web.measure(VIEW_W, VIEW_H)
            web.layout(0, 0, VIEW_W, VIEW_H)
            web.addJavascriptInterface(
                BotRelay { payload ->
                    when (val r = BotUsage.parse(payload)) {
                        is BotRead.Meter -> bot.complete(r.meter)
                        else -> bot.complete(null)
                    }
                },
                "GrokBotRelay",
            )
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    // Only grok.com is read in the background; a sign-in redirect means the session is gone.
                    return !isGrokHost(request.url.host.orEmpty())
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loaded.complete(Unit)
                }
            }
            web.loadUrl(GrokLinks.DASHBOARD)
            loaded.await()
            var parsed: ParsedUsage? = null
            for (attempt in 0 until 12) {
                if (!isGrokHost(web.url?.let { hostOf(it) }.orEmpty())) return null
                parsed = runCatching { UsageParser.parse(decodeJsResult(web.eval(EXTRACT))) }.getOrNull()
                if (parsed != null) break
                delay(1_500L)
            }
            if (parsed == null) return null
            if (parsed.botPercent != null) return parsed to null
            web.evaluateJavascript(BOT_ASK.replace("__GEN__", "0"), null)
            return parsed to withTimeoutOrNull(BOT_WAIT_MS) { bot.await() }
        } finally {
            CookieManager.getInstance().flush()
            runCatching {
                web.stopLoading()
                web.destroy()
            }
        }
    }

    private suspend fun WebView.eval(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { cont.resume(it) }
    }

    private companion object {
        const val READ_LIMIT_MS = 60_000L
        const val BOT_WAIT_MS = 10_000L
        const val VIEW_W = 1080
        const val VIEW_H = 1920
    }
}