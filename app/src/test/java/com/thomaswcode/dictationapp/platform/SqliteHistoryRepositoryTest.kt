package com.thomaswcode.dictationapp.platform

import androidx.test.core.app.ApplicationProvider
import com.thomaswcode.dictationapp.core.NoLogger
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.history.RecordStatus
import com.thomaswcode.dictationapp.core.history.RetentionPolicy
import com.thomaswcode.dictationapp.core.settings.AppPaths
import com.thomaswcode.dictationapp.core.settings.AppSettings
import com.thomaswcode.dictationapp.core.FixedSettings
import com.thomaswcode.dictationapp.core.audio.WavFileSink
import com.thomaswcode.dictationapp.core.history.InterruptedRecordings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Ported from the Windows SqliteHistoryRepositoryTests; runs Android's SQLite (FTS4) under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SqliteHistoryRepositoryTest {
    private val repo = SqliteHistoryRepository(ApplicationProvider.getApplicationContext(), "test-history.db")

    private fun record(text: String, createdAt: Long = 1_000, audio: String? = null) = DictationRecord(
        createdAt = createdAt,
        updatedAt = createdAt,
        rawTranscript = "raw $text",
        cleanedText = text,
        insertedText = text,
        tone = Tone.Formal,
        level = CleanupLevel.Medium,
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        windowTitle = "Chat with Sam",
        audioPath = audio,
        costEstimate = 0.001,
        status = RecordStatus.Inserted,
        llmModel = "openai/gpt-oss-120b",
    )

    @Test
    fun insertGetUpdateDeleteRoundTrip(): Unit = runBlocking {
        val r = record("Please start isoniazid today.")
        val id = repo.insert(r)
        assertTrue(id > 0)
        val loaded = repo.get(id)!!
        assertEquals(r.copy(id = id), loaded)
        loaded.insertedText = "Edited."
        loaded.aiEditUndone = true
        repo.update(loaded)
        assertEquals("Edited.", repo.get(id)!!.insertedText)
        assertTrue(repo.get(id)!!.aiEditUndone)
        repo.delete(id)
        assertNull(repo.get(id))
    }

    @Test
    fun searchUsesFullTextIndexWithPrefixMatching(): Unit = runBlocking {
        repo.insert(record("Please start isoniazid today.", createdAt = 1))
        repo.insert(record("Lunch at noon?", createdAt = 2))
        assertEquals(listOf("Please start isoniazid today."), repo.search("isoni").map { it.insertedText })
        assertEquals(listOf("Please start isoniazid today."), repo.search("START ISO").map { it.insertedText })
        assertEquals(2, repo.search("whatsapp").size) // app label is indexed
        assertEquals(listOf("Lunch at noon?", "Please start isoniazid today."), repo.search(null).map { it.insertedText }) // newest first
        repo.search("\"unbalanced * OR (") // user input can never break the FTS syntax
    }

    @Test
    fun searchIndexFollowsUpdates(): Unit = runBlocking {
        val id = repo.insert(record("alpha"))
        repo.update(repo.get(id)!!.apply { rawTranscript = "bravo"; insertedText = "bravo"; cleanedText = "bravo" })
        assertTrue(repo.search("alpha").isEmpty())
        assertEquals(1, repo.search("bravo").size)
    }

    @Test
    fun retentionDeletesOldRecordsAndReportsAudioPaths(): Unit = runBlocking {
        repo.insert(record("old", createdAt = 100, audio = "/a/old.wav"))
        repo.insert(record("new", createdAt = 10_000, audio = "/a/new.wav"))
        assertEquals(listOf("/a/old.wav"), repo.deleteOlderThan(5_000))
        assertEquals(listOf("new"), repo.search(null).map { it.insertedText })
        assertEquals(listOf("/a/new.wav"), repo.clearAudioOlderThan(20_000))
        assertNull(repo.search(null).single().audioPath)
    }

    @Test
    fun statsAndDeleteAll(): Unit = runBlocking {
        repo.insert(record("one", audio = "/a/1.wav"))
        repo.insert(record("two"))
        val stats = repo.stats()
        assertEquals(2, stats.count)
        assertEquals(1, stats.withAudio)
        assertEquals(0.002, stats.totalCost, 1e-9)
        assertEquals(listOf("/a/1.wav"), repo.deleteAll())
        assertEquals(0, repo.stats().count)
    }

    @Test
    fun ftsQueryBuilderQuotesTokens() {
        assertEquals("\"isoni*\"", SqliteHistoryRepository.toMatchExpression("isoni"))
        assertEquals("\"a*\" \"b*\"", SqliteHistoryRepository.toMatchExpression("  a \"b\" "))
        assertNull(SqliteHistoryRepository.toMatchExpression("   "))
    }

    @Test
    fun deleteAllSparesAWavStillBeingRecorded(): Unit = runBlocking {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "del")
        val paths = AppPaths(dir)
        paths.audioDir.mkdirs()
        val now = 10_000_000L
        val saved = File(paths.audioDir, "saved.wav").apply { writeText("x"); setLastModified(now - 3_600_000) }
        val stale = File(paths.audioDir, "stale.wav").apply { writeText("x"); setLastModified(now - 3_600_000) }
        val recording = File(paths.audioDir, "recording.wav").apply { writeText("x"); setLastModified(now - 1_000) }
        repo.insert(record("saved", audio = saved.path))
        val settings = FixedSettings(AppSettings())
        HistoryRetention(repo, settings, paths, NoLogger, clock = { now }).deleteAll()
        assertFalse(saved.exists())
        assertFalse(stale.exists())
        assertTrue(recording.exists())
        assertEquals(0, repo.stats().count)
    }

    @Test
    fun anEmptyHistoryDoesNotSweepItsRecordingsAsOrphans(): Unit = runBlocking {
        // A lost or recreated database must not turn every recording into an orphan.
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "empty")
        val paths = AppPaths(dir)
        paths.audioDir.mkdirs()
        val lonely = File(paths.audioDir, "lonely.wav").apply { writeText("x"); setLastModified(0) }
        HistoryRetention(repo, FixedSettings(AppSettings()), paths, NoLogger, clock = { 20L * 86_400_000 }).run()
        assertTrue(lonely.exists())
    }

    /** A recording as the app writes it; [finished] false is what a crash or a failed save leaves. */
    private fun wav(file: File, lastWritten: Long, finished: Boolean): File {
        val sink = WavFileSink(file.path)
        sink.write(ByteArray(32_000) { 1 })
        if (finished) sink.complete() else sink.close()
        file.setLastModified(lastWritten)
        return file
    }

    @Test
    fun theOrphanSweepLeavesUnfinishedRecordingsForRecovery(): Unit = runBlocking {
        // A save that failed in this run leaves its recording unfinished and unlisted until the next start lists it; an
        // app running for hours must not sweep it first.
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "unfinished")
        val paths = AppPaths(dir)
        paths.audioDir.mkdirs()
        val now = 20L * 86_400_000
        repo.insert(record("listed"))
        val unfinished = wav(File(paths.audioDir, "failed-save.wav"), now - 3_600_000, finished = false)
        val orphan = wav(File(paths.audioDir, "orphan.wav"), now - 3_600_000, finished = true)

        HistoryRetention(repo, FixedSettings(AppSettings()), paths, NoLogger, clock = { now }, runStartedAt = now - 7_200_000).run()

        assertTrue(unfinished.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun aLostDatabaseSkipsTheSweepEvenAfterRecoveryListsARecording(): Unit = runBlocking {
        // An empty History with an unfinished recording from an earlier run: the pass lists it, and the other
        // recordings, which no record refers to any more, are not swept as orphans.
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "lost")
        val paths = AppPaths(dir)
        paths.audioDir.mkdirs()
        val now = 20L * 86_400_000
        val interrupted = wav(File(paths.audioDir, "interrupted.wav"), now - 3_600_000, finished = false)
        val unlisted = wav(File(paths.audioDir, "unlisted.wav"), now - 3_600_000, finished = true)

        HistoryRetention(repo, FixedSettings(AppSettings()), paths, NoLogger, clock = { now }, runStartedAt = now - 60_000).run()

        assertEquals(InterruptedRecordings.RECOVERED_REASON, repo.latest()?.failureReason)
        assertTrue(interrupted.exists())
        assertTrue(unlisted.exists())
    }

    @Test
    fun retentionPassDeletesFilesAndOrphans(): Unit = runBlocking {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "ret")
        val paths = AppPaths(dir)
        paths.audioDir.mkdirs()
        val old = File(paths.audioDir, "old.wav").apply { writeText("x") }
        val orphan = File(paths.audioDir, "orphan.wav").apply { writeText("x"); setLastModified(0) }
        val fresh = File(paths.audioDir, "recording-now.wav").apply { writeText("x") }
        repo.insert(record("old", createdAt = 0, audio = old.path))
        val settings = FixedSettings(AppSettings(historyRetention = RetentionPolicy.Days14, audioRetention = RetentionPolicy.Hours24))
        HistoryRetention(repo, settings, paths, NoLogger, clock = { 20L * 86_400_000 }).run()
        assertFalse(old.exists())
        assertFalse(orphan.exists())
        assertTrue(fresh.exists())
        assertEquals(0, repo.stats().count)
    }
}
