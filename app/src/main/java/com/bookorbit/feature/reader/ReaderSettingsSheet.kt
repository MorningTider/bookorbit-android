package com.bookorbit.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bookorbit.R
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    fonts: List<ReaderFontFamily>,
    fontsLoading: Boolean,
    downloadingFont: String?,
    onFont: (ReaderFontFamily) -> Unit,
    onRefreshFonts: () -> Unit,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.rs_reading_settings),
                style = MaterialTheme.typography.titleLarge,
            )
            Label(stringResource(R.string.rs_label_theme))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                READER_THEMES.forEach { theme ->
                    val selected = settings.themeName == theme.name
                    val label = stringResource(theme.label)
                    Box(
                        modifier =
                            Modifier.size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    Color(if (settings.isDark) theme.darkBg else theme.lightBg)
                                )
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape,
                                )
                                .semantics { contentDescription = label }
                                .clickable { onChange(settings.copy(themeName = theme.name)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.a),
                            color = Color(if (settings.isDark) theme.darkFg else theme.lightFg),
                        )
                    }
                }
            }
            Toggle(stringResource(R.string.dark), settings.isDark) {
                onChange(settings.copy(isDark = it))
            }
            Toggle(stringResource(R.string.rs_system_brightness), settings.brightness < 0) {
                onChange(settings.copy(brightness = if (it) -1.0 else 0.5))
            }
            if (settings.brightness >= 0) {
                SettingSlider(
                    stringResource(R.string.rs_brightness),
                    settings.brightness,
                    0.05..1.0,
                ) {
                    onChange(settings.copy(brightness = it))
                }
            }
            SettingSlider(
                stringResource(R.string.font_size),
                settings.fontSize.toDouble(),
                10.0..48.0,
                settings.fontSize.toString(),
            ) {
                onChange(settings.copy(fontSize = it.roundToInt()))
            }
            SettingSlider(stringResource(R.string.line_height), settings.lineHeight, 0.8..3.0) {
                onChange(settings.copy(lineHeight = clampLineHeight(it)))
            }
            SettingSlider(
                stringResource(R.string.rs_paragraph_spacing),
                settings.paragraphSpacing,
                0.0..2.0,
            ) {
                onChange(settings.copy(paragraphSpacing = it))
            }
            SettingSlider(
                stringResource(R.string.rs_letter_spacing),
                settings.letterSpacing,
                0.0..0.3,
            ) {
                onChange(settings.copy(letterSpacing = it))
            }
            SettingSlider(
                stringResource(R.string.rs_margins),
                settings.sidePadding.toDouble(),
                8.0..48.0,
                settings.sidePadding.toString(),
            ) {
                onChange(settings.copy(sidePadding = it.roundToInt()))
            }
            Label(stringResource(R.string.rs_label_font))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FONT_FAMILIES.forEach { font ->
                    FilterChip(
                        selected = settings.fontFamily == font.value,
                        onClick = { onChange(settings.copy(fontFamily = font.value)) },
                        label = { Text(stringResource(font.label)) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.rs_download_fonts),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onRefreshFonts,
                    enabled = !fontsLoading && downloadingFont == null,
                ) {
                    Text(stringResource(R.string.rs_refresh_fonts))
                }
            }
            if (fontsLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            if (!fontsLoading && fonts.isEmpty()) Text(stringResource(R.string.rs_fonts_empty))
            fonts.forEach { font ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(font.name)
                        Text(
                            stringResource(
                                if (font.scope == "server") R.string.rs_server_font
                                else R.string.rs_user_font
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (downloadingFont == font.cssFamily)
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else
                        TextButton(onClick = { onFont(font) }, enabled = downloadingFont == null) {
                            Text(
                                stringResource(
                                    when {
                                        settings.fontFamily == font.cssFamily ->
                                            R.string.rs_font_selected
                                        font.downloaded -> R.string.rs_font_use
                                        else -> R.string.rs_font_download
                                    }
                                )
                            )
                        }
                }
            }
            Text(
                stringResource(R.string.rs_font_offline_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Toggle(stringResource(R.string.rs_bold), settings.fontWeight >= 700) {
                onChange(settings.copy(fontWeight = if (it) 700 else 400))
            }
            Toggle(stringResource(R.string.rs_indent), settings.paragraphIndent) {
                onChange(settings.copy(paragraphIndent = it))
            }
            Toggle(stringResource(R.string.rs_justify), settings.justify) {
                onChange(settings.copy(justify = it))
            }
            Toggle(stringResource(R.string.rs_keep_screen_on), settings.keepScreenOn) {
                onChange(settings.copy(keepScreenOn = it))
            }
            Label(stringResource(R.string.rs_label_layout))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    settings.flow == "paginated",
                    { onChange(settings.copy(flow = "paginated")) },
                    label = { Text(stringResource(R.string.paginated)) },
                )
                FilterChip(
                    settings.flow == "scrolled",
                    { onChange(settings.copy(flow = "scrolled")) },
                    label = { Text(stringResource(R.string.scrolled)) },
                )
            }
            TextButton(onClick = { onChange(ReaderSettings()) }) {
                Text(stringResource(R.string.rs_reset))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    display: String = String.format(java.util.Locale.getDefault(), "%.1f", value),
    onChange: (Double) -> Unit,
) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(display)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
