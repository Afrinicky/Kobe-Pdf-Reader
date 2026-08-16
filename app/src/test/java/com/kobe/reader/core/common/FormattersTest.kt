package com.kobe.reader.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormattersTest {

    @Test
    fun `saving percent reports the reduction`() {
        assertThat(compressionSavingPercent(originalBytes = 1_000, resultBytes = 250))
            .isEqualTo(75)
    }

    @Test
    fun `a file that grew reports no saving rather than a negative one`() {
        // The compress tool keeps the original in this case, so the UI must not
        // claim a negative saving.
        assertThat(compressionSavingPercent(originalBytes = 1_000, resultBytes = 1_400))
            .isEqualTo(0)
    }

    @Test
    fun `no change reports no saving`() {
        assertThat(compressionSavingPercent(originalBytes = 1_000, resultBytes = 1_000))
            .isEqualTo(0)
    }

    @Test
    fun `an unknown original size cannot produce a percentage`() {
        assertThat(compressionSavingPercent(originalBytes = 0, resultBytes = 500)).isEqualTo(0)
    }
}
