package com.bookorbit.core.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.CreateBookComment
import com.bookorbit.core.model.LoginRequest
import com.bookorbit.core.model.ReadingProgressWrite
import com.bookorbit.core.model.ReadingProgressWrites
import com.bookorbit.core.model.ReadingTextPosition
import com.bookorbit.core.model.SaveFileProgress
import com.bookorbit.core.model.SaveReadingSession
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Opt-in integration against a disposable NAS account, never a production library. */
@RunWith(AndroidJUnit4::class)
class Server37IntegrationTest {
    @Serializable
    private data class Fixture(
        val url: String,
        val username: String,
        val password: String,
        val bookId: Int,
        val fileId: Int,
        val scopeId: Int,
    )

    @Test
    fun publicEndpointsRoundTripProgressStatisticsSessionsAndComments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credentials = File(context.filesDir, "server37-validation.json")
        assumeTrue("Requires an explicitly prepared disposable NAS fixture", credentials.isFile)
        val json = NetworkModule.provideJson()
        val fixture = json.decodeFromString<Fixture>(credentials.readText())
        assertTrue(
            "Integration account must be disposable",
            fixture.username.startsWith("android08_"),
        )
        var token: String? = null
        val paths = mutableListOf<String>()
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val builder = chain.request().newBuilder()
                    token?.let { builder.header("Authorization", "Bearer $it") }
                    paths.add(chain.request().url.encodedPath)
                    chain.proceed(builder.build())
                }
                .build()
        val retrofit =
            Retrofit.Builder()
                .baseUrl(fixture.url)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        val api = NetworkModule.provideApiService(retrofit)
        token = api.login(LoginRequest(fixture.username, fixture.password)).accessToken
        val firstCfi = "epubcfi(/6/2!/4/2/1:0)"
        val nextCfi = "epubcfi(/6/2!/4/2/1:5)"

        // The web public API and Android adapter share the same file position in both directions.
        api.putReadingProgress(
            ReadingProgressWrites(
                listOf(
                    ReadingProgressWrite(
                        fixture.fileId,
                        21.5,
                        "text",
                        ReadingTextPosition(firstCfi, 8),
                    )
                )
            )
        )
        assertEquals(firstCfi, api.getFileProgress(fixture.fileId)!!.cfi)
        api.saveFileProgress(fixture.fileId, SaveFileProgress(nextCfi, 35.2, 12, "text"))
        val uploaded = api.getReadingProgress(fixture.fileId).items.single()
        assertEquals(nextCfi, uploaded.text.cfi)
        assertEquals(35.2, uploaded.percentage, 0.001)
        api.putReadingProgress(
            ReadingProgressWrites(
                listOf(
                    ReadingProgressWrite(
                        fixture.fileId,
                        12.0,
                        "text",
                        ReadingTextPosition(firstCfi, 4),
                    )
                )
            )
        )
        assertEquals(12.0, api.getFileProgress(fixture.fileId)!!.percentage!!, 0.001)
        assertTrue(api.getSmartScopeBooks(fixture.scopeId, 1, 20, null).total >= 1)
        val widgets = ReadingWidgetsRepository(api, json).load()
        assertTrue(widgets.streak!!.currentStreak >= 0)
        assertTrue(widgets.goal!!.completedBooks >= 0)

        val now = Instant.now()
        api.saveReadingSession(
            fixture.fileId,
            SaveReadingSession(
                UUID.randomUUID().toString(),
                now.minusSeconds(60).toString(),
                now.toString(),
                60,
                1.0,
                12.0,
                "read",
                "android",
            ),
        )
        val log = api.getBookReadingLog(fixture.bookId, 1)
        assertTrue(log.items.any { it.source == "android" && it.durationSeconds == 60 })
        assertTrue(log.stats.totalSeconds >= 60)

        val review =
            api.createBookComment(
                fixture.bookId,
                CreateBookComment(UUID.randomUUID().toString(), "安卓3.7隔离书评验收"),
            )
        val reply =
            api.createBookComment(
                fixture.bookId,
                CreateBookComment(UUID.randomUUID().toString(), "隔离回复验收", parentId = review.id),
            )
        assertEquals(review.id, reply.threadId)
        assertTrue(api.getBookComments(fixture.bookId).items.any { it.id == review.id })
        assertTrue(paths.any { it.endsWith("/reading-progress") })
        assertTrue(paths.any { it.endsWith("/dashboard/widgets/batch") })
        assertTrue(paths.none { it.endsWith("/progress") || it.endsWith("/stats/reading-goal") })
    }
}
