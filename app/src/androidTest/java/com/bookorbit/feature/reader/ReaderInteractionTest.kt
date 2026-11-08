package com.bookorbit.feature.reader

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookorbit.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var settings by mutableStateOf(ReaderSettings(isDark = false, themeName = "sepia"))
    private var showSettings by mutableStateOf(true)
    private var showContents by mutableStateOf(false)
    private var automatic by mutableStateOf(false)
    private var selectedHref: String? = null
    private val fonts =
        listOf(
            ReaderFontFamily(
                "宋体 Noto Serif",
                "__serverfont_noto_serif",
                "server",
                emptyList(),
                true,
            ),
            ReaderFontFamily(
                "楷体 LXGW WenKai",
                "__serverfont_lxgw_wenkai",
                "server",
                emptyList(),
                false,
            ),
        )

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(readerPalette(settings).page)) {
                    Text("阅读设置交互验收", Modifier.padding(24.dp))
                    ReaderChrome(
                        "阅读设置交互验收",
                        "第二章 山间清风",
                        42,
                        true,
                        settings,
                        onBack = {},
                        onSearch = {},
                        onHighlights = {},
                        onContents = { showContents = true },
                        onSettings = { showSettings = true },
                        onNight = { settings = settings.copy(isDark = !settings.isDark) },
                        onSeek = {},
                        onPreviousChapter = {},
                        onNextChapter = {},
                    )
                    if (showSettings) {
                        ReaderSettingsSheet(
                            settings,
                            fonts,
                            false,
                            null,
                            onFont = { settings = settings.copy(fontFamily = it.cssFamily) },
                            onRefreshFonts = {},
                            onChange = { settings = it },
                            onDismiss = { showSettings = false },
                            onStartAutoRead = {
                                automatic = true
                                showSettings = false
                            },
                        )
                    }
                    if (showContents) {
                        ReaderTocSheet(
                            (1..100).map { TocItem("第${it}章 山间清风", "chapter-$it") },
                            onSelect = {
                                selectedHref = it
                                showContents = false
                            },
                            onDismiss = { showContents = false },
                            settings = settings,
                            bookTitle = "阅读设置交互验收",
                            currentHref = "chapter-42",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun compactSettingsAndFontSelection() {
        show()
        compose.onNodeWithTag("reader-font-larger").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(17, settings.fontSize) }
        screenshot("compact-settings")
        compose.onNodeWithTag("reader-open-fonts").performClick()
        compose.onNodeWithText("宋体 Noto Serif").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("__serverfont_noto_serif", settings.fontFamily) }
        screenshot("font-picker")
    }

    @Test
    fun spacingAndPageModesApplyImmediately() {
        show()
        compose
            .onNodeWithText(compose.activity.getString(R.string.rs_turn_scroll))
            .performScrollTo()
            .performClick()
        compose.runOnIdle { assertEquals("scrolled", settings.flow) }
        compose.onNodeWithText(compose.activity.getString(R.string.rs_turn_none)).performClick()
        compose.runOnIdle {
            assertEquals("paginated", settings.flow)
            assertEquals(false, settings.pageAnimation)
        }
        compose
            .onNodeWithText(compose.activity.getString(R.string.rs_spacing_title))
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithContentDescription(compose.activity.getString(R.string.line_height))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.9f) }
        compose.runOnIdle { assertEquals(1.9, settings.lineHeight, 0.001) }
        screenshot("spacing-settings")
    }

    @Test
    fun automaticReadingStartsFromTheQuickPanel() {
        show()
        compose
            .onNodeWithText(compose.activity.getString(R.string.rs_start_auto_read))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertTrue(automatic)
            assertEquals(false, showSettings)
        }
    }

    @Test
    fun contentsLocateSearchAndChooseAChapter() {
        showSettings = false
        showContents = true
        show()
        compose.onNodeWithText("第42章 山间清风").assertIsDisplayed().assertIsSelected()
        screenshot("contents")
        compose
            .onNodeWithText(compose.activity.getString(R.string.rs_search_chapters))
            .performTextInput("第99章")
        compose.onNodeWithText("第99章 山间清风").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("chapter-99", selectedHref)
            assertEquals(false, showContents)
        }
    }

    @Test
    fun paperCurlAndEyeComfortAreSelectable() {
        show()
        compose
            .onNodeWithText(compose.activity.getString(R.string.rs_eye_comfort))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals("meadow", settings.themeName)
            assertEquals(false, settings.isDark)
        }
        compose
            .onNodeWithText(compose.activity.getString(R.string.rs_turn_curl))
            .performScrollTo()
            .performClick()
        compose.runOnIdle {
            assertEquals(true, settings.pageCurl)
            assertEquals(false, settings.pageAnimation)
            assertEquals("paginated", settings.flow)
        }
        screenshot("eye-comfort-curl-settings")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory =
            File(instrumentation.targetContext.getExternalFilesDir(null), "reader-ui-validation")
                .apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(directory, "$name.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            image.recycle()
        }
    }
}
