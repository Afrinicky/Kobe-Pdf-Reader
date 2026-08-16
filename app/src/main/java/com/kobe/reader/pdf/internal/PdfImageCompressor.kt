package com.kobe.reader.pdf.internal

import android.graphics.Bitmap
import android.util.Log
import com.kobe.reader.core.common.ProgressReporter
import com.kobe.reader.pdf.CompressionLevel
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Shrinks a PDF by resampling its embedded raster images.
 *
 * Why images and nothing else: in the documents people actually want to
 * compress - scans, photo-heavy reports, exported slide decks - images are
 * 90%+ of the bytes. Font subsetting and stream re-deflating move the needle by
 * a few percent and risk breaking rendering, so neither is worth it here.
 *
 * The approximation this makes, stated plainly: PDFBox can tell us an image's
 * pixel dimensions but not the size it is *drawn* at without interpreting the
 * page's content stream. We assume an image roughly covers the page it appears
 * on, so the target pixel width is the page width in inches times the target
 * DPI. For full-page scans - the dominant case - that is exact. For a small
 * logo it under-compresses, which is the safe direction to be wrong in.
 */
@Singleton
class PdfImageCompressor @Inject constructor() {

    suspend fun compress(
        document: PDDocument,
        level: CompressionLevel,
        progress: ProgressReporter,
    ) {
        val pageCount = document.numberOfPages
        // Images are frequently shared between pages; rewriting one twice would
        // double-degrade it and waste time.
        val processed = HashSet<COSName>()

        document.pages.forEachIndexed { index, page ->
            coroutineContext.ensureActive()
            val pageWidthInches = page.mediaBox.width / POINTS_PER_INCH
            val targetWidthPx = (pageWidthInches * level.targetDpi).toInt()
                .coerceIn(MIN_TARGET_WIDTH, MAX_TARGET_WIDTH)

            compressResources(
                document = document,
                resources = page.resources,
                targetWidthPx = targetWidthPx,
                quality = level.jpegQuality,
                processed = processed,
                depth = 0,
            )
            progress.report(index + 1, pageCount)
        }
    }

    /** Walks a resource dictionary, recursing into form XObjects. */
    private suspend fun compressResources(
        document: PDDocument,
        resources: PDResources?,
        targetWidthPx: Int,
        quality: Int,
        processed: MutableSet<COSName>,
        depth: Int,
    ) {
        if (resources == null || depth > MAX_FORM_DEPTH) return

        for (name in resources.xObjectNames.toList()) {
            coroutineContext.ensureActive()
            if (!processed.add(name)) continue

            val xObject: PDXObject = runCatching { resources.getXObject(name) }
                .onFailure { Log.w(TAG, "unreadable XObject $name", it) }
                .getOrNull() ?: continue

            when (xObject) {
                is PDFormXObject -> compressResources(
                    document = document,
                    resources = xObject.resources,
                    targetWidthPx = targetWidthPx,
                    quality = quality,
                    processed = processed,
                    depth = depth + 1,
                )

                is PDImageXObject -> resample(document, resources, name, xObject, targetWidthPx, quality)

                else -> Unit
            }
        }
    }

    private fun resample(
        document: PDDocument,
        resources: PDResources,
        name: COSName,
        image: PDImageXObject,
        targetWidthPx: Int,
        quality: Int,
    ) {
        // Already small enough - re-encoding would only lose quality.
        if (image.width <= targetWidthPx) return

        // A soft-masked image carries per-pixel alpha. JPEG has none, so
        // re-encoding it as JPEG would flatten the transparency and visibly
        // break the page. Leave these alone.
        if (image.softMask != null || image.mask != null) return

        val source: Bitmap = runCatching { image.image }
            .onFailure { Log.w(TAG, "cannot decode image $name", it) }
            .getOrNull() ?: return

        try {
            val targetHeight = (targetWidthPx.toLong() * source.height / source.width)
                .toInt()
                .coerceAtLeast(1)

            val scaled = Bitmap.createScaledBitmap(source, targetWidthPx, targetHeight, true)
            try {
                val replacement = JPEGFactory.createFromImage(
                    document,
                    scaled,
                    quality / 100f,
                )
                // Only keep the new version if it is genuinely smaller. A
                // photographic JPEG resampled down can still lose to the
                // original when the original was already well-optimised.
                if (streamLength(replacement) < streamLength(image)) {
                    resources.put(name, replacement)
                }
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM resampling $name (${image.width}x${image.height})", e)
        } catch (e: Exception) {
            Log.w(TAG, "resample failed for $name", e)
        } finally {
            source.recycle()
        }
    }

    /**
     * Encoded byte length of an image stream, read from its `/Length` entry -
     * cheaper and safer than decoding the stream just to measure it. An absent
     * entry counts as "unknown, assume huge", so a replacement whose length we
     * *can* read wins, and if neither length is readable the original stays.
     */
    private fun streamLength(image: PDImageXObject): Long =
        image.cosObject.getLong(COSName.LENGTH, -1L).takeIf { it >= 0 } ?: Long.MAX_VALUE

    private companion object {
        const val TAG = "PdfImageCompressor"
        const val POINTS_PER_INCH = 72f
        const val MIN_TARGET_WIDTH = 320
        const val MAX_TARGET_WIDTH = 3_000
        const val MAX_FORM_DEPTH = 4
    }
}
