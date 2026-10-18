package ch.castellon.grok.billing

import android.content.Intent

@Suppress("UNUSED_PARAMETER")
object PreviewDoor {
    fun snapshotFrom(intent: Intent?): UsageSnapshot? = null

    fun scrollRequested(intent: Intent?): Boolean = false
}
