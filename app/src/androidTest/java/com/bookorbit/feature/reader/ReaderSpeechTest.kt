package com.bookorbit.feature.reader

import android.app.Notification
import android.app.NotificationManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.bookorbit.core.auth.SecureStorage
import com.bookorbit.core.auth.SessionManager
import com.bookorbit.core.di.NetworkModule
import com.bookorbit.core.model.AuthUser
import com.bookorbit.core.network.ApiService
import com.bookorbit.core.storage.LocalRef
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit

class ReaderSpeechTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun txtSpeechAndBackgroundControls() = verify("txt")

    @Test fun epubSpeechAndBackgroundControls() = verify("epub")

    private fun verify(format: String) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        val resolver = context.contentResolver
        val previous =
            Settings.Secure.getString(resolver, "tts_default_synth")?.takeUnless {
                it == instrumentation.context.packageName
            }
        val player =
            EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    SpeechTestEntryPoint::class.java,
                )
                .speech()
        val json = NetworkModule.provideJson()
        val name = "speech-test-${System.nanoTime()}"
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
        val ready = AtomicBoolean()
        val loaded = AtomicBoolean()
        val error = AtomicReference<String?>(null)
        val spoken = java.util.Collections.synchronizedList(mutableListOf<ReaderEvent.Speech>())
        val file =
            File(context.cacheDir, "$name.txt").apply {
                writeText(
                    "第一章 开始\n甲正文第一段。${"阅读测试".repeat(200)}\n甲正文第二段。\n第二章 接续\n乙正文第一段。${"阅读测试".repeat(200)}\n乙正文第二段。"
                )
            }
        if (format == "epub")
            ZipOutputStream(file.outputStream()).use { zip ->
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
                entry(
                    "content.opf",
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">speech-test</dc:identifier><dc:title>朗读测试</dc:title><dc:language>zh-CN</dc:language></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
                )
                entry(
                    "a.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN"><head><title>第一章</title></head><body><h1>第一章 开始</h1><p>甲正文第一段。${"阅读测试".repeat(200)}</p><p>甲正文第二段。</p></body></html>""",
                )
                entry(
                    "b.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN"><head><title>第二章</title></head><body><h1>第二章 接续</h1><p>乙正文第一段。${"阅读测试".repeat(200)}</p><p>乙正文第二段。</p></body></html>""",
                )
            }
        val panel = mutableStateOf(false)
        val previousRate = player.state.value.rate
        val previousVoice = player.state.value.voice
        controller.listener = { event ->
            when (event) {
                ReaderEvent.Ready -> ready.set(true)
                is ReaderEvent.Loaded -> loaded.set(true)
                is ReaderEvent.Speech -> {
                    spoken.add(event)
                    player.accept(event)
                }
                is ReaderEvent.Error -> error.set(event.message)
                else -> Unit
            }
        }
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                check(error.get() == null) { error.get().orEmpty() }
                if (condition()) return
                SystemClock.sleep(20)
            }
            throw AssertionError("Timed out: $label, speech=${player.state.value}, events=$spoken")
        }
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                "android.permission.WRITE_SECURE_SETTINGS"
            )
            Settings.Secure.putString(
                resolver,
                "tts_default_synth",
                instrumentation.context.packageName,
            )
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            compose.setContent {
                ReaderWebView(
                    controller,
                    fonts,
                    Modifier.fillMaxSize(),
                    onRendererGone = { error.set("renderer gone") },
                )
                val speechState by player.state.collectAsStateWithLifecycle()
                if (panel.value)
                    ReaderSpeechSheet(ReaderSettings(), player, speechState) { panel.value = false }
            }
            waitFor("bridge") { ready.get() }
            controller.open(
                OpenParams(
                    LocalRef.PlainFile(file),
                    format,
                    null,
                    null,
                    ReaderSettings(),
                    title = "朗读测试",
                ),
                context,
            )
            waitFor("book") { loaded.get() }
            main {
                player.attach(controller, "朗读测试", controller::speech)
                controller.beforeNavigate = player::stop
                player.prepare()
            }
            waitFor("speech engine") {
                player.state.value.ready || player.state.value.error != null
            }
            assertNull(player.state.value.error)
            assertTrue(
                "Available voices: ${player.state.value.voices}",
                player.state.value.voices.any { it.name == "test-zh" },
            )
            main { panel.value = true }
            compose
                .onNodeWithTag("speech-sleep-60")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            assertEquals(60, player.state.value.sleepMinutes)
            compose.onNodeWithTag("speech-sleep-0").performScrollTo().performClick()
            compose.onNodeWithTag("speech-play").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            SystemClock.sleep(400)
            val screenshot =
                File(context.getExternalFilesDir(null), "reader-ui-validation/speech-$format.png")
            screenshot.parentFile!!.mkdirs()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                screenshot.outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            main {
                player.setRate(1.2f)
                player.setVoice("test-zh")
            }
            compose.onNodeWithTag("speech-play").performClick()
            main { panel.value = false }
            waitFor("first spoken text") { spoken.any { it.text.isNotBlank() } }
            main { player.pause() }
            assertFalse(player.state.value.playing)
            assertTrue(player.state.value.active)
            val size = spoken.size
            SystemClock.sleep(700)
            assertEquals(size, spoken.size)
            val notification =
                context
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .first { it.id == 834 }
                    .notification
            @Suppress("DEPRECATION")
            val token =
                notification.extras.getParcelable<MediaSession.Token>(
                    Notification.EXTRA_MEDIA_SESSION
                )!!
            val media = MediaController(context, token)
            main {
                player.setSleep(15)
                context.moveTaskToBack(true)
                media.transportControls.play()
            }
            waitFor("chapter continuation") { spoken.any { it.text.contains("乙正文") } }
            main { media.transportControls.pause() }
            waitFor("media pause") { !player.state.value.playing }
            val stale = spoken.first { it.text.isNotBlank() }
            main { controller.goToFraction(0.0) }
            assertFalse(player.state.value.active)
            main { player.accept(stale) }
            assertFalse(player.state.value.active)
            instrumentation.uiAutomation
                .executeShellCommand(
                    "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity"
                )
                .use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
            SystemClock.sleep(300)
            main { player.start() }
            waitFor("end of book") { spoken.any { it.done } && !player.state.value.active }
            assertNull(player.state.value.error)
            val complete = spoken.last { it.done }
            assertNotNull(complete.cfi)
            assertEquals(1.0, complete.fraction!!, 0.000001)
            assertTrue(
                spoken
                    .filter { it.text.isNotBlank() }
                    .all { !it.cfi.isNullOrBlank() && it.fraction!! in 0.0..1.0 }
            )
            assertEquals(0, player.state.value.sleepMinutes)
        } finally {
            main {
                player.detach(controller)
                player.setRate(previousRate)
                player.setVoice(previousVoice)
            }
            file.delete()
            session.signOut()
            context.deleteSharedPreferences(name)
            instrumentation.uiAutomation.adoptShellPermissionIdentity(
                "android.permission.WRITE_SECURE_SETTINGS"
            )
            Settings.Secure.putString(resolver, "tts_default_synth", previous)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
