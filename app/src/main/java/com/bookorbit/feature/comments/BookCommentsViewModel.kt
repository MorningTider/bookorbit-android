package com.bookorbit.feature.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookorbit.core.model.BookComment
import com.bookorbit.core.model.CreateBookComment
import com.bookorbit.core.network.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class CommentTarget(
    val scope: String = "review",
    val fileId: Int? = null,
    val chapterHref: String? = null,
    val chapterTitle: String? = null,
    val cfi: String? = null,
    val quote: String? = null,
)

@HiltViewModel
class BookCommentsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {
    data class State(
        val items: List<BookComment> = emptyList(),
        val loading: Boolean = false,
        val sending: Boolean = false,
        val error: Boolean = false,
        val unsupported: Boolean = false,
        val cursor: Int? = null,
        val thread: BookComment? = null,
        val reply: BookComment? = null,
        val draft: String = "",
    )

    private val _ui = MutableStateFlow(State())
    val ui = _ui.asStateFlow()
    private var bookId = 0
    private var target = CommentTarget()
    private var loadJob: Job? = null
    private var writeJob: Job? = null
    private var request: CreateBookComment? = null
    private var targetGeneration = 0

    fun open(bookId: Int, target: CommentTarget) {
        targetGeneration++
        loadJob?.cancel()
        writeJob?.cancel()
        this.bookId = bookId
        this.target = target
        _ui.value = State()
        request = null
        load()
    }

    fun draft(value: String) {
        _ui.update { it.copy(draft = value.take(5000)) }
        request = null
    }

    fun reply(comment: BookComment) {
        _ui.update { it.copy(reply = comment) }
        request = null
    }

    fun thread(comment: BookComment?) {
        if (_ui.value.sending) return
        targetGeneration++
        loadJob?.cancel()
        _ui.update {
            it.copy(
                loading = false,
                items = emptyList(),
                thread = comment,
                reply = comment,
                cursor = null,
                draft = "",
            )
        }
        request = null
        load()
    }

    fun load(more: Boolean = false) {
        if (_ui.value.loading) return
        val generation = targetGeneration
        val cursor = if (more) _ui.value.cursor ?: return else null
        _ui.update { it.copy(loading = true, error = false, unsupported = false) }
        loadJob =
            viewModelScope.launch {
                try {
                    val result =
                        api.getBookComments(
                            bookId,
                            target.scope,
                            target.fileId,
                            if (target.scope == "chapter") target.chapterHref else null,
                            if (target.scope == "paragraph") target.cfi else null,
                            _ui.value.thread?.id,
                            cursor,
                        )
                    if (generation != targetGeneration) return@launch
                    _ui.update {
                        it.copy(
                            items =
                                if (more) (it.items + result.items).distinctBy { row -> row.id }
                                else result.items,
                            cursor = result.nextCursor,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation != targetGeneration) return@launch
                    _ui.update {
                        it.copy(error = true, unsupported = e is HttpException && e.code() == 404)
                    }
                } finally {
                    if (generation == targetGeneration) _ui.update { it.copy(loading = false) }
                }
            }
    }

    fun send() {
        val state = _ui.value
        val generation = targetGeneration
        if (state.sending || state.draft.isBlank()) return
        val payload =
            request
                ?: CreateBookComment(
                        UUID.randomUUID().toString(),
                        state.draft.trim(),
                        state.reply?.id,
                        target.scope,
                        target.fileId,
                        target.chapterHref,
                        target.chapterTitle,
                        target.cfi,
                        target.quote,
                    )
                    .also { request = it }
        _ui.update { it.copy(sending = true, error = false) }
        writeJob =
            viewModelScope.launch {
                try {
                    api.createBookComment(bookId, payload)
                    if (generation != targetGeneration) return@launch
                    request = null
                    _ui.update { it.copy(draft = "") }
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (generation != targetGeneration) return@launch
                    _ui.update { it.copy(error = true) }
                } finally {
                    if (generation == targetGeneration) _ui.update { it.copy(sending = false) }
                }
            }
    }

    fun delete(comment: BookComment) {
        val generation = targetGeneration
        if (_ui.value.sending) return
        _ui.update { it.copy(sending = true, error = false) }
        writeJob =
            viewModelScope.launch {
                try {
                    api.deleteBookComment(
                        bookId,
                        comment.id,
                        if (comment.deleteMode == "moderate") "moderate" else "own",
                    )
                    if (generation != targetGeneration) return@launch
                    load()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (generation != targetGeneration) return@launch
                    _ui.update { it.copy(error = true) }
                } finally {
                    if (generation == targetGeneration) _ui.update { it.copy(sending = false) }
                }
            }
    }
}
