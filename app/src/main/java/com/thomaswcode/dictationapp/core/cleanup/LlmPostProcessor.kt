package com.thomaswcode.dictationapp.core.cleanup

import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.settings.ApiKeyProvider
import com.thomaswcode.dictationapp.core.settings.AppSettings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI-compatible chat call (Groq by default) with a per-model fallback chain. Budget: 4 s per attempt,
 * 8 s total. Rate limits (429) and other failures move to the next model; a 401/403 stops the chain; any
 * total failure returns the normalised raw transcript so the user always gets their words. Reasoning
 * models (gpt-oss) are asked for low reasoning effort, otherwise they spend the whole budget thinking.
 */
class LlmPostProcessor(
    private val client: OkHttpClient,
    private val keys: ApiKeyProvider,
    private val settings: () -> AppSettings,
    private val logger: Logger,
    private val perAttemptTimeoutMs: Long = PER_ATTEMPT_TIMEOUT_MS,
    private val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
) : TextPostProcessor {

    fun modelChain(overrideModel: String?): List<String> {
        val s = settings()
        val chain = mutableListOf<String>()
        if (!overrideModel.isNullOrBlank()) chain.add(overrideModel.trim())
        if (s.llmModel.isNotBlank()) chain.add(s.llmModel.trim())
        chain.addAll(s.llmFallbackModels.filter { it.isNotBlank() }.map { it.trim() })
        val seen = HashSet<String>()
        return chain.filter { seen.add(it.lowercase()) }
    }

    val baseUrl: HttpUrl
        get() {
            val url = settings().llmBaseUrl.trim()
            val withSlash = if (url.endsWith("/")) url else "$url/"
            return withSlash.toHttpUrlOrNull() ?: AppSettings.DEFAULT_LLM_BASE_URL.toHttpUrlOrNull()!!
        }

    override suspend fun process(rawTranscript: String, request: PostProcessRequest): PostProcessResult {
        val fallback = SpokenCommandNormaliser.normalise(rawTranscript)
        val key = keys.llmKey() ?: return PostProcessResult(fallback, false, null, "no-llm-key")
        val models = modelChain(request.modelOverride)
        if (models.isEmpty()) {
            return PostProcessResult(fallback, false, null, "no-model")
        }

        val systemPrompt = PromptBuilder.buildSystemPrompt(
            PromptContext(request.level, request.tone, request.keyterms, request.appName, request.url, request.appHint),
        )
        val endpoint = baseUrl.resolve("chat/completions")!!
        val started = System.nanoTime()
        var lastReason: String? = null

        try {
            return withTimeout(totalTimeoutMs) {
                for (model in models) {
                    val body = requestBody(model, systemPrompt, rawTranscript)
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("Authorization", "Bearer $key")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    try {
                        val call = client.newCall(req)
                        call.timeout().timeout(perAttemptTimeoutMs, TimeUnit.MILLISECONDS)
                        call.await().use { resp ->
                            val text = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                lastReason = "$model:http-${resp.code}"
                                logger.warn("LLM $model returned ${resp.code}: ${truncate(text, 300)}")
                                if (resp.code == 401 || resp.code == 403) {
                                    // Bad key: no point trying other models.
                                    return@withTimeout PostProcessResult(fallback, false, null, lastReason)
                                }

                                return@use // 429 rate limit, 404 unknown model, 5xx: next model
                            }

                            val parsed = parseChat(text)
                            val validation = OutputValidator.validate(rawTranscript, parsed.content)
                            if (!validation.isValid) {
                                lastReason = "$model:invalid-${validation.reason}"
                                // Reason and size only: log files outlive the history retention, so no dictated text.
                                logger.warn("LLM output from $model rejected (${validation.reason}, ${parsed.content?.length ?: 0} chars)")
                                return@use
                            }

                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            logger.info("LLM cleanup via $model in $elapsed ms (level=${request.level}, tone=${request.tone}, tokens=${parsed.promptTokens}+${parsed.completionTokens})")
                            return@withTimeout PostProcessResult(validation.text, true, model, null, parsed.promptTokens, parsed.completionTokens)
                        }
                    } catch (e: IOException) {
                        lastReason = if (e is java.io.InterruptedIOException) "$model:timeout" else "$model:${e.javaClass.simpleName}"
                        logger.warn("LLM $model failed: ${e.message}")
                    } catch (e: SerializationException) {
                        lastReason = "$model:${e.javaClass.simpleName}"
                        logger.warn("LLM $model returned unparseable JSON", e)
                    } catch (e: IllegalArgumentException) {
                        lastReason = "$model:${e.javaClass.simpleName}"
                        logger.warn("LLM $model returned an unexpected shape", e)
                    }
                }

                PostProcessResult(fallback, false, null, lastReason ?: "unknown")
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("LLM chain hit the $totalTimeoutMs ms total budget")
            return PostProcessResult(fallback, false, null, "total-timeout")
        }
    }

    private fun requestBody(model: String, systemPrompt: String, raw: String): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            }
            addJsonObject {
                put("role", "user")
                put("content", raw)
            }
        }
        put("max_tokens", maxTokensFor(raw))
        put("temperature", 0.1)
        reasoningEffortFor(model)?.let { put("reasoning_effort", it) }
    }

    private data class ChatResult(val content: String?, val promptTokens: Int?, val completionTokens: Int?)

    private fun parseChat(text: String): ChatResult {
        val root = json.parseToJsonElement(text).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val usage = root["usage"] as? JsonObject
        return ChatResult(
            content,
            usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
        )
    }

    companion object {
        const val PER_ATTEMPT_TIMEOUT_MS = 4_000L
        const val TOTAL_TIMEOUT_MS = 8_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** Clamped completion budget: roughly 2x the input plus headroom. */
        fun maxTokensFor(input: String): Int {
            val approxTokens = maxOf(1, input.length / 4)
            return (2 * approxTokens + 100).coerceIn(200, 4000)
        }

        /** Groq's gpt-oss models accept reasoning_effort; "low" keeps latency and tokens down. */
        fun reasoningEffortFor(model: String): String? = if (model.contains("gpt-oss", ignoreCase = true)) "low" else null

        private fun truncate(s: String?, max: Int): String = when {
            s == null -> ""
            s.length <= max -> s
            else -> s.substring(0, max) + "…"
        }
    }
}

/** Suspends on an OkHttp call; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

