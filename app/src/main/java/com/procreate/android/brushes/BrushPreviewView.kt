package com.procreate.android.brushes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.procreate.android.canvas.BrushEngine
import com.procreate.android.canvas.BrushProperties
import com.procreate.android.canvas.StrokeSmoothing
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Interactive Brush Studio scratchpad with a deterministic sample stroke.
 *
 * The real canvas stabilizes touch samples before feeding BrushEngine. The old preview bypassed
 * that stage, so changing "Stroke smoothing" appeared to do nothing here even though it changed
 * the real brush. This view now uses the same response/trail math for both the automatic sample
 * and hand-drawn preview strokes.
 */
class BrushPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    val brushEngine = BrushEngine()
    private var bitmap: Bitmap? = null
    private var properties = BrushProperties()
    private var isDrawing = false
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var smoothedPressure = 1f
    private var smoothedVelocity = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastEventTime = 0L
    private val renderSample = Runnable { renderAutomaticSample() }

    private val backgroundPaint = Paint().apply { color = Color.rgb(24, 24, 28) }
    private val lightBackgroundPaint = Paint().apply { color = Color.rgb(242, 243, 246) }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(24, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }

    init {
        brushEngine.color = Color.rgb(57, 126, 246)
        isClickable = true
        isFocusable = true
    }

    fun setProperties(properties: BrushProperties) {
        this.properties = properties.copy()
        brushEngine.properties = this.properties.copy()
        isDrawing = false
        removeCallbacks(renderSample)
        if (width > 0 && height > 0) postOnAnimation(renderSample)
    }

    /** Leaves a clean pad so the artist can test the brush by hand. */
    fun clear() {
        bitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            bitmap?.takeIf { !it.isRecycled }?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            renderAutomaticSample()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(renderSample)
        super.onDetachedFromWindow()
    }

    private fun renderAutomaticSample() {
        val bmp = bitmap ?: return
        if (bmp.width < 32 || bmp.height < 32) return
        bmp.eraseColor(Color.TRANSPARENT)

        val engine = BrushEngine().apply {
            color = Color.rgb(57, 126, 246)
            this.properties = this@BrushPreviewView.properties.copy().apply {
                // A 200px brush should still fit inside a phone's 120dp preview card.
                size = size.coerceIn(1f, bmp.height * 0.42f)
            }
        }
        val canvas = Canvas(bmp)
        val left = 14f * resources.displayMetrics.density
        val right = (bmp.width - left).coerceAtLeast(left + 1f)
        val centerY = bmp.height * 0.53f
        val amplitude = bmp.height * 0.18f
        val jitter = bmp.height * 0.035f
        val steps = 72
        // The sample has to turn the pen as well as move it, or a brush that takes its nib angle
        // from the barrel draws here exactly like one that does not - and this pad is where that
        // setting is meant to be judged.
        engine.stylusAzimuthAvailable = engine.properties.azimuthTracking > 0f
        fun barrelAt(t: Float) = (-PI.toFloat() / 4f) + t * (PI.toFloat() / 2f)
        fun tiltAt(t: Float) = sin(t * PI.toFloat()).coerceAtLeast(0f) * (PI.toFloat() / 5f)

        var sx = left
        var sy = centerY
        var sp = 0.35f
        engine.startStroke(sx, sy, sp, tiltAt(0f), barrelAt(0f))
        engine.stampDot(canvas, bmp, sx, sy, sp, tiltAt(0f), barrelAt(0f))

        for (index in 1..steps) {
            val t = index / steps.toFloat()
            val rawX = left + (right - left) * t
            // A smooth wave plus small deterministic hand jitter makes stabilization differences
            // visible without the sample changing randomly whenever a slider moves.
            val rawY = centerY + sin(t * PI.toFloat() * 3f) * amplitude +
                (if (index % 2 == 0) jitter else -jitter)
            val pressure = (0.28f + sin(t * PI.toFloat()).coerceAtLeast(0f) * 0.72f)
            val response = StrokeSmoothing.response(engine.properties.smoothing, 8L)
            sx += (rawX - sx) * response
            sy += (rawY - sy) * response
            val maxTrail = StrokeSmoothing.maxTrail(engine.properties.smoothing, engine.properties.size)
            val dx = rawX - sx
            val dy = rawY - sy
            val lag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (lag > maxTrail && lag > 0f) {
                val pull = (lag - maxTrail) / lag
                sx += dx * pull
                sy += dy * pull
            }
            sp += (pressure - sp) * 0.35f
            engine.strokeTo(canvas, bmp, sx, sy, sp, 0.7f, tiltAt(t), barrelAt(t))
        }
        engine.endStroke(canvas, bmp)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDrawing = true
                smoothedX = event.x
                smoothedY = event.y
                smoothedPressure = event.pressure.coerceIn(0.01f, 1f)
                smoothedVelocity = 0f
                lastRawX = event.x
                lastRawY = event.y
                lastEventTime = event.eventTime
                // The pad is where a brush gets judged, so it has to answer the barrel axes too -
                // otherwise the artist drags the nib-follows-barrel slider and the swatch under
                // their hand never changes.
                brushEngine.stylusAzimuthAvailable =
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                brushEngine.startStroke(
                    smoothedX, smoothedY, smoothedPressure, tiltOf(event), azimuthOf(event)
                )
                brushEngine.stampDot(
                    Canvas(bmp), bmp, smoothedX, smoothedY, smoothedPressure,
                    tiltOf(event), azimuthOf(event)
                )
            }
            MotionEvent.ACTION_MOVE -> if (isDrawing) {
                val canvas = Canvas(bmp)
                for (history in 0 until event.historySize) {
                    feedSample(
                        canvas,
                        bmp,
                        event.getHistoricalX(history),
                        event.getHistoricalY(history),
                        event.getHistoricalPressure(history),
                        event.getHistoricalEventTime(history),
                        tiltOf(event, history),
                        azimuthOf(event, history)
                    )
                }
                feedSample(
                    canvas, bmp, event.x, event.y, event.pressure, event.eventTime,
                    tiltOf(event), azimuthOf(event)
                )
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    val canvas = Canvas(bmp)
                    settleAtLift(
                        canvas, bmp, event.x, event.y, event.pressure, event.eventTime,
                        tiltOf(event), azimuthOf(event)
                    )
                    brushEngine.endStroke(canvas, bmp)
                }
                isDrawing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) brushEngine.endStroke(Canvas(bmp), bmp)
                isDrawing = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate()
        return true
    }

    private fun tiltOf(event: MotionEvent, historyIndex: Int = -1): Float {
        val raw = if (historyIndex >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, historyIndex)
        } else {
            event.getAxisValue(MotionEvent.AXIS_TILT, 0)
        }
        return if (raw.isFinite()) raw.coerceIn(0f, (Math.PI / 2).toFloat()) else 0f
    }

    private fun azimuthOf(event: MotionEvent, historyIndex: Int = -1): Float {
        val raw = if (historyIndex >= 0) {
            event.getHistoricalOrientation(0, historyIndex)
        } else {
            event.getOrientation(0)
        }
        return if (raw.isFinite()) raw else 0f
    }

    private fun feedSample(
        canvas: Canvas,
        target: Bitmap,
        rawX: Float,
        rawY: Float,
        pressure: Float,
        eventTime: Long,
        tilt: Float = 0f,
        azimuth: Float = 0f,
        forcedResponse: Float? = null
    ) {
        val dt = (eventTime - lastEventTime).coerceAtLeast(1L)
        val distance = hypot((rawX - lastRawX).toDouble(), (rawY - lastRawY).toDouble()).toFloat()
        val velocity = (distance / dt).coerceIn(0f, 6f)
        smoothedVelocity = smoothedVelocity * 0.75f + velocity * 0.25f
        lastRawX = rawX
        lastRawY = rawY
        lastEventTime = eventTime

        val response = forcedResponse ?: StrokeSmoothing.response(properties.smoothing, dt)
        smoothedX += (rawX - smoothedX) * response
        smoothedY += (rawY - smoothedY) * response
        if (forcedResponse == null) {
            val maxTrail = StrokeSmoothing.maxTrail(properties.smoothing, properties.size)
            val dx = rawX - smoothedX
            val dy = rawY - smoothedY
            val lag = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (lag > maxTrail && lag > 0f) {
                val pull = (lag - maxTrail) / lag
                smoothedX += dx * pull
                smoothedY += dy * pull
            }
        }
        smoothedPressure += (pressure.coerceIn(0.01f, 1f) - smoothedPressure) *
            (forcedResponse ?: 0.35f)
        brushEngine.strokeTo(
            canvas,
            target,
            smoothedX,
            smoothedY,
            smoothedPressure,
            smoothedVelocity,
            tilt,
            azimuth
        )
    }

    private fun settleAtLift(
        canvas: Canvas,
        target: Bitmap,
        rawX: Float,
        rawY: Float,
        pressure: Float,
        eventTime: Long,
        tilt: Float = 0f,
        azimuth: Float = 0f
    ) {
        val remaining = hypot((rawX - smoothedX).toDouble(), (rawY - smoothedY).toDouble()).toFloat()
        val steps = kotlin.math.ceil(remaining / 4f).toInt().coerceIn(1, 20)
        for (index in 1..steps) {
            feedSample(
                canvas,
                target,
                rawX,
                rawY,
                pressure,
                eventTime + index,
                tilt,
                azimuth,
                forcedResponse = 1f / (steps - index + 1f)
            )
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width * 0.5f, height.toFloat(), backgroundPaint)
        canvas.drawRect(width * 0.5f, 0f, width.toFloat(), height.toFloat(), lightBackgroundPaint)
        val step = 24f * resources.displayMetrics.density
        var x = step
        while (x < width * 0.5f) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}
