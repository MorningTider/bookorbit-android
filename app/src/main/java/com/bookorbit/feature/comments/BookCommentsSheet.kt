package com.bookorbit.feature.comments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookorbit.R
import com.bookorbit.core.model.BookComment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCommentsSheet(
    bookId: Int,
    target: CommentTarget = CommentTarget(),
    onDismiss: () -> Unit,
    vm: BookCommentsViewModel = hiltViewModel(key = "comments:$bookId"),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var deleting by remember { mutableStateOf<BookComment?>(null) }
    LaunchedEffect(bookId, target) { vm.open(bookId, target) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().padding(horizontal = 20.dp)
        ) {
            Text(
                stringResource(
                    when (target.scope) {
                        "chapter" -> R.string.comments_chapter
                        "paragraph" -> R.string.comments_paragraph
                        else -> R.string.comments_reviews
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.comments_visibility),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            target.quote?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
            if (state.thread != null)
                TextButton(onClick = { vm.thread(null) }, enabled = !state.sending) {
                    Text(stringResource(R.string.comments_back))
                }
            LazyColumn(
                Modifier.weight(1f).testTag("book-comments-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (state.error) item(key = "request-error") {
                    Text(
                        stringResource(
                            if (state.unsupported) R.string.comments_upgrade
                            else R.string.comments_error
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { vm.load() }, enabled = !state.loading) {
                        Text(stringResource(R.string.retry))
                    }
                }
                state.thread?.let { root ->
                    item(key = "root") {
                        CommentRow(
                            root,
                            state.sending,
                            { vm.reply(root) },
                            { deleting = root },
                            null,
                        )
                    }
                }
                items(state.items, key = { it.id }) { row ->
                    CommentRow(
                        row,
                        state.sending,
                        { vm.reply(row) },
                        { deleting = row },
                        if (state.thread == null) ({ vm.thread(row) }) else null,
                    )
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if (state.items.isEmpty() && !state.error)
                    item { Text(stringResource(R.string.comments_empty)) }
                if (state.cursor != null)
                    item {
                        TextButton(onClick = { vm.load(true) }, enabled = !state.loading) {
                            Text(stringResource(R.string.comments_more))
                        }
                    }
            }
            state.reply?.let {
                Text(
                    stringResource(R.string.comments_reply_to, it.authorName),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::draft,
                    enabled = !state.sending,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    label = { Text(stringResource(R.string.comments_write)) },
                )
                Button(
                    onClick = {
                        focus.clearFocus()
                        keyboard?.hide()
                        vm.send()
                    },
                    enabled = state.draft.isNotBlank() && !state.sending && !state.unsupported,
                ) {
                    Text(stringResource(R.string.comments_send))
                }
            }
        }
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.comments_delete_title)) },
            text = { Text(stringResource(R.string.comments_delete_body)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm-comment-delete"),
                    onClick = {
                        deleting = null
                        vm.delete(row)
                    },
                ) {
                    Text(stringResource(R.string.comments_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CommentRow(
    row: BookComment,
    busy: Boolean,
    reply: () -> Unit,
    delete: () -> Unit,
    thread: (() -> Unit)?,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("book-comment-${row.id}")) {
        Column(Modifier.padding(12.dp)) {
            Text(row.authorName, fontWeight = FontWeight.SemiBold)
            Text(
                row.createdAt.replace('T', ' ').take(16),
                style = MaterialTheme.typography.labelSmall,
            )
            if (row.parentId != null)
                Text(
                    stringResource(R.string.comments_reply_number, row.parentId),
                    style = MaterialTheme.typography.labelSmall,
                )
            Text(
                if (row.deleted) stringResource(R.string.comments_deleted) else row.body,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row {
                if (!row.deleted)
                    TextButton(onClick = reply, enabled = !busy) {
                        Text(stringResource(R.string.comments_reply))
                    }
                if (thread != null)
                    TextButton(onClick = thread, enabled = !busy) {
                        Text(stringResource(R.string.comments_thread))
                    }
                if (row.canDelete && !row.deleted)
                    TextButton(onClick = delete, enabled = !busy) {
                        Text(stringResource(R.string.comments_delete))
                    }
            }
        }
    }
}
