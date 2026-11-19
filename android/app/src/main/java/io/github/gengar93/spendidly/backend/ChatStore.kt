package io.github.gengar93.spendidly.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock

/** A reopened chat: its transcript and the provider and model it is bound to, if any. */
data class OpenedChat(val turns: List<ChatTurn>, val provider: ModelProvider?, val model: String?)

data class ChatSummary(val id: String, val title: String, val updatedAt: String, val preview: String)

/** One question and its outcome as the screen shows it. [filters] recreate the listed entries. */
data class ChatTurn(
    val question: String,
    val reply: String?,
    val failure: String?,
    val changes: List<ChatChange>,
    val filters: JSONObject?,
)

/** What Undo did to a "Deleted" chip: restored the expense, or found it no longer exists. */
enum class Restore { RESTORED, MISSING }

/**
 * One expense change as a chip, e.g. "Deleted ₹30 · Food & Snacks · Coffee". [deleted] is the expense a "Deleted" chip
 * can restore (null for other chips and chats saved before Undo). [noted]: the model has been told about the restore.
 */
data class ChatChange(
    val text: String,
    val deleted: JSONObject? = null,
    val restore: Restore? = null,
    val noted: Boolean = false,
) {
    val expenseId: String? get() = deleted?.optString("id")?.ifEmpty { null }

    /** The chip as shown. */
    val label: String
        get() = when (restore) {
            Restore.RESTORED -> "Restored" + text.removePrefix("Deleted")
            Restore.MISSING -> "Couldn't restore" + text.removePrefix("Deleted") + ". It no longer exists."
            null -> text
        }
}

/** Saved chats: the screen transcript plus the model conversation, so a chat can be continued later. */
class ChatStore internal constructor(
    private val database: LocalDatabase,
    private val session: ChatSession,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun list(): List<ChatSummary> = withContext(Dispatchers.IO) {
        database.read {
            rows("SELECT id, title, updated_at, transcript FROM chats ORDER BY updated_at DESC") { row ->
                val turns = JSONArray(row.getText(3))
                val last = if (turns.length() == 0) null else turns.getJSONObject(turns.length() - 1)
                val preview = last?.let { t ->
                    (t.value("reply") as? String).orEmpty().ifEmpty { (t.value("failure") as? String).orEmpty() }
                        .ifEmpty { t.optJSONArray("changes")?.takeIf { it.length() > 0 }?.let { parseChange(it.get(0)).label }.orEmpty() }
                }.orEmpty()
                ChatSummary(row.getText(0), row.getText(1), row.getText(2), io.github.gengar93.spendidly.ui.plainPreview(preview))
            }
        }
    }

    /** Loads a chat into the live session and returns its transcript. */
    suspend fun open(id: String): OpenedChat {
        val (turns, history, route) = withContext(Dispatchers.IO) {
            database.read {
                rows("SELECT transcript, history, provider, model FROM chats WHERE id = ?", listOf(id)) { row ->
                    val provider = row.textOrNull(2)?.let { runCatching { ModelProvider.valueOf(it) }.getOrNull() }
                    val model = row.textOrNull(3)
                    Triple(parseTurns(row.getText(0)), parseHistory(row.getText(1)), if (provider != null && model != null) provider to model else null)
                }.firstOrNull() ?: fail("CHAT_NOT_FOUND", "That chat no longer exists.")
            }
        }
        session.restore(history, route)
        return OpenedChat(turns, route?.first, route?.second)
    }

    /** Starts an empty live session. */
    suspend fun startNew() = session.newChat()

    /** Saves the live session under [id] (creating it if new) with the given transcript. */
    suspend fun save(id: String, turns: List<ChatTurn>) {
        if (turns.isEmpty()) return
        val (history, route) = session.export()
        write(id, turns, history, route)
    }

    /**
     * Saves a chat while a turn is still running, with the history from [progress]. It doesn't wait for the live
     * session, which the running turn holds. The final [save] replaces it when the turn ends.
     */
    suspend fun saveProgress(id: String, turns: List<ChatTurn>, progress: ChatProgress) {
        if (turns.isEmpty()) return
        write(id, turns, progress.history, progress.route)
    }

    /** Updates only a saved chat's transcript, e.g. after Undo, leaving its model conversation alone, even mid-turn. */
    suspend fun saveTranscript(id: String, turns: List<ChatTurn>) {
        if (turns.isEmpty()) return
        withContext(Dispatchers.IO) {
            database.write {
                execute("UPDATE chats SET updated_at = ?, transcript = ? WHERE id = ?", listOf(timestamp(clock), turnsJson(turns).toString(), id))
            }
        }
    }

    private suspend fun write(id: String, turns: List<ChatTurn>, history: List<ModelMessage>, route: Pair<ModelProvider, String>?) {
        withContext(Dispatchers.IO) {
            val now = timestamp(clock)
            val title = turns.first().question.trim().take(80)
            database.write {
                execute(
                    """INSERT INTO chats (id, title, created_at, updated_at, provider, model, history, transcript) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT(id) DO UPDATE SET updated_at = excluded.updated_at, provider = excluded.provider,
                       model = excluded.model, history = excluded.history, transcript = excluded.transcript""",
                    listOf(id, title, now, now, route?.first?.name, route?.second, historyJson(history).toString(), turnsJson(turns).toString()),
                )
            }
        }
    }

    suspend fun delete(id: String) = delete(listOf(id))

    suspend fun delete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        database.write { ids.forEach { execute("DELETE FROM chats WHERE id = ?", listOf(it)) } }
    }

    /** Removes chats not used for [days] days. Returns how many were removed. */
    suspend fun deleteOlderThan(days: Long): Int = withContext(Dispatchers.IO) {
        val cutoff = timestamp(Clock.offset(clock, java.time.Duration.ofDays(-days)))
        database.write {
            val count = rows("SELECT COUNT(*) FROM chats WHERE updated_at < ?", listOf(cutoff)) { it.getLong(0) }.single().toInt()
            execute("DELETE FROM chats WHERE updated_at < ?", listOf(cutoff))
            count
        }
    }
}

private fun turnsJson(turns: List<ChatTurn>) = JSONArray(turns.map {
    json("question" to it.question, "reply" to it.reply, "failure" to it.failure, "changes" to JSONArray(it.changes.map(::changeJson)), "filters" to it.filters)
})

/** Plain chips stay strings, as in chats saved before Undo; "Deleted" chips are objects. */
private fun changeJson(change: ChatChange): Any = if (change.deleted == null) change.text else json(
    "text" to change.text, "deleted" to change.deleted, "restore" to change.restore?.name, "noted" to change.noted,
)

private fun parseChange(value: Any): ChatChange {
    val o = value as? JSONObject ?: return ChatChange(value.toString())
    return ChatChange(
        o.getString("text"), o.optJSONObject("deleted"),
        (o.value("restore") as? String)?.let { runCatching { Restore.valueOf(it) }.getOrNull() },
        o.optBoolean("noted"),
    )
}

private fun parseTurns(text: String): List<ChatTurn> {
    val array = JSONArray(text)
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val changes = o.optJSONArray("changes")
        ChatTurn(
            o.getString("question"), o.value("reply") as? String, o.value("failure") as? String,
            if (changes == null) emptyList() else (0 until changes.length()).map { parseChange(changes.get(it)) },
            o.optJSONObject("filters"),
        )
    }
}

private fun historyJson(messages: List<ModelMessage>) = JSONArray(messages.map { m ->
    when (m) {
        is ModelMessage.User -> json("type" to "user", "text" to m.text)
        is ModelMessage.LocalReply -> json("type" to "local", "text" to m.text)
        is ModelMessage.Assistant -> json(
            "type" to "assistant", "text" to m.text, "wire" to m.wire,
            "calls" to JSONArray(m.calls.map { json("id" to it.id, "name" to it.name, "arguments" to it.arguments) }),
        )
        is ModelMessage.Results -> json(
            "type" to "results",
            "entries" to JSONArray(m.entries.map { json("id" to it.id, "content" to it.content, "failed" to it.failed) }),
        )
    }
})

private fun parseHistory(text: String): List<ModelMessage> {
    val array = JSONArray(text)
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        when (o.getString("type")) {
            "user" -> ModelMessage.User(o.getString("text"))
            "local" -> ModelMessage.LocalReply(o.getString("text"))
            "assistant" -> {
                val calls = o.getJSONArray("calls")
                ModelMessage.Assistant(
                    o.getString("text"),
                    (0 until calls.length()).map { calls.getJSONObject(it).let { c -> ToolCall(c.getString("id"), c.getString("name"), c.getString("arguments")) } },
                    o.getJSONObject("wire"),
                )
            }
            else -> {
                val entries = o.getJSONArray("entries")
                ModelMessage.Results((0 until entries.length()).map { entries.getJSONObject(it).let { e -> ToolResult(e.getString("id"), e.getString("content"), e.getBoolean("failed")) } })
            }
        }
    }
}
