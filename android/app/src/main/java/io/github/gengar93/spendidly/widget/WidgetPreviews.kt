package io.github.gengar93.spendidly.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.glance.appwidget.GlanceAppWidgetManager
import io.github.gengar93.spendidly.SpendidlyApplication
import io.github.gengar93.spendidly.ui.appColorScheme
import io.github.gengar93.spendidly.ui.currency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val receivers = listOf(WeekWidgetReceiver::class, TodayWidgetReceiver::class, SquareWidgetReceiver::class)

/**
 * Publishes the widget picker's generated previews (Android 15+) when what they show has changed: the colours (including
 * the wallpaper's, for Auto), the currency or the installed build. The platform allows only a couple of calls per widget
 * an hour, so a widget is skipped while its preview is current, and a rate-limited one is retried on a later call.
 * Waits a moment first, so flicking through palettes, which cancels the caller, publishes only the last.
 */
internal suspend fun publishWidgetPreviews(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    delay(3_000)
    val app = context.applicationContext
    val prefs = app.getSharedPreferences("widget_previews", Context.MODE_PRIVATE)
    val signature = previewSignature(app)
    val platform = AppWidgetManager.getInstance(app)
    val glance = GlanceAppWidgetManager(app)
    for (receiver in receivers) {
        val key = receiver.java.name
        val provider = ComponentName(app, receiver.java)
        val present = runCatching { platform.getWidgetPreview(provider, null, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != null }.getOrDefault(false)
        if (present && prefs.getString(key, null) == signature) continue
        val result = try {
            glance.setWidgetPreviews(receiver)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit().putString(key, signature).apply()
    }
}

/** Everything a preview depends on, so it's republished only when one of them changes. */
private suspend fun previewSignature(context: Context): String {
    val setting = (context as SpendidlyApplication).backend.colours
    val colours = listOf(false, true).flatMap { dark ->
        appColorScheme(context, setting, dark).run {
            listOf(primary, onPrimary, primaryContainer, onPrimaryContainer, secondaryContainer, tertiaryContainer, surfaceVariant, onSurface, onSurfaceVariant)
        }
    }
    val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    return "$installed|${currency.name}|${hasVoice(context)}|" + colours.joinToString(",") { Integer.toHexString(it.toArgb()) }
}
