package com.thomaswcode.dictationapp.core.history

import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

enum class RecordStatus { Pending, Inserted, CopiedOnly, Failed }

@Serializable
enum class RetentionPolicy(val label: String, val millis: Long?) {
    Hours24("24 hours", 24L * 60 * 60 * 1000),
    Days14("14 days", 14L * 24 * 60 * 60 * 1000),
    Forever("Forever", null),
}

/** One dictation. Mirrors the Windows record; [packageName] is the Android counterpart of the process name. */
data class DictationRecord(
    var id: Long = 0,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var rawTranscript: String = "",
    /** LLM output, or empty when cleanup did not run or was skipped. */
    var cleanedText: String = "",
    /** What actually went into the field (cleaned text, or raw when cleanup was skipped or undone). */
    var insertedText: String = "",
    var tone: Tone = Tone.Neutral,
    var level: CleanupLevel = CleanupLevel.Light,
    var packageName: String? = null,
    var appLabel: String? = null,
    var windowTitle: String? = null,
    var url: String? = null,
    var durationMs: Int = 0,
    var audioPath: String? = null,
    var costEstimate: Double? = null,
    var status: RecordStatus = RecordStatus.Pending,
    var llmModel: String? = null,
    var failureReason: String? = null,
    var aiEditUndone: Boolean = false,
) {
    val hasAudio: Boolean get() = !audioPath.isNullOrEmpty()

    val canUndoAiEdit: Boolean get() = !aiEditUndone && cleanedText.isNotEmpty() && cleanedText != rawTranscript

    val displayText: String get() = insertedText.ifEmpty { rawTranscript }

    val appName: String get() = appLabel?.takeIf { it.isNotBlank() } ?: packageName.orEmpty()
}

data class HistoryStats(val count: Int, val totalCost: Double, val totalAudioBytes: Long, val withAudio: Int)

interface HistoryRepository {
    suspend fun initialise()

    suspend fun insert(record: DictationRecord): Long

    suspend fun update(record: DictationRecord)

    suspend fun get(id: Long): DictationRecord?

    /** Newest first. A null or blank query lists everything; otherwise prefix full-text search. */
    suspend fun search(query: String?, limit: Int = 200): List<DictationRecord>

    suspend fun latest(): DictationRecord?

    suspend fun delete(id: Long)

    /** Deletes records created before [olderThan]; returns the audio paths they referenced. */
    suspend fun deleteOlderThan(olderThan: Long): List<String>

    /** Clears the audio path on records created before [olderThan]; returns the paths cleared. */
    suspend fun clearAudioOlderThan(olderThan: Long): List<String>

    suspend fun stats(): HistoryStats

    /** Deletes every record; returns the audio paths they referenced. */
    suspend fun deleteAll(): List<String>

    /** Every audio path still referenced (for the orphaned-file sweep). */
    suspend fun referencedAudioPaths(): Set<String>
}

/**
 * Best-effort USD estimate shown in History. Streaming is billed per audio hour; cleanup runs on Groq's
 * free tier, so every default model costs nothing. Unknown models estimate as zero.
 */
object CostEstimator {
    private val sttPerHour = mapOf("universal-3-5-pro" to 0.45, "universal-streaming" to 0.15)

    // (input, output) USD per 1M tokens, for anyone pointing the endpoint at a paid service.
    private val llmPerMillion = mapOf(
        "qwen/qwen3.8-27b" to (0.0 to 0.0),
        "openai/gpt-oss-120b" to (0.0 to 0.0),
        "openai/gpt-oss-20b" to (0.0 to 0.0),
    )

    fun sttCost(speechModel: String, audioSeconds: Double): Double {
        val rate = sttPerHour[speechModel.lowercase()] ?: 0.45
        return round6(rate * audioSeconds / 3600.0)
    }

    fun llmCost(model: String?, promptTokens: Int?, completionTokens: Int?): Double {
        val price = llmPerMillion[model?.lowercase() ?: return 0.0] ?: return 0.0
        return round6(((promptTokens ?: 0) * price.first + (completionTokens ?: 0) * price.second) / 1_000_000.0)
    }

    private fun round6(v: Double): Double = (v * 1_000_000).roundToLong() / 1_000_000.0
}
