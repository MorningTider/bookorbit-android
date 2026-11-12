package com.bookorbit.feature.sources

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.bookorbit.R
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import com.bookorbit.ui.theme.BookOrbitTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SourcesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun partialSearchImportedBadgeDuplicateDecisionAndCompletedRecordDeletion() {
        val json = NetworkModule.provideJson()
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        var deleted = false
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val body = request.body.readUtf8()
                    calls.add(path to body)
                    val response =
                        when {
                            path == "/book-sources/status" ->
                                """{"fanqie":{"enabled":true,"configured":true},"qidian":{"enabled":false,"configured":true}}"""
                            path == "/libraries" ->
                                """[{"id":7,"name":"电子书","accessLevel":"owner","allowedFormats":["epub"],"createdAt":"test","updatedAt":"test"}]"""
                            path == "/metadata-fetch/stream" ->
                                return MockResponse()
                                    .setHeader("Content-Type", "text/event-stream")
                                    .setBody(
                                        "data: {\"provider\":\"fanqie\",\"providerId\":\"123\",\"title\":\"测试小说\",\"authors\":[\"作者\"],\"description\":\"完整小说简介\"}\n\n"
                                    )
                            path == "/imports/check" ->
                                """[{"key":"fanqie:123","matches":[{"bookId":4,"libraryId":7,"libraryName":"电子书","title":"现有书籍","canEdit":true,"files":[{"id":6,"filename":"existing.epub","format":"epub"}]}]}]"""
                            request.method == "DELETE" -> {
                                deleted = true
                                """{"id":9,"libraryId":7,"title":"已完成测试","status":"completed"}"""
                            }
                            path == "/book-sources/jobs" ->
                                if (deleted) """{"items":[],"nextCursor":null}"""
                                else
                                    """{"items":[{"id":9,"libraryId":7,"title":"已完成测试","status":"completed","resultBookId":4}],"nextCursor":null}"""
                            path == "/libraries/7/sources/fanqie" ->
                                """{"id":10,"libraryId":7,"title":"测试小说","status":"queued"}"""
                            else -> return MockResponse().setResponseCode(404)
                        }
                    return MockResponse().setBody(response)
                }
            }
        server.start()
        val context = compose.activity
        val name = "sources-ui-${System.nanoTime()}"
        val storage = SecureStorage(context, name, name, "$name-owner")
        val session = SessionManager(storage, json)
        session.setServerUrl(server.url("/").toString())
        session.signIn("isolated", AuthUser(1, "isolated", isSuperuser = true))
        val client =
            OkHttpClient.Builder()
                .addInterceptor {
                    val original = it.request()
                    it.proceed(
                        original
                            .newBuilder()
                            .url(
                                server.url(
                                    original.url.encodedPath +
                                        (original.url.encodedQuery?.let { q -> "?$q" } ?: "")
                                )
                            )
                            .build()
                    )
                }
                .build()
        val api =
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ApiService::class.java)
        lateinit var vm: SourcesViewModel
        try {
            compose.runOnUiThread {
                vm = SourcesViewModel(api, SourceRepository(client, json), session)
            }
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.bookorbit.ui.LocalImageUrls provides
                        com.bookorbit.core.network.ImageUrls(session)
                ) {
                    BookOrbitTheme(darkTheme = false) {
                        Scaffold { padding ->
                            Box(Modifier.padding(padding)) { SourcesScreen({}, vm) }
                        }
                    }
                }
            }
            compose.waitUntil(5000) { !vm.state.value.loading }
            compose.onNodeWithText(context.getString(R.string.sources_query)).performTextInput("测试")
            compose
                .onNodeWithContentDescription(context.getString(R.string.sources_search))
                .performClick()
            compose.waitUntil(5000) { vm.state.value.checked }
            compose.onNodeWithText(context.getString(R.string.sources_imported)).assertExists()
            compose.onNodeWithText("完整小说简介").assertExists()
            val screenshot =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .takeScreenshot()
            java.io.File(context.filesDir, "custom2-sources.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            compose
                .onNodeWithText(context.getString(R.string.sources_download_again))
                .performScrollTo()
                .performClick()
            compose
                .onNodeWithText(context.getString(R.string.sources_attach))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(5000) { calls.any { it.first == "/libraries/7/sources/fanqie" } }
            val posted =
                json
                    .parseToJsonElement(
                        calls.first { it.first == "/libraries/7/sources/fanqie" }.second
                    )
                    .jsonObject
            assertEquals(
                "attach",
                posted["importResolution"]!!.jsonObject["action"]!!.jsonPrimitive.content,
            )
            compose
                .onNodeWithTag("source-items")
                .performScrollToNode(hasText(context.getString(R.string.sources_delete)))
            compose
                .onNodeWithText(context.getString(R.string.sources_delete))
                .performScrollTo()
                .performClick()
            compose.onNodeWithText(context.getString(R.string.sources_delete_hint)).assertExists()
            compose
                .onAllNodesWithText(context.getString(R.string.sources_delete))
                .onLast()
                .performClick()
            compose.waitUntil(5000) { deleted && vm.state.value.jobs.isEmpty() }
            assertTrue(calls.none { it.first.startsWith("/books/") })
        } finally {
            session.signOut()
            context.deleteSharedPreferences(name)
            server.shutdown()
        }
    }
}
