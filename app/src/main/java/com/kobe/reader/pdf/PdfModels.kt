package com.kobe.reader.pdf

import androidx.annotation.StringRes
import com.kobe.reader.R

/** What [PdfToolkit.inspect] can tell you without rendering a single page. */
data class PdfDocumentInfo(
    val pageCount: Int,
    val sizeBytes: Long,
    val isEncrypted: Boolean,
    val title: String? = null,
    val author: String? = null,
    val producer: String? = null,
    val createdAtMillis: Long? = null,
    val pdfVersion: String? = null,
    /** Page dimensions in points, in document order. */
    val pageSizes: List<PageDimensions> = emptyList(),
) {
    val hasMetadata: Boolean get() = !title.isNullOrBlank() || !author.isNullOrBlank()
}

data class PageDimensions(val widthPoints: Float, val heightPoints: Float) {
    val isLandscape: Boolean get() = widthPoints > heightPoints
}

/** How [PdfToolkit.split] should divide a document. */
sealed interface SplitMode {
    /** One output file per page. */
    data object EveryPage : SplitMode

    /**
     * Two files: pages `1..at` and `at+1..end`.
     * @param at 1-based, must be less than the page count.
     */
    data class AtPage(val at: Int) : SplitMode

    /** One output file per range, in the order given. 1-based, inclusive. */
    data class Ranges(val ranges: List<IntRange>) : SplitMode

    /** Fixed-size chunks, e.g. every 10 pages. */
    data class EveryNPages(val size: Int) : SplitMode
}

/**
 * Compression presets.
 *
 * The tuning here is empirical: image DPI dominates PDF size far more than JPEG
 * quality does, so each level drops resolution first and quality second.
 * [Advanced] is gated behind Pro.
 */
enum class CompressionLevel(
    @get:StringRes val labelRes: Int,
    val targetDpi: Int,
    val jpegQuality: Int,
    val isPremium: Boolean,
) {
    Light(R.string.compression_low, targetDpi = 200, jpegQuality = 85, isPremium = false),
    Balanced(R.string.compression_medium, targetDpi = 150, jpegQuality = 70, isPremium = false),
    Advanced(R.string.compression_high, targetDpi = 96, jpegQuality = 55, isPremium = true),
}

/** The set of page changes the organize screen can accumulate. */
data class PageEdits(
    /** 1-based source page numbers in their new order; excluded pages are dropped. */
    val order: List<Int>,
    /** 1-based source page number to clockwise rotation in degrees (0/90/180/270). */
    val rotations: Map<Int, Int> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = rotations.all { it.value % 360 == 0 } &&
            order == order.indices.map { it + 1 }

    companion object {
        fun identity(pageCount: Int) = PageEdits(order = (1..pageCount).toList())
    }
}

data class ImagesToPdfOptions(
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val orientation: PageOrientation = PageOrientation.Auto,
    val fit: ImageFit = ImageFit.Contain,
    val marginPoints: Float = 24f,
    /** Re-encode quality when an image has to be resampled. */
    val jpegQuality: Int = 85,
)

enum class PdfPageSize(@get:StringRes val labelRes: Int, val widthPoints: Float, val heightPoints: Float) {
    A4(R.string.page_size, 595f, 842f),
    Letter(R.string.page_size, 612f, 792f),
    /** Each page takes the exact size of its image, so nothing is cropped or padded. */
    MatchImage(R.string.page_size, 0f, 0f),
}

enum class PageOrientation { Auto, Portrait, Landscape }

enum class ImageFit {
    /** Whole image visible, letterboxed if the aspect ratios differ. */
    Contain,

    /** Image fills the page; overflow is cropped. */
    Cover,

    /** Stretched to the page, ignoring aspect ratio. */
    Stretch,
}

data class PdfToImagesOptions(
    val format: ImageFormat = ImageFormat.Jpeg,
    val dpi: Int = 150,
    val quality: Int = 90,
)

enum class ImageFormat(val extension: String, val mimeType: String) {
    Jpeg("jpg", "image/jpeg"),
    Png("png", "image/png"),
}

data class ProtectOptions(
    val userPassword: String,
    /**
     * Owner password governs permissions. Defaulting it to the user password
     * keeps the UI to one field; anyone who can open the file can also change
     * its permissions, which is the honest outcome for a consumer tool.
     */
    val ownerPassword: String = userPassword,
    val allowPrinting: Boolean = true,
    val allowExtraction: Boolean = false,
    val keyLengthBits: Int = 256,
)

/** One match from a full-document text search. */
data class SearchHit(
    /** 1-based. */
    val page: Int,
    /** Character offset of the match within that page's extracted text. */
    val charOffset: Int,
    /** Surrounding text for the results list, with the match somewhere inside. */
    val snippet: String,
)
