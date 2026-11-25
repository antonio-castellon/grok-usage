package ch.castellon.grok.usage.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import ch.castellon.grok.usage.ClockWidget
import ch.castellon.grok.usage.GrokLinks
import ch.castellon.grok.usage.R
import ch.castellon.grok.usage.UsageWidget

fun openExternal(context: Context, url: String) {
    val uri = Uri.parse(url)
    val browserPkg = findBrowserPackage(context)
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (browserPkg != null) setPackage(browserPkg)
    }
    try {
        context.startActivity(intent)
        return
    } catch (_: ActivityNotFoundException) {
        if (browserPkg != null) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }
}

fun openGrokUsage(context: Context) {
    openExternal(context, GrokLinks.USAGE)
}

private fun findBrowserPackage(context: Context): String? {
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val pm = context.packageManager
    val resolved = if (Build.VERSION.SDK_INT >= 33) {
        pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return resolved
        .mapNotNull { it.activityInfo?.packageName }
        .firstOrNull { pkg -> pkg != "ai.x.grok" && pkg != context.packageName }
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
