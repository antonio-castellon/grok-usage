package ch.castellon.grok.usage.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.castellon.grok.usage.BuildConfig
import ch.castellon.grok.usage.GrokLinks
import ch.castellon.grok.usage.Phase
import ch.castellon.grok.usage.PreviewDoor
import ch.castellon.grok.usage.R
import ch.castellon.grok.usage.StatusKind
import ch.castellon.grok.usage.UsageViewModel
import ch.castellon.grok.usage.showDashboardWeb
import ch.castellon.grok.usage.showReadingCover
import ch.castellon.grok.usage.showSessionHome
import ch.castellon.grok.usage.showSignInActions
import ch.castellon.grok.usage.showSignInWeb
import kotlinx.coroutines.delay
import ch.castellon.grok.usage.session.holdsSignIn
import ch.castellon.grok.usage.session.hostOf
import ch.castellon.grok.usage.UsageNotifier
import ch.castellon.grok.usage.UsageRefresh
import ch.castellon.grok.usage.UsageVisibility
import ch.castellon.grok.usage.UsageWidgets
import ch.castellon.grok.usage.bannerLabel
import ch.castellon.grok.usage.session.DashboardWebView
import ch.castellon.grok.usage.session.WebCallbacks

@Composable
fun AppRoot(vm: UsageViewModel = viewModel()) {
    val context = LocalContext.current
    val callbacks = remember { WebCallbacks() }
    val webRef = remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var cardsVisible by remember { mutableStateOf(true) }
    val waitNotifySettings = remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.chooseNotify(true)
    }
    fun enableNotify() {
        if (UsageNotifier.needsPermission(context)) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!UsageNotifier.canPost(context)) {
            waitNotifySettings.value = true
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
            return
        }
        vm.chooseNotify(true)
    }
    var privacyOpen by remember { mutableStateOf(false) }

    callbacks.onParsed = vm::onParsed
    callbacks.onBotMeter = vm::onBotMeter
    callbacks.onGiveUp = vm::onGiveUp
    callbacks.onProgress = { progress = it }
    callbacks.onPage = vm::notePage
    callbacks.onAccountShell = vm::onAccountShell
    callbacks.onNeedLogin = vm::onNeedLogin
    callbacks.onSessionConfirmed = vm::onSessionConfirmed
    callbacks.onUsageUnread = vm::onUsageUnread
    callbacks.sessionKnown = { vm.sessionKnown }
    callbacks.onAccountName = vm::onAccountName
    callbacks.onAccountPageRead = vm::leaveAccountPage
    callbacks.signingIn = { vm.phase == Phase.SigningIn }

    LaunchedEffect(Unit) {
        val intent = (context as? Activity)?.intent
        if (PreviewDoor.requested(intent)) vm.startDemo()
        if (PreviewDoor.scrollRequested(intent)) vm.requestScroll()
        UsageRefresh.sync(context)
        if (vm.hasStoredSession()) {
            vm.resumeSignIn(fromSession = true)
        } else if (vm.hasUnfinishedSignIn()) {
            vm.resumeSignIn(fromSession = false)
        } else if (intent?.getBooleanExtra("signin", false) == true) {
            vm.startSignIn()
        }
    }
    LaunchedEffect(vm.phase, vm.loadToken, vm.signInPaused, vm.providerHold, vm.sessionKnown) {
        if (vm.phase != Phase.SigningIn || vm.signInPaused || vm.providerHold || vm.sessionKnown) return@LaunchedEffect
        delay(vm.signInRemainingMs())
        if (vm.phase != Phase.SigningIn || vm.signInPaused || vm.providerHold || vm.sessionKnown) return@LaunchedEffect
        clearWebSession(webRef.value)
        vm.abandonStuckSignIn()
    }
    LaunchedEffect(vm.phase, vm.loadToken, vm.sessionKnown, vm.providerHold, vm.readingUsage) {
        if (vm.phase != Phase.SigningIn || vm.sessionKnown) return@LaunchedEffect
        if (vm.providerHold && !vm.readingUsage) return@LaunchedEffect
        delay(vm.signInWallRemainingMs())
        if (vm.phase != Phase.SigningIn || vm.sessionKnown) return@LaunchedEffect
        if (vm.providerHold && !vm.readingUsage) return@LaunchedEffect
        clearWebSession(webRef.value)
        vm.abandonStuckSignIn()
    }
    LaunchedEffect(vm.phase, vm.loadToken, vm.sessionKnown) {
        if (vm.phase != Phase.SigningIn || !vm.sessionKnown) return@LaunchedEffect
        delay(30_000)
        if (vm.phase == Phase.SigningIn && vm.sessionKnown && !vm.providerHold) vm.onUsageUnread()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    UsageVisibility.visible = false
                    CookieManager.getInstance().flush()
                    vm.noteLeftDuringSignIn()
                    vm.pauseSignInClock(true)
                    if (vm.phase != Phase.SigningIn) UsageRefresh.launch(context)
                }
                Lifecycle.Event.ON_START -> {
                    UsageVisibility.visible = true
                    vm.pauseSignInClock(false)
                    if (waitNotifySettings.value) {
                        waitNotifySettings.value = false
                        if (UsageNotifier.canPost(context)) vm.chooseNotify(true)
                    }
                    if (vm.returnedFromProvider()) {
                        val host = webRef.value?.url?.let { hostOf(it) }.orEmpty()
                        if (!holdsSignIn(host)) {
                            webRef.value?.postDelayed({ webRef.value?.reload() }, 700)
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm.loadToken) {
        progress = 0
        callbacks.dashboardHandoffUsed = false
    }

    val interactive = showSignInWeb(vm.phase, vm.readingUsage, vm.sessionKnown, vm.providerHold) || vm.phase == Phase.Official
    val sessionHome = showSessionHome(vm.phase, vm.sessionKnown, vm.snapshot != null)
    val readingCover = showReadingCover(vm.phase, vm.readingUsage, vm.sessionKnown)
    val showWeb = showDashboardWeb(vm.phase, vm.demo)
    val banner = bannerLabel(vm.snapshot?.overallPercent, vm.snapshot?.botPercent)
    LaunchedEffect(vm.notifyEnabled, banner, vm.demo) {
        if (vm.demo) return@LaunchedEffect
        UsageWidgets.refresh(context, vm.snapshot?.overallPercent, vm.snapshot?.botPercent)
        android.util.Log.i("GrokUsage", "status gate on=${vm.notifyEnabled} ready=${banner != null}")
        if (!vm.notifyEnabled || banner == null) {
            UsageNotifier.cancel(context)
            return@LaunchedEffect
        }
        if (!UsageNotifier.canPost(context)) {
            vm.chooseNotify(false)
            return@LaunchedEffect
        }
        UsageNotifier.show(context, vm.snapshot?.overallPercent, vm.snapshot?.botPercent)
    }

    BackHandler(enabled = vm.demo, onBack = vm::exitDemo)
    BackHandler(enabled = interactive) {
        val web = webRef.value
        if (web != null && web.canGoBack()) web.goBack() else vm.closeWeb()
    }

    if (privacyOpen) {
        AlertDialog(
            onDismissRequest = { privacyOpen = false },
            confirmButton = {
                TextButton(onClick = { privacyOpen = false }) {
                    Text(stringResource(R.string.close))
                }
            },
            title = { Text(stringResource(R.string.privacy)) },
            text = { Text(stringResource(R.string.privacy_body)) },
            containerColor = CardBg,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (interactive) {
            WebBar(
                title = if (vm.phase == Phase.Official) {
                    stringResource(R.string.official_page)
                } else {
                    stringResource(R.string.sign_in)
                },
                showDone = vm.phase == Phase.Official,
                onBack = {
                    val web = webRef.value
                    if (web != null && web.canGoBack()) web.goBack() else vm.closeWeb()
                },
                onDone = vm::closeWeb,
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = BotBlue,
                    trackColor = Line,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showWeb) {
                val density = LocalDensity.current.density
                val imeCss = (WindowInsets.ime.getBottom(LocalDensity.current) / density).toInt()
                DashboardWebView(
                    url = vm.webTarget,
                    loadToken = vm.loadToken,
                    imeBottomCssPx = if (interactive) imeCss else 0,
                    interactive = interactive,
                    callbacks = callbacks,
                    webRef = webRef,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!interactive && vm.snapshot != null) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    UsageScreen(
                        snapshot = vm.snapshot!!,
                        refreshing = vm.refreshing,
                        status = vm.status,
                        scrollTick = vm.scrollTick,
                        scrollTopTick = vm.scrollTopTick,
                        accountLabel = vm.accountLabel,
                        demo = vm.demo,
                        onExitDemo = vm::exitDemo,
                        onRefresh = { if (!vm.demo) vm.refresh() },
                        onCardsVisible = { cardsVisible = it },
                        onOpenGrok = { openGrokUsage(context) },
                        onSignInAgain = vm::startSignIn,
                        allowSignInAgain = showSignInActions(vm.sessionKnown),
                        notifyEnabled = vm.notifyEnabled,
                        onNotifyChange = { enabled ->
                            if (enabled) enableNotify() else vm.chooseNotify(false)
                        },
                        onAddWidget = { pinUsageWidget(context) },
                        onAddClockWidget = { pinClockWidget(context) },
                        onSignOut = {
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush()
                            }
                            webRef.value?.apply {
                                clearCache(true)
                                clearHistory()
                            }
                            vm.signOut()
                        },
                        onPrivacy = { privacyOpen = true },
                    )
                }
            } else if (!interactive && sessionHome) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    SignedInHome(
                        reading = vm.phase == Phase.SigningIn && vm.readingUsage,
                        accountLabel = vm.accountLabel,
                        onRefresh = vm::refresh,
                        onOpenGrok = { openGrokUsage(context) },
                        onSignOut = {
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().flush()
                            }
                            webRef.value?.apply {
                                clearCache(true)
                                clearHistory()
                            }
                            vm.signOut()
                        },
                        onPrivacy = { privacyOpen = true },
                    )
                }
            } else if (!interactive && !readingCover) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    SignInScreen(
                        onSignIn = vm::startSignIn,
                        onDemo = vm::startDemo,
                        onOpenOfficial = { openExternal(context, GrokLinks.DASHBOARD) },
                        onPrivacy = { privacyOpen = true },
                        notice = if (vm.status == StatusKind.SignInTimedOut) {
                            stringResource(R.string.status_sign_in_timeout)
                        } else {
                            null
                        },
                    )
                }
            }
            if (readingCover) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.reading_usage),
                        color = Ink,
                        fontSize = 16.sp,
                    )
                }
            }
            if (banner != null && !interactive && vm.snapshot != null && !cardsVisible) {
                UsageBanner(
                    label = banner,
                    onTap = vm::requestScrollTop,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        Text(
            text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            color = Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
    }
}

private fun clearWebSession(web: WebView?) {
    CookieManager.getInstance().removeAllCookies {
        CookieManager.getInstance().flush()
    }
    web?.apply {
        stopLoading()
        loadUrl("about:blank")
        clearCache(true)
        clearHistory()
    }
}

@Composable
private fun WebBar(
    title: String,
    showDone: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Text(stringResource(R.string.back))
        }
        if (showDone) {
            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun UsageBanner(
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_banner)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color(0xFF101012))
            .clickable(onClick = onTap)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
