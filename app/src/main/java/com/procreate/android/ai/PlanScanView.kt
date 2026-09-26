package com.procreate.android.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The visual account of what the analysis is doing.
 *
 * A tiled run is dozens of network calls over several minutes. Without something to watch, that is
 * indistinguishable from a frozen app, and the natural response is to kill it - losing the work and
 * the quota it already spent. So the scan is not decoration: it is the difference between a user
 * who waits and a user who force-quits.
 *
 * Three states, in order: the plan faded out under a grid while the sweep passes over it, then the
 * plan returning to full strength, then detected geometry drawn in as it arrives.
 */
class PlanScanView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Phase { IDLE, SCANNING, REVEALING }

    private var source: Bitmap? = null
    private var phase = Phase.IDLE
    private var sweep = 0f
    private var reveal = 0f
    private val imageMatrix = Matrix()

    /** Geometry discovered so far, in normalised 0..1 image space. */
    private val found = mutableListOf<Pair<List<Pair<Float, Float>>, Int>>()

    private var sweepAnimator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(46, 126, 87, 194)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(235, 179, 157, 219)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val borderTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(38, 126, 87, 194)
    }
    private var orbit = 0f
    private var orbitAnimator: ValueAnimator? = null
    private val shapeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setImage(bitmap: Bitmap) {
        source = bitmap
        found.clear()
        reveal = 0f
        invalidate()
    }

    fun startScanning() {
        if (phase == Phase.SCANNING) return
        phase = Phase.SCANNING
        orbitAnimator?.cancel()
        orbitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                orbit = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Called as each batch of geometry comes back, so shapes appear progressively rather than all
     * at once at the end - the user watches the plan being rebuilt.
     */
    fun addFeatures(features: List<DetectedFeature>) {
        for (feature in features) {
            val points = FeatureMerger.resolvedPoints(feature)
            if (points.size < 2) continue
            found += points to feature.feature.toolType.defaultColor
        }
        invalidate()
    }

    fun finishScanning() {
        phase = Phase.REVEALING
        sweepAnimator?.cancel()
        sweepAnimator = null
        orbitAnimator?.cancel()
        orbitAnimator = null
        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Animators outlive the view otherwise and keep invalidating a dead surface.
        sweepAnimator?.cancel()
        revealAnimator?.cancel()
        orbitAnimator?.cancel()
    }

    /**
     * Renders the detected geometry alone on white, at the source image's proportions.
     *
     * Deliberately without the plan underneath: the review asks the model to compare its
     * reconstruction against the original, and overlaying the two would let it read the original's
     * lines and credit them to its own output.
     */
    fun renderReconstruction(maxEdge: Int = 1024): Bitmap? {
        val bmp = source ?: return null
        if (found.isEmpty()) return null
        val scale = minOf(maxEdge.toFloat() / bmp.width, maxEdge.toFloat() / bmp.height, 1f)
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for ((points, color) in found) {
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = point.first * w
                val y = point.second * h
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            fill.color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
            stroke.color = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
        return out
    }

    private fun rebuildMatrix() {
        val bmp = source ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        imageMatrix.reset()
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(
            (width - bmp.width * scale) / 2f,
            (height - bmp.height * scale) / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = source ?: return
        rebuildMatrix()

        // Washed out during the scan, returning to full strength as the reveal runs.
        val alpha = when (phase) {
            Phase.IDLE -> 255
            Phase.SCANNING -> 46
            Phase.REVEALING -> (46 + (255 - 46) * reveal).toInt()
        }
        imagePaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bmp, imageMatrix, imagePaint)

        if (phase == Phase.SCANNING) {
            drawGrid(canvas)
            drawSweep(canvas)
            drawOrbitingBorder(canvas)
        }
        drawFound(canvas)
    }

    /**
     * A bright arc travelling around the image's edge.
     *
     * The sweep alone reads as a single pass that should have finished long ago; a run of dozens of
     * calls needs something that plainly keeps going. The arc is drawn on the image's own bounds,
     * not the view's, so it frames the plan rather than the empty letterboxing around it.
     */
    private fun drawOrbitingBorder(canvas: Canvas) {
        val bmp = source ?: return
        val corners = floatArrayOf(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapPoints(corners)
        val left = corners[0]; val top = corners[1]
        val right = corners[2]; val bottom = corners[3]
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(left, top, right, bottom, borderTrackPaint)

        // One position advancing around the perimeter, drawn as a short glowing run.
        val perimeter = 2f * (w + h)
        val head = (orbit * perimeter) % perimeter
        val tail = 0.18f * perimeter
        val path = Path()
        var travelled = head - tail
        if (travelled < 0f) travelled += perimeter
        val steps = 28
        for (i in 0..steps) {
            val at = (travelled + tail * i / steps) % perimeter
            val point = pointOnPerimeter(at, left, top, w, h)
            if (i == 0) path.moveTo(point.first, point.second) else path.lineTo(point.first, point.second)
        }
        borderPaint.shader = LinearGradient(
            left, top, right, bottom,
            intArrayOf(Color.argb(0, 179, 157, 219), Color.argb(255, 179, 157, 219)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, borderPaint)
    }

    private fun pointOnPerimeter(at: Float, left: Float, top: Float, w: Float, h: Float): Pair<Float, Float> = when {
        at < w -> (left + at) to top
        at < w + h -> (left + w) to (top + (at - w))
        at < 2f * w + h -> (left + w - (at - w - h)) to (top + h)
        else -> left to (top + h - (at - 2f * w - h))
    }

    private fun drawGrid(canvas: Canvas) {
        val step = (width / 14f).coerceAtLeast(24f)
        var x = 0f
        while (x <= width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y <= height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }
    }

    /** A soft band with a bright leading edge, travelling top to bottom. */
    private fun drawSweep(canvas: Canvas) {
        val bandHeight = height * 0.22f
        val centre = -bandHeight + sweep * (height + bandHeight * 2f)
        sweepPaint.shader = LinearGradient(
            0f, centre - bandHeight, 0f, centre + bandHeight * 0.25f,
            intArrayOf(
                Color.argb(0, 126, 87, 194),
                Color.argb(70, 126, 87, 194),
                Color.argb(140, 179, 157, 219)
            ),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, centre - bandHeight, width.toFloat(), centre, sweepPaint)
        canvas.drawLine(0f, centre, width.toFloat(), centre, edgePaint)
    }

    private fun drawFound(canvas: Canvas) {
        if (found.isEmpty()) return
        val mapped = FloatArray(2)
        val bmp = source ?: return
        for ((points, color) in found) {
            val path = Path()
            points.forEachIndexed { index, point ->
                mapped[0] = point.first * bmp.width
                mapped[1] = point.second * bmp.height
                imageMatrix.mapPoints(mapped)
                if (index == 0) path.moveTo(mapped[0], mapped[1]) else path.lineTo(mapped[0], mapped[1])
            }
            shapeFill.color = Color.argb(48, Color.red(color), Color.green(color), Color.blue(color))
            shapeStroke.color = Color.argb(225, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(path, shapeFill)
            canvas.drawPath(path, shapeStroke)
        }
    }
}
