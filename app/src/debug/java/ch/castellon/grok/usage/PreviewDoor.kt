package ch.castellon.grok.usage

import android.content.Intent

object PreviewDoor {
    fun requested(intent: Intent?): Boolean {
        return intent?.getBooleanExtra("preview", false) == true
    }

    fun scrollRequested(intent: Intent?): Boolean {
        return intent?.getBooleanExtra("previewScroll", false) == true
    }
}