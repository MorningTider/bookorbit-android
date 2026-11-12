package com.bookorbit.feature.sources

import com.bookorbit.core.network.MainClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

sealed interface SourceSearchEvent {
    data class Book(val book: SourceBook) : SourceSearchEvent

    data class ProviderFailed(val provider: String) : SourceSearchEvent
}

/** Uses the authenticated client, including certificate trust and silent login refresh. */
@Singleton
class SourceRepository
@Inject
constructor(@MainClient private val client: OkHttpClient, private val json: Json) {
    fun search(title: String, sources: List<String>) = callbackFlow {
        require(sources.isNotEmpty())
        val url =
            "http://localhost/metadata-fetch/stream"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("title", title.trim())
                .addQueryParameter("providers", sources.joinToString(","))
                .addQueryParameter("mediaKind", "ebook")
                .addQueryParameter("resultsPerProvider", "20")
                .build()
        val call =
            client.newCall(Request.Builder().url(url).header("Accept", "text/event-stream").build())
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Empty search response")
                            val input = body.source()
                            var event = ""
                            val data = StringBuilder()
                            fun dispatch() {
                                if (data.isEmpty()) return
                                val payload = data.toString().trimEnd()
                                val result =
                                    if (event == "provider-status") {
                                        val provider =
                                            json
                                                .parseToJsonElement(payload)
                                                .jsonObject["provider"]
                                                ?.jsonPrimitive
                                                ?.content
                                                .orEmpty()
                                        SourceSearchEvent.ProviderFailed(provider)
                                    } else
                                        SourceSearchEvent.Book(
                                            json.decodeFromString<SourceBook>(payload)
                                        )
                                if (trySend(result).isFailure)
                                    throw IOException("Search consumer unavailable")
                                data.clear()
                            }
                            while (!input.exhausted()) {
                                val line = input.readUtf8LineStrict(524_288)
                                when {
                                    line.isEmpty() -> {
                                        dispatch()
                                        event = ""
                                    }
                                    line.startsWith("event:") ->
                                        event = line.substringAfter(':').trim()
                                    line.startsWith("data:") -> {
                                        data
                                            .append(line.substringAfter(':').trimStart())
                                            .append('\n')
                                        if (data.length > 1_048_576)
                                            throw IOException("Search event too large")
                                    }
                                }
                            }
                            dispatch()
                        }
                        close()
                    } catch (e: Exception) {
                        close(e)
                    }
                }
            }
        )
        awaitClose { call.cancel() }
    }
}
