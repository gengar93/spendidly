package io.github.gengar93.tally.backend

/** A chat model Tally offers. [name] and [note] make up its label, e.g. "Claude Haiku 5.5 · fast, low cost". */
data class CuratedModel(val provider: ModelProvider, val id: String, val name: String, val note: String) {
    val label: String get() = "$name · $note"
}

/**
 * The only models Tally offers for chat. Each must support tool calling through the request format
 * [ProviderClient] uses for its provider. The first model of each provider is its default.
 * IDs were confirmed against each provider's model pages in November 2026.
 */
val curatedModels: List<CuratedModel> = listOf(
    CuratedModel(ModelProvider.ANTHROPIC, "claude-haiku-5-5", "Claude Haiku 5.5", "fast, low cost"),
    CuratedModel(ModelProvider.ANTHROPIC, "claude-sonnet-5-5", "Claude Sonnet 5.5", "smarter, higher cost"),
    // GPT-6 models take tools over Chat Completions only with reasoning off; GPT-6.1 needs the Responses API.
    CuratedModel(ModelProvider.OPENAI, "gpt-6-luna", "GPT-6 Luna", "fast, low cost"),
    CuratedModel(ModelProvider.GOOGLE, "gemini-3.8-flash", "Gemini 3.8 Flash", "fast, low cost"),
    CuratedModel(ModelProvider.OPENROUTER, "anthropic/claude-haiku-5.5", "Claude Haiku 5.5", "fast, low cost"),
    CuratedModel(ModelProvider.OPENROUTER, "anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", "smarter, higher cost"),
    CuratedModel(ModelProvider.OPENROUTER, "openai/gpt-6-luna", "GPT-6 Luna", "fast, low cost"),
    CuratedModel(ModelProvider.OPENROUTER, "google/gemini-3.8-flash", "Gemini 3.8 Flash", "fast, low cost"),
    // Not yet run through the eval suite.
    CuratedModel(ModelProvider.OPENROUTER, "deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "lowest cost"),
    CuratedModel(ModelProvider.OPENROUTER, "qwen/qwen3.8-omni-flash", "Qwen 3.8 Omni Flash", "lowest cost"),
)

fun curatedModel(provider: ModelProvider, id: String): CuratedModel? = curatedModels.firstOrNull { it.provider == provider && it.id == id }

fun curatedModels(provider: ModelProvider): List<CuratedModel> = curatedModels.filter { it.provider == provider }

/** A model Tally offers for turning a voice question into text. */
data class VoiceModel(val provider: ModelProvider, val id: String, val name: String, val note: String) {
    val label: String get() = "$name · $note"
    internal val key: String get() = "${provider.name}:$id"
}

/**
 * The only voice models Tally offers, in order of preference: the first one a saved key can use is the default.
 * Anthropic has no audio input, so it has none. IDs were confirmed in November 2026 against
 * developers.openai.com (speech-to-text), aistudio.google.com (gemini-3.8-flash takes audio) and
 * OpenRouter's public model list (audio in input_modalities).
 */
val voiceModels: List<VoiceModel> = listOf(
    // OpenAI's dedicated model, through POST /v1/audio/transcriptions.
    VoiceModel(ModelProvider.OPENAI, "gpt-transcribe", "GPT Transcribe", "made for speech"),
    // The rest get the clip plus a "transcribe exactly" instruction: Gemini's generateContent, OpenRouter's chat completions.
    VoiceModel(ModelProvider.GOOGLE, "gemini-3.8-flash", "Gemini 3.8 Flash", "fast, low cost"),
    VoiceModel(ModelProvider.OPENROUTER, "google/gemini-3.8-flash", "Gemini 3.8 Flash", "fast, low cost"),
    VoiceModel(ModelProvider.OPENROUTER, "qwen/qwen3.8-omni-flash", "Qwen 3.8 Omni Flash", "lowest cost"),
)

fun voiceModels(provider: ModelProvider): List<VoiceModel> = voiceModels.filter { it.provider == provider }

/** Works out the provider from a key's prefix. Plain "sk-" is checked last, since other providers' keys start with it too. */
fun detectProvider(key: String): ModelProvider? {
    val k = key.trim()
    return when {
        k.startsWith("sk-ant-") -> ModelProvider.ANTHROPIC
        k.startsWith("sk-or-") -> ModelProvider.OPENROUTER
        k.startsWith("AIza") -> ModelProvider.GOOGLE
        k.startsWith("sk-") -> ModelProvider.OPENAI
        else -> null
    }
}
