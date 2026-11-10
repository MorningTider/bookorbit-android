package com.bookorbit.feature.bookdetail

import com.bookorbit.core.db.ReaderProgressDao
import com.bookorbit.core.db.ReaderProgressEntity
import com.bookorbit.core.model.BookFileProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import com.bookorbit.core.db.PendingRatingDao
import com.bookorbit.core.db.PendingRatingEntity
import com.bookorbit.core.db.PendingReadStatusDao
import com.bookorbit.core.db.PendingReadStatusEntity
import com.bookorbit.core.model.BookAnnotation
import com.bookorbit.core.model.BookDetail
import com.bookorbit.core.model.BookRecommendation
import com.bookorbit.core.model.CollectionBookIds
import com.bookorbit.core.model.CollectionWithMembership
import com.bookorbit.core.model.SetRatingRequest
import com.bookorbit.core.model.SetReadStatusRequest
import com.bookorbit.core.model.UserBookStatus
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.sync.SyncScheduler
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookDetailRepository @Inject constructor(
    private val api: ApiService,
    private val pendingRatingDao: PendingRatingDao,
    private val pendingReadStatusDao: PendingReadStatusDao,
    private val syncScheduler: SyncScheduler,
    private val progressDao: ReaderProgressDao,
) {
    suspend fun bookProgress(id: Int) = api.getBookFileProgress(id)

    fun observeProgress(fileIds: List<Int>): Flow<List<ReaderProgressEntity>> {
        val batches = fileIds.distinct().chunked(900)
        if (batches.isEmpty()) return flowOf(emptyList())
        return combine(batches.map { progressDao.observeFiles(it) }) { rows -> rows.flatMap { it } }
    }

    suspend fun localProgress(fileIds: List<Int>): List<ReaderProgressEntity> =
        fileIds.distinct().chunked(900).flatMap { progressDao.getFiles(it) }

    fun displayedProgress(
        remote: List<BookFileProgress>,
        local: List<ReaderProgressEntity>,
        localAtFetch: Map<Int, ReaderProgressEntity>? = null,
    ): Map<Int, Double> {
        val result = remote.associate { it.fileId to it.percentage }.toMutableMap()
        for (entry in local) {
            val row = remote.firstOrNull { it.fileId == entry.fileId }
            if (row == null || entry.dirty || (localAtFetch != null && entry != localAtFetch[entry.fileId]))
                result[entry.fileId] = entry.percentage
        }
        return result
    }

    suspend fun readingLog(id: Int, page: Int) = api.getBookReadingLog(id, page)

    suspend fun detail(id: Int): BookDetail = api.getBookDetail(id)

    suspend fun authorBooks(id: Int): List<BookRecommendation> = api.getAuthorBooksForBook(id)

    suspend fun recommendations(id: Int): List<BookRecommendation> = api.getRecommendations(id)

    suspend fun highlights(id: Int): List<BookAnnotation> = api.getAnnotations(id)

    /** Reading position (0-100) for one file, or null if it has none or the call fails. */
    suspend fun fileProgress(fileId: Int): Double? = runCatching { api.getFileProgress(fileId)?.percentage }.getOrNull()

    /**
     * Writes the pending status locally first so it's never lost offline, then attempts the server
     * call — clearing the pending row on success and leaving it queued (for [SyncScheduler] to
     * retry) on failure.
     */
    suspend fun setReadStatus(id: Int, status: String) {
        pendingReadStatusDao.upsert(PendingReadStatusEntity(id, status, System.currentTimeMillis()))
        val ok = runCatching { api.setReadStatus(id, SetReadStatusRequest(status)) }.isSuccess
        if (ok) pendingReadStatusDao.delete(id) else syncScheduler.schedule()
    }

    /** Single-book rating reuses the bulk endpoint with one id, matching the web client. */
    suspend fun setRating(id: Int, rating: Int?) {
        pendingRatingDao.upsert(PendingRatingEntity(id, rating, System.currentTimeMillis()))
        val ok = runCatching { api.setRating(SetRatingRequest(bookIds = listOf(id), rating = rating)) }.isSuccess
        if (ok) pendingRatingDao.delete(id) else syncScheduler.schedule()
    }

    /**
     * Overlays any not-yet-synced local rating/read-status onto [book], so a queued offline edit
     * keeps showing correctly regardless of whether [book] came from the server or the offline
     * download cache, and survives app restarts.
     */
    suspend fun applyPending(book: BookDetail): BookDetail {
        val pendingRating = pendingRatingDao.get(book.id)
        val pendingStatus = pendingReadStatusDao.get(book.id)
        var result = book
        if (pendingRating != null) {
            result = result.copy(rating = pendingRating.rating?.toDouble())
        }
        if (pendingStatus != null) {
            val base = result.readStatus ?: UserBookStatus(
                status = pendingStatus.status,
                source = "local",
                updatedAt = Instant.ofEpochMilli(pendingStatus.updatedAt).toString(),
            )
            result = result.copy(readStatus = base.copy(status = pendingStatus.status))
        }
        return result
    }

    /** Retries every queued rating change. Returns true if anything is still pending afterward. */
    suspend fun flushPendingRatings(): Boolean {
        for (p in pendingRatingDao.all()) {
            val ok = runCatching {
                api.setRating(SetRatingRequest(bookIds = listOf(p.bookId), rating = p.rating))
            }.isSuccess
            if (ok) pendingRatingDao.delete(p.bookId)
        }
        return pendingRatingDao.all().isNotEmpty()
    }

    /**
     * Retries every queued read-status change. Returns true if anything is still pending afterward.
     */
    suspend fun flushPendingReadStatus(): Boolean {
        for (p in pendingReadStatusDao.all()) {
            val ok = runCatching { api.setReadStatus(p.bookId, SetReadStatusRequest(p.status)) }.isSuccess
            if (ok) pendingReadStatusDao.delete(p.bookId)
        }
        return pendingReadStatusDao.all().isNotEmpty()
    }

    suspend fun collectionsForBook(id: Int): List<CollectionWithMembership> =
        api.getCollectionsForBook(id.toString())

    suspend fun addToCollection(collectionId: Int, bookId: Int) =
        api.addBooksToCollection(collectionId, CollectionBookIds(listOf(bookId)))

    suspend fun removeFromCollection(collectionId: Int, bookId: Int) =
        api.removeBooksFromCollection(collectionId, CollectionBookIds(listOf(bookId)))
}
