package com.thomaswcode.dictationapp.core.audio

import java.io.Closeable

/** 100 ms of 16 kHz mono PCM16 little-endian audio plus its peak level (0..1). */
class AudioFrame(val pcm16: ByteArray, val peak: Float, val positionMs: Long) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 100
        const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000

        /** Peak absolute sample value scaled to 0..1. */
        fun peakOf(pcm16: ByteArray, length: Int = pcm16.size): Float {
            var max = 0
            var i = 0
            while (i + 1 < length) {
                val sample = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
                val abs = if (sample < 0) -sample else sample
                if (abs > max) max = abs
                i += 2
            }

            return (max / 32768f).coerceIn(0f, 1f)
        }
    }
}

/** A source of audio frames: the microphone, or a WAV file standing in for it. */
interface AudioCapture : Closeable {
    var onFrame: ((AudioFrame) -> Unit)?
    var onFault: ((Throwable) -> Unit)?

    /** Raised when a finite source (a WAV replay) runs out. */
    var onCompleted: (() -> Unit)?

    fun start()

    fun stop()
}

interface AudioCaptureFactory {
    /** [deviceKey] identifies a preferred input device, or null for the system default. */
    fun createMicrophone(deviceKey: String?): AudioCapture

    fun createWavReplay(path: String, speed: Double): AudioCapture
}

/** Records the dictation's audio for playback and retry from History. */
interface AudioSink : Closeable {
    val path: String
    val bytesWritten: Long

    fun write(pcm16: ByteArray)

    /** Finalises the file header. */
    fun complete()

    /** Deletes the file. */
    fun discard()
}

fun interface AudioSinkFactory {
    fun create(path: String): AudioSink
}

/** Thrown when the OS refuses microphone access (permission revoked or not yet granted). */
class MicrophoneAccessDeniedException(message: String) : Exception(message)
