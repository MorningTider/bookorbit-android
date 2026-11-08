package com.bookorbit.feature.reader

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.storage.LocalRef
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Exercises the production WebView and font repository with an isolated account and EPUB. */
@RunWith(AndroidJUnit4::class)
class ReaderEngineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fontTransportVolumeKeysChapterNavigationAndAutomaticReading() = runBlocking {
        val context = compose.activity
        val json = Json { ignoreUnknownKeys = true }
        val server = MockWebServer().apply { start() }
        val testName = "reader-engine-${System.nanoTime()}"
        val storage = SecureStorage(context, testName, testName, "$testName-owner")
        val session =
            SessionManager(storage, json).apply {
                setServerUrl(server.url("/").toString())
                signIn("isolated-ui-test", AuthUser(1, "isolated-ui-test"))
            }
        val api =
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ApiService::class.java)
        val repository = ReaderFontRepository(api, session, json, context)
        val fontBytes = File("/system/fonts/NotoSerif-Regular.ttf").readBytes()
        val font =
            ReaderFont(
                1,
                "Reader engine test serif",
                "ttf",
                fileSize = fontBytes.size.toLong(),
                createdAt = "test",
            )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(ListSerializer(ReaderFont.serializer()), listOf(font)))
        )
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody(Buffer().write(fontBytes)))
        val family = repository.catalog(true).single()
        repository.download(family)
        val faces = repository.cachedFaces(family)
        assertEquals(1, faces.size)
        val book = File(context.cacheDir, "$testName.epub").apply { writeFixture(this) }
        val background =
            mutableStateOf(readerPalette(ReaderSettings(isDark = false, themeName = "sepia")).page)
        val controller = ReaderController()
        val ready = AtomicBoolean(false)
        val loaded = AtomicBoolean(false)
        val relocated = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val fraction = AtomicReference(0.0)
        val chapter = AtomicReference<String?>(null)
        controller.listener = { event ->
            when (event) {
                ReaderEvent.Ready -> ready.set(true)
                is ReaderEvent.Loaded -> loaded.set(true)
                is ReaderEvent.Relocate -> {
                    fraction.set(event.fraction ?: 0.0)
                    chapter.set(event.chapterHref)
                    relocated.set(true)
                }
                is ReaderEvent.Error -> failure.set(event.message)
                else -> Unit
            }
        }
        try {
            compose.setContent {
                Box(Modifier.fillMaxSize().background(background.value)) {
                    ReaderWebView(
                        controller,
                        repository,
                        Modifier.fillMaxSize(),
                        onRendererGone = { failure.set("Renderer gone") },
                    )
                }
            }
            waitUntil("bridge ready", failure) { ready.get() }
            val settings =
                ReaderSettings(
                    isDark = false,
                    themeName = "sepia",
                    fontSize = 22,
                    lineHeight = 1.8,
                    fontFamily = family.cssFamily,
                    pageAnimation = false,
                )
            controller.open(
                OpenParams(LocalRef.PlainFile(book), "epub", null, null, settings),
                context,
            )
            waitUntil("EPUB loaded", failure) { loaded.get() && relocated.get() }
            val view = AtomicReference<WebView>()
            compose.runOnIdle { view.set(findWebView(context.window.decorView)!!) }
            val webView = view.get()
            controller.setFonts(faces)
            val fontProbe =
                """
                (() => {
                  const d = document.querySelector('foliate-view').renderer.getContents()[0].doc;
                  window.__fontProbe = false;
                  d.fonts.load('22px ${family.cssFamily}').then(f => {
                    const s = d.defaultView.getComputedStyle(d.body);
                    window.__fontProbe = f.length > 0 && s.fontSize === '22px' && s.fontFamily.includes('${family.cssFamily}');
                  });
                })()
            """
                    .trimIndent()
            evaluate(webView, fontProbe)
            waitUntil("downloaded font rendered", failure) {
                evaluate(webView, "window.__fontProbe === true") == "true"
            }
            compose.waitForIdle()
            val painted = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView.postVisualStateCallback(
                    1,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            painted.countDown()
                        }
                    },
                )
                assertTrue(
                    "WebView occupies the screen",
                    webView.width > 200 && webView.height > 200,
                )
            }
            assertTrue("WebView visual state", painted.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(1000)
            assertEquals(
                "Paragraph uses the mobile reading width",
                "true",
                evaluate(
                    webView,
                    """
                (() => {
                  const d = document.querySelector('foliate-view').renderer.getContents()[0].doc;
                  return d.querySelector('p').getBoundingClientRect().width > window.innerWidth * 0.6;
                })()
            """
                        .trimIndent(),
                ),
            )
            screenshot("epub-body-font")
            val start = fraction.get()
            compose.runOnIdle {
                controller.volumeKeyPagingEnabled = true
                controller.onVolumeKey = { next ->
                    if (next) controller.next() else controller.prev()
                    true
                }
                webView.requestFocus()
            }
            InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
            waitUntil("volume key moved page", failure) { fraction.get() > start }
            val beforeAuto = fraction.get()
            controller.autoRead(true, 5)
            waitUntil("automatic page moved", failure, 12_000) { fraction.get() > beforeAuto }
            controller.autoRead(false, 5)
            SystemClock.sleep(500)
            val stopped = fraction.get()
            SystemClock.sleep(5_500)
            assertEquals("Automatic reading remains stopped", stopped, fraction.get(), 0.000001)
            controller.applyStyles(settings.copy(pageCurl = true, themeName = "meadow"))
            compose.runOnIdle {
                background.value = readerPalette(settings.copy(themeName = "meadow")).page
            }
            waitUntil("whole page uses the green background", failure) {
                evaluate(
                    webView,
                    """
                    (() => {
                      const d = document.querySelector('foliate-view').renderer.getContents()[0].doc;
                      return d.defaultView.getComputedStyle(d.documentElement).backgroundColor === 'rgb(228, 239, 213)';
                    })()
                """
                        .trimIndent(),
                ) == "true"
            }
            SystemClock.sleep(300)
            screenshot("eye-comfort-body")
            val host = webView.parent as ReaderPageCurlHost
            val samples = Collections.synchronizedList(mutableListOf<Long>())
            val draws = Collections.synchronizedList(mutableListOf<Long>())
            val vsync = Collections.synchronizedList(mutableListOf<Long>())
            val metricsThread = HandlerThread("reader-frame-metrics").apply { start() }
            val metrics =
                Window.OnFrameMetricsAvailableListener { _, frame, _ ->
                    samples.add(frame.getMetric(FrameMetrics.TOTAL_DURATION))
                    draws.add(frame.getMetric(FrameMetrics.DRAW_DURATION))
                    vsync.add(frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                context.window.addOnFrameMetricsAvailableListener(
                    metrics,
                    Handler(metricsThread.looper),
                )
            }
            try {
                val beforeCurl = fraction.get()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.next() }
                waitUntil("paper fold visible", failure) {
                    var showing = false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val overlay = host.getChildAt(1) as ReaderPageCurlView
                        showing =
                            overlay.visibility == View.VISIBLE && overlay.progress in .25f..0.7f
                    }
                    showing
                }
                screenshot("paper-curl-in-motion")
                waitUntil("paper turn complete", failure) {
                    !host.isTurning && fraction.get() > beforeCurl
                }
                repeat(6) {
                    val before = fraction.get()
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.next() }
                    waitUntil("consecutive paper turn", failure) {
                        !host.isTurning && fraction.get() > before
                    }
                }
                val beforePrevious = fraction.get()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.prev() }
                waitUntil("reverse paper turn", failure) {
                    !host.isTurning && fraction.get() < beforePrevious
                }
                SystemClock.sleep(100)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    context.window.removeOnFrameMetricsAvailableListener(metrics)
                }
                metricsThread.quitSafely()
            }
            val frames = synchronized(samples) { samples.sorted() }
            assertTrue("Frame timing samples collected", frames.size > 20)
            val drawFrames = synchronized(draws) { draws.sorted() }
            val vsyncFrames =
                synchronized(vsync) { vsync.toList() }
                    .zipWithNext { a, b -> b - a }
                    .filter { it > 0 }
                    .sorted()
            val graphics = InstrumentationRegistry.getArguments().getString("graphics", "unspecified")
            val result =
                """{"device":"Android API35 emulator","graphics":"$graphics","frames":${frames.size},"medianMs":${frames[frames.size / 2] / 1_000_000.0},"p95Ms":${frames[(frames.size * .95).toInt().coerceAtMost(frames.lastIndex)] / 1_000_000.0},"drawMedianMs":${drawFrames[drawFrames.size / 2] / 1_000_000.0},"frameIntervalMedianMs":${vsyncFrames[vsyncFrames.size / 2] / 1_000_000.0},"over16_7Ms":${frames.count { it > 16_700_000 }},"maxMs":${frames.last() / 1_000_000.0}}"""
            File(context.getExternalFilesDir(null), "reader-ui-validation/frame-metrics.json")
                .writeText(result)
            val beforeSwipe = fraction.get()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val t = SystemClock.uptimeMillis()
                for ((offset, action, x) in
                    listOf(
                        Triple(0L, android.view.MotionEvent.ACTION_DOWN, .85f),
                        Triple(30L, android.view.MotionEvent.ACTION_MOVE, .45f),
                        Triple(60L, android.view.MotionEvent.ACTION_UP, .15f),
                    )) {
                    android.view.MotionEvent.obtain(
                            t,
                            t + offset,
                            action,
                            webView.width * x,
                            webView.height * .5f,
                            0,
                        )
                        .also {
                            webView.dispatchTouchEvent(it)
                            it.recycle()
                        }
                }
            }
            waitUntil("swipe paper turn", failure) {
                !host.isTurning && fraction.get() > beforeSwipe
            }
            controller.applyStyles(settings)
            controller.nextChapter()
            waitUntil("next chapter", failure) { chapter.get()?.contains("chapter2.xhtml") == true }
            controller.previousChapter()
            waitUntil("previous chapter", failure) {
                chapter.get()?.contains("chapter1.xhtml") == true
            }
            assertTrue("No reader errors", failure.get() == null)
        } finally {
            controller.autoRead(false, 5)
            controller.onVolumeKey = null
            session.signOut()
            book.delete()
            server.shutdown()
        }
    }

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        val value = AtomicReference("null")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) {
                value.set(it)
                done.countDown()
            }
        }
        assertTrue("JavaScript callback", done.await(5, TimeUnit.SECONDS))
        return value.get()
    }

    private fun waitUntil(
        label: String,
        failure: AtomicReference<String?>,
        timeout: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            check(failure.get() == null) { failure.get().orEmpty() }
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let {
                    return it
                }
            }
        return null
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory =
            File(instrumentation.targetContext.getExternalFilesDir(null), "reader-ui-validation")
                .apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val visible =
            (bitmap.width / 4 until bitmap.width * 3 / 4 step 10).sumOf { x ->
                (bitmap.height / 4 until bitmap.height * 3 / 4 step 10).count { y ->
                    val pixel = bitmap.getPixel(x, y)
                    android.graphics.Color.red(pixel) > 80 &&
                        android.graphics.Color.green(pixel) > 80
                }
            }
        assertTrue("Reading body is visibly painted", visible > 100)
        try {
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeFixture(file: File) {
        val entries =
            linkedMapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to
                    """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "OEBPS/content.opf" to
                    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">reader-test</dc:identifier><dc:title>阅读器交互验收</dc:title><dc:language>zh</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>${(1..3).joinToString("") { "<item id=\"c$it\" href=\"chapter$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }}</manifest><spine>${(1..3).joinToString("") { "<itemref idref=\"c$it\"/>" }}</spine></package>""",
                "OEBPS/nav.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>${(1..3).joinToString("") { "<li><a href=\"chapter$it.xhtml\">第${it}章 山间清风</a></li>" }}</ol></nav></body></html>""",
            )
        (1..3).forEach { chapter ->
            entries["OEBPS/chapter$chapter.xhtml"] =
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第${chapter}章</title></head><body><h1>第${chapter}章 山间清风</h1>${(1..80).joinToString("") { "<p>清风穿过山间，吹动了窗边的书页。我们沿着小路走向远处，在晨光里读完新的篇章。 This paragraph verifies font loading and page navigation.</p>" }}</body></html>"""
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
