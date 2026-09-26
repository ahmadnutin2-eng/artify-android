package com.procreate.android.color

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Horizontal full-spectrum hue strip (0-360°) with a draggable indicator. */
class HueSliderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var hueFrac = 0f // 0..1
    private val fillPaint = Paint()
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }

    var onHueChanged: ((Float) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            val hues = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
            fillPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        }
    }

    fun setHue(hue: Float) {
        hueFrac = (hue / 360f).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        val x = hueFrac * width
        canvas.drawLine(x, 0f, x, height.toFloat(), indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                hueFrac = (event.x / width).coerceIn(0f, 1f)
                invalidate()
                onHueChanged?.invoke(hueFrac * 360f)
            }
        }
        return true
    }
}
