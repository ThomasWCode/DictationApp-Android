package com.thomaswcode.dictationapp.core.history

import com.thomaswcode.dictationapp.core.Logger
import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.settings.AppSettings
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The repair of an unfinished recording. A recording's data size is written only when its session finishes (after the
 * History record is saved), so a file that still says its data is empty was left by a run that stopped while
 * recording or saving (killed, crashed, battery out).
 */
object WavRepair {
    const val BYTES_PER_SECOND = AudioFrame.SAMPLE_RATE * 2

    /**
     * When [file] is an unfinished recording, sets its sizes from the file length (cut to whole samples) and returns the
     * audio byte count. Null, and the file untouched, for anything else. Walks the chunks, so any PCM layout works.
     */
    fun repairUnfinished(file: File): Long? = RandomAccessFile(file, "rw").use { raf ->
        val layout = layout(raf) ?: return null
        val pcm = (raf.length() - layout.dataOffset) and 1L.inv()
        if (!layout.unfinished(pcm)) return null
        raf.setLength(layout.dataOffset + pcm)
        raf.seek(4)
        raf.write(le32Bytes(layout.dataOffset + pcm - 8))
        raf.seek(layout.dataOffset - 4)
        raf.write(le32Bytes(pcm))
        raf.fd.sync()
        pcm
    }

    /**
     * True when [file] is an unfinished recording, or cannot be read to tell: the orphan sweep must then keep it. Reads
     * only, the file is left as it is.
     */
    fun isUnfinished(file: File): Boolean = try {
        unfinishedPcmBytes(file) != null
    } catch (_: IOException) {
        true
    }

    /**
     * The audio byte count (cut to whole samples) [repairUnfinished] would write when [file] is an unfinished recording;
     * null for anything else. Throws when the file cannot be read (so a caller can try again later). Reads only.
     */
    fun unfinishedPcmBytes(file: File): Long? = RandomAccessFile(file, "r").use { raf ->
        val layout = layout(raf) ?: return null
        ((raf.length() - layout.dataOffset) and 1L.inv()).takeIf { layout.unfinished(it) }
    }

    private class Layout(val riffSize: Long, val dataOffset: Long, val dataSize: Long) {
        /** A RIFF size of zero, or a data size of zero with audio after the header: the sizes were never written. */
        fun unfinished(pcm: Long): Boolean = riffSize == 0L || (dataSize == 0L && pcm > 0)
    }

    private fun layout(raf: RandomAccessFile): Layout? {
        val head = ByteArray(12)
        raf.seek(0)
        if (raf.length() < 12 || raf.read(head) < 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12L
        val chunk = ByteArray(8)
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            raf.readFully(chunk)
            val size = le32(chunk, 4)
            if (String(chunk, 0, 4, Charsets.US_ASCII) == "data") return Layout(le32(head, 4), pos + 8, size)
            pos += 8 + size + (size and 1)
        }

        return null
    }

    private fun le32(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun le32Bytes(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
}

/**
 * Recordings a previous run left unfinished (killed, crashed, battery out while recording or before saving) are
 * repaired and listed in History as Failed, so Retry can transcribe them. Nothing is uploaded or pasted. A file's sizes
 * are written only once History lists it: a database error before that leaves it unfinished, so the next pass recovers
 * it instead of the sweep later taking it for a finished orphan. Only files
 * last written before [runStartedAt] (this run's start) are touched, never the file of a dictation now running. A
 * recording shorter than [MIN_RECOVERED_MS], or older than the retention settings keep, is deleted.
 */
class InterruptedRecordings(
    private val history: HistoryRepository,
    private val settings: () -> AppSettings,
    private val audioDir: File,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Reads a recording's state; replaceable so tests can make a file fail to read. */
    private val inspect: (File) -> Long? = WavRepair::unfinishedPcmBytes,
) {
    /**
     * [added] recordings were listed in History. [complete] is false when a recording could not be read: the caller
     * then runs recovery again at a later pass rather than leave that recording out until the next start.
     */
    data class Result(val added: Int, val complete: Boolean)

    suspend fun recover(runStartedAt: Long): Result {
        val files = audioDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return Result(0, complete = true)
        val s = settings()
        val now = clock()
        val keepFor = listOfNotNull(s.historyRetention.millis, s.audioRetention.millis).minOrNull()
        var referenced: Set<String>? = null
        var added = 0
        var complete = true
        for (file in files) {
            try {
                if (file.lastModified() >= runStartedAt) continue
                val pcm = inspect(file) ?: continue
                val durationMs = pcm * 1000 / WavRepair.BYTES_PER_SECOND
                val recordedAt = recordingTime(file) ?: file.lastModified()
                if (durationMs < MIN_RECOVERED_MS || (keepFor != null && recordedAt < now - keepFor)) {
                    file.delete()
                    logger.info("Deleted an interrupted recording (${durationMs / 1000.0} s) that is too short or past retention")
                    continue
                }

                val refs = referenced ?: history.referencedAudioPaths().also { referenced = it }
                if (file.path in refs) {
                    WavRepair.repairUnfinished(file)
                    logger.info("Repaired an unfinished recording that History already lists (${durationMs / 1000.0} s)")
                    continue
                }

                history.insert(
                    DictationRecord(
                        createdAt = recordedAt,
                        updatedAt = now,
                        status = RecordStatus.Failed,
                        failureReason = RECOVERED_REASON,
                        audioPath = file.path,
                        durationMs = durationMs.toInt(),
                        tone = s.defaultTone,
                        level = s.defaultCleanupLevel,
                    ),
                )
                added++
                WavRepair.repairUnfinished(file) // now that History lists it
                logger.info("Recovered an interrupted recording (${durationMs / 1000.0} s) into History for Retry")
            } catch (e: IOException) {
                complete = false
                logger.warn("Could not check ${file.name} for recovery; trying again at the next pass: ${e.message}")
            }
        }

        return Result(added, complete)
    }

    companion object {
        const val MIN_RECOVERED_MS = 300L
        const val RECOVERED_REASON = "Interrupted: DictationApp stopped during this dictation (recovered at the next start)"

        /** The time in a recording's file name (yyyyMMdd-HHmmss-SSS, local time, as AppPaths writes it), or null. */
        fun recordingTime(file: File): Long? = try {
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).parse(file.nameWithoutExtension)?.time
        } catch (_: ParseException) {
            null
        }
    }
}
