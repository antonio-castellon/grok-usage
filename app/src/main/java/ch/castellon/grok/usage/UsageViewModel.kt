package ch.castellon.grok.usage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

enum class Phase { SignedOut, SigningIn, Ready, Official }

enum class StatusKind { Empty, Updated, Saved, RefreshFailed, NeedUsagePage, SignInTimedOut }

internal const val SIGN_IN_WAIT_MS = 180_000L

internal fun cookieLooksSignedIn(header: String?): Boolean {
    if (header.isNullOrBlank()) return false
    return header.split(';').any { part ->
        val name = part.substringBefore('=').trim().lowercase()
        name.isNotEmpty() && name != "__cf_bm" && name != "_cfuvid" && !name.startsWith("__cf")
    }
}

internal fun showSignInWeb(phase: Phase, readingUsage: Boolean, sessionKnown: Boolean): Boolean {
    return phase == Phase.SigningIn && !readingUsage && !sessionKnown
}

internal fun showReadingCover(phase: Phase, readingUsage: Boolean, sessionKnown: Boolean): Boolean {
    return phase == Phase.SigningIn && readingUsage && !sessionKnown
}

internal fun showSessionHome(phase: Phase, sessionKnown: Boolean, hasSnapshot: Boolean): Boolean {
    return sessionKnown && !hasSnapshot && phase != Phase.Official
}

internal fun showSignInActions(sessionKnown: Boolean): Boolean = !sessionKnown

/** The demo never creates or loads the dashboard WebView. */
internal fun showDashboardWeb(phase: Phase, demo: Boolean): Boolean {
    return !demo && phase != Phase.SignedOut
}

internal fun signInWaitExpired(startedAtEpochMs: Long, nowEpochMs: Long, waitMs: Long = SIGN_IN_WAIT_MS): Boolean {
    if (startedAtEpochMs <= 0L) return false
    return nowEpochMs - startedAtEpochMs >= waitMs
}

class UsageViewModel(app: Application) : AndroidViewModel(app) {
    private val store = UsageStore(app)

    var snapshot by mutableStateOf(store.load())
        private set
    var phase by mutableStateOf(if (snapshot == null) Phase.SignedOut else Phase.Ready)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var status by mutableStateOf(if (snapshot == null) StatusKind.Empty else StatusKind.Saved)
        private set
    var webTarget by mutableStateOf(GrokLinks.DASHBOARD)
        private set
    var loadToken by mutableIntStateOf(0)
        private set
    var scrollTick by mutableIntStateOf(0)
        private set
    var scrollTopTick by mutableIntStateOf(0)
        private set
    var demo by mutableStateOf(false)
        private set
    var readingUsage by mutableStateOf(false)
        private set
    var sessionKnown by mutableStateOf(false)
        private set
    var signInPaused by mutableStateOf(false)
        private set
    var providerHold by mutableStateOf(false)
        private set
    private var pausedAt = 0L
    private var leftDuringSignIn = false

    private var providerSince = 0L

    var accountLabel by mutableStateOf(store.loadAccount())
        private set
    var notifyEnabled by mutableStateOf(store.loadNotify())
        private set
    private var accountPageLeft = false
    private var pendingBot: BotMeter? = null

    init {
        val saved = snapshot
        if (saved != null && saved.segments.isEmpty() && saved.botPercent == null) {
            store.forgetSnapshot()
            snapshot = null
            if (phase == Phase.Ready) phase = Phase.SignedOut
        }
        if (hasStoredSession()) resumeSignIn(fromSession = true)
    }

    fun chooseNotify(enabled: Boolean) {
        if (notifyEnabled == enabled) return
        notifyEnabled = enabled
        store.saveNotify(enabled)
        UsageRefresh.sync(getApplication())
    }

    fun onAccountName(name: String) {
        val clean = name.trim()
        if (!clean.contains('@') || clean.any { it.isWhitespace() }) return
        if (accountLabel == clean) return
        accountLabel = clean
        store.saveAccount(clean)
    }

    fun leaveAccountPage() {
        if (accountPageLeft) return
        if (!webTarget.startsWith("https://accounts.x.ai")) return
        accountPageLeft = true
        webTarget = GrokLinks.DASHBOARD
        loadToken++
    }

    fun notePage(url: String?) {
        val host = url?.let { ch.castellon.grok.usage.session.hostOf(it) }.orEmpty()
        val hold = host.isNotEmpty() && ch.castellon.grok.usage.session.holdsSignIn(host)
        if (hold && !providerHold) {
            providerSince = System.currentTimeMillis()
        } else if (!hold && providerHold && providerSince > 0L) {
            val started = store.signInStartedAt()
            if (started > 0L) {
                store.markSignInStarted(started + (System.currentTimeMillis() - providerSince))
            }
            providerSince = 0L
        }
        providerHold = hold
    }

    fun noteLeftDuringSignIn() {
        if (phase == Phase.SigningIn) leftDuringSignIn = true
    }

    fun returnedFromProvider(): Boolean {
        if (phase != Phase.SigningIn || !leftDuringSignIn) return false
        leftDuringSignIn = false
        store.markSignInStarted(System.currentTimeMillis())
        return true
    }

    fun pauseSignInClock(paused: Boolean) {
        if (paused) {
            if (phase != Phase.SigningIn || signInPaused) return
            pausedAt = System.currentTimeMillis()
            signInPaused = true
            return
        }
        if (!signInPaused) return
        val started = store.signInStartedAt()
        if (started > 0L && pausedAt > 0L) {
            store.markSignInStarted(started + (System.currentTimeMillis() - pausedAt))
        }
        pausedAt = 0L
        signInPaused = false
    }

    fun resumeSignIn(fromSession: Boolean) {
        if (fromSession) sessionKnown = true
        if (fromSession && accountLabel == null && !accountPageLeft) {
            webTarget = GrokLinks.ACCOUNT
            loadToken++
            if (snapshot != null) {
                readingUsage = false
                phase = Phase.Ready
            } else if (phase != Phase.SigningIn) {
                leaveDemo()
                readingUsage = true
                phase = Phase.SigningIn
            }
            return
        }
        if (snapshot != null && sessionKnown) {
            if (refreshing || phase == Phase.SigningIn) return
            store.clearSignInStarted()
            readingUsage = false
            phase = Phase.Ready
            refresh()
            return
        }
        if (phase == Phase.SigningIn) return
        leaveDemo()
        store.markSignInStarted(System.currentTimeMillis())
        readingUsage = true
        webTarget = GrokLinks.DASHBOARD
        phase = Phase.SigningIn
    }

    fun startSignIn() {
        leaveDemo()
        readingUsage = true
        store.markSignInStarted(System.currentTimeMillis())
        webTarget = GrokLinks.DASHBOARD
        loadToken++
        refreshing = false
        status = StatusKind.Empty
        phase = Phase.SigningIn
    }

    fun hasUnfinishedSignIn(): Boolean = store.signInStartedAt() > 0L

    fun hasStoredSession(): Boolean = UsageRefresh.hasStoredSession()


    fun signInRemainingMs(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val started = store.signInStartedAt()
        if (started <= 0L) return SIGN_IN_WAIT_MS
        return (SIGN_IN_WAIT_MS - (nowEpochMs - started)).coerceAtLeast(0L)
    }

    fun abandonStuckSignIn() {
        store.clearSignInStarted()
        refreshing = false
        webTarget = GrokLinks.DASHBOARD
        loadToken++
        status = StatusKind.SignInTimedOut
        phase = if (snapshot == null) Phase.SignedOut else Phase.Ready
    }

    fun openOfficial(url: String) {
        leaveDemo()
        webTarget = url
        loadToken++
        refreshing = false
        phase = Phase.Official
    }

    fun closeWeb() {
        store.clearSignInStarted()
        readingUsage = false
        refreshing = false
        phase = if (snapshot == null) Phase.SignedOut else Phase.Ready
    }

    fun onAccountShell() {
        if (phase != Phase.SigningIn) return
        readingUsage = true
    }

    fun onSessionConfirmed() {
        sessionKnown = true
        if (phase == Phase.SigningIn) readingUsage = true
    }

    fun onNeedLogin() {
        if (phase != Phase.SigningIn || sessionKnown) return
        readingUsage = false
    }

    fun onUsageUnread() {
        if (phase == Phase.Official || demo) return
        readingUsage = false
        refreshing = false
        store.clearSignInStarted()
        if (snapshot != null) {
            status = StatusKind.RefreshFailed
            phase = Phase.Ready
            return
        }
        if (!sessionKnown) return
        android.util.Log.i("GrokUsage", "unread no-snapshot")
        status = StatusKind.NeedUsagePage
        phase = Phase.Ready
    }

    fun refresh() {
        leaveDemo()
        if (snapshot == null && !sessionKnown) {
            startSignIn()
            return
        }
        webTarget = GrokLinks.DASHBOARD
        loadToken++
        if (snapshot == null) {
            readingUsage = true
            refreshing = false
            phase = Phase.SigningIn
        } else {
            refreshing = true
        }
    }

    fun onBotMeter(meter: BotMeter) {
        if (demo) return
        pendingBot = meter
        val current = snapshot ?: return
        if (current.botPercent != null) return
        val updated = current.copy(
            botPercent = meter.percent,
            botResetRaw = meter.resetRaw,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        snapshot = updated
        store.save(updated)
        status = StatusKind.Updated
    }

    fun onParsed(parsed: ParsedUsage) {
        if (demo) return
        val snap = UsageSnapshot.from(parsed, pendingBot, System.currentTimeMillis())
        if (parsed.botPercent != null) pendingBot = null
        snapshot = snap
        store.save(snap)
        store.clearSignInStarted()
        sessionKnown = true
        readingUsage = false
        refreshing = false
        status = StatusKind.Updated
        if (phase == Phase.SigningIn || phase == Phase.SignedOut) phase = Phase.Ready
        UsageRefresh.sync(getApplication())
    }

    fun onGiveUp() {
        if (demo || phase == Phase.SignedOut) return
        if (phase == Phase.Official) return
        if (phase == Phase.SigningIn && sessionKnown) return
        refreshing = false
        status = if (snapshot != null) StatusKind.RefreshFailed else StatusKind.NeedUsagePage
    }

    fun signOut() {
        leaveDemo()
        readingUsage = false
        sessionKnown = false
        accountLabel = null
        accountPageLeft = false
        pendingBot = null
        snapshot = null
        store.clearSignInStarted()
        store.clear()
        UsageRefresh.cancel(getApplication())
        refreshing = false
        status = StatusKind.Empty
        webTarget = GrokLinks.DASHBOARD
        loadToken++
        phase = Phase.SignedOut
    }

    fun startDemo() {
        demo = true
        snapshot = DemoData.snapshot
        phase = Phase.Ready
        status = StatusKind.Saved
        refreshing = false
    }

    fun exitDemo() {
        if (!demo) return
        leaveDemo()
        refreshing = false
        status = if (snapshot == null) StatusKind.Empty else StatusKind.Saved
        phase = if (snapshot == null) Phase.SignedOut else Phase.Ready
    }

    private fun leaveDemo() {
        if (!demo) return
        demo = false
        snapshot = store.load()
    }

    fun requestScroll() {
        scrollTick++
    }

    fun requestScrollTop() {
        scrollTopTick++
    }

}
