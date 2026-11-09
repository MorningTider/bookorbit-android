package com.bookorbit.feature.reader

import androidx.annotation.StringRes
import com.bookorbit.R
import kotlinx.serialization.Serializable

/**
 * Render settings for the foliate reader. Field names MUST match what the WebView bridge
 * (assets/reader/bridge.js) reads, since this is serialized straight into the begin/applyStyles
 * payloads. Mirrors the web client's ReaderState.
 */
@Serializable
data class ReaderSettings(
    val fontSize: Int = 16,
    val lineHeight: Double = 1.5,
    val fontFamily: String? = null,
    val fontWeight: Int = 400,
    val paragraphSpacing: Double = 0.6,
    val letterSpacing: Double = 0.0,
    val paragraphIndent: Boolean = false,
    val sidePadding: Int = 20,
    val brightness: Double = -1.0,
    val keepScreenOn: Boolean = true,
    val maxColumnCount: Int = 2,
    val gap: Double = 0.05,
    val maxInlineSize: Int = 720,
    val maxBlockSize: Int = 1440,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val isDark: Boolean = true,
    val themeName: String = "default",
    val pageAnimation: Boolean = true,
    val pageCurl: Boolean = false,
    val pageTurnEffect: String? = null,
    val backgroundStyle: String = "plain",
    val volumeKeyPaging: Boolean = false,
    val showReadingStatus: Boolean = true,
    val autoPageIntervalSeconds: Int = 15,
    val flow: String = "paginated", // "paginated" | "scrolled"
)

val FONT_SIZE_RANGE = 10..48
val LINE_HEIGHT_RANGE = 0.8..3.0

data class FontFamilyOption(@StringRes val label: Int, val value: String?)

val FONT_FAMILIES =
    listOf(
        FontFamilyOption(R.string.font_original, null),
        FontFamilyOption(R.string.font_serif, "Georgia, \"Times New Roman\", serif"),
        FontFamilyOption(
            R.string.font_sans,
            "-apple-system, \"Helvetica Neue\", Arial, sans-serif",
        ),
        FontFamilyOption(R.string.font_monospace, "Menlo, Consolas, monospace"),
    )

fun clampFontSize(v: Int): Int = v.coerceIn(FONT_SIZE_RANGE.first, FONT_SIZE_RANGE.last)

fun clampLineHeight(v: Double): Double =
    (Math.round(v * 10) / 10.0).coerceIn(LINE_HEIGHT_RANGE.start, LINE_HEIGHT_RANGE.endInclusive)

fun ReaderSettings.turnEffect(): String =
    when {
        flow == "scrolled" -> "scrolled"
        pageTurnEffect in setOf("curl", "cover", "slide", "none") -> pageTurnEffect!!
        pageCurl -> "curl"
        pageAnimation -> "slide"
        else -> "none"
    }
