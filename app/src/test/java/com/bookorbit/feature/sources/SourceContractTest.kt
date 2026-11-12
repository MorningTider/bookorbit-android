package com.bookorbit.feature.sources

import com.bookorbit.core.network.ApiService
import com.bookorbit.feature.reader.ReaderPreferencePatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SourceContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `source status honors both configured and enabled flags`() {
        val status =
            json.decodeFromString<SourceStatus>(
                """{"fanqie":{"enabled":true,"configured":true},"qidian":{"enabled":false,"configured":true}}"""
            )
        assertEquals(listOf("fanqie"), status.enabled())
        assertTrue(SourceStatus().enabled().isEmpty())
    }

    @Test
    fun `search reads split multiline events and provider failures while encoding partial query`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val client =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .url(
                                        server.url(
                                            chain.request().url.encodedPath +
                                                "?" +
                                                chain.request().url.encodedQuery
                                        )
                                    )
                                    .build()
                            )
                        }
                        .build()
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setChunkedBody(
                            """
                : keep alive
                data: {"provider":"fanqie","providerId":"9007199254740999",
                data: "title":"我在","authors":["作者"],"coverUrl":"https://example.test/cover.jpg"}

                event: provider-status
                data: {"provider":"qidian","outcome":"timeout"}

            """
                                .trimIndent() + "\n\n",
                            7,
                        )
                )
                val events =
                    withTimeout(5000) {
                        SourceRepository(client, json)
                            .search("我在 &", listOf("fanqie", "qidian"))
                            .toList()
                    }
                assertEquals(2, events.size)
                assertEquals(
                    "9007199254740999",
                    (events[0] as SourceSearchEvent.Book).book.providerId,
                )
                assertEquals("qidian", (events[1] as SourceSearchEvent.ProviderFailed).provider)
                val url = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
                assertEquals("我在 &", url.queryParameter("title"))
                assertEquals("fanqie,qidian", url.queryParameter("providers"))
                assertEquals("20", url.queryParameter("resultsPerProvider"))
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `acquisition omits decisions until user explicitly chooses and uses version guarded replace`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/api/v1/"))
                        .addConverterFactory(
                            json.asConverterFactory("application/json".toMediaType())
                        )
                        .build()
                        .create(ApiService::class.java)
                val job = """{"id":1,"libraryId":7,"title":"书","status":"queued"}"""
                server.enqueue(MockResponse().setResponseCode(202).setBody(job))
                api.acquireSource(7, "fanqie", SourceAcquisition("123"))
                val request = server.takeRequest()!!
                assertEquals("/api/v1/libraries/7/sources/fanqie", request.path)
                assertEquals("""{"bookId":"123"}""", request.body.readUtf8())
                server.enqueue(MockResponse().setBody("""{"sha256":"abc","bookId":4,"fileId":5}"""))
                val version = api.importFileVersion(5)
                assertEquals("/api/v1/imports/files/5/version", server.takeRequest()!!.path)
                server.enqueue(MockResponse().setResponseCode(202).setBody(job))
                api.acquireSource(
                    7,
                    "fanqie",
                    SourceAcquisition("123", ImportResolution("replace", 4, 5, version.sha256)),
                )
                val body =
                    json
                        .parseToJsonElement(server.takeRequest()!!.body.readUtf8())
                        .jsonObject["importResolution"]!!
                        .jsonObject
                assertEquals("abc", body["expectedSha256"]!!.jsonPrimitive.content)
                server.enqueue(MockResponse().setResponseCode(204))
                api.patchReaderDefault(
                    ReaderPreferencePatch(buildJsonObject { put("fontSize", 24) })
                )
                val patch = server.takeRequest()!!
                assertEquals("PATCH", patch.method)
                assertEquals("/api/v1/reader/defaults/epub", patch.path)
                assertEquals("""{"set":{"fontSize":24}}""", patch.body.readUtf8())
            } finally {
                server.shutdown()
            }
        }
}
