package io.github.gengar93.spendidly.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gengar93.spendidly.backend.Category
import io.github.gengar93.spendidly.backend.ExpenseItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class Step { Amount, Note, Category }

/**
 * Quick add (editing == null) or edit. Docked on the system keyboard:
 * amount → Enter → note → Enter → type to narrow categories → Enter saves the first match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(state: AppState, editing: ExpenseItem?, onClose: () -> Unit) {
    val p = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    var amount by remember { mutableStateOf(TextFieldValue(editing?.let { paiseToInput(it.amountPaise) } ?: "").let { it.copy(selection = TextRange(it.text.length)) }) }
    var note by remember { mutableStateOf(TextFieldValue(editing?.note.orEmpty())) }
    var filter by remember { mutableStateOf(TextFieldValue("")) }
    var date by remember { mutableStateOf(editing?.date ?: state.today) }
    var step by remember { mutableStateOf(Step.Amount) }
    var picking by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    val noteFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val paise = parsePaise(amount.text)

    // Order: the category most used with this note, then the user's order from Settings.
    val noteKey = note.text.trim().lowercase()
    val preferred = remember(noteKey, state.expenses) {
        if (noteKey.isEmpty()) null else state.expenses.filter { it.note?.trim()?.lowercase() == noteKey }
            .groupingBy { it.categoryId }.eachCount().maxByOrNull { it.value }?.key
    }
    val q = filter.text.trim().lowercase()
    val ordered = state.categories.sortedByDescending { it.id == preferred }.let { list ->
        if (q.isEmpty()) list else {
            val byName = list.filter { it.name.lowercase().contains(q) }
                .sortedByDescending { it.name.lowercase().startsWith(q) || it.name.lowercase().split(" ").any { w -> w.startsWith(q) } }
            byName.ifEmpty { list.filter { it.description.lowercase().contains(q) } }
        }
    }
    val highlight = if (step == Step.Category || (preferred != null && editing == null)) ordered.firstOrNull() else null
    val current = editing?.categoryId

    fun save(category: Category?) {
        val value = paise ?: return
        keyboard?.hide()
        if (editing == null) state.add(value, date, category, note.text) else state.update(editing, value, date, category, note.text)
        onClose()
    }

    LaunchedEffect(Unit) { amountFocus.requestFocus(); keyboard?.show() }
    LaunchedEffect(step) {
        when (step) {
            Step.Amount -> amountFocus.requestFocus()
            Step.Note -> noteFocus.requestFocus()
            Step.Category -> filterFocus.requestFocus()
        }
    }
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f)).clickableNoRipple(onClose))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow)
                .clickableNoRipple {}.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(p.sub.copy(alpha = 0.4f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, p.line, RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = "Change date") { picking = true }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Glyph.Calendar, null, tint = p.ink, modifier = Modifier.size(18.dp))
                    Text(shortDate(date, state.today), style = Type.label, color = p.ink)
                }
                Box(Modifier.weight(1f))
                if (editing != null) {
                    Text(
                        "Delete", style = Type.label, color = p.danger,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { keyboard?.hide(); state.remove(editing.id); onClose() }.padding(8.dp),
                    )
                }
                Text("Cancel", style = Type.small, color = p.sub, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClose() }.padding(8.dp))
            }

            // Amount: large while typing it, a compact line afterwards.
            val big = step == Step.Amount
            Row(
                Modifier.padding(top = if (big) 10.dp else 6.dp, bottom = 4.dp).clickableNoRipple { step = Step.Amount },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The symbol sits before or after the number as the phone's locale writes it.
                val money = moneyParts(0)
                if (money.prefix.isNotEmpty()) Text(money.prefix.trim(), style = Type.amount(if (big) 28 else 15, FontWeight(560)), color = p.sub, modifier = Modifier.padding(end = 2.dp))
                BasicTextField(
                    amount, { v -> if (v.text.length <= 15 && v.text.all(::amountChar)) amount = v },
                    singleLine = true, textStyle = Type.amount(if (big) 52 else 24).copy(color = p.ink),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = if (currency.decimals > 0) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (paise != null) step = Step.Note }),
                    decorationBox = { inner -> Box { if (amount.text.isEmpty()) Text("0", style = Type.amount(if (big) 52 else 24), color = p.line); inner() } },
                    modifier = (if (money.suffix.isEmpty()) Modifier.weight(1f) else Modifier.weight(1f, fill = false).width(IntrinsicSize.Max)).focusRequester(amountFocus).onFocusChanged { if (it.isFocused) step = Step.Amount },
                )
                if (money.suffix.isNotEmpty()) Text(money.suffix.trim(), style = Type.amount(if (big) 28 else 15, FontWeight(560)), color = p.sub, modifier = Modifier.padding(start = 6.dp))
            }

            // One text line: the note, then the category filter.
            val onFilter = step == Step.Category
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                if (!onFilter) {
                    if (note.text.isEmpty()) Text("Note", style = Type.body.copy(fontSize = 18.sp), color = p.sub)
                    BasicTextField(
                        note, { if (it.text.length <= 500) note = it }, singleLine = true,
                        textStyle = Type.body.copy(fontSize = 18.sp, color = p.ink), cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { if (paise != null) step = Step.Category }),
                        modifier = Modifier.fillMaxWidth().focusRequester(noteFocus).onFocusChanged { if (it.isFocused && step == Step.Amount && paise != null) step = Step.Note },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (note.text.isNotBlank()) Text(
                            note.text.trim(), style = Type.body, color = p.sub, maxLines = 1,
                            modifier = Modifier.padding(end = 10.dp).clickableNoRipple { step = Step.Note },
                        )
                        Box(Modifier.weight(1f)) {
                            if (filter.text.isEmpty()) Text("Category", style = Type.body.copy(fontSize = 18.sp), color = p.sub)
                            BasicTextField(
                                filter, { filter = it }, singleLine = true,
                                textStyle = Type.body.copy(fontSize = 18.sp, color = p.ink), cursorBrush = SolidColor(p.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                                keyboardActions = KeyboardActions(onDone = { if (state.categories.isEmpty()) save(null) else ordered.firstOrNull()?.let(::save) }),
                                modifier = Modifier.fillMaxWidth().focusRequester(filterFocus),
                            )
                        }
                    }
                }
            }

            // Two rows that scroll together, filled column by column: 0 top, 1 below, 2 top of the next.
            val scroll = rememberScrollState()
            LaunchedEffect(q) { scroll.scrollTo(0) }
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().alpha(if (paise == null) 0.4f else 1f).horizontalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (row in 0..1) {
                    val cells = ordered.withIndex().filter { it.index % 2 == row }
                    if (cells.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((index, category) in cells) key(category.id) {
                            val hit = category == highlight && index == 0
                            val wasCurrent = editing != null && category.id == current && !hit
                            Row(
                                Modifier.height(44.dp).clip(RoundedCornerShape(if (hit) 22.dp else 14.dp))
                                    .background(if (hit) p.accent else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .then(if (wasCurrent) Modifier.border(1.5.dp, p.accent, RoundedCornerShape(14.dp)) else Modifier)
                                    .clickable(enabled = paise != null, role = Role.Button, onClickLabel = "Save to ${category.name}") { save(category) }
                                    .padding(start = 6.dp, end = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                CategoryTile(category.name, size = 32.dp)
                                Text(category.name, style = Type.label, color = if (hit) p.accentInk else p.ink, maxLines = 1)
                                if (hit && step == Step.Category) Text("↵", style = Type.small, color = p.accentInk.copy(alpha = 0.8f))
                            }
                        }
                    }
                }
                if (state.categories.isEmpty()) {
                    Text(
                        "No categories yet. Enter saves it as Uncategorized.", style = Type.small, color = p.sub,
                        modifier = Modifier.padding(vertical = 10.dp).clickableNoRipple { save(null) },
                    )
                } else if (ordered.isEmpty()) {
                    Text("No category matches “${filter.text.trim()}”", style = Type.small, color = p.sub, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }

    if (picking) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(state.today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("Use date", style = Type.label) }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel", style = Type.label) } },
        ) { DatePicker(picker, showModeToggle = false) }
    }
}

