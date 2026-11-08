package com.bookorbit.feature.reader

import com.bookorbit.core.db.ReaderProgressDao
import com.bookorbit.core.db.ReaderProgressEntity
import com.bookorbit.core.model.SaveFileProgress
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class InitialProgress(val cfi: String?, val fraction: Double?)

/**
 * Offline-first reading-position store keyed by server file id, backed by Room: every position is
 * written locally first (so it survives offline + restart) and kept dirty until the server accepts
 * it.
 */
@Singleton
class ReaderProgressRepository @Inject constructor(
    private val dao: ReaderProgressDao,
    private val api: ApiService,
    private val syncScheduler: SyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val lastTimestamp = AtomicLong()

    fun enqueueReport(fileId: Int, cfi: String?, percentage: Double) {
        val timestamp = timestamp()
        scope.launch { reportAt(fileId, cfi, percentage, null, timestamp) }
    }

    /**
     * Save locally, then attempt to push to the server (clearing dirty on success). [pageNumber] is
     * forwarded to the server for cross-client parity (used by the PDF reader); the local Room store
     * keeps only cfi + percentage, from which a stable PDF page count is recoverable on resume.
     */
    suspend fun report(fileId: Int, cfi: String?, percentage: Double, pageNumber: Int? = null) {
        reportAt(fileId, cfi, percentage, pageNumber, timestamp())
    }

    private fun timestamp(): Long = lastTimestamp.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }

    private suspend fun reportAt(fileId: Int, cfi: String?, percentage: Double, pageNumber: Int?, timestamp: Long) = writes.withLock {
        if ((dao.get(fileId)?.updatedAt ?: 0) > timestamp) return@withLock
        dao.upsert(ReaderProgressEntity(fileId, cfi, percentage, timestamp, dirty = true))
        val ok = runCatching {
            api.saveFileProgress(fileId, SaveFileProgress(cfi, percentage, pageNumber))
        }.isSuccess
        if (ok) dao.markSynced(fileId) else syncScheduler.schedule()
    }

    /** Push every dirty position to the server. Returns true if anything is still dirty afterward. */
    suspend fun flushPending(): Boolean = writes.withLock {
        for (entry in dao.dirtyEntries()) {
            val ok = runCatching {
                api.saveFileProgress(entry.fileId, SaveFileProgress(entry.cfi, entry.percentage))
            }.isSuccess
            if (ok) dao.markSynced(entry.fileId)
        }
        dao.dirtyEntries().isNotEmpty()
    }

    /** Do not let a web reader start behind a pending offline position for this file. */
    suspend fun syncBeforeWeb(fileId: Int) = writes.withLock {
        val local = dao.get(fileId) ?: return@withLock
        if (!local.dirty) return@withLock
        api.saveFileProgress(fileId, SaveFileProgress(local.cfi, local.percentage))
        dao.markSynced(fileId)
    }

    /**
     * Where to resume: a not-yet-synced local position wins (most recent reading), otherwise the
     * server's, otherwise the start.
     */
    suspend fun resolveInitial(fileId: Int): InitialProgress {
        val local = dao.get(fileId)
        val server = runCatching { api.getFileProgress(fileId) }.getOrNull()

        val cfi: String?
        val percentage: Double?
        if (local != null && (local.dirty || server == null)) {
            cfi = local.cfi
            percentage = local.percentage
        } else if (server != null) {
            cfi = server.cfi
            percentage = server.percentage
        } else {
            return InitialProgress(null, null)
        }
        return InitialProgress(cfi, percentage?.let { it / 100.0 })
    }
}
