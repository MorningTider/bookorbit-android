package com.bookorbit.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

data class ReaderPalette(val page: Color, val text: Color, val panel: Color, val accent: Color)

fun readerPalette(settings: ReaderSettings): ReaderPalette {
    val theme = READER_THEMES.firstOrNull { it.name == settings.themeName } ?: READER_THEMES.first()
    return ReaderPalette(
        page = Color(if (settings.isDark) theme.darkBg else theme.lightBg),
        text = Color(if (settings.isDark) theme.darkFg else theme.lightFg),
        panel = if (settings.isDark) Color(0xFF292826) else Color(theme.lightBg),
        accent =
            when {
                settings.isDark -> Color(0xFFFF9B82)
                settings.themeName == "meadow" -> Color(0xFF587A35)
                else -> Color(0xFFC74734)
            },
    )
}

@Composable
fun ReaderAppearance(settings: ReaderSettings, content: @Composable () -> Unit) {
    val colors = readerPalette(settings)
    val scheme = if (settings.isDark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme =
            scheme.copy(
                primary = colors.text,
                onPrimary = colors.page,
                primaryContainer = colors.accent.copy(alpha = 0.12f).compositeOver(colors.panel),
                onPrimaryContainer = colors.text,
                secondary = colors.accent,
                onSecondary = if (settings.isDark) Color.Black else Color.White,
                secondaryContainer = colors.accent.copy(alpha = 0.1f).compositeOver(colors.panel),
                onSecondaryContainer = colors.text,
                surface = colors.panel,
                surfaceVariant = colors.page,
                onSurface = colors.text,
                onSurfaceVariant = colors.text.copy(alpha = 0.7f),
                outline = colors.text.copy(alpha = 0.25f),
                outlineVariant = colors.text.copy(alpha = 0.12f),
            ),
        content = content,
    )
}
