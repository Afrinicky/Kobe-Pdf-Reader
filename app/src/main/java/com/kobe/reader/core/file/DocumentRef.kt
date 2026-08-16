package com.kobe.reader.core.file

import android.net.Uri

/**
 * A PDF the app knows about, identified by the only thing that stays stable
 * across reboots and SD-card remounts: its content URI.
 *
 * Deliberately not a `java.io.File`. On API 29+ the app cannot open arbitrary
 * paths, and every path-based shortcut turns into a permission bug later.
 */
data class DocumentRef(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** -1 until the document has been opened once and its page count cached. */
    val pageCount: Int = UNKNOWN_PAGE_COUNT,
    val isFavorite: Boolean = false,
    /** Last page the user was on, 0-based. */
    val lastReadPage: Int = 0,
    /** Epoch millis of the last open, or 0 if never opened. */
    val lastOpenedAt: Long = 0L,
    /** Where the file came from, which decides which actions we can offer. */
    val origin: Origin = Origin.Picked,
) {
    val baseName: String get() = FileNaming.stripExtension(displayName)

    val hasBeenOpened: Boolean get() = lastOpenedAt > 0L

    /**
     * Files we created can be renamed and deleted freely. Files reached through
     * a folder grant usually can too. A one-off `ACTION_OPEN_DOCUMENT` pick is
     * read-only unless the provider says otherwise, so the UI hides destructive
     * actions rather than offering them and failing.
     */
    enum class Origin {
        /** Created by a Kobe Reader tool, living in the app's output folder. */
        Generated,

        /** Inside a folder tree the user granted with ACTION_OPEN_DOCUMENT_TREE. */
        GrantedFolder,

        /** Indexed from MediaStore on API <= 32. */
        MediaStore,

        /** A single file picked with ACTION_OPEN_DOCUMENT. */
        Picked,
    }

    companion object {
        const val UNKNOWN_PAGE_COUNT = -1
    }
}
