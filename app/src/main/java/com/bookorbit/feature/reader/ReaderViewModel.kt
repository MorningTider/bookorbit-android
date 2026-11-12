package com.bookorbit.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookorbit.R
import com.bookorbit.core.model.BookAnnotation
import com.bookorbit.core.model.BookBookmark
import com.bookorbit.core.storage.LocalRef
import com.bookorbit.feature.bookdetail.BookDetailRepository
import com.bookorbit.feature.downloads.DownloadsRepository
import com.bookorbit.feature.sessions.ReadingSessionTracker
import com.bookorbit.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PROGRESS_THROTTLE_MS = 2_000L

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepo: BookDetailRepository,
    private val downloads: DownloadsRepository,
    private val source: ReaderSource,
    private val progress: ReaderProgressRepository,
    private val settingsStore: ReaderSettingsStore,
    private val preferenceSync: ReaderPreferenceSync,
    val fontRepository: ReaderFontRepository,
    private val sessions: ReadingSessionTracker,
    private val annotationRepo: ReaderAnnotationRepository,
    private val bookmarkRepo: ReaderBookmarkRepository,
) : ViewModel() {

    val bookId: Int = savedStateHandle.get<Int>("id") ?: 0

    data class ResolvedOpen(
        val ref: LocalRef,
        val format: String,
        val fileId: Int,
        val initial: InitialProgress,
        val textTitle: String? = null,
    )

    /** Text the user has selected in the page, not yet saved as a highlight. */
    data class PendingSelection(val text: String, val cfi: String)

    /**
     * What the floating highlight toolbar is acting on: a new selection, or an existing highlight.
     */
    data class ToolbarTarget(
        val rect: ViewportRect?,
        val selection: PendingSelection? = null,
        val annotationId: Int? = null,
    )

    data class UiState(
        val loadingFile: Boolean = true,
        val error: UiText? = null,
        val title: String? = null,
        val syncedPosition: InitialProgress? = null,
        val author: String = "",
        val description: String = "",
        val resolved: ResolvedOpen? = null,
        val settings: ReaderSettings = ReaderSettings(),
        val perBookSettings: Boolean = false,
        val syncSettings: Boolean = false,
        val settingsPending: Boolean = false,
        val fonts: List<ReaderFontFamily> = emptyList(),
        val fontFaces: List<ReaderFontFace> = emptyList(),
        val fontsLoading: Boolean = false,
        val downloadingFont: String? = null,
        val toc: List<TocItem> = emptyList(),
        val chapterTitle: String? = null,
        val chapterHref: String? = null,
        val percentage: Int = 0,
        val precisePercentage: Double = 0.0,
        val currentPage: Int? = null,
        val totalPages: Int? = null,
        val wholeBookPages: Boolean = false,
        val elapsedSeconds: Long = 0,
        val remainingSeconds: Long? = null,
        val loaded: Boolean = false,
        val showPagingHint: Boolean = false,
        val annotations: List<BookAnnotation> = emptyList(),
        val bookmarks: List<BookBookmark> = emptyList(),
        val search: SearchState? = null,
        val toolbar: ToolbarTarget? = null,
        /** One-shot message for the screen to toast, then clear via [consumeMessage]. */
        val message: UiText? = null,
    )

    /** In-book search: the query, matches grouped by chapter as they stream in, and progress. */
    data class SearchState(
        val query: String = "",
        val running: Boolean = false,
        val progress: Double = 0.0,
        val groups: List<SearchGroup> = emptyList(),
        val total: Int = 0,
        val capped: Boolean = false,
        val done: Boolean = false,
    )

    data class SearchGroup(val label: String, val hits: List<SearchHit>)

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private var settingsSaveJob: Job? = null
    private var fontLoadJob: Job? = null
    private var lastSaved = 0L
    private var historicalStats = com.bookorbit.core.model.BookReadingStats()
    private var readingTimeJob: Job? = null
    private var checkingProgress = false
    private var pending: Pair<String?, Double>? = null

    /** Where the reader is now, so a bookmark can be dropped there. */
    private var currentCfi: String? = null

    init {
        load()
        loadAnnotations()
    }

    private fun load() {
        viewModelScope.launch {
            val settings = settingsStore.load()
            // Coach the paginated tap-zones once (they're invisible by design).
            val showHint = settings.flow == "paginated" && !settingsStore.hasSeenPagingHint()
            _ui.update { it.copy(settings = settings, showPagingHint = showHint) }
            // The cached selected face is part of the initial book open, before any page is
            // revealed.
            try {
                val fonts = fontRepository.catalog(false)
                val selected = fonts.firstOrNull { it.cssFamily == settings.fontFamily }
                val faces = fontRepository.cachedFaces(selected)
                _ui.update { it.copy(fonts = fonts, fontFaces = faces) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _ui.update { it.copy(message = UiText.of(R.string.rs_fonts_error)) }
            }
            refreshFonts()

            // Fall back to the offline-downloaded copy when the network is unavailable.
            val book = runCatching { bookRepo.detail(bookId) }.getOrNull() ?: downloads.cachedBook(bookId)
            if (book == null) {
                _ui.update { it.copy(loadingFile = false, error = UiText.of(R.string.reader_err_load_book)) }
                return@launch
            }
            _ui.update {
                it.copy(
                    title = book.title,
                    author = book.authors.joinToString("、") { author -> author.name },
                    description = book.description.orEmpty(),
                )
            }

            try {
                val history = async {
                    runCatching { bookRepo.readingLog(bookId, 1).stats }
                        .getOrDefault(historicalStats)
                }
                val resolved = source.resolve(book)
                val profile = preferenceSync.load(resolved.fileId, settings, resolved.format.lowercase() != "cbz")
                val fonts = fontRepository.catalog(false)
                val selected = fonts.firstOrNull { it.cssFamily == profile.settings.fontFamily }
                if (selected != null && !selected.downloaded) runCatching { fontRepository.download(selected) }
                val faces = fontRepository.cachedFaces(selected)
                val initial = progress.resolveInitial(resolved.fileId)
                historicalStats = history.await()
                _ui.update {
                    it.copy(
                        loadingFile = false,
                        settings = profile.settings,
                        perBookSettings = profile.perBook,
                        syncSettings = profile.sync,
                        settingsPending = profile.pending,
                        fontFaces = faces,
                        resolved = ResolvedOpen(resolved.ref, resolved.format, resolved.fileId, initial, book.files.firstOrNull { f -> f.id == resolved.fileId }?.filename?.substringBeforeLast('.')),
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        loadingFile = false,
                        error =
                            e.message?.let { m -> UiText.raw(m) }
                                ?: UiText.of(R.string.reader_err_open_book),
                    )
                }
            }
        }
    }

    fun checkSyncedProgress() {
        val fileId = _ui.value.resolved?.fileId ?: return
        if (!_ui.value.loaded || checkingProgress || _ui.value.syncedPosition != null) return
        flush()
        checkingProgress = true
        viewModelScope.launch {
            try {
                val position =
                    progress.newerRemote(
                        fileId,
                        InitialProgress(currentCfi, _ui.value.precisePercentage / 100.0),
                    )
                if (
                    position != null &&
                        (position.cfi != currentCfi ||
                            position.cfi == null &&
                                position.fraction != _ui.value.precisePercentage / 100.0)
                ) {
                    _ui.update { it.copy(syncedPosition = position) }
                }
            } finally {
                checkingProgress = false
            }
        }
    }

    fun dismissSyncedProgress() = _ui.update { it.copy(syncedPosition = null) }

    fun keepCurrentProgress() {
        dismissSyncedProgress()
        report(currentCfi, _ui.value.precisePercentage)
        flush()
    }

    fun onLoaded(toc: List<TocItem>, title: String?) {
        _ui.value.resolved?.let { sessions.beginReading(it.fileId, it.initial.fraction?.let { fraction -> fraction * 100 },
            ) }
        _ui.update { it.copy(loaded = true, toc = toc, chapterTitle = it.chapterTitle ?: title) }
        readingTimeJob?.cancel()
        readingTimeJob = viewModelScope.launch {
            while (_ui.value.loaded) {
                refreshReadingTime()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    /** Keep the last reading position and reset page state before a fresh WebView is created. */
    fun onRendererGone() {
        readingTimeJob?.cancel()
        _ui.update { state ->
            state.copy(
                loaded = false,
                toolbar = null,
                search = null,
                resolved = state.resolved?.let { resolved ->
                    resolved.copy(initial = InitialProgress(
                        currentCfi ?: resolved.initial.cfi,
                        if (state.loaded) state.precisePercentage / 100.0
                                    else resolved.initial.fraction,
                                )
                        )
                    },
            )
        }
    }

    fun onPages(current: Int?, total: Int?, wholeBook: Boolean) {
        _ui.update {
            it.copy(currentPage = current, totalPages = total, wholeBookPages = wholeBook)
        }
    }


    private fun refreshReadingTime() {
        val snap = sessions.currentReading(_ui.value.resolved?.fileId ?: return)
        val seconds = (snap?.durationSeconds ?: 0).toLong()
        val elapsed = historicalStats.totalSeconds + seconds
        val remaining =
            com.bookorbit.core.model.remainingReadingSeconds(
                _ui.value.precisePercentage,
                historicalStats.paceProgressDelta + (snap?.progressDelta ?: 0.0).coerceAtLeast(0.0),
                historicalStats.paceDurationSeconds + seconds,
            )
        _ui.update { it.copy(elapsedSeconds = elapsed, remainingSeconds = remaining)
        }
    }

    fun onRelocate(cfi: String?, fraction: Double?, chapterTitle: String?, chapterHref: String? = null,
    ) {
        _ui.update {
            it.copy(
                chapterTitle = chapterTitle ?: it.chapterTitle,
                chapterHref = chapterHref ?: it.chapterHref,
                percentage = fraction?.let { f -> (f * 100).roundToInt() } ?: it.percentage,
                precisePercentage =
                    fraction?.let { f -> (f * 100).coerceIn(0.0, 100.0) } ?: it.precisePercentage,
            )
        }
        if (cfi != null) currentCfi = cfi
        if (cfi != null && fraction != null) {
            report(cfi, fraction * 100)
            sessions.onReadingProgress(fraction * 100)
        }
    }

    // --- in-book search ---

    fun startSearch(query: String) {
        val q = query.trim()
        _ui.update {
            it.copy(search = if (q.isEmpty()) null else SearchState(query = q, running = true))
        }
    }

    fun clearSearch() = _ui.update { it.copy(search = null) }

    fun onSearchResults(label: String, hits: List<SearchHit>) = _ui.update { s ->
        val cur = s.search ?: return@update s
        cur.copy(groups = cur.groups + SearchGroup(label, hits), total = cur.total + hits.size).let { s.copy(search = it) }
    }

    fun onSearchProgress(progress: Double) = _ui.update { s ->
        s.search?.let { s.copy(search = it.copy(progress = progress)) } ?: s
    }

    fun onSearchDone(total: Int, capped: Boolean) = _ui.update { s ->
        s.search?.let { s.copy(search = it.copy(running = false, done = true, total = total, capped = capped, progress = 1.0,
                        )) } ?: s
    }

    // --- bookmarks ---

    fun loadBookmarks() {
        viewModelScope.launch {
            runCatching { bookmarkRepo.list(bookId) }.onSuccess { list -> _ui.update { it.copy(bookmarks = list) } }
        }
    }

    /** Bookmark the current page, named after the chapter and how far through the book it is. */
    fun addBookmark() {
        val cfi = currentCfi ?: return
        val s = _ui.value
        val title = BookmarkNames.default(s.chapterTitle, s.percentage)
        viewModelScope.launch {
            runCatching { bookmarkRepo.add(bookId, cfi, title) }
                .onSuccess { created -> _ui.update { it.copy(bookmarks = it.bookmarks + created) } }
                .onFailure { _ui.update { it.copy(message = UiText.of(R.string.reader_err_bookmark_save)) } }
        }
    }

    fun deleteBookmark(id: Int) {
        viewModelScope.launch {
            runCatching { bookmarkRepo.delete(bookId, id) }
                .onSuccess { _ui.update { s -> s.copy(bookmarks = s.bookmarks.filterNot { it.id == id }) } }
                .onFailure { _ui.update { it.copy(message = UiText.of(R.string.reader_err_bookmark_delete)) } }
        }
    }

    // --- highlights & notes ---

    private fun loadAnnotations() {
        viewModelScope.launch {
            // Show what we already have straight away (cached server copy + queued changes), then
            // push anything queued and refresh from the server.
            _ui.update { it.copy(annotations = annotationRepo.local(bookId)) }
            syncAnnotations()
        }
    }

    /** Replay queued changes, then reload so placeholder ids are swapped for the server's. */
    private fun syncAnnotations() {
        viewModelScope.launch {
            annotationRepo.flushPending()
            _ui.update { it.copy(annotations = annotationRepo.load(bookId)) }
        }
    }

    private suspend fun refreshLocalAnnotations() {
        _ui.update { it.copy(annotations = annotationRepo.local(bookId), toolbar = null) }
    }

    fun onSelection(text: String, cfi: String?, rect: ViewportRect?) {
        if (cfi == null) return
        _ui.update { it.copy(toolbar = ToolbarTarget(rect, selection = PendingSelection(text, cfi))) }
    }

    fun onSelectionCleared() {
        // A selection going away dismisses the new-highlight toolbar, but not one opened on an
        // existing highlight.
        _ui.update { s -> if (s.toolbar?.annotationId == null) s.copy(toolbar = null) else s }
    }

    fun onAnnotationTap(cfi: String, rect: ViewportRect?) {
        val ann = _ui.value.annotations.firstOrNull { it.cfi == cfi } ?: return
        _ui.update { it.copy(toolbar = ToolbarTarget(rect, annotationId = ann.id)) }
    }

    fun dismissToolbar() = _ui.update { it.copy(toolbar = null) }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    /** Highlight the selection in [color], or recolour the highlight the toolbar is open on. */
    fun highlight(color: String) = saveAnnotation(color = color, note = null)

    /** Save a note: on the open highlight, or on the selection as a new (yellow) highlight. */
    fun saveNote(note: String) = saveAnnotation(color = null, note = note)

    private fun saveAnnotation(color: String?, note: String?) {
        val target = _ui.value.toolbar ?: return
        val fileId = _ui.value.resolved?.fileId
        viewModelScope.launch {
            runCatching {
                when {
                    target.annotationId != null -> {
                        if (color != null) annotationRepo.setColor(bookId, target.annotationId, color)
                        if (note != null) annotationRepo.setNote(bookId, target.annotationId, note)
                    }
                    target.selection != null && fileId != null -> annotationRepo.create(
                        bookId = bookId,
                        fileId = fileId,
                        cfi = target.selection.cfi,
                        text = target.selection.text,
                        color = color ?: ReaderAnnotationRepository.COLORS.first().second,
                        note = note,
                        chapterTitle = _ui.value.chapterTitle,
                    )
                }
            }.onSuccess {
                refreshLocalAnnotations()
                syncAnnotations()
            }.onFailure {
                _ui.update { s -> s.copy(message = UiText.of(R.string.reader_err_highlight_save)) }
            }
        }
    }

    fun deleteAnnotation(id: Int) {
        viewModelScope.launch {
            runCatching { annotationRepo.delete(bookId, id) }
                .onSuccess {
                    refreshLocalAnnotations()
                    syncAnnotations()
                }
                .onFailure { _ui.update { s -> s.copy(message = UiText.of(R.string.reader_err_highlight_delete)) } }
        }
    }

    fun onError(message: String) {
        val resource = when (message) {
            "TXT_SIZE" -> R.string.reader_txt_size
            "TXT_ENCODING" -> R.string.reader_txt_encoding
            "TXT_SECTIONS" -> R.string.reader_txt_sections
            "TXT_LINE" -> R.string.reader_txt_line
            "TXT_EMPTY" -> R.string.reader_txt_empty
            else -> null
        }
        _ui.update { it.copy(error = resource?.let { id -> UiText.of(id) } ?: UiText.raw(message)) }
    }

    fun updateSettings(settings: ReaderSettings) {
        val before = _ui.value
        _ui.update { it.copy(settings = settings) }
        val fileId = before.resolved?.fileId ?: return
        preferenceSync.enqueueSave(fileId, before.settings, settings, before.perBookSettings, before.syncSettings)
        if (!before.perBookSettings) viewModelScope.launch { settingsStore.save(settings) }
        syncSettingsLater()
    }

    private fun syncSettingsLater() {
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            val success = preferenceSync.flush()
            _ui.update { it.copy(settingsPending = !success) }
        }
    }

    fun setSettingsScope(perBook: Boolean) {
        val fileId = _ui.value.resolved?.fileId ?: return
        viewModelScope.launch {
            if (!perBook) {
                preferenceSync.useDefaults(fileId, _ui.value.syncSettings)
                val profile = preferenceSync.load(fileId, settingsStore.load())
                _ui.update { it.copy(settings = profile.settings, perBookSettings = false, settingsPending = profile.pending) }
                refreshFonts()
            } else _ui.update { it.copy(perBookSettings = true) }
        }
    }

    fun setSettingsSync(enabled: Boolean) {
        val fileId = _ui.value.resolved?.fileId ?: return
        viewModelScope.launch {
            try {
                preferenceSync.setSync(enabled)
                val profile = preferenceSync.load(fileId, _ui.value.settings)
                _ui.update { it.copy(settings = profile.settings, syncSettings = profile.sync, perBookSettings = profile.perBook, settingsPending = profile.pending) }
                refreshFonts()
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                _ui.update { it.copy(message = UiText.of(R.string.rs_sync_failed)) }
            }
        }
    }

    fun refreshFonts() {
        fontLoadJob?.cancel()
        fontLoadJob = viewModelScope.launch {
            _ui.update { it.copy(fontsLoading = true) }
            try {
                for (refresh in listOf(false, true)) {
                    val fonts = fontRepository.catalog(refresh)
                    val selected = fonts.firstOrNull { it.cssFamily == _ui.value.settings.fontFamily }
                    val faces = fontRepository.cachedFaces(selected)
                    _ui.update { state ->
                        state.copy(
                            fonts = fonts,
                            fontFaces = if (faces.isNotEmpty()) faces
                                else state.fontFaces.filter { it.family == state.settings.fontFamily },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = UiText.of(R.string.rs_fonts_error)) }
            } finally {
                _ui.update { it.copy(fontsLoading = false) }
            }
        }
    }

    fun selectFont(font: ReaderFontFamily) {
        if (_ui.value.downloadingFont != null) return
        viewModelScope.launch {
            _ui.update { it.copy(downloadingFont = font.cssFamily) }
            try {
                fontRepository.download(font)
                val faces = fontRepository.cachedFaces(font)
                _ui.update { state -> state.copy(fontFaces = faces, fonts = state.fonts.map {
                    if (it.cssFamily == font.cssFamily) it.copy(downloaded = true) else it
                },
                    ) }
                updateSettings(_ui.value.settings.copy(fontFamily = font.cssFamily))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = UiText.of(R.string.rs_fonts_error)) }
            } finally {
                _ui.update { it.copy(downloadingFont = null) }
            }
        }
    }

    fun dismissPagingHint() {
        _ui.update { it.copy(showPagingHint = false) }
        viewModelScope.launch { settingsStore.markPagingHintSeen() }
    }

    private fun report(cfi: String?, percentage: Double) {
        pending = cfi to percentage
        if (System.currentTimeMillis() - lastSaved >= PROGRESS_THROTTLE_MS) flush()
    }

    fun flush() {
        val fileId = _ui.value.resolved?.fileId ?: return
        val p = pending ?: return
        pending = null
        lastSaved = System.currentTimeMillis()
        progress.enqueueReport(fileId, p.first, p.second)
    }

    override fun onCleared() {
        preferenceSync.flushOnClose()
        flush()
        sessions.end()
        super.onCleared()
    }
}
