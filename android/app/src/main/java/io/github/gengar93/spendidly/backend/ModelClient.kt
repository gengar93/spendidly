package io.github.gengar93.spendidly.backend

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface ModelMessage {
    data class User(val text: String) : ModelMessage
    data class Assistant(val text: String, val calls: List<ToolCall>, val wire: JSONObject) : ModelMessage
    data class Results(val entries: List<ToolResult>) : ModelMessage
    data class LocalReply(val text: String) : ModelMessage
}

internal data class ToolCall(val id: String, val name: String, val arguments: String)
internal data class ToolResult(val id: String, val content: String, val failed: Boolean)

internal fun interface ModelClient {
    /**
     * Streams one model reply. [onText] gets the reply text so far each time it grows, or a shorter text (possibly
     * empty) if the provider restarts the reply. The returned message is complete: tool calls are used only once whole.
     */
    fun reply(
        credentials: Credentials,
        system: String,
        tools: JSONArray,
        history: List<ModelMessage>,
        onText: (String) -> Unit,
    ): ModelMessage.Assistant
}

internal class ProviderClient : ModelClient {
    override fun reply(
        credentials: Credentials,
        system: String,
        tools: JSONArray,
        history: List<ModelMessage>,
        onText: (String) -> Unit,
    ): ModelMessage.Assistant {
        val request = buildModelRequest(credentials.provider, credentials.model, system, tools, history)
        val body = request.toString().encodeToByteArray()
        if (body.size > 256_000) fail("CONTEXT_LIMIT", "This chat is getting long. Start a new chat.")
        val connection = URL(credentials.provider.endpoint).openConnection() as HttpURLConnection
        var read = false
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            if (credentials.provider == ModelProvider.ANTHROPIC) {
                connection.setRequestProperty("x-api-key", credentials.apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                if (isSonnet(credentials.model)) connection.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
            } else {
                connection.setRequestProperty("Authorization", "Bearer ${credentials.apiKey}")
            }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) providerError(credentials.provider, status)
            val stream = ReplyStream.of(credentials.provider, onText)
            readEvents(connection.inputStream, stream)
            read = true
            // The assembled reply has the shape of a non-streamed one, so it is checked and stored the same way.
            return parseModelReply(credentials.provider, stream.response())
        } catch (_: IOException) {
            fail("PROVIDER_CONNECTION", "Could not reach ${credentials.provider.label}. Check your connection and try again.")
        } catch (_: JSONException) {
            fail("INVALID_MODEL_REPLY", "${credentials.provider.label} returned an unreadable response.")
        } finally {
            // A fully read reply leaves the socket in the keep-alive pool, which saves a TLS handshake on the next turn.
            if (!read) connection.disconnect()
        }
    }
}

internal fun buildModelRequest(
    provider: ModelProvider,
    model: String,
    system: String,
    tools: JSONArray,
    history: List<ModelMessage>,
): JSONObject {
    if (provider == ModelProvider.ANTHROPIC) {
        val messages = mutableListOf<JSONObject>()
        fun append(role: String, blocks: List<Any?>) {
            if (messages.lastOrNull()?.optString("role") != role) {
                messages.add(json("role" to role, "content" to JSONArray()))
            }
            val content = messages.last().getJSONArray("content")
            for (block in blocks) content.put(block)
        }
        for (message in history) {
            when (message) {
                is ModelMessage.User -> append("user", listOf(textBlock(message.text)))
                is ModelMessage.Assistant -> append("assistant", message.wire.getJSONArray("content").values())
                is ModelMessage.LocalReply -> append("assistant", listOf(textBlock(message.text)))
                is ModelMessage.Results -> append("user", message.entries.map {
                    json("type" to "tool_result", "tool_use_id" to it.id, "content" to it.content, "is_error" to it.failed)
                })
            }
        }
        val anthropicTools = tools.values().map {
            val function = (it as JSONObject).getJSONObject("function")
            json("name" to function.getString("name"), "description" to function.getString("description"), "input_schema" to function.getJSONObject("parameters"))
        }
        val request = json(
            "model" to model.removePrefix("anthropic/").replace(Regex("(?<=\\d)\\.(?=\\d)"), "-"),
            "system" to system, "messages" to JSONArray(messages), "tools" to JSONArray(anthropicTools),
            "max_tokens" to 3000, "stream" to true,
        )
        // Sonnet thinks at high effort by default; medium keeps turns quick. A declined turn falls back to another model.
        if (isSonnet(model)) {
            request.put("max_tokens", 8000).put("output_config", json("effort" to "medium")).put("fallbacks", "default")
        }
        return request
    }
    val instructionRole = if (provider == ModelProvider.OPENAI) "developer" else "system"
    val messages = mutableListOf(json("role" to instructionRole, "content" to system))
    for (message in history) {
        when (message) {
            is ModelMessage.User -> messages.add(json("role" to "user", "content" to message.text))
            is ModelMessage.Assistant -> messages.add(message.wire)
            is ModelMessage.LocalReply -> messages.add(json("role" to "assistant", "content" to message.text))
            is ModelMessage.Results -> for (entry in message.entries) {
                messages.add(json("role" to "tool", "tool_call_id" to entry.id, "content" to entry.content))
            }
        }
    }
    val requestTools = if (provider == ModelProvider.GOOGLE) geminiTools(tools) else tools
    val request = json("model" to model, "messages" to JSONArray(messages), "tools" to requestTools, "tool_choice" to "auto", "stream" to true)
    return when (provider) {
        // GPT-6 models take tools over Chat Completions only with reasoning off.
        ModelProvider.OPENAI -> request.put("max_completion_tokens", 3000).put("store", false).put("reasoning_effort", "none")
        // Gemini can't turn thinking off, and thinking counts toward max_tokens.
        ModelProvider.GOOGLE -> request.put("max_tokens", 8000).put("reasoning_effort", "low")
        // OpenRouter models may reason, and reasoning counts toward max_tokens.
        ModelProvider.OPENROUTER -> request.put("max_tokens", 8000)
        else -> request.put("max_tokens", 3000)
    }
}

private fun isSonnet(model: String) = model.removePrefix("anthropic/").startsWith("claude-sonnet")

/** Gemini reads tool schemas as a subset of OpenAPI: no "strict", no "additionalProperties", and nullable types as anyOf. */
private fun geminiTools(tools: JSONArray): JSONArray = JSONArray(tools.values().map { tool ->
    val copy = JSONObject((tool as JSONObject).toString())
    val function = copy.getJSONObject("function")
    function.remove("strict")
    function.put("parameters", geminiSchema(function.getJSONObject("parameters")))
    copy
})

private fun geminiSchema(node: Any?): Any? = when (node) {
    is JSONArray -> JSONArray(node.values().map(::geminiSchema))
    is JSONObject -> {
        val out = JSONObject()
        for (key in node.keys()) if (key != "additionalProperties") out.put(key, geminiSchema(node.get(key)))
        val types = (out.opt("type") as? JSONArray)?.values()?.map { it.toString() }
        if (types != null && "null" in types && types.size == 2) {
            val inner = JSONObject(out.toString()).put("type", types.first { it != "null" })
            inner.remove("description")
            val nullable = json("anyOf" to JSONArray(listOf(inner, json("type" to "null"))))
            out.optString("description").takeIf(String::isNotEmpty)?.let { nullable.put("description", it) }
            nullable
        } else out
    }
    else -> node
}

internal fun parseModelReply(provider: ModelProvider, response: JSONObject): ModelMessage.Assistant {
    if (response.value("error") != null) fail("INVALID_MODEL_REPLY", "${provider.label} could not complete the turn.")
    if (provider == ModelProvider.ANTHROPIC) {
        val blocks = response.getJSONArray("content")
        val objects = blocks.values().map {
            it as? JSONObject ?: fail("INVALID_MODEL_REPLY", "The provider returned an invalid content block.")
        }
        val calls = objects.filter { it.optString("type") == "tool_use" }.map {
            ToolCall(it.getString("id"), it.getString("name"), it.getJSONObject("input").toString())
        }
        val text = objects.filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }
        checkFinish(response.optString("stop_reason"), setOf("end_turn", "stop_sequence"), "tool_use", calls)
        return ModelMessage.Assistant(text, calls, json("role" to "assistant", "content" to blocks))
    }
    val choice = response.getJSONArray("choices").getJSONObject(0)
    val data = choice.getJSONObject("message")
    val rawCalls = data.optJSONArray("tool_calls")
    if (provider == ModelProvider.GOOGLE && rawCalls != null) fillMissingIds(rawCalls)
    val calls = rawCalls?.values()?.map {
        val call = it as? JSONObject ?: fail("INVALID_MODEL_REPLY", "The provider returned an invalid tool call.")
        if (call.optString("type") != "function") fail("INVALID_MODEL_REPLY", "The provider requested an unsupported tool type.")
        val function = call.getJSONObject("function")
        ToolCall(call.getString("id"), function.getString("name"), function.getString("arguments"))
    }.orEmpty()
    // Gemini can report "stop" for a reply that calls tools.
    val finish = choice.optString("finish_reason").let { if (provider == ModelProvider.GOOGLE && calls.isNotEmpty() && it == "stop") "tool_calls" else it }
    checkFinish(finish, setOf("stop"), "tool_calls", calls)
    val text = (data.value("content") as? String) ?: (data.value("refusal") as? String).orEmpty()
    val wire = json("role" to "assistant", "content" to text.takeIf(String::isNotEmpty))
    // Tool calls go back unchanged: Gemini puts thought signatures in them (extra_content) and rejects a turn without them.
    if (calls.isNotEmpty()) wire.put("tool_calls", rawCalls)
    if (provider == ModelProvider.GOOGLE) data.optJSONObject("extra_content")?.let { wire.put("extra_content", it) }
    if (provider == ModelProvider.OPENROUTER) {
        data.optJSONArray("reasoning_details")?.let { wire.put("reasoning_details", it) }
    }
    return ModelMessage.Assistant(text, calls, wire)
}

/** Gives blank or repeated tool call IDs a unique one, in the wire copy too, so results still match their calls. */
private fun fillMissingIds(calls: JSONArray) {
    val seen = mutableSetOf<String>()
    for (i in 0 until calls.length()) {
        val call = calls.optJSONObject(i) ?: continue
        val id = call.optString("id")
        if (id.isBlank() || !seen.add(id)) {
            val fresh = "call_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
            call.put("id", fresh)
            seen.add(fresh)
        }
    }
}

private fun checkFinish(reason: String, finalReasons: Set<String>, toolReason: String, calls: List<ToolCall>) {
    if (reason == "length" || reason == "max_tokens") fail("MODEL_OUTPUT_LIMIT", "The model ran out of space for its reply.")
    if (calls.isNotEmpty() && reason != toolReason || calls.isEmpty() && reason !in finalReasons) {
        fail("INVALID_MODEL_REPLY", "The model could not complete the reply.")
    }
}

private fun textBlock(text: String) = json("type" to "text", "text" to text)

private fun providerError(provider: ModelProvider, status: Int): Nothing {
    val label = provider.label
    when (status) {
        401 -> fail("API_KEY_REJECTED", "$label rejected the API key.")
        402 -> fail("PROVIDER_CREDITS", "Your $label account needs credits for this model.")
        403 -> fail("MODEL_ACCESS_DENIED", "Your $label account cannot use this model.")
        404 -> fail("MODEL_UNAVAILABLE", "This model is unavailable from $label.")
        429 -> fail("PROVIDER_RATE_LIMIT", "$label is rate-limited. Try again in a moment.")
        else -> fail("PROVIDER_ERROR", "$label could not reply (HTTP $status).")
    }
}
