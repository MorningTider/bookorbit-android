package com.bookorbit.feature.bookdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bookorbit.R
import com.bookorbit.core.model.BookReadingLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ReadingLogPanel(
    log: BookReadingLog?,
    loading: Boolean,
    error: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(20.dp).testTag("book-reading-log"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        log?.let {
            Text(
                stringResource(
                    R.string.detail_log_summary,
                    it.stats.totalSessions,
                    (it.stats.totalSeconds / 60).toInt(),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) {
            Text(stringResource(R.string.detail_log_error), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        if (!loading && !error && log?.items.isNullOrEmpty())
            Text(
                stringResource(R.string.detail_log_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        log?.items?.forEach { record ->
            val timestamp =
                runCatching {
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.parse(record.startedAt))
                    }
                    .getOrDefault(record.startedAt)
            val source =
                when (record.source) {
                    "android" -> "Android"
                    "web" -> stringResource(R.string.log_source_web)
                    "manual" -> stringResource(R.string.log_source_manual)
                    else -> record.source.orEmpty()
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(timestamp, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOf(source, record.format?.uppercase().orEmpty())
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.log_seconds, record.durationSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    record.endProgress?.let {
                        Text(
                            "${it.toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        if (log != null && log.items.size < log.total && !loading && !error)
            TextButton(onClick = onLoadMore) { Text(stringResource(R.string.rs_more)) }
    }
}
