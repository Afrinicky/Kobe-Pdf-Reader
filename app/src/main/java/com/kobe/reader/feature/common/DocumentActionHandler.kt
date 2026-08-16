package com.kobe.reader.feature.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.kobe.reader.R
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.data.repository.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Document management shared by Home and Files.
 *
 * Both screens offer the same row menu, and both would otherwise grow the same
 * five near-identical ViewModel methods. Keeping the behaviour here means
 * "delete asks for confirmation and prunes the index" is defined once.
 */
@Singleton
class DocumentActionHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val store: DocumentStore,
) {

    suspend fun toggleFavorite(document: DocumentRef) {
        library.setFavorite(document.uri, !document.isFavorite)
    }

    suspend fun rename(document: DocumentRef, newBaseName: String): KobeError? =
        when (val outcome = library.rename(document, newBaseName)) {
            is Outcome.Success -> null
            is Outcome.Failure -> outcome.error
        }

    suspend fun delete(document: DocumentRef): KobeError? =
        when (val outcome = library.delete(document)) {
            is Outcome.Success -> null
            is Outcome.Failure -> outcome.error
        }

    suspend fun duplicate(document: DocumentRef): KobeError? =
        when (val outcome = library.duplicate(document)) {
            is Outcome.Success -> null
            is Outcome.Failure -> outcome.error
        }

    /**
     * Builds a share intent.
     *
     * Files we generated live in app-private storage and must go out through the
     * FileProvider; documents the user picked already have a `content://` URI
     * another app can read, provided we pass the read grant along.
     */
    fun shareIntent(document: DocumentRef): Intent {
        val shareUri = document.shareableUri()
        return Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, document.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { intent ->
            Intent.createChooser(intent, context.getString(R.string.action_share))
        }
    }

    fun shareIntent(files: List<File>, mimeType: String = PDF_MIME): Intent {
        val uris = ArrayList(files.map(store::shareUri))
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, context.getString(R.string.action_share))
    }

    /** Opens a generated file in whichever PDF viewer the user prefers. */
    fun viewIntent(file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(store.shareUri(file), PDF_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun DocumentRef.shareableUri(): Uri =
        if (uri.scheme == "file") {
            store.shareUri(File(requireNotNull(uri.path)))
        } else {
            uri
        }

    /** Registers a file the user exported so the library picks it up. */
    suspend fun registerExported(destination: Uri): DocumentRef? =
        when (val outcome = library.registerPicked(destination)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> null
        }

    suspend fun registerGenerated(paths: List<String>) {
        paths.map(::File).filter { it.exists() }.forEach { library.registerGenerated(it) }
    }

    fun documentFor(uriString: String): Uri = uriString.toUri()

    private companion object {
        const val PDF_MIME = "application/pdf"
    }
}
