package com.bookorbit.feature.reader

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookorbit.R

enum class ReaderSettingsPage {
    QUICK,
    FONTS,
    SPACING,
    THEMES,
    MORE,
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onStartAutoRead: () -> Unit = {},
    onContents: () -> Unit = {},
    showProfiles: Boolean = true,
    perBook: Boolean = false,
    sync: Boolean = false,
    pendingSync: Boolean = false,
    onScope: (Boolean) -> Unit = {},
    onSync: (Boolean) -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(ReaderSettingsPage.QUICK) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.84f
    ReaderPanel(settings, onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            if (page != ReaderSettingsPage.QUICK)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page != ReaderSettingsPage.QUICK) {
                        IconButton(onClick = { page = ReaderSettingsPage.QUICK }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                    Text(
                        stringResource(
                            when (page) {
                                ReaderSettingsPage.QUICK -> R.string.rs_reading_settings
                                ReaderSettingsPage.FONTS -> R.string.rs_label_font
                                ReaderSettingsPage.SPACING -> R.string.rs_spacing_title
                                ReaderSettingsPage.THEMES -> R.string.rs_backgrounds
                                ReaderSettingsPage.MORE -> R.string.rs_more_settings
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
            if (page != ReaderSettingsPage.QUICK) HorizontalDivider()
            if (page == ReaderSettingsPage.FONTS) {
                ReaderFontPicker(
                    settings,
                    fonts,
                    fontsLoading,
                    downloadingFont,
                    onFont,
                    onRefreshFonts,
                    onChange,
                    Modifier.height(maxHeight - 144.dp),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (page) {
                        ReaderSettingsPage.QUICK ->
                            QuickSettings(
                                settings,
                                fonts,
                                onChange,
                                onFonts = { page = ReaderSettingsPage.FONTS },
                                onSpacing = { page = ReaderSettingsPage.SPACING },
                                onThemes = { page = ReaderSettingsPage.THEMES },
                                onMore = { page = ReaderSettingsPage.MORE },
                                onAutoRead = onStartAutoRead,
                            )
                        ReaderSettingsPage.SPACING -> SpacingSettings(settings, onChange)
                        ReaderSettingsPage.THEMES -> ThemePicker(settings, onChange)
                        ReaderSettingsPage.MORE -> {
                            if (showProfiles) {
                                SettingToggle(stringResource(R.string.rs_settings_sync), sync, onSync)
                                SettingToggle(stringResource(R.string.rs_settings_book), perBook, onScope)
                            }
                            if (pendingSync) Text(stringResource(R.string.rs_settings_pending))
                            MoreSettings(settings, onChange)
                        }
                        ReaderSettingsPage.FONTS -> Unit
                    }
                }
            }
            HorizontalDivider()
            ReaderBottomNavigation(
                settings,
                onContents = onContents,
                onNight = { onChange(settings.copy(isDark = !settings.isDark)) },
                onSettings = { page = ReaderSettingsPage.QUICK },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QuickSettings(
    settings: ReaderSettings,
    fonts: List<ReaderFontFamily>,
    onChange: (ReaderSettings) -> Unit,
    onFonts: () -> Unit,
    onSpacing: () -> Unit,
    onThemes: () -> Unit,
    onMore: () -> Unit,
    onAutoRead: () -> Unit,
) {
    QuickRow(R.string.rs_brightness) {
        val label = stringResource(R.string.rs_brightness)
        Slider(
            value = if (settings.brightness < 0) .5f else settings.brightness.toFloat(),
            onValueChange = { onChange(settings.copy(brightness = it.toDouble())) },
            valueRange = .05f..1f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            colors =
                SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .18f),
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .05f),
                ),
            thumb = {
                Box(
                    Modifier.size(24.dp)
                        .shadow(2.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            },
        )
        TextButton(
            onClick = {
                val active = settings.themeName == "meadow" && !settings.isDark
                onChange(
                    settings.copy(themeName = if (active) "default" else "meadow", isDark = false)
                )
            }
        ) {
            Text(stringResource(R.string.rs_eye_comfort), fontSize = 13.sp)
        }
    }
    QuickRow(R.string.font_size) {
        ReadingPill(
            stringResource(R.string.rs_a_minus),
            Modifier.width(52.dp).testTag("reader-font-smaller"),
            settings.fontSize > FONT_SIZE_RANGE.first,
        ) {
            onChange(settings.copy(fontSize = clampFontSize(settings.fontSize - 1)))
        }
        Text(
            settings.fontSize.toString(),
            Modifier.width(28.dp).testTag("reader-font-size"),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 14.sp,
        )
        ReadingPill(
            stringResource(R.string.rs_a_plus),
            Modifier.width(52.dp).testTag("reader-font-larger"),
            settings.fontSize < FONT_SIZE_RANGE.last,
        ) {
            onChange(settings.copy(fontSize = clampFontSize(settings.fontSize + 1)))
        }
        val fontName =
            fonts.firstOrNull { it.cssFamily == settings.fontFamily }?.name
                ?: FONT_FAMILIES.firstOrNull { it.value == settings.fontFamily }
                    ?.let { stringResource(it.label) }
                ?: stringResource(R.string.rs_label_font)
        ReadingPill(
            fontName + " ›",
            Modifier.weight(1f).testTag("reader-open-fonts"),
            onClick = onFonts,
        )
    }
    QuickRow(R.string.rs_colors) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                    "default" to false,
                    "sepia" to false,
                    "meadow" to false,
                    "azure" to false,
                    "amoled" to true,
                    "default" to true,
                    "gray" to true,
                )
                .forEach { (name, dark) ->
                    val theme = READER_THEMES.first { it.name == name }
                    val selected = name == settings.themeName && dark == settings.isDark
                    Box(
                        Modifier.size(30.dp)
                            .clip(CircleShape)
                            .background(Color(if (dark) theme.darkBg else theme.lightBg))
                            .border(
                                if (selected) 1.5.dp else .7.dp,
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (selected) 1f else .25f
                                ),
                                CircleShape,
                            )
                            .semantics {
                                contentDescription = name
                                this.selected = selected
                            }
                            .clickable { onChange(settings.copy(themeName = name, isDark = dark)) }
                    )
                }
        }
    }
    QuickRow(R.string.rs_background_short) {
        listOf(
                "plain" to R.string.rs_background_plain,
                "paper" to R.string.rs_background_paper,
                "soft" to R.string.rs_background_soft,
            )
            .forEach { (style, label) ->
                val page = readerPalette(settings).page
                val brush =
                    Brush.linearGradient(
                        listOf(page, if (style == "plain") page else page.copy(alpha = .65f), page)
                    )
                Box(
                    Modifier.weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(brush)
                        .border(
                            if (settings.backgroundStyle == style) 1.dp else .5.dp,
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (settings.backgroundStyle == style) .9f else .15f
                            ),
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onChange(settings.copy(backgroundStyle = style)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), fontSize = 12.sp)
                }
            }
        TextButton(onClick = onThemes, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Icon(Icons.Default.ChevronRight, stringResource(R.string.rs_more), Modifier.size(18.dp))
        }
    }
    QuickRow(R.string.rs_page_turn_short) {
        FlowRow(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .035f))
                .padding(2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(
                    "curl" to R.string.rs_turn_curl,
                    "cover" to R.string.rs_turn_cover,
                    "slide" to R.string.rs_turn_slide,
                    "scrolled" to R.string.rs_turn_scroll,
                    "none" to R.string.rs_turn_none,
                )
                .forEach { (effect, label) ->
                    Text(
                        stringResource(label),
                        Modifier.clip(RoundedCornerShape(24.dp))
                            .background(
                                if (settings.turnEffect() == effect)
                                    MaterialTheme.colorScheme.surface
                                else Color.Transparent
                            )
                            .semantics { selected = settings.turnEffect() == effect }
                            .clickable {
                                onChange(
                                    settings.copy(
                                        flow =
                                            if (effect == "scrolled") "scrolled" else "paginated",
                                        pageTurnEffect = effect,
                                        pageCurl = effect == "curl",
                                        pageAnimation = effect == "slide",
                                    )
                                )
                            }
                            .padding(horizontal = 9.dp, vertical = 9.dp),
                        fontSize = 13.sp,
                    )
                }
        }
    }
    QuickRow(R.string.rs_other) {
        ReadingPill(
            stringResource(R.string.rs_spacing_title),
            Modifier.weight(1f),
            onClick = onSpacing,
        )
        ReadingPill(stringResource(R.string.rs_more) + " ›", Modifier.weight(1f), onClick = onMore)
    }
    TextButton(onClick = onAutoRead, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rs_start_auto_read), fontSize = 15.sp)
        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
    }
}

@Composable
private fun QuickRow(@StringRes label: Int, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label),
            Modifier.width(IntrinsicSize.Max).widthIn(min = 30.dp),
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
        )
        content()
    }
}

@Composable
private fun ReadingPill(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f),
        )
    }
}

@Composable
private fun ThemeSwatch(
    theme: ReaderTheme,
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
) {
    val selected = theme.name == settings.themeName
    val label = stringResource(theme.label)
    val color = Color(if (settings.isDark) theme.darkBg else theme.lightBg)
    Box(
        modifier =
            Modifier.size(38.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    CircleShape,
                )
                .semantics {
                    contentDescription = label
                    this.selected = selected
                }
                .clickable { onChange(settings.copy(themeName = theme.name)) },
        contentAlignment = Alignment.Center,
    ) {
        if (selected)
            Icon(
                Icons.Default.Check,
                null,
                tint = Color(if (settings.isDark) theme.darkFg else theme.lightFg),
                modifier = Modifier.size(20.dp),
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePicker(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        READER_THEMES.forEach { theme ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeSwatch(theme, settings, onChange)
                Text(stringResource(theme.label), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    SettingToggle(stringResource(R.string.dark), settings.isDark) {
        onChange(settings.copy(isDark = it))
    }
}

@Composable
private fun ReaderFontPicker(
    settings: ReaderSettings,
    fonts: List<ReaderFontFamily>,
    loading: Boolean,
    downloading: String?,
    onFont: (ReaderFontFamily) -> Unit,
    onRefresh: () -> Unit,
    onChange: (ReaderSettings) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.rs_font_offline_short),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRefresh, enabled = !loading && downloading == null) {
                Text(stringResource(R.string.rs_refresh_fonts))
            }
        }
        if (loading) CircularProgressIndicator(Modifier.size(22.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(FONT_FAMILIES, key = { it.label }) { font ->
                FontCard(
                    stringResource(font.label),
                    stringResource(R.string.rs_builtin_font),
                    settings.fontFamily == font.value,
                    false,
                    downloading == null,
                ) {
                    onChange(settings.copy(fontFamily = font.value))
                }
            }
            items(fonts, key = { "${it.scope}:${it.name}" }) { font ->
                val hint =
                    stringResource(
                        when {
                            font.downloaded -> R.string.rs_font_downloaded
                            font.scope == "server" -> R.string.rs_server_font
                            else -> R.string.rs_user_font
                        }
                    )
                FontCard(
                    font.name,
                    hint,
                    settings.fontFamily == font.cssFamily,
                    downloading == font.cssFamily,
                    downloading == null,
                ) {
                    onFont(font)
                }
            }
            if (!loading && fonts.isEmpty())
                item {
                    Text(
                        stringResource(R.string.rs_fonts_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
        }
    }
}

@Composable
private fun FontCard(
    name: String,
    hint: String,
    selected: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color =
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface,
        modifier =
            Modifier.fillMaxWidth()
                .border(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .semantics { this.selected = selected },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                )
                if (loading) CircularProgressIndicator(Modifier.size(18.dp))
                else if (selected)
                    Icon(
                        Icons.Default.Check,
                        stringResource(R.string.rs_font_selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
            }
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpacingSettings(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    Text(stringResource(R.string.rs_live_preview), style = MaterialTheme.typography.bodySmall)
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
    SettingSlider(stringResource(R.string.rs_letter_spacing), settings.letterSpacing, 0.0..0.3) {
        onChange(settings.copy(letterSpacing = it))
    }
    SettingSlider(stringResource(R.string.rs_margins), settings.sidePadding.toDouble(), 8.0..48.0) {
        onChange(settings.copy(sidePadding = it.toInt()))
    }
    SettingToggle(stringResource(R.string.rs_bold), settings.fontWeight >= 700) {
        onChange(settings.copy(fontWeight = if (it) 700 else 400))
    }
    SettingToggle(stringResource(R.string.rs_indent), settings.paragraphIndent) {
        onChange(settings.copy(paragraphIndent = it))
    }
    SettingToggle(stringResource(R.string.rs_justify), settings.justify) {
        onChange(settings.copy(justify = it))
    }
    TextButton(
        onClick = {
            onChange(
                settings.copy(
                    lineHeight = 1.5,
                    paragraphSpacing = 0.6,
                    letterSpacing = 0.0,
                    sidePadding = 20,
                    fontWeight = 400,
                    paragraphIndent = false,
                    justify = true,
                )
            )
        }
    ) {
        Text(stringResource(R.string.rs_reset_spacing))
    }
}

@Composable
private fun MoreSettings(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    SettingToggle(stringResource(R.string.rs_system_brightness), settings.brightness < 0) {
        onChange(settings.copy(brightness = if (it) -1.0 else .5))
    }
    SettingToggle(stringResource(R.string.rs_volume_turn), settings.volumeKeyPaging) {
        onChange(settings.copy(volumeKeyPaging = it))
    }
    SettingToggle(stringResource(R.string.rs_keep_screen_on), settings.keepScreenOn) {
        onChange(settings.copy(keepScreenOn = it))
    }
    SettingToggle(stringResource(R.string.rs_show_status), settings.showReadingStatus) {
        onChange(settings.copy(showReadingStatus = it))
    }
    SettingSlider(
        stringResource(R.string.rs_auto_interval),
        settings.autoPageIntervalSeconds.toDouble(),
        5.0..120.0,
    ) {
        onChange(settings.copy(autoPageIntervalSeconds = it.toInt()))
    }
    Text(stringResource(R.string.rs_auto_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { onChange(ReaderSettings()) }) { Text(stringResource(R.string.rs_reset)) }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onChange: (Double) -> Unit,
) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(String.format(java.util.Locale.getDefault(), "%.1f", value))
        }
        Slider(
            value.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
