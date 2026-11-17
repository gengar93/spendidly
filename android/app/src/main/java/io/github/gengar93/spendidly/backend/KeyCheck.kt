package io.github.gengar93.spendidly.backend

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface KeyCheck {
    /** [models] and [voice] are the curated chat and voice model IDs this key can use, or null when the provider couldn't say. */
    data class Valid(val provider: ModelProvider, val models: Set<String>?, val voice: Set<String>? = null) : KeyCheck
    data class Invalid(val message: String) : KeyCheck
}

const val UNSUPPORTED_KEY_MESSAGE = "This key isn't from a supported provider (Anthropic, OpenAI, Google, OpenRouter)."

/** Tests a key with one cheap read-only call to its provider, listing the models it can use. Blocking; call off the main thread. */
internal fun checkKey(key: String): KeyCheck {
    val clean = key.trim()
    val provider = detectProvider(clean) ?: return KeyCheck.Invalid(UNSUPPORTED_KEY_MESSAGE)
    if (clean.length > 4096 || clean.any { it !in '!'..'~' }) return KeyCheck.Invalid("This key has characters a ${provider.label} key can't have. Copy it again.")
    val label = provider.label
    return try {
        // OpenRouter lists chat models (those taking tools) apart from voice models (those taking audio).
        var voiceListed: Set<String>? = null
        val listed: Set<String>? = when (provider) {
            ModelProvider.ANTHROPIC -> pages { after ->
                val page = getJson(provider, "https://api.anthropic.com/v1/models?limit=1000" + after.param("after_id"),
                    "x-api-key" to clean, "anthropic-version" to "2023-06-01")
                page.ids("data", "id") to page.optString("last_id").takeIf { page.optBoolean("has_more") && it.isNotEmpty() }
            }
            ModelProvider.OPENAI -> getJson(provider, "https://api.openai.com/v1/models", "Authorization" to "Bearer $clean").ids("data", "id").toSet()
            ModelProvider.GOOGLE -> pages { token ->
                // The key goes in a header so it never appears in a URL.
                val page = getJson(provider, "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" + token.param("pageToken"),
                    "x-goog-api-key" to clean)
                page.ids("models", "name").map { it.removePrefix("models/") } to page.optString("nextPageToken").takeIf { it.isNotEmpty() }
            }
            ModelProvider.OPENROUTER -> {
                getJson(provider, "https://openrouter.ai/api/v1/key", "Authorization" to "Bearer $clean")
                // The public list says which models take tools. If it can't be read, the key is still good.
                try {
                    val data = getJson(provider, "https://openrouter.ai/api/v1/models", limit = 16_000_000).getJSONArray("data")
                    val all = (0 until data.length()).map { data.getJSONObject(it) }
                    fun JSONObject.lists(path: String, field: String, value: String) =
                        optJSONObject(path)?.optJSONArray(field).let { a -> a != null && (0 until a.length()).any { a.optString(it) == value } }
                    voiceListed = all.filter { it.lists("architecture", "input_modalities", "audio") }.map { it.getString("id") }.toSet()
                    all.filter { m -> m.optJSONArray("supported_parameters")?.let { p -> (0 until p.length()).any { p.optString(it) == "tools" } } == true }
                        .map { it.getString("id") }.toSet()
                } catch (_: Exception) {
                    null
                }
            }
        }
        val voiceIds = if (provider == ModelProvider.OPENROUTER) voiceListed else listed
        KeyCheck.Valid(
            provider,
            listed?.let { ids -> curatedModels(provider).map { it.id }.filter { it in ids }.toSet() },
            voiceIds?.let { ids -> voiceModels(provider).map { it.id }.filter { it in ids }.toSet() },
        )
    } catch (problem: BackendProblem) {
        KeyCheck.Invalid(problem.message.orEmpty())
    } catch (_: IOException) {
        KeyCheck.Invalid("Couldn't reach $label. Check your internet connection and try again.")
    } catch (_: JSONException) {
        KeyCheck.Invalid("$label sent a reply Spendidly couldn't read. Try again later.")
    }
}

/** Follows a cursor through at most five pages. */
private fun pages(fetch: (String?) -> Pair<List<String>, String?>): Set<String> {
    val ids = mutableSetOf<String>()
    var cursor: String? = null
    repeat(5) {
        val (page, next) = fetch(cursor)
        ids.addAll(page)
        cursor = next ?: return ids
    }
    return ids
}

private fun String?.param(name: String) = if (this == null) "" else "&$name=" + URLEncoder.encode(this, "UTF-8")

private fun JSONObject.ids(array: String, field: String): List<String> {
    val items = getJSONArray(array)
    return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString(field)?.takeIf(String::isNotEmpty) }
}

private fun getJson(provider: ModelProvider, url: String, vararg headers: Pair<String, String>, limit: Int = 4_000_000): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        val status = connection.responseCode
        val label = provider.label
        when {
            status in 200..299 -> Unit
            status == 401 || status == 403 || (status == 400 && provider == ModelProvider.GOOGLE) ->
                fail("API_KEY_REJECTED", "$label didn't accept this key. Check that you copied all of it.")
            status == 429 -> fail("PROVIDER_RATE_LIMIT", "$label is busy right now. Try again in a moment.")
            else -> fail("PROVIDER_ERROR", "$label had a problem (HTTP $status). Try again later.")
        }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) fail("PROVIDER_ERROR", "$label sent a reply Spendidly couldn't read. Try again later.")
                output.write(buffer, 0, count)
            }
        }
        return JSONObject(output.toByteArray().decodeToString())
    } finally {
        connection.disconnect()
    }
}
