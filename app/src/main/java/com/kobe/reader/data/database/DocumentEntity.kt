package com.kobe.reader.data.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * The app's index of documents the user has interacted with.
 *
 * This table holds *metadata only* - never document content. It is what makes
 * "recent", "favorites" and "resume where you left off" work, and it is small
 * enough (a few hundred rows for a heavy user) to back up safely.
 *
 * The primary key is the document's URI string. It is stable for SAF documents
 * across reboots once the permission is persisted, and it means the same file
 * reached two ways never produces two rows.
 */
@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["last_opened_at"]),
        Index(value = ["is_favorite"]),
        Index(value = ["display_name"]),
    ],
)
data class DocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "last_modified")
    val lastModified: Long,

    /** -1 until the document has been opened and its page count learned. */
    @ColumnInfo(name = "page_count")
    val pageCount: Int = -1,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    /** 0-based page the user was last looking at. */
    @ColumnInfo(name = "last_read_page")
    val lastReadPage: Int = 0,

    /** Vertical position within that page, 0f..1f, so resume is pixel-accurate. */
    @ColumnInfo(name = "last_read_offset")
    val lastReadOffset: Float = 0f,

    /** 0 means "never opened" - the row exists only because it was indexed. */
    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long = 0L,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    /** Name of a [com.kobe.reader.core.file.DocumentRef.Origin] constant. */
    @ColumnInfo(name = "origin")
    val origin: String,
)
