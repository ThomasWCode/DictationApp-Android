package com.thomaswcode.dictationapp.core

import com.thomaswcode.dictationapp.core.audio.AudioFrame
import com.thomaswcode.dictationapp.core.audio.WavFileSink
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.history.InterruptedRecordings
import com.thomaswcode.dictationapp.core.history.RecordStatus
import com.thomaswcode.dictationapp.core.history.WavRepair
import com.thomaswcode.dictationapp.core.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recordings left unfinished by a crash or a kill are repaired and listed for Retry at the next start (as on Windows). */
class InterruptedRecordingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = System.currentTimeMillis()
    private val runStart = now - 60_000
    private val history = InMemoryHistory()

    private fun name(at: Long) = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(at)) + ".wav"

    /** A recording as the app writes it; [finished] false is what a crash leaves (the sizes never written). */
    private fun recording(at: Long, lastWritten: Long, bytes: Int = WavRepair.BYTES_PER_SECOND, finished: Boolean = false): File {
        val file = File(tmp.root, name(at))
        val sink = WavFileSink(file.path)
        sink.write(ByteArray(bytes) { 1 })
        if (finished) sink.complete() else sink.close()
        file.setLastModified(lastWritten)
        return file
    }

    private fun recover() = runBlocking {
        InterruptedRecordings(history, { AppSettings() }, tmp.root, NoLogger, clock = { now }).recover(runStart)
    }

    @Test
    fun anUnfinishedRecordingFromAnEarlierRunIsRepairedAndListedForRetry() {
        val at = now - 3_600_000
        val file = recording(at, lastWritten = runStart - 10_000)

        assertEquals(1, recover())
        val record = history.records.single()
        assertEquals(RecordStatus.Failed, record.status)
        assertEquals(InterruptedRecordings.RECOVERED_REASON, record.failureReason)
        assertEquals(file.path, record.audioPath)
        assertEquals(1000, record.durationMs)
        assertEquals(at, record.createdAt)
        assertNull(WavRepair.repairUnfinished(file)) // finished now
    }

    @Test
    fun tooShortOrPastRetentionRecordingsAreDeletedAndCurrentOrFinishedOnesLeftAlone() {
        val tiny = recording(now - 3_500_000, lastWritten = runStart - 10_000, bytes = 3_200)
        val pastRetention = recording(now - 25L * 86_400_000, lastWritten = now - 25L * 86_400_000)
        val thisRun = recording(now - 20_000, lastWritten = runStart + 30_000)
        val finishedOrphan = recording(now - 3_400_000, lastWritten = runStart - 10_000, finished = true)

        assertEquals(0, recover())
        assertFalse(tiny.exists())
        assertFalse(pastRetention.exists())
        assertNotNull(WavRepair.repairUnfinished(thisRun)) // untouched: this run is still writing it
        assertTrue(finishedOrphan.exists())
        assertTrue(history.records.isEmpty())
    }

    @Test
    fun aRecordingHistoryAlreadyListsIsRepairedButNotAddedAgain() {
        val file = recording(now - 3_600_000, lastWritten = runStart - 10_000)
        runBlocking { history.insert(DictationRecord(createdAt = 1, updatedAt = 1, status = RecordStatus.Inserted, audioPath = file.path)) }

        assertEquals(0, recover())
        assertEquals(1, history.records.size)
        assertNull(WavRepair.repairUnfinished(file))
    }

    @Test
    fun aRepairedRecordingReadsBackWholeSamples() {
        val file = File(tmp.root, name(now))
        val sink = WavFileSink(file.path)
        sink.write(ByteArray(AudioFrame.FRAME_BYTES + 1) { 2 }) // an odd byte: cut to whole samples
        sink.close()

        assertEquals(AudioFrame.FRAME_BYTES.toLong(), WavRepair.repairUnfinished(file))
        assertEquals(44L + AudioFrame.FRAME_BYTES, file.length())
    }
}
