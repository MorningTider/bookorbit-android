package com.bookorbit.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.bookorbit.R
import com.bookorbit.ui.LocalImageUrls
import com.bookorbit.ui.asString

@Composable
fun SourcesScreen(onBookClick: (Int) -> Unit, vm: SourcesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val submitSearch = {
        focus.clearFocus()
        keyboard?.hide()
        vm.search()
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, state.loading) {
        if (!state.loading) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() }
    }
    var chooseLibrary by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SourceJob?>(null) }
    if (chooseLibrary)
        AlertDialog(
            onDismissRequest = { chooseLibrary = false },
            title = { Text(stringResource(R.string.sources_library)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.libraries.forEach { library ->
                        TextButton(
                            onClick = {
                                vm.selectLibrary(library.id)
                                chooseLibrary = false
                            }
                        ) {
                            Text(library.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseLibrary = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    deleting?.let { job ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sources_delete)) },
            text = { Text(stringResource(R.string.sources_delete_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(job)
                        deleting = null
                    }
                ) {
                    Text(stringResource(R.string.sources_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    state.review?.let { review ->
        AlertDialog(
            onDismissRequest = vm::dismissReview,
            title = { Text(stringResource(R.string.sources_duplicate)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.sources_duplicate_hint))
                    review.matches.forEach { match ->
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(match.title, style = MaterialTheme.typography.titleMedium)
                        Text(match.libraryName)
                        if (match.canEdit) {
                            TextButton(
                                enabled = !state.busy,
                                onClick = { vm.resolve(ImportResolution("attach", match.bookId)) },
                            ) {
                                Text(stringResource(R.string.sources_attach))
                            }
                            match.files
                                .filter { it.format.equals("epub", true) }
                                .forEach { file ->
                                    TextButton(
                                        enabled = !state.busy,
                                        onClick = { vm.replace(match, file) },
                                    ) {
                                        Text(
                                            stringResource(R.string.sources_replace, file.filename)
                                        )
                                    }
                                }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = { vm.resolve(ImportResolution("new")) },
                ) {
                    Text(stringResource(R.string.sources_new))
                }
            },
            dismissButton = {
                TextButton(enabled = !state.busy, onClick = vm::dismissReview) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("source-items"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.sources_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            state.error?.let {
                Text(
                    it.asString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = { vm.reload() }) {
                    Text(stringResource(R.string.sources_reload))
                }
            }
            if (!state.loading && state.error == null && state.sources.isEmpty())
                Text(stringResource(R.string.sources_none))
            if (!state.loading && state.error == null && state.libraries.isEmpty())
                Text(stringResource(R.string.sources_no_library))
            TextButton(
                onClick = { chooseLibrary = true },
                enabled = state.libraries.isNotEmpty() && !state.busy,
            ) {
                Text(
                    stringResource(R.string.sources_library) +
                        " · " +
                        (state.libraries.firstOrNull { it.id == state.libraryId }?.name
                            ?: stringResource(R.string.sources_choose_library))
                )
            }
            OutlinedTextField(
                state.query,
                vm::query,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.sources_query)) },
                trailingIcon = {
                    IconButton(
                        onClick = submitSearch,
                        enabled = state.query.isNotBlank() && state.sources.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Search, stringResource(R.string.sources_search))
                    }
                },
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(onSearch = { submitSearch() }),
            )
            if (
                state.searched && !state.searching && state.results.isEmpty() && state.error == null
            )
                Text(stringResource(R.string.sources_no_results))
            if (state.searching)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (state.providerFailures.isNotEmpty())
                Text(
                    stringResource(R.string.sources_partial_error),
                    color = MaterialTheme.colorScheme.error,
                )
        }
        items(state.results, key = SourceBook::key) { book ->
            SourceBookCard(
                book,
                state.matches[book.key].orEmpty().isNotEmpty(),
                !state.busy && state.libraryId != null && state.checked,
            ) {
                vm.acquire(book)
            }
        }
        item {
            Text(stringResource(R.string.sources_jobs), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.latestJobs() }) {
                Text(stringResource(R.string.sources_reload))
            }
        }
        if (state.jobs.isEmpty()) item { Text(stringResource(R.string.sources_jobs_empty)) }
        items(state.jobs, key = { "job-${it.id}" }) { job ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium)
                    Text(sourceLabel(job.source ?: "fanqie") + " · " + jobStatus(job.status))
                    val progress = job.progress
                    val total = progress?.totalChapters
                    val completed = progress?.completedChapters
                    if (total != null && total > 0 && completed != null) {
                        LinearProgressIndicator(
                            progress = { (completed.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.sources_progress, completed, total))
                    } else if (job.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (job.status == "completed" && job.resultBookId != null)
                        TextButton(onClick = { onBookClick(job.resultBookId) }) {
                            Text(stringResource(R.string.sources_open))
                        }
                    if (job.active)
                        TextButton(
                            enabled = !state.busy && !job.cancelRequested,
                            onClick = { vm.cancel(job) },
                        ) {
                            Text(stringResource(R.string.sources_cancel))
                        }
                    if (job.status == "awaiting_choice")
                        TextButton(enabled = !state.busy, onClick = { vm.review(job) }) {
                            Text(stringResource(R.string.sources_duplicate))
                        }
                    if (job.retryable && job.status in setOf("failed", "cancelled"))
                        TextButton(enabled = !state.busy, onClick = { vm.retry(job) }) {
                            Text(stringResource(R.string.sources_retry))
                        }
                    if (job.status in setOf("completed", "failed", "cancelled"))
                        TextButton(enabled = !state.busy, onClick = { deleting = job }) {
                            Text(stringResource(R.string.sources_delete))
                        }
                }
            }
        }
        if (state.nextCursor != null)
            item {
                TextButton(onClick = { vm.moreJobs() }, enabled = !state.busy) {
                    Text(stringResource(R.string.sources_more))
                }
            }
    }
}

@Composable
private fun SourceBookCard(
    book: SourceBook,
    imported: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    var expanded by remember(book.key) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(84.dp, 120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                    AsyncImage(
                        LocalImageUrls.current.sourceCover(book.coverUrl),
                        contentDescription = book.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sourceLabel(book.provider), color = MaterialTheme.colorScheme.primary)
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    Text(book.authors.joinToString("、"))
                    if (imported)
                        Text(
                            stringResource(R.string.sources_imported),
                            color = MaterialTheme.colorScheme.primary,
                        )
                }
            }
            book.description?.let {
                Text(
                    android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT)
                        .toString(),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.sources_collapse else R.string.sources_expand
                        )
                    )
                }
            }
            Button(onClick = onDownload, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (imported) R.string.sources_download_again else R.string.sources_download
                    )
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: String): String =
    when (source) {
        "fanqie" -> stringResource(R.string.sources_fanqie)
        "qidian" -> stringResource(R.string.sources_qidian)
        else -> source
    }

@Composable
private fun jobStatus(status: String): String =
    stringResource(
        when (status) {
            "queued" -> R.string.sources_queued
            "downloading" -> R.string.sources_downloading
            "importing" -> R.string.sources_importing
            "completed" -> R.string.sources_completed
            "failed" -> R.string.sources_failed
            "cancelled" -> R.string.sources_cancelled
            "awaiting_choice" -> R.string.sources_duplicate
            else -> R.string.sources_unknown
        }
    )
