package io.github.gengar93.tally.backend

import org.json.JSONArray
import org.json.JSONObject

internal class BackendProblem(val code: String, message: String) : RuntimeException(message)

internal fun fail(code: String, message: String): Nothing = throw BackendProblem(code, message)

internal fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((key, value) in pairs) put(key, value ?: JSONObject.NULL)
}

internal fun success(vararg pairs: Pair<String, Any?>): JSONObject = json("ok" to true, *pairs)

internal fun failure(code: String, message: String): JSONObject =
    json("ok" to false, "error" to json("code" to code, "message" to message))

internal inline fun result(block: () -> JSONObject): JSONObject = try {
    block()
} catch (error: BackendProblem) {
    failure(error.code, error.message.orEmpty())
}

internal fun JSONObject.value(key: String): Any? = opt(key).takeUnless { it === JSONObject.NULL }

internal fun JSONObject.only(keys: Set<String>) {
    if (keys().asSequence().any { it !in keys }) {
        fail("INVALID_ARGUMENTS", "Use only ${keys.joinToString()}.")
    }
}

internal fun JSONArray.values(): List<Any?> = (0 until length()).map { opt(it).takeUnless { value -> value === JSONObject.NULL } }

internal fun JSONObject.integer(key: String, default: Int, range: IntRange, code: String): Int {
    val raw = value(key) ?: return default
    val number = (raw as? Number)?.toDouble()
    if (number == null || number != number.toInt().toDouble() || number.toInt() !in range) {
        fail(code, "$key must be an integer between ${range.first} and ${range.last}.")
    }
    return number.toInt()
}
