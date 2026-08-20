package com.kobe.reader.core.diagnostics

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shows a captured crash on screen with a Share button.
 *
 * Deliberately built from plain framework Views, no Hilt, no Compose, no app
 * theme - so it can render even when the thing that crashed is Hilt, Compose, or
 * the theme itself. Runs in its own process (see the manifest) so it survives
 * the death of the process that crashed.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "No crash details were captured."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "Kobe PDF Reader stopped"
            setTextColor(Color.parseColor("#D0271D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }

        val subtitle = TextView(this).apply {
            text = "Please tap Share and send this report to the developer."
            setTextColor(Color.DKGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(12))
        }

        val shareButton = Button(this).apply {
            text = "Share crash report"
            setOnClickListener { shareReport(trace) }
        }

        val body = TextView(this).apply {
            text = trace
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }

        val scroll = ScrollView(this).apply { addView(body) }

        root.addView(title)
        root.addView(subtitle)
        root.addView(shareButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun shareReport(trace: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kobe PDF Reader crash report")
            putExtra(Intent.EXTRA_TEXT, trace)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share crash report")) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TRACE = "com.kobe.reader.EXTRA_TRACE"
    }
}
