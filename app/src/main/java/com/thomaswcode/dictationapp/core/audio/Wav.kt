package com.thomaswcode.dictationapp.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Streams 16 kHz mono PCM16 to a WAV file; the RIFF sizes are patched in [complete], which the session calls once the
 * History record that points to the file is saved. A file left by a crash or a kill therefore still says its data is
 * empty, and HistoryMaintenance recovers it at the next start instead of deleting it as an orphan. [close] without
 * [complete] keeps that marker.
 */
class WavFileSink(override val path: String) : AudioSink {
    private val file = File(path)
    private var raf: RandomAccessFile? = null
    private val lock = Any()

    @Volatile override var bytesWritten: Long = 0
        private set

    init {
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(header(0))
        }
    }

    override fun write(pcm16: ByteArray) = synchronized(lock) {
        val r = raf ?: return
        r.write(pcm16)
        bytesWritten += pcm16.size
    }

    override fun complete() = synchronized(lock) {
        val r = raf ?: return
        r.seek(0)
        r.write(header(bytesWritten))
        r.close()
        raf = null
    }

    override fun discard() = synchronized(lock) {
        runCatching { raf?.close() }
        raf = null
        file.delete()
        Unit
    }

    override fun close() = synchronized(lock) {
        // Not completed here: the "unfinished" marker must survive a session that ended before its record was saved.
        runCatching { raf?.close() }
        raf = null
        Unit
    }

    companion object {
        fun header(dataBytes: Long): ByteArray {
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36 + dataBytes).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)
            b.putShort(1) // PCM
            b.putShort(1) // mono
            b.putInt(AudioFrame.SAMPLE_RATE)
            b.putInt(AudioFrame.SAMPLE_RATE * 2)
            b.putShort(2)
            b.putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(dataBytes.toInt())
            return b.array()
        }
    }
}

/** A parsed PCM WAV file. Only the format we write (and simple 16-bit PCM) is supported. */
class WavData(val sampleRate: Int, val channels: Int, val pcm16: ByteArray) {
    val durationMs: Long get() = if (sampleRate == 0) 0 else pcm16.size * 1000L / (sampleRate * 2L * channels)

    /** Downmixes and resamples (linearly) to 16 kHz mono so any 16-bit WAV can stand in for the mic. */
    fun toMono16k(): ByteArray {
        if (sampleRate == AudioFrame.SAMPLE_RATE && channels == 1) return pcm16
        val inSamples = pcm16.size / 2 / channels
        val mono = ShortArray(inSamples)
        val bb = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until inSamples) {
            var sum = 0
            for (c in 0 until channels) sum += bb.getShort()
            mono[i] = (sum / channels).toShort()
        }

        val outSamples = (inSamples.toLong() * AudioFrame.SAMPLE_RATE / sampleRate).toInt()
        val out = ByteBuffer.allocate(outSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outSamples) {
            val src = i.toDouble() * sampleRate / AudioFrame.SAMPLE_RATE
            val i0 = src.toInt().coerceAtMost(inSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
            val frac = src - i0
            out.putShort((mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort())
        }

        return out.array()
    }

    companion object {
        fun read(path: String): WavData {
            val bytes = File(path).readBytes()
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file: $path" }
            var pos = 12
            var sampleRate = 0
            var channels = 0
            var bits = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = bb.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        channels = bb.getShort(body + 2).toInt()
                        sampleRate = bb.getInt(body + 4)
                        bits = bb.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        require(bits == 16) { "Only 16-bit PCM WAV is supported ($bits-bit found)" }
                        val len = size.coerceAtMost(bytes.size - body).coerceAtLeast(0)
                        return WavData(sampleRate, channels.coerceAtLeast(1), bytes.copyOfRange(body, body + len))
                    }
                }

                pos = body + size + (size and 1)
            }

            throw IllegalArgumentException("WAV file has no data chunk: $path")
        }
    }
}

/**
 * Replays a WAV file as 100 ms frames, paced at real time divided by [speed]. The app's own recordings (16 kHz mono)
 * are read a frame at a time, so a long recording is never held in memory (twice) as it was; other formats (the debug
 * hook's test files) are converted in memory. The last frame is zero-padded to a whole frame.
 */
class WavReplayAudioCapture(private val path: String, private val speed: Double) : AudioCapture {
    override var onFrame: ((AudioFrame) -> Unit)? = null
    override var onFault: ((Throwable) -> Unit)? = null
    override var onCompleted: (() -> Unit)? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "wav-replay", isDaemon = true) {
            try {
                val frameDelayMs = (AudioFrame.FRAME_MS / speed.coerceAtLeast(0.1)).toLong()
                var position = 0L
                frames { chunk, length ->
                    if (!running.get()) return@frames false
                    onFrame?.invoke(AudioFrame(chunk, AudioFrame.peakOf(chunk, length), position))
                    position += AudioFrame.FRAME_MS
                    Thread.sleep(frameDelayMs)
                    true
                }

                if (running.get()) onCompleted?.invoke()
            } catch (_: InterruptedException) {
                // stopped
            } catch (e: Throwable) {
                // Errors too (out of memory on a huge foreign file): Retry fails instead of taking the app down.
                onFault?.invoke(e)
            }
        }
    }

    override fun stop() {
        running.set(false)
    }

    override fun close() = stop()

    /** Calls [emit] with each whole, zero-padded frame and the length of real audio in it, until it returns false. */
    private fun frames(emit: (ByteArray, Int) -> Boolean) {
        val data = PcmData.locate(path)
        if (data == null) {
            // Not 16 kHz mono PCM16: convert in memory (small test files).
            val pcm = WavData.read(path).toMono16k()
            var offset = 0
            while (offset < pcm.size) {
                val len = minOf(AudioFrame.FRAME_BYTES, pcm.size - offset)
                if (!emit(pcm.copyOfRange(offset, offset + len).copyOf(AudioFrame.FRAME_BYTES), len)) return
                offset += len
            }

            return
        }

        RandomAccessFile(path, "r").use { file ->
            file.seek(data.offset)
            var remaining = data.length
            while (remaining > 0) {
                val chunk = ByteArray(AudioFrame.FRAME_BYTES)
                val len = file.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (len <= 0) break
                remaining -= len
                if (!emit(chunk, len)) return
            }
        }
    }
}

/** Where the PCM data of a 16 kHz mono 16-bit WAV file lies, found by walking its chunks; null for other formats. */
internal class PcmData(val offset: Long, val length: Long) {
    companion object {
        fun locate(path: String): PcmData? = RandomAccessFile(path, "r").use { file ->
            val head = ByteArray(12)
            if (file.length() < 12 || file.read(head) < 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var pos = 12L
            var pcm16Mono16k = false
            val chunk = ByteArray(8)
            while (pos + 8 <= file.length()) {
                file.seek(pos)
                file.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        file.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        pcm16Mono16k = b.getShort(0).toInt() == 1 && b.getShort(2).toInt() == 1 && b.getInt(4) == AudioFrame.SAMPLE_RATE && b.getShort(14).toInt() == 16
                    }
                    "data" -> {
                        if (!pcm16Mono16k) return null
                        // An unfinished recording says 0: its data runs to the end of the file.
                        val available = file.length() - (pos + 8)
                        return PcmData(pos + 8, if (size == 0L || size > available) available else size)
                    }
                }

                pos += 8 + size + (size and 1)
            }

            null
        }
    }
}
