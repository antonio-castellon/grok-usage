package ch.castellon.grok.usage.ui

import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.castellon.grok.usage.GrokLinks
import ch.castellon.grok.usage.Phase
import ch.castellon.grok.usage.PreviewDoor
import ch.castellon.grok.usage.R
import ch.castellon.grok.usage.StatusKind
import ch.castellon.grok.usage.UsageViewModel
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
    var privacyOpen by remember { mutableStateOf(false) }

    callbacks.onParsed = vm::onParsed
    callbacks.onGiveUp = vm::onGiveUp
    callbacks.onProgress = { progress = it }

    LaunchedEffect(Unit) {
        val intent = (context as? Activity)?.intent
        PreviewDoor.snapshotFrom(intent)?.let { vm.showPreview(it) }
        if (PreviewDoor.scrollRequested(intent)) vm.requestScroll()
    }
    LaunchedEffect(vm.loadToken) { progress = 0 }

    val webOnTop = vm.phase == Phase.SigningIn || vm.phase == Phase.Official
    val showWeb = !vm.holdPreview && vm.phase != Phase.SignedOut
    val banner = bannerLabel(vm.snapshot?.overallPercent, vm.snapshot?.botPercent)

    BackHandler(enabled = webOnTop) {
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
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
    ) {
        if (webOnTop) {
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
            if (vm.phase == Phase.SigningIn && vm.status == StatusKind.NeedUsagePage) {
                Text(
                    text = stringResource(R.string.status_need_page),
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showWeb) {
                DashboardWebView(
                    url = vm.webTarget,
                    loadToken = vm.loadToken,
                    callbacks = callbacks,
                    webRef = webRef,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!webOnTop) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
                    val snapshot = vm.snapshot
                    if (snapshot == null) {
                        SignInScreen(
                            onSignIn = vm::startSignIn,
                            onOpenOfficial = { openExternal(context, GrokLinks.DASHBOARD) },
                            onPrivacy = { privacyOpen = true },
                        )
                    } else {
                        UsageScreen(
                            snapshot = snapshot,
                            refreshing = vm.refreshing,
                            status = vm.status,
                            scrollTick = vm.scrollTick,
                            scrollTopTick = vm.scrollTopTick,
                            onRefresh = vm::refresh,
                            onCardsVisible = { cardsVisible = it },
                            onOpen = vm::openOfficial,
                            onSignInAgain = vm::startSignIn,
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
                }
            }
            if (banner != null && !webOnTop && vm.snapshot != null && !cardsVisible) {
                UsageBanner(
                    label = banner,
                    onTap = vm::requestScrollTop,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
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
            .height(52.dp)
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
