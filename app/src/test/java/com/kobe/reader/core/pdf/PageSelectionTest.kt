package com.kobe.reader.core.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageSelectionTest {

    @Test
    fun `single page parses`() {
        assertThat(PageSelection.parse("7", pageCount = 10)).containsExactly(7)
    }

    @Test
    fun `simple range expands`() {
        assertThat(PageSelection.parse("2-5", pageCount = 10))
            .containsExactly(2, 3, 4, 5)
            .inOrder()
    }

    @Test
    fun `mixed list is sorted and deduplicated`() {
        assertThat(PageSelection.parse("5, 1-3, 2", pageCount = 10))
            .containsExactly(1, 2, 3, 5)
            .inOrder()
    }

    @Test
    fun `semicolons and extra whitespace are accepted`() {
        assertThat(PageSelection.parse(" 1 ; 3-4 ", pageCount = 10))
            .containsExactly(1, 3, 4)
            .inOrder()
    }

    @Test
    fun `open ended range runs to the last page`() {
        assertThat(PageSelection.parse("8-", pageCount = 10))
            .containsExactly(8, 9, 10)
            .inOrder()
    }

    @Test
    fun `leading dash range starts at page one`() {
        assertThat(PageSelection.parse("-3", pageCount = 10))
            .containsExactly(1, 2, 3)
            .inOrder()
    }

    @Test
    fun `reversed range is accepted as a typo with obvious intent`() {
        assertThat(PageSelection.parse("5-2", pageCount = 10))
            .containsExactly(2, 3, 4, 5)
            .inOrder()
    }

    @Test
    fun `blank input is null rather than empty`() {
        // Distinguishes "hasn't typed yet" from "typed nonsense" in the UI.
        assertThat(PageSelection.parse("   ", pageCount = 10)).isNull()
    }

    @Test
    fun `page beyond the document is rejected`() {
        assertThat(PageSelection.parse("11", pageCount = 10)).isNull()
    }

    @Test
    fun `zero is rejected because pages are one based`() {
        assertThat(PageSelection.parse("0-3", pageCount = 10)).isNull()
    }

    @Test
    fun `non numeric input is rejected`() {
        assertThat(PageSelection.parse("first three", pageCount = 10)).isNull()
    }

    @Test
    fun `one bad token rejects the whole expression`() {
        // Partially honouring a range would silently produce the wrong document.
        assertThat(PageSelection.parse("1-3, banana", pageCount = 10)).isNull()
    }

    @Test
    fun `empty document rejects everything`() {
        assertThat(PageSelection.parse("1", pageCount = 0)).isNull()
    }

    @Test
    fun `format collapses contiguous runs`() {
        assertThat(PageSelection.format(listOf(1, 2, 3, 7, 10, 11, 12)))
            .isEqualTo("1-3, 7, 10-12")
    }

    @Test
    fun `format handles a single page`() {
        assertThat(PageSelection.format(listOf(4))).isEqualTo("4")
    }

    @Test
    fun `format of nothing is empty`() {
        assertThat(PageSelection.format(emptyList())).isEmpty()
    }

    @Test
    fun `format then parse round trips`() {
        val pages = listOf(1, 2, 3, 9, 14, 15)
        val text = PageSelection.format(pages)
        assertThat(PageSelection.parse(text, pageCount = 20)).isEqualTo(pages)
    }

    @Test
    fun `toZeroBased shifts by one`() {
        assertThat(PageSelection.toZeroBased(listOf(1, 4))).containsExactly(0, 3).inOrder()
    }

    @Test
    fun `invert returns the complement`() {
        assertThat(PageSelection.invert(listOf(2, 4), pageCount = 5))
            .containsExactly(1, 3, 5)
            .inOrder()
    }
}
