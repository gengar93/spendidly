package io.github.gengar93.tally.backend

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ModelProvider(val label: String, internal val endpoint: String) {
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1/messages"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/chat/completions"),
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions"),
    GOOGLE("Google", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");

    /** The provider's first curated model. */
    val defaultModel: String get() = curatedModels(this).first().id
}

/** [models] and [voiceModels] are the curated chat and voice models the saved key can use; empty when no key is saved. */
data class ProviderConfiguration(
    val provider: ModelProvider, val model: String, val configured: Boolean, val models: List<CuratedModel>,
    val voiceModels: List<VoiceModel> = emptyList(),
)

// Keep the raw key out of data-class toString(), returned settings, and logs.
internal class Credentials(val provider: ModelProvider, val model: String, val apiKey: String)

/**
 * One encrypted key per provider, each with the chat model last picked for it. Keys stay readable per provider
 * apart from the chat model choice, so other features (e.g. voice) can reuse them.
 */
class ProviderSettings internal constructor(private val database: LocalDatabase, private val prefs: SharedPreferences) {
    private val keys = ProviderKeyEncryption()

    suspend fun configuration(provider: ModelProvider): ProviderConfiguration = withContext(Dispatchers.IO) {
        val stored = database.read {
            rows("SELECT model FROM provider_settings WHERE provider = ?", listOf(provider.name)) { it.getText(0) }.firstOrNull()
        }
        val model = stored?.let { curated(provider, it) } ?: provider.defaultModel
        // Installs from before the curated list may hold any model ID; move them to the provider's default.
        if (stored != null && stored != model) {
            database.write { execute("UPDATE provider_settings SET model = ? WHERE provider = ?", listOf(model, provider.name)) }
        }
        ProviderConfiguration(
            provider, model, stored != null,
            if (stored != null) usable(provider) else emptyList(),
            if (stored != null) usableVoice(provider) else emptyList(),
        )
    }

    /** Saves a key that [checkKey] accepted. [models] and [voice] are the curated IDs it can use, or null when unknown. */
    suspend fun saveKey(provider: ModelProvider, apiKey: String, models: Set<String>?, voice: Set<String>? = null): JSONObject = withContext(Dispatchers.IO) {
        result {
            val key = apiKey.trim()
            if (key.isEmpty() || key.length > 4096 || key.any { it !in '!'..'~' }) {
                fail("INVALID_API_KEY", "Enter a valid API key for ${provider.label}.")
            }
            if (models == null) prefs.edit().remove(modelsPref(provider)).apply()
            else prefs.edit().putStringSet(modelsPref(provider), models.toSet()).apply()
            if (voice == null) prefs.edit().remove(voicePref(provider)).apply()
            else prefs.edit().putStringSet(voicePref(provider), voice.toSet()).apply()
            val usable = usable(provider).map { it.id }
            database.write {
                val current = rows("SELECT model FROM provider_settings WHERE provider = ?", listOf(provider.name)) { it.getText(0) }.firstOrNull()
                val model = current?.takeIf { it in usable } ?: usable.firstOrNull() ?: provider.defaultModel
                val (ciphertext, iv) = keys.encrypt(provider, key)
                execute(
                    """INSERT INTO provider_settings (provider, model, encrypted_key, iv) VALUES (?, ?, ?, ?)
                       ON CONFLICT(provider) DO UPDATE SET model = excluded.model,
                       encrypted_key = excluded.encrypted_key, iv = excluded.iv""",
                    listOf(provider.name, model, ciphertext, iv),
                )
            }
            success()
        }
    }

    /** Picks the chat model for a provider whose key is saved. Only curated models are accepted. */
    suspend fun setModel(provider: ModelProvider, model: String): JSONObject = withContext(Dispatchers.IO) {
        result {
            if (curatedModel(provider, model) == null) fail("INVALID_MODEL", "Tally doesn't offer this model.")
            database.write {
                val existing = rows("SELECT 1 FROM provider_settings WHERE provider = ?", listOf(provider.name)) { it.getLong(0) }
                if (existing.isEmpty()) fail("MISSING_API_KEY", "Add a ${provider.label} key first.")
                execute("UPDATE provider_settings SET model = ? WHERE provider = ?", listOf(model, provider.name))
            }
            success()
        }
    }

    suspend fun clear(provider: ModelProvider) = withContext(Dispatchers.IO) {
        database.write { execute("DELETE FROM provider_settings WHERE provider = ?", listOf(provider.name)) }
        prefs.edit().remove(modelsPref(provider)).remove(voicePref(provider)).apply()
    }

    internal fun credentials(provider: ModelProvider): Credentials = database.read {
        rows(
            "SELECT model, encrypted_key, iv FROM provider_settings WHERE provider = ?",
            listOf(provider.name),
        ) {
            Credentials(provider, curated(provider, it.getText(0)), keys.decrypt(provider, it.getBlob(1), it.getBlob(2)))
        }.firstOrNull() ?: fail("MISSING_API_KEY", "Enter an API key for ${provider.label}.")
    }

    private fun curated(provider: ModelProvider, model: String) = if (curatedModel(provider, model) != null) model else provider.defaultModel

    /** Curated models the saved key can use. Keys saved before Tally checked keys list all of the provider's models. */
    private fun usable(provider: ModelProvider): List<CuratedModel> {
        val allowed = prefs.getStringSet(modelsPref(provider), null) ?: return curatedModels(provider)
        return curatedModels(provider).filter { it.id in allowed }
    }

    /** Voice models the saved key can use; as with chat, keys saved before Tally checked for them list all. */
    private fun usableVoice(provider: ModelProvider): List<VoiceModel> {
        val allowed = prefs.getStringSet(voicePref(provider), null) ?: return voiceModels(provider)
        return voiceModels(provider).filter { it.id in allowed }
    }

    private fun modelsPref(provider: ModelProvider) = "models_${provider.name}"
    private fun voicePref(provider: ModelProvider) = "voice_models_${provider.name}"
}

private class ProviderKeyEncryption {
    private val alias = "tally-provider-keys"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw GeneralSecurityException("Keystore key unavailable.")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    fun encrypt(provider: ModelProvider, value: String): Pair<ByteArray, ByteArray> {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(provider.name.toByteArray())
            return cipher.doFinal(value.toByteArray()) to cipher.iv
        } catch (_: Exception) {
            fail("KEY_STORAGE_FAILED", "The API key could not be stored securely.")
        }
    }

    fun decrypt(provider: ModelProvider, value: ByteArray, iv: ByteArray): String {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            cipher.updateAAD(provider.name.toByteArray())
            return cipher.doFinal(value).decodeToString()
        } catch (_: Exception) {
            fail("KEY_UNAVAILABLE", "The saved key could not be opened. Enter your ${provider.label} key again.")
        }
    }
}
