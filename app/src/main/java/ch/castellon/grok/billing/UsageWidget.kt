package ch.castellon.grok.billing

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = UsageStore(context).load()
        UsageWidgets.refresh(context, snapshot?.overallPercent, snapshot?.botPercent)
    }
}

class ClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = UsageStore(context).load()
        UsageWidgets.refresh(context, snapshot?.overallPercent, snapshot?.botPercent)
    }
}

object UsageWidgets {
    fun refresh(context: Context, overall: Float?, bot: Float?) {
        val manager = AppWidgetManager.getInstance(context)
        bind(
            context,
            manager,
            ComponentName(context, UsageWidget::class.java),
            R.layout.widget_usage,
            R.id.widget_root,
            R.id.widget_bot,
            R.id.widget_usage,
            statusIconText(bot)?.let { "$it%" } ?: "—",
            statusIconText(overall)?.let { "$it%" } ?: "—",
            1,
        )
        val lines = widgetClockLines(overall, bot)
        bind(
            context,
            manager,
            ComponentName(context, ClockWidget::class.java),
            R.layout.widget_clock,
            R.id.clock_root,
            R.id.clock_bot,
            R.id.clock_usage,
            lines.first,
            lines.second,
            2,
        )
    }

    private fun bind(
        context: Context,
        manager: AppWidgetManager,
        provider: ComponentName,
        layout: Int,
        root: Int,
        botView: Int,
        usageView: Int,
        botText: String,
        usageText: String,
        requestCode: Int,
    ) {
        val ids = manager.getAppWidgetIds(provider)
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(botView, botText)
        views.setTextViewText(usageView, usageText)
        val open = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(root, open)
        manager.updateAppWidget(ids, views)
    }
}
