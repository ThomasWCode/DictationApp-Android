package com.thomaswcode.dictationapp.core.cleanup

import kotlinx.serialization.Serializable

@Serializable
enum class CleanupLevel {
    /** Spoken-command normalisation only; no LLM call unless the tone is not Neutral. */
    None,

    /** Fillers, false starts and self-corrections removed; punctuation fixed. No rephrasing. */
    Light,

    /** Light plus grammar fixes, redundancy removal and run-on splitting. */
    Medium,

    /** Medium plus tightened wording, merged fragments and paragraphing. */
    High,
    ;

    fun next(): CleanupLevel = entries[(ordinal + 1) % entries.size]

    fun previous(): CleanupLevel = entries[(ordinal + entries.size - 1) % entries.size]
}

@Serializable
enum class Tone {
    Neutral,
    Formal,
    Casual,
    ;

    fun next(): Tone = entries[(ordinal + 1) % entries.size]

    fun previous(): Tone = entries[(ordinal + entries.size - 1) % entries.size]
}
