package com.bookorbit.feature.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Caches only the WebView body, outside the input path and independent of native controls. */
internal class ReaderPageCurlHost(
    context: Context,
    val reader: WebView,
    private val controller: ReaderController,
) : FrameLayout(context) {
    private val page = ReaderPageCurlView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private var generation = 0L
    private var ready = false
    private var destinationReady = false
    private var busy = false
    private var cached: Bitmap? = null
    internal val isTurning: Boolean
        get() = busy

    private val recover = Runnable { finish() }
    private val warm = Runnable {
        if (ready && !busy && controller.pageCurlEnabled && isAttachedToWindow) {
            cached?.recycle()
            cached = captureBody()
            cached?.prepareToDraw()
        }
    }

    init {
        reader.alpha = 0f
        addView(reader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun present(onPresented: () -> Unit) {
        reader.postVisualStateCallback(
            generation,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!isAttachedToWindow) return
                    reader.alpha = 1f
                    ready = true
                    val presented = Runnable {
                        if (ready && isAttachedToWindow) {
                            onPresented()
                            scheduleWarmup()
                        }
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 29 && reader.isHardwareAccelerated) {
                        reader.viewTreeObserver.registerFrameCommitCallback {
                            reader.postOnAnimation(presented)
                        }
                        reader.invalidate()
                    } else {
                        reader.postOnAnimation { reader.postOnAnimation(presented) }
                    }
                }
            },
        )
    }

    fun invalidatePage() {
        if (busy) finish()
        cached?.recycle()
        cached = null
        scheduleWarmup()
    }

    private fun scheduleWarmup() {
        handler.removeCallbacks(warm)
        if (ready && controller.pageCurlEnabled) handler.postDelayed(warm, 100)
    }

    private fun captureBody(): Bitmap? {
        if (reader.width == 0 || reader.height == 0) return null
        val scale = min(1f, 1280f / max(reader.width, reader.height))
        val snapshot =
            try {
                Bitmap.createBitmap(
                    max(1, (reader.width * scale).toInt()),
                    max(1, (reader.height * scale).toInt()),
                    Bitmap.Config.ARGB_8888,
                )
            } catch (_: OutOfMemoryError) {
                return null
            }
        return try {
            Canvas(snapshot).also {
                it.scale(
                    snapshot.width.toFloat() / reader.width,
                    snapshot.height.toFloat() / reader.height,
                )
                reader.draw(it)
            }
            snapshot
        } catch (_: IllegalArgumentException) {
            snapshot.recycle()
            null
        }
    }

    fun turn(next: Boolean) {
        if (busy || !ready || !isAttachedToWindow) return
        if (if (next) controller.atEnd else controller.atStart) return
        handler.removeCallbacks(warm)
        val snapshot = cached
        cached = null
        busy = true
        destinationReady = false
        generation++
        if (snapshot == null || !ValueAnimator.areAnimatorsEnabled()) {
            snapshot?.recycle()
            controller.turnPageDirect(next)
            handler.postDelayed(recover, 2_000)
            return
        }
        page.setPage(snapshot, next, controller.pageColor, controller.pageCoverEnabled)
        // The overlay is already in place when the engine moves. Native chrome is never captured.
        animate(0f, 1f, 320)
        controller.turnPageDirect(next)
        handler.postDelayed(recover, 2_000)
    }

    private fun animate(from: Float, to: Float, durationMs: Long) {
        animator =
            ValueAnimator.ofFloat(from, to).apply {
                duration = durationMs
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    val value = it.animatedValue as Float
                    page.progress = if (destinationReady) value else min(value, .92f)
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (destinationReady) finish()
                        }
                    }
                )
                start()
            }
    }

    fun relocated() {
        if (!busy) invalidatePage()
    }

    fun pageTurnCompleted() {
        if (!busy) return
        val token = generation
        reader.postVisualStateCallback(
            token,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId != generation || !busy) return
                    destinationReady = true
                    if (page.visibility != View.VISIBLE) finish()
                    else if (animator?.isRunning != true) animate(page.progress, 1f, 80)
                }
            },
        )
    }

    private fun finish() {
        generation++
        busy = false
        handler.removeCallbacks(recover)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        page.release()
        scheduleWarmup()
    }

    fun clear() {
        finish()
        handler.removeCallbacks(warm)
        cached?.recycle()
        cached = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            clear()
            scheduleWarmup()
        }
    }

    override fun onDetachedFromWindow() {
        ready = false
        clear()
        super.onDetachedFromWindow()
    }
}

/** Folded paper is deformed as a small mesh, with paper-back tint and fold shadows. */
internal class ReaderPageCurlView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var next = true
    private var cover = false
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

    fun setPage(image: Bitmap, forward: Boolean, paper: Int, covering: Boolean = false) {
        cover = covering
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
        if (cover) {
            val offset = w * progress * if (next) -1f else 1f
            target.set(offset, 0f, offset + w, h)
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
