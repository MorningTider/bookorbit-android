package com.bookorbit.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontTest {
    private fun face(id: Int, weight: Int = 400, style: String = "normal") =
        ScopedReaderFont(
            "server",
            ReaderFont(
                id,
                "宋体 Noto Serif CJK SC",
                "otf",
                weight,
                style,
                fileSize = 42,
                createdAt = "2026-11-08",
            ),
        )

    @Test
    fun `CSS family names match server normalization and separate scopes`() {
        assertEquals(
            "__serverfont_noto_serif_cjk_sc",
            readerFontCssFamily("宋体 Noto Serif CJK SC", "server"),
        )
        assertNotEquals(readerFontCssFamily("Noto", "user"), readerFontCssFamily("Noto", "server"))
    }

    @Test
    fun `a family downloads at most four faces and covers bold and italic`() {
        val faces =
            listOf(
                face(1),
                face(2, 700),
                face(3, 400, "italic"),
                face(4, 700, "italic"),
                face(5, 300),
            )
        assertEquals(listOf(1, 2, 3, 4), selectableFontFaces(faces).map { it.font.id })
    }

    @Test
    fun `a variable file can cover multiple weights with one download`() {
        val face = face(1).let { it.copy(font = it.font.copy(weightMin = 100, weightMax = 900)) }
        assertEquals(listOf(face), selectableFontFaces(listOf(face)))
    }

    @Test
    fun `cache paths do not contain untrusted family names`() {
        val face = face(1).let { it.copy(font = it.font.copy(familyName = "../../private")) }
        assertTrue(Regex("server-1-42-[a-f0-9]{12}\\.otf").matches(face.fileName))
    }
}
