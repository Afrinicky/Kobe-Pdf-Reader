package com.kobe.reader.pdf

import android.net.Uri
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.common.ProgressReporter
import java.io.File

/**
 * Every structural PDF operation the app offers, behind one interface.
 *
 * Keeping this an interface is not ceremony: the implementation is the one part
 * of the app most likely to be swapped (PdfBox today, a native PDFium binding or
 * the platform editing APIs from API 36.1 later), and every tool ViewModel talks
 * to this type rather than to a PDF library.
 *
 * Contract for all methods:
 *  - They are `suspend` and must be called off the main thread; implementations
 *    are responsible for their own dispatcher.
 *  - They are cancellable at page boundaries. A cancelled operation deletes any
 *    partial output before it returns.
 *  - They never throw for expected failures; those come back as
 *    [Outcome.Failure]. A throw from one of these is a bug.
 *  - Page numbers in the public API are **1-based**, matching what the user sees.
 */
interface PdfToolkit {

    /** Page count, size and encryption state, without rendering anything. */
    suspend fun inspect(source: Uri, password: String? = null): Outcome<PdfDocumentInfo>

    /** Combines [sources] in the order given. */
    suspend fun merge(
        sources: List<Uri>,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Splits [source] according to [mode], producing one file per resulting part. */
    suspend fun split(
        source: Uri,
        mode: SplitMode,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Copies [pages] (1-based) into a new document, preserving their order. */
    suspend fun extractPages(
        source: Uri,
        pages: List<Int>,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Writes a copy of [source] with [pages] (1-based) removed. */
    suspend fun deletePages(
        source: Uri,
        pages: List<Int>,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /**
     * Rewrites [source] with its pages in [order].
     *
     * @param order 1-based source page numbers in their new sequence. Must be a
     *   permutation of `1..pageCount` - pages may not be dropped here; that is
     *   what [deletePages] is for.
     */
    suspend fun reorderPages(
        source: Uri,
        order: List<Int>,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Applies clockwise [rotations] (degrees, multiples of 90) to given pages. */
    suspend fun rotatePages(
        source: Uri,
        rotations: Map<Int, Int>,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /**
     * Applies every pending page edit in one pass.
     *
     * The organize screen can produce reorders, deletions and rotations at once;
     * doing them as three sequential operations would rewrite the file three
     * times and triple the peak disk use.
     */
    suspend fun applyPageEdits(
        source: Uri,
        edits: PageEdits,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Recompresses embedded images to shrink the file. */
    suspend fun compress(
        source: Uri,
        level: CompressionLevel,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Builds a PDF from [images], one image per page. */
    suspend fun imagesToPdf(
        images: List<Uri>,
        options: ImagesToPdfOptions,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Rasterises [pages] (1-based, empty means all) to image files. */
    suspend fun pdfToImages(
        source: Uri,
        pages: List<Int>,
        options: PdfToImagesOptions,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Encrypts [source] with [options]. */
    suspend fun protect(
        source: Uri,
        options: ProtectOptions,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /** Removes encryption, given the correct owner or user password. */
    suspend fun unlock(
        source: Uri,
        password: String,
        outputName: String,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<File>>

    /**
     * Finds [query] across the document.
     *
     * Runs page by page and emits nothing until it has something, so a search in
     * a 500-page document can be cancelled the moment the user keeps typing.
     */
    suspend fun search(
        source: Uri,
        query: String,
        password: String? = null,
        progress: ProgressReporter = ProgressReporter.None,
    ): Outcome<List<SearchHit>>
}
