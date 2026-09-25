package com.thomaswcode.dictationapp.core.transcription

/**
 * One streaming session. Create -> connect -> send audio -> shutdown. Callbacks are raised on the socket
 * reader thread and must return quickly.
 */
interface StreamingTranscriber {
    var onTurn: ((TurnMessage) -> Unit)?

    /** Raised when the socket closes for a reason other than our own shutdown. */
    var onFault: ((Throwable) -> Unit)?

    val isConnected: Boolean

    /** Wall-clock milliseconds from socket open request to the server's Begin message. */
    val connectLatencyMs: Long?

    /** Opens the socket and suspends until the Begin message. */
    suspend fun connect(options: SessionOptions): BeginMessage

    fun sendAudio(pcm16: ByteArray)

    fun updateConfiguration(keyterms: List<String>?, prompt: String?)

    /**
     * Graceful shutdown: silence tail -> ForceEndpoint -> wait for end_of_turn -> Terminate -> wait for
     * Termination, bounded by [hardCapMs]. Returns the Termination summary when received.
     */
    suspend fun shutdown(hardCapMs: Long): TerminationMessage?

    /** Closes the socket immediately without the handshake (cancel / discarded session). */
    fun abort()
}

fun interface StreamingTranscriberFactory {
    fun create(): StreamingTranscriber
}
