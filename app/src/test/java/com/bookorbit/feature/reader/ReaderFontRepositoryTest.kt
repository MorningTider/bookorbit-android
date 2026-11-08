package com.bookorbit.feature.reader

import android.content.Context
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderFontRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val api = mockk<ApiService>()
    private val session = mockk<SessionManager>()
    private val context = mockk<Context>()
    private val user = mockk<AuthUser>()
    private lateinit var repository: ReaderFontRepository
    private val font = ReaderFont(1, "宋体 Noto Serif", "otf", fileSize = 4, createdAt = "2026-11-08")

    @Before
    fun setup() {
        every { session.serverUrl } returns "https://books.example"
        every { user.id } returns 1
        every { session.currentUser } returns user
        every { context.filesDir } returns directory.root
        coEvery { api.getServerFonts() } returns listOf(font)
        coEvery { api.getUserFonts() } returns emptyList()
        repository = ReaderFontRepository(api, session, Json { ignoreUnknownKeys = true }, context)
    }

    @Test
    fun `downloaded fonts and the catalog remain available offline`() = runTest {
        coEvery { api.serveReaderFont("server-fonts", 1) } returns
            byteArrayOf(79, 84, 84, 79).toResponseBody()
        val family = repository.catalog(true).single()
        assertFalse(family.downloaded)
        repository.download(family)
        val cached = repository.catalog(false).single()
        assertTrue(cached.downloaded)
        assertEquals(1, repository.cachedFaces(cached).size)
        assertEquals(
            4,
            repository.openCached(cached.faces.single().fileName)!!.use { it.readBytes().size },
        )
        coEvery { api.getServerFonts() } throws IOException("offline")
        coEvery { api.getUserFonts() } throws IOException("offline")
        assertTrue(repository.catalog(true).single().downloaded)
        repository.download(cached)
        coVerify(exactly = 1) { api.serveReaderFont(any(), any()) }
    }

    @Test
    fun `a partial font is never selected or treated as downloaded`() = runTest {
        coEvery { api.serveReaderFont(any(), any()) } returns byteArrayOf(1, 2, 3).toResponseBody()
        val family = repository.catalog(true).single()
        assertTrue(runCatching { repository.download(family) }.exceptionOrNull() is IOException)
        assertFalse(repository.catalog(false).single().downloaded)
        assertTrue(repository.cachedFaces(family).isEmpty())
        assertFalse(directory.root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun `font caches are separated by account and server and reject traversal`() = runTest {
        coEvery { api.serveReaderFont(any(), any()) } returns
            byteArrayOf(79, 84, 84, 79).toResponseBody()
        val family = repository.catalog(true).single()
        repository.download(family)
        val path = family.faces.single().fileName
        assertNull(repository.openCached("../$path"))
        every { user.id } returns 2
        assertTrue(repository.catalog(false).isEmpty())
        assertNull(repository.openCached(path))
        every { user.id } returns 1
        every { session.serverUrl } returns "https://other.example"
        assertTrue(repository.catalog(false).isEmpty())
        assertNull(repository.openCached(path))
    }

    @Test
    fun `oversized and unknown font formats are filtered before download`() = runTest {
        coEvery { api.getServerFonts() } returns
            listOf(font.copy(fileSize = 51L * 1024 * 1024), font.copy(id = 2, format = "svg"))
        assertTrue(repository.catalog(true).isEmpty())
    }
}
