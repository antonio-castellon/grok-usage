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
)

data class UsageSnapshot(
    val overallPercent: Float?,
    val overallResetRaw: String?,
    val segments: List<UsageSegment>,
    val botPercent: Float?,
    val botResetRaw: String?,
    val updatedAtEpochMs: Long,
) {
    companion object {
        fun from(parsed: ParsedUsage, nowEpochMs: Long) = UsageSnapshot(
            overallPercent = parsed.overallPercent,
            overallResetRaw = parsed.overallResetRaw,
            segments = parsed.segments,
            botPercent = parsed.botPercent,
            botResetRaw = parsed.botResetRaw,
            updatedAtEpochMs = nowEpochMs,
        )
    }
}

object GrokLinks {
    const val DASHBOARD = "https://grok.com/dashboard"
    const val BILLING = "https://grok.com/dashboard/billing"
    const val LEGACY_BILLING = "https://grok.com/?_s=billing"
}
