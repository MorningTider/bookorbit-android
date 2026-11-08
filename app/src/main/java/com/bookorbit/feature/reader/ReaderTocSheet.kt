package com.bookorbit.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookorbit.R
import kotlinx.coroutines.launch

data class ReaderContentsEntry(
    val label: String,
    val href: String,
    val depth: Int,
    val position: Int,
)

fun readerContents(items: List<TocItem>): List<ReaderContentsEntry> {
    val pending = ArrayDeque<Pair<TocItem, Int>>()
    items.asReversed().forEach { pending.addLast(it to 0) }
    return buildList {
        while (pending.isNotEmpty()) {
            val (item, depth) = pending.removeLast()
            item.href?.let { add(ReaderContentsEntry(item.label, it, depth, size)) }
            item.subitems.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    toc: List<TocItem>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    settings: ReaderSettings = ReaderSettings(),
    bookTitle: String = "",
    currentHref: String? = null,
    onNight: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val flat = remember(toc) { readerContents(toc) }
    var query by rememberSaveable { mutableStateOf("") }
    var reversed by rememberSaveable { mutableStateOf(false) }
    val matching =
        remember(flat, query, reversed) {
            val entries = flat.filter { it.label.contains(query.trim(), ignoreCase = true) }
            if (reversed) entries.asReversed() else entries
        }
    val currentIndex = matching.indexOfFirst { it.href == currentHref }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    val scope = rememberCoroutineScope()
    LaunchedEffect(reversed) { listState.scrollToItem(currentIndex) }
    val height = LocalConfiguration.current.screenHeightDp.dp * .84f
    ReaderAppearance(settings) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().height(height)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bookTitle,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.contents),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.rs_chapter_count, flat.size),
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(currentIndex) } },
                        enabled = matching.any { it.href == currentHref },
                    ) {
                        Icon(Icons.Default.MyLocation, stringResource(R.string.rs_current_chapter))
                    }
                    IconButton(onClick = { reversed = !reversed }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            stringResource(
                                if (reversed) R.string.rs_toc_ascending
                                else R.string.rs_toc_descending
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.rs_search_chapters)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                if (matching.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (flat.isEmpty()) R.string.no_table_of_contents
                                else R.string.rs_no_chapters
                            )
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), state = listState) {
                        items(matching, key = { it.position }) { item ->
                            val selected = item.href == currentHref
                            Surface(
                                color =
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .semantics { this.selected = selected }
                                        .clickable { onSelect(item.href) }
                                        .padding(
                                            start = 24.dp + (item.depth.coerceAtMost(4) * 12).dp,
                                            end = 20.dp,
                                            top = 18.dp,
                                            bottom = 18.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selected)
                                        Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp))
                                    Text(
                                        item.label,
                                        Modifier.weight(1f)
                                            .padding(start = if (selected) 8.dp else 0.dp),
                                        color =
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            HorizontalDivider(
                                Modifier.padding(horizontal = 24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                ReaderBottomNavigation(
                    settings,
                    onContents = {},
                    onNight = onNight,
                    onSettings = onSettings,
                )
            }
        }
    }
}
