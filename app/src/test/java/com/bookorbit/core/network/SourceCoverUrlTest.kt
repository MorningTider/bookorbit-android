package com.bookorbit.core.network

import com.bookorbit.core.auth.SessionManager
import io.mockk.every
import io.mockk.mockk
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SourceCoverUrlTest {
    @Test
    fun `source cover stays on the authenticated server including reverse proxy subpath`() {
        val session =
            mockk<SessionManager> { every { serverUrl } returns "https://books.test/library" }
        val urls = ImageUrls(session)
        val source = "https://cover.test/a.jpg?x=1&y=2"
        val result = urls.sourceCover(source)!!.toHttpUrl()
        assertEquals("books.test", result.host)
        assertEquals("/library/api/v1/books/cover/proxy", result.encodedPath)
        assertEquals(source, result.queryParameter("url"))
        assertEquals(result.toString(), urls.sourceCover(result.toString()))
        assertNull(urls.sourceCover("javascript:alert(1)"))
        assertNull(urls.sourceCover(null))
    }
}
