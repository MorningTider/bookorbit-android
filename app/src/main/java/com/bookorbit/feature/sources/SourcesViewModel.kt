package com.bookorbit.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookorbit.R
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.Library
import com.bookorbit.core.network.ApiService
import com.bookorbit.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SourcesViewModel
@Inject
constructor(
    private val api: ApiService,
    private val repo: SourceRepository,
    private val session: SessionManager,
) : ViewModel() {
    data class Review(
        val matches: List<ImportMatch>,
        val candidate: SourceBook? = null,
        val reviewId: String? = null,
    )

    data class State(
        val loading: Boolean = true,
        val searching: Boolean = false,
        val searched: Boolean = false,
        val busy: Boolean = false,
        val sources: List<String> = emptyList(),
        val libraries: List<Library> = emptyList(),
        val libraryId: Int? = null,
        val folderId: Int? = null,
        val query: String = "",
        val results: List<SourceBook> = emptyList(),
        val matches: Map<String, List<ImportMatch>> = emptyMap(),
        val checked: Boolean = false,
        val jobs: List<SourceJob> = emptyList(),
        val nextCursor: Int? = null,
        val review: Review? = null,
        val error: UiText? = null,
        val providerFailures: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var searchJob: Job? = null
    private var checkJob: Job? = null
    private var libraryGeneration = 0
    private var loadingJobs = false
    private var jobsCursor: Int? = null

    init {
        reload()
    }

    fun reload() =
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            safely {
                val status = api.sourceStatus()
                val user = session.currentUser
                val libraries =
                    api.getLibraries().filter {
                        (user?.isSuperuser == true || it.accessLevel in setOf("owner", "editor")) &&
                            (it.allowedFormats.isEmpty() ||
                                it.allowedFormats.any { f -> f.equals("epub", true) })
                    }
                val preferred =
                    try {
                        api.sourceDestinations()["ebook"]
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                _state.update { current ->
                    val existing = libraries.firstOrNull { it.id == current.libraryId }
                    val destination =
                        existing
                            ?: libraries.firstOrNull { it.id == preferred?.libraryId }
                            ?: libraries.singleOrNull()
                    current.copy(
                        sources = status.enabled(),
                        libraries = libraries,
                        libraryId = destination?.id,
                        folderId =
                            if (existing != null) current.folderId
                            else preferred?.takeIf { it.libraryId == destination?.id }?.folderId,
                    )
                }
                refreshJobs()
            }
            _state.update { it.copy(loading = false) }
            checkImported()
        }

    fun query(value: String) {
        _state.update { it.copy(query = value.take(500)) }
    }

    fun selectLibrary(id: Int) {
        libraryGeneration++
        checkJob?.cancel()
        _state.update {
            it.copy(
                libraryId = id,
                folderId = if (id == it.libraryId) it.folderId else null,
                matches = emptyMap(),
                checked = false,
            )
        }
        checkImported()
    }

    fun search() {
        val title = state.value.query.trim()
        val sources = state.value.sources
        if (title.isEmpty() || sources.isEmpty()) return
        searchJob?.cancel()
        checkJob?.cancel()
        libraryGeneration++
        searchJob =
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        searching = true,
                        results = emptyList(),
                        matches = emptyMap(),
                        checked = false,
                        error = null,
                        providerFailures = emptySet(),
                    )
                }
                safely {
                    repo.search(title, sources).collect { event ->
                        when (event) {
                            is SourceSearchEvent.Book ->
                                _state.update {
                                    it.copy(
                                        results =
                                            (it.results + event.book).distinctBy(SourceBook::key)
                                    )
                                }
                            is SourceSearchEvent.ProviderFailed ->
                                _state.update {
                                    it.copy(providerFailures = it.providerFailures + event.provider)
                                }
                        }
                    }
                }
                _state.update { it.copy(searching = false, searched = true) }
                checkImported()
            }
    }

    private fun checkImported() {
        checkJob?.cancel()
        val snapshot = state.value
        val destination = snapshot.libraryId ?: return
        if (snapshot.results.isEmpty()) return
        val generation = libraryGeneration
        checkJob =
            viewModelScope.launch {
                safely {
                    val checks =
                        api.checkImports(
                            ImportCheck(
                                destination,
                                snapshot.results.take(40).map {
                                    ImportCandidate(
                                        it.key,
                                        ImportIdentity(
                                            it.title,
                                            it.authors,
                                            SourceOrigin(it.provider, it.providerId),
                                            isbn10 = it.isbn10,
                                            isbn13 = it.isbn13,
                                        ),
                                    )
                                },
                            )
                        )
                    if (generation == libraryGeneration)
                        _state.update {
                            it.copy(
                                matches = checks.associate { row -> row.key to row.matches },
                                checked = true,
                            )
                        }
                }
            }
    }

    suspend fun refreshJobs(more: Boolean = false) {
        if (loadingJobs) return
        loadingJobs = true
        try {
            safely {
                if (more) jobsCursor = state.value.nextCursor
                val page = api.sourceJobs(jobsCursor)
                val previous = state.value.jobs.associate { it.id to it.status }
                _state.update { it.copy(jobs = page.items, nextCursor = page.nextCursor) }
                if (page.items.any { it.status == "completed" && previous[it.id] != "completed" })
                    checkImported()
            }
        } finally {
            loadingJobs = false
        }
    }

    suspend fun poll() {
        while (true) {
            refreshJobs()
            delay(if (state.value.jobs.any { it.active }) 5_000 else 30_000)
        }
    }

    fun latestJobs() =
        viewModelScope.launch {
            jobsCursor = null
            refreshJobs()
        }

    fun moreJobs() = viewModelScope.launch { refreshJobs(true) }

    fun acquire(book: SourceBook) {
        if (!state.value.checked || state.value.busy) return
        val matches = state.value.matches[book.key].orEmpty()
        if (matches.isNotEmpty())
            _state.update { it.copy(review = Review(matches, candidate = book)) }
        else
            action {
                val libraryId = state.value.libraryId ?: return@action
                api.acquireSource(
                    libraryId,
                    book.provider,
                    SourceAcquisition(book.providerId, folderId = state.value.folderId),
                )
                refreshJobs()
            }
    }

    fun resolve(
        resolution: ImportResolution,
        candidate: SourceBook? = state.value.review?.candidate,
    ) = action {
        val libraryId = state.value.libraryId ?: return@action
        if (candidate != null)
            api.acquireSource(
                libraryId,
                candidate.provider,
                SourceAcquisition(candidate.providerId, resolution, state.value.folderId),
            )
        else state.value.review?.reviewId?.let { api.resolveImport(it, resolution) }
        _state.update { it.copy(review = null) }
        refreshJobs()
    }

    fun replace(match: ImportMatch, file: ImportFile) = action {
        val version = api.importFileVersion(file.id)
        val resolution = ImportResolution("replace", match.bookId, file.id, version.sha256)
        val review = state.value.review ?: return@action
        val libraryId = state.value.libraryId ?: return@action
        if (review.candidate != null)
            api.acquireSource(
                libraryId,
                review.candidate.provider,
                SourceAcquisition(review.candidate.providerId, resolution, state.value.folderId),
            )
        else review.reviewId?.let { api.resolveImport(it, resolution) }
        _state.update { it.copy(review = null) }
        refreshJobs()
    }

    fun review(job: SourceJob) = action {
        val review = api.importReview(job.reviewId ?: return@action)
        _state.update { it.copy(review = Review(review.matches, reviewId = review.id)) }
    }

    fun cancel(job: SourceJob) = action {
        api.cancelSourceJob(job.id)
        refreshJobs()
    }

    fun retry(job: SourceJob) = action {
        api.retrySourceJob(job.id)
        refreshJobs()
    }

    fun delete(job: SourceJob) = action {
        api.deleteSourceJob(job.id)
        refreshJobs()
    }

    fun dismissReview() {
        if (!state.value.busy) _state.update { it.copy(review = null) }
    }

    private fun action(work: suspend () -> Unit) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                safely(work)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun safely(work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.update { it.copy(error = UiText.of(R.string.sources_request_error)) }
        }
    }
}
