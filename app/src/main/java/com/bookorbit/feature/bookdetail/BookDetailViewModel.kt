package com.bookorbit.feature.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.db.DownloadEntity
import com.bookorbit.core.model.BookAnnotation
import com.bookorbit.core.model.BookDetail
import com.bookorbit.core.model.BookFiles
import com.bookorbit.core.model.BookRecommendation
import com.bookorbit.core.model.CollectionWithMembership
import com.bookorbit.feature.downloads.DownloadsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val EDIT_METADATA_PERMISSION = "library_edit_metadata"

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BookDetailRepository,
    private val downloads: DownloadsRepository,
    session: SessionManager,
) : ViewModel() {

    val bookId: Int = savedStateHandle.get<Int>("id") ?: 0

    val downloadState = downloads.observe(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    data class UiState(
        val loading: Boolean = true,
        val error: Boolean = false,
        val book: BookDetail? = null,
        val authorBooks: List<BookRecommendation> = emptyList(),
        val recommendations: List<BookRecommendation> = emptyList(),
        val highlights: List<BookAnnotation> = emptyList(),
        /** File id to reading position (0-100), for the per-format progress chips. */
        val fileProgress: Map<Int, Double> = emptyMap(),
        val readingLog: com.bookorbit.core.model.BookReadingLog? = null,
        val logLoading: Boolean = false,
        val logError: Boolean = false,
        val statusUpdating: Boolean = false,
        val ratingUpdating: Boolean = false,
    )

    data class CollectionsState(
        val loading: Boolean = false,
        val items: List<CollectionWithMembership> = emptyList(),
        val togglingId: Int? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val _collections = MutableStateFlow(CollectionsState())
    val collections = _collections.asStateFlow()

    val canRate: Boolean = session.currentUser?.let {
        it.isSuperuser || EDIT_METADATA_PERMISSION in it.permissions
    } ?: false

    init {
        load()
    }

    fun load() {
        _ui.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            val book = loadedBook()
            if (book == null) {
                _ui.update { it.copy(loading = false, error = true) }
                return@launch
            }
            _ui.update { it.copy(loading = false, book = book) }
            // Recommendations load independently; failures are non-fatal.
            launch {
                val authorBooks = runCatching { repo.authorBooks(bookId) }.getOrDefault(emptyList())
                _ui.update { it.copy(authorBooks = authorBooks) }
            }
            launch {
                val recs = runCatching { repo.recommendations(bookId) }.getOrDefault(emptyList())
                _ui.update { it.copy(recommendations = recs) }
            }
            launch {
                val highlights = runCatching { repo.highlights(bookId) }.getOrDefault(emptyList())
                _ui.update { it.copy(highlights = highlights) }
            }
            refreshReadingState()
        }
    }

    fun refreshReadingState() {
        viewModelScope.launch {
            val progress =
                runCatching { repo.bookProgress(bookId) }.getOrDefault(_ui.value.fileProgress)
            _ui.update { it.copy(fileProgress = progress) }
        }
        loadReadingLog()
    }

    fun loadReadingLog(append: Boolean = false) {
        if (_ui.value.logLoading) return
        val current = _ui.value.readingLog
        val page = if (append && current != null) current.page + 1 else 1
        _ui.update { it.copy(logLoading = true, logError = false) }
        viewModelScope.launch {
            try {
                val result = repo.readingLog(bookId, page)
                _ui.update {
                    it.copy(
                        readingLog =
                            result.copy(
                                items =
                                    if (append)
                                        (current?.items.orEmpty() + result.items).distinctBy { row
                                            ->
                                            row.id
                                        }
                                    else result.items
                            )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(logError = true) }
            } finally {
                _ui.update { it.copy(logLoading = false) }
            }
        }
    }

    fun setStatus(status: String) {
        _ui.update { it.copy(statusUpdating = true) }
        viewModelScope.launch {
            runCatching { repo.setReadStatus(bookId, status) }
            reloadBook()
            _ui.update { it.copy(statusUpdating = false) }
        }
    }

    fun setRating(rating: Int?) {
        _ui.update { it.copy(ratingUpdating = true) }
        viewModelScope.launch {
            runCatching { repo.setRating(bookId, rating) }
            reloadBook()
            _ui.update { it.copy(ratingUpdating = false) }
        }
    }

    fun loadCollections() {
        _collections.update { it.copy(loading = true) }
        viewModelScope.launch {
            val items = runCatching { repo.collectionsForBook(bookId) }.getOrDefault(emptyList())
            _collections.update { it.copy(loading = false, items = items) }
        }
    }

    fun toggleCollection(collectionId: Int, isMember: Boolean) {
        _collections.update { it.copy(togglingId = collectionId) }
        viewModelScope.launch {
            runCatching {
                if (isMember) repo.removeFromCollection(collectionId, bookId)
                else repo.addToCollection(collectionId, bookId)
            }
            // Refresh membership + the book's collection chips.
            val items = runCatching { repo.collectionsForBook(bookId) }.getOrDefault(_collections.value.items)
            _collections.update { it.copy(togglingId = null, items = items) }
            reloadBook()
        }
    }

    fun startDownload() {
        val book = _ui.value.book ?: return
        viewModelScope.launch { downloads.startDownload(book) }
    }

    fun removeDownload() {
        viewModelScope.launch { downloads.delete(bookId) }
    }

    private suspend fun reloadBook() {
        val book = loadedBook()
        if (book != null) _ui.update { it.copy(book = book) }
    }

    /**
     * Server detail if reachable, else the offline-downloaded copy, with any not-yet-synced local
     * rating/read-status overlaid so a queued offline edit never appears to be lost or reverted.
     */
    private suspend fun loadedBook(): BookDetail? {
        val book = runCatching { repo.detail(bookId) }.getOrNull() ?: downloads.cachedBook(bookId)
        return book?.let { repo.applyPending(it) }
    }
}
