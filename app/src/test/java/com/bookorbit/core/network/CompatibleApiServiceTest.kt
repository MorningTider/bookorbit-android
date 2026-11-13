package com.bookorbit.core.network

import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.SaveFileProgress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class CompatibleApiServiceTest {
    private fun api(server: MockWebServer): ApiService {
        val json = NetworkModule.provideJson()
        val retrofit =
            Retrofit.Builder()
                .baseUrl(server.url("/api/v1/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        return NetworkModule.provideApiService(retrofit)
    }

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `3_7 nested text position maps to the existing reader and writes the public DTO`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(
                    json(
                        """{"items":[{"fileId":4,"percentage":21.5,"updatedAt":"2026-01-02T00:00:00Z","text":{"cfi":"epubcfi(saved)","page":8,"updatedAt":"2026-01-01T00:00:00Z"},"narration":{"seconds":45}}]}"""
                    )
                )
                server.enqueue(json("""{"results":[]}"""))
                val api = api(server)
                val progress = api.getFileProgress(4)!!
                assertEquals("epubcfi(saved)", progress.cfi)
                assertEquals(21.5, progress.percentage, .001)
                assertEquals(8, progress.pageNumber)
                assertEquals("2026-01-01T00:00:00Z", progress.textUpdatedAt)
                api.saveFileProgress(4, SaveFileProgress("epubcfi(next)", 34.2, 12, "text"))
                assertEquals("/api/v1/reading-progress?fileId=4", server.takeRequest().path)
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/api/v1/reading-progress", request.path)
                val write =
                    Json.parseToJsonElement(request.body.readUtf8())
                        .jsonObject["writes"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(setOf("fileId", "percentage", "kind", "text"), write.keys)
                assertEquals("4", write["fileId"]!!.jsonPrimitive.content)
                assertEquals("text", write["kind"]!!.jsonPrimitive.content)
                assertEquals(
                    "epubcfi(next)",
                    write["text"]!!.jsonObject["cfi"]!!.jsonPrimitive.content,
                )
                assertEquals("12", write["text"]!!.jsonObject["page"]!!.jsonPrimitive.content)
                assertEquals(2, server.requestCount)
            }
        }

    @Test
    fun `3_5 progress falls back only when the public route is unavailable`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(json("""{"cfi":"old","percentage":10.0}"""))
            server.enqueue(MockResponse().setResponseCode(405))
            server.enqueue(MockResponse().setResponseCode(201))
            val api = api(server)
            assertEquals("old", api.getFileProgress(4)!!.cfi)
            api.saveFileProgress(4, SaveFileProgress("new", 20.0, source = "text"))
            assertEquals("/api/v1/reading-progress?fileId=4", server.takeRequest().path)
            assertEquals("/api/v1/books/files/4/progress", server.takeRequest().path)
            assertEquals("PUT", server.takeRequest().method)
            val legacy = server.takeRequest()
            assertEquals("POST", legacy.method)
            assertEquals("/api/v1/books/files/4/progress", legacy.path)
            assertEquals(
                "new",
                Json.parseToJsonElement(legacy.body.readUtf8())
                    .jsonObject["cfi"]!!
                    .jsonPrimitive
                    .content,
            )
        }
    }

    @Test
    fun `authentication authorization and server failures never use the legacy route`() = runTest {
        for (status in listOf(401, 403, 429, 500, 503)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status))
                server.enqueue(MockResponse().setResponseCode(status))
                val api = api(server)
                try {
                    api.getFileProgress(4)
                    fail("GET should preserve $status")
                } catch (error: HttpException) {
                    assertEquals(status, error.code())
                }
                try {
                    api.saveFileProgress(4, SaveFileProgress(percentage = 10.0))
                    fail("PUT should preserve $status")
                } catch (error: HttpException) {
                    assertEquals(status, error.code())
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `3_7 smart shelf retains pagination and query`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(json("""{"items":[],"total":101,"page":2,"size":20}"""))
            val result = api(server).getSmartScopeBooks(7, 2, 20, "小说")
            assertEquals(101, result.total)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/smart-scopes/7/books/query", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("小说", body["q"]!!.jsonPrimitive.content)
            assertEquals("2", body["pagination"]!!.jsonObject["page"]!!.jsonPrimitive.content)
            assertEquals("20", body["pagination"]!!.jsonObject["size"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `3_5 smart shelf preserves query and pagination on fallback`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(json("""{"items":[],"total":3}"""))
            assertEquals(3, api(server).getSmartScopeBooks(7, 1, 20, "book").total)
            assertEquals("/api/v1/smart-scopes/7/books/query", server.takeRequest().path)
            assertEquals("/api/v1/smart-scopes/7/books?page=1&size=20&q=book", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }
}
