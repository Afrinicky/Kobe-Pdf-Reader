package com.kobe.reader.core.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class FileNamingTest {

    @Test
    fun `dated name matches the documented shape`() {
        val name = FileNaming.dated("Merged", LocalDate.of(2026, 8, 16))
        assertThat(name).isEqualTo("Merged_2026-08-16")
    }

    @Test
    fun `derived name keeps the source base name`() {
        assertThat(FileNaming.derived("Compressed", "report.pdf"))
            .isEqualTo("Compressed_report")
    }

    @Test
    fun `derived name falls back to the prefix when the source is unusable`() {
        assertThat(FileNaming.derived("Compressed", "///")).isEqualTo("Compressed")
    }

    @Test
    fun `indexed name zero pads`() {
        assertThat(FileNaming.indexed("scan.pdf", 7)).isEqualTo("scan_007")
    }

    @Test
    fun `extension is added only when missing`() {
        assertThat(FileNaming.withExtension("report")).isEqualTo("report.pdf")
        assertThat(FileNaming.withExtension("report.pdf")).isEqualTo("report.pdf")
        // Case-insensitive, so an upper-case extension isn't doubled up.
        assertThat(FileNaming.withExtension("report.PDF")).isEqualTo("report.PDF")
    }

    @Test
    fun `stripExtension leaves a hidden file intact`() {
        assertThat(FileNaming.stripExtension("report.pdf")).isEqualTo("report")
        assertThat(FileNaming.stripExtension(".hidden")).isEqualTo(".hidden")
        assertThat(FileNaming.stripExtension("no-extension")).isEqualTo("no-extension")
    }

    @Test
    fun `extensionOf lowercases and ignores trailing dots`() {
        assertThat(FileNaming.extensionOf("Report.PDF")).isEqualTo("pdf")
        assertThat(FileNaming.extensionOf("report.")).isEmpty()
        assertThat(FileNaming.extensionOf("report")).isEmpty()
    }

    @Test
    fun `sanitise removes path separators and reserved characters`() {
        assertThat(FileNaming.sanitiseBase("""a/b\c:d*e?f"g<h>i|j"""))
            .isEqualTo("abcdefghij")
    }

    @Test
    fun `sanitise keeps spaces and hyphens`() {
        assertThat(FileNaming.sanitiseBase("Q3 report - final")).isEqualTo("Q3 report - final")
    }

    @Test
    fun `sanitise collapses runs of whitespace`() {
        assertThat(FileNaming.sanitiseBase("too   many    spaces"))
            .isEqualTo("too many spaces")
    }

    @Test
    fun `sanitise trims leading and trailing dots`() {
        assertThat(FileNaming.sanitiseBase("..name..")).isEqualTo("name")
    }

    @Test
    fun `sanitise returns empty for input with nothing usable`() {
        assertThat(FileNaming.sanitiseBase("///")).isEmpty()
    }

    @Test
    fun `sanitise caps the length`() {
        assertThat(FileNaming.sanitiseBase("x".repeat(500))).hasLength(120)
    }

    @Test
    fun `uniquify returns the plain name when nothing collides`() {
        assertThat(FileNaming.uniquify("report") { false }).isEqualTo("report.pdf")
    }

    @Test
    fun `uniquify appends a counter on collision`() {
        val taken = setOf("report.pdf", "report (2).pdf")
        assertThat(FileNaming.uniquify("report") { it in taken })
            .isEqualTo("report (3).pdf")
    }

    @Test
    fun `uniquify strips an existing extension before numbering`() {
        val taken = setOf("report.pdf")
        assertThat(FileNaming.uniquify("report.pdf") { it in taken })
            .isEqualTo("report (2).pdf")
    }

    @Test
    fun `uniquify falls back to a name that cannot collide when exhausted`() {
        // Everything is taken, so the counter loop runs out.
        val name = FileNaming.uniquify("report", limit = 3) { true }
        assertThat(name).startsWith("report ")
        assertThat(name).endsWith(".pdf")
        assertThat(name).isNotEqualTo("report (2).pdf")
        assertThat(name).isNotEqualTo("report (3).pdf")
    }

    @Test
    fun `uniquify substitutes a default for an unusable name`() {
        assertThat(FileNaming.uniquify("///") { false }).isEqualTo("Document.pdf")
    }

    @Test
    fun `uniquify honours a non pdf extension`() {
        assertThat(FileNaming.uniquify("page", extension = "jpg") { false })
            .isEqualTo("page.jpg")
    }
}
