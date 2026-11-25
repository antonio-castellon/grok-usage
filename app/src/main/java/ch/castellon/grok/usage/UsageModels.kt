package ch.castellon.grok.usage

data class UsageSegment(
    val label: String,
    val percent: Float,
)

data class ParsedUsage(
    val overallPercent: Float?,
    val overallResetRaw: String?,
    val segments: List<UsageSegment>,
    val botPercent: Float?,
    val botResetRaw: String?,
) {
    /** Combines a structured parse and a text/bot parse without dropping fields from either. */
    fun mergeWith(fallback: ParsedUsage?): ParsedUsage {
        if (fallback == null) return this
        val useOverall = overallPercent != null
        val useBot = botPercent != null
        return ParsedUsage(
            overallPercent = overallPercent ?: fallback.overallPercent,
            overallResetRaw = if (useOverall) overallResetRaw ?: fallback.overallResetRaw else fallback.overallResetRaw ?: overallResetRaw,
            segments = if (segments.isNotEmpty()) segments else fallback.segments,
            botPercent = botPercent ?: fallback.botPercent,
            botResetRaw = if (useBot) botResetRaw ?: fallback.botResetRaw else fallback.botResetRaw ?: botResetRaw,
        )
    }
}

data class UsageSnapshot(
    val overallPercent: Float?,
    val overallResetRaw: String?,
    val segments: List<UsageSegment>,
    val botPercent: Float?,
    val botResetRaw: String?,
    val updatedAtEpochMs: Long,
) {
    companion object {
        /** One rule for the app and the background pull: the page's bot card wins over the separate meter. */
        fun from(parsed: ParsedUsage, bot: BotMeter?, nowEpochMs: Long) = UsageSnapshot(
            overallPercent = parsed.overallPercent,
            overallResetRaw = parsed.overallResetRaw,
            segments = parsed.segments,
            botPercent = parsed.botPercent ?: bot?.percent,
            botResetRaw = if (parsed.botPercent != null) parsed.botResetRaw else bot?.resetRaw ?: parsed.botResetRaw,
            updatedAtEpochMs = nowEpochMs,
        )

        /** Partial snapshot when only the Grok Bot meter is available. */
        fun partialBot(bot: BotMeter, nowEpochMs: Long) = UsageSnapshot(
            overallPercent = null,
            overallResetRaw = null,
            segments = emptyList(),
            botPercent = bot.percent,
            botResetRaw = bot.resetRaw,
            updatedAtEpochMs = nowEpochMs,
        )
    }
}

object GrokLinks {
    const val DASHBOARD = "https://grok.com/dashboard"
    const val USAGE = "https://grok.com/?_s=usage"
    const val SIGN_IN = "https://accounts.x.ai/sign-in?redirect=grok"
    const val ACCOUNT = "https://accounts.x.ai/account"
    const val BILLING = "https://grok.com/dashboard/billing"
    const val LEGACY_BILLING = "https://grok.com/?_s=billing"
}
