package com.kobe.reader.core.pdf

/**
 * Parses the page-range syntax users type into the split/extract/delete tools:
 * `1-3, 5, 8-10`, `2`, `4-`, `-6`, `1-3;5`.
 *
 * Everything here is 1-based because that is what the user sees and types.
 * Callers convert to 0-based indices at the PDF boundary via [toZeroBased].
 */
object PageSelection {

    private val SEPARATOR = Regex("[,;\\s]+")

    /**
     * @param input raw text from the user
     * @param pageCount the document's page count, used to resolve open-ended
     *   ranges (`4-` means "4 to the end") and to reject out-of-bounds numbers
     * @return sorted, de-duplicated 1-based page numbers, or `null` if [input]
     *   is not valid. `null` rather than an empty list so that "typed nothing
     *   yet" and "typed nonsense" stay distinguishable.
     */
    fun parse(input: String, pageCount: Int): List<Int>? {
        if (pageCount <= 0) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val pages = sortedSetOf<Int>()
        for (token in trimmed.split(SEPARATOR)) {
            if (token.isEmpty()) continue
            val range = parseToken(token, pageCount) ?: return null
            pages.addAll(range)
        }
        return pages.toList().takeIf { it.isNotEmpty() }
    }

    private fun parseToken(token: String, pageCount: Int): IntRange? {
        val dash = token.indexOf('-')

        // A bare number: "7"
        if (dash < 0) {
            val page = token.toIntOrNull() ?: return null
            return if (page in 1..pageCount) page..page else null
        }

        // "-6" -> 1..6
        if (dash == 0) {
            val end = token.substring(1).toIntOrNull() ?: return null
            return if (end in 1..pageCount) 1..end else null
        }

        // "4-" -> 4..pageCount
        if (dash == token.lastIndex) {
            val start = token.dropLast(1).toIntOrNull() ?: return null
            return if (start in 1..pageCount) start..pageCount else null
        }

        // "2-9"
        val start = token.substring(0, dash).toIntOrNull() ?: return null
        val end = token.substring(dash + 1).toIntOrNull() ?: return null
        if (start !in 1..pageCount || end !in 1..pageCount) return null
        // Accept a reversed range rather than rejecting it - "9-2" is a typo
        // with an obvious intent, and refusing it just annoys people.
        return if (start <= end) start..end else end..start
    }

    /** Renders page numbers back into the compact form, e.g. `1-3, 7, 10-12`. */
    fun format(pages: Collection<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.toSortedSet().toList()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = start

        for (page in sorted.drop(1)) {
            if (page == previous + 1) {
                previous = page
                continue
            }
            parts += renderRange(start, previous)
            start = page
            previous = page
        }
        parts += renderRange(start, previous)
        return parts.joinToString(", ")
    }

    private fun renderRange(start: Int, end: Int): String =
        if (start == end) start.toString() else "$start-$end"

    /** Converts 1-based page numbers to 0-based indices for the PDF layer. */
    fun toZeroBased(pages: Collection<Int>): List<Int> = pages.map { it - 1 }

    /** The complement of [pages] within a document of [pageCount] pages. */
    fun invert(pages: Collection<Int>, pageCount: Int): List<Int> {
        val excluded = pages.toSet()
        return (1..pageCount).filterNot { it in excluded }
    }
}
