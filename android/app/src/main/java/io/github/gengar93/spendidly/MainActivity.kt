package io.github.gengar93.spendidly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gengar93.spendidly.backend.ExpenseItem
import io.github.gengar93.spendidly.ui.AppState
import io.github.gengar93.spendidly.ui.AskScreen
import io.github.gengar93.spendidly.ui.ChatView
import io.github.gengar93.spendidly.ui.Dock
import io.github.gengar93.spendidly.ui.EntrySheet
import io.github.gengar93.spendidly.ui.HistoryScreen
import io.github.gengar93.spendidly.ui.SpendidlyTheme
import io.github.gengar93.spendidly.ui.LocalPalette
import io.github.gengar93.spendidly.ui.Screen
import io.github.gengar93.spendidly.ui.SettingsScreen
import io.github.gengar93.spendidly.ui.SummaryScreen
import io.github.gengar93.spendidly.ui.ToastHost

class MainActivity : ComponentActivity() {
    /** What a widget asked to open (quick add, a new chat, or a new chat recording); cleared once handled. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(io.github.gengar93.spendidly.widget.EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequest.value = intent?.getStringExtra(io.github.gengar93.spendidly.widget.EXTRA_OPEN)
        enableEdgeToEdge()
        val backend = (application as SpendidlyApplication).backend
        setContent {
            SpendidlyTheme {
                val scope = rememberCoroutineScope()
                val state = remember { AppState(backend, scope) }
                LaunchedEffect(Unit) { state.refresh() }
                // Widgets show a mic instead of the chat button once a saved key supports voice, and follow the colour setting.
                val voiceReady = state.voiceModel != null
                LaunchedEffect(state.expenses, io.github.gengar93.spendidly.ui.currency, io.github.gengar93.spendidly.ui.colours, voiceReady) { if (state.loaded) io.github.gengar93.spendidly.widget.updateWidgets(applicationContext) }
                App(state, openRequest.value) { openRequest.value = null }
            }
        }
    }
}

private sealed interface Entry {
    data object New : Entry
    data class Edit(val item: ExpenseItem) : Entry
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun App(state: AppState, open: String?, onOpened: () -> Unit) {
    val p = LocalPalette.current
    var entry by remember { mutableStateOf<Entry?>(null) }
    LaunchedEffect(open) {
        when (open) {
            io.github.gengar93.spendidly.widget.OPEN_ADD -> { state.closeChat(); entry = Entry.New }
            io.github.gengar93.spendidly.widget.OPEN_CHAT -> { entry = null; state.go(Screen.Ask); state.newChat(focus = true) }
            io.github.gengar93.spendidly.widget.OPEN_VOICE -> { entry = null; state.go(Screen.Ask); state.newChat(); state.voiceRequest = true }
        }
        if (open != null) onOpened()
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeVisible = WindowInsets.isImeVisible
    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 4.dp, bottom = nav + 100.dp)

    BackHandler(enabled = entry == null && !state.chatOpen && (state.screen == Screen.History || state.screen == Screen.Ask)) { state.go(Screen.Summary) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        when (state.screen) {
            Screen.Summary -> SummaryScreen(state, padding) { entry = Entry.Edit(it) }
            Screen.History -> HistoryScreen(state, padding) { entry = Entry.Edit(it) }
            Screen.Ask -> AskScreen(state, padding)
            Screen.Settings -> SettingsScreen(state, padding.let { PaddingValues(start = 16.dp, end = 16.dp, top = top + 4.dp, bottom = nav + 32.dp) })
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (state.screen == Screen.Settings || imeVisible) nav + 12.dp else 0.dp)) {
            // The open chat shows toasts above its own input.
            if (!state.chatOpen) ToastHost(state.toast, { state.toast = null }, Modifier.padding(bottom = 10.dp))
            if (state.screen != Screen.Settings && !(imeVisible && entry == null)) {
                // On Ask the + starts a new chat; everywhere else it adds an expense.
                Dock(state.screen, { state.go(it) }, { if (state.screen == Screen.Ask) state.newChat() else entry = Entry.New }, Modifier.padding(bottom = nav + 14.dp))
            }
        }
        // Keeps scrolled content from running under the status bar.
        Box(Modifier.fillMaxWidth().height(top).background(p.bg))
        if (state.chatOpen) ChatView(state)
        if (state.tableFilter != null) io.github.gengar93.spendidly.ui.ExpenseTable(state) { entry = Entry.Edit(it) }
        entry?.let { current ->
            EntrySheet(state, (current as? Entry.Edit)?.item) { entry = null }
        }
    }
}
