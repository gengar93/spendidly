package io.github.gengar93.spendidly.backend

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The app's local backend, shared by screens in the current process. */
class SpendidlyBackend internal constructor(context: Context) {
    private val app = context.applicationContext
    private val database = LocalDatabase(app)
    private val prefs = app.getSharedPreferences("spendidly", Context.MODE_PRIVATE)
    val expenses = ExpenseStore(database)
    val providers = ProviderSettings(database, prefs)
    private val toolsText by lazy { app.assets.open("expense-tools.json").bufferedReader().use { it.readText() } }
    private var tools: Pair<AppCurrency, JSONArray>? = null
    val chat = ChatSession(expenses, providers, ProviderClient()) {
        val currency = currentCurrency
        synchronized(this) { tools?.takeIf { it.first == currency }?.second ?: expenseTools(toolsText, currency).also { tools = currency to it } }
    }
    val chats = ChatStore(database, chat)

    init {
        // The first launch fixes the region's currency, so a later region change doesn't relabel amounts.
        currentCurrency = AppCurrency.fromCode(prefs.getString("currency", null)) ?: AppCurrency.regionDefault().also(::saveCurrency)
        currentColours = ColourSetting(prefs.getString("colours", null), prefs.getBoolean("vivid_colours", true))
    }

    /** One currency per install. Changing it only relabels existing amounts; nothing is converted. */
    var currency: AppCurrency
        get() = currentCurrency
        set(value) { saveCurrency(value); currentCurrency = value }

    private fun saveCurrency(value: AppCurrency) = prefs.edit().putString("currency", value.code).apply()

    /** The app's and widgets' colours. Applies at once; see [ColourSetting]. */
    var colours: ColourSetting
        get() = currentColours
        set(value) { saveColours(value); currentColours = value }

    private fun saveColours(value: ColourSetting) =
        prefs.edit().putString("colours", value.palette).putBoolean("vivid_colours", value.vivid).apply()

    /** The provider the Ask screen uses. Picking a model in Settings sets it. */
    var activeProvider: ModelProvider
        get() = prefs.getString("provider", null)?.let { runCatching { ModelProvider.valueOf(it) }.getOrNull() } ?: ModelProvider.ANTHROPIC
        set(value) { prefs.edit().putString("provider", value.name).apply() }

    /** Tests a pasted key with its provider. The key is not saved; see [ProviderSettings.saveKey]. */
    suspend fun checkKey(key: String): KeyCheck = withContext(Dispatchers.IO) { io.github.gengar93.spendidly.backend.checkKey(key) }

    /**
     * The voice model last picked in Settings, as "PROVIDER:id"; null means the first one a saved key can use.
     * A pick of OpenRouter's Gemini, no longer offered, becomes OpenRouter's default voice model.
     */
    var voiceChoice: String?
        get() = prefs.getString("voice_model", null)?.let { saved ->
            if (saved != RETIRED_VOICE_CHOICE) saved
            else voiceModels(ModelProvider.OPENROUTER).first().key.also { voiceChoice = it }
        }
        set(value) { prefs.edit().putString("voice_model", value).apply() }

    /** Voice models the saved keys can use, in [voiceModels] order. */
    suspend fun voiceOptions(): List<VoiceModel> {
        val usable = ModelProvider.entries.flatMap { providers.configuration(it).voiceModels }.toSet()
        return voiceModels.filter { it in usable }
    }

    /** Whether Spendidly has asked for the microphone before, to tell a permanent denial from a first one. */
    var micAsked: Boolean
        get() = prefs.getBoolean("mic_asked", false)
        set(value) { prefs.edit().putBoolean("mic_asked", value).apply() }

    private val voiceDir = File(app.cacheDir, "voice")

    /**
     * Starts recording a question for [model] to a fresh file in the cache, first removing any clip a crash left behind.
     * Category names and recent notes go along as spelling hints. The caller must hold RECORD_AUDIO.
     */
    fun startVoice(model: VoiceModel): VoiceSession {
        voiceDir.mkdirs()
        voiceDir.listFiles()?.forEach { it.delete() }
        return VoiceSession(model, File(voiceDir, "voice-${System.currentTimeMillis()}.wav")) {
            providers.credentials(model.provider).apiKey to voiceHints()
        }
    }

    /** Category names, then the 50 most recent distinct notes, kept short. */
    private fun voiceHints(): List<String> {
        val categories = expenses.getCategories().map { it.name.trim() }
        val notes = expenses.snapshot().asSequence().mapNotNull { it.note?.trim()?.takeIf { n -> n.isNotEmpty() && n.length <= 60 } }
            .distinctBy { it.lowercase() }.take(50).toList()
        return (categories + notes).distinctBy { it.lowercase() }.filter { it.isNotEmpty() && it.length <= 60 }
    }

    /** Whether chats unused for a week are removed automatically. */
    var autoDeleteChats: Boolean
        get() = prefs.getBoolean("auto_delete_chats", false)
        set(value) { prefs.edit().putBoolean("auto_delete_chats", value).apply() }

    /** Adds two months of fictional expenses ending today, for trying the app out. */
    suspend fun loadDemoData(): ImportSummary = withContext(Dispatchers.IO) { loadDemoData(database) }

    /** Reads a .spendidly backup or an expenses CSV without changing anything, so the UI can check its currency first. */
    suspend fun readImport(input: InputStream): ImportFile = withContext(Dispatchers.IO) { readImportFile(input) }

    suspend fun importData(file: ImportFile): ImportSummary = withContext(Dispatchers.IO) { importBundle(database, file) }

    /** Writes a .spendidly bundle of expenses and categories that [readImport] reads back. */
    suspend fun exportBackup(output: OutputStream): ExportSummary = withContext(Dispatchers.IO) { exportBundle(database, output, currency) }

    /** Writes the expenses alone as CSV. */
    suspend fun exportCsv(output: OutputStream): ExportSummary = withContext(Dispatchers.IO) { exportCsv(database, output) }

    /** The UI calls this after a user confirms the destructive reset. Leaves the app empty but keeps the currency and colours. */
    suspend fun resetAllData() {
        chat.reset(database::reset)
        prefs.edit().clear().apply()
        saveCurrency(currency)
        saveColours(colours)
    }
}

/** A curated palette's id, or null to follow the wallpaper. [vivid] applies only when following the wallpaper. */
data class ColourSetting(val palette: String? = null, val vivid: Boolean = true)

internal var currentColours by mutableStateOf(ColourSetting())
