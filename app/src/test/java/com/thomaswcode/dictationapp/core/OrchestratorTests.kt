package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.audio.AudioCapture
import com.thomaswcode.dictationapp.core.audio.AudioCaptureFactory
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.AudioSink
import com.thomaswcode.dictationapp.core.audio.AudioSinkFactory
import com.thomaswcode.dictationapp.core.audio.WavFileSink
import com.thomaswcode.dictationapp.core.audio.WavReplayAudioCapture
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.PostProcessRequest
import com.thomaswcode.dictationapp.core.cleanup.PostProcessResult
import com.thomaswcode.dictationapp.core.cleanup.TextPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.dictionary.DictionaryTerm
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.history.HistoryRepository
import com.thomaswcode.dictationapp.core.history.HistoryStats
import com.thomaswcode.dictationapp.core.history.RecordStatus
import com.thomaswcode.dictationapp.core.insertion.Clipboard
import com.thomaswcode.dictationapp.core.insertion.ForegroundContext
import com.thomaswcode.dictationapp.core.insertion.ForegroundContextProvider
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.insertion.InsertionOutcome
import com.thomaswcode.dictationapp.core.insertion.InsertionResult
import com.thomaswcode.dictationapp.core.insertion.Notifier
import com.thomaswcode.dictationapp.core.insertion.TextInserter
import com.thomaswcode.dictationapp.core.insertion.ToastKind
import com.thomaswcode.dictationapp.core.rules.AppRule
import com.thomaswcode.dictationapp.core.session.DictationOrchestrator
import com.thomaswcode.dictationapp.core.session.DictationState
import com.thomaswcode.dictationapp.core.session.DictationStateMachine
import com.thomaswcode.dictationapp.core.session.DictationTrigger
import com.thomaswcode.dictationapp.core.session.StatusHub
import com.thomaswcode.dictationapp.core.settings.AppPaths
import com.thomaswcode.dictationapp.core.settings.AppSettings
import com.thomaswcode.dictationapp.core.transcription.BeginMessage
import com.thomaswcode.dictationapp.core.transcription.SessionOptions
import com.thomaswcode.dictationapp.core.transcription.StreamingTranscriber
import com.thomaswcode.dictationapp.core.transcription.StreamingTranscriberFactory
import com.thomaswcode.dictationapp.core.transcription.TerminationMessage
import com.thomaswcode.dictationapp.core.transcription.TurnMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------- fakes

class FakeCapture : AudioCapture {
    override var onFrame: ((AudioFrame) -> Unit)? = null
    override var onFault: ((Throwable) -> Unit)? = null
    override var onCompleted: (() -> Unit)? = null

    @Volatile var running = false

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun close() = stop()

    fun emit(fill: Byte, peak: Float = 0.4f) = onFrame?.invoke(AudioFrame(ByteArray(AudioFrame.FRAME_BYTES) { fill }, peak, 0))
}

class FakeSink(override val path: String) : AudioSink {
    @Volatile override var bytesWritten = 0L

    @Volatile var completed = false

    @Volatile var discarded = false

    override fun write(pcm16: ByteArray) {
        bytesWritten += pcm16.size
    }

    override fun complete() {
        completed = true
    }

    override fun discard() {
        discarded = true
    }

    override fun close() = Unit
}

class FakeTranscriber(private val autoConnect: Boolean = false, private val autoTurnText: String? = null) : StreamingTranscriber {
    private val begin = CompletableDeferred<BeginMessage>()
    val frames = AtomicInteger()

    @Volatile var shutdownCalled = false

    /** When set, shutdown fails like a socket that dropped during the handshake. */
    @Volatile var shutdownError: Throwable? = null

    /** When false, shutdown returns no Termination summary. */
    @Volatile var sendTermination = true

    /** Raise a socket fault after the release, when the session no longer selects on it, then return no summary. */
    @Volatile var faultBeforeShutdown = false

    @Volatile var aborted = false
    override var onTurn: ((TurnMessage) -> Unit)? = null
    override var onFault: ((Throwable) -> Unit)? = null
    override val isConnected: Boolean get() = begin.isCompleted && !aborted
    override val connectLatencyMs: Long? = 5

    override suspend fun connect(options: SessionOptions): BeginMessage {
        if (autoConnect) begin.complete(BeginMessage("auto"))
        return begin.await()
    }

    override fun sendAudio(pcm16: ByteArray) {
        if (frames.incrementAndGet() == 1 && autoTurnText != null) onTurn?.invoke(TurnMessage(0, true, true, autoTurnText, autoTurnText))
    }

    override fun updateConfiguration(keyterms: List<String>?, prompt: String?) = Unit

    override suspend fun shutdown(hardCapMs: Long): TerminationMessage? {
        shutdownCalled = true
        shutdownError?.let { throw it }
        if (faultBeforeShutdown) {
            onFault?.invoke(IOException("socket dropped"))
            return null
        }

        return if (sendTermination) TerminationMessage(audioDurationSeconds = 3.0) else null
    }

    override fun abort() {
        aborted = true
        begin.completeExceptionally(IOException("aborted"))
    }

    fun completeConnect() = begin.complete(BeginMessage("s1"))

    fun failConnect(e: Throwable) = begin.completeExceptionally(e)

    fun raiseTurn(order: Int, text: String, endOfTurn: Boolean, formatted: Boolean = false) =
        onTurn?.invoke(TurnMessage(order, formatted, endOfTurn, text, text))
}

class FakeInserter : TextInserter {
    val inserted = CopyOnWriteArrayList<Pair<String, InsertMethod>>()
    var outcome = InsertionOutcome.Inserted

    override suspend fun insert(text: String, target: ForegroundContext, method: InsertMethod): InsertionResult {
        inserted += text to method
        return InsertionResult(outcome, if (outcome == InsertionOutcome.Inserted) null else "nope", text)
    }
}

class FakeClipboard : Clipboard {
    val texts = CopyOnWriteArrayList<String>()

    override suspend fun setText(text: String) {
        texts += text
    }
}

class FakeNotifier : Notifier {
    val toasts = CopyOnWriteArrayList<Triple<String, String, ToastKind>>()

    @Volatile var micRequested = false

    override fun toast(title: String, message: String, kind: ToastKind) {
        toasts += Triple(title, message, kind)
    }

    override fun requestMicrophonePermission() {
        micRequested = true
    }
}

class FakePostProcessor : TextPostProcessor {
    val requests = CopyOnWriteArrayList<PostProcessRequest>()

    @Volatile var gate: CompletableDeferred<Unit>? = null

    @Volatile var respond: (String) -> PostProcessResult = { raw -> PostProcessResult(raw.uppercase(), true, "test-model", null) }

    override suspend fun process(rawTranscript: String, request: PostProcessRequest): PostProcessResult {
        requests += request
        gate?.await()
        return respond(rawTranscript)
    }
}

class InMemoryHistory : HistoryRepository {
    val records = CopyOnWriteArrayList<DictationRecord>()
    private var nextId = 1L

    override suspend fun initialise() = Unit

    override suspend fun insert(record: DictationRecord): Long {
        record.id = nextId++
        records += record.copy()
        return record.id
    }

    override suspend fun update(record: DictationRecord) {
        records.replaceAll { if (it.id == record.id) record.copy() else it }
    }

    override suspend fun get(id: Long) = records.firstOrNull { it.id == id }?.copy()

    override suspend fun search(query: String?, limit: Int) = records.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun latest() = records.maxByOrNull { it.createdAt }

    override suspend fun delete(id: Long) {
        records.removeIf { it.id == id }
    }

    override suspend fun deleteOlderThan(olderThan: Long) = emptyList<String>()

    override suspend fun clearAudioOlderThan(olderThan: Long) = emptyList<String>()

    override suspend fun stats() = HistoryStats(records.size, 0.0, 0, 0)

    override suspend fun deleteAll() = emptyList<String>().also { records.clear() }

    override suspend fun referencedAudioPaths() = records.mapNotNull { it.audioPath }.toSet()
}

// ---------------------------------------------------------------- tests

class DictationStateMachineTest {
    @Test
    fun fullHappyPath() {
        val m = DictationStateMachine()
        assertEquals(DictationState.Arming, m.fire(DictationTrigger.Press))
        assertEquals(DictationState.Recording, m.fire(DictationTrigger.BeginReceived))
        assertEquals(DictationState.Finalising, m.fire(DictationTrigger.Release))
        assertEquals(DictationState.PostProcessing, m.fire(DictationTrigger.HandshakeComplete))
        assertEquals(DictationState.Inserting, m.fire(DictationTrigger.PostProcessed))
        assertEquals(DictationState.Idle, m.fire(DictationTrigger.Inserted))
    }

    @Test
    fun releaseBeforeBeginFinalisesInsteadOfCancelling() {
        val m = DictationStateMachine()
        m.fire(DictationTrigger.Press)
        assertEquals(DictationState.Finalising, m.fire(DictationTrigger.Release))
    }

    @Test
    fun armingFailurePathsReturnToIdle() {
        for (t in listOf(DictationTrigger.ConnectTimeout, DictationTrigger.SocketFault, DictationTrigger.Cancel, DictationTrigger.Failed)) {
            val m = DictationStateMachine()
            m.fire(DictationTrigger.Press)
            assertEquals(t.name, DictationState.Idle, m.fire(t))
        }
    }

    @Test
    fun capReachedFinalisesAndNothingHeardGoesIdle() {
        val m = DictationStateMachine()
        m.fire(DictationTrigger.Press)
        m.fire(DictationTrigger.BeginReceived)
        assertEquals(DictationState.Finalising, m.fire(DictationTrigger.CapReached))
        assertEquals(DictationState.Idle, m.fire(DictationTrigger.NothingHeard))
    }

    @Test
    fun pressWhileBusyIsIgnoredAndFlashes() {
        var flashes = 0
        val m = DictationStateMachine(onPressIgnored = { flashes++ })
        m.fire(DictationTrigger.Press)
        m.fire(DictationTrigger.BeginReceived)
        assertEquals(DictationState.Recording, m.fire(DictationTrigger.Press))
        assertEquals(1, flashes)
    }

    @Test
    fun irrelevantTriggersAreIgnoredInEveryState() {
        for (state in DictationState.entries) {
            for (trigger in DictationTrigger.entries) {
                // Every pair either has a defined target or is ignored; nothing throws.
                DictationStateMachine.next(state, trigger)
            }
        }

        val m = DictationStateMachine()
        assertEquals(DictationState.Idle, m.fire(DictationTrigger.Inserted))
        assertEquals(DictationState.Idle, m.fire(DictationTrigger.Release))
    }
}

/** End-to-end orchestration with in-memory fakes for every platform seam (ported from the Windows tests). */
class DictationOrchestratorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val capture = FakeCapture()
    private val sinks = CopyOnWriteArrayList<FakeSink>()
    private val transcribers = CopyOnWriteArrayList<FakeTranscriber>()
    private var transcriberFactory: () -> FakeTranscriber = { FakeTranscriber() }
    private var replayPath: String? = null
    private val editable = ForegroundContext(7, "com.whatsapp", "WhatsApp", "Chat", null, true, false, "EditText")
    private var context = editable
    private val inserter = FakeInserter()
    private val clipboard = FakeClipboard()
    private val notifier = FakeNotifier()
    private val post = FakePostProcessor()
    private val history = InMemoryHistory()
    private val settings = FixedSettings(AppSettings(appRules = listOf(AppRule(packageGlob = "com.whatsapp", tone = Tone.Casual, level = CleanupLevel.Light, hint = "chat"))))
    private val hub = StatusHub()
    private var micGranted = true
    private val flashes = AtomicInteger()
    private lateinit var orchestrator: DictationOrchestrator

    /** The session creates these asynchronously after press(); wait for them rather than racing. */
    private val transcriber: FakeTranscriber
        get() {
            waitFor("transcriber created") { transcribers.isNotEmpty() }
            return transcribers.last()
        }

    private val sink: FakeSink
        get() {
            waitFor("sink created") { sinks.isNotEmpty() }
            return sinks.last()
        }

    private fun start() {
        orchestrator = DictationOrchestrator(
            captures = object : AudioCaptureFactory {
                override fun createMicrophone(deviceKey: String?) = capture

                override fun createWavReplay(path: String, speed: Double): AudioCapture = if (replayPath != null) WavReplayAudioCapture(path, 50.0) else capture
            },
            sinks = AudioSinkFactory { path -> FakeSink(path).also { sinks += it } },
            transcribers = StreamingTranscriberFactory { transcriberFactory().also { transcribers += it } },
            foreground = ForegroundContextProvider { context },
            inserter = inserter,
            clipboard = clipboard,
            notifier = notifier,
            postProcessor = post,
            history = history,
            settings = settings,
            hub = hub,
            paths = AppPaths(tmp.root),
            logger = NoLogger,
            hasMicrophonePermission = { micGranted },
        )
        orchestrator.start(scope)
        scope.launch { hub.flashed.collect { flashes.incrementAndGet() } }
    }

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun waitForState(state: DictationState) = waitFor("state $state") { orchestrator.state == state }

    /**
     * Arming is entered as soon as the press is dispatched, but the session job attaches the frame handler and
     * starts the microphone a moment later; frames emitted before that would be dropped.
     */
    private fun waitForArmed() {
        waitForState(DictationState.Arming)
        waitFor("microphone started") { capture.running }
    }

    private fun waitForIdleSession() {
        waitFor("idle") { orchestrator.state == DictationState.Idle }
        runBlocking { orchestrator.currentSessionJob?.join() }
    }

    private fun dictate(text: String, handsFree: Boolean = false) {
        orchestrator.press()
        if (handsFree) orchestrator.enterHandsFree()
        waitForArmed()
        capture.emit(1)
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(2)
        transcriber.raiseTurn(0, text, endOfTurn = true, formatted = true)
        orchestrator.release()
        waitForIdleSession()
    }

    @Test
    fun holdInsertsCleanedTextAndRecordsHistory() {
        start()
        orchestrator.press()
        waitForArmed()
        waitFor("hub arming") { hub.current.state == DictationState.Arming }

        // Audio captured before the socket is ready must be buffered, not lost.
        capture.emit(1)
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        waitFor("buffered frame flushed") { transcriber.frames.get() == 1 }

        capture.emit(2)
        transcriber.raiseTurn(0, "hello world", endOfTurn = false)
        waitFor("live text") { hub.current.liveText == "hello world" }
        transcriber.raiseTurn(0, "Hello world.", endOfTurn = true, formatted = true)
        orchestrator.release()
        waitForIdleSession()

        assertEquals(listOf("HELLO WORLD." to InsertMethod.Direct), inserter.inserted.toList())
        assertTrue(transcriber.shutdownCalled)
        assertTrue(sink.completed)
        val record = history.records.single()
        assertEquals(RecordStatus.Inserted, record.status)
        assertEquals("Hello world.", record.rawTranscript)
        assertEquals("HELLO WORLD.", record.cleanedText)
        assertEquals("com.whatsapp", record.packageName)
        assertEquals(Tone.Casual, record.tone)
        assertEquals(sink.path, record.audioPath)
        assertTrue(record.costEstimate!! > 0)
        assertEquals("chat", post.requests.single().appHint)
        assertEquals("WhatsApp", post.requests.single().appName)
        assertEquals(DictationState.Idle, hub.current.state)
        assertNull(hub.current.badge)
    }

    @Test
    fun tapStartsHandsFreeAndReleaseStopsIt() {
        start()
        orchestrator.press()
        orchestrator.enterHandsFree()
        waitFor("hands-free published") { hub.current.handsFree && hub.current.isActive }
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        assertTrue(hub.current.handsFree)
        capture.emit(1)
        transcriber.raiseTurn(0, "Hands free.", true, true)
        orchestrator.release()
        waitForIdleSession()
        assertEquals("HANDS FREE.", inserter.inserted.single().first)
        assertFalse(hub.current.handsFree)
    }

    @Test
    fun releaseBeforeBeginWaitsForTheConnectionThenFlushes() {
        start()
        orchestrator.press()
        waitForArmed()
        capture.emit(1)
        capture.emit(2)
        orchestrator.release()
        waitForState(DictationState.Finalising)
        transcriber.raiseTurn(0, "Late start.", true, true)
        transcriber.completeConnect()
        waitForIdleSession()
        assertEquals(2, transcriber.frames.get())
        assertEquals("LATE START.", inserter.inserted.single().first)
    }

    @Test
    fun cancelDiscardsTheDictation() {
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        transcriber.raiseTurn(0, "Never mind.", true, true)
        orchestrator.cancel()
        waitForIdleSession()
        assertTrue(inserter.inserted.isEmpty())
        assertTrue(history.records.isEmpty())
        assertTrue(sink.discarded)
        assertTrue(transcriber.aborted)
        assertEquals("Discarded", hub.current.badge)
    }

    @Test
    fun silentCancelLeavesNoBadge() {
        start()
        orchestrator.press()
        waitForArmed()
        orchestrator.cancel(silent = true)
        waitForIdleSession()
        assertNull(hub.current.badge)
        assertTrue(history.records.isEmpty())
    }

    @Test
    fun noTextFieldGoesToClipboardWithToast() {
        context = editable.copy(isEditable = false, editableReason = "no-input-focus")
        start()
        dictate("Copy me.")
        assertTrue(inserter.inserted.isEmpty())
        assertEquals(listOf("COPY ME."), clipboard.texts.toList())
        assertEquals(RecordStatus.CopiedOnly, history.records.single().status)
        assertTrue(notifier.toasts.any { it.first == "Copied to clipboard" })
    }

    @Test
    fun passwordFieldIsNeverTypedInto() {
        context = editable.copy(isPassword = true)
        start()
        dictate("secret words")
        assertTrue(inserter.inserted.isEmpty())
        assertEquals(RecordStatus.CopiedOnly, history.records.single().status)
    }

    @Test
    fun connectFailureKeepsAudioAndRecordsFailed() {
        start()
        orchestrator.press()
        waitForArmed()
        capture.emit(1)
        transcriber.failConnect(IOException("no network"))
        waitForIdleSession()
        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals(sink.path, record.audioPath)
        assertTrue(sink.completed)
        assertTrue(notifier.toasts.any { it.first == "Network error" })
        assertEquals("Failed", hub.current.badge)
    }

    @Test
    fun failureWithoutAudioKeepsThePartialTranscript() {
        settings.update { it.copy(storeAudio = false) }
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        transcriber.raiseTurn(0, "Half a sentence", endOfTurn = false)
        transcriber.onFault?.invoke(IOException("socket dropped"))
        waitForIdleSession()
        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals("Half a sentence", record.rawTranscript)
        assertNull(record.audioPath)
        assertTrue(sinks.isEmpty())
        assertTrue(notifier.toasts.any { it.second.contains("saved in History") })
    }

    @Test
    fun failureWithNeitherAudioNorWordsOnlyToasts() {
        settings.update { it.copy(storeAudio = false) }
        start()
        orchestrator.press()
        waitForArmed()
        transcriber.failConnect(IOException("no network"))
        waitForIdleSession()
        assertTrue(history.records.isEmpty())
        assertTrue(notifier.toasts.any { it.first == "Dictation failed" })
    }

    @Test
    fun nothingHeardWritesNoRecordAndRemovesAudio() {
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        orchestrator.release()
        waitForIdleSession()
        assertTrue(history.records.isEmpty())
        assertTrue(sink.discarded)
        assertTrue(post.requests.isEmpty())
        assertEquals("Nothing heard", hub.current.badge)
    }

    @Test
    fun cleanupFailureInsertsRawTextWithBadge() {
        post.respond = { raw -> PostProcessResult(raw, false, null, "a:http-429") }
        start()
        dictate("Raw words.")
        assertEquals("Raw words.", inserter.inserted.single().first)
        val record = history.records.single()
        assertEquals("", record.cleanedText)
        assertEquals("a:http-429", record.failureReason)
        assertEquals("cleanup skipped", hub.current.badge)
    }

    @Test
    fun pressWhileFinishingFlashesAndIsIgnored() {
        val gate = CompletableDeferred<Unit>()
        post.gate = gate
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        transcriber.raiseTurn(0, "Busy.", true, true)
        orchestrator.release()
        waitForState(DictationState.PostProcessing)
        orchestrator.press()
        waitFor("flash") { flashes.get() == 1 }
        gate.complete(Unit)
        waitForIdleSession()
        assertEquals(1, transcribers.size)
        assertEquals(1, history.records.size)
    }

    @Test
    fun missingMicrophonePermissionAsksForItAndRecordsNothing() {
        micGranted = false
        start()
        orchestrator.press()
        waitFor("microphone permission requested") { notifier.micRequested }
        waitForIdleSession()
        assertTrue(notifier.micRequested)
        assertTrue(notifier.toasts.any { it.first == "Microphone blocked" })
        assertTrue(history.records.isEmpty())
        assertFalse(capture.running)
    }

    @Test
    fun socketFailureDuringShutdownKeepsAudioAsFailedInsteadOfInserting() {
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        transcriber.raiseTurn(0, "The start of a longer", endOfTurn = false)
        transcriber.shutdownError = IOException("Streaming connection lost during shutdown")
        orchestrator.release()
        waitForIdleSession()
        assertTrue(inserter.inserted.isEmpty())
        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals(sink.path, record.audioPath)
        assertTrue(notifier.toasts.any { it.first == "Network error" })
    }

    @Test
    fun faultAfterReleaseWithoutSummaryIsNotInserted() {
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        transcriber.raiseTurn(0, "Cut off", endOfTurn = false)
        transcriber.faultBeforeShutdown = true
        orchestrator.release()
        waitForIdleSession()
        assertTrue(inserter.inserted.isEmpty())
        assertEquals(RecordStatus.Failed, history.records.single().status)
    }

    @Test
    fun insertMethodFollowsTheAppTheTextLandsIn() {
        settings.update { it.copy(appRules = listOf(AppRule(packageGlob = "com.google.android.gm", insertMethod = InsertMethod.Paste, tone = Tone.Formal))) }
        start()
        orchestrator.press()
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        capture.emit(1)
        transcriber.raiseTurn(0, "Moved apps.", true, true)
        context = editable.copy(windowId = 9, packageName = "com.google.android.gm", appLabel = "Gmail")
        orchestrator.release()
        waitForIdleSession()
        // Paste from Gmail's rule; the tone chosen where the dictation started (no WhatsApp rule: default) is kept.
        assertEquals(InsertMethod.Paste, inserter.inserted.single().second)
        assertEquals(Tone.Neutral, post.requests.single().tone)
    }

    @Test
    fun appRuleInsertMethodIsPassedToTheInserter() {
        settings.update { it.copy(appRules = listOf(AppRule(packageGlob = "com.whatsapp", insertMethod = InsertMethod.Paste))) }
        start()
        dictate("Paste this.")
        assertEquals(InsertMethod.Paste, inserter.inserted.single().second)
    }

    @Test
    fun dictionaryUsageIsCounted() {
        settings.update { it.copy(dictionary = listOf(DictionaryTerm("LSHTM", addedAt = 2), DictionaryTerm("unused", addedAt = 1))) }
        start()
        dictate("I work at LSHTM.")
        val terms = settings.current.dictionary.associateBy { it.term }
        assertEquals(1, terms.getValue("LSHTM").useCount)
        assertEquals(0, terms.getValue("unused").useCount)
        assertEquals(listOf("LSHTM", "unused"), post.requests.single().keyterms)
    }

    @Test
    fun retryReStreamsAudioAndCopiesResult() {
        val wav = File(tmp.root, "failed.wav").path
        WavFileSink(wav).apply {
            write(ByteArray(AudioFrame.FRAME_BYTES * 4) { 3 })
            complete()
        }
        replayPath = wav
        transcriberFactory = { FakeTranscriber(autoConnect = true, autoTurnText = "Recovered text.") }
        start()
        val id = runBlocking {
            history.insert(DictationRecord(createdAt = 1, updatedAt = 1, status = RecordStatus.Failed, audioPath = wav, tone = Tone.Formal, level = CleanupLevel.Medium))
        }
        val ok = runBlocking { orchestrator.retry(id) }
        assertTrue(ok)
        assertEquals(listOf("RECOVERED TEXT."), clipboard.texts.toList())
        val record = history.records.single()
        assertEquals(RecordStatus.CopiedOnly, record.status)
        assertEquals("Recovered text.", record.rawTranscript)
        assertEquals(Tone.Formal, post.requests.single().tone)
        assertTrue(notifier.toasts.any { it.first == "Retry complete" })
    }
}
