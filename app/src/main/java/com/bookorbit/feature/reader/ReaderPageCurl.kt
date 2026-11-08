package com.bookorbit.feature.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Owns one bounded snapshot while the real reader moves to the destination page. */
internal class ReaderPageCurlHost(
    context: Context,
    val reader: WebView,
    private val controller: ReaderController,
) : FrameLayout(context) {
    private val page = ReaderPageCurlView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private var generation = 0L
    private var waitingForPage = false
    private var busy = false
    internal val isTurning: Boolean
        get() = busy

    private val recover = Runnable { clear() }

    init {
        addView(reader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun turn(next: Boolean) {
        if (busy || !isAttachedToWindow || width == 0 || height == 0) return
        busy = true
        val token = ++generation
        // Query the actual paginator boundaries so a first/last-page tap does not animate.
        val boundary = if (next) "atEnd" else "atStart"
        reader.evaluateJavascript(
            "document.querySelector('foliate-view')?.renderer?.$boundary === true"
        ) { blocked ->
            if (token != generation) return@evaluateJavascript
            if (blocked == "true") {
                clear()
                return@evaluateJavascript
            }
            reader.postVisualStateCallback(
                token,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        reader.postOnAnimation { if (requestId == generation) capture(token, next) }
                    }
                },
            )
        }
        handler.postDelayed(recover, 2_000)
    }

    private fun capture(token: Long, next: Boolean) {
        val activity =
            context.activity()
                ?: run {
                    clear()
                    controller.turnPageDirect(next)
                    return
                }
        val location = IntArray(2)
        reader.getLocationInWindow(location)
        val scale = min(1f, 1280f / max(reader.width, reader.height))
        val snapshot =
            try {
                Bitmap.createBitmap(
                    max(1, (reader.width * scale).toInt()),
                    max(1, (reader.height * scale).toInt()),
                    Bitmap.Config.ARGB_8888,
                )
            } catch (_: OutOfMemoryError) {
                clear()
                controller.turnPageDirect(next)
                return
            }
        val bounds =
            Rect(location[0], location[1], location[0] + reader.width, location[1] + reader.height)
        try {
            PixelCopy.request(
                activity.window,
                bounds,
                snapshot,
                { result ->
                    if (token != generation) {
                        snapshot.recycle()
                        return@request
                    }
                    if (result != PixelCopy.SUCCESS) {
                        android.util.Log.w(
                            "BookOrbitPageCurl",
                            "Snapshot unavailable, result=$result",
                        )
                        snapshot.recycle()
                        clear()
                        controller.turnPageDirect(next)
                        return@request
                    }
                    page.setPage(snapshot, next, controller.pageColor)
                    waitingForPage = true
                    controller.turnPageDirect(next)
                },
                handler,
            )
        } catch (_: IllegalArgumentException) {
            snapshot.recycle()
            clear()
            controller.turnPageDirect(next)
        }
    }

    fun relocated() {
        if (!waitingForPage) return
        waitingForPage = false
        val token = generation
        reader.postVisualStateCallback(
            token,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId != generation || !busy) return
                    handler.removeCallbacks(recover)
                    animator =
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 380
                            interpolator = FastOutSlowInInterpolator()
                            addUpdateListener { page.progress = it.animatedValue as Float }
                            addListener(
                                object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator) = clear()
                                }
                            )
                            start()
                        }
                }
            },
        )
    }

    fun clear() {
        generation++
        waitingForPage = false
        busy = false
        handler.removeCallbacks(recover)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        page.release()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) clear()
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

/** Folded paper is deformed as a small mesh, with paper-back tint and fold shadows. */
internal class ReaderPageCurlView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var next = true
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val front = Path()
    private val back = Path()
    private val target = RectF()
    private val shaderMatrix = Matrix()
    private val vertices = FloatArray((COLUMNS + 1) * (ROWS + 1) * 2)
    private val colors = IntArray((COLUMNS + 1) * (ROWS + 1))
    private val shadow =
        LinearGradient(
            0f,
            0f,
            1f,
            0f,
            intArrayOf(0x00000000, 0x40000000, 0x00000000),
            null,
            Shader.TileMode.CLAMP,
        )
    var progress = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        visibility = INVISIBLE
    }

    fun setPage(image: Bitmap, forward: Boolean, paper: Int) {
        release()
        bitmap = image
        next = forward
        // A little reversed ink shows through the back of the paper.
        backPaint.colorFilter =
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        .12f,
                        0f,
                        0f,
                        0f,
                        Color.red(paper) * .88f,
                        0f,
                        .12f,
                        0f,
                        0f,
                        Color.green(paper) * .88f,
                        0f,
                        0f,
                        .12f,
                        0f,
                        Color.blue(paper) * .88f,
                        0f,
                        0f,
                        0f,
                        1f,
                        0f,
                    )
                )
            )
        progress = 0f
        visibility = VISIBLE
    }

    override fun onDraw(canvas: Canvas) {
        val image = bitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (progress <= 0f) {
            target.set(0f, 0f, w, h)
            canvas.drawBitmap(image, null, target, imagePaint)
            return
        }
        val bend = sin(progress * PI).toFloat()
        val fold = w * (1f - progress)
        val tilt = w * .12f * bend
        val radius = max(1f, w * .10f * bend)
        fun x(value: Float) = if (next) value else w - value
        front.reset()
        front.moveTo(x(0f), 0f)
        front.lineTo(x(fold + tilt), 0f)
        front.quadTo(x(fold - radius * .22f), h * .55f, x(fold - tilt), h)
        front.lineTo(x(0f), h)
        front.close()
        val saved = canvas.save()
        canvas.clipPath(front)
        target.set(0f, 0f, w, h)
        canvas.drawBitmap(image, null, target, imagePaint)
        canvas.restoreToCount(saved)

        // Draw the soft cast shadow on the revealed destination page.
        shaderMatrix.setScale(radius * 2.8f, 1f)
        shaderMatrix.postTranslate(x(fold) - radius * 1.4f, 0f)
        shadow.setLocalMatrix(shaderMatrix)
        shadowPaint.shader = shadow
        canvas.drawRect(x(fold) - radius * 1.8f, 0f, x(fold) + radius * 1.8f, h, shadowPaint)

        for (row in 0..ROWS) {
            val y = h * row / ROWS
            val localFold = fold + tilt * (1f - 2f * row / ROWS)
            for (column in 0..COLUMNS) {
                val sourceX = w * column / COLUMNS
                val u = if (next) sourceX else w - sourceX
                val distance = max(0f, u - localFold)
                val angle = min(PI.toFloat(), distance / radius)
                val foldedX =
                    if (u <= localFold) u
                    else localFold + radius * sin(angle) - max(0f, distance - radius * PI.toFloat())
                val index = row * (COLUMNS + 1) + column
                vertices[index * 2] = x(foldedX)
                vertices[index * 2 + 1] = y + sin(angle) * tilt * (row.toFloat() / ROWS - .5f)
                val shade = (255 - 35 * sin(angle)).toInt().coerceIn(0, 255)
                colors[index] = Color.rgb(shade, shade, shade)
            }
        }
        // The mesh is clipped to the curled part, leaving the flat front untouched.
        back.reset()
        back.moveTo(x(fold + tilt), 0f)
        back.quadTo(x(fold - radius * .22f), h * .55f, x(fold - tilt), h)
        back.lineTo(x(2 * (fold - tilt) - w + radius * PI.toFloat()), h)
        back.lineTo(x(2 * (fold + tilt) - w + radius * PI.toFloat()), 0f)
        back.close()
        val backSave = canvas.save()
        canvas.clipPath(back)
        canvas.drawBitmapMesh(image, COLUMNS, ROWS, vertices, 0, colors, 0, backPaint)
        canvas.restoreToCount(backSave)
    }

    fun release() {
        visibility = INVISIBLE
        bitmap?.recycle()
        bitmap = null
        invalidate()
    }

    companion object {
        private const val COLUMNS = 40
        private const val ROWS = 8
    }
}
