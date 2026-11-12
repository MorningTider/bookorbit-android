package com.bookorbit.feature.sources

import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.model.Library
import com.bookorbit.core.network.ApiService
import com.bookorbit.util.MainDispatcherRule
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourcesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val api = mockk<ApiService>()
    private val repository = mockk<SourceRepository>()
    private val session =
        mockk<SessionManager> { every { currentUser } returns AuthUser(1, "reader") }

    private fun configure() {
        coEvery { api.sourceStatus() } returns SourceStatus(SourceAvailability(true, true))
        coEvery { api.getLibraries() } returns
            listOf(
                Library(
                    1,
                    "EPUB",
                    accessLevel = "editor",
                    allowedFormats = listOf("epub"),
                    createdAt = "test",
                    updatedAt = "test",
                ),
                Library(
                    2,
                    "TXT only",
                    accessLevel = "owner",
                    allowedFormats = listOf("txt"),
                    createdAt = "test",
                    updatedAt = "test",
                ),
                Library(
                    3,
                    "Read only",
                    accessLevel = "viewer",
                    createdAt = "test",
                    updatedAt = "test",
                ),
            )
        coEvery { api.sourceDestinations() } returns mapOf("ebook" to SourceDestination(1, 8))
        coEvery { api.sourceJobs(any()) } returns SourceJobsPage()
        coEvery { api.checkImports(any()) } answers
            {
                firstArg<ImportCheck>().candidates.map { ImportCheckResult(it.key) }
            }
    }

    @Test
    fun `destinations require EPUB acceptance and editing rights without old media types`() =
        runTest {
            configure()
            val vm = SourcesViewModel(api, repository, session)
            assertEquals(listOf(1), vm.state.value.libraries.map { it.id })
            assertEquals(1, vm.state.value.libraryId)
            assertEquals(8, vm.state.value.folderId)
        }

    @Test
    fun `a cancelled old search cannot replace a newer search or its duplicate check`() = runTest {
        configure()
        every { repository.search("旧书", any()) } returns
            flow {
                delay(60_000)
                emit(SourceSearchEvent.Book(SourceBook("fanqie", "old", "旧书")))
            }
        every { repository.search("新书", any()) } returns
            flowOf(SourceSearchEvent.Book(SourceBook("fanqie", "new", "新书")))
        val vm = SourcesViewModel(api, repository, session)
        vm.query("旧书")
        vm.search()
        vm.query("新书")
        vm.search()
        assertEquals(listOf("new"), vm.state.value.results.map { it.providerId })
        assertTrue(vm.state.value.checked)
        assertTrue(vm.state.value.searched)
        assertFalse(vm.state.value.searching)
        coVerify(exactly = 1) {
            api.checkImports(match { it.candidates.single().key == "fanqie:new" })
        }
    }

    @Test
    fun `multiple destinations follow a valid configured default and otherwise require selection`() =
        runTest {
            configure()
            coEvery { api.getLibraries() } returns
                listOf(
                    Library(
                        4,
                        "Other",
                        accessLevel = "owner",
                        createdAt = "test",
                        updatedAt = "test",
                    ),
                    Library(
                        1,
                        "Preferred",
                        accessLevel = "editor",
                        createdAt = "test",
                        updatedAt = "test",
                    ),
                )
            assertEquals(1, SourcesViewModel(api, repository, session).state.value.libraryId)
            coEvery { api.sourceDestinations() } returns emptyMap()
            val state = SourcesViewModel(api, repository, session).state.value
            assertNull(state.libraryId)
            assertNull(state.folderId)
        }
}
