package io.github.gengar93.spendidly.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Clock

enum class ExpenseAction { ADDED, UPDATED, DELETED }

data class ExpenseChange(val action: ExpenseAction, val expense: JSONObject)
data class ChatFailure(val code: String, val message: String)

/** Shown, and kept in the model history, when a turn that already changed expenses ends without a reply. */
const val UNFINISHED_REPLY = "The reply didn't finish."

/**
 * Reported right after a write succeeds, while the turn goes on. [changes] are the turn's changes so far, one per
 * expense. [history] is the conversation as it would stand if the turn ended here, so a chat saved now stays valid.
 */
class ChatProgress internal constructor(
    val changes: List<ExpenseChange>,
    internal val history: List<ModelMessage>,
    internal val route: Pair<ModelProvider, String>,
)

/** Changes (one per expense) and tables are cumulative for the turn. [sent]: the message, with its notes, is in the history. */
data class ChatOutcome(
    val reply: String? = null,
    val failure: ChatFailure? = null,
    val changes: List<ExpenseChange> = emptyList(),
    val tables: List<JSONObject> = emptyList(),
    val sent: Boolean = false,
)

class ChatSession internal constructor(
    private val expenses: ExpenseStore,
    private val settings: ProviderSettings,
    private val client: ModelClient,
    private val clock: Clock = Clock.systemUTC(),
    /** Built for the current currency, so the descriptions follow a change in Settings. */
    private val tools: () -> JSONArray,
) {
    private val mutex = Mutex()
    private val history = mutableListOf<ModelMessage>()
    private var route: Pair<ModelProvider, String>? = null

    /**
     * [restored] are expenses deleted earlier in this chat that the user has since restored with Undo; a note about
     * each goes before the message. [onText] gets the reply text so far as it streams in, on the turn's thread; it goes
     * back to empty when a round turns out to call tools, since only the last round's text is the reply.
     * [onProgress] runs after each successful add, update or delete, before the turn continues.
     */
    suspend fun send(
        provider: ModelProvider,
        text: String,
        restored: List<JSONObject> = emptyList(),
        onText: (String) -> Unit = {},
        onProgress: suspend (ChatProgress) -> Unit = {},
    ): ChatOutcome = serialized {
        val input = text.trim()
        if (input.isEmpty() || input.length > 20_000) {
            return@serialized unsuccessful("INVALID_MESSAGE", "Enter a message of 1–20,000 characters.")
        }
        if (history.size >= 290) return@serialized unsuccessful("CONTEXT_LIMIT", "This chat is getting long. Start a new chat.")
        val credentials = try {
            settings.credentials(provider)
        } catch (error: BackendProblem) {
            return@serialized unsuccessful(error.code, error.message.orEmpty())
        } catch (_: Exception) {
            return@serialized unsuccessful("SETTINGS_UNAVAILABLE", "Spendidly could not read the provider settings.")
        }
        val selectedRoute = provider to credentials.model
        if (route != null && route != selectedRoute) {
            return@serialized unsuccessful("PROVIDER_CHANGED", "Start a new chat to switch providers or models.")
        }
        // Notes go in the user's message rather than a message of their own, so roles still alternate for every provider.
        val notes = restored.joinToString("") { "${restoreNote(it)}\n" }
        val message = if (notes.isEmpty()) input else "$notes\n$input"
        val turn = Turn(input, (history + ModelMessage.User(message)).toMutableList(), selectedRoute, onText, onProgress)
        runTurn(turn, credentials)
    }

    suspend fun newChat() = serialized { clear() }

    /** Replaces the in-memory conversation with a saved one so follow-ups keep its context. */
    internal suspend fun restore(messages: List<ModelMessage>, savedRoute: Pair<ModelProvider, String>?) = serialized {
        clear()
        history.addAll(messages)
        route = savedRoute
    }

    /** The committed conversation and the provider and model it is bound to. */
    internal suspend fun export(): Pair<List<ModelMessage>, Pair<ModelProvider, String>?> = serialized { history.toList() to route }

    internal suspend fun reset(resetDatabase: () -> Unit) = serialized {
        resetDatabase()
        clear()
    }

    private fun clear() {
        history.clear()
        route = null
    }

    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private suspend fun runTurn(turn: Turn, credentials: Credentials): ChatOutcome {
        try {
            return withTimeout(120_000L) { continueTurn(turn, credentials) }
        } catch (_: TimeoutCancellationException) {
            return stop(turn, replyFailure(turn, "TURN_TIMEOUT", "The turn took too long to complete."))
        } catch (error: CancellationException) {
            stop(turn, replyFailure(turn, "TURN_CANCELLED", "The turn was interrupted."))
            throw error
        } catch (error: BackendProblem) {
            return stop(turn, replyFailure(turn, error.code, error.message.orEmpty()))
        } catch (_: Exception) {
            return stop(turn, replyFailure(turn, "TURN_FAILED", "Spendidly could not complete this turn."))
        }
    }

    private suspend fun continueTurn(turn: Turn, credentials: Credentials): ChatOutcome {
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = turn.batch
            if (batch == null) {
                val system = chatPrompt(expenses.getCategories(), turn.rounds, turn.searches, clock)
                val job = currentCoroutineContext().job
                val reply = client.reply(credentials, system, tools(), turn.messages) { text ->
                    // A cancelled or timed-out turn stops reading the stream instead of waiting for its end.
                    job.ensureActive()
                    show(turn, text)
                }
                currentCoroutineContext().ensureActive()
                // Text before tool calls isn't the reply; the next round writes that.
                if (reply.calls.isNotEmpty()) show(turn, "")
                if (reply.calls.isEmpty()) {
                    if (reply.text.isBlank()) {
                        if (turn.changes.isEmpty()) fail("EMPTY_MODEL_REPLY", "The model returned an empty reply.")
                        // The changes are the answer. Providers reject empty assistant turns, so history gets a placeholder.
                        turn.messages.add(ModelMessage.LocalReply("Done."))
                        commit(turn)
                        return outcome(turn)
                    }
                    turn.messages.add(reply)
                    commit(turn)
                    return outcome(turn, reply = reply.text)
                }
                validateBatch(turn, reply.calls)
                turn.messages.add(reply)
                turn.batch = Batch(reply.calls)
                turn.rounds++
                continue
            }
            if (batch.complete) {
                finishBatch(turn, batch)
                // Out of searches is fine: the model can still present candidates; validateBatch blocks further searches.
                if (turn.rounds >= toolRoundsLimit) {
                    return stop(turn, replyFailure(turn, "ROUND_LIMIT", "This turn reached its tool limit."))
                }
                continue
            }
            val call = batch.current
            val args = parseArguments(call.arguments)
            if (call.name == "find_expenses") turn.searches++
            val result = if (args == null) failure("INVALID_ARGUMENTS", "Tool arguments must be a JSON object.") else perform {
                when (call.name) {
                    "add_expense" -> expenses.addChatExpense(args, turn.source)
                    "list_expenses" -> expenses.listExpenses(args)
                    "summarize_expenses" -> expenses.summarizeExpenses(args)
                    "find_expenses" -> expenses.findExpenses(args)
                    "update_expense" -> expenses.updateExpense(args)
                    "delete_expense" -> expenses.deleteChatExpense(args)
                    else -> failure("UNKNOWN_TOOL", "This tool is not available.")
                }
            }
            record(turn, batch, call, result)
            result.problem()?.let { problem ->
                val canRefineSummary = problem.code != "TOOL_EXECUTION_FAILED" && batch.calls.all { it.name == "summarize_expenses" }
                if (!canRefineSummary) return stop(turn, problem)
            }
        }
    }

    private fun show(turn: Turn, text: String) {
        if (text == turn.partial) return
        turn.partial = text
        try {
            turn.onText(text)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Showing text early is best effort, like progress.
        }
    }

    private fun validateBatch(turn: Turn, calls: List<ToolCall>) {
        if (calls.size > callsPerRoundLimit || calls.any { it.id.isBlank() || it.id.length > 200 || it.name.isBlank() } ||
            calls.map { it.id }.distinct().size != calls.size || calls.any { it.id in turn.callIds }) {
            fail("INVALID_TOOL_BATCH", "The model returned an invalid or repeated tool batch.")
        }
        if (calls.count { it.name == "find_expenses" } > searchesLimit - turn.searches) {
            fail("SEARCH_LIMIT", "Narrow the search to identify the expense.")
        }
        turn.callIds.addAll(calls.map { it.id })
    }

    private suspend fun record(turn: Turn, batch: Batch, call: ToolCall, result: JSONObject) {
        val ok = result.optBoolean("ok")
        val change = if (!ok) null else when (call.name) {
            "add_expense" -> ExpenseChange(ExpenseAction.ADDED, result.getJSONObject("expense"))
            "update_expense" -> ExpenseChange(ExpenseAction.UPDATED, result.getJSONObject("expense"))
            "delete_expense" -> ExpenseChange(ExpenseAction.DELETED, result.getJSONObject("deleted_expense"))
            else -> null
        }
        if (ok && call.name == "list_expenses") turn.tables.add(result)
        batch.results.add(ToolResult(call.id, result.toString(), failed = !ok))
        if (change == null) return
        turn.changes.add(change)
        val progress = ChatProgress(presented(turn.changes), closed(turn, batch, UNFINISHED_REPLY), turn.route)
        try {
            turn.onProgress(progress)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Showing or saving early is best effort; the turn's outcome still carries the change.
        }
    }

    private fun finishBatch(turn: Turn, batch: Batch) {
        turn.messages.add(ModelMessage.Results(batch.results.toList()))
        turn.batch = null
    }

    /** The turn's messages with [batch] closed (calls not yet run marked as skipped) and [note] as the last reply. */
    private fun closed(turn: Turn, batch: Batch?, note: String): List<ModelMessage> {
        val messages = turn.messages.toMutableList()
        if (batch != null) {
            val skipped = failure("NOT_EXECUTED", "The turn stopped before this tool ran.").toString()
            val results = batch.results + batch.calls.drop(batch.results.size).map { ToolResult(it.id, skipped, failed = true) }
            messages.add(ModelMessage.Results(results))
        }
        messages.add(ModelMessage.LocalReply(note))
        return messages
    }

    /** Text received before a failure stays, as the reply so far, with the failure noted after it. */
    private fun stop(turn: Turn, problem: ChatFailure): ChatOutcome {
        val partial = turn.partial.takeIf(String::isNotBlank)
        if (turn.rounds > 0) {
            val messages = closed(turn, turn.batch, if (partial == null) problem.message else "$partial\n\n${problem.message}")
            turn.batch = null
            turn.messages.clear()
            turn.messages.addAll(messages)
            commit(turn)
        }
        return outcome(turn, reply = partial, failure = problem)
    }

    /** Once expenses changed or the reply began, a later failure is only about the reply; the changes stand. */
    private fun replyFailure(turn: Turn, code: String, message: String) =
        ChatFailure(code, if (turn.changes.isEmpty() && turn.partial.isBlank()) message else "$UNFINISHED_REPLY $message")

    private fun commit(turn: Turn) {
        history.clear()
        history.addAll(turn.messages)
        route = turn.route
        turn.committed = true
    }

    private fun outcome(turn: Turn, reply: String? = null, failure: ChatFailure? = null) = ChatOutcome(
        reply, failure,
        presented(turn.changes),
        turn.tables.map { JSONObject(it.toString()) },
        turn.committed,
    )

    private class Turn(
        val source: String,
        val messages: MutableList<ModelMessage>,
        val route: Pair<ModelProvider, String>,
        val onText: (String) -> Unit,
        val onProgress: suspend (ChatProgress) -> Unit,
    ) {
        /** The current round's text so far, as shown. */
        var partial = ""
        val changes = mutableListOf<ExpenseChange>()
        val tables = mutableListOf<JSONObject>()
        val callIds = mutableSetOf<String>()
        var batch: Batch? = null
        var rounds = 0
        var searches = 0
        var committed = false
    }

    private class Batch(val calls: List<ToolCall>) {
        val results = mutableListOf<ToolResult>()
        val current: ToolCall get() = calls[results.size]
        val complete: Boolean get() = results.size == calls.size
    }
}

/**
 * One change per expense, in the order first touched: a later update or delete of the same expense replaces the
 * earlier entry. An expense added and then edited in the same turn still reads as added. Expenses are copied.
 */
private fun presented(changes: List<ExpenseChange>): List<ExpenseChange> {
    val byExpense = LinkedHashMap<Any, ExpenseChange>()
    changes.forEachIndexed { i, change ->
        val key: Any = change.expense.optString("id").ifEmpty { i }
        val previous = byExpense[key]
        val action = if (previous?.action == ExpenseAction.ADDED && change.action == ExpenseAction.UPDATED) ExpenseAction.ADDED else change.action
        byExpense[key] = ExpenseChange(action, JSONObject(change.expense.toString()))
    }
    return byExpense.values.toList()
}

/** A short factual note for the model about an expense the user restored after it was deleted in this chat. */
private fun restoreNote(expense: JSONObject): String {
    val note = expense.value("description") as? String
    return "Note: the user restored the expense ${expense.optString("amount")} ${expense.optString("currency")}, " +
        "${expense.optString("spent_on")}, ${expense.optString("category_name")}, " +
        "${if (note == null) "no note" else "note ${JSONObject.quote(note)}"}, id ${expense.optString("id")}, that was deleted earlier."
}

private fun unsuccessful(code: String, message: String) = ChatOutcome(failure = ChatFailure(code, message))

private fun JSONObject.problem(): ChatFailure? = optJSONObject("error")?.let {
    ChatFailure(it.getString("code"), it.getString("message"))
}

private fun perform(operation: () -> JSONObject): JSONObject = try {
    operation()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    failure("TOOL_EXECUTION_FAILED", "Spendidly could not complete the expense action. Check your history before repeating a write.")
}

private fun parseArguments(text: String): JSONObject? {
    if (text.length > 20_000) return null
    return try {
        val parser = JSONTokener(text)
        (parser.nextValue() as? JSONObject)?.takeIf { parser.nextClean() == '\u0000' }
    } catch (_: JSONException) {
        null
    }
}
