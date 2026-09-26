package com.procreate.android.ui.common

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The chequerboard every painting app puts behind a transparent thumbnail.
 *
 * Without it a layer that is mostly empty renders as a blank dark square, which is
 * indistinguishable from a layer that is genuinely empty - and a thumbnail of white strokes on a
 * dark panel disappears entirely.
 */
class CheckerDrawable(
    private val cellPx: Float,
    private val light: Int = 0xFF4A4A50.toInt(),
    private val dark: Int = 0xFF37373C.toInt()
) : Drawable() {

    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = light
        canvas.drawRect(b, paint)
        paint.color = dark
        var row = 0
        var y = b.top.toFloat()
        while (y < b.bottom) {
            var col = 0
            var x = b.left.toFloat()
            while (x < b.right) {
                if ((row + col) % 2 == 1) {
                    canvas.drawRect(x, y, minOf(x + cellPx, b.right.toFloat()), minOf(y + cellPx, b.bottom.toFloat()), paint)
                }
                x += cellPx
                col++
            }
            y += cellPx
            row++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required override; the return value is ignored on current platforms.")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
