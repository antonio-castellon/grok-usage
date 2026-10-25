package ch.castellon.grok.usage.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ch.castellon.grok.usage.BotMeter
import ch.castellon.grok.usage.BotRead
import ch.castellon.grok.usage.BotUsage
import ch.castellon.grok.usage.GrokLinks
import ch.castellon.grok.usage.ParsedUsage
import ch.castellon.grok.usage.UsageParser
import ch.castellon.grok.usage.decodeJsResult
import ch.castellon.grok.usage.formatPercent

class WebCallbacks {
    var onParsed: (ParsedUsage) -> Unit = {}
    var onGiveUp: () -> Unit = {}
    var onProgress: (Int) -> Unit = {}
    var onPage: (String?) -> Unit = {}
    var signingIn: () -> Boolean = { false }
    var dashboardHandoffUsed: Boolean = false
    var onAccountShell: () -> Unit = {}
    var onNeedLogin: () -> Unit = {}
    var onSessionConfirmed: () -> Unit = {}
    var onUsageUnread: () -> Unit = {}
    var sessionKnown: () -> Boolean = { false }
    var onAccountName: (String) -> Unit = {}
    var onAccountPageRead: () -> Unit = {}
    var usageFallbackUsed: Boolean = false
    var onBotMeter: (BotMeter) -> Unit = {}
}

private const val REFLOW = """
(function(){
  try {
    var head = document.head || document.documentElement;
    var meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      head.appendChild(meta);
    }
    meta.setAttribute('content', 'width=device-width, initial-scale=1, viewport-fit=cover');
    if (!window.__grokUsageFit) {
      window.__grokUsageFit = function() {
        try {
          var vv = window.visualViewport;
          var vh = window.innerHeight || (vv ? vv.height : 0);
          if (!vh || vh < 200) return;
          var ime = window.__grokIme || 0;
          var vvLimit = vv ? (vv.offsetTop + vv.height) : vh;
          var limit = Math.min(vvLimit, vh - ime) - 12;
          if (limit < 160) return;
          var nodes = document.body ? document.body.querySelectorAll('*') : [];
          var best = null;
          var bestArea = 0;
          var tagged = [];
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.getAttribute('data-grok-fit')) tagged.push(el);
            var pos = getComputedStyle(el).position;
            if (pos !== 'fixed' && pos !== 'absolute') continue;
            var prev = parseFloat(el.getAttribute('data-grok-shift') || '0') || 0;
            var rect = el.getBoundingClientRect();
            var top = rect.top - prev;
            var height = rect.height;
            var width = rect.width;
            if (width < 120 || height < 160 || height > vh * 0.92) continue;
            var area = width * height;
            if (area > bestArea) {
              bestArea = area;
              best = {el: el, top: top, bottom: top + height, height: height};
            }
          }
          for (var t = 0; t < tagged.length; t++) {
            if (!best || tagged[t] !== best.el) {
              tagged[t].style.removeProperty('margin-top');
              tagged[t].style.removeProperty('max-height');
              tagged[t].style.removeProperty('overflow-y');
              tagged[t].removeAttribute('data-grok-fit');
              tagged[t].removeAttribute('data-grok-shift');
            }
          }
          if (best) {
            var el = best.el;
            var shift = 0;
            if (best.bottom > limit) {
              shift = -Math.min(best.bottom - limit, Math.max(0, best.top - 8));
            } else if (best.top < 0) {
              shift = Math.min(-best.top + 8, Math.max(0, limit - best.bottom - 8));
            }
            if (Math.abs(shift) > 1) {
              el.style.setProperty('margin-top', Math.round(shift) + 'px', 'important');
              el.setAttribute('data-grok-shift', String(Math.round(shift)));
            } else {
              el.style.removeProperty('margin-top');
              el.removeAttribute('data-grok-shift');
            }
            var room = Math.max(200, limit - Math.max(8, best.top + shift) );
            if (best.height > room + 8) {
              el.style.setProperty('max-height', Math.round(room) + 'px', 'important');
              el.style.setProperty('overflow-y', 'auto', 'important');
            } else {
              el.style.removeProperty('max-height');
              el.style.removeProperty('overflow-y');
            }
            el.setAttribute('data-grok-fit', '1');
          }
          var focus = document.activeElement;
          if (focus && focus !== document.body && focus.getBoundingClientRect) {
            var fr = focus.getBoundingClientRect();
            if ((fr.bottom > limit || fr.top < 0) && focus.scrollIntoView) {
              focus.scrollIntoView({block:'center', inline:'nearest'});
            }
          }
        } catch (e) {}
      };
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', window.__grokUsageFit);
        window.visualViewport.addEventListener('scroll', window.__grokUsageFit);
      }
      window.addEventListener('resize', window.__grokUsageFit);
    }
    window.__grokUsageFit();
  } catch (e) {}
})();
"""

private const val ACCOUNT_NAME = """
(function(){
  try {
    var t = (document.body && document.body.innerText) || '';
    var m = t.match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i);
    return m ? m[0] : '';
  } catch (e) { return ''; }
})()
"""

private const val SESSION_PROBE = """
(function(){
  try {
    var t = ((document.body && document.body.innerText) || '').slice(0, 6000).toLowerCase();
    if (t.length < 40) return 'wait';
    if (/\d+\s*%\s*used/.test(t) || t.indexOf('weekly grok bot') >= 0) return 'usage';
    if (t.indexOf('sign-in methods') >= 0 || t.indexOf('account created') >= 0 || t.indexOf('sign out') >= 0) return 'account';
    return 'login';
  } catch (e) { return 'login'; }
})()
"""

internal const val EXTRACT = """
(function(){
  try {
    var t = (document.body && document.body.innerText) || '';
    if (t.length > 80000) t = t.substring(0, 80000);
    return t;
  } catch (e) { return ''; }
})()
"""

internal const val BOT_ASK = """
(function(){
  var finished = false;
  var ws = null;
  function done(obj) {
    if (finished) return;
    finished = true;
    try { if (ws) ws.close(); } catch (e) {}
    try { GrokBotRelay.result(JSON.stringify(obj)); } catch (e) {}
  }
  function ask() {
    if (finished || !ws) return;
    try { ws.send(JSON.stringify({jsonrpc:'2.0', id:1, method:'bot.usage', params:{}})); }
    catch (e) { done({gen:__GEN__, ok:false, err:'send'}); }
  }
  var timer = setTimeout(function(){ done({gen:__GEN__, ok:false, err:'timeout'}); }, 8000);
  try {
    var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    ws = new WebSocket(scheme + location.host + '/ws/bot/');
  } catch (e) {
    clearTimeout(timer);
    done({gen:__GEN__, ok:false, err:'open'});
    return;
  }
  ws.onopen = function() {
    try { ws.send(JSON.stringify({protocol_version:'1.0.0', kind:'bot_client'})); } catch (e) {}
    setTimeout(ask, 500);
  };
  ws.onmessage = function(ev) {
    if (finished || typeof ev.data !== 'string') return;
    var msg;
    try { msg = JSON.parse(ev.data); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.method === 'ping') {
      try { ws.send(JSON.stringify({method:'pong', ts_ms: msg.ts_ms || Date.now()})); } catch (e) {}
      return;
    }
    var result = (msg.result && typeof msg.result === 'object') ? msg.result : null;
    if (!result && typeof msg.usagePercent === 'number') result = msg;
    if (msg.connection_id && !(result && typeof result.usagePercent === 'number')) { ask(); return; }
    if (!result) return;
    var known = typeof result.currentPeriodStartMs === 'number' ||
      typeof result.usagePercent === 'number' ||
      typeof result.hasNonZeroIncludedLimit === 'boolean' ||
      typeof result.includedLimitZero === 'boolean';
    if (!known) return;
    clearTimeout(timer);
    var pct = result.usagePercent;
    var excluded = result.includedLimitZero === true || result.hasNonZeroIncludedLimit === false;
    if (typeof pct !== 'number') {
      if (result.hasNonZeroIncludedLimit === true) pct = 0;
      else { done({gen:__GEN__, ok:true, none:true}); return; }
    }
    if (excluded && pct <= 0) { done({gen:__GEN__, ok:true, none:true}); return; }
    var resetMs = (typeof result.nextResetAtMs === 'number') ? result.nextResetAtMs : 0;
    done({gen:__GEN__, ok:true, percent:pct, resetMs:resetMs});
  };
  ws.onerror = function() { clearTimeout(timer); done({gen:__GEN__, ok:false, err:'error'}); };
  ws.onclose = function() { clearTimeout(timer); done({gen:__GEN__, ok:false, err:'close'}); };
})();
"""

private val allowedSuffixes = listOf(
    "x.ai",
    "spacex.ai",
    "spacex.com",
    "grok.com",
    "grokusercontent.com",
    "grokipedia.com",
    "x.com",
    "twitter.com",
    "cursor.com",
    "cursor.sh",
    "workos.com",
    "google.com",
    "googleapis.com",
    "gstatic.com",
    "googleusercontent.com",
    "youtube.com",
    "youtu.be",
    "withgoogle.com",
    "recaptcha.net",
    "github.com",
    "githubusercontent.com",
    "githubassets.com",
    "twimg.com",
    "t.co",
    "apple.com",
    "cloudflare.com",
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DashboardWebView(
    url: String,
    loadToken: Int,
    imeBottomCssPx: Int,
    interactive: Boolean,
    callbacks: WebCallbacks,
    webRef: MutableState<WebView?>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appliedIme = remember { intArrayOf(Int.MIN_VALUE) }
    AndroidView(
        modifier = modifier,
        factory = {
            buildWebView(context, callbacks).also { webRef.value = it }
        },
        onRelease = { web ->
            if (webRef.value === web) webRef.value = null
            runCatching {
                web.stopLoading()
                web.destroy()
            }
        },
        update = { web ->
            webRef.value = web
            web.visibility = View.VISIBLE
            if (interactive) {
                web.alpha = 1f
                web.translationX = 0f
            } else {
                web.alpha = 0f
                web.translationX = -8000f
            }
            val key = "$url#$loadToken"
            if (web.tag != key) {
                web.tag = key
                loadWhenLaidOut(web, key, url)
            }
            if (appliedIme[0] != imeBottomCssPx) {
                appliedIme[0] = imeBottomCssPx
                val host = web.url?.let { hostOf(it) }.orEmpty()
                if (injectUsageScript(host)) {
                    web.post {
                        web.evaluateJavascript(
                            "window.__grokIme=$imeBottomCssPx; if (window.__grokUsageFit) window.__grokUsageFit();",
                            null,
                        )
                    }
                }
            }
        },
    )
}

// The official usage page is a website, so JavaScript stays on.
// Weekly numbers come from that page's text. The bot meter is the same
// reading the usage page loads for this session when the card is not in the text.
@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: Context, callbacks: WebCallbacks): WebView {
    val client = UsageWebClient(callbacks)
    return WebView(context).apply {
        alpha = 0f
        translationX = -8000f
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        setBackgroundColor(Color.parseColor("#070708"))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.setSupportZoom(false)
        settings.userAgentString = mobileUserAgent(settings.userAgentString)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        hideAppIdentity(settings)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        var lastHeight = 0
        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val height = view.height
            if (height > 0 && kotlin.math.abs(height - lastHeight) > 40) {
                lastHeight = height
                (view as WebView).post { reflow(view) }
            }
        }
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.offscreenPreRaster = true
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(
            BotRelay { payload ->
                post { client.onBotPayload(this, payload) }
            },
            "GrokBotRelay",
        )
        webChromeClient = object : WebChromeClient() {
            private var popup: WebView? = null

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                callbacks.onProgress(newProgress)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                popup?.let { detachPopup(it) }
                val child = WebView(view.context)
                if (callbacks.sessionKnown()) {
                    child.alpha = 0f
                    child.translationX = -8000f
                }
                child.setBackgroundColor(Color.parseColor("#070708"))
                child.settings.javaScriptEnabled = true
                child.settings.domStorageEnabled = true
                child.settings.userAgentString = view.settings.userAgentString
                child.settings.setSupportMultipleWindows(true)
                child.settings.javaScriptCanOpenWindowsAutomatically = true
                hideAppIdentity(child.settings)
                CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
                child.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(childView: WebView, request: WebResourceRequest): Boolean {
                        val next = request.url.toString()
                        if (request.isForMainFrame && leaveForUsage(callbacks, view, next, callbacks.signingIn())) {
                            detachPopup(childView)
                            return true
                        }
                        if (isLoginReturnUrl(next)) {
                            view.loadUrl(next)
                            detachPopup(childView)
                            return true
                        }
                        return consumeNavigation(childView, request.url)
                    }

                    override fun onPageFinished(childView: WebView, url: String?) {
                        callbacks.onPage(url)
                        if (url != null && isLoginReturnUrl(url)) {
                            view.loadUrl(url)
                            detachPopup(childView)
                        }
                    }
                }
                child.webChromeClient = this
                (view.parent as? ViewGroup)?.addView(
                    child,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                popup = child
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }

            private fun detachPopup(child: WebView) {
                child.post {
                    (child.parent as? ViewGroup)?.removeView(child)
                    if (popup === child) popup = null
                    child.destroy()
                }
            }
        }
        webViewClient = client
    }
}

class BotRelay(
    private val onResult: (String) -> Unit,
) {
    @JavascriptInterface
    fun result(payload: String?) {
        onResult(payload?.take(240).orEmpty())
    }
}

private class UsageWebClient(
    private val callbacks: WebCallbacks,
) : WebViewClient() {
    private var chain = 0
    private var botGen = 0
    private var botBusy = false
    private var botTries = 0
    private var botGot = false

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        botGen++
        botBusy = false
        botTries = 0
        botGot = false
    }

    fun onBotPayload(view: WebView, payload: String) {
        val gen = BotUsage.generation(payload)
        if (gen != null && gen != botGen) return
        botBusy = false
        val host = view.url?.let { hostOf(it) }.orEmpty()
        if (!isGrokHost(host)) return
        when (val read = BotUsage.parse(payload)) {
            is BotRead.Meter -> {
                botGot = true
                android.util.Log.i(
                    "GrokUsage",
                    "bot ws ok pct=${formatPercent(read.meter.percent)} reset=${read.meter.resetRaw != null}",
                )
                callbacks.onBotMeter(read.meter)
            }
            BotRead.None -> {
                botGot = true
                android.util.Log.i("GrokUsage", "bot ws none")
            }
            is BotRead.Failed -> {
                android.util.Log.i("GrokUsage", "bot ws err=${read.reason}")
                if (botTries < 3) view.postDelayed({ requestBot(view) }, 1600L)
            }
        }
    }

    private fun requestBot(view: WebView) {
        if (botGot || botBusy || botTries >= 3) return
        val host = view.url?.let { hostOf(it) }.orEmpty()
        if (!isGrokHost(host) || !callbacks.sessionKnown()) return
        botTries += 1
        botBusy = true
        android.util.Log.i("GrokUsage", "bot ws ask try=$botTries")
        view.evaluateJavascript(BOT_ASK.replace("__GEN__", botGen.toString()), null)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) {
            val uri = request.url
            android.util.Log.i("GrokUsage", "nav " + (uri.host ?: "none") + pathOf(uri.toString()))
        }
        if (request.isForMainFrame && leaveForUsage(callbacks, view, request.url.toString(), callbacks.signingIn())) return true
        return consumeNavigation(view, request.url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        android.util.Log.i(
            "GrokUsage",
            "finished " + (url?.let { (hostOf(it) ?: "none") + pathOf(it) } ?: "none"),
        )
        callbacks.onPage(url)
        val host = url?.let { hostOf(it) }.orEmpty()
        CookieManager.getInstance().flush()
        if (!injectUsageScript(host)) return
        if (host == "accounts.x.ai" || host == "accounts.spacex.ai") {
            view.postDelayed({
                val now = view.url?.let { hostOf(it) }.orEmpty()
                if (now != "accounts.x.ai" && now != "accounts.spacex.ai") {
                    callbacks.onAccountPageRead()
                    return@postDelayed
                }
                view.evaluateJavascript(ACCOUNT_NAME) { raw ->
                    val name = decodeJsResult(raw).trim()
                    if (name.contains('@')) callbacks.onAccountName(name)
                    callbacks.onAccountPageRead()
                }
            }, 2000)
        }
        if (url != null && leaveForUsage(callbacks, view, url, callbacks.signingIn())) return
        if (callbacks.signingIn()) probeSession(view, url)
        if (url != null && callbacks.signingIn() && loginHandsOffToDashboard(url)) {
            handOffToDashboard(view, url)
            return
        }
        view.post { reflow(view) }
        view.postDelayed({ reflow(view) }, 350)
        view.postDelayed({ reflow(view) }, 900)
        startRead(view)
        requestBot(view)
    }

    private fun handOffToDashboard(view: WebView, fromUrl: String) {
        if (callbacks.dashboardHandoffUsed) return
        callbacks.dashboardHandoffUsed = true
        view.postDelayed({
            val current = view.url ?: return@postDelayed
            if (current.substringBefore('#') != fromUrl.substringBefore('#') && !loginHandsOffToDashboard(current)) {
                return@postDelayed
            }
            view.loadUrl(GrokLinks.DASHBOARD)
        }, 900)
    }

    private fun probeSession(view: WebView, url: String?) {
        view.evaluateJavascript(SESSION_PROBE) { raw ->
            val kind = raw?.trim()?.trim('"') ?: return@evaluateJavascript
            if (!callbacks.signingIn()) return@evaluateJavascript
            if (kind == "wait") {
                val host = url?.let { hostOf(it) }.orEmpty()
                if (holdsSignIn(host)) callbacks.onNeedLogin()
                return@evaluateJavascript
            }
            if (kind == "usage") return@evaluateJavascript
            if (kind == "account") callbacks.onSessionConfirmed()
            if (callbacks.sessionKnown()) return@evaluateJavascript
            val foreign = url != null && (prefersXaiAccount(url) || kind == "account" || isXaiAccountHome(url))
            if (foreign) {
                callbacks.onAccountShell()
                if (!callbacks.dashboardHandoffUsed) {
                    callbacks.dashboardHandoffUsed = true
                    view.loadUrl(GrokLinks.USAGE)
                } else {
                    callbacks.onNeedLogin()
                }
                return@evaluateJavascript
            }
            if (kind == "login") callbacks.onNeedLogin()
        }
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
            val text = decodeJsResult(raw)
            val parsed = runCatching { UsageParser.parse(text) }.getOrNull()
            val host = view.url?.let { hostOf(it) }.orEmpty()
            val path = view.url?.let { pathOf(it) }.orEmpty()
            val grok = Regex("(?i)supergrok|grok\\s+build|weekly\\s+grok\\s+bot").containsMatchIn(text)
            val pct = Regex("(?i)\\d+(?:\\.\\d+)?\\s*%\\s*used").containsMatchIn(text)
            val botWord = Regex("(?i)grok\\s+bot").containsMatchIn(text)
            val retryLimit = if (callbacks.sessionKnown()) 12 else 5
            if (attempt == 0 || parsed != null || attempt >= retryLimit) {
                android.util.Log.i(
                    "GrokUsage",
                    "read $host$path len=${text.length} pct=$pct grok=$grok bot=$botWord parsed=${parsed != null} try=$attempt",
                )
            }
            if (parsed != null) {
                callbacks.onParsed(parsed)
                if (parsed.botPercent == null) requestBot(view)
            } else if (
                attempt >= 2 &&
                callbacks.sessionKnown() &&
                !callbacks.usageFallbackUsed &&
                (host.endsWith("cursor.com") || host.endsWith("cursor.sh"))
            ) {
                callbacks.usageFallbackUsed = true
                android.util.Log.i("GrokUsage", "fallback grok.com/?_s=usage")
                view.loadUrl("https://grok.com/?_s=usage")
            } else if (attempt < retryLimit) {
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

internal fun mobileUserAgent(raw: String?): String {
    val base = raw?.takeIf { it.isNotBlank() }
        ?: "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/131.0.0.0"
    return "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Mobile Safari/537.36"
}

internal fun hideAppIdentity(settings: WebSettings) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
}

private fun loadWhenLaidOut(web: WebView, key: String, url: String, attempt: Int = 0) {
    web.post {
        if (web.tag != key) return@post
        if ((web.parent == null || web.height <= 0) && attempt < 40) {
            loadWhenLaidOut(web, key, url, attempt + 1)
            return@post
        }
        web.loadUrl(url)
    }
}

private fun reflow(view: WebView) {
    if (view.height <= 0) return
    val host = view.url?.let { hostOf(it) }.orEmpty()
    if (host == "x.com" || host.endsWith(".x.com") || host.endsWith("twitter.com") || host.contains("google.") ||
        host.endsWith("spacex.ai") || host.endsWith("spacex.com") || host.endsWith("cursor.com") ||
        host.endsWith("cursor.sh") || host.endsWith("workos.com")
    ) {
        return
    }
    runCatching { view.evaluateJavascript(REFLOW, null) }
}

private fun consumeNavigation(view: WebView, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return true
    if (scheme == "intent") {
        val parsed = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
        val fallback = parsed?.getStringExtra("browser_fallback_url")
        val data = parsed?.data
        val target = when {
            data?.scheme == "https" && data.host?.let { hostAllowed(it) } == true -> data
            !fallback.isNullOrBlank() -> Uri.parse(fallback)
            else -> null
        }
        if (target != null && (target.scheme == "https" || target.scheme == "http") && hostAllowed(target.host.orEmpty())) {
            view.loadUrl(target.toString())
        }
        return true
    }
    val embedded = httpsFromAppScheme(uri)
    if (embedded != null) {
        view.loadUrl(embedded)
        return true
    }
    if (scheme != "http" && scheme != "https") return true
    val host = uri.host ?: return true
    return !hostAllowed(host)
}

private fun httpsFromAppScheme(uri: Uri): String? {
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme == "http" || scheme == "https" || scheme == "intent") return null
    val looksLikeWeb = scheme == "x-safari-https" || scheme == "googlechrome" || scheme == "googlechromes" ||
        scheme.startsWith("x-safari-")
    if (!looksLikeWeb) return null
    val host = uri.host ?: return null
    if (!hostAllowed(host)) return null
    return uri.buildUpon().scheme("https").build().toString()
}

internal fun prefersXaiAccount(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return host == "cursor.com" || host.endsWith(".cursor.com") ||
        host == "cursor.sh" || host.endsWith(".cursor.sh")
}

internal fun isXaiAccountHome(url: String): Boolean {
    val host = hostOf(url) ?: return false
    if (host != "accounts.x.ai" && host != "accounts.spacex.ai") return false
    val path = pathOf(url)
    if (path.contains("sign-in") || path.contains("signin") || path.contains("login") ||
        path.contains("sign-up") || path.contains("signup") || path.contains("callback") || path.contains("oauth")
    ) {
        return false
    }
    return true
}

private fun leaveForUsage(callbacks: WebCallbacks, view: WebView, url: String, signingIn: Boolean): Boolean {
    if (!signingIn || (!prefersXaiAccount(url) && !isXaiAccountHome(url))) return false
    if (isXaiAccountHome(url)) callbacks.onSessionConfirmed()
    callbacks.onAccountShell()
    if (callbacks.sessionKnown()) return false
    if (!callbacks.dashboardHandoffUsed) {
        callbacks.dashboardHandoffUsed = true
        view.loadUrl(GrokLinks.USAGE)
    } else {
        callbacks.onNeedLogin()
    }
    return true
}

internal fun isGrokHost(host: String): Boolean {
    return host == "grok.com" || host.endsWith(".grok.com")
}

internal fun hostOf(url: String): String? {
    val rest = url.substringAfter("://", missingDelimiterValue = "")
    if (rest.isEmpty()) return null
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    return host.takeIf { it.isNotEmpty() && !it.contains(' ') }
}

internal fun hostAllowed(host: String): Boolean {
    val normalized = host.lowercase()
    return allowedSuffixes.any { suffix ->
        normalized == suffix || normalized.endsWith(".$suffix")
    }
}

/** Google's sign-in page fails when the app injects scripts or reloads it mid-check. */
internal fun injectUsageScript(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized.isEmpty()) return true
    return !normalized.contains("google.")
}

internal fun holdsSignIn(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized.isEmpty()) return false
    return normalized.contains("google.") ||
        normalized == "x.com" || normalized.endsWith(".x.com") ||
        normalized.endsWith("twitter.com") ||
        normalized.endsWith("x.ai") ||
        normalized.endsWith("spacex.ai") ||
        normalized.endsWith("spacex.com") ||
        normalized.endsWith("cursor.com") ||
        normalized.endsWith("cursor.sh") ||
        normalized.endsWith("workos.com")
}

internal fun loginHandsOffToDashboard(url: String): Boolean {
    val host = hostOf(url) ?: return false
    val path = pathOf(url)
    val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#').lowercase()
    if (host == "grok.com" || host.endsWith(".grok.com")) return false
    if (holdsSignIn(host) && (host.endsWith("x.ai") || host.endsWith("spacex.ai") || host.endsWith("cursor.com") || host.endsWith("cursor.sh"))) {
        if (path.contains("sign-in") || path.contains("signin") || path.contains("login") || path.contains("sign-up") || path.contains("signup")) {
            return query.contains("code=") || path.contains("callback")
        }
        return path.contains("callback") || path.contains("complete") ||
            path.contains("success") || path.contains("linked") || query.contains("code=")
    }
    if (host == "x.com" || host.endsWith(".x.com") || host.endsWith("twitter.com")) {
        return path == "/home" || path == "/home/"
    }
    return false
}

internal fun isLoginReturnUrl(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return false
    val host = hostOf(url) ?: return false
    val path = pathOf(url)
    if ((host == "grok.com" || host.endsWith(".grok.com")) &&
        (path.isEmpty() || path == "/" || path.startsWith("/dashboard") || path.startsWith("/chat"))
    ) {
        return true
    }
    return loginHandsOffToDashboard(url)
}

private fun pathOf(url: String): String {
    val rest = url.substringAfter("://", missingDelimiterValue = url)
    val slash = rest.indexOf('/')
    if (slash < 0) return ""
    return rest.substring(slash).substringBefore('?').substringBefore('#').lowercase()
}
