package com.bookorbit.feature.reader

import com.bookorbit.core.db.ReaderProgressDao
import com.bookorbit.core.db.ReaderProgressEntity
import com.bookorbit.core.model.FileProgress
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.sync.SyncScheduler
import io.mockk.*
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReaderProgressSyncTest {
    private val dao = mockk<ReaderProgressDao>()
    private val api = mockk<ApiService>()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val repo = ReaderProgressRepository(dao, api, scheduler)
    private var row: ReaderProgressEntity? = null

    @Before
    fun setup() {
        coEvery { dao.get(1) } answers { row }
        coEvery { dao.upsert(any()) } answers { row = firstArg() }
        coEvery { dao.markSynced(1) } answers { row = row?.copy(dirty = false) }
        coEvery { api.saveFileProgress(1, any()) } returns Unit
    }

    private fun server(cfi: String, percent: Double, timestamp: Long) =
        FileProgress(cfi, percent, updatedAt = Instant.ofEpochMilli(timestamp).toString())

    @Test
    fun `reopening adopts the web position including a deliberate backward move`() = runTest {
        row = ReaderProgressEntity(1, "old", 80.0, 1000, false)
        coEvery { api.getFileProgress(1) } returns server("web", 25.0, 2000)
        assertEquals(InitialProgress("web", .25), repo.resolveInitial(1))
        assertEquals("web", row?.cfi)
        assertFalse(row!!.dirty)
    }

    @Test
    fun `a newer web checkpoint supersedes an older offline checkpoint`() = runTest {
        row = ReaderProgressEntity(1, "offline", 30.0, 1000, true)
        coEvery { api.getFileProgress(1) } returns server("web-new", 60.0, 3000)
        assertEquals(InitialProgress("web-new", .6), repo.resolveInitial(1))
        coVerify(exactly = 0) { api.saveFileProgress(any(), any()) }
    }

    @Test
    fun `newer offline reading is uploaded before resuming`() = runTest {
        row = ReaderProgressEntity(1, "offline-new", 20.0, 4000, true)
        coEvery { api.getFileProgress(1) } returns server("web-old", 70.0, 1000)
        assertEquals(InitialProgress("offline-new", .2), repo.resolveInitial(1))
        coVerify {
            api.saveFileProgress(1, match { it.cfi == "offline-new" && it.source == "text" })
        }
        assertFalse(row!!.dirty)
    }

    @Test
    fun `network failure retains the offline checkpoint and queues a retry`() = runTest {
        row = ReaderProgressEntity(1, "offline", 33.0, 1000, true)
        coEvery { api.getFileProgress(1) } throws java.io.IOException("offline")
        coEvery { api.saveFileProgress(1, any()) } throws java.io.IOException("offline")
        assertEquals(InitialProgress("offline", .33), repo.resolveInitial(1))
        assertTrue(row!!.dirty)
        verify { scheduler.schedule() }
    }

    @Test
    fun `active reading offers a new remote position only once`() = runTest {
        row = ReaderProgressEntity(1, "old", 10.0, 1000, false)
        coEvery { api.getFileProgress(1) } returns server("new", 45.0, 2000)
        assertEquals(InitialProgress("new", .45), repo.newerRemote(1))
        assertNull(repo.newerRemote(1))
    }

    @Test
    fun `own upload acknowledgement does not request a jump`() = runTest {
        row = ReaderProgressEntity(1, "same", 10.0, 1000, false)
        coEvery { api.getFileProgress(1) } returns server("same", 10.0, 2000)
        assertNull(repo.newerRemote(1))
        assertEquals(2000L, row!!.updatedAt)
    }

    @Test
    fun `acknowledged local clock ahead does not hide a web change`() = runTest {
        row = ReaderProgressEntity(1, "phone", 80.0, Long.MAX_VALUE / 2, false)
        coEvery { api.getFileProgress(1) } returns server("web", 25.0, 2000)
        assertEquals(InitialProgress("web", .25), repo.newerRemote(1))
    }

    @Test
    fun `reading after a server timestamp ahead can still save a new checkpoint`() = runTest {
        val future = System.currentTimeMillis() + 86_400_000
        coEvery { api.getFileProgress(1) } returns server("web", 25.0, future)
        repo.resolveInitial(1)
        repo.report(1, "phone-next", 30.0)
        coVerify { api.saveFileProgress(1, match { it.cfi == "phone-next" }) }
        assertEquals("phone-next", row?.cfi)
    }

    @Test
    fun `a reconciled row still offers a jump when the displayed page is older`() = runTest {
        row = ReaderProgressEntity(1, "web", 45.0, 2000, false)
        coEvery { api.getFileProgress(1) } returns server("web", 45.0, 2000)
        assertEquals(
            InitialProgress("web", .45),
            repo.newerRemote(1, InitialProgress("visible-old", .1)),
        )
    }
}
