package com.kobe.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kobe.reader.core.diagnostics.CrashReporter
import com.kobe.reader.data.prefs.KobeSettings
import com.kobe.reader.data.prefs.SettingsRepository
import com.kobe.reader.monetization.ads.AdsController
import com.kobe.reader.ui.CrashReportScreen
import com.kobe.reader.ui.KobeApp
import com.kobe.reader.ui.theme.KobeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var ads: AdsController

    /** A PDF handed to us by another app, consumed once by the nav host. */
    private val pendingDocument = MutableStateFlow<Uri?>(null)
    val incomingDocument: StateFlow<Uri?> = pendingDocument.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate to hand the splash over cleanly.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // If the previous run crashed, show the captured stack trace instead of
        // the app, with a Share button, so a tester without a computer can send
        // it to us. Dismissing clears it and returns to the app on next launch.
        val crash = CrashReporter.consume(this)
        if (crash != null) {
            setContent {
                KobeTheme {
                    CrashReportScreen(
                        report = crash,
                        onShare = { shareText(crash) },
                        onDismiss = {
                            CrashReporter.clear(this)
                            recreate()
                        },
                    )
                }
            }
            return
        }

        val settings = settingsRepository.settings.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
        // Hold the splash only until the theme is known, so the app never paints
        // light-then-dark on a device set to dark mode.
        splash.setKeepOnScreenCondition { settings.value == null }

        handleIntent(intent)
        // The ad SDK reaches out to Google Play Services at startup; never let a
        // failure there crash the app before the user sees a single screen.
        runCatching { ads.prepare() }

        setContent {
            val current by settings.collectAsStateWithLifecycle()
            val resolved = current ?: KobeSettings()

            KobeTheme(
                themePreference = resolved.theme,
                useDynamicColor = resolved.useDynamicColor,
            ) {
                KobeApp(
                    settings = resolved,
                    incomingDocument = incomingDocument,
                    onDocumentConsumed = { pendingDocument.value = null },
                )
            }
        }
    }

    /**
     * `singleTask` means a second "open with" arrives here rather than in a new
     * Activity. Both paths must parse identically, hence one shared handler.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtraCompat(Intent.EXTRA_STREAM)
            else -> null
        } ?: return

        // The sending app granted us a one-shot read; take it before the
        // Activity is recreated and the grant evaporates.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pendingDocument.value = uri
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kobe PDF Reader crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share crash report")) }
    }

    private fun Intent.getParcelableExtraCompat(name: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
}
