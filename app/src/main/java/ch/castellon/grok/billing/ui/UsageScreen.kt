package ch.castellon.grok.billing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import ch.castellon.grok.billing.R
import ch.castellon.grok.billing.StatusKind
import ch.castellon.grok.billing.UsageSnapshot
import ch.castellon.grok.billing.barFractions
import ch.castellon.grok.billing.formatPercent
import ch.castellon.grok.billing.resetLabel
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsageScreen(
    snapshot: UsageSnapshot,
    refreshing: Boolean,
    status: StatusKind,
    scrollTick: Int,
    scrollTopTick: Int,
    accountLabel: String?,
    onRefresh: () -> Unit,
    onCardsVisible: (Boolean) -> Unit,
    onOpenGrok: () -> Unit,
    onSignInAgain: () -> Unit,
    allowSignInAgain: Boolean,
    onSignOut: () -> Unit,
    onPrivacy: () -> Unit,
    notifyEnabled: Boolean,
    onNotifyChange: (Boolean) -> Unit,
    onAddWidget: () -> Unit,
    onAddClockWidget: () -> Unit,
) {
    val scroll = rememberScrollState()
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(scrollTick) {
        if (scrollTick == 0) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(scrollTopTick) {
        if (scrollTopTick == 0) return@LaunchedEffect
        scroll.animateScrollTo(0)
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                viewportTop = coords.positionInRoot().y
                viewportBottom = viewportTop + coords.size.height
            },
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isRefreshing = refreshing,
                state = pullState,
                color = Ink,
                containerColor = CardBg,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!accountLabel.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.signed_in_as, accountLabel),
                    color = Muted,
                    fontSize = 14.sp,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.onGloballyPositioned { coords ->
                    val top = coords.positionInRoot().y
                    val bottom = top + coords.size.height
                    val visibleTop = max(top, viewportTop)
                    val visibleBottom = min(bottom, viewportBottom)
                    val visible = (visibleBottom - visibleTop).coerceAtLeast(0f)
                    val span = min(
                        coords.size.height.toFloat(),
                        (viewportBottom - viewportTop).coerceAtLeast(1f),
                    )
                    onCardsVisible(visible >= span * 0.55f)
                },
            ) {
                if (status == StatusKind.RefreshFailed || status == StatusKind.SignInTimedOut) {
                    Text(
                        text = stringResource(
                            if (status == StatusKind.SignInTimedOut) {
                                R.string.status_sign_in_timeout
                            } else {
                                R.string.status_refresh_failed
                            },
                        ),
                        color = Warning,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                    if (allowSignInAgain) {
                        TextButton(onClick = onSignInAgain) {
                            Text(stringResource(R.string.sign_in_again))
                        }
                    }
                }
                if (snapshot.overallPercent != null) {
                    UsageCard(
                        title = stringResource(R.string.usage),
                        percent = snapshot.overallPercent,
                        reset = resetLabel(snapshot.overallResetRaw),
                        fractions = barFractions(
                            snapshot.overallPercent,
                            snapshot.segments.map { it.percent },
                        ),
                        colors = if (snapshot.segments.isEmpty()) {
                            listOf(Meter)
                        } else {
                            snapshot.segments.map { segmentColor(it.label) }
                        },
                        legend = snapshot.segments.map { segment ->
                            segment.label to formatPercent(segment.percent)
                        },
                    )
                }
                if (snapshot.botPercent != null) {
                    UsageCard(
                        title = stringResource(R.string.bot_title),
                        percent = snapshot.botPercent,
                        reset = resetLabel(snapshot.botResetRaw),
                        fractions = listOf(snapshot.botPercent.coerceIn(0f, 100f) / 100f),
                        colors = listOf(BotBlue),
                        legend = emptyList(),
                    )
                }
                Text(
                    text = stringResource(R.string.last_updated, formatUpdated(snapshot.updatedAtEpochMs)),
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.data_note),
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            NotifyRow(enabled = notifyEnabled, onChange = onNotifyChange)
            LinkButton(stringResource(R.string.widget_add), onClick = onAddWidget)
            LinkButton(stringResource(R.string.widget_add_clock), onClick = onAddClockWidget)
            LinkButton(stringResource(R.string.open_grok_usage), onClick = onOpenGrok)
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sign_out))
            }
            TextButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.privacy))
            }
            Text(
                text = stringResource(R.string.not_affiliated),
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NotifyRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.notify_title),
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.notify_body),
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = BotBlue,
                uncheckedThumbColor = Ink,
                uncheckedTrackColor = Line,
                uncheckedBorderColor = Line,
            ),
        )
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageCard(
    title: String,
    percent: Float,
    reset: String?,
    fractions: List<Float>,
    colors: List<Color>,
    legend: List<Pair<String, String>>,
) {
    val barDescription = stringResource(R.string.cd_usage_bar, formatPercent(percent))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            color = Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${formatPercent(percent)}%",
                color = Ink,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.used),
                color = Muted,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }
        if (reset != null) {
            Text(
                text = reset,
                color = Muted,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SegmentBar(
            fractions = fractions,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .semantics { contentDescription = barDescription },
        )
        if (legend.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                legend.forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(segmentColor(label)),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "$label $value%",
                            color = Ink,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentBar(
    fractions: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.clip(RoundedCornerShape(99.dp)),
    ) {
        drawRect(Track)
        var x = 0f
        fractions.forEachIndexed { index, fraction ->
            val width = size.width * fraction.coerceIn(0f, 1f)
            if (width > 0f) {
                drawRect(
                    color = colors.getOrElse(index) { Meter },
                    topLeft = Offset(x, 0f),
                    size = Size(width, size.height),
                )
            }
            x += width
        }
    }
}

private fun formatUpdated(epochMs: Long): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US).format(zoned)
}
