package com.kobe.reader.core.common

/**
 * Progress for a long-running PDF operation.
 *
 * [current]/[total] are page or file counts, not bytes: they are what a progress
 * label can honestly say ("Page 12 of 340"). [total] is 0 when the work length
 * isn't knowable up front, which the UI renders as an indeterminate bar.
 */
data class Progress(
    val current: Int = 0,
    val total: Int = 0,
) {
    val isDeterminate: Boolean get() = total > 0
    val fraction: Float get() = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f

    companion object {
        val Indeterminate = Progress()
    }
}

/** Callback handed to toolkit operations so they can report page-level progress. */
fun interface ProgressReporter {
    fun report(current: Int, total: Int)

    companion object {
        val None = ProgressReporter { _, _ -> }
    }
}
