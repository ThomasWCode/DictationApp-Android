package com.thomaswcode.dictationapp.core.session

import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.audio.AudioCapture
import com.thomaswcode.dictationapp.core.audio.AudioCaptureFactory
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.AudioSink
import com.thomaswcode.dictationapp.core.audio.AudioSinkFactory
import com.thomaswcode.dictationapp.core.audio.LevelMeter
import com.thomaswcode.dictationapp.core.audio.MicrophoneAccessDeniedException
import com.thomaswcode.dictationapp.core.audio.MicrophoneUnavailableException
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
import kotlinx.coroutines.async
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
    /** How long a field lookup may take before the session goes on without it (see [captureWithin]). */
    private val lookupTimeoutMs: Long = LOOKUP_TIMEOUT_MS,
) {
    val machine = DictationStateMachine()
    private val events = Channel<ControlEvent>(Channel.UNLIMITED)
    private val ownerLock = Any()
    private val replayGate = Mutex()

    // Clipboard writers take turns (an insertion, History's Insert, a Retry's copy), so a paste can never pick up
    // another writer's text.
    private val clipboardGate = Mutex()
    private lateinit var scope: CoroutineScope

    @Volatile private var session: DictationSession? = null

    @Volatile private var sessionJob: Job? = null

    /**
     * Set while a Retry streams. A dictation and a Retry both stream to AssemblyAI and end on the clipboard, so one at a
     * time: a press meanwhile flashes busy, and a Retry during a dictation is refused. Guarded by [ownerLock].
     */
    @Volatile private var retrying = false

    /** The running Retry's own job and record, so deleting that record can stop it ([cancelRetry]). Guarded by [ownerLock]. */
    private var retryJob: Job? = null
    private var retryRecordId = 0L

    private var badgeSeq = 0

    /** Scales the microphone's level to the user's speaking volume for the bubble's bars, learning across dictations. */
    private val microphoneLevels = LevelMeter()

    /** Cleanup settings problems already shown this run (one toast each). */
    private val reportedCleanupProblems = HashSet<String>()

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

    /**
     * Discard the dictation. Only possible while it records: once released (✓ or letting go) it always finishes into the
     * field or onto the clipboard. [silent] skips the "Discarded" badge (used when a press turns into a drag).
     */
    fun cancel(silent: Boolean = false) = events.trySend(ControlEvent.Cancel(silent))

    /**
     * The accessibility service is going away (switched off, or stopped by the system). A dictation still recording is
     * stopped and kept in History as Failed with its audio, for Retry; one already released finishes as usual (onto
     * the clipboard, as there is no field to type into any more). Not a discard: that is only the user's choice.
     */
    fun interrupt() = events.trySend(ControlEvent.Interrupt)

    /**
     * Runs a full dictation (context capture, streaming, cleanup, insertion) with a WAV file standing in for
     * the microphone. The session releases itself when the file ends. Debug hook, like --simulate on Windows.
     */
    fun simulateDictationFromWav(wavPath: String) = events.trySend(ControlEvent.Press(wavPath))

    private fun dispatch(ev: ControlEvent) {
        when (ev) {
            is ControlEvent.Press -> synchronized(ownerLock) {
                if (retrying) {
                    hub.flash() // a Retry is streaming (see retrying)
                } else if (machine.isIdle && session == null) {
                    machine.fire(DictationTrigger.Press)
                    // A test recording gets a meter of its own: it is far louder than a voice, and teaching the
                    // microphone's meter its level would leave the bars barely moving in the next dictation.
                    val s = DictationSession(clock(), ev.wavPath, if (ev.wavPath == null) microphoneLevels else LevelMeter())
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
            ControlEvent.Interrupt -> session?.interrupt()
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
            // 1. Microphone first: anything said before it starts is lost. Frames buffer in the channel until the
            // socket is ready; the field lookup below comes after, so it cannot delay the recording.
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
                hub.update { it.copy(level = s.lastLevel) }
            }
            capture.onFault = { s.fault(it) }
            capture.start()
            s.mark("mic")
            publishSession(s, DictationState.Arming, "Listening")

            // 2. Socket: its TLS and session setup overlaps the field lookup below.
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

            // 3. Where the user is: the focused field, its app and website, and the app rule (tone and level). The lookup
            // can be slow (an app that answers accessibility queries late), so a release, a discard, a fault or the
            // service going away meanwhile stops the microphone at once rather than when the lookup returns: nothing said
            // after the release is recorded.
            // Bounded too: an app that never answers (hung) must not hold the session, and its microphone, before the
            // duration cap is even running; the insertion looks at the field again anyway.
            val deadline = jobs.async { delay(lookupTimeoutMs) }
            val lookup = jobs.async { safeCapture() }
            val endedDuringLookup = select<Boolean> {
                lookup.onAwait { false }
                deadline.onAwait { false }
                s.released.onJoin { true }
                s.cancelled.onJoin { true }
                s.faulted.onJoin { true }
                s.interrupted.onJoin { true }
            }
            if (endedDuringLookup) capture.stop()
            s.context = select {
                lookup.onAwait { it }
                deadline.onAwait {
                    lookup.cancel()
                    logger.warn("Field lookup took over $lookupTimeoutMs ms; going on without it")
                    ForegroundContext.Unknown
                }
            }
            deadline.cancel()
            s.mark("lookup")
            s.rule = AppRulesResolver.resolve(s.context, settings.appRules, settings.defaultTone, settings.defaultCleanupLevel, settings.insertMethod)
            s.tone = s.rule.tone
            s.level = s.rule.level
            record.packageName = s.context.packageName.ifEmpty { null }
            record.appLabel = s.context.appLabel.ifEmpty { null }
            record.windowTitle = s.context.windowTitle.ifEmpty { null }
            record.url = s.context.url
            logger.info("Dictation started in ${s.context.packageName} (${s.context.windowTitle}) rule=${s.rule.matchedBy} editable=${s.context.isEditable}/${s.context.editableReason} url=${s.context.urlSource}")
            publishSession(s, DictationState.Arming, "Listening")

            val first = select<Outcome> {
                connect.onJoin { Outcome.Connected }
                s.released.onJoin { Outcome.Released }
                s.cancelled.onJoin { Outcome.Cancelled }
                s.faulted.onJoin { Outcome.Faulted }
                s.interrupted.onJoin { Outcome.Interrupted }
            }

            // A discard wins over a release that arrived with it.
            if (s.cancelled.isCompleted) return discard(s, capture, sink, t, if (s.cancelled.await()) null else "Discarded")
            when (first) {
                Outcome.Interrupted -> return saveInterrupted(s, record, capture, sink, t)
                Outcome.Faulted -> return fail(s, record, capture, sink, t, s.faulted.await())
                Outcome.Released -> {
                    // Released before Begin. The release ends the recording: stop the microphone now, then give the
                    // connection its remaining budget and send what was recorded. Nothing said after the release is
                    // recorded or uploaded.
                    s.mark("release")
                    machine.fire(DictationTrigger.Release)
                    capture.stop()
                    s.frames.close()
                    publishSession(s, DictationState.Finalising, "Connecting")
                    try {
                        connect.await()
                    } catch (e: Exception) {
                        if (e is CancellationException && !connect.isCompleted) throw e
                        return fail(s, record, capture, sink, t, e)
                    }

                    s.mark("begin")
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
                    s.mark("begin")
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
                        s.interrupted.onJoin { Outcome.Interrupted }
                        cap.onJoin { Outcome.Cap }
                    }
                    capJob.cancel()
                    s.mark("release")
                    if (s.cancelled.isCompleted) return discard(s, capture, sink, t, if (s.cancelled.await()) null else "Discarded")
                    when (end) {
                        Outcome.Interrupted -> return saveInterrupted(s, record, capture, sink, t)
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

                    // The pill and its ✕ go at once: from here the dictation finishes (a discard is only possible while
                    // recording), so the bubble shows it working while the audio tail drains.
                    publishSession(s, DictationState.Finalising, "Finishing")
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
            s.mark("handshake")
            // A socket fault after the release is no longer being selected on; without a Termination summary the
            // transcript may be missing its tail, so save it as Failed (audio kept for Retry) rather than insert it.
            if (termination == null && s.faulted.isCompleted) return fail(s, record, capture, sink, t, s.faulted.await())
            if (termination == null) logger.warn("No Termination summary from the streaming session; the transcript may be missing its last words")
            val audioSeconds = termination?.audioDurationSeconds ?: (s.audioDurationMs / 1000.0)
            record.durationMs = s.audioDurationMs.toInt()
            record.costEstimate = CostEstimator.sttCost(settings.speechModel, billedSeconds(termination, audioSeconds))
            val rawText = s.assembler.finalText
            record.rawTranscript = rawText

            // The recording is finished (complete) only once its record is saved: until then its header keeps the
            // "unfinished" marker, so a crash or kill during cleanup or insertion leaves a file the next start
            // recovers into History instead of an orphan the sweep deletes.
            record.audioPath = sink?.path

            if (rawText.isBlank()) {
                sink?.discard()
                record.audioPath = null
                logger.info("Nothing heard (ms since the press: ${s.timings()})")
                finish(s, DictationTrigger.NothingHeard, "Nothing heard")
                return
            }

            machine.fire(DictationTrigger.HandshakeComplete)

            // 5. Post-processing.
            publishSession(s, DictationState.PostProcessing, "Cleaning up")
            record.tone = s.tone
            record.level = s.level
            val keyterms = KeytermsSelector.select(settings.dictionary)
            val request = PostProcessRequest(s.level, s.tone, keyterms, s.context.appName, s.context.url, s.rule.hint, pauseMarkedTranscript = s.assembler.pauseMarkedText)
            val result = postProcessor.process(rawText, request)
            s.mark("cleanup")
            reportCleanupProblem(result.configurationProblem, result.applied)
            record.cleanedText = if (result.applied) result.text else ""
            record.llmModel = result.model
            record.failureReason = if (result.applied) null else result.failureReason
            record.costEstimate = (record.costEstimate ?: 0.0) + CostEstimator.llmCost(result.model, result.promptTokens, result.completionTokens)
            val badge = if (result.applied || !PostProcessorRouter.needsLlm(s.level, s.tone)) null else "cleanup skipped"
            machine.fire(DictationTrigger.PostProcessed)

            // 6. Inserting. Re-check the focused field; if focus moved to another field, insert there but keep
            // the tone chosen at the start. If focus went nowhere, the original field is tried.
            publishSession(s, DictationState.Inserting, "Inserting")
            val current = captureWithin(jobs)
            val target = if (current.isEditable) current else s.context
            // Tone and cleanup stay as chosen at the start, but how the text goes in depends on where it lands:
            // finishing in Gmail after starting in a plain editor must use Gmail's rule (e.g. Paste). An address that
            // cannot be read now (the bar scrolled away) is no sign of a move.
            val insertMethod = if (target.packageName != s.context.packageName || (target.urlHost != null && target.urlHost != s.context.urlHost)) {
                logger.info("Focus moved during dictation: ${s.context.packageName} -> ${target.packageName}")
                insertMethodFor(target, settings)
            } else {
                s.rule.insertMethod
            }

            val insertion = deliver(result.text, target, insertMethod)
            s.mark("insert")
            record.insertedText = insertion.insertedText ?: result.text
            var endBadge = badge
            val trigger = when (insertion.outcome) {
                InsertionOutcome.Inserted -> {
                    record.status = RecordStatus.Inserted
                    if (insertion.leftOnClipboard) endBadge = listOfNotNull(badge, "also copied").joinToString(" · ")
                    DictationTrigger.Inserted
                }
                InsertionOutcome.CopiedOnly -> {
                    record.status = RecordStatus.CopiedOnly
                    DictationTrigger.CopiedOnly
                }
                InsertionOutcome.Failed -> {
                    record.status = RecordStatus.Failed
                    record.failureReason = appendReason(record.failureReason, insertion.reason)
                    DictationTrigger.Failed
                }
            }

            record.updatedAt = clock()
            if (saveRecord(record)) sink?.complete()
            bumpDictionaryUsage(keyterms, rawText)
            logger.info("Dictation ${record.status}: ${countWords(record.insertedText)} words, ${"%.1f".format(audioSeconds)}s audio, llm=${result.model ?: "none"} (ms since the press: ${s.timings()})")
            finish(s, trigger, endBadge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Errors too (an ExceptionInInitializerError once escaped this and lost the dictation): keep what was heard.
            logger.error("Dictation session crashed in state ${machine.state}", e)
            withContext(NonCancellable) { runCatching { fail(s, record, capture, sink, transcriber, e) } }
        } finally {
            jobs.cancel()
            runCatching { capture?.close() }
            runCatching { sink?.close() }
            runCatching { transcriber?.abort() }
            finish(s, DictationTrigger.Failed, null)
        }
    }

    /**
     * History's Insert: into the field that has focus now, with the same checks, insertion method (the destination
     * app's rule) and outcome toasts as a live dictation. The record itself is not changed.
     */
    suspend fun reinsert(text: String): InsertionResult {
        if (!machine.isIdle) {
            notifier.toast("Insert", "Finish the current dictation first.", ToastKind.Warning)
            return InsertionResult(InsertionOutcome.Failed, "A dictation is running")
        }

        val target = safeCapture()
        return try {
            // The stored text carries the spacing of where it first went; the field it goes into now decides afresh.
            val insertion = deliver(text.trimStart(), target, insertMethodFor(target, settings.current))
            logger.info("History Insert into ${target.packageName}: ${insertion.outcome}")
            insertion
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("History Insert failed", e)
            notifier.toast("Could not insert", e.message ?: e.javaClass.simpleName, ToastKind.Warning)
            InsertionResult(InsertionOutcome.Failed, e.message)
        }
    }

    /**
     * Delivers [text] into [target]: through the inserter when it is an editable field that is not a password field,
     * onto the clipboard otherwise, and onto the clipboard as well when the insertion fails. Shows the toast for a copy
     * or a failure. Clipboard writers take turns, and a delivery, once started, is never cancelled half-way: it is the
     * commit point of a dictation.
     */
    private suspend fun deliver(text: String, target: ForegroundContext, method: InsertMethod): InsertionResult = withContext(NonCancellable) {
        var copiedAfterFailure = false
        val insertion = clipboardGate.withLock {
            val result = when {
                !target.isEditable -> copy(text, "No text field has focus, text copied")
                target.isPassword -> copy(text, "Not typing into a password field, text copied")
                else -> inserter.insert(text, target, method)
            }

            if (result.outcome == InsertionOutcome.Failed && target.isEditable && !target.isPassword) {
                copiedAfterFailure = copy(result.insertedText ?: text, null).outcome == InsertionOutcome.CopiedOnly
            }

            result
        }

        when (insertion.outcome) {
            InsertionOutcome.CopiedOnly -> notifier.toast("Copied to clipboard", insertion.reason ?: "Paste it where you need it.", ToastKind.Info)
            InsertionOutcome.Failed -> notifier.toast(
                "Could not insert",
                (insertion.reason ?: "Unknown error") + if (copiedAfterFailure) ". Text copied to the clipboard." else ". Use Copy in History.",
                ToastKind.Warning,
            )
            // The bubble cannot show text, so an uncertain insertion is told by a toast.
            InsertionOutcome.Inserted -> if (insertion.leftOnClipboard) {
                notifier.toast("Inserted and copied", insertion.reason ?: "The text is on the clipboard as well.", ToastKind.Info)
            }
        }

        insertion
    }

    /** Puts [text] on the clipboard without a joining space: it will be pasted by hand somewhere else. */
    private suspend fun copy(text: String, reason: String?): InsertionResult {
        val copied = text.trimStart()
        return try {
            clipboard.setText(copied)
            InsertionResult(InsertionOutcome.CopiedOnly, reason, copied)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not put the text on the clipboard", e)
            InsertionResult(InsertionOutcome.Failed, appendReason(reason, "the clipboard could not be set: ${e.message}"), copied)
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

    /**
     * The accessibility service went away while the dictation was still recording: keep what was recorded as a Failed
     * record with its audio and the words heard, for Retry. No network call, no insertion.
     */
    private suspend fun saveInterrupted(s: DictationSession, record: DictationRecord, capture: AudioCapture?, sink: AudioSink?, transcriber: StreamingTranscriber?) {
        logger.warn("Accessibility service stopped during a dictation; keeping it for Retry")
        capture?.stop()
        s.frames.close()
        transcriber?.abort()
        if (sink != null && sink.bytesWritten > 0) record.audioPath = sink.path else sink?.discard()
        record.status = RecordStatus.Failed
        record.failureReason = INTERRUPTED_REASON
        record.rawTranscript = s.assembler.finalText
        record.tone = s.tone
        record.level = s.level
        record.durationMs = s.audioDurationMs.toInt()
        record.updatedAt = clock()
        if ((record.audioPath != null || record.rawTranscript.isNotBlank()) && saveRecord(record) && record.audioPath != null) sink?.complete()
        finish(s, DictationTrigger.Failed, null)
    }

    private suspend fun fail(s: DictationSession, record: DictationRecord, capture: AudioCapture?, sink: AudioSink?, transcriber: StreamingTranscriber?, error: Throwable) {
        logger.warn("Dictation failed: ${error.message}", error)
        capture?.stop()
        s.frames.close()
        transcriber?.abort()
        if (sink != null && sink.bytesWritten > 0) {
            record.audioPath = sink.path // finished below once the record is saved
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
        if (transcriber?.connectLatencyMs != null && record.costEstimate == null) {
            // The streaming session was open and billed; no summary, so the audio sent is the best estimate.
            record.costEstimate = CostEstimator.sttCost(settings.current.speechModel, s.audioDurationMs / 1000.0)
        }

        record.updatedAt = clock()
        val isNetwork = error is IOException || error is TimeoutException || error.cause is IOException
        when {
            error is MicrophoneAccessDeniedException -> {
                notifier.toast("Microphone blocked", "Allow microphone access for DictationApp.", ToastKind.Error)
                notifier.requestMicrophonePermission()
            }
            error is MicrophoneUnavailableException -> notifier.toast("Microphone unavailable", message, ToastKind.Warning)
            record.audioPath != null -> {
                if (saveRecord(record)) {
                    sink?.complete()
                    if (isNetwork) {
                        notifier.toast("Network error", "Saved to history. Use Retry when you are back online.", ToastKind.Warning)
                    } else {
                        notifier.toast("Dictation failed", "$message The recording is saved in History.", ToastKind.Warning)
                    }
                } else {
                    // Left unfinished on purpose: the next start recovers it into History for Retry.
                    notifier.toast("Dictation failed", "$message History could not be written; the recording is kept and will be listed after a restart.", ToastKind.Warning)
                }
            }
            // No audio ("Store audio" off, or nothing recorded) but some words were heard: keep them in History,
            // where they can still be copied. Retry needs audio, so it stays unavailable for this record.
            record.rawTranscript.isNotBlank() -> {
                val saved = saveRecord(record)
                notifier.toast("Dictation failed", message + if (saved) " What was heard so far is saved in History." else " History could not be written.", ToastKind.Warning)
            }
            else -> notifier.toast("Dictation failed", message, ToastKind.Error)
        }

        finish(s, DictationTrigger.Failed, "Failed")
    }

    /** False when History could not be written (logged); callers then say so instead of "saved". */
    private suspend fun saveRecord(record: DictationRecord): Boolean = try {
        if (record.id == 0L) record.id = history.insert(record) else history.update(record)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logger.error("Could not write history record", e)
        false
    }

    /**
     * A cleanup settings problem that will recur (rejected key, a model the key may not use, a retired model) is shown
     * once per run, even when a fallback model covered for it.
     */
    private fun reportCleanupProblem(problem: String?, applied: Boolean) {
        if (problem == null || !synchronized(reportedCleanupProblems) { reportedCleanupProblems.add(problem) }) return
        notifier.toast(if (applied) "Cleanup model problem" else "Cleanup skipped", problem, ToastKind.Warning)
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

    private fun buildSessionOptions(settings: AppSettings) = sessionOptions(settings)

    /** The field with focus now, or [ForegroundContext.Unknown] after [lookupTimeoutMs] (an app that does not answer). */
    private suspend fun captureWithin(jobs: CoroutineScope): ForegroundContext {
        val lookup = jobs.async { safeCapture() }
        return withTimeoutOrNull(lookupTimeoutMs) { lookup.await() } ?: ForegroundContext.Unknown.also {
            lookup.cancel()
            logger.warn("Field lookup took over $lookupTimeoutMs ms; going on without it")
        }
    }

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
                level = s.lastLevel,
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
     * Re-streams a stored WAV through the transcriber (at [RETRY_REPLAY_SPEED]), cleans it up and copies the result to
     * the clipboard. Used by Retry in History for failed and pending records. A retry whose streaming session ended
     * without its Termination summary may be missing its end, so it counts as failed: the record keeps its earlier
     * words and stays retryable.
     */
    suspend fun retry(recordId: Long): Boolean {
        val record = history.get(recordId)
        val audio = record?.audioPath
        if (record == null || audio.isNullOrEmpty() || !File(audio).exists()) {
            notifier.toast("Retry", "No audio is stored for this dictation.", ToastKind.Warning)
            return false
        }

        var reserved = false
        return try {
            // Its own scope, taken in the same step as the reservation: cancelRetry (the record is being deleted) ends
            // the retry however soon after the start it comes, without cancelling the caller.
            coroutineScope {
                val busy = synchronized(ownerLock) {
                    when {
                        retrying -> "A retry is already running."
                        !machine.isIdle || session != null -> "Finish the current dictation first."
                        else -> {
                            retrying = true // until this retry ends; it outlives the History screen
                            reserved = true
                            retryJob = coroutineContext[Job]
                            retryRecordId = recordId
                            null
                        }
                    }
                }
                if (busy == null) {
                    retryStreaming(record, audio, recordId)
                } else {
                    notifier.toast("Retry", busy, ToastKind.Warning)
                    false
                }
            }
        } catch (e: RetryStopped) {
            logger.info("Retry of record $recordId stopped: the dictation is being deleted")
            false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.error("Retry of record $recordId failed", e)
            record.failureReason = "retry: ${e.message}"
            runCatching { history.update(record) }
            notifier.toast("Retry failed", e.message ?: e.javaClass.simpleName, ToastKind.Error)
            false
        } finally {
            if (reserved) {
                synchronized(ownerLock) {
                    retrying = false
                    retryJob = null
                }
            }
        }
    }

    /**
     * Stops a running Retry of [recordId] (any Retry when null) before its record or recording is deleted: otherwise it
     * would keep streaming the deleted audio and then put the deleted dictation on the clipboard.
     */
    fun cancelRetry(recordId: Long? = null) {
        val job = synchronized(ownerLock) { retryJob.takeIf { retrying && (recordId == null || recordId == retryRecordId) } }
        job?.cancel(RetryStopped())
    }

    /** How [cancelRetry] ends a Retry: told apart from a cancellation of the Retry's caller, which goes on up. */
    private class RetryStopped : CancellationException("the dictation is being deleted")

    private suspend fun retryStreaming(record: DictationRecord, audio: String, recordId: Long): Boolean {
        val settings = settings.current
        val transcript = transcribeWav(audio, RETRY_REPLAY_SPEED, null)
        record.costEstimate = (record.costEstimate ?: 0.0) + CostEstimator.sttCost(settings.speechModel, billedSeconds(transcript.termination, transcript.wallClockMs / 1000.0))
        record.updatedAt = clock()
        if (transcript.termination == null) {
            logger.warn("Retry of record $recordId ended without a Termination summary; keeping the earlier transcript")
            record.failureReason = "retry: the transcription did not finish"
            history.update(record)
            notifier.toast("Retry failed", "The transcription did not finish. The recording is kept; try Retry again.", ToastKind.Warning)
            return false
        }

        if (transcript.text.isBlank()) {
            record.failureReason = "retry: nothing heard"
            history.update(record)
            notifier.toast("Retry", "Nothing was heard in the recording.", ToastKind.Warning)
            return false
        }

        val request = PostProcessRequest(record.level, record.tone, KeytermsSelector.select(settings.dictionary), record.appName, record.url, null, pauseMarkedTranscript = transcript.pauseMarkedText)
        val result = postProcessor.process(transcript.text, request)
        reportCleanupProblem(result.configurationProblem, result.applied)
        clipboardGate.withLock { clipboard.setText(result.text) }
        record.rawTranscript = transcript.text
        record.cleanedText = if (result.applied) result.text else ""
        record.insertedText = result.text
        record.llmModel = result.model
        record.status = RecordStatus.CopiedOnly
        record.failureReason = if (result.applied) null else result.failureReason
        record.updatedAt = clock()
        record.costEstimate = (record.costEstimate ?: 0.0) + CostEstimator.llmCost(result.model, result.promptTokens, result.completionTokens)
        history.update(record)
        notifier.toast("Retry complete", "Text copied to the clipboard.", ToastKind.Success)
        return true
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
            try {
                withTimeout(CONNECT_TIMEOUT_MS + 2_000) { transcriber.connect(buildSessionOptions(settings)) }
            } catch (e: TimeoutCancellationException) {
                // A failure Retry reports, as in a dictation; left as a cancellation it would end the Retry without a word.
                throw TimeoutException("Connection timed out")
            }
            capture.start()
            coroutineScope {
                val sender = launch { for (frame in frames) transcriber.sendAudio(frame.pcm16) }
                done.await()
                sender.join()
            }

            val termination = transcriber.shutdown(HANDSHAKE_CAP_MS)
            WavTranscription(assembler.finalText, bytes.get() * 1000 / (AudioFrame.SAMPLE_RATE * 2), transcriber.connectLatencyMs, termination, clock() - started, assembler.pauseMarkedText)
        } finally {
            capture.close()
            transcriber.abort()
        }
    }

    private enum class Outcome { Connected, Released, Cancelled, Faulted, Interrupted, Cap }

    private sealed interface ControlEvent {
        data class Press(val wavPath: String?) : ControlEvent

        data object Release : ControlEvent

        data object HandsFree : ControlEvent

        data class Cancel(val silent: Boolean) : ControlEvent

        data object Interrupt : ControlEvent
    }

    /** Mutable per-dictation state shared between the control loop and the session job. */
    private class DictationSession(val startedAt: Long, val simulatedWavPath: String?, private val levels: LevelMeter) {
        val released = CompletableDeferred<Unit>()

        /** Completes with true for a silent cancel (no badge). */
        val cancelled = CompletableDeferred<Boolean>()
        val faulted = CompletableDeferred<Throwable>()

        /** The accessibility service went away (see [DictationOrchestrator.interrupt]). */
        val interrupted = CompletableDeferred<Unit>()
        private val started = System.nanoTime()
        private val marks = mutableListOf<Pair<String, Long>>()
        val frames = Channel<AudioFrame>(Channel.UNLIMITED)
        val assembler = TranscriptAssembler()
        private val bytes = AtomicLong()

        @Volatile var context: ForegroundContext = ForegroundContext.Unknown

        @Volatile var rule = ResolvedRule(Tone.Neutral, CleanupLevel.Light, InsertMethod.Direct, null, "default")

        @Volatile var tone: Tone = Tone.Neutral

        @Volatile var level: CleanupLevel = CleanupLevel.Light

        @Volatile var handsFree = false

        @Volatile var transcriber: StreamingTranscriber? = null

        /** The bubble's bar height for the latest frame (see [LevelMeter]). */
        @Volatile var lastLevel = 0f

        @Volatile var maxPeak = 0f

        @Volatile var frameCount = 0

        val audioDurationMs: Long get() = bytes.get() * 1000 / (AudioFrame.SAMPLE_RATE * 2)

        fun release() = released.complete(Unit)

        fun cancel(silent: Boolean) = cancelled.complete(silent)

        fun fault(e: Throwable) = faulted.complete(e)

        fun interrupt() = interrupted.complete(Unit)

        /** Notes when a phase was reached, for the one timing line logged per dictation (no text). */
        fun mark(phase: String) = synchronized(marks) {
            if (marks.none { it.first == phase }) marks.add(phase to (System.nanoTime() - started) / 1_000_000)
        }

        /** For example "mic 21, lookup 180, begin 902, release 3410, handshake 4120, cleanup 4800, insert 5130". */
        fun timings(): String = synchronized(marks) { marks.joinToString(", ") { "${it.first} ${it.second}" } }

        fun onFrame(frame: AudioFrame) {
            bytes.addAndGet(frame.pcm16.size.toLong())
            lastLevel = levels.add(frame.peak)
            if (frame.peak > maxPeak) maxPeak = frame.peak
            frameCount++
        }
    }

    companion object {
        /** The insertion method the app rules give [target] (used when text lands somewhere other than where it started). */
        fun insertMethodFor(target: ForegroundContext, settings: AppSettings): InsertMethod =
            AppRulesResolver.resolve(target, settings.appRules, settings.defaultTone, settings.defaultCleanupLevel, settings.insertMethod).insertMethod

        fun sessionOptions(settings: AppSettings) = SessionOptions(
            speechModel = settings.speechModel,
            keyterms = KeytermsSelector.select(settings.dictionary),
            languageCodes = settings.languageCodes?.takeIf { it.isNotBlank() },
            minTurnSilenceMs = DICTATION_MIN_TURN_SILENCE_MS,
            maxTurnSilenceMs = DICTATION_MAX_TURN_SILENCE_MS,
        )

        /**
         * Silence before AssemblyAI may end a turn at terminal punctuation. Its default (100 ms) suits voice agents:
         * a pause to think mid-sentence became a full stop and a new capitalised sentence. Dictation needs no early
         * turn ends, because finishing the dictation force-ends the last turn at once.
         */
        const val DICTATION_MIN_TURN_SILENCE_MS = 1_000

        /** Silence after which a turn ends even without terminal punctuation (default 1000 ms). */
        const val DICTATION_MAX_TURN_SILENCE_MS = 3_600

        const val CONNECT_TIMEOUT_MS = 3_000L

        /** A field lookup normally takes tens of milliseconds; one that takes this long is given up. */
        const val LOOKUP_TIMEOUT_MS = 5_000L
        const val HANDSHAKE_CAP_MS = 2_500L

        /**
         * Replay pace for Retry: real time. AssemblyAI processes streamed audio at about 1.25x real time at most and then
         * ends the session with a short handshake, so the old 4x replay left the end of any recording longer than a few
         * seconds unprocessed and the retry came back silently cut off. A retry therefore takes about as long as the
         * recording.
         */
        const val RETRY_REPLAY_SPEED = 1.0

        /** Failure reason of a dictation still recording when the accessibility service went away. */
        const val INTERRUPTED_REASON = "Interrupted: the accessibility service stopped during the dictation"

        /**
         * What AssemblyAI bills: the time the streaming session was open, from its Termination summary; otherwise the
         * measured fallback. The audio length alone misses the connection and handshake time.
         */
        fun billedSeconds(termination: TerminationMessage?, fallbackSeconds: Double): Double =
            termination?.sessionDurationSeconds?.takeIf { it > 0 } ?: fallbackSeconds

        /** A failure reason added after another (cleanup, then insertion) is appended, never overwrites it. */
        fun appendReason(existing: String?, added: String?): String? = when {
            existing.isNullOrEmpty() -> added
            added.isNullOrEmpty() -> existing
            else -> "$existing; $added"
        }
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
    val pauseMarkedText: String? = null,
)
