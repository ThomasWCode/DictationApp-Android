package com.thomaswcode.dictationapp.platform.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The floating bubble, drawn by hand so the overlay needs no Compose runtime or lifecycle. Modes:
 * idle (dark circle, white mic), offline (grey), recording (red with live level bars), hands-free (a pill:
 * cancel, level bars, check mark), processing (accent blue, breathing). Colours follow the Windows app.
 */
class BubbleView(context: Context) : View(context) {
    enum class Mode { Idle, Offline, Recording, HandsFree, Processing }

    /** Which part of the hands-free pill a touch landed on. */
    enum class Region { Cancel, Stop }

    var mode: Mode = Mode.Idle
        set(value) {
            if (field == value) return
            val resize = (field == Mode.HandsFree) != (value == Mode.HandsFree)
            field = value
            if (resize) requestLayout()
            invalidate()
        }

    /** Microphone peak (0..1) of the latest frame. */
    var level: Float = 0f

    /** In hold mode the finger has slid far enough away that releasing will discard. */
    var cancelArmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Bubble diameter in pixels (the visual circle, without the padding around it). */
    var diameter: Float = 0f
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    val pad: Float get() = diameter * PAD_FRACTION
    val pillWidth: Float get() = diameter * PILL_FACTOR
    val contentWidth: Float get() = if (mode == Mode.HandsFree) pillWidth else diameter

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bars = FloatArray(BAR_COUNT) { MIN_BAR }
    private val rect = RectF()
    private val path = Path()
    private var ringColor = 0
    private var ringUntil = 0L

    fun ring(color: Int, durationMs: Long = 1_800) {
        ringColor = color
        ringUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
    }

    fun shake() {
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, 10f, -10f, 7f, -7f, 3f, 0f).setDuration(360).start()
    }

    fun regionAt(x: Float): Region = if (x < pad + diameter) Region.Cancel else Region.Stop

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((contentWidth + 2 * pad).toInt(), (diameter + 2 * pad).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The bubble lives on the screen edge, inside the back-gesture zone: without this, dragging it sideways
        // also fires Back and closes the keyboard.
        if (changed) systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
    }

    override fun onDraw(canvas: Canvas) {
        if (diameter <= 0f) return
        val d = diameter
        val top = pad
        val cy = top + d / 2
        val now = SystemClock.uptimeMillis()
        var animating = false
        fill.setShadowLayer(d * 0.09f, 0f, d * 0.03f, SHADOW)

        when (mode) {
            Mode.Idle, Mode.Offline -> {
                fill.color = if (mode == Mode.Offline) GREY else DARK
                canvas.drawCircle(pad + d / 2, cy, d / 2, fill)
                // A faint light rim keeps the dark bubble visible over dark apps.
                stroke.color = RIM
                stroke.strokeWidth = d * 0.035f
                canvas.drawCircle(pad + d / 2, cy, d / 2 - stroke.strokeWidth / 2, stroke)
                drawMic(canvas, pad + d / 2, cy, d, if (mode == Mode.Offline) 0x99FFFFFF.toInt() else WHITE)
            }
            Mode.Recording -> {
                fill.color = if (cancelArmed) GREY else RED
                canvas.drawCircle(pad + d / 2, cy, d / 2, fill)
                if (cancelArmed) drawCross(canvas, pad + d / 2, cy, d * 0.36f, WHITE) else drawBars(canvas, pad + d / 2, cy, d * 0.56f, d * 0.5f, now)
                animating = true
            }
            Mode.HandsFree -> {
                val w = pillWidth
                fill.color = DARK
                rect.set(pad, top, pad + w, top + d)
                canvas.drawRoundRect(rect, d / 2, d / 2, fill)
                fill.clearShadowLayer()
                // Cancel on the left, live level in the middle, check mark on the right.
                val inner = d * 0.78f
                fill.color = 0x33FFFFFF
                canvas.drawCircle(pad + d / 2, cy, inner / 2, fill)
                drawCross(canvas, pad + d / 2, cy, inner * 0.36f, 0xDDFFFFFF.toInt())
                fill.color = RED
                val midLeft = pad + d
                val midRight = pad + w - d
                rect.set(midLeft + d * 0.06f, top + d * 0.11f, midRight - d * 0.06f, top + d * 0.89f)
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
                drawBars(canvas, (midLeft + midRight) / 2, cy, rect.width() * 0.62f, rect.height() * 0.6f, now)
                fill.color = BLUE
                canvas.drawCircle(pad + w - d / 2, cy, inner / 2, fill)
                drawCheck(canvas, pad + w - d / 2, cy, inner * 0.42f, WHITE)
                animating = true
            }
            Mode.Processing -> {
                val phase = (now % BREATH_MS) / BREATH_MS.toFloat()
                val breath = 0.55f + 0.45f * (0.5f + 0.5f * sin(2 * PI * phase).toFloat())
                fill.color = withAlpha(BLUE, breath)
                canvas.drawCircle(pad + d / 2, cy, d / 2, fill)
                drawMic(canvas, pad + d / 2, cy, d, WHITE)
                animating = true
            }
        }

        fill.clearShadowLayer()
        if (now < ringUntil) {
            val remaining = (ringUntil - now) / 1_800f
            stroke.color = withAlpha(ringColor, min(1f, remaining * 1.5f))
            stroke.strokeWidth = d * 0.08f
            if (mode == Mode.HandsFree) {
                rect.set(pad, top, pad + pillWidth, top + d)
                canvas.drawRoundRect(rect, d / 2, d / 2, stroke)
            } else {
                canvas.drawCircle(pad + d / 2, cy, d / 2 - stroke.strokeWidth / 2, stroke)
            }

            animating = true
        }

        if (animating) postInvalidateOnAnimation()
    }

    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, width: Float, maxHeight: Float, now: Long) {
        // Speech peaks sit around 0.05-0.5; a square root spreads them over the bar height.
        val shown = if (level <= 0f) 0f else min(1f, sqrt(level) * 1.5f)
        val t = now / 1000f
        val step = width / BAR_COUNT
        val barWidth = step * 0.55f
        glyph.color = WHITE
        for (i in 0 until BAR_COUNT) {
            val wobble = 0.78f + 0.22f * sin(t * 9f + i * 1.7f)
            val target = max(MIN_BAR, shown * BAR_WEIGHTS[i] * wobble)
            bars[i] += (target - bars[i]) * 0.35f
            val h = max(barWidth, bars[i] * maxHeight)
            val x = cx - width / 2 + step * i + (step - barWidth) / 2
            rect.set(x, cy - h / 2, x + barWidth, cy + h / 2)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, glyph)
        }
    }

    /** Microphone glyph: capsule, cradle arc, stem and base (the shape of the app icon). */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, d: Float, color: Int) {
        glyph.color = color
        stroke.color = color
        stroke.strokeWidth = d * 0.055f
        val cw = d * 0.2f
        val ch = d * 0.34f
        val capsuleTop = cy - d * 0.27f
        rect.set(cx - cw / 2, capsuleTop, cx + cw / 2, capsuleTop + ch)
        canvas.drawRoundRect(rect, cw / 2, cw / 2, glyph)
        val arcR = d * 0.2f
        val arcCy = capsuleTop + ch - cw / 2
        rect.set(cx - arcR, arcCy - arcR, cx + arcR, arcCy + arcR)
        canvas.drawArc(rect, 0f, 180f, false, stroke)
        canvas.drawLine(cx, arcCy + arcR, cx, cy + d * 0.26f, stroke)
        canvas.drawLine(cx - d * 0.11f, cy + d * 0.26f, cx + d * 0.11f, cy + d * 0.26f, stroke)
    }

    private fun drawCross(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = size * 0.26f
        val h = size / 2
        canvas.drawLine(cx - h, cy - h, cx + h, cy + h, stroke)
        canvas.drawLine(cx - h, cy + h, cx + h, cy - h, stroke)
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = size * 0.22f
        path.reset()
        path.moveTo(cx - size * 0.5f, cy + size * 0.02f)
        path.lineTo(cx - size * 0.14f, cy + size * 0.36f)
        path.lineTo(cx + size * 0.52f, cy - size * 0.32f)
        canvas.drawPath(path, stroke)
    }

    companion object {
        const val DARK = 0xF0161A20.toInt()
        const val RED = 0xFFE5484D.toInt()
        const val BLUE = 0xFF1F6FEB.toInt()
        const val GREY = 0xFF6B7280.toInt()
        const val AMBER = 0xFFF5A524.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        private const val SHADOW = 0x55000000
        private const val RIM = 0x40FFFFFF
        private const val PAD_FRACTION = 0.2f
        private const val PILL_FACTOR = 3.1f
        private const val BAR_COUNT = 5
        private const val MIN_BAR = 0.14f
        private const val BREATH_MS = 1_300L
        private val BAR_WEIGHTS = floatArrayOf(0.55f, 0.82f, 1f, 0.82f, 0.55f)

        fun withAlpha(color: Int, alpha: Float): Int {
            val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
            return (a shl 24) or (color and 0x00FFFFFF)
        }
    }
}

/** The drop target shown at the bottom of the screen while the bubble is dragged: drop there to hide it. */
class SnoozeTargetView(context: Context) : View(context) {
    var diameter: Float = 0f
        set(value) {
            field = value
            requestLayout()
        }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animate().scaleX(if (value) 1.18f else 1f).scaleY(if (value) 1.18f else 1f).setDuration(120).start()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = BubbleView.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BubbleView.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((diameter * 2.4f).toInt(), (diameter * 1.9f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = diameter * 0.62f
        fill.color = if (active) BubbleView.RED else 0xCC161A20.toInt()
        canvas.drawCircle(cx, cy, diameter / 2, fill)
        stroke.strokeWidth = diameter * 0.07f
        val h = diameter * 0.16f
        canvas.drawLine(cx - h, cy - h, cx + h, cy + h, stroke)
        canvas.drawLine(cx - h, cy + h, cx + h, cy - h, stroke)
        text.textSize = diameter * 0.26f
        canvas.drawText("Hide 10 min", cx, cy + diameter * 0.5f + text.textSize * 1.3f, text)
    }
}
