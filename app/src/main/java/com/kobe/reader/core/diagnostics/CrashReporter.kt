package com.kobe.reader.core.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures otherwise-invisible crashes so a tester without a computer can still
 * get us the stack trace.
 *
 * On any uncaught exception the full trace is written to a file in internal
 * storage and logged, then the previous handler runs so behaviour is otherwise
 * unchanged. On the next launch [MainActivity] reads the file and shows the
 * trace on screen with a Share button.
 *
 * This is a debug-diagnostics aid; it does not swallow the crash.
 */
object CrashReporter {

    private const val TAG = "KobeCrash"
    private const val CRASH_FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { writer ->
                    PrintWriter(writer).use { throwable.printStackTrace(it) }
                }.toString()

                val report = buildString {
                    appendLine("Kobe PDF Reader crash report")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Time: ${System.currentTimeMillis()}")
                    appendLine()
                    append(trace)
                }

                Log.e(TAG, report)
                File(appContext.filesDir, CRASH_FILE).writeText(report)
            }
            // Let the platform show its normal "app stopped" behaviour.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The last crash report, or null if there is none. */
    fun consume(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CRASH_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, CRASH_FILE).delete() }
    }
}
