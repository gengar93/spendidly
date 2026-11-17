package io.github.gengar93.tally.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gengar93.tally.backend.ExpenseItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** With fewer expenses than this there is no large/everyday split. */
const val LARGE_MIN_ENTRIES = 30

/**
 * The amount from which a single expense counts as large and is kept out of everyday figures: about the
 * 95th percentile of the last 90 days (all data when those hold too few), rounded to 1, 2 or 5 × 10ⁿ whole
 * units. Null with fewer than [LARGE_MIN_ENTRIES] expenses, when everything counts as everyday.
 */
fun largeThreshold(items: List<ExpenseItem>, today: LocalDate): Long? {
    val recent = items.filter { it.date > today.minusDays(90) && !it.date.isAfter(today) }
    val sample = (if (recent.size >= LARGE_MIN_ENTRIES) recent else items).map { it.amountPaise }.sorted()
    if (sample.size < LARGE_MIN_ENTRIES) return null
    val p95 = sample[(kotlin.math.ceil(sample.size * 0.95).toInt() - 1).coerceIn(sample.indices)]
    val units = (p95 / 100.0).coerceAtLeast(1.0)
    val power = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(units)))
    // Nearest of 1, 2, 5, 10 × 10ⁿ on a log scale, so 3,400 → 5,000 and 2,900 → 2,000.
    val step = listOf(1.0, 2.0, 5.0, 10.0).minBy { kotlin.math.abs(kotlin.math.ln(units / (it * power))) }
    return Math.round(step * power) * 100
}

fun isLarge(paise: Long, threshold: Long?) = threshold != null && paise >= threshold

private data class Bucket(val label: String, val title: String, val total: Long, val everyday: Long, val large: Boolean, val start: LocalDate, val end: LocalDate)

@Composable
fun SummaryScreen(state: AppState, contentPadding: PaddingValues, onEdit: (ExpenseItem) -> Unit) {
    val p = LocalPalette.current
    val today = state.today
    val all = state.expenses
    val todays = all.filter { it.date == today }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { ScreenTitle("Today") { HeaderButton(Glyph.Settings, "Settings") { state.go(Screen.Settings) } } }
        item { Hero(today, todays, all.isEmpty()) }
        item { Periods(state, today) }
        item { MonthTiles(all, today, state.largePaise) }
        val recentDay = all.firstOrNull()?.date
        if (recentDay != null) item {
            val items = all.filter { it.date == recentDay }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (recentDay == today) "Today's entries" else "Latest entries, ${shortDate(recentDay, today)}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = p.sub, modifier = Modifier.weight(1f))
                Text(
                    "See all", style = Type.label, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { state.go(Screen.History) }).padding(6.dp),
                )
            }
            SegmentedColumn { items.forEachIndexed { i, e -> ExpenseRow(e, i, items.size, onClick = { onEdit(e) }) } }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Hero(today: LocalDate, todays: List<ExpenseItem>, empty: Boolean) {
    val c = MaterialTheme.colorScheme
    val spin = rememberInfiniteTransition(label = "blob").animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "blob")
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(c.primaryContainer)) {
        Box(
            Modifier.size(150.dp).align(Alignment.TopEnd).offset(30.dp, (-30).dp).rotate(spin.value)
                .clip(MaterialShapes.Cookie12Sided.toShape()).background(c.onPrimaryContainer.copy(alpha = 0.08f)),
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
            Text("Spent today · ${today.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.ENGLISH)}, ${shortDateLong(today)}", style = Type.small.copy(fontWeight = FontWeight.Medium), color = c.onPrimaryContainer.copy(alpha = 0.8f))
            Text(
                buildAnnotatedString {
                    val money = moneyParts(todays.sumOf { it.amountPaise })
                    withStyle(SpanStyle(fontSize = 32.sp, color = c.onPrimaryContainer.copy(alpha = 0.6f))) { append(money.prefix) }
                    append(money.number)
                    withStyle(SpanStyle(fontSize = 32.sp, color = c.onPrimaryContainer.copy(alpha = 0.6f))) { append(money.suffix) }
                },
                style = Type.amount(64), color = c.onPrimaryContainer, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            if (todays.isEmpty()) {
                Text(
                    if (empty) "No expenses yet. Tap + to add one, or import your history in Settings." else "Nothing logged today. Tap + when you spend.",
                    style = Type.small, color = c.onPrimaryContainer.copy(alpha = 0.8f),
                )
            } else {
                val byCategory = todays.groupBy { it.categoryName }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }.entries.sortedByDescending { it.value }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    byCategory.take(4).forEach { (name, total) ->
                        Row(
                            Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(c.onPrimaryContainer.copy(alpha = 0.09f)).padding(start = 5.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CategoryTile(name, size = 22.dp)
                            Text("$name ${rupees(total)}", style = Type.small.copy(fontWeight = FontWeight.Medium), color = c.onPrimaryContainer, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun shortDateLong(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH)}"

private fun buckets(period: Int, all: List<ExpenseItem>, today: LocalDate, large: Long?): List<Bucket> {
    fun make(label: String, title: String, s: LocalDate, e: LocalDate): Bucket {
        val items = all.filter { it.date >= s && it.date <= e }
        return Bucket(label, title, items.sumOf { it.amountPaise }, items.filter { !isLarge(it.amountPaise, large) }.sumOf { it.amountPaise }, items.any { isLarge(it.amountPaise, large) }, s, e)
    }
    return when (period) {
        0 -> {
            val monday = today.with(DayOfWeek.MONDAY)
            (0..6).map { monday.plusDays(it.toLong()) }.map { d ->
                make(d.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.ENGLISH), d.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.ENGLISH) + ", " + shortDateLong(d), d, d)
            }
        }
        1 -> {
            val first = today.withDayOfMonth(1)
            var s = first
            val out = mutableListOf<Bucket>()
            var n = 1
            while (s.month == first.month) {
                val e = minOf(s.with(DayOfWeek.SUNDAY), first.plusMonths(1).minusDays(1))
                out += make("W$n", "${shortDateLong(s)} – ${shortDateLong(e)}", s, e)
                s = e.plusDays(1); n++
            }
            out
        }
        else -> (1..12).map { m ->
            val s = LocalDate.of(today.year, m, 1)
            make(s.month.getDisplayName(JTextStyle.NARROW, Locale.ENGLISH), s.month.getDisplayName(JTextStyle.FULL, Locale.ENGLISH), s, s.plusMonths(1).minusDays(1))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Periods(state: AppState, today: LocalDate) {
    val c = MaterialTheme.colorScheme
    var period by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember(period) { mutableStateOf<Int?>(null) }
    val large = state.largePaise
    val list = buckets(period, state.expenses, today, large)
    val current = list.indexOfFirst { today >= it.start && today <= it.end }
    // Week bars show full days; month and year bars show everyday spending so rent doesn't flatten them.
    val value: (Bucket) -> Long = { if (period == 0) it.total else it.everyday }
    val cap = (list.filter { !(period == 0 && it.large) }.maxOfOrNull(value) ?: 0L).coerceAtLeast(1L) * 6 / 5
    val focus = selected ?: current

    ConnectedGroup(listOf("Week", "Month", "Year"), period, { period = it }, Modifier.padding(top = 18.dp, bottom = 10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerLow).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            val label = selected?.let { list[it].title } ?: listOf("This week", "${today.month.getDisplayName(JTextStyle.FULL, Locale.ENGLISH)}, everyday", "${today.year}, everyday")[period]
            Text(label, style = Type.small, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(wholeRupees(selected?.let { value(list[it]) } ?: list.sumOf(value)), style = Type.amount(20, FontWeight(560)), color = c.onSurface)
        }
        Row(Modifier.fillMaxWidth().height(110.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(if (list.size > 7) 5.dp else 8.dp), verticalAlignment = Alignment.Bottom) {
            list.forEachIndexed { i, b ->
                val future = b.start.isAfter(today)
                val fraction by animateFloatAsState(
                    if (future) 0.05f else (value(b).toFloat() / cap).coerceIn(0.06f, 1f),
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "bar",
                )
                val on = i == focus && !future
                val radius by androidx.compose.animation.core.animateDpAsState(if (on) 20.dp else 12.dp, label = "barR")
                val hatch = period == 0 && b.large
                val hatchColor = c.tertiaryContainer
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(fraction).clip(RoundedCornerShape(radius))
                            .background(if (on) c.primary else c.surfaceContainerHighest)
                            .then(if (hatch && !on) Modifier.drawBehind {
                                val step = 10.dp.toPx(); var x = -size.height
                                while (x < size.width) { drawLine(hatchColor, Offset(x, size.height), Offset(x + size.height, 0f), 6.dp.toPx()); x += step }
                            } else Modifier)
                            .semantics { contentDescription = "${b.title} ${rupees(b.total)}" }
                            .combinedClickable(
                                enabled = !future, role = Role.Button,
                                onClick = { selected = if (selected == i) null else i },
                                onLongClick = { if (b.total > 0) state.ask("What made up ${b.title}, ${rupees(b.total)}?") },
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(if (list.size > 7) 5.dp else 8.dp)) {
            list.forEachIndexed { i, b ->
                Text(b.label, style = Type.tiny, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    color = if (i == focus) c.onSurface else c.onSurfaceVariant, fontWeight = if (i == focus) FontWeight.Bold else null)
            }
        }
        Text(if (period == 0 || large == null) "Tap a bar for its total, hold it to ask about it" else "Rent and other entries of ${wholeRupees(large)} or more are left out", style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun MonthTiles(all: List<ExpenseItem>, today: LocalDate, threshold: Long?) {
    val c = MaterialTheme.colorScheme
    val start = today.withDayOfMonth(1)
    val month = all.filter { it.date >= start && it.date <= today }
    val everyday = month.filter { !isLarge(it.amountPaise, threshold) }.sumOf { it.amountPaise }
    val large = month.filter { isLarge(it.amountPaise, threshold) }
    val prevStart = start.minusMonths(1)
    val prevEnd = prevStart.plusDays((today.dayOfMonth - 1).toLong()).coerceAtMost(start.minusDays(1))
    val prevEveryday = all.filter { it.date >= prevStart && it.date <= prevEnd && !isLarge(it.amountPaise, threshold) }.sumOf { it.amountPaise }
    val prevName = prevStart.month.getDisplayName(JTextStyle.FULL, Locale.ENGLISH)
    val diff = everyday - prevEveryday

    var explain by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Modifier.weight(1f), c.secondaryContainer, c.onSecondaryContainer, "${start.month.getDisplayName(JTextStyle.FULL, Locale.ENGLISH)} everyday", wholeRupees(everyday),
            if (prevEveryday > 0) "${wholeRupees(kotlin.math.abs(diff))} ${if (diff <= 0) "under" else "over"} $prevName"
            else if (threshold == null) "Every entry counts" else "Under ${wholeRupees(threshold)} each")
        if (threshold == null) {
            val needed = LARGE_MIN_ENTRIES - all.size
            Tile(Modifier.weight(1f), c.tertiaryContainer, c.onTertiaryContainer, "Large expenses", "—",
                "Shown after $needed more entr${if (needed == 1) "y" else "ies"}", onInfo = { explain = true })
        } else Tile(Modifier.weight(1f), c.tertiaryContainer, c.onTertiaryContainer, "Large expenses", wholeRupees(large.sumOf { it.amountPaise }),
            if (large.isEmpty()) "None this month" else "${large.size} of ${wholeRupees(threshold)} or more", onInfo = { explain = true })
    }
    if (explain) Sheet("Large expenses", onDismiss = { explain = false }) {
        Text(
            if (threshold == null) "Once you have $LARGE_MIN_ENTRIES entries, Tally sets a line from your own spending: about your top 5% by amount over the last 90 days. Entries at or above it count as large."
            else "Single entries of ${wholeRupees(threshold)} or more. That's about your top 5% by amount over the last 90 days, rounded, and it updates as you add expenses.",
            style = Type.body, color = c.onSurfaceVariant,
        )
        Text(
            "Large expenses are left out of everyday spending and of the Month and Year bars, so rent and one-offs don't hide what you spend day to day.",
            style = Type.body, color = c.onSurfaceVariant,
        )
        Text(
            "Got it", style = Type.label, color = c.primary,
            modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(20.dp)).clickable { explain = false }.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Tile(modifier: Modifier, bg: Color, fg: Color, label: String, value: String, footer: String, onInfo: (() -> Unit)? = null) {
    val base = modifier.clip(RoundedCornerShape(24.dp)).background(bg)
    Column((if (onInfo != null) base.clickable(onClickLabel = "Explain $label", onClick = onInfo) else base).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.tiny, color = fg.copy(alpha = 0.8f), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (onInfo != null) Icon(Glyph.Info, null, tint = fg.copy(alpha = 0.8f), modifier = Modifier.padding(start = 6.dp).size(15.dp))
        }
        Text(value, style = Type.amount(24, FontWeight(560)), color = fg, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp), maxLines = 1)
        Text(footer, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 2)
    }
}
