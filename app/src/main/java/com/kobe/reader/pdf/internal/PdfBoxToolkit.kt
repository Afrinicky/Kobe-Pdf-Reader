package com.kobe.reader.pdf.internal

import android.net.Uri
import android.util.Log
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.common.ProgressReporter
import com.kobe.reader.core.common.runCatchingKobe
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.raise
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.pdf.CompressionLevel
import com.kobe.reader.pdf.ImagesToPdfOptions
import com.kobe.reader.pdf.PageDimensions
import com.kobe.reader.pdf.PageEdits
import com.kobe.reader.pdf.PdfDocumentInfo
import com.kobe.reader.pdf.PdfToImagesOptions
import com.kobe.reader.pdf.PdfToolkit
import com.kobe.reader.pdf.ProtectOptions
import com.kobe.reader.pdf.SearchHit
import com.kobe.reader.pdf.SplitMode
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * [PdfToolkit] backed by PdfBox-Android.
 *
 * Three conventions run through every operation here:
 *
 *  1. **Work on a real file.** PdfBox needs random access and `content://`
 *    streams are not seekable, so sources are copied into cache first and the
 *    copies are deleted in `finally`.
 *  2. **Bounded memory.** Documents load with [MemoryUsageSetting.setupMixed],
 *    which spills to a temp file past a threshold. Loading a 300 MB scan fully
 *    into the heap is the single most common way a PDF app dies on a cheap phone.
 *  3. **Cancellation deletes output.** Every page loop calls `ensureActive()`,
 *    and a failure or cancellation removes the half-written file rather than
 *    leaving the user with a corrupt result in their library.
 */
@Singleton
class PdfBoxToolkit @Inject constructor(
    private val store: DocumentStore,
    private val imageCompressor: PdfImageCompressor,
    private val imagePlacer: ImageToPdfPlacer,
    private val rasteriser: PdfRasteriser,
    @param:Dispatcher(KobeDispatcher.Default) private val cpu: CoroutineDispatcher,
) : PdfToolkit {

    // ------------------------------------------------------------------ inspect

    override suspend fun inspect(source: Uri, password: String?): Outcome<PdfDocumentInfo> =
        withContext(cpu) {
            runCatchingKobe {
                withWorkingCopy(source) { file ->
                    loadDocument(file, password).use { document ->
                        PdfDocumentInfo(
                            pageCount = document.numberOfPages,
                            sizeBytes = file.length(),
                            isEncrypted = document.isEncrypted,
                            title = document.documentInformation?.title?.takeIf { it.isNotBlank() },
                            author = document.documentInformation?.author?.takeIf { it.isNotBlank() },
                            producer = document.documentInformation?.producer,
                            createdAtMillis = document.documentInformation
                                ?.creationDate
                                ?.timeInMillis,
                            pdfVersion = document.version.toString(),
                            pageSizes = document.pages.map { page ->
                                PageDimensions(page.mediaBox.width, page.mediaBox.height)
                            },
                        )
                    }
                }
            }
        }

    // ------------------------------------------------------------------ merge

    override suspend fun merge(
        sources: List<Uri>,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            if (sources.size < 2) KobeError.Unexpected().raise()

            val copies = mutableListOf<File>()
            val target = store.newOutputFile(outputName)
            try {
                // Materialise first so the space check sees the real total.
                sources.forEachIndexed { index, uri ->
                    coroutineContext.ensureActive()
                    copies += store.materialise(uri)
                    progress.report(index + 1, sources.size + 1)
                }
                store.requireSpaceFor(copies.sumOf { it.length() })

                val merger = PDFMergerUtility().apply {
                    destinationFileName = target.absolutePath
                    copies.forEach { addSource(it) }
                }
                // PDFMergerUtility gives no per-page callback, so the merge itself
                // is reported as the final step rather than faked page by page.
                merger.mergeDocuments(memorySetting())
                progress.report(sources.size + 1, sources.size + 1)

                if (!target.exists() || target.length() == 0L) KobeError.Unexpected().raise()
                listOf(target)
            } catch (throwable: Throwable) {
                target.delete()
                throw throwable
            } finally {
                copies.forEach(store::releaseWorkingCopy)
            }
        }
    }

    // ------------------------------------------------------------------ split

    override suspend fun split(
        source: Uri,
        mode: SplitMode,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                store.requireSpaceFor(file.length() * 2)
                loadDocument(file, password = null).use { document ->
                    val ranges = mode.toRanges(document.numberOfPages)
                    if (ranges.isEmpty()) KobeError.Unexpected().raise()

                    writeParts(document, ranges, outputName, progress)
                }
            }
        }
    }

    /** Turns a [SplitMode] into concrete 1-based inclusive page ranges. */
    private fun SplitMode.toRanges(pageCount: Int): List<IntRange> = when (this) {
        SplitMode.EveryPage -> (1..pageCount).map { it..it }

        is SplitMode.AtPage -> when {
            at !in 1 until pageCount -> emptyList()
            else -> listOf(1..at, (at + 1)..pageCount)
        }

        is SplitMode.Ranges -> ranges
            .map { it.first.coerceIn(1, pageCount)..it.last.coerceIn(1, pageCount) }
            .filter { it.first <= it.last }

        is SplitMode.EveryNPages -> when {
            size < 1 -> emptyList()
            else -> (1..pageCount step size).map { start ->
                start..minOf(start + size - 1, pageCount)
            }
        }
    }

    /** Writes one output file per range by importing pages into fresh documents. */
    private suspend fun writeParts(
        document: PDDocument,
        ranges: List<IntRange>,
        outputName: String,
        progress: ProgressReporter,
    ): List<File> {
        val written = mutableListOf<File>()
        try {
            ranges.forEachIndexed { index, range ->
                coroutineContext.ensureActive()
                val part = store.newOutputFile(
                    if (ranges.size == 1) outputName else "${outputName}_${index + 1}",
                )
                PDDocument().use { output ->
                    for (page in range) {
                        coroutineContext.ensureActive()
                        output.importPage(document.getPage(page - 1))
                    }
                    output.save(part)
                }
                written += part
                progress.report(index + 1, ranges.size)
            }
            return written
        } catch (throwable: Throwable) {
            written.forEach { it.delete() }
            throw throwable
        }
    }

    // ------------------------------------------------------------------ pages

    override suspend fun extractPages(
        source: Uri,
        pages: List<Int>,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                loadDocument(file, password = null).use { document ->
                    val valid = pages.filter { it in 1..document.numberOfPages }
                    if (valid.isEmpty()) KobeError.Unexpected().raise()
                    store.requireSpaceFor(file.length())
                    writeExplicitPages(document, valid, outputName, progress)
                }
            }
        }
    }

    private suspend fun writeExplicitPages(
        document: PDDocument,
        pages: List<Int>,
        outputName: String,
        progress: ProgressReporter,
    ): List<File> {
        val target = store.newOutputFile(outputName)
        try {
            PDDocument().use { output ->
                pages.forEachIndexed { index, page ->
                    coroutineContext.ensureActive()
                    output.importPage(document.getPage(page - 1))
                    progress.report(index + 1, pages.size)
                }
                output.save(target)
            }
            return listOf(target)
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        }
    }

    override suspend fun deletePages(
        source: Uri,
        pages: List<Int>,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = when (val info = inspect(source)) {
        is Outcome.Failure -> info
        is Outcome.Success -> {
            val removed = pages.toSet()
            val keep = (1..info.value.pageCount).filterNot { it in removed }
            if (keep.isEmpty()) {
                Outcome.Failure(KobeError.Unexpected())
            } else {
                applyPageEdits(source, PageEdits(order = keep), outputName, progress)
            }
        }
    }

    override suspend fun reorderPages(
        source: Uri,
        order: List<Int>,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = applyPageEdits(source, PageEdits(order = order), outputName, progress)

    override suspend fun rotatePages(
        source: Uri,
        rotations: Map<Int, Int>,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = when (val info = inspect(source)) {
        is Outcome.Failure -> info
        is Outcome.Success -> applyPageEdits(
            source = source,
            edits = PageEdits(
                order = (1..info.value.pageCount).toList(),
                rotations = rotations,
            ),
            outputName = outputName,
            progress = progress,
        )
    }

    /**
     * Reorder + delete + rotate in one rewrite.
     *
     * Pages are moved within the loaded document rather than imported into a new
     * one. That keeps the outline, metadata and shared resources intact, and
     * avoids the size blow-up `importPage` causes when several pages reference
     * the same font or image.
     */
    override suspend fun applyPageEdits(
        source: Uri,
        edits: PageEdits,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                store.requireSpaceFor(file.length())
                val target = store.newOutputFile(outputName)
                try {
                    loadDocument(file, password = null).use { document ->
                        val originals: List<PDPage> = document.pages.toList()
                        val order = edits.order.filter { it in 1..originals.size }
                        if (order.isEmpty()) KobeError.Unexpected().raise()

                        // Detach every page, then re-attach only the ones we keep,
                        // in their new order.
                        originals.forEach { document.pages.remove(it) }

                        order.forEachIndexed { index, sourcePage ->
                            coroutineContext.ensureActive()
                            val page = originals[sourcePage - 1]
                            edits.rotations[sourcePage]?.let { delta ->
                                page.rotation = normaliseRotation(page.rotation + delta)
                            }
                            document.pages.add(page)
                            progress.report(index + 1, order.size)
                        }
                        document.save(target)
                    }
                    listOf(target)
                } catch (throwable: Throwable) {
                    target.delete()
                    throw throwable
                }
            }
        }
    }

    // ------------------------------------------------------------------ compress

    override suspend fun compress(
        source: Uri,
        level: CompressionLevel,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                store.requireSpaceFor(file.length())
                val target = store.newOutputFile(outputName)
                try {
                    loadDocument(file, password = null).use { document ->
                        imageCompressor.compress(document, level, progress)
                        document.save(target)
                    }

                    // Recompressing can make a file *bigger* - a scanned page
                    // already stored as an optimal JPEG gains nothing and loses
                    // to re-encoding overhead. Keep whichever is smaller.
                    if (target.length() >= file.length()) {
                        file.copyTo(target, overwrite = true)
                    }
                    listOf(target)
                } catch (throwable: Throwable) {
                    target.delete()
                    throw throwable
                }
            }
        }
    }

    // ------------------------------------------------------------------ images

    override suspend fun imagesToPdf(
        images: List<Uri>,
        options: ImagesToPdfOptions,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            if (images.isEmpty()) KobeError.Unexpected().raise()
            val target = store.newOutputFile(outputName)
            try {
                imagePlacer.build(images, options, target, progress)
                listOf(target)
            } catch (throwable: Throwable) {
                target.delete()
                throw throwable
            }
        }
    }

    override suspend fun pdfToImages(
        source: Uri,
        pages: List<Int>,
        options: PdfToImagesOptions,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                rasteriser.rasterise(file, pages, options, outputName, progress)
            }
        }
    }

    // ------------------------------------------------------------------ security

    override suspend fun protect(
        source: Uri,
        options: ProtectOptions,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                store.requireSpaceFor(file.length())
                val target = store.newOutputFile(outputName)
                try {
                    loadDocument(file, password = null).use { document ->
                        progress.report(0, 1)
                        val permissions = AccessPermission().apply {
                            setCanPrint(options.allowPrinting)
                            setCanPrintDegraded(options.allowPrinting)
                            setCanExtractContent(options.allowExtraction)
                            setCanExtractForAccessibility(true)
                            setCanModify(false)
                            setCanModifyAnnotations(false)
                            setCanFillInForm(false)
                            setCanAssembleDocument(false)
                        }
                        val policy = StandardProtectionPolicy(
                            options.ownerPassword,
                            options.userPassword,
                            permissions,
                        ).apply { encryptionKeyLength = options.keyLengthBits }

                        document.protect(policy)
                        document.save(target)
                        progress.report(1, 1)
                    }
                    listOf(target)
                } catch (throwable: Throwable) {
                    target.delete()
                    throw throwable
                }
            }
        }
    }

    override suspend fun unlock(
        source: Uri,
        password: String,
        outputName: String,
        progress: ProgressReporter,
    ): Outcome<List<File>> = withContext(cpu) {
        runCatchingKobe {
            withWorkingCopy(source) { file ->
                val target = store.newOutputFile(outputName)
                try {
                    loadDocument(file, password).use { document ->
                        progress.report(0, 1)
                        // Refuse to strip protection we were not entitled to remove.
                        if (!document.currentAccessPermission.isOwnerPermission) {
                            KobeError.WrongPassword.raise()
                        }
                        document.isAllSecurityToBeRemoved = true
                        document.save(target)
                        progress.report(1, 1)
                    }
                    listOf(target)
                } catch (throwable: Throwable) {
                    target.delete()
                    throw throwable
                }
            }
        }
    }

    // ------------------------------------------------------------------ search

    override suspend fun search(
        source: Uri,
        query: String,
        password: String?,
        progress: ProgressReporter,
    ): Outcome<List<SearchHit>> = withContext(cpu) {
        runCatchingKobe {
            val needle = query.trim()
            if (needle.length < MIN_QUERY_LENGTH) return@runCatchingKobe emptyList<SearchHit>()

            withWorkingCopy(source) { file ->
                loadDocument(file, password).use { document ->
                    val hits = mutableListOf<SearchHit>()
                    val stripper = PDFTextStripper().apply { sortByPosition = true }

                    for (page in 1..document.numberOfPages) {
                        // One page per pass so a search in a long document stops
                        // the instant the user types another character.
                        coroutineContext.ensureActive()
                        stripper.startPage = page
                        stripper.endPage = page

                        val text = runCatching { stripper.getText(document) }
                            .onFailure { Log.w(TAG, "text extraction failed on page $page", it) }
                            .getOrDefault("")

                        hits += findIn(text, needle, page)
                        progress.report(page, document.numberOfPages)
                        if (hits.size >= MAX_HITS) break
                    }
                    hits
                }
            }
        }
    }

    private fun findIn(text: String, needle: String, page: Int): List<SearchHit> {
        if (text.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        var index = text.indexOf(needle, ignoreCase = true)
        while (index >= 0 && hits.size < MAX_HITS_PER_PAGE) {
            hits += SearchHit(
                page = page,
                charOffset = index,
                snippet = snippetAround(text, index, needle.length),
            )
            index = text.indexOf(needle, startIndex = index + needle.length, ignoreCase = true)
        }
        return hits
    }

    private fun snippetAround(text: String, at: Int, length: Int): String {
        val start = (at - SNIPPET_PADDING).coerceAtLeast(0)
        val end = (at + length + SNIPPET_PADDING).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace('\n', ' ').trim() + suffix
    }

    // ------------------------------------------------------------------ plumbing

    /** Copies [source] into cache, runs [block] against it, then cleans up. */
    private suspend fun <T> withWorkingCopy(source: Uri, block: suspend (File) -> T): T {
        val copy = store.materialise(source)
        return try {
            block(copy)
        } finally {
            store.releaseWorkingCopy(copy)
        }
    }

    /**
     * Loads a document, converting PdfBox's password exceptions into the two
     * cases the UI distinguishes: "needs a password" and "that one was wrong".
     */
    private fun loadDocument(file: File, password: String?): PDDocument = try {
        PDDocument.load(file, password.orEmpty(), memorySetting())
    } catch (e: InvalidPasswordException) {
        if (password.isNullOrEmpty()) KobeError.PasswordRequired.raise()
        else KobeError.WrongPassword.raise()
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM loading ${file.name} (${file.length()} bytes)", e)
        KobeError.DocumentTooLarge.raise()
    }

    /**
     * Spills to a temp file beyond [MAX_HEAP_BYTES] so that page count, not heap
     * size, decides whether a document opens.
     */
    private fun memorySetting(): MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(MAX_HEAP_BYTES).setTempDir(store.cacheDir)

    private fun normaliseRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private companion object {
        const val TAG = "PdfBoxToolkit"
        const val MAX_HEAP_BYTES = 24L * 1024 * 1024
        const val MIN_QUERY_LENGTH = 2
        const val MAX_HITS = 500
        const val MAX_HITS_PER_PAGE = 50
        const val SNIPPET_PADDING = 48
    }
}
