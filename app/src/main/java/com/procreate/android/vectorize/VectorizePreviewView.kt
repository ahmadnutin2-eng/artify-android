package com.procreate.android.vectorize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Shows the source image with the traced paths on top, each one individually selectable.
 *
 * Review is the point of this feature: the user decides what enters the drawing, one path at a
 * time, rather than the app importing everything it happened to find. Accepted paths are drawn in
 * the accent colour, rejected ones dim out, so the decision state is visible at a glance.
 */
class VectorizePreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var source: Bitmap? = null
    private var paths: List<TracedPath> = emptyList()
    private val accepted = mutableSetOf<Int>()
    private val imageMatrix = Matrix()

    /** Raised whenever a path is toggled, so the host can keep its counter in sync. */
    var onSelectionChanged: ((acceptedCount: Int, total: Int) -> Unit)? = null

    private val acceptedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#7E57C2")
    }
    private val rejectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#66FFFFFF")
    }
    private val dimPaint = Paint().apply { alpha = 120 }

    fun setContent(bitmap: Bitmap, result: VectorizationResult) {
        source = bitmap
        paths = result.paths
        accepted.clear()
        // Pre-accepting the confident paths means the common case is "glance, then confirm"
        // instead of tapping every single path before anything can happen.
        paths.forEachIndexed { index, path -> if (path.confidence >= 0.5f) accepted += index }
        notifySelection()
        requestLayout()
        invalidate()
    }

    fun acceptedPaths(): List<TracedPath> = paths.filterIndexed { index, _ -> index in accepted }

    fun selectAll() {
        accepted.clear()
        accepted.addAll(paths.indices)
        notifySelection()
        invalidate()
    }

    fun selectNone() {
        accepted.clear()
        notifySelection()
        invalidate()
    }

    private fun notifySelection() = onSelectionChanged?.invoke(accepted.size, paths.size)

    /** Fits the source image inside the view while preserving aspect ratio; the same matrix maps
     * traced points, so the overlay can never drift out of register with the image under it. */
    private fun rebuildMatrix() {
        val bmp = source ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        imageMatrix.reset()
        imageMatrix.setScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = source ?: return
        rebuildMatrix()
        canvas.drawBitmap(bmp, imageMatrix, dimPaint)

        val mapped = FloatArray(2)
        paths.forEachIndexed { index, traced ->
            val path = Path()
            traced.points.forEachIndexed { i, point ->
                mapped[0] = point.x; mapped[1] = point.y
                imageMatrix.mapPoints(mapped)
                if (i == 0) path.moveTo(mapped[0], mapped[1]) else path.lineTo(mapped[0], mapped[1])
            }
            if (traced.closed) path.close()
            canvas.drawPath(path, if (index in accepted) acceptedPaint else rejectedPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val hit = nearestPathIndex(event.x, event.y) ?: return true
        if (hit in accepted) accepted -= hit else accepted += hit
        notifySelection()
        invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun nearestPathIndex(screenX: Float, screenY: Float): Int? {
        val tolerance = 28f
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        val mapped = FloatArray(2)
        paths.forEachIndexed { index, traced ->
            for (point in traced.points) {
                mapped[0] = point.x; mapped[1] = point.y
                imageMatrix.mapPoints(mapped)
                val d = kotlin.math.hypot((mapped[0] - screenX).toDouble(), (mapped[1] - screenY).toDouble()).toFloat()
                if (d < bestDistance) { bestDistance = d; best = index }
            }
        }
        return if (bestDistance <= tolerance) best else null
    }
}
