package com.bookorbit.feature.reader

import android.content.Context
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.model.BookDetail
import com.bookorbit.core.model.BookFileRef
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.storage.LocalRef
import com.bookorbit.feature.downloads.DownloadsRepository
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ReaderSourceTest {
    @Test
    fun `reader cache is account scoped and incomplete bytes cannot become a cached book`() =
        runBlocking {
            val directory = Files.createTempDirectory("reader-source").toFile()
            val context = mockk<Context> { every { cacheDir } returns directory
            every { getString(any()) } returns "Download error" }
            var userId = 1
            val session =
                mockk<SessionManager> {
                    every { serverUrl } returns "https://isolated.test"
                    every { currentUser } answers { AuthUser(userId, "isolated") }
                }
            val api = mockk<ApiService>()
            val downloads = mockk<DownloadsRepository>()
            coEvery { downloads.localFiles(1) } returns emptyMap()
            val ref = BookFileRef(5, "txt", "primary", sizeBytes = 4)
            val book =
                BookDetail(
                    id = 1,
                    libraryId = 7,
                    libraryName = "Test",
                    status = "active",
                    addedAt = "test",
                    files = listOf(ref),
                )
            val source = ReaderSource(api, downloads, session, context)
            try {
                coEvery { api.serveFile(5) } returns "BAD".toResponseBody()
                try {
                    source.resolve(book)
                    fail("Incomplete bytes must be rejected")
                } catch (_: java.io.IOException) {}
                assertTrue(directory.walkTopDown().filter { it.isFile }.toList().isEmpty())
                coEvery { api.serveFile(5) } returns "BOOK".toResponseBody()
                val first = source.resolve(book).ref as LocalRef.PlainFile
                assertEquals("BOOK", first.file.readText())
                source.resolve(book)
                coVerify(exactly = 2) { api.serveFile(5) }
                userId = 2
                coEvery { api.serveFile(5) } returns "NEXT".toResponseBody()
                val other = source.resolve(book).ref as LocalRef.PlainFile
                assertNotEquals(first.file.parent, other.file.parent)
                assertEquals("NEXT", other.file.readText())
                assertEquals("BOOK", first.file.readText())
            } finally {
                directory.deleteRecursively()
            }
        }
}
