package com.procreate.android.urban.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.procreate.android.R
import com.procreate.android.urban.model.UrbanArrowPreview
import com.procreate.android.urban.model.UrbanFillPattern
import com.procreate.android.urban.model.UrbanGeometryKind
import com.procreate.android.urban.model.UrbanPreviewSymbol
import com.procreate.android.urban.model.UrbanStrokePattern
import com.procreate.android.urban.model.UrbanToolType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A compact preview driven by the same semantic definition used by each Urban tool. */
internal class UrbanToolPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var tool: UrbanToolType? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val shapePath = Path()
    private val previewBounds = RectF()

    init {
        minimumWidth = dp(48f).toInt()
        minimumHeight = dp(48f).toInt()
        isDuplicateParentStateEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(52f).toInt()
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentTool = tool ?: return
        val selected = drawableState.contains(android.R.attr.state_selected)
        val pressed = drawableState.contains(android.R.attr.state_pressed)
        val accent = ContextCompat.getColor(context, R.color.procreate_accent)
        val surface = ContextCompat.getColor(context, R.color.surface_3)
        fillPaint.color = when {
            pressed -> ColorUtils.blendARGB(surface, accent, 0.34f)
            selected -> ColorUtils.blendARGB(surface, accent, 0.22f)
            else -> surface
        }
        previewBounds.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        canvas.drawRoundRect(previewBounds, dp(13f), dp(13f), fillPaint)

        strokePaint.color = if (selected) accent else readableColor(currentTool.defaultColor)
        strokePaint.strokeWidth = dp(if (selected) 2.7f else 2.3f)
        strokePaint.pathEffect = null
        fillPaint.color = strokePaint.color
        textPaint.color = strokePaint.color
        previewBounds.inset(dp(8f), dp(8f))

        val preview = currentTool.definition.preview
        when (currentTool.definition.geometryKind) {
            UrbanGeometryKind.POINT -> drawPointSymbol(canvas, preview.symbol)
            UrbanGeometryKind.PATH -> drawPathPreview(
                canvas,
                preview.strokePattern,
                preview.arrow,
                preview.symbol,
                preview.showLabels
            )
            UrbanGeometryKind.POLYGON -> drawPolygonPreview(
                canvas,
                preview.strokePattern,
                preview.fillPattern,
                preview.showVertexNodes,
                preview.showLabels
            )
            UrbanGeometryKind.COMMAND -> drawPointSymbol(canvas, preview.symbol)
        }
    }

    private fun drawPathPreview(
        canvas: Canvas,
        pattern: UrbanStrokePattern,
        arrow: UrbanArrowPreview,
        symbol: UrbanPreviewSymbol,
        showLabel: Boolean
    ) {
        val p1x = previewBounds.left + previewBounds.width() * 0.06f
        val p1y = previewBounds.bottom - previewBounds.height() * 0.24f
        val p2x = previewBounds.left + previewBounds.width() * 0.46f
        val p2y = previewBounds.top + previewBounds.height() * 0.30f
        val p3x = previewBounds.right - previewBounds.width() * 0.08f
        val p3y = previewBounds.centerY()
        shapePath.reset()
        shapePath.moveTo(p1x, p1y)
        shapePath.lineTo(p2x, p2y)
        shapePath.lineTo(p3x, p3y)
        strokePaint.pathEffect = pathEffect(pattern)
        canvas.drawPath(shapePath, strokePaint)
        strokePaint.pathEffect = null

        if (symbol == UrbanPreviewSymbol.UTILITY_NODE) {
            listOf(p1x to p1y, p2x to p2y, p3x to p3y).forEach { (x, y) ->
                fillPaint.color = strokePaint.color
                canvas.drawCircle(x, y, dp(2f), fillPaint)
                canvas.drawCircle(x, y, dp(3.8f), strokePaint)
            }
        }
        when (arrow) {
            UrbanArrowPreview.NONE -> Unit
            UrbanArrowPreview.TRIANGLE -> drawArrowHead(canvas, p2x, p2y, p3x, p3y, dp(7f), true)
            UrbanArrowPreview.CHEVRON_WIDE -> drawArrowHead(canvas, p2x, p2y, p3x, p3y, dp(9f), false)
            UrbanArrowPreview.DIRECTION -> drawArrowHead(canvas, p2x, p2y, p3x, p3y, dp(6f), false)
        }
        if (showLabel) drawCenteredText(canvas, "12", previewBounds.centerX(), previewBounds.bottom, dp(8f))
    }

    private fun drawPolygonPreview(
        canvas: Canvas,
        strokePattern: UrbanStrokePattern,
        fillPattern: UrbanFillPattern,
        showNodes: Boolean,
        showLabel: Boolean
    ) {
        val left = previewBounds.left + dp(1f)
        val top = previewBounds.top + dp(2f)
        val right = previewBounds.right - dp(1f)
        val bottom = previewBounds.bottom - dp(1f)
        val points = arrayOf(
            left + (right - left) * 0.18f to top,
            right to top + (bottom - top) * 0.26f,
            right - (right - left) * 0.18f to bottom,
            left to bottom - (bottom - top) * 0.18f
        )
        shapePath.reset()
        shapePath.moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { shapePath.lineTo(it.first, it.second) }
        shapePath.close()

        when (fillPattern) {
            UrbanFillPattern.NONE -> Unit
            UrbanFillPattern.SOLID -> {
                fillPaint.color = withAlpha(strokePaint.color, 92)
                canvas.drawPath(shapePath, fillPaint)
            }
            UrbanFillPattern.DIAGONAL_45 -> drawHatch(canvas, shapePath, false)
            UrbanFillPattern.STIPPLE_DOTS -> drawStipple(canvas, shapePath)
            UrbanFillPattern.RUIN_HATCH -> drawHatch(canvas, shapePath, true)
        }
        strokePaint.pathEffect = pathEffect(strokePattern)
        canvas.drawPath(shapePath, strokePaint)
        strokePaint.pathEffect = null

        if (showNodes) {
            fillPaint.color = strokePaint.color
            points.forEach { (x, y) -> canvas.drawCircle(x, y, dp(2.3f), fillPaint) }
        }
        if (showLabel) drawCenteredText(canvas, "1", previewBounds.centerX(), previewBounds.centerY(), dp(9f))
    }

    private fun drawHatch(canvas: Canvas, polygon: Path, cross: Boolean) {
        val previousColor = strokePaint.color
        val previousWidth = strokePaint.strokeWidth
        strokePaint.color = withAlpha(previousColor, 180)
        strokePaint.strokeWidth = dp(1.1f)
        canvas.save()
        canvas.clipPath(polygon)
        val spacing = dp(5f)
        var offset = -previewBounds.height()
        while (offset <= previewBounds.width() + previewBounds.height()) {
            canvas.drawLine(
                previewBounds.left + offset,
                previewBounds.bottom,
                previewBounds.left + offset + previewBounds.height(),
                previewBounds.top,
                strokePaint
            )
            if (cross) {
                canvas.drawLine(
                    previewBounds.left + offset,
                    previewBounds.top,
                    previewBounds.left + offset + previewBounds.height(),
                    previewBounds.bottom,
                    strokePaint
                )
            }
            offset += spacing
        }
        canvas.restore()
        strokePaint.color = previousColor
        strokePaint.strokeWidth = previousWidth
    }

    private fun drawStipple(canvas: Canvas, polygon: Path) {
        fillPaint.color = withAlpha(strokePaint.color, 190)
        canvas.save()
        canvas.clipPath(polygon)
        val gap = dp(5f)
        var row = 0
        var y = previewBounds.top
        while (y <= previewBounds.bottom) {
            var x = previewBounds.left + if (row % 2 == 0) 0f else gap / 2f
            while (x <= previewBounds.right) {
                canvas.drawCircle(x, y, dp(1f), fillPaint)
                x += gap
            }
            row++
            y += gap
        }
        canvas.restore()
    }

    private fun drawPointSymbol(canvas: Canvas, symbol: UrbanPreviewSymbol) {
        val cx = previewBounds.centerX()
        val cy = previewBounds.centerY()
        val radius = min(previewBounds.width(), previewBounds.height()) * 0.33f
        fillPaint.color = withAlpha(strokePaint.color, 54)
        when (symbol) {
            UrbanPreviewSymbol.STATION_BADGE -> {
                canvas.drawCircle(cx, cy, radius, fillPaint)
                canvas.drawCircle(cx, cy, radius, strokePaint)
                drawCenteredText(canvas, "A", cx, cy, dp(12f))
            }
            UrbanPreviewSymbol.STREET_LIGHT -> {
                canvas.drawCircle(cx, cy, radius * 0.34f, fillPaint)
                canvas.drawCircle(cx, cy, radius * 0.34f, strokePaint)
                repeat(8) { index ->
                    val angle = Math.PI * 2.0 * index / 8.0
                    canvas.drawLine(
                        cx + cos(angle).toFloat() * radius * 0.52f,
                        cy + sin(angle).toFloat() * radius * 0.52f,
                        cx + cos(angle).toFloat() * radius,
                        cy + sin(angle).toFloat() * radius,
                        strokePaint
                    )
                }
            }
            UrbanPreviewSymbol.MANHOLE -> {
                canvas.drawCircle(cx, cy, radius, fillPaint)
                canvas.drawCircle(cx, cy, radius, strokePaint)
                canvas.drawCircle(cx, cy, radius * 0.62f, strokePaint)
                canvas.drawLine(cx - radius * 0.55f, cy, cx + radius * 0.55f, cy, strokePaint)
            }
            UrbanPreviewSymbol.WATER_TANK_OR_WELL -> {
                canvas.drawCircle(cx, cy, radius, fillPaint)
                canvas.drawCircle(cx, cy, radius, strokePaint)
                shapePath.reset()
                shapePath.moveTo(cx - radius * 0.62f, cy)
                shapePath.quadTo(cx - radius * 0.3f, cy - radius * 0.35f, cx, cy)
                shapePath.quadTo(cx + radius * 0.3f, cy + radius * 0.35f, cx + radius * 0.62f, cy)
                canvas.drawPath(shapePath, strokePaint)
            }
            UrbanPreviewSymbol.ELECTRIC_CABINET -> {
                val box = RectF(cx - radius * 0.78f, cy - radius, cx + radius * 0.78f, cy + radius)
                canvas.drawRoundRect(box, dp(3f), dp(3f), fillPaint)
                canvas.drawRoundRect(box, dp(3f), dp(3f), strokePaint)
                shapePath.reset()
                shapePath.moveTo(cx + dp(1f), cy - radius * 0.65f)
                shapePath.lineTo(cx - radius * 0.32f, cy)
                shapePath.lineTo(cx, cy)
                shapePath.lineTo(cx - dp(1f), cy + radius * 0.65f)
                shapePath.lineTo(cx + radius * 0.34f, cy - dp(1f))
                shapePath.lineTo(cx, cy - dp(1f))
                shapePath.close()
                fillPaint.color = strokePaint.color
                canvas.drawPath(shapePath, fillPaint)
            }
            UrbanPreviewSymbol.UTILITY_NODE -> {
                canvas.drawLine(cx - radius, cy, cx + radius, cy, strokePaint)
                canvas.drawCircle(cx, cy, radius * 0.3f, fillPaint)
                canvas.drawCircle(cx, cy, radius * 0.3f, strokePaint)
            }
            UrbanPreviewSymbol.VEHICLE_SHED -> {
                shapePath.reset()
                shapePath.moveTo(cx - radius, cy - radius * 0.2f)
                shapePath.lineTo(cx, cy - radius)
                shapePath.lineTo(cx + radius, cy - radius * 0.2f)
                canvas.drawPath(shapePath, strokePaint)
                canvas.drawLine(cx - radius * 0.75f, cy - radius * 0.12f, cx - radius * 0.75f, cy + radius, strokePaint)
                canvas.drawLine(cx + radius * 0.75f, cy - radius * 0.12f, cx + radius * 0.75f, cy + radius, strokePaint)
                canvas.drawRoundRect(
                    RectF(cx - radius * 0.52f, cy + radius * 0.24f, cx + radius * 0.52f, cy + radius * 0.7f),
                    dp(2f), dp(2f), strokePaint
                )
            }
            UrbanPreviewSymbol.WASTE_BIN -> {
                val bin = RectF(cx - radius * 0.62f, cy - radius * 0.46f, cx + radius * 0.62f, cy + radius * 0.7f)
                canvas.drawRoundRect(bin, dp(2f), dp(2f), fillPaint)
                canvas.drawRoundRect(bin, dp(2f), dp(2f), strokePaint)
                canvas.drawLine(cx - radius * 0.84f, cy - radius * 0.58f, cx + radius * 0.84f, cy - radius * 0.58f, strokePaint)
                fillPaint.color = strokePaint.color
                canvas.drawCircle(cx - radius * 0.38f, cy + radius * 0.88f, dp(1.8f), fillPaint)
                canvas.drawCircle(cx + radius * 0.38f, cy + radius * 0.88f, dp(1.8f), fillPaint)
            }
            UrbanPreviewSymbol.UTILITY_POLE -> {
                canvas.drawLine(cx, cy - radius, cx, cy + radius, strokePaint)
                canvas.drawLine(cx - radius * 0.78f, cy - radius * 0.52f, cx + radius * 0.78f, cy - radius * 0.52f, strokePaint)
                fillPaint.color = strokePaint.color
                canvas.drawCircle(cx - radius * 0.64f, cy - radius * 0.42f, dp(1.6f), fillPaint)
                canvas.drawCircle(cx + radius * 0.64f, cy - radius * 0.42f, dp(1.6f), fillPaint)
            }
            UrbanPreviewSymbol.SCALE_CALIBRATION -> {
                canvas.drawLine(cx - radius, cy, cx + radius, cy, strokePaint)
                canvas.drawLine(cx - radius, cy - radius * 0.45f, cx - radius, cy + radius * 0.45f, strokePaint)
                canvas.drawLine(cx + radius, cy - radius * 0.45f, cx + radius, cy + radius * 0.45f, strokePaint)
                drawCenteredText(canvas, "10m", cx, cy - radius * 0.68f, dp(7f))
            }
            UrbanPreviewSymbol.NONE -> {
                canvas.drawCircle(cx, cy, radius * 0.65f, fillPaint)
                canvas.drawCircle(cx, cy, radius * 0.65f, strokePaint)
            }
        }
    }

    private fun drawArrowHead(
        canvas: Canvas,
        fromX: Float,
        fromY: Float,
        tipX: Float,
        tipY: Float,
        size: Float,
        filled: Boolean
    ) {
        val angle = atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
        val wing = 0.68
        val leftX = tipX - (cos(angle - wing) * size).toFloat()
        val leftY = tipY - (sin(angle - wing) * size).toFloat()
        val rightX = tipX - (cos(angle + wing) * size).toFloat()
        val rightY = tipY - (sin(angle + wing) * size).toFloat()
        shapePath.reset()
        shapePath.moveTo(leftX, leftY)
        shapePath.lineTo(tipX, tipY)
        shapePath.lineTo(rightX, rightY)
        if (filled) {
            shapePath.close()
            fillPaint.color = strokePaint.color
            canvas.drawPath(shapePath, fillPaint)
        } else {
            canvas.drawPath(shapePath, strokePaint)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float) {
        textPaint.textSize = size
        textPaint.color = strokePaint.color
        canvas.drawText(text, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun pathEffect(pattern: UrbanStrokePattern): PathEffect? = when (pattern) {
        UrbanStrokePattern.NONE, UrbanStrokePattern.SOLID -> null
        UrbanStrokePattern.DASHED -> DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        UrbanStrokePattern.DOTTED -> DashPathEffect(floatArrayOf(dp(1.2f), dp(4f)), 0f)
    }

    private fun readableColor(color: Int): Int =
        if (ColorUtils.calculateLuminance(color) < 0.12) ColorUtils.blendARGB(color, Color.WHITE, 0.42f) else color

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * density
}
