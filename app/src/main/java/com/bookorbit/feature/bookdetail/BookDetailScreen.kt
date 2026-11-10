package com.bookorbit.feature.bookdetail

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bookorbit.R
import com.bookorbit.core.model.BookAnnotation
import com.bookorbit.core.model.BookDetail
import com.bookorbit.core.model.BookFiles
import com.bookorbit.feature.reader.highlightColor
import com.bookorbit.ui.LocalImageUrls
import com.bookorbit.ui.components.RecommendationScroller
import com.bookorbit.ui.components.StarRating
import com.bookorbit.ui.components.htmlToAnnotatedString

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onRead: (Int) -> Unit,
    onReadPdf: (Int) -> Unit,
    onReadComic: (Int) -> Unit,
    onListen: (Int) -> Unit,
    onBookClick: (Int) -> Unit,
    onAuthorClick: (id: Int, name: String) -> Unit,
    onSeriesClick: (id: Int, name: String) -> Unit,
    vm: BookDetailViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (!ui.loading) vm.refreshReadingState()
    }
    var statusSheet by remember { mutableStateOf(false) }
    var collectionSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { vm.startDownload() }
    val startDownload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startDownload()
        }
    }

    Scaffold(
        bottomBar = {
            ui.book?.let { book ->
                DetailReadingBar(book, ui.fileProgress, onRead, onReadPdf, onReadComic, onListen)
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.book?.title ?: stringResource(R.string.detail_book_details),
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                ui.error || ui.book == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.failed_to_load_book_details), color = MaterialTheme.colorScheme.error,
                            )
                        Button(onClick = vm::load, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
                    }
                }
                else -> BookDetailContent(
                    book = ui.book!!,
                    vm = vm,
                    ui = ui,
                    onBookClick = onBookClick,
                    onAuthorClick = onAuthorClick,
                    onSeriesClick = onSeriesClick,
                    onStartDownload = startDownload,
                    onOpenStatusSheet = { statusSheet = true },
                    onOpenCollectionSheet = { collectionSheet = true },
                )
            }
        }
    }

    val book = ui.book
    if (book != null && statusSheet) {
        ReadStatusSheet(
            current = book.readStatus?.status,
            onSelect = {
                vm.setStatus(it)
                statusSheet = false
            },
            onDismiss = { statusSheet = false },
        )
    }
    if (book != null && collectionSheet) {
        CollectionPickerSheet(
            state = collections,
            onLoad = vm::loadCollections,
            onToggle = vm::toggleCollection,
            onDismiss = { collectionSheet = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookDetailContent(
    book: BookDetail,
    vm: BookDetailViewModel,
    ui: BookDetailViewModel.UiState,
    onBookClick: (Int) -> Unit,
    onAuthorClick: (id: Int, name: String) -> Unit,
    onSeriesClick: (id: Int, name: String) -> Unit,
    onStartDownload: () -> Unit,
    onOpenStatusSheet: () -> Unit,
    onOpenCollectionSheet: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var reviews by remember { mutableStateOf(false) }
    if (reviews)
        com.bookorbit.feature.comments.BookCommentsSheet(book.id, onDismiss = { reviews = false })
    val goodreadsId = book.providerIds["goodreads"]

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val download by vm.downloadState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        DetailHero(book, onAuthorClick = onAuthorClick, onSeriesClick = onSeriesClick)

        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            DownloadControl(download, onStartDownload, vm::removeDownload)
        }

        FormatChips(book, ui.fileProgress)
        androidx.compose.material3.TextButton(
            onClick = { reviews = true },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(stringResource(R.string.comments_reviews))
        }

        val tabs =
            listOf(
                stringResource(R.string.detail_tab_details),
                stringResource(R.string.detail_tab_files),
                if (ui.highlights.isNotEmpty())
                    stringResource(R.string.detail_tab_highlights_n, ui.highlights.size)
                else stringResource(R.string.detail_tab_highlights),
                stringResource(R.string.detail_tab_reading_log),
            )
        androidx.compose.material3.ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label, maxLines = 1) },
                )
            }
        }

        when (tab) {
            0 ->
                DetailsTab(
                    book = book,
                    vm = vm,
                    ui = ui,
                    goodreadsId = goodreadsId,
                    onBookClick = onBookClick,
                    onOpenStatusSheet = onOpenStatusSheet,
                    onOpenCollectionSheet = onOpenCollectionSheet,
                    onOpenUri = { uriHandler.openUri(it) },
                )
            1 -> FilesTab(book)
            2 -> HighlightsTab(ui.highlights)
            else ->
                ReadingLogPanel(
                    ui.readingLog,
                    ui.logLoading,
                    ui.logError,
                    onLoadMore = { vm.loadReadingLog(append = true) },
                    onRetry = { vm.loadReadingLog(append = ui.readingLog != null) },
                )
        }

        Box(Modifier.height(24.dp))
    }
}

/** Compact cover-led overview keeps metadata and primary reading controls in view. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailHero(
    book: BookDetail,
    onAuthorClick: (Int, String) -> Unit,
    onSeriesClick: (Int, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f),
                        MaterialTheme.colorScheme.background,
                    )
                )
            )
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        AsyncImage(
            LocalImageUrls.current.cover(book.id),
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(108.dp, 162.dp).clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                book.title ?: stringResource(R.string.card_unknown_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            book.subtitle
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                book.authors.forEach { author ->
                    Text(
                        author.name,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.clickable { onAuthorClick(author.id, author.name) }
                                .padding(vertical = 4.dp),
                    )
                }
            }
            book.rating?.let { StarRating(value = it, onChange = null) }
            Text(
                stringResource(R.string.detail_in_library, book.libraryName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(book.publishedYear?.toString(), book.publisher).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            book.seriesName?.let { name ->
                Chip(
                    name + (book.seriesIndex?.let { " #${it.toInt()}" } ?: ""),
                    modifier =
                        Modifier.then(
                            if (book.seriesId != null)
                                Modifier.clickable { onSeriesClick(book.seriesId, name) }
                            else Modifier
                        ),
                )
            }
            book.audioMetadata
                ?.narrators
                ?.takeIf { it.isNotEmpty() }
                ?.let { narrators ->
                    Text(
                        stringResource(
                            R.string.detail_narrated_by,
                            narrators.joinToString(", ") { it.name },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
    }
}

@Composable
internal fun DetailReadingBar(
    book: BookDetail,
    progress: Map<Int, Double>,
    onRead: (Int) -> Unit,
    onReadPdf: (Int) -> Unit,
    onReadComic: (Int) -> Unit,
    onListen: (Int) -> Unit,
) {
    val target = BookFiles.readingTarget(book)
    val file =
        when (target) {
            is BookFiles.ReadingTarget.Foliate -> target.file
            is BookFiles.ReadingTarget.Pdf -> target.file
            is BookFiles.ReadingTarget.Comic -> target.file
            else -> null
        }
    val percentage = file?.let { progress[it.id] } ?: 0.0
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (file != null)
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        when (target) {
                            is BookFiles.ReadingTarget.Pdf -> onReadPdf(book.id)
                            is BookFiles.ReadingTarget.Comic -> onReadComic(book.id)
                            else -> onRead(book.id)
                        }
                    },
                ) {
                    Text(
                        if (percentage > 0)
                            stringResource(R.string.detail_continue_progress,
                                com.bookorbit.core.model.readingPercentage(percentage),
                            )
                        else stringResource(R.string.read)
                    )
                }
            if (BookFiles.isAudiobook(book))
                androidx.compose.material3.OutlinedButton(
                    onClick = { onListen(book.id) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Headset, null)
                    Text(stringResource(R.string.listen))
                }
        }
    }
}

@Composable
private fun DownloadControl(
    download: com.bookorbit.core.db.DownloadEntity?,
    onStartDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    when (download?.status) {
        "COMPLETE" -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = com.bookorbit.ui.theme.SuccessGreen,
                )
            Text(stringResource(R.string.downloaded), color = com.bookorbit.ui.theme.SuccessGreen, modifier = Modifier.weight(1f),
                )
            androidx.compose.material3.TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
        }
        "DOWNLOADING" -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.downloading, ((download.progress) * 100).toInt()))
        }
        "FAILED" -> Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                download.lastError ?: stringResource(R.string.download_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onStartDownload,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text(stringResource(R.string.retry_download), modifier = Modifier.padding(start = 8.dp),
                    )
            }
        }
        else -> androidx.compose.material3.OutlinedButton(onClick = onStartDownload, modifier = Modifier.fillMaxWidth(),
            ) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Text(stringResource(R.string.download), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** One chip per format the book comes in, with your reading position in it when there is one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(book: BookDetail, fileProgress: Map<Int, Double>) {
    val formats = book.files.filter { it.format != null }
    if (formats.isEmpty()) return
    FlowRow(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        formats.distinctBy { it.format!!.lowercase() }.forEach { file ->
            val pct = fileProgress[file.id]
            Chip(file.format!!.uppercase() + (pct?.let { " ${com.bookorbit.core.model.readingPercentage(it)}%" } ?: ""),
                    emphasized = pct != null,
                )
        }
    }
}

@Composable
private fun DetailsTab(
    book: BookDetail,
    vm: BookDetailViewModel,
    ui: BookDetailViewModel.UiState,
    goodreadsId: String?,
    onBookClick: (Int) -> Unit,
    onOpenStatusSheet: () -> Unit,
    onOpenCollectionSheet: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    Column {
        ShelfCards(book, vm, ui, onOpenStatusSheet, onOpenCollectionSheet)

        book.description?.let { description ->
            Section(stringResource(R.string.synopsis)) {
                val annotated = remember(description) { htmlToAnnotatedString(description) }
                Text(annotated, style = MaterialTheme.typography.bodyMedium)
            }
        }

        DetailsSection(book)

        if (goodreadsId != null) {
            Section(stringResource(R.string.links)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenUri("https://www.goodreads.com/book/show/$goodreadsId") }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.view_on_goodreads), modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }

        if (book.genres.isNotEmpty()) {
            Section(stringResource(R.string.genres)) { FlowRowChips(book.genres) }
        }
        if (book.tags.isNotEmpty()) {
            Section(stringResource(R.string.tags)) { FlowRowChips(book.tags) }
        }

        if (ui.authorBooks.isNotEmpty()) {
            RecommendationScroller(stringResource(R.string.more_by_this_author), ui.authorBooks, onBookClick = onBookClick,
            )
        }
        if (ui.recommendations.isNotEmpty()) {
            RecommendationScroller(stringResource(R.string.similar_books), ui.recommendations, onBookClick = onBookClick,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { Chip(it) }
    }
}

/** Reading status, rating and collections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShelfCards(
    book: BookDetail,
    vm: BookDetailViewModel,
    ui: BookDetailViewModel.UiState,
    onOpenStatusSheet: () -> Unit,
    onOpenCollectionSheet: () -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenStatusSheet),
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                val meta = book.readStatus?.let { readStatusMeta(it.status) }
                Icon(
                    meta?.icon ?: Icons.Filled.Book,
                    contentDescription = null,
                    tint = meta?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(meta?.label ?: R.string.status_set),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                if (ui.statusUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (vm.canRate || book.rating != null) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.rating), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StarRating(
                        value = book.rating,
                        onChange = if (vm.canRate) vm::setRating else null,
                        enabled = !ui.ratingUpdating,
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenCollectionSheet),
        ) {
            Text(
                if (book.collections.isNotEmpty()) {
                    pluralStringResource(R.plurals.detail_in_collections, book.collections.size, book.collections.size,
                    )
                } else {
                    stringResource(R.string.detail_add_to_collection)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(14.dp),
            )
        }
        if (book.collections.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                book.collections.forEach { Chip(it.name) }
            }
        }
    }
}

@Composable
private fun FilesTab(book: BookDetail) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (book.files.isEmpty()) {
            Text(stringResource(R.string.no_files), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        book.files.forEach { f ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (f.format ?: "file").uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (f.role == "primary") {
                        Text(" · " + stringResource(R.string.primary), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.weight(1f))
                    val detail = listOfNotNull(
                        f.sizeBytes?.let { formatBytes(it) },
                        f.durationSeconds?.let { formatDuration(it) },
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                f.filename?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun HighlightsTab(highlights: List<BookAnnotation>) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (highlights.isEmpty()) {
            Text(
                stringResource(R.string.no_highlights_yet_press_and),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        highlights.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(4.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(highlightColor(a.color))
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(a.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    )
                    a.note?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    a.chapterTitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "%d KB".format(bytes / 1_000)
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun DetailsSection(book: BookDetail) {
    val rows = buildList {
        book.pageCount?.let { add(stringResource(R.string.detail_row_pages) to it.toString()) }
        book.language?.let { add(stringResource(R.string.detail_row_language) to it.uppercase()) }
        book.publisher?.let { add(stringResource(R.string.detail_row_publisher) to it) }
        book.publishedYear?.let { add(stringResource(R.string.detail_row_published) to it.toString()) }
        book.isbn13?.let { add(stringResource(R.string.detail_row_isbn13) to it) }
        book.isbn10?.let { add(stringResource(R.string.detail_row_isbn10) to it) }
        add(stringResource(R.string.detail_row_library) to book.libraryName)
    }
    if (rows.isEmpty()) return
    Section(stringResource(R.string.details)) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 24.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
