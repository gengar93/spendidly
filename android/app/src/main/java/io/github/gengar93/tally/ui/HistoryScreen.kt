package io.github.gengar93.tally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.gengar93.tally.backend.ExpenseItem

@Composable
fun HistoryScreen(state: AppState, contentPadding: PaddingValues, onEdit: (ExpenseItem) -> Unit) {
    val c = MaterialTheme.colorScheme
    val query = state.historyQuery.trim().lowercase()
    val filtered = state.expenses.filter { e ->
        (state.historyCategory == null || e.categoryName == state.historyCategory) &&
            (query.isEmpty() || e.note?.lowercase()?.contains(query) == true || e.categoryName.lowercase().contains(query) ||
                amountMatches(e.amountPaise, query))
    }
    val days = filtered.groupBy { it.date }.entries.toList()
    val usedCategories = state.usedCategoryNames()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            ScreenTitle("History") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeaderButton(Glyph.Table, "Expense table") { state.openTable(TableFilter.thisMonth(state.today)) }
                    HeaderButton(Glyph.Settings, "Settings") { state.go(Screen.Settings) }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerHigh).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyph.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f).padding(start = 12.dp)) {
                    if (state.historyQuery.isEmpty()) Text("Search notes or amounts", style = Type.body, color = c.onSurfaceVariant)
                    BasicTextField(
                        state.historyQuery, { state.historyQuery = it }, singleLine = true,
                        textStyle = Type.body.copy(color = c.onSurface), cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.historyQuery.isNotEmpty()) {
                    Icon(Glyph.Close, "Clear search", tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp).clickableNoRipple { state.historyQuery = "" })
                }
            }
        }
        if (usedCategories.size > 1) item {
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill("All", state.historyCategory == null, { state.historyCategory = null }) }
                items(usedCategories) { name ->
                    Pill(name, state.historyCategory == name, { state.historyCategory = if (state.historyCategory == name) null else name }) {
                        CategoryTile(name, size = 24.dp)
                    }
                }
            }
        }
        if (days.isEmpty()) item {
            Text(
                if (state.expenses.isEmpty()) "Your expenses will appear here. Tap + to add one." else "Nothing matches. Try another word or clear the filter.",
                style = Type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 32.dp),
            )
        }
        items(days, key = { it.key.toString() }) { (date, items) ->
            DayGroup(date, state.today, items, onClick = onEdit)
        }
    }
}
