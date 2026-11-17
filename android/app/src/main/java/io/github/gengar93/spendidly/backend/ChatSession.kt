package io.github.gengar93.spendidly.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
data class DeletionConfirmation(val token: String, val expense: JSONObject)

/** Changes and tables are cumulative for the current turn, including after a deletion decision. */
data class ChatOutcome(
    val reply: String? = null,
    val failure: ChatFailure? = null,
    val changes: List<ExpenseChange> = emptyList(),
    val tables: List<JSONObject> = emptyList(),
    val deletion: DeletionConfirmation? = null,
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
    private var pending: PendingDeletion? = null

    suspend fun send(provider: ModelProvider, text: String): ChatOutcome = serialized {
        if (pending != null) return@serialized unsuccessful("CONFIRMATION_PENDING", "Resolve the pending deletion first.")
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
        val turn = Turn(input, (history + ModelMessage.User(input)).toMutableList(), selectedRoute)
        runTurn(turn, credentials)
    }

    suspend fun resolveDeletion(token: String, approved: Boolean): ChatOutcome = serialized {
        val deletion = pending
        if (deletion == null || deletion.token != token) {
            return@serialized unsuccessful("NO_PENDING_DELETION", "This deletion request is no longer pending.")
        }
        val turn = deletion.turn
        val batch = checkNotNull(turn.batch)
        val call = batch.current
        pending = null
        if (!approved) {
            val cancelled = failure("DELETE_CANCELLED", "Deletion canceled.")
            record(turn, batch, call, cancelled)
            return@serialized stop(turn, ChatFailure("DELETE_CANCELLED", "Deletion canceled."))
        }
        val result = perform { expenses.deleteConfirmed(deletion.expense) }
        record(turn, batch, call, result)
        result.problem()?.let { return@serialized stop(turn, it) }

        // The confirmed local write stands even if the provider cannot finish the turn.
        val credentials = try {
            settings.credentials(turn.route.first)
        } catch (error: BackendProblem) {
            return@serialized stop(turn, ChatFailure(error.code, error.message.orEmpty()))
        } catch (_: Exception) {
            return@serialized stop(turn, ChatFailure("SETTINGS_UNAVAILABLE", "Spendidly could not read the provider settings."))
        }
        if (credentials.model != turn.route.second) {
            return@serialized stop(turn, ChatFailure("PROVIDER_CHANGED", "Start a new chat to switch providers or models."))
        }
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
        pending = null
    }

    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private suspend fun runTurn(turn: Turn, credentials: Credentials): ChatOutcome {
        try {
            return withTimeout(120_000L) { continueTurn(turn, credentials) }
        } catch (_: TimeoutCancellationException) {
            return stop(turn, ChatFailure("TURN_TIMEOUT", "The turn took too long to complete."))
        } catch (error: CancellationException) {
            stop(turn, ChatFailure("TURN_CANCELLED", "The turn was interrupted."))
            throw error
        } catch (error: BackendProblem) {
            return stop(turn, ChatFailure(error.code, error.message.orEmpty()))
        } catch (_: Exception) {
            return stop(turn, ChatFailure("TURN_FAILED", "Spendidly could not complete this turn."))
        }
    }

    private suspend fun continueTurn(turn: Turn, credentials: Credentials): ChatOutcome {
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = turn.batch
            if (batch == null) {
                val system = chatPrompt(expenses.getCategories(), turn.rounds, turn.searches, clock)
                val reply = client.reply(credentials, system, tools(), turn.messages)
                currentCoroutineContext().ensureActive()
                if (reply.calls.isEmpty()) {
                    if (reply.text.isBlank()) fail("EMPTY_MODEL_REPLY", "The model returned an empty reply.")
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
                    return stop(turn, ChatFailure("ROUND_LIMIT", "This turn reached its tool limit."))
                }
                continue
            }
            val call = batch.current
            val args = parseArguments(call.arguments)
            if (call.name == "delete_expense" && args != null) {
                // The user confirms in chat before the model calls this, so it deletes directly.
                val result = try {
                    val target = expenses.deletionTarget(args)
                    perform { expenses.deleteConfirmed(target) }
                } catch (error: BackendProblem) {
                    failure(error.code, error.message.orEmpty())
                }
                record(turn, batch, call, result)
                result.problem()?.let { return stop(turn, it) }
                continue
            }
            if (call.name == "find_expenses") turn.searches++
            val result = if (args == null) failure("INVALID_ARGUMENTS", "Tool arguments must be a JSON object.") else perform {
                when (call.name) {
                    "add_expense" -> expenses.addChatExpense(args, turn.source)
                    "list_expenses" -> expenses.listExpenses(args)
                    "summarize_expenses" -> expenses.summarizeExpenses(args)
                    "find_expenses" -> expenses.findExpenses(args)
                    "update_expense" -> expenses.updateExpense(args)
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

    private fun record(turn: Turn, batch: Batch, call: ToolCall, result: JSONObject) {
        val ok = result.optBoolean("ok")
        if (ok) {
            when (call.name) {
                "add_expense" -> turn.changes.add(ExpenseChange(ExpenseAction.ADDED, result.getJSONObject("expense")))
                "update_expense" -> turn.changes.add(ExpenseChange(ExpenseAction.UPDATED, result.getJSONObject("expense")))
                "delete_expense" -> turn.changes.add(ExpenseChange(ExpenseAction.DELETED, result.getJSONObject("deleted_expense")))
                "list_expenses" -> turn.tables.add(result)
            }
        }
        batch.results.add(ToolResult(call.id, result.toString(), failed = !ok))
    }

    private fun finishBatch(turn: Turn, batch: Batch) {
        turn.messages.add(ModelMessage.Results(batch.results.toList()))
        turn.batch = null
    }

    private fun stop(turn: Turn, problem: ChatFailure): ChatOutcome {
        turn.batch?.let { batch ->
            val skipped = failure("NOT_EXECUTED", "The turn stopped before this tool ran.").toString()
            for (call in batch.calls.drop(batch.results.size)) batch.results.add(ToolResult(call.id, skipped, failed = true))
            finishBatch(turn, batch)
        }
        if (turn.rounds > 0) {
            turn.messages.add(ModelMessage.LocalReply(problem.message))
            commit(turn)
        }
        return outcome(turn, failure = problem)
    }

    private fun commit(turn: Turn) {
        history.clear()
        history.addAll(turn.messages)
        route = turn.route
    }

    private fun outcome(
        turn: Turn,
        reply: String? = null,
        failure: ChatFailure? = null,
        deletion: DeletionConfirmation? = null,
    ) = ChatOutcome(
        reply, failure,
        turn.changes.map { it.copy(expense = JSONObject(it.expense.toString())) },
        turn.tables.map { JSONObject(it.toString()) },
        deletion,
    )

    private class Turn(val source: String, val messages: MutableList<ModelMessage>, val route: Pair<ModelProvider, String>) {
        val changes = mutableListOf<ExpenseChange>()
        val tables = mutableListOf<JSONObject>()
        val callIds = mutableSetOf<String>()
        var batch: Batch? = null
        var rounds = 0
        var searches = 0
    }

    private class Batch(val calls: List<ToolCall>) {
        val results = mutableListOf<ToolResult>()
        val current: ToolCall get() = calls[results.size]
        val complete: Boolean get() = results.size == calls.size
    }

    private data class PendingDeletion(val token: String, val expense: Expense, val turn: Turn)
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
