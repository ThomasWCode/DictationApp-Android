package com.thomaswcode.dictationapp.core.transcription

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Server -> client messages of the AssemblyAI streaming v3 protocol. */
sealed interface StreamingMessage

@Serializable
data class BeginMessage(
    val id: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0,
) : StreamingMessage

@Serializable
data class WordInfo(
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
    val confidence: Double = 0.0,
    @SerialName("word_is_final") val wordIsFinal: Boolean = false,
)

@Serializable
data class TurnMessage(
    @SerialName("turn_order") val turnOrder: Int = 0,
    @SerialName("turn_is_formatted") val turnIsFormatted: Boolean = false,
    @SerialName("end_of_turn") val endOfTurn: Boolean = false,
    /** Finalised words of the turn so far. */
    val transcript: String? = null,
    /** Full text of the turn including unfinalised words (Universal-3.5 Pro). */
    val utterance: String? = null,
    @SerialName("end_of_turn_confidence") val endOfTurnConfidence: Double? = null,
    @SerialName("language_code") val languageCode: String? = null,
    val words: List<WordInfo>? = null,
) : StreamingMessage {
    /** Best available text: utterance, else transcript, else the joined word list. */
    val bestText: String
        get() = when {
            !utterance.isNullOrBlank() -> utterance.trim()
            !transcript.isNullOrBlank() -> transcript.trim()
            !words.isNullOrEmpty() -> words.joinToString(" ") { it.text }.trim()
            else -> ""
        }
}

@Serializable
data class TerminationMessage(
    @SerialName("audio_duration_seconds") val audioDurationSeconds: Double = 0.0,
    @SerialName("session_duration_seconds") val sessionDurationSeconds: Double = 0.0,
) : StreamingMessage

@Serializable
data class ErrorMessage(val error: String? = null) : StreamingMessage

object StreamingMessageParser {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Returns a typed message, or null for message types we do not model (e.g. SpeechStarted). */
    fun parse(text: String): StreamingMessage? {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        val type = (root["type"] as? JsonPrimitive)?.contentOrNull
        // Errors arrive both untyped ({"error": ...}) and typed ({"type":"Error","error": ...}); either must fail fast
        // rather than leave the session waiting for a timeout.
        if (root.containsKey("error") || type.equals("Error", ignoreCase = true)) {
            val detail = root["error"] ?: root["message"]
            return ErrorMessage((detail as? JsonPrimitive)?.contentOrNull ?: detail?.toString() ?: "unknown error")
        }

        return when (type) {
            "Begin" -> json.decodeFromJsonElement(BeginMessage.serializer(), root)
            "Turn" -> json.decodeFromJsonElement(TurnMessage.serializer(), root)
            "Termination" -> json.decodeFromJsonElement(TerminationMessage.serializer(), root)
            else -> null
        }
    }
}
