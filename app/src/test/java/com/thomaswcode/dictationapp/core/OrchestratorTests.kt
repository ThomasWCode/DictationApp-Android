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

    /** A frame from the microphone; like a real one, nothing arrives once it is stopped. */
    fun emit(fill: Byte, peak: Float = 0.4f) {
        if (running) onFrame?.invoke(AudioFrame(ByteArray(AudioFrame.FRAME_BYTES) { fill }, peak, 0))
    }
}

class FakeSink(override val path: String, private val probe: (() -> Int)? = null) : AudioSink {
    @Volatile override var bytesWritten = 0L

    @Volatile var completed = false

    /** What the probe said when the recording was finished (the number of saved records, say). */
    @Volatile var probeAtComplete: Int? = null

    @Volatile var discarded = false

    override fun write(pcm16: ByteArray) {
        bytesWritten += pcm16.size
    }

    override fun complete() {
        completed = true
        probeAtComplete = probe?.invoke()
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

    /** The billed session length the Termination summary reports (0: not reported). */
    @Volatile var sessionSeconds = 0.0

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

        return if (sendTermination) TerminationMessage(audioDurationSeconds = 3.0, sessionDurationSeconds = sessionSeconds) else null
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

    /** Inserted, but not sure it landed as intended, so also left on the clipboard. */
    var leftOnClipboard = false

    override suspend fun insert(text: String, target: ForegroundContext, method: InsertMethod): InsertionResult {
        inserted += text to method
        return InsertionResult(outcome, if (outcome == InsertionOutcome.Inserted) null else "nope", text, leftOnClipboard)
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
    fun cancelIsIgnoredOnceTheDictationStoppedRecording() {
        // A discard is only possible while recording; after the release the dictation always finishes.
        val m = DictationStateMachine()
        m.fire(DictationTrigger.Press)
        m.fire(DictationTrigger.BeginReceived)
        m.fire(DictationTrigger.Release)
        assertEquals(DictationState.Finalising, m.fire(DictationTrigger.Cancel))
        m.fire(DictationTrigger.HandshakeComplete)
        assertEquals(DictationState.PostProcessing, m.fire(DictationTrigger.Cancel))
        m.fire(DictationTrigger.PostProcessed)
        assertEquals(DictationState.Inserting, m.fire(DictationTrigger.Cancel))
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
    @Volatile private var replaySpeed: Double? = null
    private var sinkProbe: (() -> Int)? = null
    private var lookupGate: CompletableDeferred<Unit>? = null
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

                override fun createWavReplay(path: String, speed: Double): AudioCapture {
                    replaySpeed = speed
                    return if (replayPath != null) WavReplayAudioCapture(path, 50.0) else capture
                }
            },
            sinks = AudioSinkFactory { path -> FakeSink(path, sinkProbe).also { sinks += it } },
            transcribers = StreamingTranscriberFactory { transcriberFactory().also { transcribers += it } },
            foreground = ForegroundContextProvider {
                lookupGate?.await()
                context
            },
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
        assertTrue(notifier.toasts.any { it.first == "Copied to clipboard" && it.second.contains("password") })
    }

    @Test
    fun anInsertionThatFailsIsCopiedAndReported() {
        inserter.outcome = InsertionOutcome.Failed
        start()
        dictate("Keep me.")
        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals("nope", record.failureReason)
        assertEquals(listOf("KEEP ME."), clipboard.texts.toList())
        assertTrue(notifier.toasts.any { it.first == "Could not insert" && it.second.endsWith("Text copied to the clipboard.") })
    }

    @Test
    fun anInsertionThatMayNotHaveLandedAsIntendedSaysItWasAlsoCopied() {
        inserter.leftOnClipboard = true
        start()
        dictate("Maybe.")
        assertEquals(RecordStatus.Inserted, history.records.single().status)
        assertEquals("also copied", hub.current.badge)
        assertTrue(notifier.toasts.isEmpty())
    }

    @Test
    fun historyInsertGoesThroughTheSameDeliveryWithTheDestinationRule() {
        settings.update { it.copy(appRules = listOf(AppRule(packageGlob = "com.google.android.gm", insertMethod = InsertMethod.Paste))) }
        context = editable.copy(packageName = "com.google.android.gm", appLabel = "Gmail")
        start()

        // The stored text's joining space belonged to the field it first went into.
        val result = runBlocking { orchestrator.reinsert(" Stored words.") }
        assertEquals(InsertionOutcome.Inserted, result.outcome)
        assertEquals(listOf("Stored words." to InsertMethod.Paste), inserter.inserted.toList())
        assertTrue(history.records.isEmpty())

        context = editable.copy(isEditable = false, editableReason = "no-input-focus")
        assertEquals(InsertionOutcome.CopiedOnly, runBlocking { orchestrator.reinsert(" Stored words.") }.outcome)
        assertEquals(listOf("Stored words."), clipboard.texts.toList())
        assertTrue(notifier.toasts.any { it.first == "Copied to clipboard" })
    }

    @Test
    fun historyInsertWaitsForTheDictationToFinish() {
        start()
        orchestrator.press()
        waitForArmed()
        assertEquals(InsertionOutcome.Failed, runBlocking { orchestrator.reinsert("Stored words.") }.outcome)
        assertTrue(inserter.inserted.isEmpty())
        assertTrue(notifier.toasts.any { it.second == "Finish the current dictation first." })
        orchestrator.cancel()
        waitForIdleSession()
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
    fun releaseBeforeBeginStopsTheMicrophoneAtOnce() {
        // Nothing said after the release is recorded or uploaded, even while the connection is still being set up.
        transcriberFactory = { FakeTranscriber(autoTurnText = "Pre-release words.") }
        start()
        orchestrator.press()
        waitForArmed()
        capture.emit(1)
        orchestrator.release()
        waitFor("microphone stopped at the release") { !capture.running }
        assertFalse(transcriber.isConnected)
        capture.emit(2) // a real microphone delivers nothing after stop
        transcriber.completeConnect()
        waitForIdleSession()

        assertEquals(1, transcriber.frames.get())
        assertEquals(AudioFrame.FRAME_BYTES.toLong(), sink.bytesWritten)
        assertEquals("PRE-RELEASE WORDS.", inserter.inserted.single().first)
    }

    @Test
    fun theMicrophoneStartsBeforeTheFieldLookup() {
        lookupGate = CompletableDeferred()
        start()
        orchestrator.press()
        waitFor("microphone started while the lookup is still running") { capture.running }
        waitFor("transcriber created") { transcribers.isNotEmpty() }
        lookupGate!!.complete(Unit)
        orchestrator.cancel()
        waitForIdleSession()
    }

    @Test
    fun aDiscardWinsOverAReleaseThatArrivedWithIt() {
        lookupGate = CompletableDeferred()
        start()
        orchestrator.press()
        waitFor("microphone started") { capture.running }
        orchestrator.cancel()
        orchestrator.release()
        Thread.sleep(50)
        lookupGate!!.complete(Unit)
        waitForIdleSession()

        assertTrue(inserter.inserted.isEmpty())
        assertTrue(history.records.isEmpty())
        assertTrue(transcriber.aborted)
    }

    @Test
    fun cancelAfterTheCheckMarkIsIgnoredAndTheDictationFinishes() {
        // A discard is only possible while recording: after ✓ the dictation always finishes.
        post.gate = CompletableDeferred()
        start()
        orchestrator.press()
        orchestrator.enterHandsFree()
        waitForArmed()
        capture.emit(1)
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        transcriber.raiseTurn(0, "Keep me.", endOfTurn = true, formatted = true)
        orchestrator.release()
        waitFor("hub shows the dictation finishing") { hub.current.state == DictationState.PostProcessing }
        orchestrator.cancel()
        Thread.sleep(50)
        post.gate!!.complete(Unit)
        waitForIdleSession()

        assertEquals("KEEP ME.", inserter.inserted.single().first)
        assertEquals(RecordStatus.Inserted, history.records.single().status)
    }

    @Test
    fun serviceTeardownWhileRecordingKeepsTheDictationForRetry() {
        start()
        orchestrator.press()
        waitForArmed()
        capture.emit(1)
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        transcriber.raiseTurn(0, "half a thought", endOfTurn = false)
        orchestrator.interrupt()
        waitForIdleSession()

        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals(DictationOrchestrator.INTERRUPTED_REASON, record.failureReason)
        assertEquals("half a thought", record.rawTranscript)
        assertTrue(record.audioPath != null && sink.completed)
        assertTrue(inserter.inserted.isEmpty())
        assertTrue(transcriber.aborted)
    }

    @Test
    fun serviceTeardownAfterTheReleaseLetsTheDictationFinish() {
        post.gate = CompletableDeferred()
        start()
        orchestrator.press()
        waitForArmed()
        capture.emit(1)
        transcriber.completeConnect()
        waitForState(DictationState.Recording)
        transcriber.raiseTurn(0, "Finish me.", endOfTurn = true, formatted = true)
        orchestrator.release()
        waitFor("cleaning up") { hub.current.state == DictationState.PostProcessing }
        orchestrator.interrupt()
        post.gate!!.complete(Unit)
        waitForIdleSession()

        assertEquals(RecordStatus.Inserted, history.records.single().status)
    }

    @Test
    fun theRecordingIsFinishedOnlyAfterItsRecordIsSaved() {
        // Until then its header keeps the "unfinished" marker, so a crash during cleanup or insertion leaves a file the
        // next start recovers into History instead of an orphan the sweep deletes.
        sinkProbe = { history.records.size }
        start()
        dictate("Saved first.")
        assertEquals(1, sink.probeAtComplete)
    }

    @Test
    fun anErrorInTheSessionStillKeepsTheDictation() {
        // An Error escaped the old catch and lost the dictation (the ICU regex crash): it is caught and saved now.
        post.respond = { throw ExceptionInInitializerError("boom") }
        start()
        dictate("Kept anyway.")

        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertTrue(record.audioPath != null)
    }

    @Test
    fun aBusyMicrophoneIsNotReportedAsBlocked() {
        start()
        orchestrator.press()
        waitForArmed()
        capture.onFault?.invoke(com.thomaswcode.dictationapp.core.audio.MicrophoneUnavailableException("The microphone did not start; another app may be using it."))
        waitForIdleSession()

        assertTrue(notifier.toasts.any { it.first == "Microphone unavailable" })
        assertFalse(notifier.micRequested)
    }

    @Test
    fun costIsEstimatedFromTheBilledSession() {
        transcriberFactory = { FakeTranscriber().also { it.sessionSeconds = 4.0 } }
        start()
        dictate("Billed.")
        // The fake session reports 3 s of audio in a 4 s session: AssemblyAI bills the session.
        assertEquals(com.thomaswcode.dictationapp.core.history.CostEstimator.sttCost(settings.current.speechModel, 4.0), history.records.single().costEstimate!!, 1e-9)
    }

    @Test
    fun aRecurringCleanupProblemIsReportedOncePerRun() {
        post.respond = { raw -> PostProcessResult(raw.uppercase(), true, "b", null, configurationProblem = "The Groq key may not use a.") }
        start()
        dictate("One.")
        dictate("Two.")
        assertEquals(1, notifier.toasts.count { it.second == "The Groq key may not use a." })
    }

    @Test
    fun retryThatEndsWithoutASummaryKeepsTheEarlierWords() {
        val wav = File(tmp.root, "failed.wav").path
        WavFileSink(wav).apply {
            write(ByteArray(AudioFrame.FRAME_BYTES * 4) { 3 })
            complete()
        }
        replayPath = wav
        transcriberFactory = { FakeTranscriber(autoConnect = true, autoTurnText = "Recov").also { it.sendTermination = false } }
        start()
        val id = runBlocking {
            history.insert(DictationRecord(createdAt = 1, updatedAt = 1, status = RecordStatus.Failed, audioPath = wav, rawTranscript = "the earlier, longer words"))
        }
        val ok = runBlocking { orchestrator.retry(id) }

        assertFalse(ok)
        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals("the earlier, longer words", record.rawTranscript)
        assertTrue(record.failureReason!!.contains("did not finish"))
        assertTrue(clipboard.texts.isEmpty())
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
        // Paced at real time: AssemblyAI cannot keep up with a faster stream.
        assertEquals(DictationOrchestrator.RETRY_REPLAY_SPEED, replaySpeed!!, 0.0)
        assertEquals(1.0, DictationOrchestrator.RETRY_REPLAY_SPEED, 0.0)
    }
}
