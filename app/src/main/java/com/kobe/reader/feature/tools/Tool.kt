package com.kobe.reader.feature.tools

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kobe.reader.R
import com.kobe.reader.monetization.ProFeature

/**
 * The tool catalog.
 *
 * One entry per user-facing operation, carrying everything the UI and the
 * gating layer need: label, icon, what it consumes, and which [ProFeature] it
 * charges against. Adding a tool is adding an entry here plus a branch in
 * [ToolFlowViewModel.execute] - nothing else changes.
 */
enum class Tool(
    val key: String,
    @get:StringRes val titleRes: Int,
    @get:StringRes val descriptionRes: Int,
    @get:DrawableRes val iconRes: Int,
    val input: ToolInput,
    val feature: ProFeature,
    /** Shown on Home's quick-tools strip. */
    val isQuickTool: Boolean = false,
) {
    Merge(
        key = "merge",
        titleRes = R.string.tool_merge,
        descriptionRes = R.string.tool_merge_desc,
        iconRes = R.drawable.ic_merge,
        input = ToolInput.MultiplePdfs,
        feature = ProFeature.Merge,
        isQuickTool = true,
    ),
    Split(
        key = "split",
        titleRes = R.string.tool_split,
        descriptionRes = R.string.tool_split_desc,
        iconRes = R.drawable.ic_split,
        input = ToolInput.SinglePdf,
        feature = ProFeature.Split,
        isQuickTool = true,
    ),
    Compress(
        key = "compress",
        titleRes = R.string.tool_compress,
        descriptionRes = R.string.tool_compress_desc,
        iconRes = R.drawable.ic_compress,
        input = ToolInput.SinglePdf,
        feature = ProFeature.Compress,
        isQuickTool = true,
    ),
    ImagesToPdf(
        key = "images_to_pdf",
        titleRes = R.string.tool_images_to_pdf,
        descriptionRes = R.string.tool_images_to_pdf_desc,
        iconRes = R.drawable.ic_images_to_pdf,
        input = ToolInput.MultipleImages,
        feature = ProFeature.ImagesToPdf,
        isQuickTool = true,
    ),
    PdfToImages(
        key = "pdf_to_images",
        titleRes = R.string.tool_pdf_to_images,
        descriptionRes = R.string.tool_pdf_to_images_desc,
        iconRes = R.drawable.ic_pdf_to_images,
        input = ToolInput.SinglePdf,
        feature = ProFeature.PdfToImages,
        isQuickTool = true,
    ),
    Organize(
        key = "organize",
        titleRes = R.string.tool_reorder,
        descriptionRes = R.string.tool_reorder_desc,
        iconRes = R.drawable.ic_organize,
        input = ToolInput.SinglePdf,
        feature = ProFeature.Organize,
        isQuickTool = true,
    ),
    ExtractPages(
        key = "extract",
        titleRes = R.string.tool_extract,
        descriptionRes = R.string.tool_extract_desc,
        iconRes = R.drawable.ic_extract,
        input = ToolInput.SinglePdf,
        feature = ProFeature.ExtractPages,
    ),
    DeletePages(
        key = "delete_pages",
        titleRes = R.string.tool_delete_pages,
        descriptionRes = R.string.tool_delete_pages_desc,
        iconRes = R.drawable.ic_delete,
        input = ToolInput.SinglePdf,
        feature = ProFeature.DeletePages,
    ),
    Rotate(
        key = "rotate",
        titleRes = R.string.tool_rotate,
        descriptionRes = R.string.tool_rotate_desc,
        iconRes = R.drawable.ic_rotate_right,
        input = ToolInput.SinglePdf,
        feature = ProFeature.Rotate,
    ),
    Protect(
        key = "protect",
        titleRes = R.string.tool_protect,
        descriptionRes = R.string.tool_protect_desc,
        iconRes = R.drawable.ic_lock,
        input = ToolInput.SinglePdf,
        feature = ProFeature.PasswordProtect,
    ),
    ;

    companion object {
        fun fromKey(key: String): Tool? = entries.firstOrNull { it.key == key }

        val quickTools: List<Tool> get() = entries.filter { it.isQuickTool }
    }
}

/** What the picker should ask for before the tool can run. */
enum class ToolInput {
    SinglePdf,
    MultiplePdfs,
    MultipleImages,
    ;

    val mimeTypes: Array<String>
        get() = when (this) {
            SinglePdf, MultiplePdfs -> arrayOf("application/pdf")
            MultipleImages -> arrayOf("image/*")
        }

    val allowsMultiple: Boolean get() = this != SinglePdf
}
