package com.thomaswcode.dictationapp.core.cleanup

data class PostProcessRequest(
    val level: CleanupLevel,
    val tone: Tone,
    val keyterms: List<String>,
    val appName: String,
    val url: String?,
    val appHint: String?,
    val modelOverride: String? = null,
)

data class PostProcessResult(
    val text: String,
    val applied: Boolean,
    val model: String?,
    val failureReason: String?,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
) {
    companion object {
        fun passthrough(text: String) = PostProcessResult(text, false, null, null)
    }
}

interface TextPostProcessor {
    suspend fun process(rawTranscript: String, request: PostProcessRequest): PostProcessResult
}

/** Spoken-command normalisation only; no network. */
class PassthroughPostProcessor : TextPostProcessor {
    override suspend fun process(rawTranscript: String, request: PostProcessRequest): PostProcessResult =
        PostProcessResult.passthrough(SpokenCommandNormaliser.normalise(rawTranscript))
}

/** Sends None+Neutral to the passthrough (no network call) and everything else to the LLM. */
class PostProcessorRouter(
    private val passthrough: TextPostProcessor,
    private val llm: TextPostProcessor,
) : TextPostProcessor {
    override suspend fun process(rawTranscript: String, request: PostProcessRequest): PostProcessResult =
        if (needsLlm(request.level, request.tone)) {
            llm.process(rawTranscript, request)
        } else {
            passthrough.process(rawTranscript, request)
        }

    companion object {
        fun needsLlm(level: CleanupLevel, tone: Tone): Boolean = level != CleanupLevel.None || tone != Tone.Neutral
    }
}
