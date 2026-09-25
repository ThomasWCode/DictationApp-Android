package com.thomaswcode.dictationapp.core.transcription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Query-string parameters for one streaming session. Immutable; built per dictation. */
data class SessionOptions(
    val speechModel: String = "universal-3-5-pro",
    val sampleRate: Int = 16_000,
    val encoding: String = "pcm_s16le",
    /** Free-text context (Pro only), at most 1750 characters. */
    val prompt: String? = null,
    val keyterms: List<String> = emptyList(),
    val languageCodes: String? = null,
    val minTurnSilenceMs: Int? = null,
    val maxTurnSilenceMs: Int? = null,
    val vadThreshold: Double? = null,
    val inactivityTimeoutSeconds: Int? = null,
    val formatTurns: Boolean = true,
) {
    val isPro: Boolean get() = speechModel.contains("pro", ignoreCase = true)

    fun buildQueryString(): String {
        val sb = StringBuilder()
        fun add(key: String, value: String) {
            sb.append(if (sb.isEmpty()) '?' else '&').append(key).append('=').append(escapeDataString(value))
        }

        add("sample_rate", sampleRate.toString())
        add("speech_model", speechModel)
        add("encoding", encoding)
        if (formatTurns) add("format_turns", "true")
        if (!prompt.isNullOrBlank() && isPro) add("prompt", prompt.take(MAX_PROMPT_LENGTH))
        if (keyterms.isNotEmpty()) add("keyterms_prompt", keytermsJson(keyterms))
        if (!languageCodes.isNullOrBlank()) add("language_codes", languageCodes)
        minTurnSilenceMs?.let { add("min_turn_silence", it.toString()) }
        maxTurnSilenceMs?.let { add("max_turn_silence", it.toString()) }
        vadThreshold?.let { add("vad_threshold", DecimalFormat("0.###", DecimalFormatSymbols(Locale.ROOT)).format(it)) }
        inactivityTimeoutSeconds?.let { add("inactivity_timeout", it.toString()) }
        return sb.toString()
    }

    companion object {
        const val MAX_PROMPT_LENGTH = 1750

        fun keytermsJson(keyterms: List<String>): String = JsonArray(keyterms.map { JsonPrimitive(it) }).toString()

        /** RFC 3986 percent-encoding, the same as .NET's Uri.EscapeDataString (space is %20, never +). */
        fun escapeDataString(value: String): String {
            val sb = StringBuilder(value.length + 16)
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                    sb.append(ch)
                } else {
                    sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
                }
            }

            return sb.toString()
        }

        private val HEX = "0123456789ABCDEF".toCharArray()
    }
}
