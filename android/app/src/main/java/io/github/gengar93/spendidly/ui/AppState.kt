package io.github.gengar93.spendidly.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gengar93.spendidly.backend.AppCurrency
import io.github.gengar93.spendidly.backend.Category
import io.github.gengar93.spendidly.backend.ChatOutcome
import io.github.gengar93.spendidly.backend.ChatSummary
import io.github.gengar93.spendidly.backend.ChatTurn
import io.github.gengar93.spendidly.backend.CuratedModel
import io.github.gengar93.spendidly.backend.ExpenseItem
import io.github.gengar93.spendidly.backend.ImportFile
import io.github.gengar93.spendidly.backend.KeyCheck
import io.github.gengar93.spendidly.backend.SpendidlyBackend
import io.github.gengar93.spendidly.backend.ModelProvider
import io.github.gengar93.spendidly.backend.ProviderConfiguration
import io.github.gengar93.spendidly.backend.UNSUPPORTED_KEY_MESSAGE
import io.github.gengar93.spendidly.backend.VoiceModel
import io.github.gengar93.spendidly.backend.VoiceRecorder
import io.github.gengar93.spendidly.backend.curatedModel
import io.github.gengar93.spendidly.backend.decimal
import io.github.gengar93.spendidly.backend.detectProvider
import io.github.gengar93.spendidly.backend.voiceModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

enum class Screen { Summary, History, Ask, Settings }

enum class VoiceStage { Idle, Recording, Transcribing }

/** Recordings stop on their own after a minute. */
const val VOICE_LIMIT_MS = 60_000L

data class Toast(val text: String, val id: Long = System.nanoTime(), val undo: (() -> Unit)? = null)

sealed interface KeyCheckState {
    data object Idle : KeyCheckState
    data class Checking(val provider: ModelProvider) : KeyCheckState
    /** [hasModels] is false when the key works but can't use any of Spendidly's chat models; [hasVoice] says whether it can do voice. */
    data class Valid(val provider: ModelProvider, val hasModels: Boolean, val hasVoice: Boolean = false) : KeyCheckState
    data class Invalid(val message: String) : KeyCheckState
}

class AskEntry(val question: String) {
    var pending by mutableStateOf(true)
    var reply by mutableStateOf<String?>(null)
    var failure by mutableStateOf<String?>(null)
    var rows by mutableStateOf<List<ExpenseItem>>(emptyList())
    var filters: JSONObject? = null
    var changes by mutableStateOf<List<String>>(emptyList())
    var deletion by mutableStateOf<Pair<String, JSONObject>?>(null)
}

/** Screen state backed by the local backend. All writes go through here so lists stay current. */
class AppState(private val backend: SpendidlyBackend, private val scope: CoroutineScope) {
    var expenses by mutableStateOf<List<ExpenseItem>>(emptyList()); private set
    var categories by mutableStateOf<List<Category>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var screen by mutableStateOf(Screen.Summary)
    var previous = Screen.Summary
    var toast by mutableStateOf<Toast?>(null)
    var historyQuery by mutableStateOf("")
    var historyCategory by mutableStateOf<String?>(null)

    var provider by mutableStateOf(backend.activeProvider); private set
    var providerConfigs by mutableStateOf<Map<ModelProvider, ProviderConfiguration>>(emptyMap()); private set

    val asks = mutableStateListOf<AskEntry>()
    var askBusy by mutableStateOf(false); private set
    var chats by mutableStateOf<List<ChatSummary>>(emptyList()); private set
    var chatOpen by mutableStateOf(false); private set
    var chatId by mutableStateOf<String?>(null); private set
    var autoDeleteChats by mutableStateOf(backend.autoDeleteChats); private set
    /** Chat ids picked in multi-select; empty means not selecting. */
    var selectedChats by mutableStateOf<Set<String>>(emptySet())
    private var chatRoute by mutableStateOf<Pair<ModelProvider, String>?>(null)

    /** The provider a reopened chat was held with, when it no longer matches the current settings. */
    val chatLockedTo: ModelProvider?
        get() = chatRoute?.takeIf { (p, m) -> p != provider || providerConfigs[p]?.model != m }?.first

    /** Why a reopened chat is read-only, naming the model it used. */
    val chatLockedNote: String?
        get() = chatLockedTo?.let { p ->
            val used = chatRoute?.second ?: return@let null
            curatedModel(p, used)?.let { "Read-only. This chat used ${it.name}; pick it under Model in Settings to continue." }
                ?: "Read-only. This chat used $used, which Spendidly no longer offers."
        }

    /** The model Ask uses now, if its provider has a key. */
    val currentModel: CuratedModel?
        get() = providerConfigs[provider]?.takeIf { it.configured }?.let { curatedModel(it.provider, it.model) }

    /** The open chat's model, e.g. "Claude Haiku 5.5 (claude-haiku-5-5)", for reporting a reply. */
    val chatModelLabel: String?
        get() = chatRoute?.let { (p, m) -> curatedModel(p, m)?.let { "${it.name} ($m)" } ?: m }
            ?: currentModel?.let { "${it.name} (${it.id})" }

    /** Result of the last pasted key, shown in the keys sheet. */
    var keyCheck by mutableStateOf<KeyCheckState>(KeyCheckState.Idle)

    val today: LocalDate get() = LocalDate.now(ZoneId.systemDefault())

    /** From which amount an expense counts as large on Summary; null until there are enough expenses. */
    var largePaise by mutableStateOf<Long?>(null); private set

    private fun setData(items: List<ExpenseItem>, cats: List<Category>) {
        expenses = items
        categories = cats
        largePaise = largeThreshold(items, today)
    }

    /** The expense table, open while non-null. */
    var tableFilter by mutableStateOf<TableFilter?>(null); private set
    fun openTable(filter: TableFilter) { tableFilter = filter }
    fun closeTable() { tableFilter = null }
    fun tableFor(filters: JSONObject) = TableFilter.fromModel(filters, categories.associate { it.id to it.name })

    fun refresh() = scope.launch {
        val (items, cats) = withContext(Dispatchers.IO) { backend.expenses.snapshot() to backend.expenses.getCategories() }
        setData(items, cats)
        loaded = true
        providerConfigs = ModelProvider.entries.associateWith { backend.providers.configuration(it) }
        // Keep Ask on a provider that has a key, e.g. after its key is removed.
        if (providerConfigs[provider]?.configured != true) {
            providerConfigs.values.firstOrNull { it.configured && it.models.isNotEmpty() }?.let { selectProvider(it.provider) }
        }
        if (backend.autoDeleteChats) backend.chats.deleteOlderThan(7)
        chats = backend.chats.list()
    }

    fun go(to: Screen) {
        // Settings returns to where it was opened from; reopening it must not make it its own way back.
        if (to == Screen.Settings && screen != Screen.Settings) previous = screen
        screen = to
    }

    /** Opens Settings from anywhere, closing a full-screen chat first so Settings isn't hidden behind it. */
    fun openSettings() {
        closeChat()
        go(Screen.Settings)
    }

    fun show(text: String, undo: (() -> Unit)? = null) { toast = Toast(text, undo = undo) }

    private fun JSONObject.errorMessage(): String? =
        if (optBoolean("ok")) null else optJSONObject("error")?.optString("message") ?: "Something went wrong."

    fun add(paise: Long, date: LocalDate, category: Category?, note: String) = scope.launch {
        val args = JSONObject().put("amount", decimal(paise)).put("spent_on", date.toString())
            .put("category_id", category?.id ?: JSONObject.NULL)
        if (note.isNotBlank()) args.put("description", note.trim())
        val result = withContext(Dispatchers.IO) { backend.expenses.addExpense(args) }
        result.errorMessage()?.let { show(it); return@launch }
        val id = result.getJSONObject("expense").getString("id")
        refresh()
        show("${rupees(paise)} saved to ${category?.name ?: "Uncategorized"}") { remove(id, announce = false) }
    }

    fun update(item: ExpenseItem, paise: Long, date: LocalDate, category: Category?, note: String) = scope.launch {
        val changes = JSONObject().put("amount", decimal(paise)).put("spent_on", date.toString())
            .put("category_id", category?.id ?: JSONObject.NULL)
            .put("description", note.trim().ifEmpty { null } ?: JSONObject.NULL)
        val result = withContext(Dispatchers.IO) {
            backend.expenses.updateExpense(JSONObject().put("expense_id", item.id).put("changes", changes))
        }
        result.errorMessage()?.let { show(it); return@launch }
        refresh()
        show("Changes saved")
    }

    fun remove(id: String, announce: Boolean = true) = scope.launch {
        val result = withContext(Dispatchers.IO) {
            val preview = backend.expenses.previewDeletion(id)
            if (!preview.optBoolean("ok")) preview else backend.expenses.deleteExpense(preview.getJSONObject("expense"))
        }
        result.errorMessage()?.let { show(it); return@launch }
        refresh()
        if (announce) show("Expense deleted") {
            scope.launch { withContext(Dispatchers.IO) { backend.expenses.restoreExpense(id) }; refresh() }
        } else show("Expense removed")
    }

    fun saveCategory(existing: Category?, name: String, description: String, done: () -> Unit) = scope.launch {
        val result = withContext(Dispatchers.IO) {
            if (existing == null) backend.expenses.createCategory(name, description)
            else backend.expenses.updateCategory(existing.id, name, description)
        }
        result.errorMessage()?.let { show(it); return@launch }
        refresh(); done()
    }

    fun deleteCategory(category: Category) = scope.launch {
        withContext(Dispatchers.IO) { backend.expenses.deleteCategory(category.id) }
        refresh()
        show("${category.name} deleted. Its expenses are now Uncategorized.")
    }

    /** Applies a new category order at once, then saves it. */
    fun reorderCategories(order: List<Category>) {
        if (order.map { it.id } == categories.map { it.id }) return
        categories = order
        scope.launch {
            withContext(Dispatchers.IO) { backend.expenses.reorderCategories(order.map { it.id }) }
            refresh()
        }
    }

    /** Names of categories that have expenses, in the user's order, then any others (e.g. Uncategorized). */
    fun usedCategoryNames(): List<String> {
        val used = expenses.mapTo(HashSet()) { it.categoryName }
        val named = categories.map { it.name }.filter { it in used }
        return named + (used - named.toSet()).sorted()
    }

    private fun selectProvider(value: ModelProvider) {
        backend.activeProvider = value
        provider = value
    }

    /** Picking a model also picks its provider. */
    fun chooseModel(model: CuratedModel) = scope.launch {
        backend.providers.setModel(model.provider, model.id).errorMessage()?.let { show(it); return@launch }
        selectProvider(model.provider)
        refresh()
    }

    /** Detects the provider from the key, tests it once, and saves it only if the provider accepts it. */
    fun checkAndSaveKey(key: String) {
        if (keyCheck is KeyCheckState.Checking) return
        val detected = detectProvider(key) ?: run { keyCheck = KeyCheckState.Invalid(UNSUPPORTED_KEY_MESSAGE); return }
        keyCheck = KeyCheckState.Checking(detected)
        scope.launch {
            when (val check = backend.checkKey(key)) {
                is KeyCheck.Invalid -> keyCheck = KeyCheckState.Invalid(check.message)
                is KeyCheck.Valid -> {
                    backend.providers.saveKey(check.provider, key, check.models, check.voice).errorMessage()?.let { keyCheck = KeyCheckState.Invalid(it); return@launch }
                    // The first key becomes the one Ask uses; later keys leave the current model alone.
                    if (providerConfigs[provider]?.configured != true) selectProvider(check.provider)
                    refresh().join()
                    val saved = providerConfigs[check.provider]
                    keyCheck = KeyCheckState.Valid(check.provider, saved?.models.orEmpty().isNotEmpty(), saved?.voiceModels.orEmpty().isNotEmpty())
                }
            }
        }
    }

    fun removeKey(value: ModelProvider) = scope.launch {
        backend.providers.clear(value)
        keyCheck = KeyCheckState.Idle
        refresh()
        show("${value.label} key removed")
    }

    /** Switches the install's currency. Existing amounts keep their numbers; nothing is converted. */
    fun chooseCurrency(value: AppCurrency) {
        if (value == backend.currency) return
        backend.currency = value
        show("Amounts now show in ${value.displayName}")
    }

    /** A backup in another currency, waiting for the user to confirm importing it as it is. */
    var pendingImport by mutableStateOf<ImportFile?>(null); private set

    /** Reads a .spendidly backup or a CSV, and imports it unless its currency differs from the app's. */
    fun import(context: Context, uri: Uri) = scope.launch {
        try {
            val file = context.contentResolver.openInputStream(uri)?.use { backend.readImport(it) }
                ?: run { show("That file couldn't be opened."); return@launch }
            if (file.currency != null && !file.currency.equals(backend.currency.code, ignoreCase = true)) pendingImport = file
            else applyImport(file)
        } catch (error: Exception) {
            show(error.message ?: "That file couldn't be imported.")
        }
    }

    fun resolveImport(approved: Boolean) {
        val file = pendingImport ?: return
        pendingImport = null
        if (approved) scope.launch { applyImport(file) }
    }

    private suspend fun applyImport(file: ImportFile) {
        try {
            val summary = backend.importData(file)
            refresh()
            val skipped = if (summary.expensesSkipped > 0) ", skipped ${summary.expensesSkipped} already here" else ""
            show("Imported ${summary.expensesAdded} expenses and ${summary.categoriesAdded} new categories$skipped")
        } catch (error: Exception) {
            show(error.message ?: "That file couldn't be imported.")
        }
    }

    /** Saves a backup (.spendidly) or the expenses alone as CSV to a file the user picked. */
    fun export(context: Context, uri: Uri, csvOnly: Boolean) = scope.launch {
        try {
            val summary = context.contentResolver.openOutputStream(uri)?.use { if (csvOnly) backend.exportCsv(it) else backend.exportBackup(it) }
                ?: run { show("That file couldn't be created."); return@launch }
            val expenses = "${summary.expenses} expense${if (summary.expenses == 1) "" else "s"}"
            show(if (csvOnly) "Exported $expenses" else "Exported $expenses and ${summary.categories} categor${if (summary.categories == 1) "y" else "ies"}")
        } catch (error: Exception) {
            show(error.message ?: "The export couldn't be saved.")
        }
    }

    fun loadDemoData() = scope.launch {
        val summary = runCatching { backend.loadDemoData() }.getOrElse { show(it.message ?: "Demo data couldn't be loaded."); return@launch }
        refresh()
        screen = Screen.Summary
        show("Added ${summary.expensesAdded} demo expenses")
    }

    fun eraseEverything() = scope.launch {
        cancelVoice()
        backend.resetAllData()
        voiceChoice = null
        asks.clear()
        chatOpen = false
        chatId = null
        selectedChats = emptySet()
        autoDeleteChats = backend.autoDeleteChats
        provider = backend.activeProvider
        keyCheck = KeyCheckState.Idle
        historyQuery = ""; historyCategory = null
        refresh()
        screen = Screen.Summary
        show("Everything erased")
    }

    // Ask

    val hasKey: Boolean get() = providerConfigs[provider]?.configured == true

    /** Set when the chat input should take focus and raise the keyboard (e.g. from the widget). */
    var focusChatInput by mutableStateOf(false)

    /** Opens an empty chat in the full-screen chat view. */
    fun newChat(focus: Boolean = false) {
        if (askBusy) return
        cancelVoice()
        focusChatInput = focus
        asks.clear()
        chatRoute = null
        chatId = java.util.UUID.randomUUID().toString()
        chatOpen = true
        scope.launch { backend.chats.startNew() }
    }

    fun openChat(id: String) {
        if (askBusy) return
        cancelVoice()
        scope.launch {
            val opened = try { backend.chats.open(id) } catch (error: Exception) { show(error.message ?: "That chat couldn't be opened."); return@launch }
            asks.clear()
            chatRoute = if (opened.provider != null && opened.model != null) opened.provider to opened.model else null
            asks.addAll(opened.turns.map { t ->
                AskEntry(t.question).apply {
                    pending = false; reply = t.reply; failure = t.failure; changes = t.changes
                    filters = t.filters; rows = t.filters?.let(::matching).orEmpty()
                }
            })
            chatId = id
            chatOpen = true
        }
    }

    fun closeChat() { cancelVoice(); chatOpen = false }

    fun deleteChat(id: String) = deleteChats(setOf(id))

    fun deleteChats(ids: Set<String>) = scope.launch {
        backend.chats.delete(ids)
        if (chatId in ids) { chatOpen = false; chatId = null; asks.clear() }
        selectedChats = emptySet()
        chats = backend.chats.list()
        show(if (ids.size == 1) "Chat deleted" else "${ids.size} chats deleted")
    }

    fun toggleChat(id: String) {
        selectedChats = if (id in selectedChats) selectedChats - id else selectedChats + id
    }

    fun setAutoDeleteChats(on: Boolean) = scope.launch {
        backend.autoDeleteChats = on
        autoDeleteChats = on
        if (on) {
            val removed = backend.chats.deleteOlderThan(7)
            chats = backend.chats.list()
            if (removed > 0) show("Removed $removed chat${if (removed == 1) "" else "s"} older than a week")
        }
    }

    /** Sends a question in the open chat, or starts a new chat with it (e.g. from holding a bar on Summary). */
    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || askBusy) return
        val fresh = !chatOpen
        if (fresh) {
            asks.clear()
            chatRoute = null
            chatId = java.util.UUID.randomUUID().toString()
            chatOpen = true
        }
        val entry = AskEntry(text)
        asks.add(entry)
        askBusy = true
        scope.launch {
            if (fresh) backend.chats.startNew()
            val outcome = runCatching { backend.chat.send(provider, text) }.getOrElse { ChatOutcome(failure = io.github.gengar93.spendidly.backend.ChatFailure("FAILED", it.message ?: "The request failed.")) }
            apply(entry, outcome)
        }
    }

    fun resolveDeletion(entry: AskEntry, approved: Boolean) {
        val (token, _) = entry.deletion ?: return
        entry.deletion = null
        entry.pending = true
        askBusy = true
        scope.launch { apply(entry, backend.chat.resolveDeletion(token, approved)) }
    }

    private suspend fun apply(entry: AskEntry, outcome: ChatOutcome) {
        entry.reply = outcome.reply
        entry.failure = outcome.failure?.message
        entry.deletion = outcome.deletion?.let { it.token to it.expense }
        entry.changes = outcome.changes.map { change ->
            val e = change.expense
            val verb = change.action.name.lowercase().replaceFirstChar { it.uppercase() }
            "$verb ${rupees(decimalPaise(e.optString("amount")) ?: 0)} · ${e.optString("category_name")}" +
                (e.optString("description").takeIf { it.isNotEmpty() && it != "null" }?.let { " · $it" } ?: "")
        }
        if (outcome.changes.isNotEmpty()) {
            val (items, cats) = withContext(Dispatchers.IO) { backend.expenses.snapshot() to backend.expenses.getCategories() }
            setData(items, cats)
        }
        entry.filters = outcome.tables.lastOrNull()?.let { it.optJSONObject("table")?.optJSONObject("initial_filters") ?: it.optJSONObject("initial_filters") }
        entry.rows = entry.filters?.let(::matching).orEmpty()
        entry.pending = false
        askBusy = false
        chatId?.let { id ->
            runCatching {
                backend.chats.save(id, asks.filter { !it.pending }.map { ChatTurn(it.question, it.reply, it.failure, it.changes, it.filters) })
                chats = backend.chats.list()
            }
        }
    }

    // Voice

    var voiceStage by mutableStateOf(VoiceStage.Idle); private set
    /** How long the current recording runs, and its loudness (0 to 1) for the meter. */
    var voiceMillis by mutableStateOf(0L); private set
    var voiceLevel by mutableStateOf(0f); private set
    /** Set when the open chat should start recording at once (the widget's mic). */
    var voiceRequest by mutableStateOf(false)
    private var voiceChoice by mutableStateOf(backend.voiceChoice)
    private var recorder: VoiceRecorder? = null
    private var voiceJob: Job? = null

    /** Voice models the saved keys can use, in order of preference. */
    val voiceOptions: List<VoiceModel>
        get() = voiceModels.filter { m -> providerConfigs[m.provider]?.voiceModels?.contains(m) == true }

    /** The voice model in use: the one picked in Settings, else the first available. Null when no key supports voice. */
    val voiceModel: VoiceModel?
        get() = voiceOptions.let { options -> options.firstOrNull { it.key == voiceChoice } ?: options.firstOrNull() }

    var micAsked: Boolean
        get() = backend.micAsked
        set(value) { backend.micAsked = value }

    /** Set to open the API keys sheet when Settings shows. */
    var showKeys by mutableStateOf(false)

    /** Leaves the chat for Settings with the API keys sheet open. */
    fun openKeys() {
        closeChat()
        keyCheck = KeyCheckState.Idle
        showKeys = true
        go(Screen.Settings)
    }

    fun chooseVoice(model: VoiceModel) {
        backend.voiceChoice = model.key
        voiceChoice = model.key
    }

    /** Starts recording. The caller has checked [voiceModel] and the microphone permission. */
    fun startVoice() {
        if (voiceStage != VoiceStage.Idle || askBusy || voiceModel == null) return
        val r = try { backend.startRecording() } catch (_: Exception) {
            show("Spendidly couldn't use the microphone. Another app may be using it.")
            return
        }
        recorder = r
        voiceMillis = 0; voiceLevel = 0f
        voiceStage = VoiceStage.Recording
        voiceJob = scope.launch {
            while (voiceStage == VoiceStage.Recording && recorder === r) {
                voiceMillis = r.millis; voiceLevel = r.level
                if (r.millis >= VOICE_LIMIT_MS) { stopVoice(); break }
                delay(100)
            }
        }
    }

    /** Stops recording, transcribes the clip and sends the transcript as the next question. */
    fun stopVoice() {
        val r = recorder ?: return
        val model = voiceModel ?: return cancelVoice()
        recorder = null
        voiceJob?.cancel()
        voiceStage = VoiceStage.Transcribing
        voiceJob = scope.launch {
            // The clip is deleted whatever happens, including a cancel while it's being sent.
            val audio = withContext(NonCancellable + Dispatchers.IO) { r.stop() }
            try {
                val problem = when {
                    r.millis < 600 -> "That was too short. Tap the mic, speak, then tap stop."
                    r.peak < 0.005f -> "Spendidly didn't hear anything. Check the microphone and try again."
                    else -> null
                }
                if (problem != null) {
                    voiceStage = VoiceStage.Idle
                    show(problem)
                    return@launch
                }
                val text = try {
                    backend.transcribe(model, audio)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    voiceStage = VoiceStage.Idle
                    show(error.message ?: "Voice didn't work. Try again.")
                    return@launch
                }
                voiceStage = VoiceStage.Idle
                ask(text)
            } finally {
                audio.delete()
            }
        }
    }

    /** Drops the recording or transcription in progress; nothing is sent. */
    fun cancelVoice() {
        voiceJob?.cancel()
        voiceJob = null
        recorder?.let { r -> recorder = null; scope.launch(Dispatchers.IO) { r.cancel() } }
        voiceStage = VoiceStage.Idle
    }

    /** Applies list_expenses filters to the local snapshot so answers can show the actual entries. */
    private fun matching(filters: JSONObject): List<ExpenseItem> {
        val start = filters.optString("start_date").takeIf { it.length == 10 }?.let(LocalDate::parse)
        val end = filters.optString("end_date").takeIf { it.length == 10 }?.let(LocalDate::parse)
        val ids = filters.optJSONArray("category_ids")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getString(it) } }
        val min = filters.optString("min_amount").let(::decimalPaise)
        val max = filters.optString("max_amount").let(::decimalPaise)
        val search = filters.optString("description_search").takeIf { it.isNotBlank() && it != "null" }?.lowercase()
        return expenses.filter { e ->
            (start == null || !e.date.isBefore(start)) && (end == null || !e.date.isAfter(end)) &&
                (ids == null || e.categoryId in ids) && (min == null || e.amountPaise >= min) && (max == null || e.amountPaise <= max) &&
                (search == null || e.note?.lowercase()?.contains(search) == true)
        }.take(50)
    }
}
