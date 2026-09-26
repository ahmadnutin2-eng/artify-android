package com.procreate.android.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A rounded panel with a beak pointing back at the control that opened it.
 *
 * A menu that appears with no visible connection to anything asks the reader to remember which
 * button they pressed. The beak answers that without a word: the panel is visibly attached to its
 * own button, so a stack of similar-looking menus never becomes ambiguous.
 *
 * Drawn as one path rather than a card with a triangle laid on top, because two shapes meeting
 * leave a seam - visible as a hairline at the join, and worse once there is a border to match up.
 */
class PopoverBackground(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val cornerRadius: Float,
    private val beakWidth: Float,
    private val beakHeight: Float,
    private val strokeWidth: Float
) : Drawable() {

    enum class Edge { TOP, BOTTOM, START, END }

    /** Which side the beak leaves from. */
    var edge: Edge = Edge.TOP
        set(value) {
            field = value
            invalidateSelf()
        }

    /**
     * Where along that edge the beak sits, in pixels from the panel's left (or top, for a beak on a
     * vertical edge). Clamped on draw so a beak can never be asked to grow out of a corner.
     */
    var beakCenter: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        this.strokeWidth = this@PopoverBackground.strokeWidth
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        buildPath()
        canvas.drawPath(path, fillPaint)
        if (strokeWidth > 0f) canvas.drawPath(path, strokePaint)
    }

    private fun buildPath() {
        path.reset()
        val b = bounds
        val inset = strokeWidth / 2f
        val body = RectF(
            b.left + inset + if (edge == Edge.START) beakHeight else 0f,
            b.top + inset + if (edge == Edge.TOP) beakHeight else 0f,
            b.right - inset - if (edge == Edge.END) beakHeight else 0f,
            b.bottom - inset - if (edge == Edge.BOTTOM) beakHeight else 0f
        )
        path.addRoundRect(body, cornerRadius, cornerRadius, Path.Direction.CW)

        val half = beakWidth / 2f
        // Keep the whole beak on the straight part of the edge; overlapping a rounded corner would
        // leave it growing out of thin air.
        val margin = cornerRadius + half + inset
        val beak = Path()
        when (edge) {
            Edge.TOP -> {
                val x = beakCenter.coerceIn(body.left + margin, body.right - margin)
                beak.moveTo(x - half, body.top + inset)
                beak.lineTo(x, b.top + inset)
                beak.lineTo(x + half, body.top + inset)
            }
            Edge.BOTTOM -> {
                val x = beakCenter.coerceIn(body.left + margin, body.right - margin)
                beak.moveTo(x - half, body.bottom - inset)
                beak.lineTo(x, b.bottom - inset)
                beak.lineTo(x + half, body.bottom - inset)
            }
            Edge.START -> {
                val y = beakCenter.coerceIn(body.top + margin, body.bottom - margin)
                beak.moveTo(body.left + inset, y - half)
                beak.lineTo(b.left + inset, y)
                beak.lineTo(body.left + inset, y + half)
            }
            Edge.END -> {
                val y = beakCenter.coerceIn(body.top + margin, body.bottom - margin)
                beak.moveTo(body.right - inset, y - half)
                beak.lineTo(b.right - inset, y)
                beak.lineTo(body.right - inset, y + half)
            }
        }
        beak.close()
        path.op(beak, Path.Op.UNION)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable, but still abstract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
