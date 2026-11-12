package com.bookorbit.feature.search

import androidx.compose.ui.res.stringResource
import com.bookorbit.R
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import com.bookorbit.feature.sources.SourcesScreen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bookorbit.core.model.SearchResult
import com.bookorbit.ui.LocalImageUrls

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBookClick: (Int) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    var sourceTab by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = if (sourceTab) 1 else 0) {
            Tab(selected = !sourceTab, onClick = { sourceTab = false }, text = { Text(stringResource(R.string.sources_local_tab)) })
            Tab(selected = sourceTab, onClick = { sourceTab = true }, text = { Text(stringResource(R.string.sources_tab)) })
        }
        if (sourceTab) {
            SourcesScreen(onBookClick)
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_books)) },
        )

        when {
            state.showHint -> CenteredHint(stringResource(R.string.search_hint))
            state.results.isEmpty() && !state.loading ->
                CenteredHint(stringResource(R.string.search_no_results, query.trim()))
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.results, key = { it.id }) { result ->
                    SearchResultRow(result = result, onClick = { onBookClick(result.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CenteredHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    val imageUrls = LocalImageUrls.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = imageUrls.cover(result.id),
            contentDescription = result.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 44.dp, height = 66.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                result.title ?: stringResource(R.string.card_unknown_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.authors.isNotEmpty()) {
                Text(
                    result.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            result.seriesName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            result.libraryName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
