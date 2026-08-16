package com.kobe.reader.core.common

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import java.util.concurrent.TimeUnit

/** Human-readable file sizes, using the same units the system file picker does. */
fun Long.asFileSize(context: Context): String =
    if (this <= 0L) "—" else Formatter.formatShortFileSize(context, this)

/**
 * "2 hours ago", "Yesterday", "16 Aug" - relative for the recent past, absolute
 * once relative stops being useful.
 */
fun Long.asRelativeTime(context: Context): String {
    if (this <= 0L) return ""
    val now = System.currentTimeMillis()
    val age = now - this
    return if (age < TimeUnit.DAYS.toMillis(RELATIVE_DAYS)) {
        DateUtils.getRelativeTimeSpanString(
            this,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    } else {
        DateUtils.formatDateTime(
            context,
            this,
            DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
}

/** Percentage saved by a compression run, floored at 0. */
fun compressionSavingPercent(originalBytes: Long, resultBytes: Long): Int {
    if (originalBytes <= 0L || resultBytes >= originalBytes) return 0
    return (((originalBytes - resultBytes).toDouble() / originalBytes) * 100).toInt()
}

private const val RELATIVE_DAYS = 6L
