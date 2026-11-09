package com.bookorbit.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bookorbit.R

enum class HomeShortcut {
    SEARCH,
    LIBRARY,
    DOWNLOADS,
    NOTES,
    STATS,
}

@Composable
internal fun HomeShortcuts(onOpen: (HomeShortcut) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
            modifier =
                Modifier.fillMaxWidth().testTag("home-search").clickable {
                    onOpen(HomeShortcut.SEARCH)
                },
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                Text(
                    stringResource(R.string.home_search_books),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                    Triple(HomeShortcut.LIBRARY, R.string.tab_library, Icons.Outlined.LocalLibrary),
                    Triple(HomeShortcut.DOWNLOADS, R.string.you_downloads, Icons.Filled.Download),
                    Triple(HomeShortcut.NOTES, R.string.tab_notes, Icons.Outlined.EditNote),
                    Triple(HomeShortcut.STATS, R.string.nav_stats, Icons.Outlined.BarChart),
                )
                .forEach { (target, label, icon) ->
                    Column(
                        Modifier.weight(1f)
                            .testTag("home-${target.name.lowercase()}")
                            .clickable { onOpen(target) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f),
                        ) {
                            Icon(
                                icon,
                                null,
                                Modifier.padding(12.dp).size(23.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                    }
                }
        }
    }
}
