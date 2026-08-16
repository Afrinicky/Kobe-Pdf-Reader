package com.kobe.reader.monetization

import androidx.annotation.StringRes
import com.kobe.reader.R

/**
 * The single list of what Pro unlocks.
 *
 * Everything that gates on entitlement refers to a constant here, so moving a
 * feature between tiers is a one-line edit rather than a hunt through the UI.
 * That is the "add or remove premium features without restructuring" requirement,
 * made concrete.
 *
 * @param dailyFreeLimit how many times a free user may run this per day.
 *   `UNLIMITED` for things that are free forever, `LOCKED` for Pro-only.
 */
enum class ProFeature(
    @get:StringRes val labelRes: Int,
    val dailyFreeLimit: Int,
) {
    Merge(R.string.tool_merge, dailyFreeLimit = 3),
    Split(R.string.tool_split, dailyFreeLimit = 3),
    ExtractPages(R.string.tool_extract, dailyFreeLimit = 5),
    DeletePages(R.string.tool_delete_pages, dailyFreeLimit = 5),
    Organize(R.string.tool_reorder, dailyFreeLimit = 3),
    Rotate(R.string.tool_rotate, dailyFreeLimit = UNLIMITED),
    ImagesToPdf(R.string.tool_images_to_pdf, dailyFreeLimit = 2),

    /** Basic compression is free; [com.kobe.reader.pdf.CompressionLevel.Advanced] is not. */
    Compress(R.string.tool_compress, dailyFreeLimit = 2),
    AdvancedCompression(R.string.compression_high, dailyFreeLimit = LOCKED),
    PdfToImages(R.string.tool_pdf_to_images, dailyFreeLimit = LOCKED),
    PasswordProtect(R.string.tool_protect, dailyFreeLimit = LOCKED),
    BatchProcessing(R.string.pro_benefit_batch, dailyFreeLimit = LOCKED),
    ;

    val isProOnly: Boolean get() = dailyFreeLimit == LOCKED
    val isUnlimited: Boolean get() = dailyFreeLimit == UNLIMITED

    companion object {
        const val LOCKED = 0
        const val UNLIMITED = -1
    }
}

/** Answer to "may the user run this right now?". */
sealed interface FeatureAccess {
    /** Go ahead. [remainingToday] is null for Pro users and unlimited features. */
    data class Allowed(val remainingToday: Int?) : FeatureAccess

    /** Pro-only feature, free user. */
    data class RequiresPro(val feature: ProFeature) : FeatureAccess

    /** Free-tier feature, daily allowance spent. */
    data class LimitReached(val feature: ProFeature, val dailyLimit: Int) : FeatureAccess

    val isAllowed: Boolean get() = this is Allowed
}
