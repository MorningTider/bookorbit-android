package com.bookorbit.feature.comments

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.BookComment
import com.bookorbit.core.model.BookCommentPage
import com.bookorbit.core.model.CreateBookComment
import com.bookorbit.core.network.ApiService
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class BookCommentsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun publicReviewReplyRetryAndDeletionConfirmation() {
        val json = NetworkModule.provideJson()
        val submissions = CopyOnWriteArrayList<CreateBookComment>()
        val replies = CopyOnWriteArrayList<BookComment>()
        val root =
            BookComment(
                1,
                1,
                2,
                "另一位读者",
                scope = "review",
                body = "已存在的书评",
                createdAt = "2026-11-09T10:00:00Z",
                updatedAt = "2026-11-09T10:00:00Z",
            )
        val roots = CopyOnWriteArrayList(listOf(root))
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "GET") {
                        val items =
                            if (request.path.orEmpty().contains("threadId=1")) replies.toList()
                            else roots.toList()
                        return MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(json.encodeToString(BookCommentPage(items)))
                    }
                    if (request.method == "POST") {
                        val dto = json.decodeFromString<CreateBookComment>(request.body.readUtf8())
                        submissions.add(dto)
                        if (submissions.size == 1) return MockResponse().setResponseCode(503)
                        val created =
                            root.copy(
                                id = submissions.size,
                                userId = 1,
                                authorName = "我",
                                parentId = dto.parentId,
                                threadId = dto.parentId?.let { 1 },
                                body = dto.body,
                                canDelete = true,
                                deleteMode = "own",
                            )
                        if (dto.parentId != null) replies.add(created) else roots.add(0, created)
                        return MockResponse()
                            .setResponseCode(201)
                            .setHeader("Content-Type", "application/json")
                            .setBody(json.encodeToString(created))
                    }
                    if (request.method == "DELETE") {
                        replies.clear()
                        return MockResponse().setResponseCode(204)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        server.start()
        val api =
            Retrofit.Builder()
                .baseUrl(server.url("/api/v1/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ApiService::class.java)
        val vm = BookCommentsViewModel(api)
        try {
            compose.setContent { MaterialTheme { BookCommentsSheet(1, onDismiss = {}, vm = vm) } }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("已存在的书评").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("写下你的评论").performTextInput("我的书评")
            compose.onNodeWithText("发送").performClick()
            compose.waitUntil(5000) { vm.ui.value.error }
            assertEquals("我的书评", vm.ui.value.draft)
            compose.onNodeWithText("发送").performClick()
            compose.waitUntil(5000) { vm.ui.value.items.any { it.body == "我的书评" } }
            assertEquals(submissions[0].clientId, submissions[1].clientId)
            compose.onNodeWithTag("book-comments-list").performScrollToNode(hasText("已存在的书评"))
            compose
                .onNode(hasText("查看回复") and hasAnyAncestor(hasTestTag("book-comment-1")))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitUntil(5000) { vm.ui.value.thread?.id == 1 && !vm.ui.value.loading }
            compose.onNodeWithText("写下你的评论").performTextInput("回复正文")
            compose.onNodeWithText("发送").performClick()
            compose.waitUntil(5000) { vm.ui.value.items.any { it.body == "回复正文" } }
            assertEquals(1, submissions.last().parentId)
            compose.onNodeWithTag("book-comments-list").performScrollToNode(hasText("删除"))
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除这条评论？").assertExists()
            compose.onNodeWithText("取消").performClick()
            assertEquals(1, replies.size)
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithTag("confirm-comment-delete").performClick()
            compose.waitUntil(5000) { replies.isEmpty() && !vm.ui.value.sending }
        } finally {
            server.shutdown()
        }
    }
}
