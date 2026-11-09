package com.bookorbit.feature.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Reader panels share the same bounded, inset-aware layout and never resize the book itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPanel(
    settings: ReaderSettings,
    onDismiss: () -> Unit,
    dim: Float = 0f,
    content: @Composable ColumnScope.() -> Unit,
) {
    ReaderAppearance(settings) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            scrimColor = Color.Black.copy(alpha = dim),
            dragHandle = null,
            contentWindowInsets = { WindowInsets.safeDrawing },
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            tonalElevation = 0.dp,
            content = content,
        )
    }
}
