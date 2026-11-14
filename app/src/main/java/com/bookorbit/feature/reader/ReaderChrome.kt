package com.bookorbit.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookorbit.R

@Composable
fun ReaderChrome(
    title: String,
    chapterTitle: String?,
    percentage: Int,
    loaded: Boolean,
    settings: ReaderSettings,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onHighlights: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onNight: () -> Unit,
    onSeek: (Double) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    precisePercentage: Double = percentage.toDouble(),
    elapsedSeconds: Long = 0,
    remainingSeconds: Long? = null,
    onReview: () -> Unit = {},
    onChapterComment: () -> Unit = {},
    chapterAvailable: Boolean = true,
    onSpeech: () -> Unit = {},
) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    val fraction = seeking ?: (precisePercentage / 100).toFloat()
    ReaderAppearance(settings) {
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), shadowElevation = 0.dp) {
                Row(
                    Modifier.statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        chapterTitle?.let {
                            Text(
                                it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onSpeech, enabled = loaded) {
                        Text(stringResource(R.string.speech_title))
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, stringResource(R.string.search_in_book))
                    }
                    IconButton(onClick = onHighlights) {
                        Icon(Icons.Default.Bookmarks, stringResource(R.string.highlights))
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), shadowElevation = 0.dp) {
                Column(
                    Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = onReview) {
                            Text(stringResource(R.string.comments_reviews))
                        }
                        TextButton(
                            onClick = onChapterComment,
                            enabled = loaded && chapterAvailable,
                        ) {
                            Text(stringResource(R.string.comments_chapter))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            stringResource(R.string.rs_elapsed_minutes, elapsedSeconds / 60),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            remainingSeconds?.let {
                                stringResource(R.string.rs_remaining_minutes, (it + 59) / 60)
                            } ?: stringResource(R.string.rs_pace_pending),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onPreviousChapter,
                            enabled = loaded && percentage > 0,
                        ) {
                            Text(stringResource(R.string.rs_previous_chapter), maxLines = 1)
                        }
                        val progressLabel = stringResource(R.string.rs_progress)
                        Slider(
                            value = fraction,
                            onValueChange = { seeking = it },
                            onValueChangeFinished = {
                                seeking?.let { onSeek(it.toDouble()) }
                                seeking = null
                            },
                            enabled = loaded,
                            modifier =
                                Modifier.weight(1f).semantics { contentDescription = progressLabel },
                        )
                        TextButton(onClick = onNextChapter, enabled = loaded && percentage < 100) {
                            Text(stringResource(R.string.rs_next_chapter), maxLines = 1)
                        }
                    }
                    Text(
                        "${com.bookorbit.core.model.readingPercentage(seeking?.let { it.toDouble() * 100 } ?: precisePercentage)}%",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReaderBottomNavigation(settings, onContents, onNight, onSettings)
                }
            }
        }
    }
}

@Composable
private fun ReaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
fun ReaderBottomNavigation(
    settings: ReaderSettings,
    onContents: () -> Unit,
    onNight: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("reader-bottom-navigation"),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        ReaderAction(Icons.AutoMirrored.Filled.List, stringResource(R.string.contents), onContents)
        ReaderAction(
            if (settings.isDark) Icons.Default.LightMode else Icons.Outlined.DarkMode,
            stringResource(if (settings.isDark) R.string.rs_day else R.string.rs_night),
            onNight,
        )
        ReaderAction(Icons.Outlined.Settings, stringResource(R.string.settings), onSettings)
    }
}
