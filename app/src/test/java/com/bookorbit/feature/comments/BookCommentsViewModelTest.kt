package com.bookorbit.feature.comments

import com.bookorbit.core.model.BookComment
import com.bookorbit.core.model.BookCommentPage
import com.bookorbit.core.network.ApiService
import com.bookorbit.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookCommentsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val root =
        BookComment(
            1,
            1,
            2,
            "Reader",
            scope = "review",
            body = "Root",
            createdAt = "2026-11-09T10:00:00Z",
            updatedAt = "2026-11-09T10:00:00Z",
        )

    @Test
    fun `server nullable deletion mode is preserved`() {
        val parsed =
            Json.decodeFromString<BookComment>(
                """{"id":1,"bookId":1,"userId":2,"authorName":"Reader","scope":"review","body":"Root","createdAt":"now","updatedAt":"now","deleteMode":null}"""
            )
        assertNull(parsed.deleteMode)
        assertFalse(parsed.canDelete)
    }

    @Test
    fun `opening replies during a pending root request ignores its late result`() = runTest {
        val api = mockk<ApiService>()
        val roots = CompletableDeferred<BookCommentPage>()
        val replies = CompletableDeferred<BookCommentPage>()
        coEvery { api.getBookComments(1, "review", null, null, null, null, null) } coAnswers
            {
                withContext(NonCancellable) { roots.await() }
            }
        coEvery { api.getBookComments(1, "review", null, null, null, 1, null) } coAnswers
            {
                replies.await()
            }
        val vm = BookCommentsViewModel(api)
        vm.open(1, CommentTarget())
        vm.thread(root)
        roots.complete(BookCommentPage(listOf(root)))
        assertTrue(vm.ui.value.loading)
        assertTrue(vm.ui.value.items.isEmpty())
        val reply = root.copy(id = 2, body = "Reply", parentId = 1, threadId = 1)
        replies.complete(BookCommentPage(listOf(reply)))
        assertEquals(listOf(reply), vm.ui.value.items)
        assertFalse(vm.ui.value.loading)
    }

    @Test
    fun `switching books ignores the old target response`() = runTest {
        val api = mockk<ApiService>()
        val old = CompletableDeferred<BookCommentPage>()
        coEvery { api.getBookComments(1, "review", null, null, null, null, null) } coAnswers
            {
                withContext(NonCancellable) { old.await() }
            }
        val next = root.copy(id = 3, bookId = 2, body = "Other book")
        coEvery { api.getBookComments(2, "review", null, null, null, null, null) } returns
            BookCommentPage(listOf(next))
        val vm = BookCommentsViewModel(api)
        vm.open(1, CommentTarget())
        vm.open(2, CommentTarget())
        old.complete(BookCommentPage(listOf(root)))
        assertEquals(listOf(next), vm.ui.value.items)
    }
}
