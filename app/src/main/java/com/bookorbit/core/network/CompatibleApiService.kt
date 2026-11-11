package com.bookorbit.core.network

import com.bookorbit.core.model.BookQuery
import com.bookorbit.core.model.BooksPage
import com.bookorbit.core.model.DashboardWidgetRequest
import com.bookorbit.core.model.FileProgress
import com.bookorbit.core.model.Pagination
import com.bookorbit.core.model.ReadingGoalWidget
import com.bookorbit.core.model.ReadingProgressWrite
import com.bookorbit.core.model.ReadingProgressWrites
import com.bookorbit.core.model.ReadingStreakWidget
import com.bookorbit.core.model.ReadingTextPosition
import com.bookorbit.core.model.SaveFileProgress
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.HttpException

/** Keeps existing readers working with the public 3.7 APIs and the earlier 3.5 server. */
class CompatibleApiService(private val api: ApiService, private val json: Json) :
    ApiService by api {
    override suspend fun getFileProgress(fileId: Int): FileProgress? =
        withLegacyFallback(
            current = {
                api.getReadingProgress(fileId)
                    .items
                    .firstOrNull { it.fileId == fileId }
                    ?.asFileProgress()
            },
            legacy = { api.getFileProgress(fileId) },
        )

    override suspend fun saveFileProgress(fileId: Int, body: SaveFileProgress) =
        withLegacyFallback(
            current = {
                api.putReadingProgress(
                    ReadingProgressWrites(
                        listOf(
                            ReadingProgressWrite(
                                fileId = fileId,
                                percentage = body.percentage,
                                kind = body.source ?: "text",
                                text = ReadingTextPosition(cfi = body.cfi, page = body.pageNumber),
                            )
                        )
                    )
                )
            },
            legacy = { api.saveFileProgress(fileId, body) },
        )

    override suspend fun getSmartScopeBooks(id: Int, page: Int, size: Int, q: String?): BooksPage =
        withLegacyFallback(
            current = {
                api.querySmartScopeBooks(id, BookQuery(pagination = Pagination(page, size), q = q))
            },
            legacy = { api.getSmartScopeBooks(id, page, size, q) },
        )

    override suspend fun getReadingStreak(): ReadingStreakWidget =
        withLegacyFallback(
            current = { json.decodeFromJsonElement(widget("reading-streak")) },
            legacy = { api.getReadingStreak() },
        )

    override suspend fun getReadingGoal(): ReadingGoalWidget =
        withLegacyFallback(
            current = { json.decodeFromJsonElement(widget("reading-goal")) },
            legacy = { api.getReadingGoal() },
        )

    private suspend fun widget(type: String) =
        api.getDashboardWidgets(DashboardWidgetRequest(listOf(type)))
            .items
            .firstOrNull { it.type == type && !it.failed }
            ?.data ?: throw IOException("Dashboard widget result is unavailable")

    private suspend fun <T> withLegacyFallback(
        current: suspend () -> T,
        legacy: suspend () -> T,
    ): T =
        try {
            current()
        } catch (error: HttpException) {
            // An expired login, refused permission or server failure must reach the caller.
            if (error.code() != 404 && error.code() != 405) throw error
            legacy()
        }
}
