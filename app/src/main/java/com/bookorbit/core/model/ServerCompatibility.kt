package com.bookorbit.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class ReadingProgressList(val items: List<ReadingProgressEntry>)

@Serializable
data class ReadingProgressEntry(
    val fileId: Int,
    val percentage: Double,
    val updatedAt: String? = null,
    val text: ReadingTextPosition,
) {
    fun asFileProgress() =
        FileProgress(
            cfi = text.cfi,
            percentage = percentage,
            pageNumber = text.page,
            updatedAt = updatedAt,
            textUpdatedAt = text.updatedAt,
        )
}

@Serializable
data class ReadingTextPosition(
    val cfi: String? = null,
    val page: Int? = null,
    val updatedAt: String? = null,
)

@Serializable data class ReadingProgressWrites(val writes: List<ReadingProgressWrite>)

@Serializable
data class ReadingProgressWrite(
    val fileId: Int,
    val percentage: Double,
    val kind: String,
    val text: ReadingTextPosition,
)

@Serializable data class DashboardWidgetRequest(val widgets: List<String>)

@Serializable data class DashboardWidgetList(val items: List<DashboardWidgetResult>)

@Serializable
data class DashboardWidgetResult(val type: String, val data: JsonElement, val failed: Boolean)
