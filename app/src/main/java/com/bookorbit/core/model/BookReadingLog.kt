package com.bookorbit.core.model

import kotlinx.serialization.Serializable

@Serializable data class BookFileProgress(val fileId: Int, val percentage: Double = 0.0)

@Serializable
data class BookReadingRecord(
    val id: Int,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Int,
    val endProgress: Double? = null,
    val source: String? = null,
    val format: String? = null,
    val sessionType: String = "read",
)

@Serializable data class BookReadingStats(val totalSessions: Int = 0, val totalSeconds: Long = 0)

@Serializable
data class BookReadingLog(
    val items: List<BookReadingRecord> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val stats: BookReadingStats = BookReadingStats(),
)
