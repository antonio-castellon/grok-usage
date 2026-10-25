package ch.castellon.grok.usage

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.castellon.grok.usage.session.UsageRefreshWorker
import ch.castellon.grok.usage.session.isGrokHost
import java.util.concurrent.TimeUnit

/** True while the usage screen is in the foreground. The screen already reads usage then. */
internal object UsageVisibility {
    @Volatile
    var visible: Boolean = false
}

/** Background pull every 15 minutes (the Android minimum) while something shows the figures. */
object UsageRefresh {
    const val WORK_NAME = "usage-refresh"
    const val KICK_NAME = "usage-refresh-now"
    const val ACTION = "ch.castellon.grok.usage.REFRESH_USAGE"
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
        if (wantedNow(app)) {
            enqueue(app)
            arm(app)
        } else {
            cancel(app)
            disarm(app)
        }
    }

    /**
     * Starts a short foreground read so the page can load after the app is closed.
     * Returns true when no worker fallback is needed.
     */
    fun launch(context: Context): Boolean {
        val app = context.applicationContext
        if (UsageVisibility.visible) return true
        if (!wantedNow(app)) {
            android.util.Log.i("GrokUsage", "background launch idle")
            return true
        }
        val started = runCatching {
            ContextCompat.startForegroundService(app, Intent(app, UsageRefreshService::class.java))
        }.onFailure {
            android.util.Log.i("GrokUsage", "background start ${it.javaClass.simpleName}")
        }.isSuccess
        if (!started) kick(app)
        return started
    }

    /** One immediate pull. A pull already waiting is left as it is. */
    fun kick(context: Context) {
        val app = context.applicationContext
        if (!wantedNow(app)) return
        val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(KICK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun wantedNow(context: Context): Boolean {
        val store = UsageStore(context)
        return wanted(hasStoredSession(), store.load() != null, widgetCount(context), store.loadNotify())
    }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(
            INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            5,
            TimeUnit.MINUTES,
        )
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    private fun arm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = SystemClock.elapsedRealtime() + INTERVAL_MINUTES * 60_000L
        val callback = pending(context)
        val canExact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        if (canExact) {
            val exact = runCatching {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, callback)
            }
            if (exact.isSuccess) {
                android.util.Log.i("GrokUsage", "background alarm exact")
                return
            }
            android.util.Log.i(
                "GrokUsage",
                "background alarm ${exact.exceptionOrNull()?.javaClass?.simpleName}",
            )
        }
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, callback)
        android.util.Log.i("GrokUsage", "background alarm idle")
    }

    private fun disarm(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, RefreshAlarm::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Wakes the process on a 15-minute alarm and after boot, then pulls usage. */
class RefreshAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != UsageRefresh.ACTION) return
        UsageRefresh.sync(context)
        UsageRefresh.launch(context)
    }
}

/** Hosts the background page may open while it follows the session back to grok.com. */
internal fun backgroundMayNavigate(host: String): Boolean {
    val normalized = host.lowercase()
    return isGrokHost(normalized) || normalized == "accounts.x.ai" || normalized == "accounts.spacex.ai"
}