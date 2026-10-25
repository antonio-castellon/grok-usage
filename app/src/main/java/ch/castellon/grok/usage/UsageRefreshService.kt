package ch.castellon.grok.usage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ch.castellon.grok.usage.session.pullUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short foreground read. A background WebView on this phone does not load the usage
 * page unless the process is in the foreground. The notice is removed when the read ends.
 */
class UsageRefreshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stopped = AtomicBoolean(false)
    private var latestStartId = 0
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        val posted = runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notice(), type)
        }
        if (posted.isFailure) {
            android.util.Log.i(
                "GrokUsage",
                "background service ${posted.exceptionOrNull()?.javaClass?.simpleName}",
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        wake = runCatching {
            val power = getSystemService(PowerManager::class.java)
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grokusage:refresh").apply {
                setReferenceCounted(false)
                acquire(100_000L)
            }
        }.getOrNull()
        android.util.Log.i("GrokUsage", "background service up")
        scope.launch {
            try {
                runCatching { pullUsage(applicationContext) }.onFailure {
                    android.util.Log.i("GrokUsage", "background read ${it.javaClass.simpleName}")
                }
            } finally {
                running.set(false)
                releaseWake()
                halt()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        android.util.Log.i("GrokUsage", "background service timeout")
        halt()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.i("GrokUsage", "background service timeout")
        halt()
    }

    override fun onDestroy() {
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    private fun halt() {
        if (!stopped.compareAndSet(false, true)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    private fun releaseWake() {
        val held = wake ?: return
        wake = null
        runCatching { if (held.isHeld) held.release() }
    }

    private fun notice() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_usage)
        .setContentTitle(getString(R.string.refresh_running))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                3,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setSilent(true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .build()
        .also { ensureChannel(this) }

    companion object {
        private val running = AtomicBoolean(false)
        private const val CHANNEL = "usage_refresh"
        private const val NOTIF_ID = 43

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            val channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.refresh_channel),
                NotificationManager.IMPORTANCE_MIN,
            )
            channel.setShowBadge(false)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
    }
}
