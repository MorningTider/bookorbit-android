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
import androidx.compose.runtime.key
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
        val boldBytes = File("/system/fonts/NotoSerif-Bold.ttf").readBytes()
        val boldFont = font.copy(id = 2, weight = 700, fileSize = boldBytes.size.toLong())
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(ListSerializer(ReaderFont.serializer()), listOf(font, boldFont),
                    ))
        )
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody(Buffer().write(fontBytes)))
        server.enqueue(MockResponse().setBody(Buffer().write(boldBytes)))
        val family = repository.catalog(true).single()
        repository.download(family)
        val faces = repository.cachedFaces(family)
        assertEquals(2, faces.size)
        val book = File(context.cacheDir, "$testName.epub").apply { writeFixture(this) }
        val background =
            mutableStateOf(readerPalette(ReaderSettings(isDark = false, themeName = "sepia")).page)
        var controller = ReaderController()
        val readerInstance = mutableStateOf(0)
        val cfi = AtomicReference<String?>(null)
        val ready = AtomicBoolean(false)
        val loaded = AtomicBoolean(false)
        val firstFrameFontReady = AtomicBoolean(false)
        val pages = AtomicReference<ReaderEvent.Pages?>()
        val loadedEventSeen = AtomicBoolean(false)
        val initialProgressReportedEarly = AtomicBoolean(false)
        val relocated = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val fraction = AtomicReference(0.0)
        val chapter = AtomicReference<String?>(null)
        val listener: (ReaderEvent) -> Unit = { event ->
            when (event) {
                ReaderEvent.Ready -> ready.set(true)
                is ReaderEvent.Loaded -> {
                    loadedEventSeen.set(true)
                    findWebView(context.window.decorView)!!.evaluateJavascript(
                        """
                        (() => {
                          const d = document.querySelector('foliate-view').renderer.getContents()[0]?.doc;
                          return !!d && d.fonts.size > 0 && d.fonts.check('22px ${family.cssFamily}') && d.defaultView.getComputedStyle(d.body).fontFamily.includes('${family.cssFamily}');
                        })()
                    """
                            .trimIndent()
                    ) {
                        firstFrameFontReady.set(it == "true")
                        loaded.set(true)
                    }
                }
                is ReaderEvent.Pages -> pages.set(event)
                is ReaderEvent.Relocate -> {
                    if (!loadedEventSeen.get()) initialProgressReportedEarly.set(true)
                    cfi.set(event.cfi)
                    fraction.set(event.fraction ?: 0.0)
                    chapter.set(event.chapterHref)
                    relocated.set(true)
                }
                is ReaderEvent.Error -> failure.set(event.message)
                else -> Unit
            }
        }
        controller.listener = listener
        try {
            compose.setContent {
                Box(Modifier.fillMaxSize().background(background.value)) {
                    key(readerInstance.value) {
                        ReaderWebView(
                            controller,
                            repository,
                            Modifier.fillMaxSize(),
                            onRendererGone = { failure.set("Renderer gone") },
                        )
                    }
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
                OpenParams(LocalRef.PlainFile(book), "epub", null, null, settings, faces),
                context,
            )
            waitUntil("EPUB loaded", failure) { loaded.get() && relocated.get() }
            assertEquals(
                "Initial positions wait for the loaded event",
                false,
                initialProgressReportedEarly.get(),
            )
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
            val drawCheck = AtomicReference<Bitmap>()
            compose.runOnIdle {
                val image = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(image)
                canvas.scale(540f / webView.width, 1200f / webView.height)
                webView.draw(canvas)
                drawCheck.set(image)
            }
            val drawable = drawCheck.get()
            val directory =
                File(context.getExternalFilesDir(null), "reader-ui-validation").apply { mkdirs() }
            File(directory, "webview-only-snapshot.png").outputStream().use {
                drawable.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            drawable.recycle()
            File(directory, "startup-regression.txt")
                .writeText("fontReadyAtLoaded=${firstFrameFontReady.get()}")
            assertTrue(
                "Saved font must be ready before revealing the first page",
                firstFrameFontReady.get(),
            )
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
            waitUntil("all chapters have real reflowed page counts", failure) {
                pages.get()?.wholeBook == true
            }
            assertTrue("Whole book page count includes later chapters", pages.get()!!.total!! > 10)
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
            var inputToMotionMs = 0.0
            try {
                val beforeCurl = fraction.get()
                val turnRequestedAt = SystemClock.elapsedRealtimeNanos()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.next() }
                waitUntil("paper animation begins", failure) {
                    var moving = false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val overlay = host.getChildAt(1) as ReaderPageCurlView
                        moving = overlay.visibility == View.VISIBLE && overlay.progress > 0f
                    }
                    moving
                }
                inputToMotionMs =
                    (SystemClock.elapsedRealtimeNanos() - turnRequestedAt) / 1_000_000.0
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
                // Keep the screenshot's compositor work outside the timing sample.
                synchronized(samples) { samples.clear() }
                synchronized(draws) { draws.clear() }
                synchronized(vsync) { vsync.clear() }
                repeat(6) {
                    SystemClock.sleep(150)
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
            val graphics =
                InstrumentationRegistry.getArguments().getString("graphics", "unspecified")
            val result =
                """{"device":"Android API35 emulator","graphics":"$graphics","inputToMotionMs":$inputToMotionMs,"frames":${frames.size},"medianMs":${frames[frames.size / 2] / 1_000_000.0},"p95Ms":${frames[(frames.size * .95).toInt().coerceAtMost(frames.lastIndex)] / 1_000_000.0},"drawMedianMs":${drawFrames[drawFrames.size / 2] / 1_000_000.0},"frameIntervalMedianMs":${vsyncFrames[vsyncFrames.size / 2] / 1_000_000.0},"over16_7Ms":${frames.count { it > 16_700_000 }},"maxMs":${frames.last() / 1_000_000.0}}"""
            File(context.getExternalFilesDir(null), "reader-ui-validation/frame-metrics.json")
                .writeText(result)
            SystemClock.sleep(200)
            val beforeHold = fraction.get()
            fun touch(action: Int, x: Float, down: Long) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    android.view.MotionEvent.obtain(
                            down,
                            SystemClock.uptimeMillis(),
                            action,
                            webView.width * x,
                            webView.height * .55f,
                            0,
                        )
                        .also {
                            webView.dispatchTouchEvent(it)
                            it.recycle()
                        }
                }
            }
            val touchTime = SystemClock.uptimeMillis()
            touch(android.view.MotionEvent.ACTION_DOWN, .85f, touchTime)
            touch(android.view.MotionEvent.ACTION_MOVE, .5f, touchTime)
            SystemClock.sleep(800)
            assertTrue(
                "Paper preview remains visible while the finger is down",
                host.isTurning && host.dragProgress in 0.25f..0.5f,
            )
            assertEquals(
                "Holding does not commit reading progress",
                beforeHold,
                fraction.get(),
                .000001,
            )
            screenshot("paper-held-under-finger")
            touch(android.view.MotionEvent.ACTION_UP, .45f, touchTime)
            waitUntil("released gesture commits the page", failure) {
                !host.isTurning && fraction.get() > beforeHold
            }
            SystemClock.sleep(200)
            val beforeCancel = fraction.get()
            val cancelTime = SystemClock.uptimeMillis()
            touch(android.view.MotionEvent.ACTION_DOWN, .85f, cancelTime)
            touch(android.view.MotionEvent.ACTION_MOVE, .5f, cancelTime)
            SystemClock.sleep(250)
            touch(android.view.MotionEvent.ACTION_MOVE, .84f, cancelTime)
            touch(android.view.MotionEvent.ACTION_UP, .84f, cancelTime)
            waitUntil("dragging back restores the page", failure) { !host.isTurning }
            assertEquals(
                "Cancelled preview does not change saved progress",
                beforeCancel,
                fraction.get(),
                .000001,
            )
            SystemClock.sleep(200)
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
            controller.applyStyles(settings.copy(pageTurnEffect = "cover", pageAnimation = false))
            SystemClock.sleep(300)
            val beforeCover = fraction.get()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.next() }
            waitUntil("cover animation begins", failure) { host.isTurning }
            waitUntil("cover turn complete", failure) {
                !host.isTurning && fraction.get() > beforeCover
            }
            controller.applyStyles(settings)
            SystemClock.sleep(100)
            evaluate(
                webView,
                """
                (() => {
                  const renderer = document.querySelector('foliate-view').renderer;
                  const prepare = renderer.prepareDocument;
                  window.__headingFontReadyBeforeReveal = false;
                  renderer.prepareDocument = async doc => {
                    await prepare(doc);
                    window.__headingFontReadyBeforeReveal = doc.fonts.check('700 22px ${family.cssFamily}');
                  };
                })()
            """
                    .trimIndent(),
            )
            val chapterStarted = SystemClock.elapsedRealtime()
            val probe =
                "JSON.stringify({header:document.getElementById('chapter-title').textContent,location:document.querySelector('foliate-view').lastLocation,contents:document.querySelector('foliate-view').renderer.getContents().map(x=>({index:x.index,title:x.doc.title})),pages:document.querySelector('foliate-view').renderer.pages,page:document.querySelector('foliate-view').renderer.page})"
            File(context.getExternalFilesDir(null), "reader-ui-validation/chapter-before.txt")
                .writeText(evaluate(webView, probe))
            controller.nextChapter()
            try {
                waitUntil("next chapter", failure) { chapter.get()?.contains("chapter2.xhtml") == true }
            } finally {
                File(context.getExternalFilesDir(null), "reader-ui-validation/chapter-after.txt")
                    .writeText(evaluate(webView, probe))
            }
            File(context.getExternalFilesDir(null), "reader-ui-validation/chapter-transition.txt")
                .writeText(
                    "chapterMs=${SystemClock.elapsedRealtime() - chapterStarted}\nheadingReady=" +
                        evaluate(webView, "window.__headingFontReadyBeforeReveal")
                )
            assertEquals(
                "Chapter heading bold font is ready before the section can be shown",
                "true",
                evaluate(webView, "window.__headingFontReadyBeforeReveal"),
            )
            assertEquals(
                "New chapter has its selected font before relocation",
                "true",
                evaluate(
                    webView,
                    """(() => { const d=document.querySelector('foliate-view').renderer.getContents()[0].doc; return d.fonts.status==='loaded' && d.fonts.check('22px ${family.cssFamily}') && d.defaultView.getComputedStyle(d.body).fontFamily.includes('${family.cssFamily}'); })()""",
                ),
            )
            assertEquals(
                "Header changes in the same DOM as the chapter",
                "\"第2章 山间清风\"",
                evaluate(webView, "document.getElementById('chapter-title').textContent"),
            )
            controller.previousChapter()
            waitUntil("previous chapter", failure) {
                chapter.get()?.contains("chapter1.xhtml") == true
            }
            controller.goToFraction(.42)
            waitUntil("saved nonzero position", failure) {
                kotlin.math.abs(fraction.get() - .42) < .02
            }
            val savedCfi = cfi.get()
            val savedFraction = fraction.get()
            val cachedSelection = repository.catalog(false).single()
            val savedFaces = repository.cachedFaces(cachedSelection)
            ready.set(false)
            loaded.set(false)
            relocated.set(false)
            firstFrameFontReady.set(false)
            compose.runOnIdle {
                controller = ReaderController().also { it.listener = listener }
                readerInstance.value++
            }
            compose.waitForIdle()
            waitUntil("reopened bridge", failure) { ready.get() }
            loadedEventSeen.set(false)
            initialProgressReportedEarly.set(false)
            relocated.set(false)
            controller.open(
                OpenParams(
                    LocalRef.PlainFile(book),
                    "epub",
                    savedCfi,
                    savedFraction,
                    settings,
                    savedFaces,
                ),
                context,
            )
            waitUntil("reopened book", failure) { loaded.get() && relocated.get() }
            assertTrue(
                "Reopened book reveals the selected font on its first frame",
                firstFrameFontReady.get(),
            )
            val reopened = AtomicReference<WebView>()
            compose.runOnIdle { reopened.set(findWebView(context.window.decorView)!!) }
            val anchor = org.json.JSONObject.quote(savedCfi)
            assertEquals(
                "Saved text remains on the visible page",
                "true",
                evaluate(
                    reopened.get(),
                    """(() => {
                const v=document.querySelector('foliate-view'); const target=v.resolveCFI($anchor);
                const content=v.renderer.getContents().find(c=>c.index===target.index);
                if(!content) return false;
                const range=target.anchor(content.doc); const frame=content.doc.defaultView.frameElement.getBoundingClientRect();
                return Array.from(range.getClientRects()).some(rect=>rect.right+frame.left>0 && rect.left+frame.left<window.innerWidth && rect.bottom+frame.top>0 && rect.top+frame.top<window.innerHeight);
            })()""",
                ),
            )
            val diagnostic =
                evaluate(
                    reopened.get(),
                    """(() => {
              const v=document.querySelector('foliate-view'); const r=v.renderer; const d=r.getContents()[0]?.doc;
              return JSON.stringify({start:r.start,page:r.page,pages:r.pages,body:d?.body?.textContent?.slice(0,80),width:d?.documentElement?.clientWidth,height:d?.documentElement?.clientHeight,fonts:d?.fonts?.status,viewWidth:v.clientWidth,viewHeight:v.clientHeight});
            })()""",
                )
            File(context.getExternalFilesDir(null), "reader-ui-validation/reopen-diagnostic.txt")
                .writeText(
                    "alpha=${reopened.get().alpha} visible=${reopened.get().visibility}\n$diagnostic"
                )
            screenshot("reopened-font-ready")
            assertEquals(
                "No transient layout positions reach progress storage",
                false,
                initialProgressReportedEarly.get(),
            )
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
            SystemClock.sleep(10)
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
        val ink =
            (bitmap.width / 5 until bitmap.width * 4 / 5 step 3).sumOf { x ->
                (bitmap.height / 4 until bitmap.height * 3 / 4 step 3).count { y ->
                    val pixel = bitmap.getPixel(x, y)
                    android.graphics.Color.red(pixel) < 140 &&
                        android.graphics.Color.green(pixel) < 140 &&
                        android.graphics.Color.blue(pixel) < 140
                }
            }
        try {
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(
            "Reading body is visibly painted (visible=$visible, ink=$ink)",
            visible > 100 && ink > 100,
        )
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
