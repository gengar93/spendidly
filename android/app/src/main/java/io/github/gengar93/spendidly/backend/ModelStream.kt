package io.github.gengar93.spendidly.backend

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Reads a streamed reply's Server-Sent Events into [stream]. A body that is plain JSON (a provider that ignored
 * "stream") is taken whole. A stream that stops before the reply is complete counts as a dropped connection.
 */
internal fun readEvents(input: InputStream, stream: ReplyStream) {
    val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    val data = StringBuilder()
    var raw: StringBuilder? = null
    var size = 0L
    fun dispatch() {
        val text = data.toString()
        data.setLength(0)
        if (text == "[DONE]") stream.done() else stream.event(JSONObject(text))
    }
    try {
        while (!stream.complete) {
            val line = reader.readLine() ?: break
            size += line.length + 1
            if (size > 2_000_000) fail("INVALID_MODEL_REPLY", "The provider response was too large.")
            when {
                raw != null -> raw.append(line).append('\n')
                line.isEmpty() -> if (data.isNotEmpty()) dispatch()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                line.startsWith("{") && size == line.length + 1L -> raw = StringBuilder(line).append('\n')
                // Comments (": OPENROUTER PROCESSING"), event names and ids carry nothing the data lines don't.
                else -> Unit
            }
        }
        if (!stream.complete && data.isNotEmpty()) dispatch()
        raw?.let { stream.whole(JSONObject(it.toString())) }
    } finally {
        // After the last event the rest of the body is drained if it's quick, keeping the connection reusable.
        runCatching { reader.close() }
    }
    if (!stream.finished) throw IOException("The reply stream ended early.")
}

/** Builds one reply from its stream events, reporting the text meant for the user as it grows. */
internal abstract class ReplyStream(private val onText: (String) -> Unit) {
    /** Set once the provider has said the reply is over. */
    var complete = false
        protected set
    private var whole: JSONObject? = null
    private var shown = ""

    /** Whether the stream got far enough for its reply to be judged by [parseModelReply]. */
    open val finished: Boolean get() = complete

    abstract fun event(data: JSONObject)

    open fun done() { complete = true }

    fun whole(response: JSONObject) {
        whole = response
        complete = true
    }

    /** The reply in the shape the provider gives it when not streaming. */
    fun response(): JSONObject = whole ?: assembled()

    protected abstract fun assembled(): JSONObject

    protected fun show(text: String) {
        if (text == shown) return
        shown = text
        onText(text)
    }

    companion object {
        fun of(provider: ModelProvider, onText: (String) -> Unit): ReplyStream =
            if (provider == ModelProvider.ANTHROPIC) AnthropicStream(onText) else ChatCompletionsStream(provider, onText)
    }
}

/**
 * Anthropic Messages events. Blocks start whole and grow by deltas: text, thinking and signature deltas extend the
 * field they name, and a tool's input arrives as JSON text that is parsed when its block stops.
 */
private class AnthropicStream(onText: (String) -> Unit) : ReplyStream(onText) {
    private val blocks = sortedMapOf<Int, JSONObject>()
    private val inputs = HashMap<Int, StringBuilder>()
    private var stopReason: Any? = null

    override fun event(data: JSONObject) {
        when (data.optString("type")) {
            "message_start" -> {
                // A new message replaces anything streamed before it.
                blocks.clear()
                inputs.clear()
                stopReason = data.optJSONObject("message")?.value("stop_reason")
                show(text())
            }
            "content_block_start" -> {
                val index = data.getInt("index")
                val block = data.getJSONObject("content_block")
                blocks[index] = block
                if (block.optString("type") == "tool_use") inputs[index] = StringBuilder()
                show(text())
            }
            "content_block_delta" -> {
                val index = data.getInt("index")
                val block = blocks[index] ?: fail("INVALID_MODEL_REPLY", "The provider returned an invalid content block.")
                val delta = data.getJSONObject("delta")
                when (delta.optString("type")) {
                    "input_json_delta" -> inputs.getOrPut(index, ::StringBuilder).append(delta.optString("partial_json"))
                    "citations_delta" -> (block.optJSONArray("citations") ?: JSONArray().also { block.put("citations", it) }).put(delta.opt("citation"))
                    else -> for (key in delta.keys()) {
                        if (key == "type") continue
                        val now = delta.get(key)
                        val before = block.opt(key)
                        block.put(key, if (now is String && before is String) before + now else now)
                    }
                }
                if (block.optString("type") == "text") show(text())
            }
            "content_block_stop" -> {
                val index = data.getInt("index")
                val input = inputs.remove(index)
                if (input != null && input.isNotBlank()) blocks[index]?.put("input", JSONObject(input.toString()))
            }
            "message_delta" -> data.optJSONObject("delta")?.let { if (it.has("stop_reason")) stopReason = it.value("stop_reason") }
            "message_stop" -> complete = true
            "error" -> fail("INVALID_MODEL_REPLY", "${ModelProvider.ANTHROPIC.label} could not complete the turn.")
        }
    }

    /** Joined as [parseModelReply] joins them, so the streamed text ends equal to the reply's. */
    private fun text() = blocks.values.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }

    override fun assembled() = json("content" to JSONArray(blocks.values.toList()), "stop_reason" to stopReason)
}

/**
 * OpenAI-style chunks (OpenAI, Google's OpenAI-compatible endpoint, OpenRouter). Text and tool call arguments arrive
 * as pieces; tool calls, OpenRouter's reasoning details and Gemini's extra_content (thought signatures) are merged
 * by index so the message matches the non-streamed one.
 */
private class ChatCompletionsStream(private val provider: ModelProvider, onText: (String) -> Unit) : ReplyStream(onText) {
    private val content = StringBuilder()
    private val refusal = StringBuilder()
    private val calls = mutableListOf<JSONObject>()
    private val callAt = HashMap<Int, JSONObject>()
    private val reasoning = mutableListOf<JSONObject>()
    private val reasoningAt = HashMap<Int, JSONObject>()
    private var extra: JSONObject? = null
    private var finish: String? = null

    /** Some servers close without "[DONE]" once the reply has finished. */
    override val finished: Boolean get() = complete || finish != null

    override fun event(data: JSONObject) {
        if (data.value("error") != null) fail("INVALID_MODEL_REPLY", "${provider.label} could not complete the turn.")
        val choice = data.optJSONArray("choices")?.optJSONObject(0) ?: return
        choice.optJSONObject("delta")?.let(::merge)
        (choice.value("finish_reason") as? String)?.let { finish = it }
        show(content.ifEmpty { refusal }.toString())
    }

    private fun merge(delta: JSONObject) {
        (delta.value("content") as? String)?.let(content::append)
        (delta.value("refusal") as? String)?.let(refusal::append)
        delta.optJSONArray("tool_calls")?.values()?.forEach { (it as? JSONObject)?.let(::mergeCall) }
        delta.optJSONArray("reasoning_details")?.values()?.forEach { (it as? JSONObject)?.let(::mergeReasoning) }
        delta.optJSONObject("extra_content")?.let { extra = deepMerge(extra ?: JSONObject(), it) }
    }

    private fun mergeCall(piece: JSONObject) {
        val index = (piece.value("index") as? Number)?.toInt()
        val id = piece.value("id") as? String ?: ""
        val name = piece.optJSONObject("function")?.value("name") as? String ?: ""
        var call = if (index != null) callAt[index] else calls.lastOrNull()
        // A different id, or without an index a second name, starts another call.
        val otherId = id.isNotEmpty() && call?.optString("id").orEmpty().let { it.isNotEmpty() && it != id }
        val otherName = index == null && id.isEmpty() && name.isNotEmpty() && call?.optJSONObject("function")?.optString("name").orEmpty().isNotEmpty()
        if (call == null || otherId || otherName) {
            call = JSONObject()
            calls.add(call)
            if (index != null) callAt[index] = call
        }
        for (key in piece.keys()) {
            val value = piece.get(key)
            if (value == JSONObject.NULL) continue
            when (key) {
                "function" -> if (value is JSONObject) {
                    val function = call.optJSONObject("function") ?: JSONObject().also { call.put("function", it) }
                    for (part in value.keys()) {
                        val v = value.get(part)
                        when {
                            v == JSONObject.NULL -> Unit
                            part == "arguments" && v is String -> function.put(part, function.optString(part) + v)
                            part == "name" && function.optString(part).isNotEmpty() -> Unit
                            else -> function.put(part, v)
                        }
                    }
                }
                "extra_content" -> if (value is JSONObject) call.put(key, deepMerge(call.optJSONObject(key) ?: JSONObject(), value))
                "id", "type" -> if (value is String && value.isNotEmpty()) call.put(key, value)
                else -> call.put(key, value)
            }
        }
    }

    private fun mergeReasoning(piece: JSONObject) {
        val index = (piece.value("index") as? Number)?.toInt()
        val entry = index?.let(reasoningAt::get) ?: JSONObject().also {
            reasoning.add(it)
            if (index != null) reasoningAt[index] = it
        }
        for (key in piece.keys()) {
            val value = piece.get(key)
            val before = entry.opt(key)
            when {
                value == JSONObject.NULL -> Unit
                key in growingReasoningFields && value is String && before is String -> entry.put(key, before + value)
                else -> entry.put(key, value)
            }
        }
    }

    override fun assembled(): JSONObject {
        val message = json("role" to "assistant", "content" to content.toString().ifEmpty { null }, "refusal" to refusal.toString().ifEmpty { null })
        if (calls.isNotEmpty()) message.put("tool_calls", JSONArray(calls.map { call ->
            // OpenRouter's non-streamed calls keep their index; OpenAI's and Gemini's have none.
            if (provider != ModelProvider.OPENROUTER) call.remove("index")
            if (!call.has("type") && call.has("function")) call.put("type", "function")
            call
        }))
        extra?.let { message.put("extra_content", it) }
        if (reasoning.isNotEmpty()) message.put("reasoning_details", JSONArray(reasoning))
        return json("choices" to JSONArray(listOf(json("message" to message, "finish_reason" to finish))))
    }
}

/** Reasoning detail fields that stream in pieces; the rest (type, format, id, index) are set whole. */
private val growingReasoningFields = setOf("text", "summary", "data", "signature")

private fun deepMerge(target: JSONObject, source: JSONObject): JSONObject {
    for (key in source.keys()) {
        val value = source.get(key)
        val before = target.opt(key)
        when {
            value is JSONObject && before is JSONObject -> deepMerge(before, value)
            value != JSONObject.NULL -> target.put(key, value)
        }
    }
    return target
}
