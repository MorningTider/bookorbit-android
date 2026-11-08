package com.bookorbit.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderContentsTest {
    @Test
    fun `contents preserve order depth and duplicate labels`() {
        val entries =
            readerContents(
                listOf(
                    TocItem(
                        "卷一",
                        "volume",
                        listOf(TocItem("第一章", "chapter1"), TocItem("第二章", "chapter2")),
                    ),
                    TocItem("卷二", null, listOf(TocItem("第一章", "chapter3"))),
                )
            )
        assertEquals(listOf("volume", "chapter1", "chapter2", "chapter3"), entries.map { it.href })
        assertEquals(listOf(0, 1, 1, 1), entries.map { it.depth })
        assertEquals(listOf(0, 1, 2, 3), entries.map { it.position })
    }

    @Test
    fun `large chapter lists retain stable unique positions`() {
        val toc = (1..10_000).map { TocItem("第${it}章", "chapter-$it") }
        val entries = readerContents(toc)
        assertEquals(10_000, entries.size)
        assertEquals("chapter-10000", entries.last().href)
        assertEquals(10_000, entries.map { it.position }.toSet().size)
    }
}
