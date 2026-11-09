package com.bookorbit.feature.reader

import com.bookorbit.core.db.ReaderProgressDao
import com.bookorbit.core.db.ReaderProgressEntity
import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.FileProgress
import com.bookorbit.core.model.SaveFileProgress
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.sync.SyncScheduler
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Uses the production Retrofit interface and serializer across an actual local HTTP connection. */
class ReaderSyncHttpTest {
    @Test
    fun `android upload and web backward move share the same file progress`() = runTest {
        val json = NetworkModule.provideJson()
        var saved = FileProgress("web-start", 20.0, textUpdatedAt = "2026-01-01T00:00:00Z")
        var uploaded: SaveFileProgress? = null
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/api/v1/books/files/1/progress")
                        return MockResponse().setResponseCode(404)
                    if (request.method == "POST") {
                        uploaded = json.decodeFromString<SaveFileProgress>(request.body.readUtf8())
                        saved =
                            FileProgress(
                                uploaded!!.cfi,
                                uploaded!!.percentage,
                                textUpdatedAt = Instant.now().toString(),
                            )
                        return MockResponse().setResponseCode(201)
                    }
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(json.encodeToString(saved))
                }
            }
        server.start()
        try {
            val api =
                Retrofit.Builder()
                    .baseUrl(server.url("/api/v1/"))
                    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                    .build()
                    .create(ApiService::class.java)
            val dao = mockk<ReaderProgressDao>()
            var row: ReaderProgressEntity? = null
            coEvery { dao.get(1) } answers { row }
            coEvery { dao.upsert(any()) } answers { row = firstArg() }
            coEvery { dao.markSynced(1) } answers { row = row?.copy(dirty = false) }
            val repo = ReaderProgressRepository(dao, api, mockk<SyncScheduler>(relaxed = true))
            assertEquals(InitialProgress("web-start", .2), repo.resolveInitial(1))
            repo.report(1, "android-next", 42.0)
            assertEquals("android-next", uploaded?.cfi)
            assertEquals("text", uploaded?.source)
            assertEquals(42.0, saved.percentage, .001)
            saved = FileProgress("web-back", 15.0, textUpdatedAt = Instant.now().toString())
            assertEquals(InitialProgress("web-back", .15), repo.newerRemote(1))
            assertEquals(InitialProgress("web-back", .15), repo.resolveInitial(1))
            assertFalse(row!!.dirty)
        } finally {
            server.shutdown()
        }
    }
}
