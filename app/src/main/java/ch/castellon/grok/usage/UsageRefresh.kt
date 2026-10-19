package ch.castellon.grok.usage

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.webkit.CookieManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.castellon.grok.usage.session.UsageRefreshWorker
import java.util.concurrent.TimeUnit

/** Background pull every 15 minutes (the Android minimum) while something shows the figures. */
object UsageRefresh {
    const val WORK_NAME = "usage-refresh"
    const val INTERVAL_MINUTES = 15L

    private val sessionCookieUrls = arrayOf(
        "https://grok.com",
        "https://grok.com/dashboard",
        "https://accounts.x.ai",
        "https://accounts.spacex.ai",
    )

    /** Pure rule: pull only for a signed-in user with figures on a widget or the status notification. */
    internal fun wanted(hasSession: Boolean, hasSnapshot: Boolean, widgets: Int, notify: Boolean): Boolean {
        return hasSession && hasSnapshot && (widgets > 0 || notify)
    }

    fun hasStoredSession(): Boolean {
        val jar = runCatching { CookieManager.getInstance() }.getOrNull() ?: return false
        return sessionCookieUrls.any { url ->
            cookieLooksSignedIn(runCatching { jar.getCookie(url) }.getOrNull())
        }
    }

    fun widgetCount(context: Context): Int {
        val manager = AppWidgetManager.getInstance(context) ?: return 0
        return listOf(UsageWidget::class.java, ClockWidget::class.java).sumOf {
            runCatching { manager.getAppWidgetIds(ComponentName(context, it)).size }.getOrDefault(0)
        }
    }

    /** Enqueues or cancels the periodic pull to match the current state. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val store = UsageStore(app)
        val want = wanted(hasStoredSession(), store.load() != null, widgetCount(app), store.loadNotify())
        if (want) enqueue(app) else cancel(app)
    }

    private fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}