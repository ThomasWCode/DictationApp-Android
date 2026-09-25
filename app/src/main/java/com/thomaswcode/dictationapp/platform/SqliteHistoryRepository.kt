package com.thomaswcode.dictationapp.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.history.DictationRecord
import com.thomaswcode.dictationapp.core.history.HistoryRepository
import com.thomaswcode.dictationapp.core.history.HistoryStats
import com.thomaswcode.dictationapp.core.history.RecordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SQLite (WAL) with an FTS4 external-content index kept in sync by triggers, the Android counterpart of the
 * Windows FTS5 store: the platform SQLite always has FTS4, FTS5 is not guaranteed. Migrations are versioned
 * SQL scripts applied by [SQLiteOpenHelper]'s onCreate/onUpgrade.
 */
class SqliteHistoryRepository(context: Context, name: String? = DB_NAME) : HistoryRepository {
    private val helper = object : SQLiteOpenHelper(context, name, null, MIGRATIONS.size) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.enableWriteAheadLogging()
        }

        override fun onCreate(db: SQLiteDatabase) = migrate(db, 0)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion)

        private fun migrate(db: SQLiteDatabase, from: Int) {
            for (v in from until MIGRATIONS.size) {
                MIGRATIONS[v].forEach { db.execSQL(it.trimIndent()) }
            }
        }
    }

    private suspend fun <T> io(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) { block(helper.writableDatabase) }

    override suspend fun initialise() {
        io { }
    }

    override suspend fun insert(record: DictationRecord): Long = io { db ->
        db.insertOrThrow("dictations", null, values(record)).also { record.id = it }
    }

    override suspend fun update(record: DictationRecord) = io { db ->
        db.update("dictations", values(record), "id = ?", arrayOf(record.id.toString()))
        Unit
    }

    override suspend fun get(id: Long): DictationRecord? = io { db ->
        db.rawQuery("SELECT $COLUMNS FROM dictations WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) read(c) else null }
    }

    override suspend fun search(query: String?, limit: Int): List<DictationRecord> = io { db ->
        val match = query?.let(::toMatchExpression)
        val cursor = if (match == null) {
            db.rawQuery("SELECT $COLUMNS FROM dictations ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString()))
        } else {
            db.rawQuery(
                "SELECT $COLUMNS FROM dictations WHERE id IN (SELECT docid FROM dictations_fts WHERE dictations_fts MATCH ?) ORDER BY created_at DESC LIMIT ?",
                arrayOf(match, limit.toString()),
            )
        }
        cursor.use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
    }

    override suspend fun latest(): DictationRecord? = search(null, 1).firstOrNull()

    override suspend fun delete(id: Long) = io { db ->
        db.delete("dictations", "id = ?", arrayOf(id.toString()))
        Unit
    }

    override suspend fun deleteOlderThan(olderThan: Long): List<String> = io { db ->
        db.beginTransaction()
        try {
            val paths = audioPaths(db, "created_at < ? AND audio_path IS NOT NULL", arrayOf(olderThan.toString()))
            db.delete("dictations", "created_at < ?", arrayOf(olderThan.toString()))
            db.setTransactionSuccessful()
            paths
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun clearAudioOlderThan(olderThan: Long): List<String> = io { db ->
        db.beginTransaction()
        try {
            val paths = audioPaths(db, "created_at < ? AND audio_path IS NOT NULL", arrayOf(olderThan.toString()))
            db.update("dictations", ContentValues().apply { putNull("audio_path") }, "created_at < ? AND audio_path IS NOT NULL", arrayOf(olderThan.toString()))
            db.setTransactionSuccessful()
            paths
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun stats(): HistoryStats = io { db ->
        val (count, cost, withAudio) = db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(cost_estimate), 0), COALESCE(SUM(CASE WHEN audio_path IS NOT NULL THEN 1 ELSE 0 END), 0) FROM dictations",
            null,
        ).use { c ->
            c.moveToFirst()
            Triple(c.getInt(0), c.getDouble(1), c.getInt(2))
        }
        val bytes = audioPaths(db, "audio_path IS NOT NULL", null).sumOf { File(it).length() }
        HistoryStats(count, cost, bytes, withAudio)
    }

    override suspend fun deleteAll(): List<String> = io { db ->
        val paths = audioPaths(db, "audio_path IS NOT NULL", null)
        db.delete("dictations", null, null)
        paths
    }

    override suspend fun referencedAudioPaths(): Set<String> = io { db -> audioPaths(db, "audio_path IS NOT NULL", null).toSet() }

    private fun audioPaths(db: SQLiteDatabase, where: String, args: Array<String>?): List<String> =
        db.rawQuery("SELECT audio_path FROM dictations WHERE $where", args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun values(r: DictationRecord) = ContentValues().apply {
        put("created_at", r.createdAt)
        put("updated_at", r.updatedAt)
        put("raw_transcript", r.rawTranscript)
        put("cleaned_text", r.cleanedText)
        put("inserted_text", r.insertedText)
        put("tone", r.tone.name)
        put("level", r.level.name)
        put("package_name", r.packageName)
        put("app_label", r.appLabel)
        put("window_title", r.windowTitle)
        put("url", r.url)
        put("duration_ms", r.durationMs)
        put("audio_path", r.audioPath)
        put("cost_estimate", r.costEstimate)
        put("status", r.status.name)
        put("llm_model", r.llmModel)
        put("failure_reason", r.failureReason)
        put("ai_edit_undone", if (r.aiEditUndone) 1 else 0)
    }

    private fun read(c: Cursor) = DictationRecord(
        id = c.getLong(0),
        createdAt = c.getLong(1),
        updatedAt = c.getLong(2),
        rawTranscript = c.getString(3).orEmpty(),
        cleanedText = c.getString(4).orEmpty(),
        insertedText = c.getString(5).orEmpty(),
        tone = enumOr(c.getString(6), Tone.Neutral),
        level = enumOr(c.getString(7), CleanupLevel.Light),
        packageName = c.getString(8),
        appLabel = c.getString(9),
        windowTitle = c.getString(10),
        url = c.getString(11),
        durationMs = c.getInt(12),
        audioPath = c.getString(13),
        costEstimate = if (c.isNull(14)) null else c.getDouble(14),
        status = enumOr(c.getString(15), RecordStatus.Failed),
        llmModel = c.getString(16),
        failureReason = c.getString(17),
        aiEditUndone = c.getInt(18) != 0,
    )

    companion object {
        const val DB_NAME = "history.db"

        private const val COLUMNS =
            "id, created_at, updated_at, raw_transcript, cleaned_text, inserted_text, tone, level, package_name, app_label, window_title, url, duration_ms, audio_path, cost_estimate, status, llm_model, failure_reason, ai_edit_undone"

        /** One list of statements per schema version; SQLiteOpenHelper records the version reached. */
        private val MIGRATIONS: List<List<String>> = listOf(
            // v1
            listOf(
                """
                CREATE TABLE dictations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    raw_transcript TEXT NOT NULL,
                    cleaned_text TEXT NOT NULL DEFAULT '',
                    inserted_text TEXT NOT NULL DEFAULT '',
                    tone TEXT NOT NULL,
                    level TEXT NOT NULL,
                    package_name TEXT,
                    app_label TEXT,
                    window_title TEXT,
                    url TEXT,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    audio_path TEXT,
                    cost_estimate REAL,
                    status TEXT NOT NULL,
                    llm_model TEXT,
                    failure_reason TEXT,
                    ai_edit_undone INTEGER NOT NULL DEFAULT 0
                )
                """,
                "CREATE INDEX ix_dictations_created ON dictations(created_at)",
                """
                CREATE VIRTUAL TABLE dictations_fts USING fts4(
                    content="dictations", raw_transcript, cleaned_text, inserted_text, window_title, app_label
                )
                """,
                """
                CREATE TRIGGER dictations_bu BEFORE UPDATE ON dictations BEGIN
                    DELETE FROM dictations_fts WHERE docid = old.rowid;
                END
                """,
                """
                CREATE TRIGGER dictations_bd BEFORE DELETE ON dictations BEGIN
                    DELETE FROM dictations_fts WHERE docid = old.rowid;
                END
                """,
                """
                CREATE TRIGGER dictations_au AFTER UPDATE ON dictations BEGIN
                    INSERT INTO dictations_fts(docid, raw_transcript, cleaned_text, inserted_text, window_title, app_label)
                    VALUES (new.rowid, new.raw_transcript, new.cleaned_text, new.inserted_text, new.window_title, new.app_label);
                END
                """,
                """
                CREATE TRIGGER dictations_ai AFTER INSERT ON dictations BEGIN
                    INSERT INTO dictations_fts(docid, raw_transcript, cleaned_text, inserted_text, window_title, app_label)
                    VALUES (new.rowid, new.raw_transcript, new.cleaned_text, new.inserted_text, new.window_title, new.app_label);
                END
                """,
            ),
        )

        /**
         * Free text to a safe FTS4 expression: each word becomes a quoted prefix term (`"isoni*"`), so user
         * input can never break the query syntax. Returns null for a blank query.
         */
        fun toMatchExpression(query: String): String? {
            val terms = query.split(Regex("\\s+"))
                .map { it.replace("\"", "").trim() }
                .filter { it.isNotEmpty() }
            if (terms.isEmpty()) return null
            return terms.joinToString(" ") { "\"$it*\"" }
        }

        private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: default
    }
}
