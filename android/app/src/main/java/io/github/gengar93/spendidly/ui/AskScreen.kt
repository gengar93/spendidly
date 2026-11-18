package io.github.gengar93.spendidly.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.gengar93.spendidly.SUPPORT_EMAIL
import io.github.gengar93.spendidly.backend.ChatSummary
import io.github.gengar93.spendidly.reportReply
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val starters = listOf(
    "How much on food this month?",
    "Biggest expenses last month",
    "This week vs last week",
)

/** The Ask tab: past chats. The + button starts a new one. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AskScreen(state: AppState, contentPadding: PaddingValues) {
    val c = MaterialTheme.colorScheme
    val selecting = state.selectedChats.isNotEmpty()
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = selecting) { state.selectedChats = emptySet() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            if (selecting) Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeaderButton(Glyph.Close, "Cancel selection") { state.selectedChats = emptySet() }
                Text("${state.selectedChats.size} selected", style = Type.heading, color = c.onSurface, modifier = Modifier.weight(1f))
                Text(
                    if (state.selectedChats.size == state.chats.size) "None" else "All", style = Type.label, color = c.primary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        state.selectedChats = if (state.selectedChats.size == state.chats.size) emptySet() else state.chats.map { it.id }.toSet()
                    }.padding(10.dp),
                )
                HeaderButton(Glyph.Delete, "Delete selected chats") { confirm = true }
            } else ScreenTitle("Ask")
        }
        if (!state.hasKey) item { NeedsKey(state) }
        if (state.chats.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = if (state.hasKey) 48.dp else 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Glyph.Ask, null, tint = c.onSecondaryContainer, modifier = Modifier.size(40.dp))
                }
                Text("No chats yet", style = Type.heading, color = c.onSurface, modifier = Modifier.padding(top = 16.dp))
                Text("Tap + to ask about your spending in plain words.", style = Type.body, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        } else item {
            val chats = state.chats
            if (!selecting) Text("Hold a chat to select several", style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 10.dp))
            SegmentedColumn(Modifier.padding(top = 4.dp)) {
                chats.forEachIndexed { i, chat ->
                    val picked = chat.id in state.selectedChats
                    SegmentedItem(
                        i, chats.size, verticalPadding = 18.dp, selected = picked,
                        onClick = { if (selecting) state.toggleChat(chat.id) else state.openChat(chat.id) },
                        onLongClick = { state.toggleChat(chat.id) },
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(if (picked) CircleShape else MaterialShapes.Cookie4Sided.toShape())
                                .background(if (picked) c.primary else c.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (picked) Glyph.Check else Glyph.Ask, null, tint = if (picked) c.onPrimary else c.onSecondaryContainer, modifier = Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chat.title, style = Type.body.copy(fontWeight = FontWeight.Medium), color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (chat.preview.isNotEmpty()) Text(chat.preview, style = Type.small, color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(when_(chat, state), style = Type.tiny, color = c.onSurfaceVariant, modifier = Modifier.align(Alignment.Top).padding(top = 2.dp))
                    }
                }
            }
        }
    }
    if (confirm) {
        val n = state.selectedChats.size
        Sheet(if (n == 1) "Delete this chat?" else "Delete $n chats?", onDismiss = { confirm = false }) {
            Text("They're removed from this phone. Your expenses aren't affected.", style = Type.small, color = c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delete", style = Type.label, color = c.onError, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.error).clickable { state.deleteChats(state.selectedChats); confirm = false }.padding(horizontal = 20.dp, vertical = 11.dp))
                Text("Cancel", style = Type.label, color = c.primary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { confirm = false }.padding(horizontal = 14.dp, vertical = 11.dp))
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

private fun when_(chat: ChatSummary, state: AppState): String {
    val at = runCatching { Instant.parse(chat.updatedAt).atZone(ZoneId.systemDefault()) }.getOrNull() ?: return ""
    return if (at.toLocalDate() == state.today) at.format(timeFormat).lowercase() else shortDate(at.toLocalDate(), state.today)
}

@Composable
private fun NeedsKey(state: AppState) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerLow).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ask needs an API key", style = Type.heading, color = c.onSurface)
        Text("Paste a key from Anthropic, OpenAI, Google or OpenRouter in Settings. Spendidly works out which one it is. Your expenses stay on this phone; only your question and the figures needed to answer it are sent.", style = Type.small, color = c.onSurfaceVariant)
        Text(
            "Open Settings", style = Type.label, color = c.onPrimary,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.primary).clickable { state.openSettings() }.padding(horizontal = 20.dp, vertical = 11.dp),
        )
    }
}

/** Full-screen conversation: bubbles for questions, answers on the surface, input docked on the keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatView(state: AppState) {
    val c = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var confirmDelete by remember { mutableStateOf(false) }
    val savedId = state.chatId?.takeIf { id -> state.chats.any { it.id == id } }
    val context = LocalContext.current
    var needsVoiceKey by remember { mutableStateOf(false) }
    var micBlocked by remember { mutableStateOf(false) }
    var askedBefore by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> state.startVoice()
            // Asked before and no rationale now: Android won't show the prompt again, so point to app settings.
            askedBefore && (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false -> micBlocked = true
            else -> state.show("Voice needs microphone access.")
        }
    }
    fun mic() {
        when {
            state.voiceModel == null -> needsVoiceKey = true
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> state.startVoice()
            else -> { askedBefore = state.micAsked; state.micAsked = true; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
    // From the widget: start once saved keys have loaded, which a cold start may not have done yet.
    val keysLoaded = state.providerConfigs.isNotEmpty()
    LaunchedEffect(state.voiceRequest, keysLoaded) {
        if (state.voiceRequest && keysLoaded) { state.voiceRequest = false; mic() }
    }
    // Leaving the app drops a recording, since Android would record silence in the background. A clip being transcribed still sends.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && state.voiceStage == VoiceStage.Recording) state.cancelVoice()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { state.closeChat() }
    BackHandler(enabled = state.voiceStage != VoiceStage.Idle) { state.cancelVoice() }
    LaunchedEffect(state.asks.size, state.asks.lastOrNull()?.pending) {
        if (state.asks.isNotEmpty()) list.animateScrollToItem(state.asks.size)
    }
    Column(Modifier.fillMaxSize().background(c.surface).clickableNoRipple {}.statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderButton(Glyph.Back, "Back to chats") { state.closeChat() }
            Text(
                state.asks.firstOrNull()?.question ?: "New chat", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (savedId != null && !state.askBusy) HeaderButton(Glyph.Delete, "Delete chat") { confirmDelete = true }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            if (state.asks.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                    Text("What would you like to know?", style = Type.title.copy(fontSize = Type.heading.fontSize * 1.2f), color = c.onSurface)
                    Text("Answers come from your own entries. You can also ask it to add or fix an expense.", style = Type.body, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (state.hasKey) FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        starters.forEach { s ->
                            Text(
                                s, style = Type.label, color = c.onSecondaryContainer,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.secondaryContainer)
                                    .clickable(role = Role.Button) { state.ask(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    } else NeedsKey(state)
                }
            }
            itemsIndexed(state.asks) { _, entry -> Turn(state, entry) }
        }
        val locked = state.chatLockedTo
        if (locked != null) Row(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(c.surfaceContainerHigh).padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.chatLockedNote ?: "Read-only. This chat used ${locked.label}.", style = Type.small, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("New chat", style = Type.label, color = c.onPrimary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.primary).clickable { state.newChat() }.padding(horizontal = 16.dp, vertical = 10.dp))
        } else {
            ToastHost(state.toast, { state.toast = null }, Modifier.padding(top = 4.dp, bottom = 6.dp))
            ChatInput(state, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp), ::mic)
        }
    }
    if (needsVoiceKey) Sheet("Voice needs a key", onDismiss = { needsVoiceKey = false }) {
        Text("Voice needs an OpenAI, Google or OpenRouter key. Add one in Settings → API keys.", style = Type.small, color = c.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open Settings", style = Type.label, color = c.onPrimary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.primary).clickable { needsVoiceKey = false; state.openKeys() }.padding(horizontal = 20.dp, vertical = 11.dp))
            Text("Not now", style = Type.label, color = c.primary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { needsVoiceKey = false }.padding(horizontal = 14.dp, vertical = 11.dp))
        }
    }
    if (micBlocked) Sheet("Microphone is off", onDismiss = { micBlocked = false }) {
        Text("To ask by voice, allow Microphone for Spendidly in app settings.", style = Type.small, color = c.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open app settings", style = Type.label, color = c.onPrimary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.primary).clickable {
                micBlocked = false
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }.padding(horizontal = 20.dp, vertical = 11.dp))
            Text("Not now", style = Type.label, color = c.primary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { micBlocked = false }.padding(horizontal = 14.dp, vertical = 11.dp))
        }
    }
    if (confirmDelete && savedId != null) Sheet("Delete this chat?", onDismiss = { confirmDelete = false }) {
        Text("The conversation is removed from this phone. Your expenses aren't affected.", style = Type.small, color = c.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Delete", style = Type.label, color = c.onError, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.error).clickable { state.deleteChat(savedId); confirmDelete = false }.padding(horizontal = 20.dp, vertical = 11.dp))
            Text("Cancel", style = Type.label, color = c.primary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { confirmDelete = false }.padding(horizontal = 14.dp, vertical = 11.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Turn(state: AppState, entry: AskEntry) {
    val c = MaterialTheme.colorScheme
    var reporting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                entry.question, style = Type.body, color = c.onPrimary,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.primary).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        if (entry.pending) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LoadingIndicator(Modifier.size(36.dp))
            Text("Working it out", style = Type.small, color = c.onSurfaceVariant)
        } else {
            entry.reply?.let { reply ->
                Column {
                    MarkdownText(reply, Modifier.padding(end = 12.dp))
                    // Play's AI-generated content policy needs a way to report replies; kept small and quiet.
                    Row(
                        Modifier.padding(top = 2.dp).offset(x = (-8).dp).clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClickLabel = "Report this reply") { reporting = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Glyph.Flag, null, tint = c.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        Text("Report", style = Type.tiny, color = c.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }
            entry.failure?.let { Text(it, style = Type.body, color = c.error) }
        }
        if (entry.changes.isNotEmpty()) Column(Modifier.clip(RoundedCornerShape(20.dp)).background(c.primaryContainer).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entry.changes.forEach { Text(it, style = Type.small, color = c.onPrimaryContainer) }
        }
        entry.deletion?.let { (_, expense) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Delete this expense?", style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
                Text(
                    "${rupees(decimalPaise(expense.optString("amount")) ?: 0)} · ${expense.optString("category_name")} · ${expense.optString("spent_on")}" +
                        (expense.optString("description").takeIf { it.isNotEmpty() && it != "null" }?.let { "\n$it" } ?: ""),
                    style = Type.small, color = c.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delete", style = Type.label, color = c.onError, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.error).clickable { state.resolveDeletion(entry, true) }.padding(horizontal = 18.dp, vertical = 10.dp))
                    Text("Keep it", style = Type.label, color = c.onSecondaryContainer, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.secondaryContainer).clickable { state.resolveDeletion(entry, false) }.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
        // Inline rows fill at most ~30% of the screen; the rest are a tap away in the table.
        val screenHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        val rows = entry.rows.take((screenHeight * 0.3f / ExpenseRowHeight).toInt().coerceIn(1, 12))
        val more = entry.rows.size - rows.size
        if (rows.isNotEmpty()) SegmentedColumn { rows.forEachIndexed { i, item -> ExpenseRow(item, i, rows.size) } }
        val filter = entry.filters?.takeIf { !entry.pending }?.let { remember(it, state.categories) { state.tableFor(it) } }
        val found = filter?.let { state.expenses.filter(it::matches) }.orEmpty()
        if (filter != null && found.isNotEmpty()) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.secondaryContainer)
                .clickable { state.openTable(filter) }.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.secondary), contentAlignment = Alignment.Center) {
                Icon(Glyph.Table, null, tint = c.onSecondary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (more > 0) "+$more more · Open in table" else "Open in table", style = Type.label.copy(fontWeight = FontWeight.SemiBold), color = c.onSecondaryContainer)
                Text("${found.size} entr${if (found.size == 1) "y" else "ies"} · ${rupees(found.sumOf { it.amountPaise })}", style = Type.tiny, color = c.onSecondaryContainer.copy(alpha = 0.8f))
            }
            Icon(Glyph.Chevron, null, tint = c.onSecondaryContainer, modifier = Modifier.size(20.dp))
        } else if (more > 0) Text("+$more more", style = Type.small, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
    }
    val reply = entry.reply
    if (reporting && reply != null) {
        val context = LocalContext.current
        Sheet("Report this reply?", onDismiss = { reporting = false }) {
            Text(
                "Spendidly opens your email app with a draft to Spendidly support. It includes this reply, your question, the app version and the model. You can edit it first, and nothing is sent until you send it.",
                style = Type.small, color = c.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open email", style = Type.label, color = c.onPrimary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.primary).clickable {
                    reporting = false
                    if (!reportReply(context, entry.question, reply, state.chatModelLabel)) state.show("No email app found. Write to $SUPPORT_EMAIL to report it.")
                }.padding(horizontal = 20.dp, vertical = 11.dp))
                Text("Cancel", style = Type.label, color = c.primary, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { reporting = false }.padding(horizontal = 14.dp, vertical = 11.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatInput(state: AppState, modifier: Modifier, onMic: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(state.focusChatInput) {
        if (state.focusChatInput && state.hasKey) {
            kotlinx.coroutines.delay(150)
            focus.requestFocus(); keyboard?.show()
        }
        state.focusChatInput = false
    }
    val voice = state.voiceStage
    LaunchedEffect(voice) { if (voice != VoiceStage.Idle) keyboard?.hide() }
    val enabled = state.hasKey && !state.askBusy
    val canSend = enabled && text.isNotBlank()
    fun send() { if (canSend) { state.ask(text); text = "" } }
    val shape = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.surfaceContainerHigh)
    if (voice != VoiceStage.Idle) {
        // Recording: cancel, elapsed time with a live level dot, and stop (which sends). Then transcribing.
        Row(modifier.then(shape).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Glyph.Close, if (voice == VoiceStage.Recording) "Cancel recording" else "Cancel", Color.Transparent, c.onSurfaceVariant) { state.cancelVoice() }
            Row(Modifier.weight(1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (voice == VoiceStage.Recording) {
                    val dot by animateDpAsState((10 + 12 * state.voiceLevel.coerceIn(0f, 1f) * 2).coerceAtMost(22f).dp, label = "level")
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(dot).clip(CircleShape).background(c.error)) }
                    Text("Listening", style = Type.body, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text("${clock(state.voiceMillis)} / ${clock(VOICE_LIMIT_MS)}", style = Type.small, color = c.onSurfaceVariant)
                } else {
                    LoadingIndicator(Modifier.size(28.dp))
                    Text("Transcribing…", style = Type.body, color = c.onSurfaceVariant)
                }
            }
            if (voice == VoiceStage.Recording) RoundButton(Glyph.Stop, "Stop and send", c.primary, c.onPrimary) { state.stopVoice() }
        }
        return
    }
    Row(
        modifier.then(shape).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) {
            if (text.isEmpty()) Text(if (state.hasKey) "Ask about your spending" else "Add a key in Settings to ask", style = Type.body, color = c.onSurfaceVariant)
            BasicTextField(
                text, { text = it }, enabled = state.hasKey, maxLines = 5,
                textStyle = Type.body.copy(color = c.onSurface), cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().then(androidx.compose.ui.Modifier.focusRequester(focus)),
            )
        }
        // The mic shows while the box is empty, send once there's text.
        if (text.isEmpty()) RoundButton(Glyph.Mic, "Ask by voice", Color.Transparent, if (state.askBusy) c.onSurfaceVariant.copy(alpha = 0.5f) else c.onSurfaceVariant, enabled = !state.askBusy) { onMic() }
        else RoundButton(Glyph.Send, "Send", if (canSend) c.primary else Color.Transparent, if (canSend) c.onPrimary else c.onSurfaceVariant.copy(alpha = 0.5f), enabled = canSend) { send() }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, background: Color, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(background).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** "0:07" for a duration in milliseconds. */
private fun clock(millis: Long): String {
    val seconds = millis / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
