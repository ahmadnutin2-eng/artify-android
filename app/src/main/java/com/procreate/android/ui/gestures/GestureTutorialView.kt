package com.procreate.android.ui.gestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.procreate.android.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Animates one gesture at a time on a miniature canvas.
 *
 * Everything is drawn procedurally rather than played back from a video or a sprite sheet, so the
 * demonstration stays crisp at any screen density, follows the app's own colours, and costs a few
 * kilobytes instead of a few megabytes. Each step is a loop of the same length every time it
 * repeats, which is what makes a gesture readable: the eye gets to see it more than once.
 */
class GestureTutorialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var step: GestureStep = GestureStep.DRAW
        set(value) {
            field = value
            cycleStartNanos = System.nanoTime()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var cycleStartNanos = System.nanoTime()

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER }
    private val paperEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x22FFFFFF
        strokeWidth = 1f * density
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INK
    }
    private val fingerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fingerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val artPath = Path()
    private val revealPath = Path()
    private val pathMeasure = PathMeasure()
    private val cardMatrix = Matrix()
    private val cardRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val elapsedMs = (System.nanoTime() - cycleStartNanos) / 1_000_000f
        // Each loop pauses briefly at the end so the finished state is legible before it restarts.
        val t = ((elapsedMs % step.cycleMs) / step.cycleMs).coerceIn(0f, 1f)

        layoutCard()
        when (step) {
            GestureStep.DRAW -> drawStrokeDemo(canvas, t)
            GestureStep.PAN -> drawPanDemo(canvas, t)
            GestureStep.ZOOM -> drawZoomDemo(canvas, t)
            GestureStep.ROTATE -> drawRotateDemo(canvas, t)
            GestureStep.UNDO -> drawTapDemo(canvas, t, fingers = 2, undoing = true)
            GestureStep.UNDO_HOLD -> drawHoldRepeatDemo(canvas, t)
            GestureStep.REDO -> drawTapDemo(canvas, t, fingers = 3, undoing = false)
            GestureStep.BRUSH_SIZE -> drawBrushDemo(canvas, t)
            GestureStep.EYEDROPPER -> drawEyedropperDemo(canvas, t)
            GestureStep.CLEAR_LAYER -> drawClearDemo(canvas, t)
            GestureStep.TOGGLE_UI -> drawToggleUiDemo(canvas, t)
            GestureStep.LAYER_SWIPE -> drawLayerSwipeDemo(canvas, t)
        }
        postInvalidateOnAnimation()
    }

    /** The mock canvas is a 4:3 card, inset enough to leave room for fingers around its edges. */
    private fun layoutCard() {
        val maxW = width * 0.62f
        val maxH = height * 0.62f
        val w = min(maxW, maxH * 4f / 3f)
        val h = w * 3f / 4f
        cardRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    // ==================== Shared pieces ====================

    /** The card plus its sample sketch, optionally transformed and with the art partly revealed. */
    private fun drawCard(
        canvas: Canvas,
        transform: Matrix? = null,
        artProgress: Float = 1f,
        artAlpha: Int = 255
    ) {
        canvas.save()
        transform?.let { canvas.concat(it) }

        val radius = 14f * density
        canvas.drawRoundRect(cardRect, radius, radius, paperPaint)
        canvas.drawRoundRect(cardRect, radius, radius, paperEdgePaint)

        canvas.save()
        canvas.clipRect(cardRect)
        buildArtPath()
        artPaint.strokeWidth = max(2f, cardRect.width() * 0.022f)
        artPaint.alpha = artAlpha

        // Horizon first: it belongs to the scene rather than to the stroke being demonstrated, so
        // it stays put while the hills are drawn on.
        artPaint.alpha = (artAlpha * 0.35f).toInt().coerceIn(0, 255)
        canvas.drawLine(
            cardRect.left + cardRect.width() * 0.10f, cardRect.top + cardRect.height() * 0.78f,
            cardRect.right - cardRect.width() * 0.10f, cardRect.top + cardRect.height() * 0.78f,
            artPaint
        )

        artPaint.alpha = artAlpha
        if (artProgress >= 0.999f) {
            canvas.drawPath(artPath, artPaint)
        } else if (artProgress > 0f) {
            pathMeasure.setPath(artPath, false)
            revealPath.reset()
            pathMeasure.getSegment(0f, pathMeasure.length * artProgress, revealPath, true)
            canvas.drawPath(revealPath, artPaint)
        }
        artPaint.alpha = 255
        canvas.restore()
        canvas.restore()
    }

    /** A single-contour hill line sized to the card, so a partial reveal traces one clean stroke. */
    private fun buildArtPath() {
        artPath.reset()
        val l = cardRect.left
        val t = cardRect.top
        val w = cardRect.width()
        val h = cardRect.height()
        artPath.moveTo(l + w * 0.12f, t + h * 0.72f)
        artPath.cubicTo(
            l + w * 0.30f, t + h * 0.34f,
            l + w * 0.44f, t + h * 0.36f,
            l + w * 0.56f, t + h * 0.66f
        )
        artPath.cubicTo(
            l + w * 0.66f, t + h * 0.44f,
            l + w * 0.78f, t + h * 0.46f,
            l + w * 0.88f, t + h * 0.72f
        )
    }

    /**
     * A fingertip: a translucent accent disc under a bright ring. [press] (0-1) swells it, which
     * is what reads as "this finger is touching down" without any text.
     */
    private fun drawFinger(canvas: Canvas, x: Float, y: Float, press: Float = 1f, tint: Int = ACCENT) {
        val base = 15f * density
        val r = base * (0.86f + 0.24f * press)
        fingerFillPaint.color = withAlpha(tint, (70 + 90 * press).toInt())
        canvas.drawCircle(x, y, r * 1.7f, fingerFillPaint)
        fingerFillPaint.color = withAlpha(tint, (150 + 90 * press).toInt())
        canvas.drawCircle(x, y, r, fingerFillPaint)
        fingerRingPaint.color = withAlpha(Color.WHITE, (140 + 100 * press).toInt())
        canvas.drawCircle(x, y, r, fingerRingPaint)
    }

    /** An expanding ring, for the moment a tap registers. */
    private fun drawTapRipple(canvas: Canvas, x: Float, y: Float, progress: Float, tint: Int = ACCENT) {
        if (progress <= 0f || progress >= 1f) return
        val r = 16f * density + progress * 46f * density
        fingerRingPaint.color = withAlpha(tint, ((1f - progress) * 200).toInt())
        fingerRingPaint.strokeWidth = 3f * density
        canvas.drawCircle(x, y, r, fingerRingPaint)
        fingerRingPaint.strokeWidth = 2f * density
    }

    /** A small dark pill with a caption, used to name what a shortcut just did. */
    private fun drawChip(canvas: Canvas, text: String, alpha: Float) {
        if (alpha <= 0.01f) return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val cx = width / 2f
        val cy = cardRect.top - 30f * density
        val boxW = chipTextPaint.measureText(text) + 30f * density
        val boxH = 32f * density
        fingerFillPaint.color = withAlpha(ACCENT, a)
        canvas.drawRoundRect(
            RectF(cx - boxW / 2f, cy - boxH / 2f, cx + boxW / 2f, cy + boxH / 2f),
            boxH / 2f, boxH / 2f, fingerFillPaint
        )
        chipTextPaint.alpha = a
        canvas.drawText(text, cx, cy + 5f * density, chipTextPaint)
        chipTextPaint.alpha = 255
    }

    // ==================== Per-gesture demonstrations ====================

    private fun drawStrokeDemo(canvas: Canvas, t: Float) {
        // Draw for the first 75% of the loop, then hold the finished stroke so it can be read.
        val progress = easeInOut((t / 0.75f).coerceIn(0f, 1f))
        drawCard(canvas, artProgress = progress)

        buildArtPath()
        pathMeasure.setPath(artPath, false)
        val pos = FloatArray(2)
        pathMeasure.getPosTan(pathMeasure.length * progress, pos, null)
        // Once the stroke is done the finger eases off, which reads as lifting away.
        drawFinger(canvas, pos[0], pos[1], press = if (t < 0.75f) 1f else 0.4f)
    }

    private fun drawPanDemo(canvas: Canvas, t: Float) {
        val travel = cardRect.width() * 0.22f
        val shift = easeInOut(pingPong(t)) * travel
        cardMatrix.reset()
        cardMatrix.setTranslate(shift, -shift * 0.35f)
        drawCard(canvas, cardMatrix)

        val baseY = cardRect.centerY()
        val baseX = cardRect.centerX()
        drawFinger(canvas, baseX - 22f * density + shift, baseY + 16f * density - shift * 0.35f)
        drawFinger(canvas, baseX + 22f * density + shift, baseY - 16f * density - shift * 0.35f)
    }

    private fun drawZoomDemo(canvas: Canvas, t: Float) {
        val phase = easeInOut(pingPong(t))
        val scale = 1f + phase * 0.42f
        cardMatrix.reset()
        cardMatrix.setScale(scale, scale, cardRect.centerX(), cardRect.centerY())
        drawCard(canvas, cardMatrix)

        val spread = (34f + phase * 68f) * density
        val cx = cardRect.centerX()
        val cy = cardRect.centerY()
        drawFinger(canvas, cx - spread, cy + spread * 0.45f)
        drawFinger(canvas, cx + spread, cy - spread * 0.45f)
    }

    private fun drawRotateDemo(canvas: Canvas, t: Float) {
        val angle = easeInOut(pingPong(t)) * 38f
        cardMatrix.reset()
        cardMatrix.setRotate(angle, cardRect.centerX(), cardRect.centerY())
        drawCard(canvas, cardMatrix)

        val radius = cardRect.width() * 0.30f
        val cx = cardRect.centerX()
        val cy = cardRect.centerY()
        val rad = Math.toRadians(angle.toDouble())
        drawFinger(canvas, cx + (cos(rad) * radius).toFloat(), cy + (sin(rad) * radius).toFloat())
        drawFinger(canvas, cx - (cos(rad) * radius).toFloat(), cy - (sin(rad) * radius).toFloat())
    }

    private fun drawTapDemo(canvas: Canvas, t: Float, fingers: Int, undoing: Boolean) {
        // Two taps per loop, so the "tap" reads as a tap rather than a hold.
        val tapPhase = (t * 2f) % 1f
        val press = if (tapPhase < 0.18f) tapPhase / 0.18f else (1f - (tapPhase - 0.18f) / 0.2f).coerceAtLeast(0f)
        // The sketch retreats on undo and comes back on redo, which is the whole point of the pair.
        val art = if (undoing) 1f - 0.55f * pingPong(t) else 0.45f + 0.55f * pingPong(t)
        drawCard(canvas, artProgress = art)

        val cy = cardRect.centerY()
        val spacing = 40f * density
        val startX = cardRect.centerX() - spacing * (fingers - 1) / 2f
        for (i in 0 until fingers) {
            val x = startX + spacing * i
            drawFinger(canvas, x, cy, press)
            drawTapRipple(canvas, x, cy, ((tapPhase - 0.18f) / 0.5f).coerceIn(0f, 1f))
        }
        drawChip(
            canvas,
            context.getString(if (undoing) R.string.undo else R.string.redo),
            if (tapPhase in 0.18f..0.7f) 1f else 0f
        )
    }

    /**
     * Two fingers that stay down while the drawing retreats step by step.
     *
     * The point being taught is the difference from the plain tap: the fingers never lift, and the
     * art keeps going back. So they are drawn pressed for the whole loop, and the sketch is undone
     * in discrete jumps rather than smoothly - a continuous fade would read as an opacity slider.
     */
    private fun drawHoldRepeatDemo(canvas: Canvas, t: Float) {
        val steps = 5
        // A short pause at full art, then the staircase, so the start of each loop is legible.
        val runT = ((t - 0.18f) / 0.82f).coerceIn(0f, 1f)
        val stepIndex = (runT * steps).toInt().coerceIn(0, steps)
        val art = 1f - stepIndex.toFloat() / steps

        drawCard(canvas, artProgress = art)

        val cy = cardRect.centerY()
        val spacing = 40f * density
        for (i in 0 until 2) {
            val x = cardRect.centerX() - spacing / 2f + spacing * i
            // Held down throughout - pressed the whole time, never released.
            drawFinger(canvas, x, cy, 1f)
            // A ripple on each step, marking that another undo just fired.
            val withinStep = (runT * steps) % 1f
            if (runT > 0f) drawTapRipple(canvas, x, cy, withinStep)
        }
        drawChip(canvas, context.getString(R.string.undo), if (runT > 0f) 1f else 0f)
    }

    /**
     * A layer row being swiped sideways, with the row following the finger and springing back.
     * Drawn as a small stack of rows rather than the canvas card, because this gesture lives in
     * the layers panel and showing the canvas would point at the wrong place.
     */
    private fun drawLayerSwipeDemo(canvas: Canvas, t: Float) {
        val rowH = cardRect.height() * 0.24f
        val rowW = cardRect.width() * 0.78f
        val left = cardRect.centerX() - rowW / 2f
        val top = cardRect.centerY() - rowH * 1.6f
        val radius = 10f * density

        // Out and back, so both the travel and the spring-back are shown in one loop.
        val swipe = pingPong(t)
        val offset = easeInOut(swipe) * rowW * 0.28f
        val targetRow = 1

        for (i in 0 until 3) {
            val y = top + rowH * 1.15f * i
            val dx = if (i == targetRow) offset else 0f
            fingerFillPaint.color = withAlpha(if (i == targetRow) ACCENT else TOOLBAR, if (i == targetRow) 90 else 60)
            canvas.drawRoundRect(
                left + dx, y, left + rowW + dx, y + rowH, radius, radius, fingerFillPaint
            )
            fingerRingPaint.color = withAlpha(if (i == targetRow) ACCENT else TOOLBAR, 180)
            canvas.drawRoundRect(
                left + dx, y, left + rowW + dx, y + rowH, radius, radius, fingerRingPaint
            )
        }

        val rowCy = top + rowH * 1.15f * targetRow + rowH / 2f
        drawFinger(canvas, left + rowW * 0.5f + offset, rowCy, 1f)
        drawChip(canvas, context.getString(R.string.layer_alpha_lock), swipe)
    }

    private fun drawBrushDemo(canvas: Canvas, t: Float) {
        drawCard(canvas)
        // First third: two fingers rest in place. Then they drag right and the brush swells.
        val holding = t < 0.30f
        val dragT = ((t - 0.30f) / 0.55f).coerceIn(0f, 1f)
        val drag = easeInOut(dragT) * cardRect.width() * 0.30f

        val cx = cardRect.centerX()
        val cy = cardRect.centerY()
        val brushRadius = (10f + 46f * easeInOut(dragT)) * density
        accentPaint.color = withAlpha(ACCENT, 90)
        canvas.drawCircle(cx, cy, brushRadius, accentPaint)
        fingerRingPaint.color = withAlpha(ACCENT, 220)
        canvas.drawCircle(cx, cy, brushRadius, fingerRingPaint)

        val pulse = if (holding) 0.6f + 0.4f * sin(t / 0.30f * Math.PI * 2).toFloat() else 1f
        drawFinger(canvas, cx - 30f * density + drag, cy + 34f * density, pulse)
        drawFinger(canvas, cx + 30f * density + drag, cy + 34f * density, pulse)
    }

    private fun drawEyedropperDemo(canvas: Canvas, t: Float) {
        drawCard(canvas)

        // A colour patch on the card is what the finger is resting on.
        val patchX = cardRect.left + cardRect.width() * 0.66f
        val patchY = cardRect.top + cardRect.height() * 0.40f
        accentPaint.color = SAMPLE_COLOR
        canvas.drawCircle(patchX, patchY, cardRect.width() * 0.10f, accentPaint)

        val hold = (t / 0.45f).coerceIn(0f, 1f)
        drawFinger(canvas, patchX, patchY, 0.6f + 0.4f * hold)
        // The ring closes as the hold completes, then the loupe rises with the sampled colour.
        fingerRingPaint.color = withAlpha(Color.WHITE, 200)
        fingerRingPaint.strokeWidth = 3f * density
        val sweep = 360f * hold
        val r = 30f * density
        canvas.drawArc(
            RectF(patchX - r, patchY - r, patchX + r, patchY + r),
            -90f, sweep, false, fingerRingPaint
        )
        fingerRingPaint.strokeWidth = 2f * density

        if (t > 0.45f) {
            val rise = easeInOut(((t - 0.45f) / 0.3f).coerceIn(0f, 1f))
            val loupeY = patchY - (46f + 26f * rise) * density
            val loupeR = 26f * density
            accentPaint.color = SAMPLE_COLOR
            canvas.drawCircle(patchX, loupeY, loupeR, accentPaint)
            fingerRingPaint.color = withAlpha(Color.WHITE, (rise * 255).toInt())
            fingerRingPaint.strokeWidth = 3f * density
            canvas.drawCircle(patchX, loupeY, loupeR, fingerRingPaint)
            fingerRingPaint.strokeWidth = 2f * density
        }
    }

    private fun drawClearDemo(canvas: Canvas, t: Float) {
        val swipe = easeInOut((t / 0.6f).coerceIn(0f, 1f))
        drawCard(canvas, artAlpha = ((1f - swipe) * 255).toInt().coerceIn(0, 255))

        val cy = cardRect.top + cardRect.height() * 0.22f + swipe * cardRect.height() * 0.55f
        val spacing = 38f * density
        val startX = cardRect.centerX() - spacing
        for (i in 0 until 3) {
            drawFinger(canvas, startX + spacing * i, cy)
        }
    }

    private fun drawToggleUiDemo(canvas: Canvas, t: Float) {
        drawCard(canvas)

        // Mock toolbars fade out and back, which is exactly what the real gesture does.
        val visible = 1f - pingPong(t)
        val alpha = (visible * 220).toInt().coerceIn(0, 255)
        fingerFillPaint.color = withAlpha(TOOLBAR, alpha)
        val barH = 20f * density
        val barW = cardRect.width() * 0.30f
        val inset = 10f * density
        canvas.drawRoundRect(
            RectF(cardRect.left + inset, cardRect.top + inset, cardRect.left + inset + barW, cardRect.top + inset + barH),
            barH / 2f, barH / 2f, fingerFillPaint
        )
        canvas.drawRoundRect(
            RectF(cardRect.right - inset - barW, cardRect.top + inset, cardRect.right - inset, cardRect.top + inset + barH),
            barH / 2f, barH / 2f, fingerFillPaint
        )

        val tapPhase = (t * 2f) % 1f
        val press = if (tapPhase < 0.18f) tapPhase / 0.18f else (1f - (tapPhase - 0.18f) / 0.2f).coerceAtLeast(0f)
        val cy = cardRect.centerY() + cardRect.height() * 0.22f
        val spacing = 34f * density
        val startX = cardRect.centerX() - spacing * 1.5f
        for (i in 0 until 4) {
            drawFinger(canvas, startX + spacing * i, cy, press)
        }
    }

    // ==================== Timing helpers ====================

    /** 0 -> 1 -> 0 across the loop, for gestures that go out and come back. */
    private fun pingPong(t: Float): Float = if (t < 0.5f) t * 2f else (1f - t) * 2f

    private fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - Math.pow((-2f * x + 2f).toDouble(), 3.0).toFloat() / 2f
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val PAPER = 0xFFF6F6F9.toInt()
        const val INK = 0xFF2B2B31.toInt()
        const val ACCENT = 0xFF7E57C2.toInt()
        const val TOOLBAR = 0xFF3A3A42.toInt()
        const val SAMPLE_COLOR = 0xFFE0654F.toInt()
    }
}
