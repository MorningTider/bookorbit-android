package com.bookorbit.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bookorbit.R
import com.bookorbit.core.model.BookAnnotation
import com.bookorbit.ui.LocalImageUrls
import com.bookorbit.ui.components.htmlToAnnotatedString
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
    onSearch: () -> Unit = {},
    bookId: Int? = null,
    author: String = "",
    description: String = "",
    annotations: List<BookAnnotation> = emptyList(),
    onBookDetails: (() -> Unit)? = null,
) {
    val flat = remember(toc) { readerContents(toc) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    val matching =
        remember(flat, query, reversed) {
            val entries = flat.filter { it.label.contains(query.trim(), ignoreCase = true) }
            if (reversed) entries.asReversed() else entries
        }
    val currentIndex = matching.indexOfFirst { it.href == currentHref }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, reversed) { listState.scrollToItem(currentIndex) }
    val height = LocalConfiguration.current.screenHeightDp.dp * .84f
    ReaderPanel(settings, onDismiss, dim = .35f) {
        Column(Modifier.fillMaxWidth().height(height)) {
            if (!searching) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.clickable(
                                enabled = onBookDetails != null,
                                onClickLabel = stringResource(R.string.rs_open_book_details),
                            ) {
                                onBookDetails?.invoke()
                            }
                            .width(46.dp)
                            .height(66.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bookId != null) {
                            AsyncImage(
                                model = LocalImageUrls.current.cover(bookId),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else Icon(Icons.AutoMirrored.Filled.MenuBook, null)
                    }
                    Column(
                        Modifier.weight(1f).clickable(
                            enabled = onBookDetails != null,
                            onClickLabel = stringResource(R.string.rs_open_book_details),
                        ) {
                            onBookDetails?.invoke()
                        },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            bookTitle + if (onBookDetails != null) " ›" else "",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            author,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, stringResource(R.string.search_in_book))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    listOf(
                            R.string.details,
                            R.string.contents,
                            R.string.rs_quotes,
                            R.string.tab_notes,
                        )
                        .forEachIndexed { index, label ->
                            Text(
                                stringResource(label),
                                Modifier.weight(1f)
                                    .semantics { selected = tab == index }
                                    .clickable { tab = index }
                                    .padding(vertical = 10.dp),
                                fontSize = 16.sp,
                                fontWeight =
                                    if (tab == index) FontWeight.Bold else FontWeight.Normal,
                                color =
                                    MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (tab == index) 1f else .5f
                                    ),
                            )
                        }
                }
            }
            if (tab == 1) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rs_chapter_count, flat.size),
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                    )
                    IconButton(onClick = { searching = !searching }) {
                        Icon(
                            Icons.Default.Search,
                            stringResource(R.string.rs_search_chapters),
                            Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(currentIndex) } },
                        enabled = matching.any { it.href == currentHref },
                    ) {
                        Icon(
                            Icons.Default.MyLocation,
                            stringResource(R.string.rs_current_chapter),
                            Modifier.size(20.dp),
                        )
                    }
                    TextButton(onClick = { reversed = !reversed }) {
                        Text(
                            stringResource(
                                if (reversed) R.string.rs_toc_ascending
                                else R.string.rs_toc_descending
                            ),
                            fontSize = 13.sp,
                        )
                    }
                }
                if (searching)
                    OutlinedTextField(
                        query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.rs_search_chapters)) },
                        singleLine = true,
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (tab) {
                0 ->
                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(bookTitle, fontWeight = FontWeight.Bold)
                        Text(author, fontSize = 14.sp)
                        Text(
                            remember(description) { htmlToAnnotatedString(description) },
                            fontSize = 14.sp,
                            lineHeight = 23.sp,
                        )
                    }
                1 ->
                    if (matching.isEmpty()) {
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(
                                    if (flat.isEmpty()) R.string.no_table_of_contents
                                    else R.string.rs_no_chapters
                                )
                            )
                        }
                    } else
                        LazyColumn(Modifier.weight(1f), state = listState) {
                            items(matching, key = { it.position }) { item ->
                                val selected = item.href == currentHref
                                Row(
                                    Modifier.fillMaxWidth()
                                        .semantics { this.selected = selected }
                                        .clickable { onSelect(item.href) }
                                        .padding(
                                            start = 22.dp + (item.depth.coerceAtMost(4) * 12).dp,
                                            end = 20.dp,
                                            top = 19.dp,
                                            bottom = 19.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selected)
                                        Icon(
                                            Icons.Default.MyLocation,
                                            null,
                                            Modifier.size(17.dp),
                                            tint = readerPalette(settings).accent,
                                        )
                                    Text(
                                        item.label,
                                        Modifier.weight(1f)
                                            .padding(start = if (selected) 7.dp else 0.dp),
                                        fontSize = 15.sp,
                                        color =
                                            if (selected) readerPalette(settings).accent
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 22.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                else -> {
                    val entries =
                        if (tab == 3) annotations.filter { !it.note.isNullOrBlank() }
                        else annotations
                    if (entries.isEmpty())
                        Box(
                            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.notes_empty), fontSize = 14.sp)
                        }
                    else
                        LazyColumn(Modifier.weight(1f)) {
                            items(entries, key = { it.id }) { annotation ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = annotation.cfi != null) {
                                            annotation.cfi?.let(onSelect)
                                        }
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(annotation.text, fontSize = 15.sp)
                                    annotation.note
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let {
                                            Text(
                                                it,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    annotation.chapterTitle?.let {
                                        Text(
                                            it,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                            }
                        }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReaderBottomNavigation(
                settings,
                onContents = { tab = 1 },
                onNight = onNight,
                onSettings = onSettings,
            )
        }
    }
}
