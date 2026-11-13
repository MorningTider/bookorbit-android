package com.bookorbit.feature.reader

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewClientCompat
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.storage.LocalRef
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

/** Counts actual font-file reads across the production reader's chapter documents. */
class ReaderChapterFontTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectedChineseFontIsDecodedOnceAcrossChaptersAndPageCounting() = verifyChapters(false)

    @Test
    fun choosingFontThenCurlingIntoNextChapterKeepsTitlesAndFontReady() =
        verifyChapters(true, "epub")

    @Test fun txtChapterCurlAndBackgroundCountingShareResources() = verifyChapters(true, "txt")

    private fun verifyChapters(selectAfterOpen: Boolean, format: String = "txt") = runBlocking {
        val context = compose.activity
        val json = NetworkModule.provideJson()
        val name = "chapter-font-${System.nanoTime()}"
        val storage = SecureStorage(context, name, name, "$name-owner")
        val session = SessionManager(storage, json)
        session.setServerUrl("https://isolated.invalid")
        session.signIn("isolated", AuthUser(1, "isolated"))
        val api =
            Retrofit.Builder()
                .baseUrl("https://isolated.invalid/")
                .build()
                .create(ApiService::class.java)
        val fonts = ReaderFontRepository(api, session, json, context)
        val controller = ReaderController()
        val ready = AtomicBoolean(false)
        val loaded = AtomicBoolean(false)
        val chapter = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)
        val reads = AtomicInteger(0)
        val directory =
            File(context.filesDir, "reader-fonts/${fontDigest("https://isolated.invalid/1")}")
                .apply { mkdirs() }
        val fontFile = File(directory, "server-1-1-123456abcdef.ttf")
        val source = File("/system/fonts/NotoSerifCJK-Regular.ttc")
        source.copyTo(fontFile, overwrite = true)
        val text = File(context.cacheDir, "$name.$format")
        if (format == "txt") {
            text.writeText(
                (1..6).joinToString("\n") { "第${it}章 中文标题\n" + "中文阅读正文，验证所选字体切换章节。".repeat(120) }
            )
        } else {
            ZipOutputStream(text.outputStream()).use { zip ->
                fun entry(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry(
                    "META-INF/container.xml",
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                )
                val manifest =
                    (1..6).joinToString("") {
                        """<item id="c$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>"""
                    }
                val spine = (1..6).joinToString("") { """<itemref idref="c$it"/>""" }
                entry(
                    "content.opf",
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">chapter-font</dc:identifier><dc:title>测试书</dc:title><dc:language>zh</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""",
                )
                val navigation =
                    (1..6).joinToString("") {
                        """<li><a href="chapter$it.xhtml">第${it}章 中文标题</a></li>"""
                    }
                entry(
                    "nav.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol>$navigation</ol></nav></body></html>""",
                )
                for (index in 1..6) {
                    val paragraph = "中文阅读正文，验证所选字体切换章节。".repeat(120)
                    entry(
                        "chapter$index.xhtml",
                        """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第${index}章 中文标题</title></head><body><h1>第${index}章 中文标题</h1><p>$paragraph</p></body></html>""",
                    )
                }
            }
        }
        val family = "__serverfont_chapter_test"
        val face = ReaderFontFace(family, fontFile.name, "ttf", 400, null, null, "normal")
        val times = mutableListOf<Long>()
        controller.listener = { event ->
            when (event) {
                ReaderEvent.Ready -> ready.set(true)
                is ReaderEvent.Loaded -> loaded.set(true)
                is ReaderEvent.Relocate -> chapter.set(event.chapterHref)
                is ReaderEvent.Error -> failure.set(event.message)
                else -> Unit
            }
        }
        try {
            compose.setContent {
                ReaderWebView(
                    controller,
                    fonts,
                    Modifier.fillMaxSize(),
                    onRendererGone = { failure.set("Renderer gone") },
                )
            }
            waitFor("bridge", failure) { ready.get() }
            val web = findWebView(context.window.decorView)!!
            compose.runOnIdle {
                val delegate = web.webViewClient
                web.webViewClient =
                    object : WebViewClientCompat() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (request.url.path?.startsWith("/reader-fonts/") == true)
                                reads.incrementAndGet()
                            return delegate.shouldInterceptRequest(view, request)
                        }
                    }
            }
            val settings =
                ReaderSettings(
                    fontFamily = family,
                    fontSize = 22,
                    isDark = false,
                    pageAnimation = false,
                )
            controller.open(
                OpenParams(
                    LocalRef.PlainFile(text),
                    format,
                    null,
                    null,
                    if (selectAfterOpen) settings.copy(fontFamily = null) else settings,
                    listOf(face),
                ),
                context,
            )
            waitFor("Chinese font first page", failure) { loaded.get() && chapter.get() != null }
            if (selectAfterOpen) {
                assertEquals(0, reads.get())
                controller.setFonts(listOf(face))
                controller.applyStyles(settings.copy(pageCurl = true))
                waitFor("selected Chinese font applied", failure) {
                    evaluate(
                        web,
                        """(() => {
                        const d = document.querySelector('foliate-view').renderer.getContents()[0].doc;
                        return d.fonts.status === 'loaded' && d.defaultView.getComputedStyle(d.body).fontFamily.includes('$family');
                    })()""",
                    ) == "true"
                }
            }
            for (index in 1..5) {
                if (selectAfterOpen) {
                    evaluate(
                        web,
                        """window.__lastPageReady = false;
                        document.querySelector('foliate-view').renderer.goTo({ index: ${index - 1}, anchor: () => 1 }).then(() => window.__lastPageReady = true);""",
                    )
                    waitFor("last page before chapter boundary", failure) {
                        evaluate(web, "window.__lastPageReady") == "true"
                    }
                    SystemClock.sleep(200)
                }
                val before = chapter.get()
                val start = SystemClock.elapsedRealtime()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    if (selectAfterOpen) controller.next() else controller.nextChapter()
                }
                waitFor("selected font chapter $index", failure) {
                    chapter.get() != before &&
                        (!selectAfterOpen || controller.curlHost?.isTurning == false)
                }
                times.add(SystemClock.elapsedRealtime() - start)
                assertEquals(
                    "true",
                    evaluate(
                        web,
                        """(() => {
                    const d = document.querySelector('foliate-view').renderer.getContents()[0].doc;
                    return d.fonts.check('700 22px $family', '中文标题') && d.fonts.check('400 22px $family', '中文正文') && d.defaultView.getComputedStyle(d.body).fontFamily.includes('$family') && document.getElementById('chapter-title').textContent === d.querySelector('h1').textContent;
                })()""",
                    ),
                )
            }
            val report =
                File(
                    context.getExternalFilesDir(null),
                    "reader-ui-validation/chapter-font${if (selectAfterOpen) "-selected-curl-$format" else ""}.json",
                )
            report.parentFile!!.mkdirs()
            report.writeText(
                """{"fontBytes":${source.length()},"fontReads":${reads.get()},"chapterMs":${times}}"""
            )
            assertEquals(
                "The chosen face is read once for all chapter FontFaceSets and page counting",
                1,
                reads.get(),
            )
            // The chapter's FontFaceSet must reference the same decoded face, including bold
            // fallback.
            assertEquals(
                "true",
                evaluate(
                    web,
                    """(() => {
                const d = document.querySelector('foliate-view').renderer.getContents()[0].doc;
                return [...d.fonts].filter(f => f.family === '$family').every(f => f.status === 'loaded');
            })()""",
                ),
            )
        } finally {
            text.delete()
            fontFile.delete()
            session.signOut()
            context.deleteSharedPreferences(name)
        }
    }

    private fun waitFor(
        label: String,
        failure: AtomicReference<String?>,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(failure.get() == null) { failure.get().orEmpty() }
            if (condition()) return
            SystemClock.sleep(10)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) {
                result.set(it)
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let {
                return it
            }
        return null
    }
}
