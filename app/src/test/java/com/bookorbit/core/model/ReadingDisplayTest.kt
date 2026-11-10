package com.bookorbit.core.model

import org.junit.Assert.*
import org.junit.Test

class ReadingDisplayTest {
    @Test
    fun `reader details and shelf use the same decimal rounding`() {
        assertEquals("0.6", readingPercentage(.56))
        assertEquals("22", readingPercentage(21.96))
        assertEquals("0", readingPercentage(Double.NaN))
        assertEquals("100", readingPercentage(101.0))
    }

    @Test
    fun `remaining time needs measured forward reading and counts completed as zero`() {
        assertNull(remainingReadingSeconds(20.0, 0.0, 600))
        assertNull(remainingReadingSeconds(20.0, 5.0, 5))
        assertEquals(4800L, remainingReadingSeconds(20.0, 10.0, 600))
        assertEquals(0L, remainingReadingSeconds(100.0, 0.0, 0))
    }
}
