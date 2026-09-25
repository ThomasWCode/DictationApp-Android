package com.thomaswcode.dictationapp.core.settings

import com.thomaswcode.dictationapp.core.cleanup.CleanupLevel
import com.thomaswcode.dictationapp.core.cleanup.Tone
import com.thomaswcode.dictationapp.core.dictionary.DictionaryTerm
import com.thomaswcode.dictationapp.core.history.RetentionPolicy
import com.thomaswcode.dictationapp.core.insertion.InsertMethod
import com.thomaswcode.dictationapp.core.rules.AppRule
import com.thomaswcode.dictationapp.core.rules.DefaultAppRules
import kotlinx.serialization.Serializable

@Serializable
enum class BubbleSide { Left, Right }

/**
 * Everything the user can configure, serialised to `files/settings.json`. The same settings as the Windows
 * app, except that the hotkey, Flow bar mode, autostart and updater have no Android counterpart; the bubble
 * settings take their place.
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,

    /** AssemblyAI key (transcription), encrypted with an Android Keystore key. Never the plaintext. */
    val apiKeyProtected: String? = null,

    /** Groq key (cleanup and tone), encrypted with an Android Keystore key. */
    val groqApiKeyProtected: String? = null,

    val speechModel: String = SPEECH_MODEL_PRO,

    /** Preferred input device key (see MicrophoneCapture.deviceKey), or null for the default microphone. */
    val microphoneDevice: String? = null,

    val defaultTone: Tone = Tone.Neutral,
    val defaultCleanupLevel: CleanupLevel = CleanupLevel.Light,

    /** OpenAI-compatible chat completions base URL. Groq by default. */
    val llmBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val llmModel: String = DEFAULT_LLM_MODEL,

    /** Tried in order when the primary model fails or is rate-limited. Free-tier Groq models only. */
    val llmFallbackModels: List<String> = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-20b"),

    val storeAudio: Boolean = true,
    val historyRetention: RetentionPolicy = RetentionPolicy.Days14,
    val audioRetention: RetentionPolicy = RetentionPolicy.Days14,

    /** Default way of getting text into a field; app rules can override it. */
    val insertMethod: InsertMethod = InsertMethod.Direct,

    val maxDictationMinutes: Int = 20,

    /** Comma-separated language codes for the streaming session, or null for automatic. */
    val languageCodes: String? = null,

    val firstRunCompleted: Boolean = false,

    // Bubble
    /** Bubble diameter as a percentage of 44 dp. */
    val bubbleSizePercent: Int = 100,
    /** Bubble opacity while idle; it is fully opaque while dictating. */
    val bubbleOpacityPercent: Int = 80,
    /** Shrink the idle bubble after five seconds without use. */
    val bubbleShrinkWhenIdle: Boolean = false,
    /** Show the bubble only while the on-screen keyboard is open (Wispr Flow's behaviour). */
    val bubbleOnlyWithKeyboard: Boolean = true,
    val bubbleSide: BubbleSide = BubbleSide.Right,
    /** Gap between the bubble and the top of the keyboard, in dp. */
    val bubbleOffsetDp: Int = 12,
    /** Vertical position (fraction of screen height) used when no keyboard is showing. */
    val bubbleYFraction: Float = 0.62f,
    val hapticFeedback: Boolean = true,
    /** Package names where the bubble never appears. */
    val hiddenInPackages: List<String> = emptyList(),

    val dictionary: List<DictionaryTerm> = emptyList(),
    val appRules: List<AppRule> = DefaultAppRules.seed(),
) {
    val hasApiKey: Boolean get() = !apiKeyProtected.isNullOrEmpty()

    val hasGroqKey: Boolean get() = !groqApiKeyProtected.isNullOrEmpty()

    val maxDictationMs: Long get() = maxDictationMinutes.coerceIn(1, 180) * 60_000L

    companion object {
        const val SPEECH_MODEL_PRO = "universal-3-5-pro"
        const val SPEECH_MODEL_STANDARD = "universal-streaming"
        const val DEFAULT_LLM_BASE_URL = "https://api.groq.com/openai/v1/"
        const val DEFAULT_LLM_MODEL = "openai/gpt-oss-120b"
        const val BUBBLE_BASE_DP = 44
        const val BUBBLE_MIN_PERCENT = 50
        const val BUBBLE_MAX_PERCENT = 160
        const val OPACITY_MIN_PERCENT = 20
    }
}
