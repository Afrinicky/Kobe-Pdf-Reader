package com.kobe.reader.core.error

import androidx.annotation.StringRes
import com.kobe.reader.R

/**
 * Every failure the user can see, expressed in product terms rather than
 * exception types.
 *
 * The rule this enforces: nothing below the repository/toolkit boundary ever
 * lets an [Exception] escape to the UI. Callers map once, at the boundary, via
 * [KobeError.from], and the UI only ever renders [messageRes].
 */
sealed class KobeError(@get:StringRes val messageRes: Int) {

    /** The bytes aren't a PDF, or the PDF's structure is broken beyond repair. */
    data object CorruptedDocument : KobeError(R.string.error_corrupted)

    /** A password is required and we don't have one yet. */
    data object PasswordRequired : KobeError(R.string.error_password)

    /** A password was supplied and the document rejected it. */
    data object WrongPassword : KobeError(R.string.error_wrong_password)

    /** The picked file isn't a PDF at all (wrong MIME type / magic bytes). */
    data object NotAPdf : KobeError(R.string.error_not_a_pdf)

    /** Device storage ran out mid-write. */
    data object OutOfSpace : KobeError(R.string.error_no_space)

    /** The document is structurally valid but too big for this device. */
    data object DocumentTooLarge : KobeError(R.string.error_too_large)

    /** Rendering or processing exhausted the heap. */
    data object OutOfMemory : KobeError(R.string.error_out_of_memory)

    /** A previously granted SAF permission has been revoked or expired. */
    data object PermissionLost : KobeError(R.string.error_no_permission)

    /** The URI no longer resolves - file moved, renamed or deleted elsewhere. */
    data object FileMissing : KobeError(R.string.error_file_missing)

    /** A valid PDF that uses something we can't handle yet. */
    data object UnsupportedFeature : KobeError(R.string.error_unsupported)

    /** The user cancelled. Not really an error; kept here so flows stay uniform. */
    data object Cancelled : KobeError(R.string.error_cancelled)

    /** The chosen output name is already taken in the destination folder. */
    data object DuplicateName : KobeError(R.string.error_duplicate_name)

    /** The user cleared the filename field. */
    data object EmptyName : KobeError(R.string.error_empty_name)

    // --- Input validation ---------------------------------------------------
    // These never come from an exception; tools raise them before starting work
    // so the user gets a specific sentence instead of a generic failure.

    data object NothingSelected : KobeError(R.string.error_nothing_selected)

    data object NeedTwoFiles : KobeError(R.string.error_need_two_files)

    data object InvalidPageRange : KobeError(R.string.error_invalid_page_range)

    data object PasswordTooShort : KobeError(R.string.password_too_short)

    data object PasswordMismatch : KobeError(R.string.password_mismatch)

    /**
     * Anything we haven't classified. [cause] is kept for logcat only - it is
     * never shown to the user.
     */
    data class Unexpected(val cause: Throwable? = null) : KobeError(R.string.error_generic)

    companion object {
        /**
         * Maps a thrown exception onto the closest product-level error.
         *
         * PdfBox reports most problems as [java.io.IOException] with a message,
         * so message sniffing is unavoidable here. Keeping it in one place means
         * the rest of the codebase never has to do it.
         */
        fun from(throwable: Throwable): KobeError {
            if (throwable is KobeException) return throwable.error

            val message = throwable.message.orEmpty().lowercase()
            return when {
                throwable is OutOfMemoryError -> OutOfMemory
                throwable is SecurityException -> PermissionLost
                throwable is java.io.FileNotFoundException -> FileMissing

                // PdfBox's InvalidPasswordException lives in a package we don't
                // want to import this low down; match on type name instead.
                throwable::class.java.simpleName == "InvalidPasswordException" ->
                    if (message.contains("empty password")) PasswordRequired else WrongPassword

                message.contains("password") -> WrongPassword
                message.contains("encrypted") -> PasswordRequired
                message.contains("no space left") || message.contains("enospc") -> OutOfSpace
                message.contains("not a pdf") || message.contains("header") -> NotAPdf
                message.contains("damaged") || message.contains("corrupt") -> CorruptedDocument
                message.contains("expected") && message.contains("xref") -> CorruptedDocument

                else -> Unexpected(throwable)
            }
        }
    }
}

/** Throwable wrapper so deep call stacks can fail with an already-classified error. */
class KobeException(val error: KobeError) : Exception(error.toString())

/** Shorthand for `throw KobeException(this)`. */
fun KobeError.raise(): Nothing = throw KobeException(this)
