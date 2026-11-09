package com.bookorbit.feature.bookdetail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bookorbit.R
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.*
import com.bookorbit.core.network.ImageUrls
import com.bookorbit.feature.dashboard.HomeShortcut
import com.bookorbit.feature.dashboard.HomeShortcuts
import com.bookorbit.ui.LocalImageUrls
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BookExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var session: SessionManager
    private lateinit var server: MockWebServer
    private val book =
        BookDetail(
            1,
            1,
            "电子书",
            "available",
            "2026-11-09",
            title = "山间清风",
            subtitle = "阅读体验验收",
            publisher = "示例出版社",
            publishedYear = 2026,
            authors = listOf(BookAuthorRef(2, "验收作者")),
            files = listOf(BookFileRef(10, "epub", "primary")),
        )

    @Before
    fun setup() {
        val image = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(49, 90, 122))
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f
            }
        canvas.drawText("山间", 15f, 70f, paint)
        canvas.drawText("清风", 15f, 108f, paint)
        val bytes =
            ByteArrayOutputStream()
                .also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        image.recycle()
        server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse()
                                .setHeader("Content-Type", "image/png")
                                .setBody(Buffer().write(bytes))
                    }
                start()
            }
        val name = "book-experience-${System.nanoTime()}"
        session =
            SessionManager(
                    SecureStorage(compose.activity, name, name, "$name-owner"),
                    Json { ignoreUnknownKeys = true },
                )
                .apply {
                    setServerUrl(server.url("/").toString())
                    signIn("isolated-ui-test", AuthUser(1, "test"))
                }
    }

    @After
    fun cleanup() {
        session.signOut()
        server.shutdown()
    }

    @Test
    fun homeSearchAndEveryShortcutNavigate() {
        val opened = mutableListOf<HomeShortcut>()
        compose.setContent { MaterialTheme { Surface { HomeShortcuts { opened.add(it) } } } }
        compose.onNodeWithTag("home-search").performClick()
        for (shortcut in
            listOf(
                HomeShortcut.LIBRARY,
                HomeShortcut.DOWNLOADS,
                HomeShortcut.NOTES,
                HomeShortcut.STATS,
            )) compose.onNodeWithTag("home-${shortcut.name.lowercase()}").performClick()
        compose.runOnIdle { assertEquals(HomeShortcut.entries.toSet(), opened.toSet()) }
        screenshot("home-shortcuts")
    }

    @Test
    fun overviewShowsMetadataAndContinuesTheCorrectFormat() {
        var openedAuthor = 0
        var read = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalImageUrls provides ImageUrls(session)) {
                    Surface {
                        Column(Modifier.fillMaxSize()) {
                            DetailHero(book, { id, _ -> openedAuthor = id }, { _, _ -> })
                            Box(Modifier.weight(1f))
                            DetailReadingBar(
                                book,
                                mapOf(10 to 42.0),
                                { read = it },
                                { fail("Unexpected PDF") },
                                { fail("Unexpected comic") },
                                {},
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("验收作者").performClick()
        compose.runOnIdle { assertEquals(2, openedAuthor) }
        compose
            .onNodeWithText(compose.activity.getString(R.string.detail_continue_progress, 42))
            .performClick()
        compose.runOnIdle { assertEquals(1, read) }
        screenshot("book-overview")
    }

    @Test
    fun readingRecordsShowServerSummaryAndLoadMore() {
        var more = false
        val log =
            BookReadingLog(
                items =
                    listOf(
                        BookReadingRecord(
                            1,
                            "2026-11-09T02:00:00Z",
                            "2026-11-09T02:10:00Z",
                            600,
                            42.0,
                            "android",
                            "epub",
                        ),
                        BookReadingRecord(
                            2,
                            "2026-11-08T03:00:00Z",
                            "2026-11-08T03:05:00Z",
                            300,
                            35.0,
                            "web",
                            "epub",
                        ),
                    ),
                total = 3,
                stats = BookReadingStats(3, 1500),
            )
        compose.setContent {
            MaterialTheme { Surface { ReadingLogPanel(log, false, false, { more = true }, {}) } }
        }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose
            .onNodeWithText(compose.activity.getString(R.string.detail_log_summary, 3, 25))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.rs_more)).performClick()
        compose.runOnIdle { assertTrue(more) }
        screenshot("book-reading-log")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(250)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory =
            File(context.getExternalFilesDir(null), "reader-ui-validation").apply { mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
}
