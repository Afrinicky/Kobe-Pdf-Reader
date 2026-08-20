package com.kobe.reader

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration as WorkConfiguration
import com.kobe.reader.core.diagnostics.CrashReporter
import com.kobe.reader.pdf.render.PageBitmapCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KobeApplication : Application(), WorkConfiguration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var pageCache: PageBitmapCache

    /**
     * WorkManager is configured here rather than by its default initializer so
     * that Hilt can construct `@HiltWorker` workers. The manifest removes the
     * default `WorkManagerInitializer` to match.
     */
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Install the crash catcher as early as possible - before content
        // providers (which include third-party auto-initializers) run - so even
        // a pre-onCreate startup crash is captured for the next launch.
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // PdfBox ships its font metrics and glyph lists as Android assets and
        // cannot find them without this. Skipping it produces a confusing
        // "Could not find AFM resource" the first time a PDF is written. Guarded
        // so a failure here never takes the whole app down on launch.
        runCatching { PDFBoxResourceLoader.init(applicationContext) }
            .onFailure { Log.w("KobeApplication", "PdfBox init failed", it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Page bitmaps are the largest thing we hold and the cheapest to rebuild.
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> pageCache.trim(aggressive = true)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> pageCache.trim(aggressive = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Cached bitmaps are keyed by pixel width; a size change invalidates them.
        pageCache.clear()
    }
}
