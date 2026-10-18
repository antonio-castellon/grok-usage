package ch.castellon.grok.billing

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

fun formatPercent(value: Float): String {
    val clamped = value.coerceIn(0f, 100f)
    val rounded = round(clamped * 10f) / 10f
    return if (abs(rounded - rounded.toInt()) < 0.05f) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}

fun bannerLabel(overall: Float?, bot: Float?): String? = when {
    overall != null && bot != null -> "Usage ${formatPercent(overall)}% · Bot ${formatPercent(bot)}%"
    overall != null -> "Usage ${formatPercent(overall)}%"
    bot != null -> "Bot ${formatPercent(bot)}%"
    else -> null
}

fun statusIconText(percent: Float?): String? = percent?.let { formatPercent(it) }

/** Status-bar glyph: the bot percent only, with a percent sign. */
fun statusBarFigure(bot: Float?, overall: Float? = null): String? =
    statusIconText(bot ?: overall)?.let { "$it%" }

/**
 * Big digits on top, the decimal and percent sign tucked underneath,
 * so the bot number fills the status-bar slot.
 */
fun statusIconGlyphs(percent: Float?): List<String> {
    val text = statusIconText(percent) ?: return emptyList()
    val dot = text.indexOf('.')
    if (dot < 0) return listOf("$text%")
    return listOf(text.substring(0, dot), text.substring(dot) + "%")
}

/** Both figures for the notification shade. Bot first. */
fun notificationLines(overall: Float?, bot: Float?): List<String> = listOfNotNull(
    statusIconText(bot)?.let { "Bot $it%" },
    statusIconText(overall)?.let { "Usage $it%" },
)

/** Clock widget: one line per figure, always both lines. */
fun widgetClockLines(overall: Float?, bot: Float?): Pair<String, String> {
    val botText = statusIconText(bot)?.let { "$it%" } ?: "—"
    val usageText = statusIconText(overall)?.let { "$it%" } ?: "—"
    return "Bot $botText" to "Usage $usageText"
}

fun resetLabel(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return "Resets $trimmed"
}

/**
 * Width of each colored piece as a fraction of the full bar.
 * Pool percentages (Build 9 + Chat 4 = 13) are drawn at face value.
 * Shares of the used amount (Build 70 + Chat 30 while the pool is 13% used)
 * are scaled into the used portion.
 */
fun barFractions(overall: Float, segmentPercents: List<Float>): List<Float> {
    val overallFraction = overall.coerceIn(0f, 100f) / 100f
    if (segmentPercents.isEmpty()) return listOf(overallFraction)
    val sum = segmentPercents.sum()
    if (sum <= 0f) return listOf(overallFraction)
    if (sum <= overall + 1.5f) {
        return segmentPercents.map { it.coerceAtLeast(0f) / 100f }
    }
    return segmentPercents.map { (it.coerceAtLeast(0f) / sum) * overallFraction }
}
