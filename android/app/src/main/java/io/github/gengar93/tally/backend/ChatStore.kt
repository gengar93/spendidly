package io.github.gengar93.tally.backend

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
    val changes: List<String>,
    val filters: JSONObject?,
)

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
                val preview = last?.let { it.optString("reply").ifEmpty { it.optString("failure") } }.orEmpty()
                ChatSummary(row.getText(0), row.getText(1), row.getText(2), io.github.gengar93.tally.ui.plainPreview(preview))
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
    json("question" to it.question, "reply" to it.reply, "failure" to it.failure, "changes" to JSONArray(it.changes), "filters" to it.filters)
})

private fun parseTurns(text: String): List<ChatTurn> {
    val array = JSONArray(text)
    return (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        val changes = o.optJSONArray("changes")
        ChatTurn(
            o.getString("question"), o.value("reply") as? String, o.value("failure") as? String,
            if (changes == null) emptyList() else (0 until changes.length()).map(changes::getString),
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
