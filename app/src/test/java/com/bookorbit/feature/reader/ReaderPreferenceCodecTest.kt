package com.bookorbit.feature.reader

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderPreferenceCodecTest {
    @Test
    fun `device settings never enter server patches`() {
        val encoded =
            ReaderPreferenceCodec.encode(ReaderSettings(brightness = .5, volumeKeyPaging = true))
        assertFalse(encoded.containsKey("brightness"))
        assertFalse(encoded.containsKey("volumeKeyPaging"))
        assertFalse(encoded.containsKey("pageTurnEffect"))
        assertEquals(JsonNull, encoded["textIndent"])
    }

    @Test
    fun `patch only contains changed fields including explicit null font reset`() {
        val before = ReaderSettings(fontFamily = "__serverfont_test", fontSize = 22)
        val patch = ReaderPreferenceCodec.changed(before, before.copy(fontFamily = null))
        assertEquals(buildJsonObject { put("fontFamily", JsonNull) }, patch)
    }

    @Test
    fun `web fractional size and nullable spacing map into Android without losing controls`() {
        val base = ReaderSettings(sidePadding = 40, pageCurl = true)
        val result =
            ReaderPreferenceCodec.merge(
                base,
                buildJsonObject {
                    put("fontSize", 22.5)
                    put("letterSpacing", JsonNull)
                    put("textIndent", 2)
                    put("wordSpacing", 1)
                },
            )
        assertEquals(23, result.fontSize)
        assertEquals(0.0, result.letterSpacing, 0.0)
        assertTrue(result.paragraphIndent)
        assertEquals(40, result.sidePadding)
        assertTrue(result.pageCurl)
    }

    @Test
    fun `unrelated Android updates preserve unsupported web settings`() {
        val before = ReaderSettings(fontSize = 20)
        val patch =
            ReaderPreferenceCodec.changed(before, before.copy(fontSize = 24, brightness = .2))
        assertEquals(setOf("fontSize"), patch.keys)
    }
}
