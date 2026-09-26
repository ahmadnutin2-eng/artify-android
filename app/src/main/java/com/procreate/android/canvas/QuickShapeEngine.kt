package com.procreate.android.canvas

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * أنواع الأشكال الهندسية الذكية الناتجة عن QuickShape.
 */
sealed class QuickShape {
    data class Line(val start: PointF, val end: PointF) : QuickShape()
    data class Circle(val center: PointF, val radius: Float) : QuickShape()
    data class Ellipse(val bounds: RectF) : QuickShape()
    data class Rectangle(val bounds: RectF) : QuickShape()
    data class Triangle(val p1: PointF, val p2: PointF, val p3: PointF) : QuickShape()

    fun nameAr(): String = when (this) {
        is Line -> "خط مستقيم"
        is Circle -> "دائرة مثالية"
        is Ellipse -> "قطع ناقص"
        is Rectangle -> "مستطيل"
        is Triangle -> "مثلث"
    }

    fun nameEn(): String = when (this) {
        is Line -> "Straight Line"
        is Circle -> "Circle"
        is Ellipse -> "Ellipse"
        is Rectangle -> "Rectangle"
        is Triangle -> "Triangle"
    }
}

/**
 * محرك الكشف عن الأشكال الذكية السريعة (QuickShape Engine) المستوحى من Procreate:
 * عند رسم خط أو شكل مغلق والتوقف للحظة، يتعرف المحرك على نية الفنان ويحول السكتة اليدوية
 * إلى شكل هندسي نقي ودقيق.
 */
object QuickShapeEngine {

    /**
     * يحلل مصفوفة نقاط السكتة المرسومة باليد ويكتشف ما إذا كانت تمثل شكلاً هندسياً.
     */
    fun detect(points: List<PointF>): QuickShape? {
        if (points.size < 6) return null
        val start = points.first()
        val end = points.last()
        val chordDist = hypot(end.x - start.x, end.y - start.y)

        // حساب الطول الإجمالي للمسار المرسوم
        var totalLength = 0f
        for (i in 0 until points.size - 1) {
            totalLength += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
        }
        if (totalLength < 40f) return null

        val isClosed = (chordDist / totalLength) < 0.25f && totalLength > 60f

        if (!isClosed) {
            // فحص الخط المستقيم (Straight Line)
            var maxDev = 0f
            val dx = end.x - start.x
            val dy = end.y - start.y
            if (chordDist > 30f) {
                for (p in points) {
                    val dist = abs(dy * p.x - dx * p.y + end.x * start.y - end.y * start.x) / chordDist
                    if (dist > maxDev) maxDev = dist
                }
                // إذا كان أقصى انحراف أقل من 10% من طول الوتر، فهو خط مستقيم نقي
                if (maxDev / chordDist < 0.10f) {
                    return QuickShape.Line(PointF(start.x, start.y), PointF(end.x, end.y))
                }
            }
        } else {
            // فحص الأشكال المغلقة: الدائرة، القطع الناقص، والمستطيل
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            val w = maxX - minX
            val h = maxY - minY
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f

            if (w > 20f && h > 20f) {
                val aspect = min(w, h) / max(w, h)
                val rx = w / 2f
                val ry = h / 2f

                // انحراف نصف القطر عن الدائرة / القطع الناقص
                var varSum = 0f
                for (p in points) {
                    val nx = (p.x - cx) / rx
                    val ny = (p.y - cy) / ry
                    val d = hypot(nx, ny)
                    varSum += abs(d - 1f)
                }
                val avgDeviation = varSum / points.size

                if (avgDeviation < 0.18f) {
                    return if (aspect > 0.85f) {
                        QuickShape.Circle(PointF(cx, cy), (rx + ry) / 2f)
                    } else {
                        QuickShape.Ellipse(RectF(minX, minY, maxX, maxY))
                    }
                }

                // مستطيل متماثل الأضلاع
                return QuickShape.Rectangle(RectF(minX, minY, maxX, maxY))
            }
        }
        return null
    }

    /**
     * تحويل الشكل المكتشف إلى كائن [Path] للرسم المباشر على اللوحة.
     */
    fun toPath(shape: QuickShape): Path {
        val path = Path()
        when (shape) {
            is QuickShape.Line -> {
                path.moveTo(shape.start.x, shape.start.y)
                path.lineTo(shape.end.x, shape.end.y)
            }
            is QuickShape.Circle -> {
                path.addCircle(shape.center.x, shape.center.y, shape.radius, Path.Direction.CW)
            }
            is QuickShape.Ellipse -> {
                path.addOval(shape.bounds, Path.Direction.CW)
            }
            is QuickShape.Rectangle -> {
                path.addRect(shape.bounds, Path.Direction.CW)
            }
            is QuickShape.Triangle -> {
                path.moveTo(shape.p1.x, shape.p1.y)
                path.lineTo(shape.p2.x, shape.p2.y)
                path.lineTo(shape.p3.x, shape.p3.y)
                path.close()
            }
        }
        return path
    }
}
