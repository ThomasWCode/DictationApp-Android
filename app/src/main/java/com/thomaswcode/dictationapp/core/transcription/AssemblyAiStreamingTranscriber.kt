package com.thomaswcode.dictationapp.core.transcription

import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.settings.ApiKeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException

/**
 * OkHttp WebSocket client for wss://streaming.assemblyai.com/v3/ws. OkHttp queues sends in order on its
 * own writer thread, so frames, ForceEndpoint and Terminate keep their order without a send lock.
 */
class AssemblyAiStreamingTranscriber(
    private val client: OkHttpClient,
    private val keys: ApiKeyProvider,
    private val logger: Logger,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : StreamingTranscriber {
    private val begin = CompletableDeferred<BeginMessage>()
    private val termination = CompletableDeferred<TerminationMessage>()

    @Volatile private var endOfTurn: CompletableDeferred<Boolean>? = null

    @Volatile private var hasOpenTurn = false

    @Volatile private var closing = false

    @Volatile private var open = false

    @Volatile private var socket: WebSocket? = null

    /** The socket failure, remembered even while closing so shutdown can report a truncated session. */
    @Volatile private var failure: Throwable? = null

    @Volatile override var onTurn: ((TurnMessage) -> Unit)? = null

    @Volatile override var onFault: ((Throwable) -> Unit)? = null

    override val isConnected: Boolean get() = open && !closing

    override var connectLatencyMs: Long? = null
        private set

    var sessionId: String? = null
        private set

    override suspend fun connect(options: SessionOptions): BeginMessage {
        val key = keys.assemblyAiKey() ?: throw IllegalStateException("No AssemblyAI API key is configured.")
        val request = Request.Builder()
            .url(endpoint + options.buildQueryString())
            .header("Authorization", key)
            .build()
        val started = System.nanoTime()
        logger.debug("Connecting to $endpoint model=${options.speechModel} keyterms=${options.keyterms.size}")
        socket = client.newWebSocket(request, Listener())
        try {
            val msg = begin.await()
            connectLatencyMs = (System.nanoTime() - started) / 1_000_000
            sessionId = msg.id
            logger.info("Streaming session ${msg.id} began after $connectLatencyMs ms")
            return msg
        } catch (e: CancellationException) {
            abort()
            throw e
        }
    }

    override fun sendAudio(pcm16: ByteArray) {
        val ws = socket ?: return
        if (!open || pcm16.isEmpty()) return
        if (!ws.send(pcm16.toByteString())) {
            logger.debug("Audio frame dropped: socket closing or send queue full")
        }
    }

    override fun updateConfiguration(keyterms: List<String>?, prompt: String?) {
        val payload = buildJsonObject {
            put("type", "UpdateConfiguration")
            if (keyterms != null) put("keyterms_prompt", JsonArray(keyterms.map { JsonPrimitive(it) }))
            if (prompt != null) put("prompt", prompt.take(SessionOptions.MAX_PROMPT_LENGTH))
        }
        sendText(payload.toString())
    }

    override suspend fun shutdown(hardCapMs: Long): TerminationMessage? {
        val ws = socket
        if (ws == null || !open) {
            // The socket died after the user released: the transcript may be missing its tail.
            failure?.let { throw IOException("Streaming connection lost: ${it.message}", it) }
            return null
        }

        closing = true
        val started = System.nanoTime()
        var result: TerminationMessage? = null
        try {
            val completed = withTimeoutOrNull(hardCapMs) {
                // 1. Silence tail so the server's VAD sees the end of speech.
                ws.send(ByteArray(AudioFrame.SAMPLE_RATE * 2 * SILENCE_TAIL_MS / 1000).toByteString())

                // 2. ForceEndpoint and wait for the closing Turn.
                val hadOpenTurn = hasOpenTurn
                val eot = CompletableDeferred<Boolean>()
                endOfTurn = eot
                ws.send("""{"type":"ForceEndpoint"}""")
                if (withTimeoutOrNull(if (hadOpenTurn) END_OF_TURN_WAIT_OPEN_MS else END_OF_TURN_WAIT_CLOSED_MS) { runCatching { eot.await() }.getOrNull() } == null) {
                    logger.info("No end_of_turn after ForceEndpoint within budget (open turn: $hadOpenTurn)")
                }

                // 3. Terminate and wait for the summary.
                ws.send("""{"type":"Terminate"}""")
                result = withTimeoutOrNull(TERMINATION_WAIT_MS) { runCatching { termination.await() }.getOrNull() }
                if (result == null) logger.info("No Termination message within budget")
                true
            }
            if (completed == null) logger.info("Shutdown handshake hit the $hardCapMs ms hard cap")
        } finally {
            logger.debug("Shutdown handshake took ${(System.nanoTime() - started) / 1_000_000} ms")
            runCatching { ws.close(1000, "done") }
            open = false
        }

        // No summary and the socket failed on the way: report it so the session is saved as Failed with its audio
        // (Retry) instead of inserting a possibly truncated transcript.
        if (result == null) failure?.let { throw IOException("Streaming connection lost during shutdown: ${it.message}", it) }
        return result
    }

    override fun abort() {
        closing = true
        open = false
        runCatching { socket?.cancel() }
        val aborted = IOException("Session aborted")
        begin.completeExceptionally(aborted)
        termination.completeExceptionally(aborted)
        endOfTurn?.completeExceptionally(aborted)
    }

    private fun sendText(text: String) {
        val ws = socket ?: return
        if (open) ws.send(text)
    }

    private fun dispatch(text: String) {
        val msg = try {
            StreamingMessageParser.parse(text)
        } catch (e: SerializationException) {
            logger.warn("Unparseable streaming message (${text.length} chars)", e)
            return
        } catch (e: IllegalArgumentException) {
            logger.warn("Unparseable streaming message (${text.length} chars)", e)
            return
        }

        when (msg) {
            is BeginMessage -> begin.complete(msg)
            is TurnMessage -> {
                logger.debug("Turn ${msg.turnOrder} eot=${msg.endOfTurn} fmt=${msg.turnIsFormatted} chars=${msg.bestText.length}")
                hasOpenTurn = !msg.endOfTurn
                if (msg.endOfTurn) endOfTurn?.complete(true)
                onTurn?.invoke(msg)
            }
            is TerminationMessage -> {
                logger.info("Session ended: audio ${msg.audioDurationSeconds}s, session ${msg.sessionDurationSeconds}s")
                termination.complete(msg)
            }
            is ErrorMessage -> fault(IllegalStateException("AssemblyAI streaming error: ${msg.error}"))
            null -> Unit
        }
    }

    private fun fault(error: Throwable) {
        logger.warn("Streaming transcriber fault: ${error.message}", error)
        if (failure == null) failure = error
        begin.completeExceptionally(error)
        // A fault (e.g. a typed error frame) during the shutdown handshake ends its waits now instead of after they time
        // out; shutdown then reports the failure rather than returning a partial transcript as complete.
        endOfTurn?.completeExceptionally(error)
        termination.completeExceptionally(error)
        onFault?.invoke(error)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            open = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) = dispatch(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            logger.debug("Server closed the socket: $code $reason")
            if (code != 1000) failure = IOException("Server closed the stream: $code $reason")
            if (!closing) fault(IOException("Server closed the stream: $code $reason"))
            open = false
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            open = false
            val closed = IOException("Socket closed")
            begin.completeExceptionally(closed)
            termination.completeExceptionally(closed)
            endOfTurn?.completeExceptionally(closed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            open = false
            val error = if (response != null) IOException("HTTP ${response.code} ${response.message}".trim(), t) else t
            failure = error
            if (!closing) fault(error) else begin.completeExceptionally(error)
            termination.completeExceptionally(error)
            endOfTurn?.completeExceptionally(error)
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "wss://streaming.assemblyai.com/v3/ws"
        private const val SILENCE_TAIL_MS = 300
        private const val END_OF_TURN_WAIT_OPEN_MS = 1_500L
        private const val END_OF_TURN_WAIT_CLOSED_MS = 400L
        private const val TERMINATION_WAIT_MS = 1_000L
    }
}
