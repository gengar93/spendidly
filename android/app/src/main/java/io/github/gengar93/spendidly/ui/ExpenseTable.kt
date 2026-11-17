package io.github.gengar93.spendidly.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gengar93.spendidly.backend.ExpenseItem
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/** What the table shows. Null bounds mean open-ended; an empty category set means all. */
data class TableFilter(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val categories: Set<String> = emptySet(),
    val min: Long? = null,
    val max: Long? = null,
    val search: String = "",
) {
    fun matches(e: ExpenseItem) =
        (start == null || !e.date.isBefore(start)) && (end == null || !e.date.isAfter(end)) &&
            (categories.isEmpty() || e.categoryName in categories) &&
            (min == null || e.amountPaise >= min) && (max == null || e.amountPaise <= max) &&
            (search.isBlank() || e.note?.contains(search.trim(), ignoreCase = true) == true)

    companion object {
        fun thisMonth(today: LocalDate) = TableFilter(start = today.withDayOfMonth(1), end = today)

        /** From the filters the AI used for list_expenses; category ids are mapped to names. */
        fun fromModel(filters: JSONObject, categoryNames: Map<String, String>): TableFilter {
            fun date(key: String) = filters.optString(key).takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val ids = filters.optJSONArray("category_ids")
            val names = if (ids == null) emptySet() else (0 until ids.length()).map { if (ids.isNull(it)) "Uncategorized" else categoryNames[ids.getString(it)] ?: "" }.filter { it.isNotEmpty() }.toSet()
            return TableFilter(
                date("start_date"), date("end_date"), names,
                decimalPaise(filters.optString("min_amount")), decimalPaise(filters.optString("max_amount")),
                filters.optString("description_search").takeIf { it != "null" }.orEmpty(),
            )
        }
    }
}

private enum class Mode(val label: String) { Entries("Entries"), Day("By day"), Category("By category"), Month("By month") }
private enum class Sort { DateDesc, DateAsc, AmountDesc, AmountAsc }

private data class Group(val key: String, val label: String, val sub: String, val items: List<ExpenseItem>, val category: String?) {
    val total get() = items.sumOf { it.amountPaise }
}

private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

private fun rangeLabel(f: TableFilter, today: LocalDate): String = when {
    f.start == null && f.end == null -> "All time"
    f.start == today.withDayOfMonth(1) && f.end == today -> "This month"
    f.start == today.with(DayOfWeek.MONDAY) && f.end == today -> "This week"
    f.start == today.minusMonths(1).withDayOfMonth(1) && f.end == today.withDayOfMonth(1).minusDays(1) -> "Last month"
    f.start != null && f.end != null -> if (f.start.year == f.end.year) "${f.start.format(dayFmt)} – ${f.end.format(dayFmt)}" else "${f.start} – ${f.end}"
    f.start != null -> "From ${f.start.format(dayFmt)}"
    else -> "Until ${f.end!!.format(dayFmt)}"
}

/** Full-screen expense table with filters, grouping and sorting. */
@Composable
fun ExpenseTable(state: AppState, onEdit: (ExpenseItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    val filter = state.tableFilter ?: return
    var mode by remember { mutableStateOf(Mode.Entries) }
    var sort by remember { mutableStateOf(Sort.DateDesc) }
    var groupByTotal by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var expanded by remember(mode) { mutableStateOf(setOf<String>()) }
    BackHandler(enabled = !sheet) { state.closeTable() }

    val rows = state.expenses.filter(filter::matches)
    val total = rows.sumOf { it.amountPaise }
    val sorted = when (sort) {
        Sort.DateDesc -> rows
        Sort.DateAsc -> rows.reversed()
        Sort.AmountDesc -> rows.sortedByDescending { it.amountPaise }
        Sort.AmountAsc -> rows.sortedBy { it.amountPaise }
    }
    val groups: List<Group> = when (mode) {
        Mode.Entries -> emptyList()
        Mode.Day -> rows.groupBy { it.date }.map { (d, v) -> Group(d.toString(), dayLabel(d, state.today), "${v.size} entr${if (v.size == 1) "y" else "ies"}", v, null) }
        Mode.Category -> rows.groupBy { it.categoryName }.map { (n, v) -> Group(n, n, "${v.size} entr${if (v.size == 1) "y" else "ies"}", v, n) }
        Mode.Month -> rows.groupBy { it.date.withDayOfMonth(1) }.map { (m, v) -> Group(m.toString(), m.format(monthFmt), "${v.size} entries", v, null) }
    }.let { g -> if (groupByTotal) g.sortedByDescending { it.total } else if (mode == Mode.Category) g.sortedBy { it.label } else g.sortedByDescending { it.key } }

    Box(Modifier.fillMaxSize().background(c.surface).clickableNoRipple {}) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeaderButton(Glyph.Back, "Close table") { state.closeTable() }
                    Text("Expenses", style = Type.heading.copy(fontFamily = NumberFont), color = c.onSurface, modifier = Modifier.weight(1f))
                    HeaderButton(Glyph.Filter, "Filters") { sheet = true }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.primaryContainer).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${rangeLabel(filter, state.today)} · ${rows.size} entr${if (rows.size == 1) "y" else "ies"}", style = Type.tiny, color = c.onPrimaryContainer.copy(alpha = 0.75f))
                        Text(rupees(total), style = Type.amount(26), color = c.onPrimaryContainer)
                    }
                    if (rows.isNotEmpty()) Text("Avg ${wholeRupees(total / rows.size)}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.onPrimaryContainer)
                }
            }
            item {
                LazyRow(Modifier.padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Pill(rangeLabel(filter, state.today), filter.start != null || filter.end != null, { sheet = true }) }
                    item {
                        Pill(
                            when (filter.categories.size) { 0 -> "All categories"; 1 -> filter.categories.first(); else -> "${filter.categories.size} categories" },
                            filter.categories.isNotEmpty(), { sheet = true },
                        )
                    }
                    item {
                        Pill(
                            when {
                                filter.min != null && filter.max != null -> "${wholeRupees(filter.min)}–${wholeRupees(filter.max)}"
                                filter.min != null -> "Over ${wholeRupees(filter.min)}"
                                filter.max != null -> "Under ${wholeRupees(filter.max)}"
                                else -> "Amount"
                            },
                            filter.min != null || filter.max != null, { sheet = true },
                        )
                    }
                    if (filter.search.isNotBlank()) item { Pill("“${filter.search}”", true, { sheet = true }) }
                    if (filter != TableFilter()) item { Pill("Clear", false, { state.openTable(TableFilter()) }) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mode.entries.forEachIndexed { i, m ->
                        val on = m == mode
                        val outer = 18.dp
                        val inner = if (on) 18.dp else 5.dp
                        Box(
                            Modifier.weight(1f).height(36.dp)
                                .clip(RoundedCornerShape(if (i == 0) outer else inner, if (i == Mode.entries.lastIndex) outer else inner, if (i == Mode.entries.lastIndex) outer else inner, if (i == 0) outer else inner))
                                .background(if (on) c.primary else c.surfaceContainerHigh).clickable(role = Role.RadioButton) { mode = m },
                            contentAlignment = Alignment.Center,
                        ) { Text(m.label, style = Type.tiny.copy(fontWeight = FontWeight.Medium), color = if (on) c.onPrimary else c.onSurface, maxLines = 1) }
                    }
                }
            }
            if (rows.isEmpty()) item {
                Text("Nothing matches these filters. Widen the dates or clear a filter.", style = Type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp))
            } else if (mode == Mode.Entries) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(c.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HeaderCell("Date", sort == Sort.DateDesc || sort == Sort.DateAsc, sort == Sort.DateAsc, Modifier.width(62.dp)) { sort = if (sort == Sort.DateDesc) Sort.DateAsc else Sort.DateDesc }
                        Text("Note", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                        HeaderCell("Amount", sort == Sort.AmountDesc || sort == Sort.AmountAsc, sort == Sort.AmountAsc, Modifier, end = true) { sort = if (sort == Sort.AmountDesc) Sort.AmountAsc else Sort.AmountDesc }
                    }
                }
                items(sorted, key = { it.id }) { e ->
                    val last = e.id == sorted.last().id
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(if (last) RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp) else RoundedCornerShape(0.dp))
                            .background(c.surfaceContainerLowest).clickable { onEdit(e) }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.width(62.dp)) {
                            Text(e.date.format(dayFmt), style = Type.tiny, color = c.onSurface)
                            Text(e.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.ENGLISH), style = Type.tiny, color = c.onSurfaceVariant)
                        }
                        CategoryTile(e.categoryName, size = 26.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.note ?: e.categoryName, style = Type.small.copy(fontWeight = FontWeight.Medium), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (e.note != null) Text(e.categoryName, style = Type.tiny, color = c.onSurfaceVariant, maxLines = 1)
                        }
                        Text(rupees(e.amountPaise), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, textAlign = TextAlign.End)
                    }
                    if (!last) Box(Modifier.fillMaxWidth().background(c.surfaceContainerLowest).padding(horizontal = 14.dp)) { Hairline() }
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                        Pill(if (groupByTotal) "Sort: Total ↓" else if (mode == Mode.Category) "Sort: Name" else "Sort: Newest", false, { groupByTotal = !groupByTotal })
                    }
                }
                items(groups, key = { it.key }) { g ->
                    val open = g.key in expanded
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerLowest)
                            .clickable { expanded = if (open) expanded - g.key else expanded + g.key }.animateContentSize().padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (g.category != null) CategoryTile(g.category, size = 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(g.label, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(g.sub, style = Type.tiny, color = c.onSurfaceVariant)
                            }
                            Text(rupees(g.total), style = Type.amount(16, FontWeight(600)), color = c.onSurface)
                        }
                        val share = if (total > 0) (g.total.toFloat() / total).coerceIn(0.005f, 1f) else 0f
                        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceContainerHigh)) {
                            Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(c.primary))
                        }
                        if (open) {
                            Spacer(Modifier.height(8.dp))
                            if (mode == Mode.Month) {
                                // Month cards break down by category, like the web's month + category grouping.
                                g.items.groupBy { it.categoryName }.entries.sortedByDescending { e -> e.value.sumOf { it.amountPaise } }.forEach { (name, v) ->
                                    Hairline()
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CategoryTile(name, size = 24.dp)
                                        Text("$name · ${v.size}", style = Type.small, color = c.onSurface, modifier = Modifier.weight(1f))
                                        Text(rupees(v.sumOf { it.amountPaise }), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                                    }
                                }
                            } else g.items.sortedByDescending { it.amountPaise }.forEach { e ->
                                Hairline()
                                Row(
                                    Modifier.fillMaxWidth().clickable { onEdit(e) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (mode == Mode.Day) CategoryTile(e.categoryName, size = 24.dp)
                                    else Text(e.date.format(dayFmt), style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.width(48.dp))
                                    Text(e.note ?: e.categoryName, style = Type.small, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(rupees(e.amountPaise), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (sheet) FilterSheet(state, filter, rows = { f -> state.expenses.count(f::matches) }, onApply = { state.openTable(it); sheet = false }, onDismiss = { sheet = false })
    }
}

@Composable
private fun HeaderCell(label: String, active: Boolean, ascending: Boolean, modifier: Modifier, end: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Text(
        label + if (active) (if (ascending) " ↑" else " ↓") else "",
        style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = if (active) c.primary else c.onSurfaceVariant,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(state: AppState, current: TableFilter, rows: (TableFilter) -> Int, onApply: (TableFilter) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val today = state.today
    var draft by remember { mutableStateOf(current) }
    var minText by remember { mutableStateOf(current.min?.let(::paiseToInput).orEmpty()) }
    var maxText by remember { mutableStateOf(current.max?.let(::paiseToInput).orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    BackHandler { onDismiss() }
    val effective = draft.copy(min = parsePaise(minText), max = parsePaise(maxText))
    val count = rows(effective)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickableNoRipple(onDismiss))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surfaceContainerLow).clickableNoRipple {}
                .navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurfaceVariant.copy(alpha = 0.4f)))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text("Filters", style = Type.heading, color = c.onSurface)
                SheetLabel("When")
                val presets = listOf(
                    "This week" to TableFilter(start = today.with(DayOfWeek.MONDAY), end = today),
                    "This month" to TableFilter.thisMonth(today),
                    "Last month" to TableFilter(start = today.minusMonths(1).withDayOfMonth(1), end = today.withDayOfMonth(1).minusDays(1)),
                    "Last 3 months" to TableFilter(start = today.minusMonths(3).plusDays(1), end = today),
                    "All time" to TableFilter(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, p) ->
                        Pill(label, draft.start == p.start && draft.end == p.end, { draft = draft.copy(start = p.start, end = p.end) })
                    }
                    val custom = presets.none { draft.start == it.second.start && draft.end == it.second.end }
                    Pill(if (custom) rangeLabel(draft, today) else "Custom…", custom, { picking = true })
                }
                SheetLabel("Categories")
                val used = state.usedCategoryNames()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    used.forEach { name ->
                        val on = name in draft.categories
                        Pill(name, on, { draft = draft.copy(categories = if (on) draft.categories - name else draft.categories + name) }) { CategoryTile(name, size = 22.dp) }
                    }
                }
                SheetLabel("Amount")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetField(minText, { minText = it.filter(::amountChar) }, "Min ${currency.symbol()}", Modifier.weight(1f), number = true)
                    SheetField(maxText, { maxText = it.filter(::amountChar) }, "Max ${currency.symbol()}", Modifier.weight(1f), number = true)
                }
                SheetLabel("Note contains")
                SheetField(draft.search, { draft = draft.copy(search = it) }, "Swiggy, Uber…", Modifier.fillMaxWidth())
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Clear", style = Type.label, color = c.onSecondaryContainer, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(c.secondaryContainer)
                        .clickable { draft = TableFilter(); minText = ""; maxText = "" }.padding(vertical = 14.dp),
                )
                Text(
                    "Show $count entr${if (count == 1) "y" else "ies"}", style = Type.label, color = c.onPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1.4f).clip(RoundedCornerShape(24.dp)).background(c.primary).clickable { onApply(effective) }.padding(vertical = 14.dp),
                )
            }
        }
    }

    if (picking) {
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = draft.start?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = draft.end?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    draft = draft.copy(start = day(picker.selectedStartDateMillis), end = day(picker.selectedEndDateMillis) ?: day(picker.selectedStartDateMillis))
                    picking = false
                }) { Text("Use dates", style = Type.label) }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel", style = Type.label) } },
        ) { DateRangePicker(picker, modifier = Modifier.height(520.dp), showModeToggle = false) }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun SheetField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier, number: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceContainerHighest).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.onSurfaceVariant)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = Type.body.copy(color = c.onSurface), cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (!number) KeyboardType.Text else if (currency.decimals > 0) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

