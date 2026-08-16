package com.kobe.reader.data.repository

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.common.runCatchingKobe
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.raise
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.data.database.DocumentDao
import com.kobe.reader.data.database.DocumentEntity
import com.kobe.reader.data.prefs.SettingsRepository
import com.kobe.reader.data.prefs.SortOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library: what documents exist, how they're ordered, and what the user can
 * do to them.
 *
 * This is the app's error boundary for storage work. Below it, [DocumentStore]
 * throws; above it, everything is an [Outcome]. ViewModels never see an
 * exception from here.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val dao: DocumentDao,
    private val store: DocumentStore,
    private val settings: SettingsRepository,
    @param:Dispatcher(KobeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    // ---------------------------------------------------------------- queries

    /** Everything we know about, ordered by the user's chosen sort. */
    val allDocuments: Flow<List<DocumentRef>> =
        combine(dao.observeAll(), settings.settings.map { it.sortOrder }) { rows, order ->
            rows.map(DocumentEntity::toRef).sortedWith(order.comparator())
        }

    val recentDocuments: Flow<List<DocumentRef>> =
        dao.observeRecent().map { rows -> rows.map(DocumentEntity::toRef) }

    val favoriteDocuments: Flow<List<DocumentRef>> =
        combine(dao.observeFavorites(), settings.settings.map { it.sortOrder }) { rows, order ->
            rows.map(DocumentEntity::toRef).sortedWith(order.comparator())
        }

    fun observe(uri: Uri): Flow<DocumentRef?> =
        dao.observe(uri.toString()).map { it?.toRef() }

    suspend fun find(uri: Uri): DocumentRef? = dao.find(uri.toString())?.toRef()

    // ---------------------------------------------------------------- indexing

    /**
     * Registers a document the user just picked, persisting its SAF permission
     * so the app can still open it after a reboot.
     */
    suspend fun registerPicked(uri: Uri): Outcome<DocumentRef> = withContext(io) {
        runCatchingKobe {
            store.persistPermission(uri, writable = false)
            val ref = store.describe(uri, DocumentRef.Origin.Picked)
                ?: KobeError.FileMissing.raise()

            val existing = dao.find(uri.toString())
            if (existing == null) {
                dao.upsert(ref.toEntity())
                ref
            } else {
                // Keep favorite and reading position; refresh what may have moved.
                dao.refreshMetadata(
                    uri = existing.uri,
                    displayName = ref.displayName,
                    sizeBytes = ref.sizeBytes,
                    lastModified = ref.lastModified,
                )
                existing.toRef().copy(
                    displayName = ref.displayName,
                    sizeBytes = ref.sizeBytes,
                    lastModified = ref.lastModified,
                )
            }
        }
    }

    /** Records a folder-tree grant and indexes what's inside it. */
    suspend fun addFolder(treeUri: Uri): Outcome<Int> = withContext(io) {
        runCatchingKobe {
            store.persistPermission(treeUri, writable = true)
            settings.addGrantedFolder(treeUri.toString())
            val found = store.listPdfsInTree(treeUri)
            dao.insertIfAbsent(found.map { it.toEntity() })
            found.size
        }
    }

    suspend fun removeFolder(treeUri: Uri) = withContext(io) {
        store.releasePermission(treeUri)
        settings.removeGrantedFolder(treeUri.toString())
        dao.pruneUnusedByOrigin(DocumentRef.Origin.GrantedFolder.name)
    }

    /**
     * Rebuilds the index from every source we're allowed to read.
     *
     * Existing rows keep their user state - a rescan must never wipe a favorite
     * or a reading position, which is why this uses `insertIfAbsent` plus a
     * targeted metadata refresh rather than a blanket upsert.
     */
    suspend fun rescan(): Outcome<Int> = withContext(io) {
        runCatchingKobe {
            val discovered = buildList {
                addAll(store.listGeneratedPdfs())
                store.grantedTrees().forEach { tree ->
                    addAll(runCatching { store.listPdfsInTree(tree) }.getOrDefault(emptyList()))
                }
                if (store.canUseMediaStore()) {
                    addAll(
                        runCatching { store.listPdfsFromMediaStore() }
                            .getOrDefault(emptyList()),
                    )
                }
            }.distinctBy { it.uri }

            dao.insertIfAbsent(discovered.map { it.toEntity() })
            discovered.forEach { ref ->
                dao.refreshMetadata(
                    uri = ref.uri.toString(),
                    displayName = ref.displayName,
                    sizeBytes = ref.sizeBytes,
                    lastModified = ref.lastModified,
                )
            }
            discovered.size
        }
    }

    /** Adds a file a tool just produced, so it appears in the library at once. */
    suspend fun registerGenerated(file: File): DocumentRef = withContext(io) {
        val ref = DocumentRef(
            uri = Uri.fromFile(file),
            displayName = file.name,
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            origin = DocumentRef.Origin.Generated,
        )
        dao.upsert(ref.toEntity())
        ref
    }

    // ---------------------------------------------------------------- reading state

    suspend fun recordOpened(uri: Uri, pageCount: Int) = withContext(io) {
        dao.setPageCount(uri.toString(), pageCount)
        val existing = dao.find(uri.toString())
        dao.saveReadingPosition(
            uri = uri.toString(),
            page = existing?.lastReadPage ?: 0,
            offset = existing?.lastReadOffset ?: 0f,
            openedAt = System.currentTimeMillis(),
        )
    }

    suspend fun saveReadingPosition(uri: Uri, page: Int, offset: Float) = withContext(io) {
        dao.saveReadingPosition(
            uri = uri.toString(),
            page = page,
            offset = offset,
            openedAt = System.currentTimeMillis(),
        )
    }

    suspend fun setFavorite(uri: Uri, favorite: Boolean) = withContext(io) {
        dao.setFavorite(uri.toString(), favorite)
    }

    suspend fun clearHistory() = withContext(io) { dao.clearHistory() }

    // ---------------------------------------------------------------- mutations

    suspend fun rename(ref: DocumentRef, newBaseName: String): Outcome<DocumentRef> =
        withContext(io) {
            runCatchingKobe {
                val newUri = store.rename(ref, newBaseName)
                val updated = store.describe(newUri, ref.origin)
                    ?: throw IllegalStateException("renamed document vanished")
                dao.rename(ref.uri.toString(), newUri.toString(), updated.displayName)
                updated
            }
        }

    suspend fun delete(ref: DocumentRef): Outcome<Unit> = withContext(io) {
        runCatchingKobe {
            val deleted = store.delete(ref)
            // Drop the row either way: if the file is gone from under us, the
            // library should stop showing it rather than showing a dead entry.
            dao.delete(ref.uri.toString())
            if (!deleted && store.isReadable(ref.uri)) {
                throw SecurityException("provider refused delete")
            }
        }
    }

    suspend fun duplicate(ref: DocumentRef): Outcome<DocumentRef> = withContext(io) {
        runCatchingKobe {
            val copy = store.duplicate(ref)
            registerGenerated(copy)
        }
    }

    /**
     * Removes index rows whose files no longer resolve.
     *
     * Files disappear constantly on Android - the user deletes them in another
     * app, an SD card is unmounted, a folder grant is revoked. Without this the
     * library slowly fills with entries that fail on tap.
     */
    suspend fun pruneMissing(): Int = withContext(io) {
        val stale = dao.all()
            .filterNot { store.isReadable(it.uri.toUri()) }
            .map { it.uri }
        if (stale.isNotEmpty()) {
            Log.i(TAG, "pruning ${stale.size} unreachable documents")
            dao.deleteAll(stale)
        }
        stale.size
    }

    private fun SortOrder.comparator(): Comparator<DocumentRef> = when (this) {
        SortOrder.NameAscending -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        SortOrder.NameDescending ->
            compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        SortOrder.DateDescending -> compareByDescending { it.lastModified }
        SortOrder.DateAscending -> compareBy { it.lastModified }
        SortOrder.SizeDescending -> compareByDescending { it.sizeBytes }
        SortOrder.SizeAscending -> compareBy { it.sizeBytes }
    }

    private companion object {
        const val TAG = "LibraryRepository"
    }
}

// -------------------------------------------------------------------- mapping

internal fun DocumentEntity.toRef(): DocumentRef = DocumentRef(
    uri = uri.toUri(),
    displayName = displayName,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    pageCount = pageCount,
    isFavorite = isFavorite,
    lastReadPage = lastReadPage,
    lastOpenedAt = lastOpenedAt,
    origin = runCatching { DocumentRef.Origin.valueOf(origin) }
        .getOrDefault(DocumentRef.Origin.Picked),
)

internal fun DocumentRef.toEntity(): DocumentEntity = DocumentEntity(
    uri = uri.toString(),
    displayName = displayName,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    pageCount = pageCount,
    isFavorite = isFavorite,
    lastReadPage = lastReadPage,
    lastOpenedAt = lastOpenedAt,
    origin = origin.name,
)
