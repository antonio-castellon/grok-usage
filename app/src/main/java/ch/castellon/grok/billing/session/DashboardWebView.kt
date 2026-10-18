package ch.castellon.grok.billing.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ch.castellon.grok.billing.ParsedUsage
import ch.castellon.grok.billing.UsageParser
import ch.castellon.grok.billing.decodeJsResult

class WebCallbacks {
    var onParsed: (ParsedUsage) -> Unit = {}
    var onGiveUp: () -> Unit = {}
    var onProgress: (Int) -> Unit = {}
}

private const val EXTRACT = """
(function(){
  try {
    var t = (document.body && document.body.innerText) || '';
    if (t.length > 80000) t = t.substring(0, 80000);
    return t;
  } catch (e) { return ''; }
})()
"""

private val allowedSuffixes = listOf(
    "x.ai",
    "grok.com",
    "x.com",
    "twitter.com",
    "google.com",
    "googleapis.com",
    "gstatic.com",
    "googleusercontent.com",
    "apple.com",
    "cloudflare.com",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardWebView(
    url: String,
    loadToken: Int,
    callbacks: WebCallbacks,
    webRef: MutableState<WebView?>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(callbacks) { buildWebView(context, callbacks) }
    DisposableEffect(webView) {
        webRef.value = webView
        onDispose {
            webRef.value = null
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = { web ->
            val key = "$url#$loadToken"
            if (web.tag != key) {
                web.tag = key
                web.loadUrl(url)
            }
        },
    )
}

// The official dashboard is a website, so JavaScript has to stay on.
// The app reads the text of the page the user opened. It does not call a private billing API.
@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: Context, callbacks: WebCallbacks): WebView {
    return WebView(context).apply {
        setBackgroundColor(Color.parseColor("#070708"))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.offscreenPreRaster = true
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgress(newProgress)
            }
        }
        webViewClient = UsageWebClient(callbacks)
    }
}

private class UsageWebClient(
    private val callbacks: WebCallbacks,
) : WebViewClient() {
    private var chain = 0

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return true
        return !allowed(host)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        startRead(view)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!request.isForMainFrame) return
        chain++
        callbacks.onGiveUp()
    }

    private fun startRead(view: WebView) {
        val id = ++chain
        read(view, id, 0)
    }

    private fun read(view: WebView, id: Int, attempt: Int) {
        if (id != chain) return
        try {
            view.evaluateJavascript(EXTRACT) { raw ->
            if (id != chain) return@evaluateJavascript
            val parsed = runCatching { UsageParser.parse(decodeJsResult(raw)) }.getOrNull()
            if (parsed != null) {
                callbacks.onParsed(parsed)
            } else if (attempt < 5) {
                view.postDelayed({ read(view, id, attempt + 1) }, delayFor(attempt))
            } else {
                    callbacks.onGiveUp()
                }
            }
        } catch (_: Throwable) {
            if (id == chain) callbacks.onGiveUp()
        }
    }

    private fun delayFor(attempt: Int): Long = when (attempt) {
        0 -> 400L
        1 -> 800L
        2 -> 1400L
        else -> 2200L
    }
}

private fun allowed(host: String): Boolean {
    val normalized = host.lowercase()
    return allowedSuffixes.any { suffix ->
        normalized == suffix || normalized.endsWith(".$suffix")
    }
}
