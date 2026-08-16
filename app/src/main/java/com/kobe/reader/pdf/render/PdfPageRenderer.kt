package com.kobe.reader.pdf.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.IntRange
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.raise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Renders pages with the platform [PdfRenderer].
 *
 * Why the platform renderer and not PdfBox: `PdfRenderer` is backed by the same
 * native PDFium the system PDF viewer uses. It is an order of magnitude faster
 * than rasterising through PdfBox on Android and it does not hold the whole page
 * tree in the Java heap - which is what keeps a 400-page document usable on a
 * 2 GB phone. PdfBox is still used for *structural* work (see PdfToolkit); the
 * two never touch the same open handle.
 *
 * Thread safety: `PdfRenderer` allows exactly one open page at a time and is not
 * thread safe. The [mutex] serialises every access, so callers can render
 * thumbnails concurrently without corrupting state.
 */
class PdfPageRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val mutex = Mutex()

    @Volatile
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /**
     * Point size (1/72 inch) of a page, used to preserve aspect ratio before
     * committing to a bitmap allocation.
     */
    suspend fun pageSize(@IntRange(from = 0) index: Int): PageSize = mutex.withLock {
        ensureOpen()
        val page = renderer.openPage(index)
        try {
            PageSize(page.width, page.height)
        } finally {
            page.close()
        }
    }

    /**
     * Renders page [index] into a bitmap [targetWidth] pixels wide, keeping the
     * page's aspect ratio.
     *
     * @param forPrint uses [PdfRenderer.Page.RENDER_MODE_FOR_PRINT], which
     *   disables screen-oriented anti-aliasing tweaks. Used when exporting to
     *   images so the output matches what a print would look like.
     */
    suspend fun renderPage(
        @IntRange(from = 0) index: Int,
        targetWidth: Int,
        forPrint: Boolean = false,
    ): Bitmap = mutex.withLock {
        ensureOpen()
        require(targetWidth > 0) { "targetWidth must be positive" }

        val page = renderer.openPage(index)
        try {
            val width = targetWidth.coerceAtMost(MAX_BITMAP_EDGE)
            val height = ((width.toLong() * page.height) / page.width.coerceAtLeast(1))
                .toInt()
                .coerceIn(1, MAX_BITMAP_EDGE)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PdfRenderer composites onto whatever is already in the bitmap, so a
            // page with a transparent background would otherwise come out black.
            bitmap.eraseColor(Color.WHITE)

            val mode = if (forPrint) {
                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
            } else {
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            }
            page.render(bitmap, null, null, mode)
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM rendering page $index at width $targetWidth", e)
            KobeError.OutOfMemory.raise()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "renderer state error on page $index", e)
            KobeError.CorruptedDocument.raise()
        } finally {
            page.close()
        }
    }

    private fun ensureOpen() {
        if (closed) KobeError.FileMissing.raise()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    data class PageSize(val widthPoints: Int, val heightPoints: Int) {
        val aspectRatio: Float get() = widthPoints.toFloat() / heightPoints.coerceAtLeast(1)
        val isLandscape: Boolean get() = widthPoints > heightPoints
    }

    companion object {
        private const val TAG = "PdfPageRenderer"

        /**
         * Android refuses to draw bitmaps beyond the GPU texture limit, and a
         * bitmap this wide is already ~100 MB. Zoom is handled by re-rendering
         * a crop rather than by making one enormous bitmap.
         */
        const val MAX_BITMAP_EDGE = 4_096

        /**
         * Opens [file] for rendering.
         *
         * The file must be a real seekable file - see
         * [com.kobe.reader.core.file.DocumentStore.materialise] for turning a
         * `content://` URI into one.
         */
        fun open(file: File): PdfPageRenderer {
            if (!file.exists() || file.length() == 0L) KobeError.FileMissing.raise()
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                Log.w(TAG, "cannot open ${file.name}", e)
                KobeError.FileMissing.raise()
            }
            return try {
                PdfPageRenderer(descriptor, PdfRenderer(descriptor))
            } catch (e: SecurityException) {
                // PdfRenderer throws SecurityException for password-protected files.
                runCatching { descriptor.close() }
                Log.i(TAG, "password protected: ${file.name}")
                KobeError.PasswordRequired.raise()
            } catch (e: IOException) {
                runCatching { descriptor.close() }
                Log.w(TAG, "not a readable PDF: ${file.name}", e)
                KobeError.CorruptedDocument.raise()
            }
        }
    }
}
