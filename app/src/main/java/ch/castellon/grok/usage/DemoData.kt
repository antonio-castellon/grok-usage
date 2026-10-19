package ch.castellon.grok.usage

/**
 * Round, obviously fake numbers for the demo screen (store reviewers)
 * and the debug preview. Never written to UsageStore.
 */
object DemoData {
    private const val RESET = "Jan 1, 2030, 12:00 PM"

    val snapshot = UsageSnapshot(
        overallPercent = 42f,
        overallResetRaw = RESET,
        segments = listOf(
            UsageSegment("Grok Build", 25f),
            UsageSegment("Chat", 17f),
        ),
        botPercent = 60f,
        botResetRaw = RESET,
        updatedAtEpochMs = 1_893_499_200_000L,
    )
}