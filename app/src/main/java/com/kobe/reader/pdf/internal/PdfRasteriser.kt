package com.kobe.reader.pdf.internal

import android.graphics.Bitmap
import android.util.Log
import com.kobe.reader.core.common.ProgressReporter
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.raise
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.core.file.FileNaming
import com.kobe.reader.pdf.ImageFormat
import com.kobe.reader.pdf.PdfToImagesOptions
import com.kobe.reader.pdf.render.PdfPageRenderer
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Turns PDF pages into image files.
 *
 * Uses the platform [PdfPageRenderer] rather than PdfBox: PDFBox's own
 * `PDFRenderer` is not available in the Android port, and even where it is, the
 * native renderer is dramatically faster and uses a fraction of the heap. That
 * matters most here, where a 50-page export means 50 full-resolution bitmaps
 * one after another.
 */
@Singleton
class PdfRasteriser @Inject constructor(
    private val store: DocumentStore,
) {

    /**
     * @param pages 1-based page numbers; empty means every page
     * @return one image file per page, in page order
     */
    suspend fun rasterise(
        source: File,
        pages: List<Int>,
        options: PdfToImagesOptions,
        outputName: String,
        progress: ProgressReporter,
    ): List<File> {
        val written = mutableListOf<File>()
        val renderer = PdfPageRenderer.open(source)

        try {
            val selected = if (pages.isEmpty()) {
                (1..renderer.pageCount).toList()
            } else {
                pages.filter { it in 1..renderer.pageCount }.distinct().sorted()
            }
            if (selected.isEmpty()) KobeError.Unexpected().raise()

            // Rough estimate: an A4 page at the chosen DPI, times the page count.
            store.requireSpaceFor(estimateBytes(selected.size, options))

            selected.forEachIndexed { index, page ->
                coroutineContext.ensureActive()

                val size = renderer.pageSize(page - 1)
                val widthPx = ((size.widthPoints / POINTS_PER_INCH) * options.dpi)
                    .toInt()
                    .coerceIn(MIN_WIDTH_PX, PdfPageRenderer.MAX_BITMAP_EDGE)

                val bitmap = renderer.renderPage(page - 1, widthPx, forPrint = true)
                try {
                    written += writeImage(bitmap, outputName, page, options)
                } finally {
                    bitmap.recycle()
                }
                progress.report(index + 1, selected.size)
            }
            return written
        } catch (throwable: Throwable) {
            written.forEach { it.delete() }
            throw throwable
        } finally {
            renderer.close()
        }
    }

    private fun writeImage(
        bitmap: Bitmap,
        outputName: String,
        page: Int,
        options: PdfToImagesOptions,
    ): File {
        val target = store.newOutputFile(
            FileNaming.indexed(outputName, page),
            options.format.extension,
        )
        val format = when (options.format) {
            ImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
            ImageFormat.Png -> Bitmap.CompressFormat.PNG
        }

        target.outputStream().buffered(BUFFER_BYTES).use { output ->
            // PNG ignores the quality argument, which is fine - it is lossless.
            if (!bitmap.compress(format, options.quality.coerceIn(1, 100), output)) {
                Log.w(TAG, "compress returned false for page $page")
                KobeError.Unexpected().raise()
            }
        }
        return target
    }

    private fun estimateBytes(pageCount: Int, options: PdfToImagesOptions): Long {
        val pixelsPerPage = (A4_WIDTH_INCHES * options.dpi) * (A4_HEIGHT_INCHES * options.dpi)
        val bytesPerPixel = when (options.format) {
            // JPEG at typical quality lands near a tenth of a byte per pixel;
            // PNG on a text page compresses well but far less predictably.
            ImageFormat.Jpeg -> 0.12
            ImageFormat.Png -> 0.6
        }
        return (pixelsPerPage * bytesPerPixel * pageCount).toLong()
    }

    private companion object {
        const val TAG = "PdfRasteriser"
        const val POINTS_PER_INCH = 72f
        const val MIN_WIDTH_PX = 200
        const val BUFFER_BYTES = 64 * 1024
        const val A4_WIDTH_INCHES = 8.27
        const val A4_HEIGHT_INCHES = 11.69
    }
}
