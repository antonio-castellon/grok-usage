package ch.castellon.grok.billing

import android.content.Intent

object PreviewDoor {
    fun snapshotFrom(intent: Intent?): UsageSnapshot? {
        if (intent?.getBooleanExtra("preview", false) != true) return null
        return UsageSnapshot(
            overallPercent = 13f,
            overallResetRaw = "Oct 20, 2026, 10:48 PM",
            segments = listOf(
                UsageSegment("Grok Build", 9f),
                UsageSegment("Chat", 4f),
            ),
            botPercent = 65f,
            botResetRaw = "Oct 19, 2026, 3:00 AM",
            updatedAtEpochMs = 1_760_800_000_000L,
        )
    }

    fun scrollRequested(intent: Intent?): Boolean {
        return intent?.getBooleanExtra("previewScroll", false) == true
    }
}
