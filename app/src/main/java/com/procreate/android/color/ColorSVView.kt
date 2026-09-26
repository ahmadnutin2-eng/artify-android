package com.procreate.android.color

import android.content.Context
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Classic saturation/value square: X axis is saturation (white -> pure hue), Y axis is
 * value/brightness (opaque -> black). Drag anywhere to pick; the hue itself is set externally
 * via [setHue] (driven by a separate hue strip).
 */
class ColorSVView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hue = 0f
    private var satFrac = 1f   // 0..1, x position
    private var valFrac = 1f   // 0..1, y position (1 = top/bright)

    private val fillPaint = Paint()
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val puckShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(120, 0, 0, 0)
    }

    var onColorChanged: ((Int) -> Unit)? = null

    private fun rebuildShader() {
        if (width == 0 || height == 0) return
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        val satShader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        val valShader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        fillPaint.shader = ComposeShader(satShader, valShader, PorterDuff.Mode.MULTIPLY)
    }

    fun setHue(newHue: Float) {
        hue = newHue
        rebuildShader()
        invalidate()
        notifyColor()
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        satFrac = hsv[1]
        valFrac = hsv[2]
        rebuildShader()
        invalidate()
    }

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, satFrac, valFrac))

    private fun notifyColor() {
        onColorChanged?.invoke(currentColor())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        val px = satFrac * width
        val py = (1f - valFrac) * height
        canvas.drawCircle(px, py, 12f, puckShadowPaint)
        canvas.drawCircle(px, py, 12f, puckPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                satFrac = (event.x / width).coerceIn(0f, 1f)
                valFrac = (1f - event.y / height).coerceIn(0f, 1f)
                invalidate()
                notifyColor()
            }
        }
        return true
    }
}
