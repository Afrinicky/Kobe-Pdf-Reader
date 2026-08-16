package com.kobe.reader.core.file

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Default names for generated files, plus the sanitising every user-supplied
 * name goes through before it reaches the filesystem.
 *
 * Shapes produced here match the spec's examples:
 * `Merged_2026-08-16.pdf`, `Compressed_report.pdf`, `Images_to_PDF.pdf`.
 */
object FileNaming {

    const val PDF_EXTENSION = "pdf"

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Characters that are illegal on FAT/exFAT - still the format of most SD
     * cards and USB sticks. Android's own storage is more permissive, but a
     * file the user later copies to a card shouldn't become unreadable.
     * Spaces and hyphens are deliberately kept.
     */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    /** Control characters, which no filesystem enjoys. */
    private val CONTROL = Regex("""\p{Cntrl}""")

    private val WHITESPACE = Regex("""\s+""")

    /** Longest base name we'll produce. Leaves room for " (2)" plus ".pdf". */
    private const val MAX_BASE_LENGTH = 120

    /** `Merged_2026-08-16` - used when the output has no single obvious source. */
    fun dated(prefix: String, today: LocalDate = LocalDate.now()): String =
        "${sanitiseBase(prefix)}_${today.format(DATE)}"

    /** `Compressed_report` - used when the output derives from one input file. */
    fun derived(prefix: String, sourceName: String): String {
        val base = sanitiseBase(stripExtension(sourceName))
        if (base.isEmpty()) return sanitiseBase(prefix)
        return "${sanitiseBase(prefix)}_$base".take(MAX_BASE_LENGTH)
    }

    /** `report_page_007` - used for per-page output (split, PDF to images). */
    fun indexed(base: String, index: Int, width: Int = 3): String =
        "${sanitiseBase(stripExtension(base))}_${index.toString().padStart(width, '0')}"

    /** Adds [extension] unless [name] already ends with it (case-insensitively). */
    fun withExtension(name: String, extension: String = PDF_EXTENSION): String {
        val clean = name.trim()
        return if (clean.endsWith(".$extension", ignoreCase = true)) clean else "$clean.$extension"
    }

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        // A leading dot marks a hidden file, not an extension separator.
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.lastIndex) name.substring(dot + 1).lowercase() else ""
    }

    /**
     * Strips path separators and reserved characters, collapses whitespace and
     * trims to a sane length. Returns an empty string if nothing usable is left,
     * which callers surface as [com.kobe.reader.core.error.KobeError.EmptyName].
     */
    fun sanitiseBase(raw: String): String = raw
        .replace(CONTROL, "")
        .replace(ILLEGAL, "")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')
        .take(MAX_BASE_LENGTH)

    /**
     * Resolves a collision by appending ` (2)`, ` (3)` … the way every desktop
     * file manager does.
     *
     * @param taken predicate answering "does this full filename already exist?"
     */
    fun uniquify(
        desired: String,
        extension: String = PDF_EXTENSION,
        limit: Int = 999,
        taken: (String) -> Boolean,
    ): String {
        val base = sanitiseBase(stripExtension(desired)).ifEmpty { "Document" }
        val first = "$base.$extension"
        if (!taken(first)) return first

        for (suffix in 2..limit) {
            val candidate = "$base ($suffix).$extension"
            if (!taken(candidate)) return candidate
        }
        // Astronomically unlikely; fall back to something that cannot collide.
        return "$base ${System.currentTimeMillis()}.$extension"
    }
}
