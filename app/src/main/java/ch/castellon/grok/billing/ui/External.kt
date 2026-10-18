package ch.castellon.grok.billing.ui

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.appwidget.AppWidgetProvider
import ch.castellon.grok.billing.ClockWidget
import ch.castellon.grok.billing.R
import ch.castellon.grok.billing.UsageWidget

fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // The phone has no app that can open https links.
    }
}

fun openGrokUsage(context: Context) {
    val usage = Intent(Intent.ACTION_VIEW, Uri.parse("xai-grok://usage")).apply {
        setPackage("ai.x.grok")
    }
    try {
        context.startActivity(usage)
        return
    } catch (_: ActivityNotFoundException) {
        // The installed Grok app did not accept the Usage link.
    }
    val launch = context.packageManager.getLaunchIntentForPackage("ai.x.grok")
    if (launch != null) {
        context.startActivity(launch)
        return
    }
    openExternal(context, "https://grok.com/")
}

fun pinUsageWidget(context: Context) {
    pinHomeWidget(context, UsageWidget::class.java)
}

fun pinClockWidget(context: Context) {
    pinHomeWidget(context, ClockWidget::class.java)
}

private fun pinHomeWidget(context: Context, providerClass: Class<out AppWidgetProvider>) {
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, providerClass)
    if (Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
        manager.requestPinAppWidget(provider, null, null)
        return
    }
    Toast.makeText(context, R.string.widget_pin_unavailable, Toast.LENGTH_LONG).show()
}
