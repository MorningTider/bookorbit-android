package com.bookorbit.feature.reader

import com.bookorbit.core.db.ReaderProgressDao
import com.bookorbit.core.db.ReaderProgressEntity
import com.bookorbit.core.model.FileProgress
import com.bookorbit.core.model.SaveFileProgress
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.sync.SyncScheduler
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InitialProgress(val cfi: String?, val fraction: Double?)

/**
 * Serializes local checkpoints and remote reconciliation, including deliberate backward reading.
 */
@Singleton
class ReaderProgressRepository
@Inject
constructor(
    private val dao: ReaderProgressDao,
    private val api: ApiService,
    private val syncScheduler: SyncScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val lastTimestamp = AtomicLong()

    fun enqueueReport(fileId: Int, cfi: String?, percentage: Double) {
        val timestamp = timestamp()
        // Acquire the write queue before a newly opened reader can resolve its starting position.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            reportAt(fileId, cfi, percentage, null, timestamp)
        }
    }

    suspend fun report(fileId: Int, cfi: String?, percentage: Double, pageNumber: Int? = null) {
        reportAt(fileId, cfi, percentage, pageNumber, timestamp())
    }

    private fun timestamp(): Long =
        lastTimestamp.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }

    private suspend fun reportAt(
        fileId: Int,
        cfi: String?,
        percentage: Double,
        pageNumber: Int?,
        timestamp: Long,
    ) =
        writes.withLock {
            if ((dao.get(fileId)?.updatedAt ?: 0) > timestamp) return@withLock
            val entry =
                ReaderProgressEntity(
                    fileId,
                    cfi,
                    percentage.coerceIn(0.0, 100.0),
                    timestamp,
                    dirty = true,
                )
            dao.upsert(entry)
            upload(entry, pageNumber)
        }

    private suspend fun remote(fileId: Int): FileProgress? =
        try {
            api.getFileProgress(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun serverStamp(progress: FileProgress): Long? =
        runCatching { Instant.parse(progress.textUpdatedAt ?: progress.updatedAt).toEpochMilli() }
            .getOrNull()

    private suspend fun adopt(
        fileId: Int,
        progress: FileProgress,
        previous: ReaderProgressEntity?,
    ) {
        val timestamp = serverStamp(progress) ?: previous?.updatedAt ?: 0
        lastTimestamp.updateAndGet { maxOf(it, timestamp) }
        dao.upsert(
            ReaderProgressEntity(
                fileId,
                progress.cfi,
                progress.percentage.coerceIn(0.0, 100.0),
                timestamp,
                dirty = false,
            )
        )
    }

    private suspend fun upload(entry: ReaderProgressEntity, pageNumber: Int? = null): Boolean {
        val latest = remote(entry.fileId)
        if (latest != null && (serverStamp(latest) ?: 0) > entry.updatedAt) {
            adopt(entry.fileId, latest, entry)
            return true
        }
        return try {
            api.saveFileProgress(
                entry.fileId,
                SaveFileProgress(entry.cfi, entry.percentage, pageNumber, source = "text"),
            )
            dao.markSynced(entry.fileId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            syncScheduler.schedule()
            false
        }
    }

    suspend fun flushPending(): Boolean =
        writes.withLock {
            for (entry in dao.dirtyEntries()) upload(entry)
            dao.dirtyEntries().isNotEmpty()
        }

    suspend fun syncBeforeWeb(fileId: Int) =
        writes.withLock {
            val local = dao.get(fileId) ?: return@withLock
            if (local.dirty) check(upload(local)) { "Reading progress is waiting for a connection" }
        }

    suspend fun resolveInitial(fileId: Int): InitialProgress =
        writes.withLock {
            val local = dao.get(fileId)
            lastTimestamp.updateAndGet { maxOf(it, local?.updatedAt ?: 0) }
            val latest = remote(fileId)
            if (
                latest != null &&
                    (local == null || !local.dirty || (serverStamp(latest) ?: 0) > local.updatedAt)
            ) {
                adopt(fileId, latest, local)
                return@withLock InitialProgress(latest.cfi, latest.percentage / 100.0)
            }
            if (local != null) {
                if (local.dirty) upload(local)
                val resolved = dao.get(fileId) ?: local
                return@withLock InitialProgress(resolved.cfi, resolved.percentage / 100.0)
            }
            InitialProgress(null, null)
        }

    /** Active reading offers an explicit jump when another client changes the saved position. */
    suspend fun newerRemote(fileId: Int, displayed: InitialProgress? = null): InitialProgress? =
        writes.withLock {
            val local = dao.get(fileId)
            val latest = remote(fileId) ?: return@withLock null
            // A clean row is already acknowledged. Its clock may differ from the server's, so
            // compare the actual position instead of rejecting a later web edit by device time.
            if (local?.dirty == true && (serverStamp(latest) ?: 0) <= local.updatedAt)
                return@withLock null
            adopt(fileId, latest, local)
            val current = displayed ?: InitialProgress(local?.cfi, local?.percentage?.div(100.0))
            if (
                current.cfi == latest.cfi &&
                    kotlin.math.abs((current.fraction ?: 0.0) * 100 - latest.percentage) < .01
            )
                return@withLock null
            InitialProgress(latest.cfi, latest.percentage / 100.0)
        }
}
