package com.thomaswcode.dictationapp.core.cleanup

/**
 * Pure. Turns spoken enumerations into a numbered list, one item per line. Recognised markers are
 * "1." / "1)" / "1:" at a word boundary and the phrases "number one", "point one", "item one" (words or
 * digits). A list needs at least two markers in ascending order starting at 1, so "version 1.2" and a lone
 * "number one priority" are left alone. Trailing "and" / "then" / "," before the next item is dropped.
 */
object ListFormatter {
    private val words = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    private val marker = Regex(
        """(?iU)(?<![\w.,])(?:(?<d>\d{1,2})[.):](?=\s)|\b(?:number|point|item)\s+(?<w>one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|\d{1,2})\b(?![.,]?\d))""",
    )

    private val trailingJoiner = Regex("""(?iU)[\s,]*\b(?:and then|and|then)\b[\s,]*$|[\s,]+$""")

    private data class Marker(val index: Int, val length: Int, val value: Int)

    fun format(text: String): String {
        if (text.isBlank()) {
            return text
        }

        val markers = marker.findAll(text)
            .map { Marker(it.range.first, it.value.length, valueOf(it)) }
            .filter { it.value > 0 }
            .toList()
        if (markers.size < 2) {
            return text
        }

        // Collect ascending runs 1, 2, 3, ... in text order; a run of at least two markers is a list.
        val runs = mutableListOf<MutableList<Marker>>()
        var current: MutableList<Marker>? = null
        for (m in markers) {
            if (m.value == 1) {
                current = mutableListOf(m)
                runs.add(current)
            } else if (current != null && m.value == current.last().value + 1) {
                current.add(m)
            } else {
                current = null
            }
        }

        val listMarkers = runs.filter { it.size >= 2 }.flatten().sortedBy { it.index }
        if (listMarkers.isEmpty()) {
            return text
        }

        val sb = StringBuilder(text.length + listMarkers.size * 4)
        var pos = 0
        for (m in listMarkers) {
            sb.append(trailingJoiner.replace(text.substring(pos, m.index), ""))
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                sb.append('\n')
            }

            sb.append(m.value).append(". ")
            pos = m.index + m.length
            // Skip whitespace and a stray punctuation mark right after the marker ("number one, buy milk").
            while (pos < text.length && (text[pos].isWhitespace() || text[pos] == ',' || text[pos] == ':')) {
                pos++
            }
        }

        sb.append(text.substring(pos))
        return sb.toString().trim()
    }

    private fun valueOf(m: MatchResult): Int {
        m.groups["d"]?.let { return it.value.toInt() }
        val w = m.groups["w"]?.value ?: return 0
        return w.toIntOrNull() ?: words[w.lowercase()] ?: 0
    }
}
