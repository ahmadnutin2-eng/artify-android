package com.procreate.android.ui.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.procreate.android.R

/**
 * Artify's vertical fill-bar slider, rather than a rotated stock [android.widget.SeekBar]: a
 * single rounded pill whose *filled height* is the
 * value - there's no separate thumb to grab precisely, and a tap or drag anywhere along the
 * pill's length jumps straight to that position. That's a genuinely different interaction
 * language from a thumb-on-a-track, not just a different skin, so it's its own View rather than
 * a restyled SeekBar.
 */
class VerticalFillSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var max: Int = 100
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    var progress: Int = 50
        set(value) {
            field = value.coerceIn(0, max)
            invalidate()
        }

    /** (value, fromUser) - fromUser is false when set programmatically via [progress]. */
    var onProgressChanged: ((Int, Boolean) -> Unit)? = null

    /** Fires true when a finger starts dragging the pill, false when it lifts - lets a caller
     * show a live value preview only for the duration of the actual drag. */
    var onTrackingChanged: ((Boolean) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_2)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.procreate_accent)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The pill is drawn inside the horizontal padding rather than edge to edge, which lets the
        // view itself be a full 48dp-wide touch target while the visible control stays slim. A
        // 48dp-wide pill would read as a chunky bar instead of the fine slider this control is.
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        val r = w / 2f

        canvas.drawRoundRect(left, top, right, bottom, r, r, trackPaint)
        canvas.drawRoundRect(left, top, right, bottom, r, r, outlinePaint)

        val fraction = progress.toFloat() / max
        val fillTop = top + h * (1f - fraction)
        // Both fills share the exact same rounded-pill geometry as the track and differ only in
        // the clip - so the visible edge is the true rounded corner near the bottom, and a plain
        // flat line anywhere the clip cuts through the pill's straight side walls instead.
        canvas.save()
        canvas.clipRect(left, fillTop, right, bottom)
        canvas.drawRoundRect(left, top, right, bottom, r, r, fillPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onTrackingChanged?.invoke(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                // Measured against the same padded track the pill is drawn in, so the value under
                // the finger matches what the fill shows.
                val trackHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
                val fraction = (1f - (event.y - paddingTop) / trackHeight).coerceIn(0f, 1f)
                progress = (fraction * max).toInt()
                onProgressChanged?.invoke(progress, true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onTrackingChanged?.invoke(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
