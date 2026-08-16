package com.kobe.reader.data.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY last_opened_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE last_opened_at > 0 ORDER BY last_opened_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE is_favorite = 1 ORDER BY display_name COLLATE NOCASE ASC")
    fun observeFavorites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    fun observe(uri: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun find(uri: String): DocumentEntity?

    /** One-shot snapshot, used by the reachability sweep. */
    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Upsert
    suspend fun upsertAll(documents: List<DocumentEntity>)

    /**
     * Inserts discovered documents without clobbering reading state.
     *
     * A library rescan must not reset `last_read_page` or `is_favorite`, so a
     * plain upsert is wrong here - IGNORE keeps whatever the row already holds.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(documents: List<DocumentEntity>)

    /**
     * Refreshes only the fields a rescan can legitimately learn, leaving user
     * state alone.
     */
    @Query(
        """
        UPDATE documents
        SET display_name = :displayName,
            size_bytes = :sizeBytes,
            last_modified = :lastModified
        WHERE uri = :uri
        """,
    )
    suspend fun refreshMetadata(
        uri: String,
        displayName: String,
        sizeBytes: Long,
        lastModified: Long,
    )

    @Query("UPDATE documents SET is_favorite = :favorite WHERE uri = :uri")
    suspend fun setFavorite(uri: String, favorite: Boolean)

    @Query(
        """
        UPDATE documents
        SET last_read_page = :page,
            last_read_offset = :offset,
            last_opened_at = :openedAt
        WHERE uri = :uri
        """,
    )
    suspend fun saveReadingPosition(uri: String, page: Int, offset: Float, openedAt: Long)

    @Query("UPDATE documents SET page_count = :pageCount WHERE uri = :uri")
    suspend fun setPageCount(uri: String, pageCount: Int)

    @Query("UPDATE documents SET uri = :newUri, display_name = :displayName WHERE uri = :oldUri")
    suspend fun rename(oldUri: String, newUri: String, displayName: String)

    @Query("DELETE FROM documents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM documents WHERE uri IN (:uris)")
    suspend fun deleteAll(uris: List<String>)

    /** Clears history without touching favorites - "clear recents" in settings. */
    @Query("UPDATE documents SET last_opened_at = 0, last_read_page = 0, last_read_offset = 0")
    suspend fun clearHistory()

    /** Drops rows for documents that came from a folder grant being revoked. */
    @Query("DELETE FROM documents WHERE origin = :origin AND is_favorite = 0 AND last_opened_at = 0")
    suspend fun pruneUnusedByOrigin(origin: String)

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun count(): Int
}
