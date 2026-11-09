package com.bookorbit.feature.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.bookorbit.core.model.BookAnnotation
import com.bookorbit.core.storage.LocalRef
import com.bookorbit.core.storage.openInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parameters for opening a book in the reader. */
data class OpenParams(
    val ref: LocalRef,
    val format: String,
    val cfi: String?,
    val fraction: Double?,
    val settings: ReaderSettings,
    val fontFaces: List<ReaderFontFace> = emptyList(),
)

@Serializable
private data class OpenMeta(
    val format: String,
    val cfi: String?,
    val fraction: Double?,
    val settings: ReaderSettings,
    val fontFaces: List<ReaderFontFace> = emptyList(),
)

// Base64 is streamed in slices so a large book never rides in a single evaluateJavascript call.
// Length stays a multiple of 4 so each slice decodes independently in the WebView.
private const val CHUNK_CHARS = 256_000

// Raw bytes per read, sized so CHUNK_BYTES * 4/3 == CHUNK_CHARS exactly. A multiple of 3 so every
// chunk but the last encodes with no padding, keeping each slice independently decodable.
private const val CHUNK_BYTES = CHUNK_CHARS / 4 * 3

/**
 * Imperative handle over the reader WebView. Drives foliate via the begin/chunk/commit/command
 * protocol, buffering an open requested before the bridge signals ready.
 */
class ReaderController {
    @Volatile private var webView: WebView? = null
    private var presented = false
    private var initialLocation: ReaderEvent.Relocate? = null

    /** Set by the screen to receive parsed reader events. */
    var listener: ((ReaderEvent) -> Unit)? = null

    fun attach(webView: WebView) {
        this.webView = webView
        presented = false
        initialLocation = null
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    /** Called from the JS interface (binder thread); marshals events to the main thread. */
    fun handleMessage(view: WebView, data: String) {
        if (webView !== view) return
        val event = ReaderBridge.parseEvent(data) ?: return
        Handler(Looper.getMainLooper()).post {
            if (webView === view) {
                when (event) {
                    is ReaderEvent.Relocate -> {
                        atStart = event.atStart
                        atEnd = event.atEnd
                        curlHost?.relocated()
                    }
                    ReaderEvent.PageTurnRequested -> next()
                    ReaderEvent.PageTurnCompleted -> curlHost?.pageTurnCompleted()
                    else -> Unit
                }
                if (event is ReaderEvent.Relocate && !presented) {
                    initialLocation = event
                    return@post
                }
                if (event is ReaderEvent.Loaded) {
                    val reveal = {
                        if (webView === view) {
                            presented = true
                            listener?.invoke(event)
                            initialLocation?.let { listener?.invoke(it) }
                            initialLocation = null
                        }
                    }
                    curlHost?.present(reveal) ?: reveal()
                } else listener?.invoke(event)
            }
        }
    }

    /**
     * Streams the book into foliate. Safe to call off the main thread (it reads the file and
     * evaluates JS via WebView.post); the screen calls it only after the bridge signals ready.
     */
    fun open(params: OpenParams, context: Context) = runOpen(params, context)

    fun goTo(target: String) =
        command(
            buildJsonObject {
                    put("type", "goTo")
                    put("target", target)
                }
                .toString()
        )

    fun goToFraction(value: Double) =
        command(
            buildJsonObject {
                    put("type", "goToFraction")
                    put("value", value)
                }
                .toString()
        )

    var onVolumeKey: ((Boolean) -> Boolean)? = null
    var volumeKeyPagingEnabled: Boolean = false
    internal var curlHost: ReaderPageCurlHost? = null
    internal var pageCurlEnabled = false
    internal var pageCoverEnabled = false
    internal var atStart = false
    internal var atEnd = false
    private var lastFonts: List<ReaderFontFace>? = null
    private var lastLayout: ReaderSettings? = null
    internal var pageColor = android.graphics.Color.WHITE

    fun previousChapter() = command(buildJsonObject { put("type", "previousChapter") }.toString())

    fun nextChapter() = command(buildJsonObject { put("type", "nextChapter") }.toString())

    fun autoRead(running: Boolean, seconds: Int) =
        command(
            buildJsonObject {
                    put("type", "autoRead")
                    put("running", running)
                    put("seconds", seconds)
                }
                .toString()
        )

    fun next() = turnPage(true)

    fun prev() = turnPage(false)

    private fun turnPage(next: Boolean) {
        val host = curlHost
        if (pageCurlEnabled && host != null) host.turn(next) else turnPageDirect(next)
    }

    internal fun turnPageDirect(next: Boolean) =
        command(buildJsonObject { put("type", if (next) "next" else "prev") }.toString())

    private fun updatePageAppearance(settings: ReaderSettings) {
        val enabled = settings.turnEffect() in setOf("curl", "cover")
        val theme =
            READER_THEMES.firstOrNull { it.name == settings.themeName } ?: READER_THEMES.first()
        val color = (if (settings.isDark) theme.darkBg else theme.lightBg).toInt()
        val update = {
            if (enabled != pageCurlEnabled || color != pageColor) curlHost?.clear()
            pageCurlEnabled = enabled
            pageCoverEnabled = settings.turnEffect() == "cover"
            pageColor = color
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update()
        else Handler(Looper.getMainLooper()).post(update)
    }

    /**
     * Replace the set of highlights drawn in the page. Only CFI-anchored, resolvable ones are
     * drawn.
     */
    fun setAnnotations(items: List<BookAnnotation>) {
        val arr =
            kotlinx.serialization.json.buildJsonArray {
                for (a in items) {
                    val cfi = a.cfi ?: continue
                    if (a.positionStatus == "failed") continue
                    add(
                        buildJsonObject {
                            put("cfi", cfi)
                            put("color", a.color)
                            put("style", a.style)
                        }
                    )
                }
            }
        command(
            buildJsonObject {
                    put("type", "setAnnotations")
                    put("items", arr)
                }
                .toString()
        )
    }

    fun search(query: String) =
        command(
            buildJsonObject {
                    put("type", "search")
                    put("query", query)
                }
                .toString()
        )

    fun clearSearch() = command(buildJsonObject { put("type", "clearSearch") }.toString())

    fun clearSelection() = command(buildJsonObject { put("type", "clearSelection") }.toString())

    fun setFonts(faces: List<ReaderFontFace>) {
        if (lastFonts == faces) return
        lastFonts = faces
        curlHost?.invalidatePage()
        val cmd = buildJsonObject {
            put("type", "setFonts")
            put(
                "faces",
                ReaderBridge.json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(ReaderFontFace.serializer()),
                    faces,
                ),
            )
        }
        command(cmd.toString())
    }

    fun applyStyles(settings: ReaderSettings) {
        updatePageAppearance(settings)
        val layout =
            settings.copy(
                brightness = -1.0,
                keepScreenOn = true,
                volumeKeyPaging = false,
                showReadingStatus = true,
                autoPageIntervalSeconds = 15,
            )
        if (lastLayout == layout) return
        lastLayout = layout
        curlHost?.invalidatePage()
        val cmd = buildJsonObject {
            put("type", "applyStyles")
            put(
                "settings",
                ReaderBridge.json.encodeToJsonElement(ReaderSettings.serializer(), settings),
            )
        }
        command(cmd.toString())
    }

    // The `window.__readerCommand &&` guard inside the JS no-ops if the bridge isn't ready yet.
    private fun command(json: String) = eval(ReaderBridge.jsCommand(json))

    // Reads and encodes the file one CHUNK_BYTES slice at a time so peak memory stays bounded by
    // the chunk size instead of the whole file (a naive readBytes()+encodeToString() on a 90MB
    // EPUB peaks at ~450MB and reliably OOMs on default heap sizes).
    private fun runOpen(params: OpenParams, context: Context) {
        webView?.post {
            presented = false
            initialLocation = null
        }
        updatePageAppearance(params.settings)
        lastFonts = params.fontFaces
        val meta =
            OpenMeta(params.format, params.cfi, params.fraction, params.settings, params.fontFaces)
        val metaJson = ReaderBridge.json.encodeToString(OpenMeta.serializer(), meta)
        eval(ReaderBridge.jsBegin(metaJson))

        params.ref.openInputStream(context).use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                var filled = 0
                while (filled < CHUNK_BYTES) {
                    val n = input.read(buffer, filled, CHUNK_BYTES - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) break
                eval(ReaderBridge.jsChunk(Base64.encodeToString(buffer, 0, filled, Base64.NO_WRAP)))
                if (filled < CHUNK_BYTES) break
            }
        }
        eval(ReaderBridge.jsCommit())
    }

    private fun eval(js: String) {
        val wv = webView ?: return
        wv.post { if (webView === wv) wv.evaluateJavascript(js, null) }
    }
}

/**
 * WebView that suppresses the system Copy/Select-all toolbar. The page shows its own highlight
 * toolbar instead; the native selection handles are unaffected.
 */
private class ReaderNativeWebView(context: Context, private val controller: ReaderController) :
    WebView(context) {
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var draggingPage = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!controller.pageCurlEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = event.eventTime
                draggingPage = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (
                    !draggingPage &&
                        event.pointerCount == 1 &&
                        event.eventTime - downAt < 350 &&
                        kotlin.math.abs(event.x - downX) > slop * 2 &&
                        kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY) * 1.5f
                ) {
                    draggingPage = true
                    android.view.MotionEvent.obtain(event).also {
                        it.action = android.view.MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(it)
                        it.recycle()
                    }
                }
                if (
                    draggingPage ||
                        (event.pointerCount == 1 &&
                            event.eventTime - downAt <
                                android.view.ViewConfiguration.getLongPressTimeout())
                )
                    return true
            }
            android.view.MotionEvent.ACTION_UP ->
                if (draggingPage) {
                    draggingPage = false
                    if (kotlin.math.abs(event.x - downX) > width * .12f) {
                        if (event.x < downX) controller.next() else controller.prev()
                    }
                    return true
                }
            android.view.MotionEvent.ACTION_CANCEL -> draggingPage = false
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val volumeKey =
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeKey && controller.volumeKeyPagingEnabled && controller.onVolumeKey != null) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                controller.onVolumeKey?.invoke(
                    event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                )
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun startActionMode(
        callback: android.view.ActionMode.Callback?,
        type: Int,
    ): android.view.ActionMode? =
        super.startActionMode(callback?.let { EmptyActionModeCallback(it) }, type)
}

private class EmptyActionModeCallback(private val delegate: android.view.ActionMode.Callback) :
    android.view.ActionMode.Callback2() {
    override fun onCreateActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu,
    ): Boolean {
        delegate.onCreateActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onPrepareActionMode(
        mode: android.view.ActionMode,
        menu: android.view.Menu,
    ): Boolean {
        delegate.onPrepareActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) =
        false

    override fun onDestroyActionMode(mode: android.view.ActionMode) =
        delegate.onDestroyActionMode(mode)

    override fun onGetContentRect(
        mode: android.view.ActionMode,
        view: android.view.View,
        outRect: android.graphics.Rect,
    ) {
        if (delegate is android.view.ActionMode.Callback2)
            delegate.onGetContentRect(mode, view, outRect)
        else super.onGetContentRect(mode, view, outRect)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderWebView(
    controller: ReaderController,
    fontRepository: ReaderFontRepository,
    modifier: Modifier = Modifier,
    onRendererGone: () -> Unit,
) {
    val destroyedViews = androidx.compose.runtime.remember { mutableSetOf<WebView>() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader =
                WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .addPathHandler(
                        "/reader-fonts/",
                        WebViewAssetLoader.PathHandler { path ->
                            val input = fontRepository.openCached(path)
                            if (input == null)
                                WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    404,
                                    "Not Found",
                                    emptyMap(),
                                    null,
                                )
                            else
                                WebResourceResponse(
                                    "font/${path.substringAfterLast('.')}",
                                    null,
                                    200,
                                    "OK",
                                    mapOf(
                                        "Access-Control-Allow-Origin" to "*",
                                        "Cache-Control" to "no-store",
                                    ),
                                    input,
                                )
                        },
                    )
                    .build()

            val reader =
                ReaderNativeWebView(context, controller).apply {
                    isFocusableInTouchMode = true
                    requestFocus()
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    webViewClient =
                        object : WebViewClientCompat() {
                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                Log.e(
                                    "BookOrbitWebView",
                                    "Local reader renderer gone: crashed=${detail.didCrash()}, " +
                                        "priority=${detail.rendererPriorityAtExit()}, provider=${WebView.getCurrentWebViewPackage()?.versionName}",
                                )
                                controller.detach(view)
                                destroyedViews.add(view)
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.destroy()
                                onRendererGone()
                                return true
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? =
                                assetLoader.shouldInterceptRequest(request.url)
                        }

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun postMessage(message: String) =
                                controller.handleMessage(this@apply, message)
                        },
                        "AndroidReaderBridge",
                    )

                    controller.attach(this)
                    loadUrl("https://appassets.androidplatform.net/assets/reader/index.html")
                }
            ReaderPageCurlHost(context, reader, controller).also { controller.curlHost = it }
        },
        onRelease = { host ->
            host.clear()
            if (controller.curlHost === host) controller.curlHost = null
            val view = host.reader
            controller.detach(view)
            if (!destroyedViews.remove(view)) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        },
    )
}

/** Build OpenParams once the file and initial position are resolved. */
fun openParamsFor(
    ref: LocalRef,
    format: String,
    initial: InitialProgress,
    settings: ReaderSettings,
): OpenParams = OpenParams(ref, format, initial.cfi, initial.fraction, settings)
