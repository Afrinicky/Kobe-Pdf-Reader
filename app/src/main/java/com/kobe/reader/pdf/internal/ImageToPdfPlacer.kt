package com.kobe.reader.pdf.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.kobe.reader.core.common.ProgressReporter
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.raise
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.pdf.ImageFit
import com.kobe.reader.pdf.ImagesToPdfOptions
import com.kobe.reader.pdf.PageOrientation
import com.kobe.reader.pdf.PdfPageSize
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a PDF from a list of images, one page per image.
 *
 * The two things that make this harder than it looks, both handled here:
 *
 *  - **Memory.** A modern phone photo is 4000x3000; decoding a dozen of them at
 *    full size is an instant OOM on a budget device. Every image is decoded with
 *    `inSampleSize` chosen from its declared bounds, so nothing larger than the
 *    page needs is ever in memory.
 *  - **EXIF rotation.** Camera photos are almost always stored landscape with an
 *    orientation tag. Ignoring it is why so many "images to PDF" apps produce
 *    sideways pages.
 */
@Singleton
class ImageToPdfPlacer @Inject constructor(
    private val store: DocumentStore,
) {

    suspend fun build(
        images: List<Uri>,
        options: ImagesToPdfOptions,
        target: File,
        progress: ProgressReporter,
    ) {
        PDDocument().use { document ->
            images.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                addPage(document, uri, options)
                progress.report(index + 1, images.size)
            }
            if (document.numberOfPages == 0) KobeError.Unexpected().raise()
            document.save(target)
        }
    }

    private fun addPage(document: PDDocument, uri: Uri, options: ImagesToPdfOptions) {
        val bounds = readBounds(uri) ?: run {
            Log.w(TAG, "skipping unreadable image $uri")
            return
        }

        val pageBox = pageBoxFor(bounds, options)
        val contentWidth = max(pageBox.width - options.marginPoints * 2, 1f)
        val contentHeight = max(pageBox.height - options.marginPoints * 2, 1f)

        // Decode no larger than the page actually needs at print resolution.
        val neededPx = (max(contentWidth, contentHeight) / POINTS_PER_INCH * RENDER_DPI).toInt()
        val bitmap = decodeScaled(uri, neededPx) ?: run {
            Log.w(TAG, "skipping undecodable image $uri")
            return
        }

        try {
            val page = PDPage(pageBox)
            document.addPage(page)

            val image = encode(document, bitmap, options.jpegQuality)
            val placement = place(
                imageWidth = bitmap.width.toFloat(),
                imageHeight = bitmap.height.toFloat(),
                boxWidth = contentWidth,
                boxHeight = contentHeight,
                fit = options.fit,
            )

            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(
                    image,
                    options.marginPoints + (contentWidth - placement.width) / 2f,
                    options.marginPoints + (contentHeight - placement.height) / 2f,
                    placement.width,
                    placement.height,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * JPEG for opaque photos, lossless PNG-style for anything with alpha.
     * Running a screenshot with transparency through JPEG turns its background
     * black, which is a bug report waiting to happen.
     */
    private fun encode(document: PDDocument, bitmap: Bitmap, quality: Int): PDImageXObject =
        if (bitmap.hasAlpha()) {
            LosslessFactory.createFromImage(document, bitmap)
        } else {
            JPEGFactory.createFromImage(document, bitmap, quality / 100f)
        }

    // ---------------------------------------------------------------- geometry

    private fun pageBoxFor(bounds: Bounds, options: ImagesToPdfOptions): PDRectangle {
        if (options.pageSize == PdfPageSize.MatchImage) {
            // Convert pixels to points at a nominal 72 dpi so the page is the
            // same physical size the image would print at.
            return PDRectangle(bounds.width.toFloat(), bounds.height.toFloat())
        }

        val portrait = PDRectangle(options.pageSize.widthPoints, options.pageSize.heightPoints)
        val landscape = PDRectangle(options.pageSize.heightPoints, options.pageSize.widthPoints)

        return when (options.orientation) {
            PageOrientation.Portrait -> portrait
            PageOrientation.Landscape -> landscape
            PageOrientation.Auto -> if (bounds.width > bounds.height) landscape else portrait
        }
    }

    private fun place(
        imageWidth: Float,
        imageHeight: Float,
        boxWidth: Float,
        boxHeight: Float,
        fit: ImageFit,
    ): Placement {
        if (imageWidth <= 0f || imageHeight <= 0f) return Placement(boxWidth, boxHeight)

        val scale = when (fit) {
            ImageFit.Contain -> min(boxWidth / imageWidth, boxHeight / imageHeight)
            ImageFit.Cover -> max(boxWidth / imageWidth, boxHeight / imageHeight)
            ImageFit.Stretch -> return Placement(boxWidth, boxHeight)
        }
        return Placement(imageWidth * scale, imageHeight * scale)
    }

    // ---------------------------------------------------------------- decoding

    private fun readBounds(uri: Uri): Bounds? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        store.openInput(uri).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        // Swap for EXIF-rotated photos so page orientation is decided on what
        // the user will actually see.
        val rotation = readExifRotation(uri)
        if (rotation == 90 || rotation == 270) {
            Bounds(options.outHeight, options.outWidth)
        } else {
            Bounds(options.outWidth, options.outHeight)
        }
    }.onFailure { Log.w(TAG, "bounds read failed for $uri", it) }.getOrNull()

    private fun decodeScaled(uri: Uri, targetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        store.openInput(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = store.openInput(uri).use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        applyExifRotation(uri, decoded)
    }.onFailure { Log.w(TAG, "decode failed for $uri", it) }.getOrNull()

    /**
     * Largest power-of-two subsample that still leaves the image at or above
     * [targetPx] on its longest edge. `BitmapFactory` rounds to a power of two
     * anyway, so computing it explicitly avoids surprises.
     */
    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= targetPx && sample < MAX_SAMPLE_SIZE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun readExifRotation(uri: Uri): Int = runCatching {
        store.openInput(uri).use { input ->
            when (
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
    }.getOrDefault(0)

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = readExifRotation(uri)
        if (degrees == 0) return bitmap
        return try {
            val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { rotated -> if (rotated !== bitmap) bitmap.recycle() }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM rotating $uri, using unrotated", e)
            bitmap
        }
    }

    private data class Bounds(val width: Int, val height: Int)

    private data class Placement(val width: Float, val height: Float)

    private companion object {
        const val TAG = "ImageToPdfPlacer"
        const val POINTS_PER_INCH = 72f

        /** Enough for a sharp page without decoding a 12 MP photo in full. */
        const val RENDER_DPI = 200f
        const val MAX_SAMPLE_SIZE = 16
    }
}
