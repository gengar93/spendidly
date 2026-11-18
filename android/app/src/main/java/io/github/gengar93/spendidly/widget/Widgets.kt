package io.github.gengar93.spendidly.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.gengar93.spendidly.SpendidlyApplication
import io.github.gengar93.spendidly.MainActivity
import io.github.gengar93.spendidly.R
import io.github.gengar93.spendidly.ui.isLarge
import io.github.gengar93.spendidly.ui.largeThreshold
import io.github.gengar93.spendidly.ui.wholeRupees
import io.github.gengar93.spendidly.ui.appColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Extra on the launch intent telling MainActivity what to open. */
const val EXTRA_OPEN = "spendidly.open"
const val OPEN_ADD = "add"
const val OPEN_CHAT = "chat"
const val OPEN_VOICE = "voice"

/** [voice] is true when a saved key supports voice, so the chat button becomes a mic. */
private data class WidgetData(val today: Long, val week: Long, val days: List<Long>, val largeDays: Set<Int>, val todayIndex: Int, val voice: Boolean)

private suspend fun loadData(context: Context): WidgetData = withContext(Dispatchers.IO) {
    val backend = (context.applicationContext as SpendidlyApplication).backend
    val items = runCatching { backend.expenses.snapshot() }.getOrDefault(emptyList())
    val today = LocalDate.now(ZoneId.systemDefault())
    val threshold = largeThreshold(items, today)
    val monday = today.with(DayOfWeek.MONDAY)
    val days = (0..6).map { i -> items.filter { it.date == monday.plusDays(i.toLong()) }.sumOf { it.amountPaise } }
    val large = (0..6).filter { i -> items.any { it.date == monday.plusDays(i.toLong()) && isLarge(it.amountPaise, threshold) } }.toSet()
    val voice = hasVoice(context)
    WidgetData(days[today.dayOfWeek.value - 1], days.sum(), days, large, today.dayOfWeek.value - 1, voice)
}

/** Whether a saved key can use a voice model, so Ask can start by voice. */
internal suspend fun hasVoice(context: Context): Boolean =
    runCatching { (context.applicationContext as SpendidlyApplication).backend.voiceOptions().isNotEmpty() }.getOrDefault(false)

/** Plausible figures for the picker previews: a Thursday, after a big Tuesday. */
private val sampleData = listOf(42_000L, 125_000L, 18_500L, 64_000L, 0L, 0L, 0L).let { days ->
    WidgetData(today = days[3], week = days.sum(), days = days, largeDays = setOf(1), todayIndex = 3, voice = false)
}

/** Same colours as the app, from the colour setting. */
private fun widgetColors(context: Context): androidx.glance.color.ColorProviders {
    val setting = (context.applicationContext as SpendidlyApplication).backend.colours
    return ColorProviders(light = appColorScheme(context, setting, dark = false), dark = appColorScheme(context, setting, dark = true))
}

private fun open(context: Context, what: String): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction("io.github.gengar93.spendidly.OPEN_$what")
        .putExtra(EXTRA_OPEN, what)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
)

/**
 * Bumped by [updateWidgets]. A widget session stays open for a while after an update, and updating an open
 * session only recomposes it, so each session reloads its data whenever this changes.
 */
private val refreshes = MutableStateFlow(0)

/** Base for the three widgets: loads totals and colours, then draws [Content], reloading both on each [updateWidgets]. */
abstract class SpendidlyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val loadedAt = refreshes.value
        val first = loadData(context)
        val firstColors = widgetColors(context)
        provideContent {
            val refresh by refreshes.collectAsState()
            var data by remember { mutableStateOf(first) }
            var colors by remember { mutableStateOf(firstColors) }
            LaunchedEffect(refresh) { if (refresh != loadedAt) { data = loadData(context); colors = widgetColors(context) } }
            GlanceTheme(colors = colors) { Content(context, data) }
        }
    }

    /** The widget picker's generated preview (Android 15+): sample figures in the current colours. See [publishWidgetPreviews]. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val colors = widgetColors(context)
        // Same Ask button as a placed widget: a mic when a voice model is set up, else the chat icon.
        val data = sampleData.copy(voice = hasVoice(context))
        provideContent { GlanceTheme(colors = colors) { Content(context, data) } }
    }

    @Composable
    internal abstract fun Content(context: Context, data: Any)
}

// ---- pieces ----

@Composable
private fun Label(text: String) {
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
}

@Composable
private fun Amount(paise: Long, size: Int) {
    Text(wholeRupees(paise), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = size.sp, fontWeight = FontWeight.Bold), maxLines = 1)
}

/** Filled pill button. [label] null makes it icon-only. */
@Composable
private fun Pill(action: Action, icon: Int, label: String?, filled: Boolean, height: Dp, modifier: GlanceModifier = GlanceModifier) {
    val bg = if (filled) GlanceTheme.colors.primary else GlanceTheme.colors.primaryContainer
    val fg = if (filled) GlanceTheme.colors.onPrimary else GlanceTheme.colors.onPrimaryContainer
    Row(
        modifier.height(height).cornerRadius(height / 2).background(bg).clickable(action),
        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(ImageProvider(icon), contentDescription = label ?: when (icon) { R.drawable.ic_w_plus -> "Add expense"; R.drawable.ic_w_mic -> "Ask by voice"; else -> "New chat" },
            colorFilter = ColorFilter.tint(fg), modifier = GlanceModifier.size(22.dp))
        if (label != null) {
            Spacer(GlanceModifier.width(8.dp))
            Text(label, style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
}

/** The Ask button: a mic that starts recording when voice is set up, else a chat bubble that opens the keyboard. */
@Composable
private fun AskPill(context: Context, data: WidgetData, label: String?, modifier: GlanceModifier) {
    if (data.voice) Pill(open(context, OPEN_VOICE), R.drawable.ic_w_mic, label, false, 52.dp, modifier)
    else Pill(open(context, OPEN_CHAT), R.drawable.ic_w_chat, label, false, 52.dp, modifier)
}

@Composable
private fun Bars(data: WidgetData, maxHeight: Dp) {
    val cap = (data.days.filterIndexed { i, _ -> i !in data.largeDays }.maxOrNull() ?: 0L).coerceAtLeast(1L) * 6 / 5
    // Glance allows at most 10 children per Row, so the gaps are padding on each bar's wrapper, not Spacers.
    Row(verticalAlignment = Alignment.Bottom, modifier = GlanceModifier.height(maxHeight)) {
        data.days.forEachIndexed { i, v ->
            val h = if (i > data.todayIndex) 6f else (v.toFloat() / cap).coerceIn(0.1f, 1f) * maxHeight.value
            val color: ColorProvider = when {
                i == data.todayIndex -> GlanceTheme.colors.primary
                i in data.largeDays -> GlanceTheme.colors.tertiaryContainer
                else -> GlanceTheme.colors.surfaceVariant
            }
            Box(GlanceModifier.padding(start = if (i > 0) 6.dp else 0.dp)) {
                Box(GlanceModifier.width(12.dp).height(h.dp).cornerRadius(6.dp).background(color)) {}
            }
        }
    }
}

@Composable
private fun Frame(context: Context, padding: Dp, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(GlanceTheme.colors.widgetBackground)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            .padding(padding),
    ) { content() }
}

// ---- widgets ----

/** 4 × 2: today, this week, week bars, labelled buttons. */
class WeekWidget : SpendidlyWidget() {
    @Composable
    override fun Content(context: Context, data: Any) {
        val d = data as WidgetData
        Frame(context, 18.dp) {
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(GlanceModifier.defaultWeight()) {
                        Label("Spent today")
                        Amount(d.today, 32)
                        Label("This week ${wholeRupees(d.week)}")
                    }
                    Bars(d, 56.dp)
                }
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth()) {
                    Pill(open(context, OPEN_ADD), R.drawable.ic_w_plus, "Add expense", true, 52.dp, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    AskPill(context, d, "Ask", GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

/** 4 × 1: today with this week beneath, round buttons on the right. */
class TodayWidget : SpendidlyWidget() {
    @Composable
    override fun Content(context: Context, data: Any) {
        val d = data as WidgetData
        Frame(context, 10.dp) {
            Row(GlanceModifier.fillMaxSize().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Label("Today")
                    Amount(d.today, 24)
                    Label("Week ${wholeRupees(d.week)}")
                }
                Pill(open(context, OPEN_ADD), R.drawable.ic_w_plus, null, true, 52.dp, GlanceModifier.width(52.dp))
                Spacer(GlanceModifier.width(8.dp))
                AskPill(context, d, null, GlanceModifier.width(52.dp))
            }
        }
    }
}

/** 2 × 2: today, icon-only buttons. */
class SquareWidget : SpendidlyWidget() {
    @Composable
    override fun Content(context: Context, data: Any) {
        val d = data as WidgetData
        Frame(context, 16.dp) {
            Column(GlanceModifier.fillMaxSize()) {
                Label("Spent today")
                Amount(d.today, 30)
                Spacer(GlanceModifier.defaultWeight())
                Row(GlanceModifier.fillMaxWidth()) {
                    Pill(open(context, OPEN_ADD), R.drawable.ic_w_plus, null, true, 52.dp, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(8.dp))
                    AskPill(context, d, null, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

class WeekWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeekWidget() }
class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TodayWidget() }
class SquareWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SquareWidget() }

/** Redraws every placed Spendidly widget, e.g. after expenses change, then refreshes the picker previews if they're stale. */
suspend fun updateWidgets(context: Context) {
    refreshes.update { it + 1 }
    WeekWidget().updateAll(context)
    TodayWidget().updateAll(context)
    SquareWidget().updateAll(context)
    publishWidgetPreviews(context)
}
