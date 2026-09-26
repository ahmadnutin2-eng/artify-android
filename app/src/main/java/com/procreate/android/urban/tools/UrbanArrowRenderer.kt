package com.procreate.android.urban.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import com.procreate.android.urban.model.ArrowHeadType
import com.procreate.android.urban.model.UrbanElement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * محرك رسم الأسهم والمحاور البصرية الحضرية المتقدمة.
 * يدعم:
 * - التحكم في رأس السهم وبدايته ونهايته.
 * - نقاط متسلسلة (Trailing Dots) خلف رأس السهم توضح اتجاه الحركة وتدفقها.
 * - أنماط الخطوط (منقط عريض للمحور الرئيسي، متقطع للثانوي، عريض للمداخل).
 * - محطات المشاهدة الدائرية (A, B, C).
 */
object UrbanArrowRenderer {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val stationBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val stationStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 22f
        isFakeBoldText = true
    }

    fun renderArrow(canvas: Canvas, arrow: UrbanElement.ArrowPath) {
        val pts = arrow.points
        if (pts.size < 2) return

        linePaint.color = arrow.color
        linePaint.strokeWidth = arrow.strokeWidth

        // تجهيز نمط الخط (منقط، متقطع، أو مصمت)
        if (arrow.isDotted) {
            // نقاط متقاربة عريضة كما في المحور الرئيسي
            linePaint.pathEffect = DashPathEffect(floatArrayOf(4f, arrow.strokeWidth * 1.5f), 0f)
        } else if (arrow.isDashed) {
            // خط متقطع كما في المحور الثانوي
            linePaint.pathEffect = DashPathEffect(floatArrayOf(arrow.strokeWidth * 2.5f, arrow.strokeWidth * 1.8f), 0f)
        } else {
            linePaint.pathEffect = null
        }

        // رسم المسار الأساسي
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
        }
        canvas.drawPath(path, linePaint)

        // حساب الزاوية عند نقطة النهاية لرسم رأس السهم
        // (نستخدم نقطة مستقرة بمسافة كافية للخلف بدلاً من آخر نقطتين فقط، لتفادي دوران رأس
        // السهم بشكل غريب بسبب حركة يد صغيرة جداً في آخر جزء من الرسم)
        val endP = pts.last()
        val minLookback = (arrow.strokeWidth * 2f).coerceAtLeast(1f)
        val prevP = stableDirectionPoint(pts, fromEnd = true, minDist = minLookback)
        val angle = atan2((endP.y - prevP.y).toDouble(), (endP.x - prevP.x).toDouble()).toFloat()

        // 1. رسم النقاط المتسلسلة خلف رأس السهم (Trailing Dots) إذا كانت مفعلة
        if (arrow.hasTrailingDots) {
            renderTrailingDots(canvas, endP, angle, arrow.color, arrow.strokeWidth)
        }

        // 2. رسم رأس السهم
        arrowHeadPaint.color = arrow.color
        renderArrowHead(canvas, endP, angle, arrow.arrowHeadType, arrow.strokeWidth)

        // 3. رسم رأس بداية السهم إن وجد
        if (arrow.arrowHeadType == ArrowHeadType.DOUBLE_HEAD) {
            val startP = pts.first()
            val nextP = stableDirectionPoint(pts, fromEnd = false, minDist = minLookback)
            val startAngle = atan2((startP.y - nextP.y).toDouble(), (startP.x - nextP.x).toDouble()).toFloat()
            renderArrowHead(canvas, startP, startAngle, ArrowHeadType.LARGE_TRIANGLE, arrow.strokeWidth)
        }

        // 4. رسم محطات المشاهدة (Stations A, B, C)
        for (st in arrow.stations) {
            renderStation(canvas, st.point, st.label, arrow.color)
        }

        // 5. كتابة نصوص الاتجاهات (مثل "إلى أبها", "إلى تمنيه")
        arrow.endLabel?.let { label ->
            val labelX = endP.x + cos(angle.toDouble()).toFloat() * (arrow.strokeWidth * 3.5f)
            val labelY = endP.y + sin(angle.toDouble()).toFloat() * (arrow.strokeWidth * 3.5f) + 8f
            textPaint.color = arrow.color
            textPaint.textSize = 24f
            canvas.drawText(label, labelX, labelY, textPaint)
        }
    }

    /**
     * يعيد نقطة "مستقرة" على المسار بمسافة لا تقل عن [minDist] من الطرف المطلوب
     * ([fromEnd] = نهاية المسار أو بدايته)، بدلاً من الاعتماد على النقطة المجاورة مباشرة فقط.
     * يمنع هذا اهتزاز زاوية رأس السهم عندما تكون آخر حركة لليد قصيرة جداً وضجيجية الاتجاه.
     */
    private fun stableDirectionPoint(pts: List<PointF>, fromEnd: Boolean, minDist: Float): PointF {
        val anchor = if (fromEnd) pts.last() else pts.first()
        var acc = 0f
        var result = if (fromEnd) pts[pts.size - 2] else pts[1]
        val range = if (fromEnd) (pts.size - 2) downTo 0 else 1 until pts.size
        var prev = anchor
        for (i in range) {
            val cur = pts[i]
            acc += hypot((cur.x - prev.x).toDouble(), (cur.y - prev.y).toDouble()).toFloat()
            result = cur
            if (acc >= minDist) break
            prev = cur
        }
        return result
    }

    /**
     * رسم نقاط متدرجة خلف رأس السهم تعبر عن ديناميكية واتجاه التدفق والحركة
     */
    private fun renderTrailingDots(canvas: Canvas, endP: PointF, angle: Float, color: Int, strokeWidth: Float) {
        dotPaint.color = color
        val numDots = 4
        val step = strokeWidth * 1.8f
        val baseRadius = strokeWidth * 0.45f

        for (i in 1..numDots) {
            val dist = i * step + (strokeWidth * 1.4f)
            val dotX = endP.x - cos(angle.toDouble()).toFloat() * dist
            val dotY = endP.y - sin(angle.toDouble()).toFloat() * dist
            val radius = baseRadius * (1f - (i * 0.15f)).coerceAtLeast(0.2f)
            dotPaint.alpha = (255 * (1f - (i * 0.18f))).toInt().coerceIn(40, 255)
            canvas.drawCircle(dotX, dotY, radius, dotPaint)
        }
        dotPaint.alpha = 255
    }

    /**
     * رسم رأس السهم بالأشكال الهندسية المختلفة
     */
    private fun renderArrowHead(canvas: Canvas, point: PointF, angle: Float, type: ArrowHeadType, strokeWidth: Float) {
        val headLength = strokeWidth * 3.2f
        val headWidth = strokeWidth * 2.2f

        val headPath = Path()
        when (type) {
            ArrowHeadType.LARGE_TRIANGLE -> {
                // رأس سهم مثلث عريض ومصمت كالظاهر في المحاور البصرية
                val cosA = cos(angle.toDouble()).toFloat()
                val sinA = sin(angle.toDouble()).toFloat()
                val normX = -sinA
                val normY = cosA

                headPath.moveTo(point.x, point.y)
                headPath.lineTo(
                    point.x - cosA * headLength + normX * headWidth,
                    point.y - sinA * headLength + normY * headWidth
                )
                headPath.lineTo(
                    point.x - cosA * (headLength * 0.75f),
                    point.y - sinA * (headLength * 0.75f)
                )
                headPath.lineTo(
                    point.x - cosA * headLength - normX * headWidth,
                    point.y - sinA * headLength - normY * headWidth
                )
                headPath.close()
                canvas.drawPath(headPath, arrowHeadPaint)
            }
            ArrowHeadType.CHEVRON_WIDE -> {
                // سهم عريض للمداخل (Chevron)
                val cosA = cos(angle.toDouble()).toFloat()
                val sinA = sin(angle.toDouble()).toFloat()
                val normX = -sinA
                val normY = cosA

                headPath.moveTo(point.x, point.y)
                headPath.lineTo(
                    point.x - cosA * headLength + normX * headWidth,
                    point.y - sinA * headLength + normY * headWidth
                )
                headPath.lineTo(
                    point.x - cosA * (headLength * 0.8f),
                    point.y - sinA * (headLength * 0.8f)
                )
                headPath.lineTo(
                    point.x - cosA * headLength - normX * headWidth,
                    point.y - sinA * headLength - normY * headWidth
                )
                headPath.close()
                canvas.drawPath(headPath, arrowHeadPaint)
            }
            ArrowHeadType.ROUNDED_DOT -> {
                canvas.drawCircle(point.x, point.y, headWidth, arrowHeadPaint)
            }
            ArrowHeadType.DOUBLE_HEAD -> {
                // مثلث بسيط
                val cosA = cos(angle.toDouble()).toFloat()
                val sinA = sin(angle.toDouble()).toFloat()
                val normX = -sinA
                val normY = cosA

                headPath.moveTo(point.x, point.y)
                headPath.lineTo(
                    point.x - cosA * headLength + normX * headWidth,
                    point.y - sinA * headLength + normY * headWidth
                )
                headPath.lineTo(
                    point.x - cosA * headLength - normX * headWidth,
                    point.y - sinA * headLength - normY * headWidth
                )
                headPath.close()
                canvas.drawPath(headPath, arrowHeadPaint)
            }
        }
    }

    /**
     * رسم محطة بصرية برقم أو حرف (A, B, C) بدائرة بيضاء أنيقة وحدود ملونة
     */
    private fun renderStation(canvas: Canvas, point: PointF, label: String, color: Int) {
        val radius = 22f
        stationStrokePaint.color = color

        // خلفية بيضاء
        canvas.drawCircle(point.x, point.y, radius, stationBgPaint)
        // إطار بلون المحور
        canvas.drawCircle(point.x, point.y, radius, stationStrokePaint)

        // النص بالحرف (A, B, C)
        textPaint.color = color
        textPaint.textSize = 22f
        val fontMetrics = textPaint.fontMetrics
        val textY = point.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(label, point.x, textY, textPaint)
    }
}
