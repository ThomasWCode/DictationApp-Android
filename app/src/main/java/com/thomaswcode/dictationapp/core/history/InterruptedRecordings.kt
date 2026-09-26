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
        val head = ByteArray(12)
        if (raf.length() < 12 || raf.read(head) < 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val riffSize = le32(head, 4)
        var pos = 12L
        var dataOffset = -1L
        var dataSize = 0L
        val chunk = ByteArray(8)
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            raf.readFully(chunk)
            val size = le32(chunk, 4)
            if (String(chunk, 0, 4, Charsets.US_ASCII) == "data") {
                dataOffset = pos + 8
                dataSize = size
                break
            }

            pos += 8 + size + (size and 1)
        }

        if (dataOffset < 0) return null
        val pcm = (raf.length() - dataOffset) and 1L.inv()
        if (riffSize != 0L && !(dataSize == 0L && pcm > 0)) return null // finished
        raf.setLength(dataOffset + pcm)
        raf.seek(4)
        raf.write(le32Bytes(dataOffset + pcm - 8))
        raf.seek(dataOffset - 4)
        raf.write(le32Bytes(pcm))
        raf.fd.sync()
        pcm
    }

    private fun le32(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun le32Bytes(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
}

/**
 * Recordings a previous run left unfinished (killed, crashed, battery out while recording or before saving) are
 * repaired and listed in History as Failed, so Retry can transcribe them. Nothing is uploaded or pasted. Only files
 * last written before [runStartedAt] (this run's start) are touched, never the file of a dictation now running. A
 * recording shorter than [MIN_RECOVERED_MS], or older than the retention settings keep, is deleted.
 */
class InterruptedRecordings(
    private val history: HistoryRepository,
    private val settings: () -> AppSettings,
    private val audioDir: File,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns the number of recordings added to History. */
    suspend fun recover(runStartedAt: Long): Int {
        val files = audioDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return 0
        val s = settings()
        val now = clock()
        val keepFor = listOfNotNull(s.historyRetention.millis, s.audioRetention.millis).minOrNull()
        var referenced: Set<String>? = null
        var added = 0
        for (file in files) {
            try {
                if (file.lastModified() >= runStartedAt) continue
                val pcm = WavRepair.repairUnfinished(file) ?: continue
                val durationMs = pcm * 1000 / WavRepair.BYTES_PER_SECOND
                val recordedAt = recordingTime(file) ?: file.lastModified()
                if (durationMs < MIN_RECOVERED_MS || (keepFor != null && recordedAt < now - keepFor)) {
                    file.delete()
                    logger.info("Deleted an interrupted recording (${durationMs / 1000.0} s) that is too short or past retention")
                    continue
                }

                val refs = referenced ?: history.referencedAudioPaths().also { referenced = it }
                if (file.path in refs) {
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
                logger.info("Recovered an interrupted recording (${durationMs / 1000.0} s) into History for Retry")
            } catch (e: IOException) {
                logger.debug("Could not check ${file.name} for recovery: ${e.message}")
            }
        }

        return added
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
