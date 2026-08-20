package com.kobe.reader.core.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Captures otherwise-invisible startup crashes so a tester without a computer
 * can still get us the stack trace.
 *
 * Installed from [android.app.Application.attachBaseContext] - the earliest
 * point in the process lifecycle - so it catches failures in ContentProviders
 * (the ad SDK auto-initialiser, for one) and in `Application.onCreate`, not just
 * crashes that happen once an Activity is up.
 *
 * On an uncaught exception it writes the trace to a file, launches
 * [CrashActivity] in a separate process to show it on screen with a Share
 * button, then ends the broken process. This is a debug diagnostic; it does not
 * pretend the crash didn't happen.
 */
object CrashReporter {

    private const val TAG = "KobeCrash"
    const val CRASH_FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = runCatching {
                StringWriter().also { writer ->
                    PrintWriter(writer).use { throwable.printStackTrace(it) }
                }.toString()
            }.getOrDefault(throwable.toString())

            val report = buildString {
                appendLine("Kobe PDF Reader crash report")
                appendLine("Thread: ${thread.name}")
                appendLine("App: ${appContext.packageName}")
                appendLine()
                append(trace)
            }

            runCatching { Log.e(TAG, report) }
            runCatching { File(appContext.filesDir, CRASH_FILE).writeText(report) }

            runCatching {
                val intent = Intent(appContext, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_TRACE, report)
                }
                appContext.startActivity(intent)
            }

            // The process is broken; end it rather than let the framework loop.
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /**
     * The last crash report, or null if there is none. Used by the in-app
     * fallback display, in case the separate-process [CrashActivity] could not be
     * launched (some OEMs block activity starts from a dying process).
     */
    fun consume(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CRASH_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, CRASH_FILE).delete() }
    }
}
