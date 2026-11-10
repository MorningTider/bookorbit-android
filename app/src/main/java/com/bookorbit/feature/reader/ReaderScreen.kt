package com.bookorbit.feature.reader

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bookorbit.R
import com.bookorbit.ui.asString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onBookDetails: () -> Unit = onBack,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val controller = remember { ReaderController() }
    var ready by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var rendererFailed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var tocVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var highlightsVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf<String?>(null) }
    var autoReading by remember { mutableStateOf(false) }
    var readerTop by remember { mutableStateOf(0.0) }
    var statusClock by remember { mutableStateOf("") }
    var batteryPercent by remember { mutableStateOf<Int?>(null) }

    val context = LocalContext.current
    val density = LocalDensity.current.density
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.checkSyncedProgress()
            if (event == Lifecycle.Event.ON_STOP) {
                autoReading = false
                vm.flush()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.onVolumeKey = null
            controller.volumeKeyPagingEnabled = false
            controller.autoRead(false, 15)
        }
    }
    LaunchedEffect(ui.settings.showReadingStatus, owner) {
        if (ui.settings.showReadingStatus) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    statusClock =
                        android.text.format.DateFormat.getTimeFormat(context)
                            .format(java.util.Date())
                    val battery =
                        context.registerReceiver(
                            null,
                            android.content.IntentFilter(
                                android.content.Intent.ACTION_BATTERY_CHANGED
                            ),
                        )
                    val level =
                        battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale =
                        battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null
                    kotlinx.coroutines.delay(60_000)
                }
            }
        }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.checkSyncedProgress()
                kotlinx.coroutines.delay(15_000)
            }
        }
    }
    LaunchedEffect(autoReading, ui.settings.autoPageIntervalSeconds, ui.loaded) {
        controller.autoRead(autoReading && ui.loaded, ui.settings.autoPageIntervalSeconds)
    }
    controller.volumeKeyPagingEnabled =
        ui.settings.volumeKeyPaging &&
            ui.loaded &&
            !settingsVisible &&
            !tocVisible &&
            !searchVisible &&
            !highlightsVisible
    controller.onVolumeKey = { next ->
        if (controller.volumeKeyPagingEnabled) {
            autoReading = false
            if (next) controller.next() else controller.prev()
            true
        } else false
    }

    // Taps arrive from the page (so text selection keeps working): edges turn the page, the middle
    // toggles the chrome, and any tap while the highlight toolbar is open just dismisses it.
    val onTap = remember { mutableStateOf<(Double) -> Unit>({}) }
    onTap.value = { x ->
        val wasAutoReading = autoReading
        autoReading = false
        when {
            wasAutoReading -> chromeVisible = true
            ui.toolbar != null -> vm.dismissToolbar()
            ui.settings.flow == "paginated" && x < 0.3 -> controller.prev()
            ui.settings.flow == "paginated" && x > 0.7 -> controller.next()
            else -> chromeVisible = !chromeVisible
        }
    }

    // Keep the screen on while reading.
    val view = LocalView.current
    DisposableEffect(ui.settings.keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = ui.settings.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }

    val activity = context.findActivity()
    DisposableEffect(activity) {
        val previous = activity?.window?.attributes?.screenBrightness
        onDispose {
            if (activity != null && previous != null)
                activity.window.attributes =
                    activity.window.attributes.apply { screenBrightness = previous }
        }
    }
    LaunchedEffect(ui.settings.brightness) {
        activity?.window?.let { window ->
            window.attributes =
                window.attributes.apply { screenBrightness = ui.settings.brightness.toFloat() }
        }
    }
    LaunchedEffect(ui.settings, ui.fontFaces, ui.loaded) {
        if (ui.loaded) {
            controller.setFonts(ui.fontFaces)
            controller.applyStyles(ui.settings)
        }
    }

    LaunchedEffect(Unit) {
        controller.listener = { event ->
            when (event) {
                is ReaderEvent.Ready -> ready = true
                is ReaderEvent.Loaded -> vm.onLoaded(event.toc, event.title)
                is ReaderEvent.Pages -> vm.onPages(event.current, event.total, event.wholeBook)
                is ReaderEvent.Relocate ->
                    vm.onRelocate(event.cfi, event.fraction, event.chapterTitle, event.chapterHref)
                ReaderEvent.AutoReadStopped -> autoReading = false
                ReaderEvent.PageTurnRequested,
                ReaderEvent.PageTurnCompleted -> Unit
                is ReaderEvent.Error -> {
                    autoReading = false
                    vm.onError(event.message)
                }
                is ReaderEvent.Tap -> onTap.value(event.x)
                is ReaderEvent.SearchResults -> vm.onSearchResults(event.label, event.hits)
                is ReaderEvent.SearchProgress -> vm.onSearchProgress(event.progress)
                is ReaderEvent.SearchDone -> vm.onSearchDone(event.total, event.capped)
                is ReaderEvent.Selection -> {
                    autoReading = false
                    vm.onSelection(event.text, event.cfi, event.rect)
                }
                ReaderEvent.SelectionCleared -> vm.onSelectionCleared()
                is ReaderEvent.AnnotationTap -> vm.onAnnotationTap(event.cfi, event.rect)
            }
        }
    }

    // Draw the book's highlights in the page whenever they (or the loaded page) change.
    LaunchedEffect(ui.annotations, ui.loaded) {
        if (ui.loaded) controller.setAnnotations(ui.annotations)
    }

    // Drop the page's text selection once the toolbar goes away (saved, dismissed or deleted).
    LaunchedEffect(ui.toolbar == null) {
        if (ui.toolbar == null && ui.loaded) controller.clearSelection()
    }

    ui.message?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg.resolve(context), Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    var comments by remember { mutableStateOf<com.bookorbit.feature.comments.CommentTarget?>(null) }
    comments?.let { target ->
        com.bookorbit.feature.comments.BookCommentsSheet(
            vm.bookId,
            target,
            onDismiss = { comments = null },
        )
    }
    val resolved = ui.resolved
    LaunchedEffect(ready, resolved, rendererFailed) {
        if (ready && resolved != null && !opened && !rendererFailed) {
            opened = true
            withContext(Dispatchers.IO) {
                controller.open(
                    openParamsFor(resolved.ref, resolved.format, resolved.initial, ui.settings)
                        .copy(fontFaces = ui.fontFaces),
                    context,
                )
            }
        }
    }

    val surface = themeBackgroundColor(ui.settings.themeName, ui.settings.isDark)
    val paginated = ui.settings.flow == "paginated"
    val showChrome = chromeVisible
    val onSurface = if (ui.settings.isDark) Color.White else Color.Black

    Box(modifier = Modifier.fillMaxSize().background(surface)) {
        val palette = readerPalette(ui.settings)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    readerTop = it.positionInRoot().y.toDouble() / density
                }
            ) {
                if (!rendererFailed) {
                    ReaderWebView(
                        controller = controller,
                        fontRepository = vm.fontRepository,
                        modifier = Modifier.fillMaxSize(),
                        onRendererGone = {
                            rendererFailed = true
                            autoReading = false
                            vm.onRendererGone()
                        },
                    )
                }
            }
            if (ui.settings.showReadingStatus) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text(
                        statusClock,
                        color = palette.text.copy(alpha = 0.55f),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        ui.currentPage?.let { current ->
                            stringResource(
                                if (ui.wholeBookPages) R.string.rs_pages
                                else R.string.rs_chapter_pages,
                                current,
                                ui.totalPages ?: current,
                            )
                        } ?: "${com.bookorbit.core.model.readingPercentage(ui.precisePercentage)}%",
                        modifier = Modifier.weight(1f),
                        color = palette.text.copy(alpha = 0.55f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        batteryPercent?.let { stringResource(R.string.rs_battery, it) }.orEmpty(),
                        color = palette.text.copy(alpha = 0.55f),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (showChrome) {
            ReaderChrome(
                title = ui.title.orEmpty(),
                chapterTitle = ui.chapterTitle,
                percentage = ui.percentage,
                precisePercentage = ui.precisePercentage,
                elapsedSeconds = ui.elapsedSeconds,
                remainingSeconds = ui.remainingSeconds,
                loaded = ui.loaded,
                settings = ui.settings,
                onBack = onBack,
                onReview = { comments = com.bookorbit.feature.comments.CommentTarget() },
                chapterAvailable = ui.chapterHref != null && ui.resolved != null,
                onChapterComment = {
                    if (ui.chapterHref != null && ui.resolved != null)
                        comments =
                            com.bookorbit.feature.comments.CommentTarget(
                                "chapter",
                                ui.resolved?.fileId,
                                ui.chapterHref,
                                ui.chapterTitle,
                            )
                },
                onSearch = {
                    autoReading = false
                    searchVisible = true
                },
                onHighlights = {
                    autoReading = false
                    vm.loadBookmarks()
                    highlightsVisible = true
                },
                onContents = {
                    autoReading = false
                    tocVisible = true
                },
                onSettings = {
                    autoReading = false
                    settingsVisible = true
                },
                onNight = { vm.updateSettings(ui.settings.copy(isDark = !ui.settings.isDark)) },
                onSeek = {
                    autoReading = false
                    controller.goToFraction(it)
                },
                onPreviousChapter = {
                    autoReading = false
                    controller.previousChapter()
                },
                onNextChapter = {
                    autoReading = false
                    controller.nextChapter()
                },
            )
        }
        if (autoReading) {
            Button(
                onClick = {
                    autoReading = false
                    chromeVisible = true
                },
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 30.dp),
            ) {
                Text(stringResource(R.string.rs_stop_auto_read))
            }
        }

        ui.toolbar?.let { target ->
            val annotation =
                target.annotationId?.let { id -> ui.annotations.firstOrNull { it.id == id } }
            val screenWidth = LocalConfiguration.current.screenWidthDp
            val toolbarWidth = if (annotation != null) 340 else 290
            val rect = target.rect
            val centerX = rect?.let { (it.left + it.right) / 2 } ?: (screenWidth / 2.0)
            val x =
                (centerX - toolbarWidth / 2).coerceIn(
                    8.0,
                    (screenWidth - toolbarWidth - 8).coerceAtLeast(8).toDouble(),
                )
            // Above the selection when there's room, otherwise below it (clear of the selection
            // handles).
            val y =
                when {
                    rect == null -> 120.0
                    rect.top + readerTop > 150 -> rect.top + readerTop - 60
                    else -> rect.bottom + readerTop + 36
                }
            target.selection?.let { selection ->
                TextButton(
                    onClick = {
                        comments =
                            com.bookorbit.feature.comments.CommentTarget(
                                "paragraph",
                                ui.resolved?.fileId,
                                ui.chapterHref,
                                ui.chapterTitle,
                                selection.cfi,
                                selection.text.take(3000),
                            )
                        vm.dismissToolbar()
                    },
                    modifier = Modifier.offset(x = x.dp, y = (y + 52).dp),
                ) {
                    Text(stringResource(R.string.comments_paragraph))
                }
            }
            HighlightToolbar(
                currentColor = annotation?.color,
                onColor = { vm.highlight(it) },
                onNote = { noteDialog = annotation?.note.orEmpty() },
                onDelete = annotation?.let { a -> { vm.deleteAnnotation(a.id) } },
                modifier = Modifier.offset(x = x.dp, y = y.dp),
            )
        }

        if ((!ui.loaded || !ready) && ui.error == null && !rendererFailed) {
            Box(
                modifier = Modifier.fillMaxSize().background(surface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        ui.error?.let { message ->
            Box(
                modifier = Modifier.fillMaxSize().background(surface),
                contentAlignment = Alignment.Center,
            ) {
                Column2(message = message.asString(), onBack = onBack, tint = onSurface)
            }
        }

        if (rendererFailed) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().background(surface).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text(stringResource(R.string.reader_renderer_unavailable), color = onSurface)
                Button(
                    onClick = {
                        ready = false
                        opened = false
                        rendererFailed = false
                    }
                ) {
                    Text(stringResource(R.string.retry))
                }
                Button(onClick = onBack) { Text(stringResource(R.string.back)) }
            }
        }

        // One-time coach overlay teaching the (invisible) paginated tap zones.
        if (!rendererFailed && paginated && ui.loaded && ui.showPagingHint) {
            PagingHintOverlay(onDismiss = { vm.dismissPagingHint() })
        }
    }

    if (tocVisible) {
        ReaderTocSheet(
            toc = ui.toc,
            settings = ui.settings,
            bookTitle = ui.title.orEmpty(),
            currentHref = ui.chapterHref,
            bookId = vm.bookId,
            author = ui.author,
            description = ui.description,
            annotations = ui.annotations,
            onBookDetails = {
                vm.flush()
                tocVisible = false
                onBookDetails()
            },
            onSearch = {
                tocVisible = false
                searchVisible = true
            },
            onNight = { vm.updateSettings(ui.settings.copy(isDark = !ui.settings.isDark)) },
            onSettings = {
                tocVisible = false
                settingsVisible = true
            },
            onSelect = { href ->
                tocVisible = false
                controller.goTo(href)
            },
            onDismiss = { tocVisible = false },
        )
    }
    ui.syncedPosition?.let { position ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissSyncedProgress,
            title = { Text(stringResource(R.string.rs_cloud_progress)) },
            text = {
                Text(
                    stringResource(
                        R.string.rs_cloud_progress_message,
                        ((position.fraction ?: 0.0) * 100).toInt(),
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.dismissSyncedProgress()
                        if (position.cfi != null) controller.goTo(position.cfi)
                        else controller.goToFraction(position.fraction ?: 0.0)
                    }
                ) {
                    Text(stringResource(R.string.rs_cloud_jump))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = vm::keepCurrentProgress) {
                    Text(stringResource(R.string.rs_cloud_keep))
                }
            },
        )
    }
    noteDialog?.let { initial ->
        NoteDialog(
            initial = initial,
            onSave = { note ->
                noteDialog = null
                vm.saveNote(note)
            },
            onDismiss = { noteDialog = null },
        )
    }
    if (searchVisible) {
        ReaderSearchSheet(
            state = ui.search,
            onSearch = { q ->
                vm.startSearch(q)
                controller.search(q)
            },
            onClear = {
                vm.clearSearch()
                controller.clearSearch()
            },
            onJump = { cfi ->
                searchVisible = false
                controller.goTo(cfi)
            },
            onDismiss = { searchVisible = false },
        )
    }
    if (highlightsVisible) {
        HighlightsSheet(
            annotations = ui.annotations,
            bookmarks = ui.bookmarks,
            onJumpBookmark = { b ->
                highlightsVisible = false
                b.cfi?.let(controller::goTo)
            },
            onAddBookmark = vm::addBookmark,
            onDeleteBookmark = { vm.deleteBookmark(it.id) },
            onJump = { a ->
                highlightsVisible = false
                a.cfi?.let(controller::goTo)
            },
            onDelete = { vm.deleteAnnotation(it.id) },
            onDismiss = { highlightsVisible = false },
        )
    }
    if (settingsVisible) {
        ReaderSettingsSheet(
            settings = ui.settings,
            fonts = ui.fonts,
            fontsLoading = ui.fontsLoading,
            downloadingFont = ui.downloadingFont,
            onFont = vm::selectFont,
            onRefreshFonts = vm::refreshFonts,
            onContents = {
                settingsVisible = false
                tocVisible = true
            },
            onStartAutoRead = {
                settingsVisible = false
                chromeVisible = false
                autoReading = true
            },
            onChange = { updated ->
                vm.updateSettings(updated)
                controller.applyStyles(updated)
            },
            onDismiss = { settingsVisible = false },
        )
    }
}

@Composable
private fun PagingHintOverlay(onDismiss: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            Modifier.fillMaxSize()
                .background(Color(0xE60A0A0A))
                .clickable(interactionSource = interaction, indication = null, onClick = onDismiss)
    ) {
        HintZone(
            weight = 0.3f,
            icon = Icons.Filled.ChevronLeft,
            label = stringResource(R.string.previous_page),
        )
        HintZone(
            weight = 0.4f,
            icon = Icons.Filled.TouchApp,
            label = stringResource(R.string.tap_for_menu),
        )
        HintZone(
            weight = 0.3f,
            icon = Icons.Filled.ChevronRight,
            label = stringResource(R.string.next_page),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HintZone(
    weight: Float,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.weight(weight).fillMaxHeight(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(
            label,
            color = Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TapZone(weight: Float, onTap: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier.weight(weight)
                .fillMaxHeight()
                .clickable(interactionSource = interaction, indication = null, onClick = onTap)
    )
}

@Composable
private fun Column2(message: String, onBack: () -> Unit, tint: Color) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
    ) {
        Text(message, color = tint)
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = tint,
            )
        }
    }
}
