package com.thomaswcode.dictationapp.core.cleanup

data class PostProcessRequest(
    val level: CleanupLevel,
    val tone: Tone,
    val keyterms: List<String>,
    val appName: String,
    val url: String?,
    val appHint: String?,
    val modelOverride: String? = null,
    /** The transcript with pauses marked (TranscriptAssembler.pauseMarkedText), sent to the LLM instead when set. */
    val pauseMarkedTranscript: String? = null,
)

data class PostProcessResult(
    val text: String,
    val applied: Boolean,
    val model: String?,
    val failureReason: String?,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /**
     * A settings problem that will recur on every dictation (rejected key, a model the key may not use, an unknown or
     * retired model), set even when a fallback model covered for it, so the user can be told once.
     */
    val configurationProblem: String? = null,
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
