package com.procreate.android.urban.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.procreate.android.urban.model.HatchStyle
import com.procreate.android.urban.model.UrbanElement
import kotlin.math.max
import kotlin.math.min

/**
 * محرك رسم التهشير المعماري وملء الساحات والمباني والفراغات.
 * يدعم:
 * - التهشير المائل 45 درجة (للساحات العامة).
 * - التهشير النقطي (المزارع والنخيل).
 * - التهشير الشبكي المتعامد (المقابر).
 * - الملء اللوني الكودي (المباني التراثية والحديثة).
 * - شارات ترقيم الساحات (1 إلى 18) في دوائر بيضاء ناصعة.
 */
object UrbanHatchRenderer {

    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.5f
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 20f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun renderHatch(canvas: Canvas, hatch: UrbanElement.HatchPolygon) {
        val vertices = hatch.vertices
        if (vertices.size < 3) return

        // 1. بناء مسار المضلع
        val polygonPath = Path()
        polygonPath.moveTo(vertices[0].x, vertices[0].y)
        var minX = vertices[0].x
        var maxX = vertices[0].x
        var minY = vertices[0].y
        var maxY = vertices[0].y
        var centerX = 0f
        var centerY = 0f

        for (v in vertices) {
            polygonPath.lineTo(v.x, v.y)
            minX = min(minX, v.x)
            maxX = max(maxX, v.x)
            minY = min(minY, v.y)
            maxY = max(maxY, v.y)
            centerX += v.x
            centerY += v.y
        }
        polygonPath.close()
        centerX /= vertices.size
        centerY /= vertices.size

        val bounds = RectF(minX, minY, maxX, maxY)

        canvas.save()
        // اقتصاص الرسم داخل حدود المضلع
        canvas.clipPath(polygonPath)

        when (hatch.hatchStyle) {
            HatchStyle.DIAGONAL_45 -> {
                // خلفية خفيفة نصف شفافة أولاً
                fillPaint.color = hatch.color
                fillPaint.alpha = 40
                canvas.drawPath(polygonPath, fillPaint)

                // خطوط تهشير مائلة بزاوية 45 درجة
                hatchPaint.color = hatch.color
                hatchPaint.alpha = 230
                val spacing = hatch.hatchSpacing.coerceAtLeast(12f)
                val width = bounds.width()
                val height = bounds.height()
                val totalLength = width + height

                var offset = -height
                while (offset < totalLength) {
                    canvas.drawLine(
                        bounds.left + offset, bounds.top,
                        bounds.left + offset + height, bounds.bottom,
                        hatchPaint
                    )
                    offset += spacing
                }
            }
            HatchStyle.CROSS_HATCH -> {
                // تهشير متعامد شبكي
                hatchPaint.color = hatch.color
                val spacing = hatch.hatchSpacing.coerceAtLeast(14f)

                var x = bounds.left
                while (x <= bounds.right) {
                    canvas.drawLine(x, bounds.top, x, bounds.bottom, hatchPaint)
                    x += spacing
                }
                var y = bounds.top
                while (y <= bounds.bottom) {
                    canvas.drawLine(bounds.left, y, bounds.right, y, hatchPaint)
                    y += spacing
                }
            }
            HatchStyle.STIPPLE_DOTS -> {
                // تهشير نقطي كثيف للمزارع والنخيل
                fillPaint.color = hatch.color
                fillPaint.alpha = 50
                canvas.drawPath(polygonPath, fillPaint)

                hatchPaint.color = hatch.color
                hatchPaint.style = Paint.Style.FILL
                val spacing = 16f
                var x = bounds.left
                var row = 0
                while (x <= bounds.right) {
                    var y = bounds.top + (if (row % 2 == 1) spacing / 2f else 0f)
                    while (y <= bounds.bottom) {
                        canvas.drawCircle(x, y, 2.5f, hatchPaint)
                        y += spacing
                    }
                    x += spacing
                    row++
                }
            }
            HatchStyle.SOLID_FILL -> {
                // ملء لوني مع الحفاظ على درجة الشفافية للكتل
                fillPaint.color = hatch.color
                fillPaint.alpha = 210
                canvas.drawPath(polygonPath, fillPaint)
            }
        }

        canvas.restore()

        // 2. رسم حدود المضلع الخارجية بلون داكن ومحدد
        borderPaint.color = hatch.color
        borderPaint.alpha = 255
        canvas.drawPath(polygonPath, borderPaint)

        // 3. رسم شارة ترقيم الساحة (1 إلى 18) في مركز المضلع
        hatch.plazaNumber?.let { number ->
            val badgeRadius = 18f
            canvas.drawCircle(centerX, centerY, badgeRadius, badgeBgPaint)
            canvas.drawCircle(centerX, centerY, badgeRadius, badgeStrokePaint)

            val fontMetrics = badgeTextPaint.fontMetrics
            val textY = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(number.toString(), centerX, textY, badgeTextPaint)
        }
    }
}
