package ch.castellon.grok.billing

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

enum class Phase { SignedOut, SigningIn, Ready, Official }

enum class StatusKind { Empty, Updated, Saved, RefreshFailed, NeedUsagePage }

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
    var holdPreview by mutableStateOf(false)
        private set

    fun startSignIn() {
        holdPreview = false
        webTarget = GrokLinks.DASHBOARD
        loadToken++
        refreshing = false
        phase = Phase.SigningIn
    }

    fun openOfficial(url: String) {
        holdPreview = false
        webTarget = url
        loadToken++
        refreshing = false
        phase = Phase.Official
    }

    fun closeWeb() {
        refreshing = false
        phase = if (snapshot == null) Phase.SignedOut else Phase.Ready
    }

    fun refresh() {
        holdPreview = false
        if (snapshot == null && phase == Phase.SignedOut) {
            startSignIn()
            return
        }
        webTarget = GrokLinks.DASHBOARD
        refreshing = true
        loadToken++
    }

    fun onParsed(parsed: ParsedUsage) {
        if (holdPreview) return
        snapshot = UsageSnapshot.from(parsed, System.currentTimeMillis())
        store.save(snapshot!!)
        refreshing = false
        status = StatusKind.Updated
        if (phase == Phase.SigningIn) phase = Phase.Ready
    }

    fun onGiveUp() {
        if (holdPreview || phase == Phase.SignedOut) return
        refreshing = false
        if (phase == Phase.Official) return
        status = if (snapshot != null) StatusKind.RefreshFailed else StatusKind.NeedUsagePage
    }

    fun signOut() {
        holdPreview = false
        snapshot = null
        store.clear()
        refreshing = false
        status = StatusKind.Empty
        webTarget = GrokLinks.DASHBOARD
        loadToken++
        phase = Phase.SignedOut
    }

    fun showPreview(preview: UsageSnapshot) {
        holdPreview = true
        snapshot = preview
        phase = Phase.Ready
        status = StatusKind.Saved
        refreshing = false
    }

    fun requestScroll() {
        scrollTick++
    }

    fun requestScrollTop() {
        scrollTopTick++
    }
}
