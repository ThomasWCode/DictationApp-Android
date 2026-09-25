package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.WavData
import com.thomaswcode.dictationapp.core.audio.WavFileSink
import com.thomaswcode.dictationapp.core.audio.WavReplayAudioCapture
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.LlmPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.PostProcessRequest
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.dictionary.DictionaryTerm
import com.thomaswcode.dictationapp.core.history.RetentionPolicy
import com.thomaswcode.dictationapp.core.settings.ApiKeyProvider
import com.thomaswcode.dictationapp.core.settings.AppSettings
import com.thomaswcode.dictationapp.core.settings.BubbleSide
import com.thomaswcode.dictationapp.core.settings.JsonSettingsStore
import com.thomaswcode.dictationapp.core.settings.SecretStore
import com.thomaswcode.dictationapp.core.settings.SettingsApiKeyProvider
import com.thomaswcode.dictationapp.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FixedSettings(initial: AppSettings) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val flow: StateFlow<AppSettings> = state

    override fun update(transform: (AppSettings) -> AppSettings): AppSettings = transform(state.value).also { state.value = it }
}

class StaticKeys(private val assembly: String? = "aai", private val llm: String? = "gsk_test") : ApiKeyProvider {
    override fun assemblyAiKey() = assembly

    override fun llmKey() = llm
}

/** Ported from the Windows LlmPostProcessorTests, with MockWebServer in place of a scripted HttpMessageHandler. */
class LlmPostProcessorTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val request = PostProcessRequest(CleanupLevel.Medium, Tone.Formal, listOf("LSHTM"), "Gmail", null, "This is an email.")
    private val raw = "so um I think we should ship on tuesday"

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun settings(model: String = "a", fallbacks: List<String> = listOf("b", "c")) =
        FixedSettings(AppSettings(llmBaseUrl = server.url("/v1/").toString(), llmModel = model, llmFallbackModels = fallbacks))

    private fun processor(s: SettingsStore = settings(), keys: ApiKeyProvider = StaticKeys(), perAttempt: Long = 4_000, total: Long = 8_000) =
        LlmPostProcessor(client, keys, { s.current }, NoLogger, perAttempt, total)

    private fun ok(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(content)}}}],"usage":{"prompt_tokens":100,"completion_tokens":12}}""",
    )

    @Test
    fun callsWithBearerKeyAndReturnsCleanedText() = runBlocking {
        server.enqueue(ok("I think we should ship on Tuesday."))
        val result = processor(settings(model = "qwen/qwen3.8-27b")).process(raw, request)
        assertTrue(result.applied)
        assertEquals("I think we should ship on Tuesday.", result.text)
        assertEquals("qwen/qwen3.8-27b", result.model)
        assertEquals(100, result.promptTokens)
        val call = server.takeRequest()
        assertEquals("Bearer gsk_test", call.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", call.path)
        val body = call.body.readUtf8()
        assertTrue(body, body.contains("\"temperature\":0.1"))
        assertTrue(body.contains("LSHTM"))
        assertFalse(body.contains("reasoning_effort"))
    }

    @Test
    fun reasoningModelsAreAskedForLowEffort() = runBlocking {
        server.enqueue(ok("I think we should ship on Tuesday."))
        processor(settings(model = "openai/gpt-oss-120b")).process(raw, request)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"reasoning_effort\":\"low\""))
        assertEquals("low", LlmPostProcessor.reasoningEffortFor("openai/gpt-oss-20b"))
        assertNull(LlmPostProcessor.reasoningEffortFor("qwen/qwen3.8-27b"))
    }

    @Test
    fun rateLimitMovesToTheNextModel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(ok("I think we should ship on Tuesday."))
        val result = processor().process(raw, request)
        assertTrue(result.applied)
        assertEquals("c", result.model)
        assertEquals(listOf("a", "b", "c"), (1..3).map { Regex("\"model\":\"(\\w)\"").find(server.takeRequest().body.readUtf8())!!.groupValues[1] })
    }

    @Test
    fun unauthorizedStopsTheChainAndReturnsRaw() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = processor().process("raw period", request)
        assertFalse(result.applied)
        assertEquals("Raw.", result.text)
        assertEquals(1, server.requestCount)
        assertTrue(result.failureReason!!.contains("http-401"))
    }

    @Test
    fun invalidOutputIsRejectedAndNextModelTried() = runBlocking {
        server.enqueue(ok("Sure! Here is your text."))
        server.enqueue(ok("I think we should ship on Tuesday."))
        val result = processor().process(raw, request)
        assertTrue(result.applied)
        assertEquals("b", result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun allModelsFailingReturnsNormalisedRawWithReason() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        val result = processor().process("hello new line world", request)
        assertFalse(result.applied)
        assertEquals("Hello\nWorld", result.text)
        assertTrue(result.failureReason!!.contains("http-500"))
    }

    @Test
    fun missingKeyShortCircuits() = runBlocking {
        val result = processor(keys = StaticKeys(llm = null)).process(raw, request)
        assertFalse(result.applied)
        assertEquals("no-llm-key", result.failureReason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun perAttemptTimeoutMovesToNextModel() = runBlocking {
        server.enqueue(ok("slow").setHeadersDelay(2, TimeUnit.SECONDS))
        server.enqueue(ok("I think we should ship on Tuesday."))
        val result = processor(perAttempt = 300, total = 5_000).process(raw, request)
        assertTrue(result.applied)
        assertEquals("b", result.model)
    }

    @Test
    fun totalTimeoutGivesUpWithRawText() = runBlocking {
        repeat(3) { server.enqueue(ok("slow").setHeadersDelay(2, TimeUnit.SECONDS)) }
        val result = processor(perAttempt = 1_500, total = 600).process("some words to clean up", request)
        assertFalse(result.applied)
        assertEquals("Some words to clean up", result.text)
        assertEquals("total-timeout", result.failureReason)
    }

    @Test
    fun maxTokensIsClamped() {
        assertEquals(200, LlmPostProcessor.maxTokensFor("hi"))
        assertEquals(4000, LlmPostProcessor.maxTokensFor("x".repeat(40_000)))
        assertEquals(2 * 100 + 100, LlmPostProcessor.maxTokensFor("x".repeat(400)))
    }

    @Test
    fun modelChainDedupesAndHonoursOverride() {
        val p = processor(settings(model = "a", fallbacks = listOf("b", "A", "c", " ")))
        assertEquals(listOf("z", "a", "b", "c"), p.modelChain("z"))
        assertEquals(listOf("a", "b", "c"), p.modelChain(null))
    }
}

class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTripsAndPublishes() {
        val file = File(tmp.root, "settings.json")
        val store = JsonSettingsStore(file, NoLogger)
        store.update {
            it.copy(
                defaultTone = Tone.Formal,
                bubbleSizePercent = 130,
                bubbleSide = BubbleSide.Left,
                historyRetention = RetentionPolicy.Forever,
                dictionary = listOf(DictionaryTerm("LSHTM", starred = true)),
            )
        }
        assertEquals(Tone.Formal, store.current.defaultTone)
        val reloaded = JsonSettingsStore(file, NoLogger).current
        assertEquals(Tone.Formal, reloaded.defaultTone)
        assertEquals(130, reloaded.bubbleSizePercent)
        assertEquals(BubbleSide.Left, reloaded.bubbleSide)
        assertEquals(RetentionPolicy.Forever, reloaded.historyRetention)
        assertEquals("LSHTM", reloaded.dictionary.single().term)
        assertEquals(AppSettings().appRules, reloaded.appRules)
        assertTrue(file.readText().contains("\"defaultTone\": \"Formal\""))
    }

    @Test
    fun corruptFileIsMovedAsideAndDefaultsUsed() {
        val file = File(tmp.root, "settings.json").apply { writeText("{ not json") }
        val store = JsonSettingsStore(file, NoLogger)
        assertEquals(AppSettings(), store.current)
        assertFalse(file.exists())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("settings.json.corrupt-") })
    }

    @Test
    fun unknownKeysAndEnumValuesFallBackToDefaults() {
        val file = File(tmp.root, "settings.json").apply { writeText("""{"defaultTone":"Pirate","someFutureSetting":true,"bubbleOpacityPercent":55}""") }
        val s = JsonSettingsStore(file, NoLogger).current
        assertEquals(Tone.Neutral, s.defaultTone)
        assertEquals(55, s.bubbleOpacityPercent)
    }

    @Test
    fun keyProviderUnprotectsStoredKeysSeparately() {
        val secrets = object : SecretStore {
            override fun protect(plaintext: String) = "enc:" + plaintext.reversed()

            override fun unprotect(protectedValue: String) = if (protectedValue.startsWith("enc:")) protectedValue.removePrefix("enc:").reversed() else null
        }
        val store = FixedSettings(AppSettings(apiKeyProtected = secrets.protect(" aai-key "), groqApiKeyProtected = "garbage"))
        val keys = SettingsApiKeyProvider(store, secrets)
        assertEquals("aai-key", keys.assemblyAiKey())
        assertNull(keys.llmKey())
        store.update { it.copy(groqApiKeyProtected = null) }
        assertNull(keys.llmKey())
    }
}

class WavTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sinkWritesAValidHeaderAndReadsBack() {
        val path = File(tmp.root, "a/b/test.wav").path
        val sink = WavFileSink(path)
        val pcm = ByteArray(AudioFrame.FRAME_BYTES) { (it % 7).toByte() }
        sink.write(pcm)
        sink.write(pcm)
        sink.complete()
        assertEquals(2L * pcm.size, sink.bytesWritten)
        assertEquals(44L + 2 * pcm.size, File(path).length())
        val wav = WavData.read(path)
        assertEquals(16_000, wav.sampleRate)
        assertEquals(1, wav.channels)
        assertEquals(200L, wav.durationMs)
        assertArrayEquals(pcm + pcm, wav.pcm16)
    }

    @Test
    fun discardDeletesTheFile() {
        val path = File(tmp.root, "d.wav").path
        val sink = WavFileSink(path)
        sink.write(ByteArray(10))
        sink.discard()
        assertFalse(File(path).exists())
    }

    @Test
    fun replayDeliversAllFramesThenCompletes() {
        val path = File(tmp.root, "r.wav").path
        WavFileSink(path).apply {
            write(ByteArray(AudioFrame.FRAME_BYTES * 3 + 100))
            complete()
        }
        val capture = WavReplayAudioCapture(path, 20.0)
        var bytes = 0
        val done = CountDownLatch(1)
        capture.onFrame = { bytes += it.pcm16.size }
        capture.onCompleted = { done.countDown() }
        capture.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(AudioFrame.FRAME_BYTES * 3 + 100, bytes)
    }

    @Test
    fun peakIsScaledToUnitRange() {
        assertEquals(0f, AudioFrame.peakOf(ByteArray(8)), 0f)
        val loud = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F) // -32768, 32767
        assertEquals(1f, AudioFrame.peakOf(loud), 0.001f)
        assertNotNull(AudioFrame.peakOf(ByteArray(1)))
    }
}
