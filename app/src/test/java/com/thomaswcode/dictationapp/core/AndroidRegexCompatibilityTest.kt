package com.thomaswcode.dictationapp.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The JVM regex engine accepts things Android's ICU-based one rejects, so these would pass every JVM test and
 * then crash on the phone (it happened: `(?iU)` threw PatternSyntaxException on first use). Scan the sources.
 */
class AndroidRegexCompatibilityTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun sourcesAreFound() = assertTrue("run from the app module", sources.size > 20)

    @Test
    fun noUnicodeCharacterClassFlag() {
        val bad = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*\)""")
        val hits = sources.flatMap { f -> f.readLines().withIndex().filter { !it.value.trimStart().startsWith("//") && bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }
        assertTrue("(?U) is rejected by Android's regex engine: $hits", hits.isEmpty())
    }

    @Test
    fun noNamedGroups() {
        val bad = Regex("""\(\?<[A-Za-z]|groups\["""")
        val hits = sources.flatMap { f -> f.readLines().withIndex().filter { !it.value.trimStart().startsWith("//") && bad.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }
        assertTrue("Named groups are not supported by Kotlin regex on every Android version: $hits", hits.isEmpty())
    }
}
