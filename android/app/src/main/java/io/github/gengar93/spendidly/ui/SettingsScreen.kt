package io.github.gengar93.spendidly.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import io.github.gengar93.spendidly.PRIVACY_POLICY_URL
import io.github.gengar93.spendidly.appVersion
import io.github.gengar93.spendidly.backend.AppCurrency
import io.github.gengar93.spendidly.openPrivacyPolicy
import io.github.gengar93.spendidly.backend.Category
import io.github.gengar93.spendidly.backend.ModelProvider
import io.github.gengar93.spendidly.backend.detectProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state: AppState, contentPadding: PaddingValues) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    var pickModel by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }
    var editKeys by remember { mutableStateOf(false) }
    var pickCurrency by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<AppCurrency?>(null) }
    var editCategory by remember { mutableStateOf<Category?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) state.import(context, uri) }
    // octet-stream so pickers keep the .spendidly name instead of adding .zip.
    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) state.export(context, uri, csvOnly = false) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) state.export(context, uri, csvOnly = true) }
    BackHandler { state.go(state.previous) }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reorder = remember { Reorder(list, scope, with(density) { 64.dp.toPx() }, with(density) { 14.dp.toPx() }) }
    val haptics = LocalHapticFeedback.current

    val savedKeys = ModelProvider.entries.filter { state.providerConfigs[it]?.configured == true }
    LaunchedEffect(state.showKeys) { if (state.showKeys) { state.showKeys = false; editKeys = true } }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
        item {
            Box(Modifier.padding(top = 8.dp)) { HeaderButton(Glyph.Back, "Back to ${state.previous.name}") { state.go(state.previous) } }
            ScreenTitle("Settings")
        }
        item {
            Section("Currency")
            SegmentedColumn {
                SegmentedItem(0, 1, onClick = { pickCurrency = true }) {
                    Label("Currency", "Amounts show as ${rupees(currency.sample)}", Modifier.weight(1f))
                    Text(currency.displayName, style = Type.small, color = c.onSurfaceVariant)
                }
            }
        }
        item {
            Section("Ask")
            SegmentedColumn {
                val model = state.currentModel
                SegmentedItem(0, 2, onClick = { if (savedKeys.isEmpty()) { state.keyCheck = KeyCheckState.Idle; editKeys = true } else pickModel = true }) {
                    Label("Model", model?.label ?: "Add an API key first", Modifier.weight(1f))
                    if (model != null) Text(model.provider.label, style = Type.small, color = c.onSurfaceVariant)
                }
                SegmentedItem(1, 2, onClick = { state.keyCheck = KeyCheckState.Idle; editKeys = true }) {
                    Label("API keys", if (savedKeys.isEmpty()) "Stored encrypted on this phone" else savedKeys.joinToString { it.label }, Modifier.weight(1f))
                    Text(if (savedKeys.isEmpty()) "Not set" else "${savedKeys.size} saved", style = Type.small, color = if (savedKeys.isEmpty()) c.primary else c.onSurfaceVariant)
                }
            }
        }
        item {
            Section("Voice")
            SegmentedColumn {
                val voice = state.voiceModel
                SegmentedItem(0, 1, onClick = { pickVoice = true }) {
                    Label("Model", voice?.label ?: "Needs an OpenAI, Google or OpenRouter key", Modifier.weight(1f))
                    Text(voice?.provider?.label ?: "Not set", style = Type.small, color = if (voice == null) c.primary else c.onSurfaceVariant)
                }
            }
            Text(
                "Tap the mic in a chat to ask out loud. The recording goes only to the voice model's provider and is deleted once it's turned into text.",
                style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
            )
        }
        item {
            Section("Chats")
            SegmentedColumn {
                SegmentedItem(0, 1, onClick = { state.setAutoDeleteChats(!state.autoDeleteChats) }) {
                    Label("Delete chats older than a week", "When no new messages for 7 days", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = state.autoDeleteChats, onCheckedChange = { state.setAutoDeleteChats(it) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) { Section("Categories") }
                Text("Add", style = Type.label, color = c.primary, modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { newCategory = true }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            if (state.categories.isEmpty()) Text("No categories. Add one, or import a file.", style = Type.small, color = c.onSurfaceVariant, modifier = Modifier.padding(4.dp))
        }
        // One lazy item per category so rows can be dragged into a new order (hold a row, or drag its handle).
        val cats = if (reorder.dragging != null) reorder.items else state.categories
        itemsIndexed(cats, key = { _, cat -> cat.id }) { i, cat ->
            val lifted = reorder.dragging == cat.id || reorder.settling == cat.id
            // Keep the dragged row composed even if the list scrolls it out of view.
            val pinnable = LocalPinnableContainer.current
            DisposableEffect(reorder.dragging == cat.id) {
                val pin = if (reorder.dragging == cat.id) pinnable?.pin() else null
                onDispose { pin?.release() }
            }
            // Tracks the finger in window coordinates, which don't shift as rows move or the list scrolls.
            val drag: suspend PointerInputScope.(Boolean, () -> LayoutCoordinates?) -> Unit = { afterLongPress, coords ->
                fun y(local: Offset) = coords()?.takeIf { it.isAttached }?.localToWindow(local)?.y ?: local.y
                val start: (Offset) -> Unit = { at -> if (reorder.start(cat.id, state.categories, y(at))) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                val move: (PointerInputChange) -> Unit = { change -> if (reorder.dragging == cat.id) { change.consume(); reorder.dragTo(y(change.position)) } }
                // Rows leaving the screen report a cancel too, so only the dragged row's end counts.
                val end = { reorder.end(cat.id, state::reorderCategories) }
                if (afterLongPress) detectDragGesturesAfterLongPress(start, end, end) { change, _ -> move(change) }
                else awaitEachGesture {
                    // The handle drags as soon as the finger passes touch slop, measured from where it went down.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val from = y(down.position)
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    if (!reorder.start(cat.id, state.categories, from)) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        move(slop)
                        verticalDrag(slop.id, move)
                    } finally {
                        end()
                    }
                }
            }
            val rowCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
            val handleCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
            Box(
                (if (lifted) Modifier.zIndex(1f).graphicsLayer {
                    translationY = if (reorder.dragging == cat.id) reorder.offset else reorder.settle.value
                    shadowElevation = 6.dp.toPx(); shape = RoundedCornerShape(22.dp); clip = false
                } else Modifier.animateItem()).padding(bottom = if (i < cats.lastIndex) 2.dp else 0.dp),
            ) {
                SegmentedItem(
                    i, cats.size, onClick = { editCategory = cat }, selected = lifted,
                    gestures = Modifier.onGloballyPositioned { rowCoords[0] = it }.pointerInput(cat.id) { drag(true) { rowCoords[0] } },
                ) {
                    CategoryTile(cat.name)
                    Label(cat.name, cat.description.ifEmpty { "Add a short description" }, Modifier.weight(1f))
                    Box(
                        Modifier.size(40.dp).onGloballyPositioned { handleCoords[0] = it }.pointerInput(cat.id) { drag(false) { handleCoords[0] } }.semantics { contentDescription = "Drag to reorder ${cat.name}" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Glyph.DragHandle, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
                }
            }
        }
        item {
            Text(
                if (state.categories.size > 1) "Hold a category or drag its handle to reorder. Descriptions help Ask file and find expenses correctly."
                else "Descriptions help Ask file and find expenses correctly.",
                style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 8.dp),
            )
        }
        item {
            Section("Data")
            val stamp = state.today
            SegmentedColumn {
                // Any type, so .spendidly backups and CSV files can be picked.
                SegmentedItem(0, 5, onClick = { importer.launch(arrayOf("*/*")) }) { Label("Import from file", "A .spendidly backup or an expenses CSV", Modifier.weight(1f)) }
                SegmentedItem(1, 5, onClick = { backupSaver.launch("Spendidly-$stamp.spendidly") }) { Label("Export backup", "A .spendidly file this app can import again", Modifier.weight(1f)) }
                SegmentedItem(2, 5, onClick = { csvSaver.launch("Spendidly-$stamp.csv") }) { Label("Export CSV", "Expenses only, for spreadsheets", Modifier.weight(1f)) }
                SegmentedItem(3, 5, onClick = { demo = true }) { Label("Load demo data", "Two months of fictional expenses to explore with", Modifier.weight(1f)) }
                SegmentedItem(4, 5, onClick = { erase = true }) { Label("Erase everything", "Expenses, categories and keys. This can't be undone.", Modifier.weight(1f), danger = true) }
            }
            val large = state.largePaise
            Text(
                if (large == null) "Once you have $LARGE_MIN_ENTRIES expenses, Summary learns from your spending which single entries count as large. Until then everything counts as everyday spending."
                else "Large expenses on Summary are single entries of ${wholeRupees(large)} or more, learned from your recent spending. Everything else counts as everyday spending.",
                style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp, start = 6.dp, end = 6.dp),
            )
        }
        item {
            Section("About")
            SegmentedColumn {
                SegmentedItem(0, 2, onClick = { if (!openPrivacyPolicy(context)) state.show("No browser found. The policy is at $PRIVACY_POLICY_URL") }) {
                    Label("Privacy policy", "Opens in your browser", Modifier.weight(1f))
                    Icon(Glyph.Chevron, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                SegmentedItem(1, 2) {
                    Label("Spendidly", "Version ${remember(context) { appVersion(context) }}", Modifier.weight(1f))
                }
            }
        }
    }

    if (pickModel) Sheet("Model", onDismiss = { pickModel = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val groups = savedKeys.mapNotNull { p -> state.providerConfigs[p]?.takeIf { it.models.isNotEmpty() } }
            if (groups.isEmpty()) Text("Your saved keys can't use any of Spendidly's models. Add a key from another provider.", style = Type.small, color = c.onSurfaceVariant)
            groups.forEach { config ->
                Text(config.provider.label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                config.models.forEach { model ->
                    val on = config.provider == state.provider && model.id == config.model
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) c.secondaryContainer else Color.Transparent)
                            .clickable { state.chooseModel(model); pickModel = false }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label(model.name, model.note, Modifier.weight(1f))
                        Box(Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 2.dp, if (on) c.primary else c.outline, CircleShape))
                    }
                }
            }
            Text("Only models your saved keys can use are listed. Chats started with another model become read-only.", style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
        }
    }

    if (pickVoice) Sheet("Voice", onDismiss = { pickVoice = false }) {
        val options = state.voiceOptions
        if (options.isEmpty()) {
            Text("Voice needs an OpenAI, Google or OpenRouter key. Anthropic models can't take audio. Add one in API keys.", style = Type.small, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Add a key", enabled = true) { pickVoice = false; state.keyCheck = KeyCheckState.Idle; editKeys = true }
                TextAction("Not now", c.primary) { pickVoice = false }
            }
        } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val current = state.voiceModel
            options.groupBy { it.provider }.forEach { (provider, models) ->
                Text(provider.label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                models.forEach { model ->
                    val on = model == current
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) c.secondaryContainer else Color.Transparent)
                            .clickable { state.chooseVoice(model); pickVoice = false }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label(model.name, model.note, Modifier.weight(1f))
                        Box(Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 2.dp, if (on) c.primary else c.outline, CircleShape))
                    }
                }
            }
            Text("Only voice models your saved keys can use are listed. Speak in any language; it isn't translated.", style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
        }
    }

    if (pickCurrency) Sheet("Currency", onDismiss = { pickCurrency = false }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            AppCurrency.entries.forEach { option ->
                val on = option == currency
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) c.secondaryContainer else Color.Transparent)
                        .clickable {
                            pickCurrency = false
                            // Relabelling existing expenses needs a confirmation; an empty app just switches.
                            if (!on) { if (state.expenses.isEmpty()) state.chooseCurrency(option) else switchTo = option }
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(option.displayName, option.format(option.sample), Modifier.weight(1f))
                    Box(Modifier.size(20.dp).clip(CircleShape).border(if (on) 6.dp else 2.dp, if (on) c.primary else c.outline, CircleShape))
                }
            }
        }
    }

    switchTo?.let { target ->
        Sheet("Switch to ${target.displayName}?", onDismiss = { switchTo = null }) {
            Text(
                "Your existing expenses keep their numbers and show the new symbol. They aren't converted. To start fresh in the new currency, use Erase everything.",
                style = Type.small, color = c.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Switch", enabled = true) { state.chooseCurrency(target); switchTo = null }
                TextAction("Cancel", c.primary) { switchTo = null }
            }
        }
    }

    state.pendingImport?.let { file ->
        val known = AppCurrency.fromCode(file.currency)
        Sheet("Import in ${known?.displayName ?: file.currency}?", onDismiss = { state.resolveImport(false) }) {
            Text(
                "This backup is in ${known?.title ?: file.currency}, the app uses ${currency.title}. Amounts will be imported as they are, without conversion.",
                style = Type.small, color = c.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Import", enabled = true) { state.resolveImport(true) }
                TextAction("Cancel", c.primary) { state.resolveImport(false) }
            }
        }
    }

    if (editKeys) {
        var key by remember { mutableStateOf("") }
        val check = state.keyCheck
        val checking = check is KeyCheckState.Checking
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(check) { if (check is KeyCheckState.Valid) key = "" }
        Sheet("API keys", onDismiss = { editKeys = false }) {
            Text("Paste a key from Anthropic, OpenAI, Google or OpenRouter. Spendidly checks it with the provider, then stores it encrypted on this phone.", style = Type.small, color = c.onSurfaceVariant)
            savedKeys.forEach { provider ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(provider.label, "Key saved", Modifier.weight(1f))
                    TextAction("Remove", c.error) { state.removeKey(provider) }
                }
            }
            Field(key, { key = it.trim(); if (!checking) state.keyCheck = KeyCheckState.Idle }, "Paste key", Modifier.focusRequester(focus), password = true)
            val status = when (check) {
                is KeyCheckState.Checking -> "Checking with ${check.provider.label}…" to c.onSurfaceVariant
                is KeyCheckState.Invalid -> check.message to c.error
                is KeyCheckState.Valid -> when {
                    check.hasModels -> "${check.provider.label} key saved."
                    check.hasVoice -> "${check.provider.label} key saved for voice. It can't use any of Spendidly's chat models."
                    else -> "${check.provider.label} key saved, but it can't use any of Spendidly's models."
                } to c.primary
                KeyCheckState.Idle -> detectProvider(key)?.let { "${it.label} key" to c.onSurfaceVariant }
            }
            status?.let { (text, color) -> Text(text, style = Type.small, color = color) }
            PrimaryButton(if (checking) "Checking…" else "Check and save", enabled = key.isNotEmpty() && !checking) { state.checkAndSaveKey(key) }
        }
    }

    if (editCategory != null || newCategory) {
        val existing = editCategory
        var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
        var description by remember(existing) { mutableStateOf(existing?.description.orEmpty()) }
        var confirmDelete by remember(existing) { mutableStateOf(false) }
        val close = { editCategory = null; newCategory = false }
        Sheet(if (existing == null) "New category" else "Edit category", onDismiss = close) {
            Field(name, { if (it.length <= 100) name = it }, "Name", capitalize = true)
            Field(description, { if (it.length <= 500) description = it }, "What belongs here, e.g. rides, bus fares", singleLine = false, capitalize = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Save", enabled = name.isNotBlank()) { state.saveCategory(existing, name, description) { close() } }
                if (existing != null) {
                    if (!confirmDelete) TextAction("Delete", c.error) { confirmDelete = true }
                    else TextAction("Tap to confirm delete", c.error) { state.deleteCategory(existing); close() }
                }
            }
        }
    }

    if (demo) Sheet("Load demo data?", onDismiss = { demo = false }) {
        Text(
            if (state.expenses.isEmpty()) "Adds about 250 fictional expenses from the last two months, ending today, plus the categories they use."
            else "Adds about 250 fictional expenses ending today alongside your own. They're mixed in with your real entries; Erase everything removes both.",
            style = Type.small, color = c.onSurfaceVariant,
        )
        PrimaryButton("Load demo data", enabled = true) { state.loadDemoData(); demo = false }
    }

    if (erase) {
        var typed by remember { mutableStateOf("") }
        Sheet("Erase everything?", onDismiss = { erase = false }) {
            Text("This removes every expense, category and saved key from this phone. The currency stays as it is. Type erase to confirm.", style = Type.small, color = c.onSurfaceVariant)
            Field(typed, { typed = it }, "erase")
            PrimaryButton("Erase everything", enabled = typed.trim().equals("erase", ignoreCase = true), danger = true) { state.eraseEverything(); erase = false }
        }
    }
}

/** An example amount for showing a currency's format: 1,234.50, or 1,234 without decimals. */
private val AppCurrency.sample: Long get() = if (decimals > 0) 1_234_50L else 1_234_00L

/** "Euro (EUR)"; toman has no ISO code to show. */
private val AppCurrency.title: String get() = if (this == AppCurrency.IRT) displayName else "$displayName ($code)"

@Composable
private fun Section(title: String) {
    Text(title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
private fun Label(title: String, sub: String, modifier: Modifier, danger: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = if (danger) c.error else c.onSurface)
        Text(sub, style = Type.small, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerHigh).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = Type.heading.copy(fontSize = Type.heading.fontSize * 1.1f), color = c.onSurface)
            content()
        }
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, singleLine: Boolean = true, capitalize: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHighest).padding(horizontal = 16.dp, vertical = 15.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.onSurfaceVariant)
        BasicTextField(
            value, onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
            textStyle = Type.body.copy(color = c.onSurface), cursorBrush = SolidColor(c.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done,
                capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None, autoCorrectEnabled = !password,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg = if (!enabled) c.onSurface.copy(alpha = 0.1f) else if (danger) c.error else c.primary
    Text(
        text, style = Type.label, color = if (!enabled) c.onSurface.copy(alpha = 0.38f) else if (danger) c.onError else c.onPrimary,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
    )
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(text, style = Type.label, color = color, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp))
}

/**
 * Drag-to-reorder for keyed rows of a LazyColumn. The dragged row follows the finger, swaps with the row
 * its middle passes over, and scrolls the list near the edges; neighbours slide via animateItem().
 */
private class Reorder(
    private val list: LazyListState, private val scope: CoroutineScope,
    private val edge: Float, private val maxSpeed: Float,
) {
    var items by mutableStateOf<List<Category>>(emptyList()); private set
    var dragging by mutableStateOf<String?>(null); private set
    var settling by mutableStateOf<String?>(null); private set
    val settle = Animatable(0f)
    private var delta by mutableFloatStateOf(0f)
    private var startOffset = 0
    private var startPointer = 0f
    private var awaitIndex: Int? = null
    private var ending = false
    private var scroller: Job? = null

    private fun info() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragging }

    /** How far the dragged row is drawn from its slot in the list. */
    val offset: Float get() = info()?.let { startOffset + delta - it.offset } ?: 0f

    fun start(id: String, current: List<Category>, pointerY: Float): Boolean {
        if (dragging != null) return false
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return false
        items = current; startOffset = item.offset; startPointer = pointerY; delta = 0f; awaitIndex = null; dragging = id
        return true
    }

    fun dragTo(pointerY: Float) {
        if (dragging == null || ending) return
        delta = pointerY - startPointer
        swap()
        if (scroller?.isActive != true) scroller = scope.launch { follow() }
    }

    fun end(id: String, onDrop: (List<Category>) -> Unit) {
        if (dragging != id || ending) return
        ending = true
        scroller?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // A quick release can beat the layout of the last swap; let it land where it was let go.
            var frames = 0
            while (awaitIndex != null && frames++ < 6) { withFrameNanos {}; swap() }
            val from = offset
            onDrop(items)
            dragging = null
            ending = false
            settling = id
            settle.snapTo(from)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (settling == id) settling = null
        }
    }

    private fun swap() {
        val item = info() ?: return
        // Wait until the list has laid out the previous swap, or stale offsets would swap straight back.
        awaitIndex?.let { if (item.index != it) return else awaitIndex = null }
        val middle = startOffset + delta + item.size / 2f
        val ids = items.map { it.id }
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != dragging && it.key in ids && middle >= it.offset && middle <= it.offset + it.size
        } ?: return
        // Moving the first visible row would otherwise make the list follow it.
        if (list.firstVisibleItemIndex == item.index || list.firstVisibleItemIndex == target.index) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        val from = ids.indexOf(dragging)
        val to = ids.indexOf(target.key)
        items = items.toMutableList().apply { add(to, removeAt(from)) }
        awaitIndex = target.index
    }

    /** Each frame: finish a swap the last pointer event couldn't make yet, and scroll near the edges. */
    private suspend fun follow() {
        while (dragging != null) {
            val item = info() ?: return
            val top = startOffset + delta
            val bottom = top + item.size
            val end = list.layoutInfo.viewportEndOffset - list.layoutInfo.afterContentPadding
            val speed = when {
                top < edge -> -maxSpeed * ((edge - top) / edge).coerceIn(0f, 1f)
                bottom > end - edge -> maxSpeed * ((bottom - end + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed == 0f && awaitIndex == null) return
            if (speed != 0f) list.scrollBy(speed)
            swap()
            withFrameNanos {}
        }
    }
}
