package com.bookorbit.core.network

import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.DashboardWidgetRequest
import com.bookorbit.core.model.DashboardWidgetList
import com.bookorbit.core.model.ReadingGoalWidget
import com.bookorbit.core.model.ReadingStreakWidget
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ReadingWidgetsRepositoryTest {
    private val api = mockk<ApiService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val repo = ReadingWidgetsRepository(api, json)

    @Test
    fun `3_8 widgets use the session batch route and decode by type`() = runTest {
        coEvery { api.getDashboardWidgets(DashboardWidgetRequest(listOf("reading-streak", "reading-goal"))) } returns
            json.decodeFromString<DashboardWidgetList>("""{"items":[
                {"type":"reading-goal","data":{"goalBooks":24,"completedBooks":8,"year":2026},"failed":false},
                {"type":"reading-streak","data":{"currentStreak":5,"longestStreak":12,"lastSevenDays":[true,false]},"failed":false}
            ]}""")

        val result = repo.load()
        assertEquals(5, result.streak?.currentStreak)
        assertEquals(listOf(true, false), result.streak?.lastSevenDays)
        assertEquals(24, result.goal?.goalBooks)
        assertEquals(8, result.goal?.completedBooks)
        coVerify(exactly = 0) { api.getReadingStreak() }
        coVerify(exactly = 0) { api.getReadingGoal() }
    }

    @Test
    fun `failed malformed or absent widgets do not cost the other widget`() = runTest {
        for (streak in listOf(
            """{"type":"reading-streak","failed":true,"data":null},""",
            """{"type":"reading-streak","failed":false,"data":{"currentStreak":"bad"}},""",
            "",
        )) {
            coEvery { api.getDashboardWidgets(any()) } returns json.decodeFromString<DashboardWidgetList>(
                """{"items":[$streak{"type":"reading-goal","data":{"goalBooks":12,"completedBooks":3,"year":2026},"failed":false}]}"""
            )
            val result = repo.load()
            assertNull(result.streak)
            assertEquals(3, result.goal?.completedBooks)
        }
    }

    @Test
    fun `older servers fall back and retain a successful individual widget`() = runTest {
        for (code in listOf(404, 405)) {
            coEvery { api.getDashboardWidgets(any()) } throws httpError(code)
            coEvery { api.getReadingStreak() } throws httpError(403)
            coEvery { api.getReadingGoal() } returns ReadingGoalWidget(12, 3, 2026)
            assertEquals(ReadingWidgets(null, ReadingGoalWidget(12, 3, 2026)), repo.load())
        }
    }

    @Test
    fun `permission and server errors do not fall back to guarded routes`() = runTest {
        for (code in listOf(401, 403, 429, 500)) {
            coEvery { api.getDashboardWidgets(any()) } throws httpError(code)
            try {
                repo.load()
                fail("Expected HTTP $code")
            } catch (error: HttpException) {
                assertEquals(code, error.code())
            }
        }
        coVerify(exactly = 0) { api.getReadingStreak() }
        coVerify(exactly = 0) { api.getReadingGoal() }
    }

    @Test
    fun `legacy fallback propagates cancellation`() = runTest {
        coEvery { api.getDashboardWidgets(any()) } throws httpError(404)
        coEvery { api.getReadingStreak() } throws CancellationException()
        try {
            repo.load()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            coVerify(exactly = 0) { api.getReadingGoal() }
        }
    }

    @Test
    fun `production dependency graph loads both 3_8 widgets in one request`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"items":[{"type":"reading-streak","data":{"currentStreak":4}},
                {"type":"reading-goal","data":{"goalBooks":12,"completedBooks":3}}]}"""
            ))
            val result = integratedRepository(server).load()
            assertEquals(4, result.streak?.currentStreak)
            assertEquals(12, result.goal?.goalBooks)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/dashboard/widgets/batch", request.path)
            assertEquals("""{"widgets":["reading-streak","reading-goal"]}""", request.body.readUtf8())
        }
    }

    @Test
    fun `production dependency graph falls back once for 3_5 widgets`() = runTest {
        for (code in listOf(404, 405)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"currentStreak":2}"""))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"goalBooks":12}"""))
                val result = integratedRepository(server).load()
                assertEquals(2, result.streak?.currentStreak)
                assertEquals(12, result.goal?.goalBooks)
                assertEquals(3, server.requestCount)
                assertEquals("/api/v1/dashboard/widgets/batch", server.takeRequest().path)
                assertEquals("/api/v1/dashboard/widgets/reading-streak", server.takeRequest().path)
                assertEquals("/api/v1/dashboard/widgets/reading-goal", server.takeRequest().path)
            }
        }
    }

    private fun integratedRepository(server: MockWebServer): ReadingWidgetsRepository {
        val json = NetworkModule.provideJson()
        val retrofit = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        return ReadingWidgetsRepository(NetworkModule.provideApiService(retrofit), json)
    }

    private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody()))
}
