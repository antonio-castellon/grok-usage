package ch.castellon.grok.billing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.RemoteViews
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.min

object UsageNotifier {
    private const val CHANNEL = "usage_figures"
    private const val USAGE_ID = 41
    private const val BOT_ID = 42

    fun needsPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    fun canPost(context: Context): Boolean {
        if (needsPermission(context)) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(context: Context, overall: Float?, bot: Float?) {
        val lines = notificationLines(overall, bot)
        val figure = statusBarFigure(bot, overall)
        if (lines.isEmpty() || figure == null || !canPost(context)) return
        val manager = NotificationManagerCompat.from(context)
        // A second notice is folded into the app icon on this phone, which hides the digits.
        manager.cancel(BOT_ID)
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val glyphs = statusIconGlyphs(bot ?: overall)
        val bitmap = if (glyphs.isEmpty()) null else runCatching { numberIconBitmap(context, glyphs) }.getOrNull()
        val icon = bitmap?.let { IconCompat.createWithBitmap(it) }
        val content = RemoteViews(context.packageName, R.layout.notification_usage)
        content.setTextViewText(R.id.notification_bot, lines.first())
        content.setTextViewText(R.id.notification_usage, if (lines.size > 1) lines[1] else "")
        val built = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon ?: IconCompat.createWithResource(context, R.drawable.ic_stat_usage))
            .setContentTitle(lines.first())
            .setContentText(if (lines.size > 1) lines[1] else lines.first())
            .setCustomContentView(content)
            .setCustomBigContentView(content)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(USAGE_ID, built) }
            .onSuccess {
                android.util.Log.i(
                    "GrokUsage",
                    "status icon posted chars=${figure.length} lines=${lines.size} bitmap=${bitmap?.width ?: 0}",
                )
            }
            .onFailure { android.util.Log.i("GrokUsage", "status icon failed ${it.javaClass.simpleName}") }
    }

    /**
     * Square, because the status slot scales a wide bitmap down until the digits vanish.
     * The whole number is large; a decimal sits on a second line.
     */
    internal fun numberIconBitmap(context: Context, glyphs: List<String>): Bitmap {
        val side = statusBarHeight(context).toInt().coerceIn(96, 192)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val pad = side * 0.04f
        val gap = if (glyphs.size > 1) side * 0.02f else 0f
        val weights = if (glyphs.size == 1) listOf(1f) else listOf(0.74f, 0.26f)
        var top = pad
        glyphs.forEachIndexed { index, glyph ->
            val budget = (side - pad * 2f - gap * (glyphs.size - 1)) * weights[index]
            paint.textSize = fittedSize(paint, glyph, side - pad * 2f, budget)
            val metrics = paint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val baseline = top + (budget - textHeight) / 2f - metrics.ascent
            canvas.drawText(glyph, side / 2f, baseline, paint)
            top += budget + gap
        }
        return bitmap
    }

    private fun fittedSize(paint: Paint, text: String, maxWidth: Float, maxHeight: Float): Float {
        paint.textSize = 100f
        val metrics = paint.fontMetrics
        val unitHeight = (metrics.descent - metrics.ascent) / 100f
        val unitWidth = paint.measureText(text).coerceAtLeast(1f) / 100f
        return min(maxWidth / unitWidth, maxHeight / unitHeight).coerceAtLeast(1f)
    }

    private fun statusBarHeight(context: Context): Float {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        return if (px > 0) px.toFloat() else 24f * context.resources.displayMetrics.density
    }

    fun cancel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(USAGE_ID)
        manager.cancel(BOT_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.notify_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.notify_channel_body)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }
}
