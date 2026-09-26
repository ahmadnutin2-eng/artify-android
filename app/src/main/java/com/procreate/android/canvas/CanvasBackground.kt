package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Paints the paper a canvas starts on: plain white, dotted, ruled, or isometric.
 *
 * The pattern is painted *into* the background layer's bitmap rather than drawn as an overlay in
 * [DrawingView.onDraw]. An overlay would sit underneath the opaque white background layer and
 * never be seen at all; baking it in also means dotted paper behaves like paper - it exports and
 * flattens with the artwork, and anyone who wants it gone just hides or deletes that one layer.
 *
 * Repainting is non-destructive to artwork because it only ever targets [Layer.isBackground].
 */
object CanvasBackground {

    const val PAPER_COLOR = Color.WHITE
    private const val GRID_INK = 0xFFD6DBE2.toInt()
    private const val DOT_INK = 0xFFC2C9D4.toInt()
    private val tiledPaintCache = LinkedHashMap<String, Paint>()

    /**
     * Spacing between grid units, in canvas pixels. Derived from the canvas' short side so a
     * phone-sized canvas and a 4000px print canvas both end up with a sensibly dense grid instead
     * of one that's either invisible or overwhelming.
     */
    fun pitchFor(width: Int, height: Int): Int = (min(width, height) / 26).coerceIn(22, 110)

    /**
     * Paint [style] across the whole of [bitmap]. [pitch] is passed in rather than derived here so
     * that a canvas which grows keeps the grid it started with - recomputing it from the new size
     * would rescale the pattern out from under everything already drawn on it.
     */
    fun paint(
        bitmap: Bitmap,
        style: CanvasBackgroundStyle,
        pitch: Int = pitchFor(bitmap.width, bitmap.height),
        paperColor: Int = PAPER_COLOR
    ) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(paperColor, android.graphics.PorterDuff.Mode.SRC)
        if (style == CanvasBackgroundStyle.BLANK) return

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        when (style) {
            CanvasBackgroundStyle.BLANK -> Unit

            // Dots and rules tile perfectly, so they're painted through a REPEAT shader: one
            // drawRect covers the whole canvas instead of the tens of thousands of individual
            // draw calls a 4000px canvas would otherwise need.
            CanvasBackgroundStyle.DOT_GRID -> {
                val dotRadius = (pitch * 0.055f).coerceIn(1.1f, 3.2f)
                val tile = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
                Canvas(tile).drawCircle(
                    pitch / 2f, pitch / 2f, dotRadius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = patternInk(paperColor, dots = true) }
                )
                canvas.drawRect(0f, 0f, w, h, shaderPaint(tile))
            }

            CanvasBackgroundStyle.LINE_GRID -> {
                val stroke = (pitch * 0.022f).coerceIn(0.9f, 2f)
                val tile = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
                val tileCanvas = Canvas(tile)
                val linePaint = Paint().apply { color = patternInk(paperColor) }
                tileCanvas.drawRect(0f, 0f, pitch.toFloat(), stroke, linePaint)
                tileCanvas.drawRect(0f, 0f, stroke, pitch.toFloat(), linePaint)
                canvas.drawRect(0f, 0f, w, h, shaderPaint(tile))
            }

            // A 30-degree triangular grid doesn't tile on an integer pixel boundary without
            // visible seams, so it's ruled directly - a few hundred lines, drawn once.
            CanvasBackgroundStyle.ISOMETRIC -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = patternInk(paperColor)
                    strokeWidth = (pitch * 0.02f).coerceIn(0.8f, 1.8f)
                    this.style = Paint.Style.STROKE
                }
                // Verticals, plus both 30-degree diagonals. The diagonals rise by 1 for every
                // sqrt(3) across, which is what makes the cells equilateral.
                val slope = 1f / sqrt(3f)
                var x = 0f
                while (x <= w) {
                    canvas.drawLine(x, 0f, x, h, paint)
                    x += pitch
                }
                val diagonalStep = pitch * 2f * slope
                var c = -h * slope
                while (c <= w + h * slope) {
                    canvas.drawLine(c, 0f, c + h * slope, h, paint)
                    canvas.drawLine(c, h, c + h * slope, 0f, paint)
                    c += diagonalStep
                }
            }
        }
    }

    /**
     * Draws the visible part of open paper behind its currently allocated bitmap.
     *
     * Open canvases grow their real layer bitmaps only when paint reaches an edge, which keeps
     * memory finite. The workspace used to remain dark outside that allocation and kept a bright
     * border around it, so it still *felt* like a fixed sheet. Rendering the same paper pattern in
     * the visible world-space region removes that artificial wall while preserving finite storage.
     */
    fun drawOpenPaper(
        canvas: Canvas,
        style: CanvasBackgroundStyle,
        visible: RectF,
        pitch: Int,
        paperColor: Int = PAPER_COLOR
    ) {
        if (visible.isEmpty) return
        canvas.drawRect(visible, Paint().apply { color = paperColor })
        when (style) {
            CanvasBackgroundStyle.BLANK -> Unit
            CanvasBackgroundStyle.DOT_GRID,
            CanvasBackgroundStyle.LINE_GRID -> canvas.drawRect(
                visible,
                tiledPaint(style, pitch, paperColor)
            )
            CanvasBackgroundStyle.ISOMETRIC -> drawIsometric(canvas, visible, pitch, paperColor)
        }
    }

    private fun tiledPaint(style: CanvasBackgroundStyle, pitch: Int, paperColor: Int): Paint {
        val key = "${style.name}:$pitch:$paperColor"
        tiledPaintCache[key]?.let { return it }
        val tile = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888)
        val tileCanvas = Canvas(tile)
        when (style) {
            CanvasBackgroundStyle.DOT_GRID -> {
                val dotRadius = (pitch * 0.055f).coerceIn(1.1f, 3.2f)
                tileCanvas.drawCircle(
                    pitch / 2f,
                    pitch / 2f,
                    dotRadius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = patternInk(paperColor, dots = true) }
                )
            }
            CanvasBackgroundStyle.LINE_GRID -> {
                val stroke = (pitch * 0.022f).coerceIn(0.9f, 2f)
                val linePaint = Paint().apply { color = patternInk(paperColor) }
                tileCanvas.drawRect(0f, 0f, pitch.toFloat(), stroke, linePaint)
                tileCanvas.drawRect(0f, 0f, stroke, pitch.toFloat(), linePaint)
            }
            else -> Unit
        }
        return shaderPaint(tile).also {
            // At most a handful of pitches are encountered across open projects; each cached tile
            // is no larger than 110 x 110 pixels.
            tiledPaintCache[key] = it
        }
    }

    private fun drawIsometric(canvas: Canvas, visible: RectF, pitch: Int, paperColor: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = patternInk(paperColor)
            strokeWidth = (pitch * 0.02f).coerceIn(0.8f, 1.8f)
            style = Paint.Style.STROKE
        }
        canvas.save()
        canvas.clipRect(visible)

        var x = floor(visible.left / pitch).toFloat() * pitch
        while (x <= visible.right) {
            canvas.drawLine(x, visible.top, x, visible.bottom, paint)
            x += pitch
        }

        val slope = 1f / sqrt(3f)
        val diagonalStep = pitch * 2f * slope
        val span = visible.height() * slope
        var intercept = floor((visible.left - span) / diagonalStep).toFloat() * diagonalStep
        val end = visible.right + span + diagonalStep
        while (intercept <= end) {
            canvas.drawLine(
                intercept + visible.top * slope,
                visible.top,
                intercept + visible.bottom * slope,
                visible.bottom,
                paint
            )
            canvas.drawLine(
                intercept - visible.top * slope,
                visible.top,
                intercept - visible.bottom * slope,
                visible.bottom,
                paint
            )
            intercept += diagonalStep
        }
        canvas.restore()
    }

    private fun shaderPaint(tile: Bitmap) = Paint().apply {
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Keeps ruled paper legible after the Base layer is changed to a dark or saturated colour. */
    private fun patternInk(paperColor: Int, dots: Boolean = false): Int {
        val luminance = (
            Color.red(paperColor) * 0.2126f +
                Color.green(paperColor) * 0.7152f +
                Color.blue(paperColor) * 0.0722f
            ) / 255f
        return if (luminance < 0.45f) {
            Color.argb(if (dots) 105 else 82, 255, 255, 255)
        } else if (dots) DOT_INK else GRID_INK
    }
}
