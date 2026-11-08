package com.bookorbit.feature.reader

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class ReaderFont(
    val id: Int,
    val familyName: String,
    val format: String,
    val weight: Int = 400,
    val style: String = "normal",
    val weightMin: Int? = null,
    val weightMax: Int? = null,
    val fileSize: Long,
    val createdAt: String,
)

@Serializable
data class ScopedReaderFont(val scope: String, val font: ReaderFont) {
    val fileName: String
        get() =
            "$scope-${font.id}-${font.fileSize}-${fontDigest(font.createdAt + font.familyName).take(12)}.${font.format}"
}

data class ReaderFontFamily(
    val name: String,
    val cssFamily: String,
    val scope: String,
    val faces: List<ScopedReaderFont>,
    val downloaded: Boolean,
)

@Serializable
data class ReaderFontFace(
    val family: String,
    val fileName: String,
    val format: String,
    val weight: Int,
    val weightMin: Int?,
    val weightMax: Int?,
    val style: String,
)

fun fontDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

fun readerFontCssFamily(name: String, scope: String): String {
    val safe =
        name.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty {
            "font"
        }
    return "__${scope}font_$safe"
}

fun selectableFontFaces(faces: List<ScopedReaderFont>): List<ScopedReaderFont> =
    listOf("normal", "italic").flatMap { style ->
        val options = faces.filter { it.font.style == style }
        listOf(400, 700)
            .mapNotNull { weight ->
                options.minByOrNull { face ->
                    val font = face.font
                    val min = font.weightMin ?: font.weight
                    val max = font.weightMax ?: font.weight
                    when {
                        weight < min -> min - weight
                        weight > max -> weight - max
                        else -> 0
                    }
                }
            }
            .distinctBy { it.font.id }
    }
