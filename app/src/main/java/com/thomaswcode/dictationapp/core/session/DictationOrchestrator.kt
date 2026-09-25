package com.thomaswcode.dictationapp.core.session

import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.audio.AudioCapture
import com.thomaswcode.dictationapp.core.audio.AudioCaptureFactory
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.AudioSink
import com.thomaswcode.dictationapp.core.audio.AudioSinkFactory
import com.thomaswcode.dictationapp.core.audio.MicrophoneAccessDeniedException
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.PostProcessRequest
import com.thomaswcode.dictationapp.core.cleanup.PostProcessorRouter
import com.thomaswcode.dictationapp.core.cleanup.TextPostProcessor
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.dictionary.KeytermsSelector
import com.thomaswcode.dictationapp.core.history.CostEstimator
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.history.HistoryRepository
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
import com.thomaswcode.dictationapp.core.rules.AppRulesResolver
import com.thomaswcode.dictationapp.core.rules.ResolvedRule
import com.thomaswcode.dictationapp.core.settings.AppPaths
import com.thomaswcode.dictationapp.core.settings.AppSettings
import com.thomaswcode.dictationapp.core.settings.SettingsStore
import com.thomaswcode.dictationapp.core.transcription.BeginMessage
import com.thomaswcode.dictationapp.core.transcription.SessionOptions
import com.thomaswcode.dictationapp.core.transcription.StreamingTranscriber
import com.thomaswcode.dictationapp.core.transcription.StreamingTranscriberFactory
import com.thomaswcode.dictationapp.core.transcription.TerminationMessage
import com.thomaswcode.dictationapp.core.transcription.TranscriptAssembler
import com.thomaswcode.dictationapp.core.transcription.TurnMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the dictation state machine and runs one session at a time. Bubble events arrive on the main
 * thread and are funnelled through a single-reader channel so every decision is made in order. Audio
 * frames flow through a per-session channel to the socket sender, buffering until the server's Begin.
 */
class DictationOrchestrator(
    private val captures: AudioCaptureFactory,
    private val sinks: AudioSinkFactory,
    private val transcribers: StreamingTranscriberFactory,
    private val foreground: ForegroundContextProvider,
    private val inserter: TextInserter,
    private val clipboard: Clipboard,
    private val notifier: Notifier,
    private val postProcessor: TextPostProcessor,
    private val history: HistoryRepository,
    private val settings: SettingsStore,
    private val hub: StatusHub,
    private val paths: AppPaths,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hasMicrophonePermission: () -> Boolean = { true },
) {
    val machine = DictationStateMachine()
    private val events = Channel<ControlEvent>(Channel.UNLIMITED)
    private val ownerLock = Any()
    private val replayGate = Mutex()
    private lateinit var scope: CoroutineScope

    @Volatile private var session: DictationSession? = null

    @Volatile private var sessionJob: Job? = null

    private var badgeSeq = 0

    init {
        machine.onPressIgnored = { hub.flash() }
        machine.onTransition = { from, trigger, to -> logger.debug("State $from --$trigger--> $to") }
    }

    val state: DictationState get() = machine.state

    /** The job running the current session, if any (tests use it to wait for completion). */
    val currentSessionJob: Job? get() = sessionJob

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        publishIdle(null)
        scope.launch {
            // Editing the dictionary during a dictation pushes the new list mid-session.
            settings.flow.map { KeytermsSelector.select(it.dictionary) }.distinctUntilChanged().drop(1).collect { terms ->
                val s = session
                val t = s?.transcriber
                if (s != null && machine.state == DictationState.Recording && t != null && t.isConnected) {
                    runCatching { t.updateConfiguration(terms, null) }
                    logger.info("Pushed ${terms.size} keyterms mid-session")
                }
            }
        }
        return scope.launch {
            try {
                history.initialise()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.error("History database could not be initialised; dictations will not be recorded", e)
            }

            for (ev in events) {
                try {
                    dispatch(ev)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.error("Unhandled error dispatching $ev", e)
                }
            }
        }
    }

    /** Finger down on the bubble (confirmed as a press, not a drag), or a tap. */
    fun press() = events.trySend(ControlEvent.Press(null))

    /** Hold released, or the check mark tapped in hands-free mode: finish and insert. */
    fun release() = events.trySend(ControlEvent.Release)

    /** The press was a quick tap: keep dictating with nothing held until [release] or [cancel]. */
    fun enterHandsFree() = events.trySend(ControlEvent.HandsFree)

    /** Discard the dictation. [silent] skips the "Discarded" badge (used when a press turns into a drag). */
    fun cancel(silent: Boolean = false) = events.trySend(ControlEvent.Cancel(silent))

    /**
     * Runs a full dictation (context capture, streaming, cleanup, insertion) with a WAV file standing in for
     * the microphone. The session releases itself when the file ends. Debug hook, like --simulate on Windows.
     */
    fun simulateDictationFromWav(wavPath: String) = events.trySend(ControlEvent.Press(wavPath))

    private fun dispatch(ev: ControlEvent) {
        when (ev) {
            is ControlEvent.Press -> synchronized(ownerLock) {
                if (machine.isIdle && session == null) {
                    machine.fire(DictationTrigger.Press)
                    val s = DictationSession(clock(), ev.wavPath)
                    val previous = sessionJob
                    session = s
                    sessionJob = scope.launch {
                        // A new press can arrive while the previous session is still releasing the microphone
                        // and socket. Chain behind it instead of refusing.
                        if (previous != null && !previous.isCompleted) {
                            withTimeoutOrNull(3_000) { previous.join() }
                        }

                        runSession(s)
                    }
                } else {
                    machine.fire(DictationTrigger.Press) // ignored + flash
                }
            }
            ControlEvent.Release -> session?.release()
            ControlEvent.HandsFree -> session?.let { s ->
                s.handsFree = true
                hub.update { it.copy(handsFree = true) }
            }
            is ControlEvent.Cancel -> session?.cancel(ev.silent)
        }
    }

    private suspend fun runSession(s: DictationSession) {
        val settings = settings.current
        var capture: AudioCapture? = null
        var sink: AudioSink? = null
        var transcriber: StreamingTranscriber? = null
        val jobs = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        val record = DictationRecord(createdAt = s.startedAt, updatedAt = s.startedAt, status = RecordStatus.Pending)
        try {
            // 1. Capture where the user is before anything else moves.
            s.context = safeCapture()
            s.rule = AppRulesResolver.resolve(s.context, settings.appRules, settings.defaultTone, settings.defaultCleanupLevel, settings.insertMethod)
            s.tone = s.rule.tone
            s.level = s.rule.level
            record.packageName = s.context.packageName.ifEmpty { null }
            record.appLabel = s.context.appLabel.ifEmpty { null }
            record.windowTitle = s.context.windowTitle.ifEmpty { null }
            record.url = s.context.url
            logger.info("Dictation started in ${s.context.packageName} (${s.context.windowTitle}) rule=${s.rule.matchedBy} editable=${s.context.isEditable}/${s.context.editableReason}")
            publishSession(s, DictationState.Arming, "Listening")

            // 2. Microphone + WAV sink. Frames buffer in the channel until the socket is ready.
            if (s.simulatedWavPath == null && !hasMicrophonePermission()) {
                throw MicrophoneAccessDeniedException("Microphone permission has not been granted.")
            }

            if (settings.storeAudio) {
                sink = sinks.create(paths.newAudioPath(s.startedAt))
            }

            val activeSink = sink
            capture = if (s.simulatedWavPath == null) captures.createMicrophone(settings.microphoneDevice) else captures.createWavReplay(s.simulatedWavPath, 1.0)
            if (s.simulatedWavPath != null) capture.onCompleted = { s.release() }
            capture.onFrame = { frame ->
                activeSink?.write(frame.pcm16)
                s.frames.trySend(frame)
                s.onFrame(frame)
                hub.update { it.copy(level = frame.peak) }
            }
            capture.onFault = { s.fault(it) }
            capture.start()

            // 3. Socket.
            val t = transcribers.create()
            transcriber = t
            s.transcriber = t
            t.onTurn = { turn ->
                if (s.assembler.ingest(turn)) hub.update { it.copy(liveText = s.assembler.liveText) }
            }
            t.onFault = { s.fault(it) }
            val connect = CompletableDeferred<BeginMessage>()
            jobs.launch {
                try {
                    connect.complete(withTimeout(CONNECT_TIMEOUT_MS) { t.connect(buildSessionOptions(settings)) })
                } catch (e: TimeoutCancellationException) {
                    connect.completeExceptionally(TimeoutException("Connection timed out"))
                } catch (e: Throwable) {
                    connect.completeExceptionally(e)
                }
            }
            jobs.launch { watchForSilentMicrophone(s) }

            val first = select<Outcome> {
                connect.onJoin { Outcome.Connected }
                s.released.onJoin { Outcome.Released }
                s.cancelled.onJoin { Outcome.Cancelled }
                s.faulted.onJoin { Outcome.Faulted }
            }

            when (first) {
                Outcome.Cancelled -> return discard(s, capture, sink, t, if (s.cancelled.await()) null else "Discarded")
                Outcome.Faulted -> return fail(s, record, capture, sink, t, s.faulted.await())
                Outcome.Released -> {
                    // Released before Begin: give the connection its remaining budget, then flush the buffer.
                    machine.fire(DictationTrigger.Release)
                    publishSession(s, DictationState.Finalising, "Connecting")
                    try {
                        connect.await()
                    } catch (e: Exception) {
                        if (e is CancellationException && !connect.isCompleted) throw e
                        return fail(s, record, capture, sink, t, e)
                    }

                    capture.stop()
                    s.frames.close()
                    sendFrames(s, t)
                }
                else -> {
                    try {
                        connect.await()
                    } catch (e: Exception) {
                        if (e is CancellationException && !connect.isCompleted) throw e
                        machine.fire(if (e is TimeoutException) DictationTrigger.ConnectTimeout else DictationTrigger.SocketFault)
                        return fail(s, record, capture, sink, t, e)
                    }

                    machine.fire(DictationTrigger.BeginReceived)
                    logger.info("Connect latency ${t.connectLatencyMs ?: -1} ms")
                    publishSession(s, DictationState.Recording, null)
                    val sender = jobs.launch { sendFrames(s, t) }
                    val cap = CompletableDeferred<Unit>()
                    val capJob = jobs.launch {
                        delay(settings.maxDictationMs)
                        cap.complete(Unit)
                    }
                    val end = select<Outcome> {
                        s.released.onJoin { Outcome.Released }
                        s.cancelled.onJoin { Outcome.Cancelled }
                        s.faulted.onJoin { Outcome.Faulted }
                        cap.onJoin { Outcome.Cap }
                    }
                    capJob.cancel()
                    when (end) {
                        Outcome.Cancelled -> return discard(s, capture, sink, t, if (s.cancelled.await()) null else "Discarded")
                        Outcome.Faulted -> {
                            machine.fire(DictationTrigger.SocketFault)
                            return fail(s, record, capture, sink, t, s.faulted.await())
                        }
                        Outcome.Cap -> {
                            machine.fire(DictationTrigger.CapReached)
                            notifier.toast("Dictation limit", "Stopped after ${settings.maxDictationMinutes} minutes.", ToastKind.Info)
                        }
                        else -> machine.fire(DictationTrigger.Release)
                    }

                    capture.stop()
                    s.frames.close()
                    if (withTimeoutOrNull(TAIL_DRAIN_MS) { sender.join() } == null) {
                        logger.warn("Audio tail did not drain within budget")
                        sender.cancel()
                    }
                }
            }

            // 4. Finalising: graceful handshake bounded by the hard cap.
            publishSession(s, DictationState.Finalising, "Finishing")
            val termination = t.shutdown(HANDSHAKE_CAP_MS)
            // A socket fault after the release is no longer being selected on; without a Termination summary the
            // transcript may be missing its tail, so save it as Failed (audio kept for Retry) rather than insert it.
            if (termination == null && s.faulted.isCompleted) return fail(s, record, capture, sink, t, s.faulted.await())
            val audioSeconds = termination?.audioDurationSeconds ?: (s.audioDurationMs / 1000.0)
            record.durationMs = s.audioDurationMs.toInt()
            record.costEstimate = CostEstimator.sttCost(settings.speechModel, audioSeconds)
            val rawText = s.assembler.finalText
            record.rawTranscript = rawText
            sink?.let {
                it.complete()
                record.audioPath = it.path
            }

            if (rawText.isBlank()) {
                sink?.discard()
                record.audioPath = null
                logger.info("Nothing heard")
                finish(s, DictationTrigger.NothingHeard, "Nothing heard")
                return
            }

            machine.fire(DictationTrigger.HandshakeComplete)

            // 5. Post-processing.
            publishSession(s, DictationState.PostProcessing, "Cleaning up")
            record.tone = s.tone
            record.level = s.level
            val keyterms = KeytermsSelector.select(settings.dictionary)
            val request = PostProcessRequest(s.level, s.tone, keyterms, s.context.appName, s.context.url, s.rule.hint)
            val result = postProcessor.process(rawText, request)
            record.cleanedText = if (result.applied) result.text else ""
            record.llmModel = result.model
            record.failureReason = if (result.applied) null else result.failureReason
            record.costEstimate = (record.costEstimate ?: 0.0) + CostEstimator.llmCost(result.model, result.promptTokens, result.completionTokens)
            val badge = if (result.applied || !PostProcessorRouter.needsLlm(s.level, s.tone)) null else "cleanup skipped"
            machine.fire(DictationTrigger.PostProcessed)

            // 6. Inserting. Re-check the focused field; if focus moved to another field, insert there but keep
            // the tone chosen at the start. If focus went nowhere, the original field is tried.
            publishSession(s, DictationState.Inserting, "Inserting")
            val current = safeCapture()
            val target = if (current.isEditable) current else s.context
            // Tone and cleanup stay as chosen at the start, but how the text goes in depends on where it lands:
            // finishing in Gmail after starting in a plain editor must use Gmail's rule (e.g. Paste).
            val insertMethod = if (target.packageName != s.context.packageName || target.urlHost != s.context.urlHost) {
                logger.info("Focus moved during dictation: ${s.context.packageName} -> ${target.packageName}")
                insertMethodFor(target, settings)
            } else {
                s.rule.insertMethod
            }

            val insertion = if (target.isEditable && !target.isPassword) {
                inserter.insert(result.text, target, insertMethod)
            } else {
                clipboard.setText(result.text)
                InsertionResult(InsertionOutcome.CopiedOnly, "No text field has focus, text copied", result.text)
            }

            record.insertedText = insertion.insertedText ?: result.text
            val trigger = when (insertion.outcome) {
                InsertionOutcome.Inserted -> {
                    record.status = RecordStatus.Inserted
                    DictationTrigger.Inserted
                }
                InsertionOutcome.CopiedOnly -> {
                    record.status = RecordStatus.CopiedOnly
                    notifier.toast("Copied to clipboard", insertion.reason ?: "Paste it where you need it.", ToastKind.Info)
                    DictationTrigger.CopiedOnly
                }
                InsertionOutcome.Failed -> {
                    record.status = RecordStatus.Failed
                    record.failureReason = insertion.reason
                    clipboard.setText(record.insertedText)
                    notifier.toast("Could not insert", (insertion.reason ?: "Unknown error") + ". Text copied to the clipboard.", ToastKind.Warning)
                    DictationTrigger.Failed
                }
            }

            record.updatedAt = clock()
            saveRecord(record)
            bumpDictionaryUsage(keyterms, rawText)
            logger.info("Dictation ${record.status}: ${countWords(record.insertedText)} words, ${"%.1f".format(audioSeconds)}s audio, llm=${result.model ?: "none"}")
            finish(s, trigger, badge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Dictation session crashed in state ${machine.state}", e)
            withContext(NonCancellable) { fail(s, record, capture, sink, transcriber, e) }
        } finally {
            jobs.cancel()
            runCatching { capture?.close() }
            runCatching { sink?.close() }
            runCatching { transcriber?.abort() }
            finish(s, DictationTrigger.Failed, null)
        }
    }

    /** Hands the orchestrator back to Idle, but only if [s] still owns it (a new press may already have started). */
    private fun finish(s: DictationSession, trigger: DictationTrigger, badge: String?) = synchronized(ownerLock) {
        if (session !== s) return
        if (!machine.isIdle) machine.fire(trigger)
        if (!machine.isIdle) machine.fire(DictationTrigger.Failed)
        session = null
        publishIdle(badge)
    }

    private fun discard(s: DictationSession, capture: AudioCapture?, sink: AudioSink?, transcriber: StreamingTranscriber?, badge: String?) {
        capture?.stop()
        s.frames.close()
        sink?.discard()
        transcriber?.abort()
        finish(s, DictationTrigger.Cancel, badge)
    }

    private suspend fun fail(s: DictationSession, record: DictationRecord, capture: AudioCapture?, sink: AudioSink?, transcriber: StreamingTranscriber?, error: Throwable) {
        logger.warn("Dictation failed: ${error.message}", error)
        capture?.stop()
        s.frames.close()
        transcriber?.abort()
        if (sink != null && sink.bytesWritten > 0) {
            sink.complete()
            record.audioPath = sink.path
        } else {
            sink?.discard()
        }

        val message = error.message ?: error.javaClass.simpleName
        record.status = RecordStatus.Failed
        record.failureReason = message
        record.rawTranscript = s.assembler.finalText
        record.tone = s.tone
        record.level = s.level
        record.durationMs = s.audioDurationMs.toInt()
        record.updatedAt = clock()
        val isNetwork = error is IOException || error is TimeoutException || error.cause is IOException
        when {
            error is MicrophoneAccessDeniedException -> {
                notifier.toast("Microphone blocked", "Allow microphone access for DictationApp.", ToastKind.Error)
                notifier.requestMicrophonePermission()
            }
            record.audioPath != null -> {
                saveRecord(record)
                if (isNetwork) {
                    notifier.toast("Network error", "Saved to history. Use Retry when you are back online.", ToastKind.Warning)
                } else {
                    notifier.toast("Dictation failed", "$message The recording is saved in History.", ToastKind.Warning)
                }
            }
            // No audio ("Store audio" off, or nothing recorded) but some words were heard: keep them in History,
            // where they can still be copied. Retry needs audio, so it stays unavailable for this record.
            record.rawTranscript.isNotBlank() -> {
                saveRecord(record)
                notifier.toast("Dictation failed", "$message What was heard so far is saved in History.", ToastKind.Warning)
            }
            else -> notifier.toast("Dictation failed", message, ToastKind.Error)
        }

        finish(s, DictationTrigger.Failed, "Failed")
    }

    private suspend fun saveRecord(record: DictationRecord) {
        try {
            if (record.id == 0L) record.id = history.insert(record) else history.update(record)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Could not write history record", e)
        }
    }

    private fun bumpDictionaryUsage(keyterms: List<String>, text: String) {
        if (keyterms.isEmpty() || text.isEmpty()) return
        val used = keyterms.filter { text.contains(it, ignoreCase = true) }.map { it.lowercase() }.toSet()
        if (used.isEmpty()) return
        val now = clock()
        runCatching {
            settings.update { s ->
                s.copy(dictionary = s.dictionary.map { t -> if (t.term.trim().lowercase() in used) t.copy(useCount = t.useCount + 1, lastUsedAt = now) else t })
            }
        }.onFailure { logger.debug("Could not update dictionary usage: ${it.message}") }
    }

    private suspend fun watchForSilentMicrophone(s: DictationSession) {
        delay(SILENT_MIC_WARNING_MS)
        val st = machine.state
        if ((st == DictationState.Arming || st == DictationState.Recording) && session === s && s.frameCount > 10 && s.maxPeak < 1e-4f) {
            logger.warn("Microphone delivered ${s.frameCount} silent frames")
            notifier.toast("No sound from microphone", "Another app may be using the microphone, or access is blocked.", ToastKind.Warning)
        }
    }

    private suspend fun sendFrames(s: DictationSession, t: StreamingTranscriber) {
        for (frame in s.frames) t.sendAudio(frame.pcm16)
    }

    private fun buildSessionOptions(settings: AppSettings) = SessionOptions(
        speechModel = settings.speechModel,
        keyterms = KeytermsSelector.select(settings.dictionary),
        languageCodes = settings.languageCodes?.takeIf { it.isNotBlank() },
    )

    private suspend fun safeCapture(): ForegroundContext = try {
        foreground.capture()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logger.warn("Focus capture failed; treating as no text field", e)
        ForegroundContext.Unknown
    }

    private fun publishSession(s: DictationSession, state: DictationState, badge: String?) {
        if (session !== s) return
        hub.publish(
            DictationStatus(
                state = state,
                liveText = s.assembler.liveText,
                level = s.lastPeak,
                tone = s.tone,
                cleanupLevel = s.level,
                badge = badge,
                badgeSeq = badgeSeq,
                startedAt = s.startedAt,
                appName = s.context.appName,
                handsFree = s.handsFree,
            ),
        )
    }

    private fun publishIdle(badge: String?) {
        val st = settings.current
        if (badge != null) badgeSeq++
        hub.publish(DictationStatus(tone = st.defaultTone, cleanupLevel = st.defaultCleanupLevel, badge = badge, badgeSeq = badgeSeq))
    }

    /**
     * Re-streams a stored WAV through the transcriber (4x real time), cleans it up and copies the result to
     * the clipboard. Used by Retry in History for failed and pending records.
     */
    suspend fun retry(recordId: Long): Boolean {
        val record = history.get(recordId)
        val audio = record?.audioPath
        if (record == null || audio.isNullOrEmpty() || !File(audio).exists()) {
            notifier.toast("Retry", "No audio is stored for this dictation.", ToastKind.Warning)
            return false
        }

        if (!machine.isIdle) {
            notifier.toast("Retry", "Finish the current dictation first.", ToastKind.Warning)
            return false
        }

        return try {
            val settings = settings.current
            val transcript = transcribeWav(audio, 4.0, null)
            if (transcript.text.isBlank()) {
                record.failureReason = "retry: nothing heard"
                history.update(record)
                notifier.toast("Retry", "Nothing was heard in the recording.", ToastKind.Warning)
                return false
            }

            val request = PostProcessRequest(record.level, record.tone, KeytermsSelector.select(settings.dictionary), record.appName, record.url, null)
            val result = postProcessor.process(transcript.text, request)
            clipboard.setText(result.text)
            record.rawTranscript = transcript.text
            record.cleanedText = if (result.applied) result.text else ""
            record.insertedText = result.text
            record.llmModel = result.model
            record.status = RecordStatus.CopiedOnly
            record.failureReason = if (result.applied) null else result.failureReason
            record.updatedAt = clock()
            record.costEstimate = (record.costEstimate ?: 0.0) + CostEstimator.sttCost(settings.speechModel, transcript.audioDurationMs / 1000.0) +
                CostEstimator.llmCost(result.model, result.promptTokens, result.completionTokens)
            history.update(record)
            notifier.toast("Retry complete", "Text copied to the clipboard.", ToastKind.Success)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Retry of record $recordId failed", e)
            record.failureReason = "retry: ${e.message}"
            runCatching { history.update(record) }
            notifier.toast("Retry failed", e.message ?: e.javaClass.simpleName, ToastKind.Error)
            false
        }
    }

    /** Streams a WAV file through a fresh transcriber session. Used by Retry and the debug stream test. */
    suspend fun transcribeWav(wavPath: String, speed: Double, onTurn: ((TurnMessage) -> Unit)?): WavTranscription = replayGate.withLock {
        val settings = settings.current
        val assembler = TranscriptAssembler()
        val capture = captures.createWavReplay(wavPath, speed)
        val transcriber = transcribers.create()
        val frames = Channel<AudioFrame>(Channel.UNLIMITED)
        val done = CompletableDeferred<Unit>()
        val bytes = AtomicLong()
        capture.onFrame = { f ->
            bytes.addAndGet(f.pcm16.size.toLong())
            frames.trySend(f)
        }
        capture.onCompleted = {
            frames.close()
            done.complete(Unit)
        }
        capture.onFault = { e ->
            frames.close(e)
            done.completeExceptionally(e)
        }
        transcriber.onTurn = { t ->
            assembler.ingest(t)
            onTurn?.invoke(t)
        }
        transcriber.onFault = { e -> done.completeExceptionally(e) }
        try {
            val started = clock()
            withTimeout(CONNECT_TIMEOUT_MS + 2_000) { transcriber.connect(buildSessionOptions(settings)) }
            capture.start()
            coroutineScope {
                val sender = launch { for (frame in frames) transcriber.sendAudio(frame.pcm16) }
                done.await()
                sender.join()
            }

            val termination = transcriber.shutdown(HANDSHAKE_CAP_MS)
            WavTranscription(assembler.finalText, bytes.get() * 1000 / (AudioFrame.SAMPLE_RATE * 2), transcriber.connectLatencyMs, termination, clock() - started)
        } finally {
            capture.close()
            transcriber.abort()
        }
    }

    private enum class Outcome { Connected, Released, Cancelled, Faulted, Cap }

    private sealed interface ControlEvent {
        data class Press(val wavPath: String?) : ControlEvent

        data object Release : ControlEvent

        data object HandsFree : ControlEvent

        data class Cancel(val silent: Boolean) : ControlEvent
    }

    /** Mutable per-dictation state shared between the control loop and the session job. */
    private class DictationSession(val startedAt: Long, val simulatedWavPath: String?) {
        val released = CompletableDeferred<Unit>()

        /** Completes with true for a silent cancel (no badge). */
        val cancelled = CompletableDeferred<Boolean>()
        val faulted = CompletableDeferred<Throwable>()
        val frames = Channel<AudioFrame>(Channel.UNLIMITED)
        val assembler = TranscriptAssembler()
        private val bytes = AtomicLong()

        @Volatile var context: ForegroundContext = ForegroundContext.Unknown

        @Volatile var rule = ResolvedRule(Tone.Neutral, CleanupLevel.Light, InsertMethod.Direct, null, "default")

        @Volatile var tone: Tone = Tone.Neutral

        @Volatile var level: CleanupLevel = CleanupLevel.Light

        @Volatile var handsFree = false

        @Volatile var transcriber: StreamingTranscriber? = null

        @Volatile var lastPeak = 0f

        @Volatile var maxPeak = 0f

        @Volatile var frameCount = 0

        val audioDurationMs: Long get() = bytes.get() * 1000 / (AudioFrame.SAMPLE_RATE * 2)

        fun release() = released.complete(Unit)

        fun cancel(silent: Boolean) = cancelled.complete(silent)

        fun fault(e: Throwable) = faulted.complete(e)

        fun onFrame(frame: AudioFrame) {
            bytes.addAndGet(frame.pcm16.size.toLong())
            lastPeak = frame.peak
            if (frame.peak > maxPeak) maxPeak = frame.peak
            frameCount++
        }
    }

    companion object {
        /** The insertion method the app rules give [target] (used when text lands somewhere other than where it started). */
        fun insertMethodFor(target: ForegroundContext, settings: AppSettings): InsertMethod =
            AppRulesResolver.resolve(target, settings.appRules, settings.defaultTone, settings.defaultCleanupLevel, settings.insertMethod).insertMethod

        const val CONNECT_TIMEOUT_MS = 3_000L
        const val HANDSHAKE_CAP_MS = 2_500L
        const val SILENT_MIC_WARNING_MS = 2_000L
        const val TAIL_DRAIN_MS = 1_500L

        private fun countWords(s: String) = s.split(Regex("\\s+")).count { it.isNotEmpty() }
    }
}

data class WavTranscription(
    val text: String,
    val audioDurationMs: Long,
    val connectLatencyMs: Long?,
    val termination: TerminationMessage?,
    val wallClockMs: Long,
)
