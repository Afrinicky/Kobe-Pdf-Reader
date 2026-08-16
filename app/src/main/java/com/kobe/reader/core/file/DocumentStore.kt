package com.kobe.reader.core.file

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.raise
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All filesystem access in the app funnels through here.
 *
 * The storage model, and why:
 *
 *  - **Reading** happens through the Storage Access Framework. A single-file
 *    pick or a folder-tree grant both hand back a URI whose permission we
 *    persist, so nothing needs `READ_EXTERNAL_STORAGE` on modern Android and
 *    nothing needs the Play-restricted `MANAGE_EXTERNAL_STORAGE` at all.
 *  - **Discovery** ("All PDFs") is the union of granted folder trees, our own
 *    output folder, and - only on API <= 32, where the permission still exists
 *    and still covers documents - a MediaStore query.
 *  - **Writing** goes to app-private storage first. That write can never fail
 *    for permission reasons, so a 200-page merge is never lost at the last
 *    step; the user then chooses whether to export it somewhere permanent.
 */
@Singleton
class DocumentStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(KobeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Where tool output lands before the user exports it. Backed up: never. */
    val outputDir: File
        get() = File(context.filesDir, OUTPUT_DIR).apply { if (!exists()) mkdirs() }

    /** Scratch space for work that must not survive a crash. */
    val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    // ---------------------------------------------------------------- metadata

    /**
     * Reads display name / size / modified time for [uri].
     *
     * Providers are allowed to omit any of these columns, so every read has a
     * fallback rather than assuming the happy path.
     */
    suspend fun describe(uri: Uri, origin: DocumentRef.Origin): DocumentRef? = withContext(io) {
        runCatching { describeBlocking(uri, origin) }
            .onFailure { Log.w(TAG, "describe failed for $uri", it) }
            .getOrNull()
    }

    private fun describeBlocking(uri: Uri, origin: DocumentRef.Origin): DocumentRef? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File) ?: return null
            if (!file.exists()) return null
            return DocumentRef(
                uri = uri,
                displayName = file.name,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                origin = origin,
            )
        }

        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: return null
            return DocumentRef(
                uri = uri,
                displayName = name,
                sizeBytes = cursor.longOrNull(OpenableColumns.SIZE) ?: sizeByReading(uri),
                lastModified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    ?: 0L,
                origin = origin,
            )
        }
        return null
    }

    /** Last resort when a provider hides SIZE: stream the file and count. */
    private fun sizeByReading(uri: Uri): Long = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
    }.getOrDefault(0L)

    /** True if [uri] still resolves and we still hold permission for it. */
    suspend fun isReadable(uri: Uri): Boolean = withContext(io) {
        runCatching { resolver.openInputStream(uri)?.use { true } ?: false }
            .getOrDefault(false)
    }

    // ---------------------------------------------------------------- streams

    /**
     * Opens [uri] for reading, translating the three failure modes that actually
     * happen in the field into errors the UI can explain.
     */
    fun openInput(uri: Uri): InputStream = try {
        resolver.openInputStream(uri) ?: KobeError.FileMissing.raise()
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "openInput missing: $uri", e)
        KobeError.FileMissing.raise()
    } catch (e: SecurityException) {
        Log.w(TAG, "openInput denied: $uri", e)
        KobeError.PermissionLost.raise()
    }

    fun openOutput(uri: Uri): OutputStream = try {
        // "wt" truncates. Without it, overwriting a larger file leaves a tail of
        // the old content behind and produces a corrupt PDF.
        resolver.openOutputStream(uri, "wt") ?: KobeError.FileMissing.raise()
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "openOutput missing: $uri", e)
        KobeError.FileMissing.raise()
    } catch (e: SecurityException) {
        Log.w(TAG, "openOutput denied: $uri", e)
        KobeError.PermissionLost.raise()
    }

    /**
     * PdfBox needs random access, and a `content://` stream isn't seekable in
     * general. Copying to app storage first is the only reliable answer; the
     * copy is deleted by [releaseWorkingCopy] when the operation finishes.
     */
    suspend fun materialise(uri: Uri, suffix: String = ".pdf"): File = withContext(io) {
        val target = File.createTempFile("kobe_", suffix, cacheDir)
        openInput(uri).use { input ->
            target.outputStream().use { output ->
                val copied = input.copyTo(output, DEFAULT_BUFFER)
                if (copied == 0L) KobeError.CorruptedDocument.raise()
            }
        }
        target
    }

    fun releaseWorkingCopy(file: File?) {
        if (file != null && file.parentFile == cacheDir) file.delete()
    }

    // ---------------------------------------------------------------- output

    /**
     * Creates a uniquely-named file in the app's output folder.
     *
     * Uniqueness is resolved against the folder's real contents, so repeated
     * merges produce `Merged_2026-08-16.pdf`, `Merged_2026-08-16 (2).pdf`, …
     * instead of silently overwriting.
     */
    fun newOutputFile(desiredName: String, extension: String = FileNaming.PDF_EXTENSION): File {
        val dir = outputDir
        val name = FileNaming.uniquify(desiredName, extension) { candidate ->
            File(dir, candidate).exists()
        }
        return File(dir, name)
    }

    /** Bytes free on the volume holding the output folder. */
    fun freeSpaceBytes(): Long = runCatching { outputDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    /**
     * Fails fast when a job obviously cannot fit.
     *
     * [estimatedBytes] is padded because PDF writing is not in-place: PdfBox
     * holds a partial copy while saving, so peak usage exceeds the final size.
     */
    fun requireSpaceFor(estimatedBytes: Long) {
        val needed = (estimatedBytes * SPACE_HEADROOM).toLong() + MIN_HEADROOM_BYTES
        if (freeSpaceBytes() < needed) KobeError.OutOfSpace.raise()
    }

    /** A shareable `content://` URI for a file we generated. */
    fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * Copies a generated file to a destination the user picked with
     * `ACTION_CREATE_DOCUMENT`.
     */
    suspend fun exportTo(source: File, destination: Uri): Unit = withContext(io) {
        openOutput(destination).use { output ->
            source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER) }
        }
    }

    // ---------------------------------------------------------------- SAF grants

    /**
     * Persists read (and, for trees, write) permission so the grant survives a
     * reboot. Without this the URI works exactly once.
     */
    fun persistPermission(uri: Uri, writable: Boolean) {
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (writable) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.w(TAG, "could not persist permission for $uri", it) }
    }

    fun releasePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    /** Folder trees the user has granted us, newest grant first. */
    fun grantedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .sortedByDescending { it.persistedTime }
        .map { it.uri }

    // ---------------------------------------------------------------- discovery

    /**
     * Lists every PDF inside a granted folder tree, recursing into subfolders.
     *
     * [maxDepth] stops a pathological directory tree from turning library load
     * into a multi-second stall on a slow device.
     */
    suspend fun listPdfsInTree(treeUri: Uri, maxDepth: Int = MAX_TREE_DEPTH): List<DocumentRef> =
        withContext(io) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            buildList { collectPdfs(root, depth = 0, maxDepth = maxDepth, into = this) }
        }

    private fun collectPdfs(
        dir: DocumentFile,
        depth: Int,
        maxDepth: Int,
        into: MutableList<DocumentRef>,
    ) {
        if (depth > maxDepth) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> collectPdfs(child, depth + 1, maxDepth, into)
                child.isFile && child.looksLikePdf() -> into += DocumentRef(
                    uri = child.uri,
                    displayName = child.name ?: continue,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                    origin = DocumentRef.Origin.GrantedFolder,
                )
            }
        }
    }

    private fun DocumentFile.looksLikePdf(): Boolean =
        type == PDF_MIME || name?.endsWith(".pdf", ignoreCase = true) == true

    /**
     * MediaStore sweep for PDFs.
     *
     * Only useful on API <= 32: from API 33 there is no runtime permission that
     * grants access to non-media documents, so the query returns other apps'
     * files not at all and our own only sometimes. Callers check
     * [canUseMediaStore] first.
     */
    suspend fun listPdfsFromMediaStore(limit: Int = MEDIA_STORE_LIMIT): List<DocumentRef> =
        withContext(io) {
            if (!canUseMediaStore()) return@withContext emptyList()

            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
            )
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
            val order = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            runCatching {
                resolver.query(collection, projection, selection, arrayOf(PDF_MIME), order)
                    ?.use { cursor -> cursor.readMediaStoreRows(collection, limit) }
                    .orEmpty()
            }.onFailure { Log.w(TAG, "MediaStore sweep failed", it) }
                .getOrDefault(emptyList())
        }

    private fun Cursor.readMediaStoreRows(collection: Uri, limit: Int): List<DocumentRef> {
        val idColumn = getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameColumn = getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val sizeColumn = getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val dateColumn = getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

        val results = ArrayList<DocumentRef>(minOf(count, limit))
        while (moveToNext() && results.size < limit) {
            val name = getString(nameColumn) ?: continue
            results += DocumentRef(
                uri = ContentUris.withAppendedId(collection, getLong(idColumn)),
                displayName = name,
                sizeBytes = getLong(sizeColumn),
                // MediaStore stores DATE_MODIFIED in seconds, everything else
                // in the app works in millis.
                lastModified = getLong(dateColumn) * 1000L,
                origin = DocumentRef.Origin.MediaStore,
            )
        }
        return results
    }

    fun canUseMediaStore(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2

    /** PDFs this app generated, newest first. */
    fun listGeneratedPdfs(): List<DocumentRef> = outputDir
        .listFiles { file -> file.isFile && file.name.endsWith(".pdf", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { file ->
            DocumentRef(
                uri = Uri.fromFile(file),
                displayName = file.name,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                origin = DocumentRef.Origin.Generated,
            )
        }

    // ---------------------------------------------------------------- mutations

    /**
     * Renames a document in place.
     *
     * SAF providers may refuse, and `file://` URIs need a plain filesystem
     * rename, so both paths are handled and the caller gets the new URI back
     * (it changes on some providers).
     */
    suspend fun rename(ref: DocumentRef, newBaseName: String): Uri = withContext(io) {
        val base = FileNaming.sanitiseBase(newBaseName)
        if (base.isEmpty()) KobeError.EmptyName.raise()
        val newName = FileNaming.withExtension(base, FileNaming.extensionOf(ref.displayName)
            .ifEmpty { FileNaming.PDF_EXTENSION })

        if (ref.uri.scheme == ContentResolver.SCHEME_FILE) {
            val current = ref.uri.path?.let(::File) ?: KobeError.FileMissing.raise()
            val target = File(current.parentFile, newName)
            if (target.exists()) KobeError.DuplicateName.raise()
            if (!current.renameTo(target)) KobeError.Unexpected().raise()
            return@withContext Uri.fromFile(target)
        }

        try {
            DocumentsContract.renameDocument(resolver, ref.uri, newName)
                ?: KobeError.PermissionLost.raise()
        } catch (e: SecurityException) {
            Log.w(TAG, "rename denied for ${ref.uri}", e)
            KobeError.PermissionLost.raise()
        } catch (e: IllegalStateException) {
            // Providers throw this for "a file with that name already exists".
            Log.w(TAG, "rename conflict for ${ref.uri}", e)
            KobeError.DuplicateName.raise()
        }
    }

    suspend fun delete(ref: DocumentRef): Boolean = withContext(io) {
        runCatching {
            if (ref.uri.scheme == ContentResolver.SCHEME_FILE) {
                ref.uri.path?.let(::File)?.delete() ?: false
            } else {
                DocumentsContract.deleteDocument(resolver, ref.uri)
            }
        }.onFailure { Log.w(TAG, "delete failed for ${ref.uri}", it) }
            .getOrDefault(false)
    }

    /** Copies a document into the app's output folder under a `Copy of …` name. */
    suspend fun duplicate(ref: DocumentRef): File = withContext(io) {
        requireSpaceFor(ref.sizeBytes)
        val target = newOutputFile("Copy of ${ref.baseName}")
        openInput(ref.uri).use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
        }
        target
    }

    // ---------------------------------------------------------------- helpers

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private companion object {
        const val TAG = "DocumentStore"
        const val PDF_MIME = "application/pdf"
        const val OUTPUT_DIR = "output"
        const val CACHE_DIR = "share"
        const val DEFAULT_BUFFER = 64 * 1024
        const val MAX_TREE_DEPTH = 6
        const val MEDIA_STORE_LIMIT = 2_000

        /** PdfBox peaks well above the output size while saving. */
        const val SPACE_HEADROOM = 2.5
        const val MIN_HEADROOM_BYTES = 16L * 1024 * 1024
    }
}
