package com.procreate.android.urban.tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanToolType
import kotlin.math.cos
import kotlin.math.sin

/**
 * محرك رسم رموز البنية التحتية ومحددات التشوّه البصري وحدود الموقع.
 */
object UrbanSymbolRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 18f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun renderMarker(canvas: Canvas, marker: UrbanElement.PointMarker) {
        val pos = marker.position
        val r = marker.radius

        when (marker.toolType) {
            UrbanToolType.STATION_BADGE -> {
                fillPaint.color = Color.WHITE
                strokePaint.color = marker.color
                strokePaint.strokeWidth = 3f
                canvas.drawCircle(pos.x, pos.y, r, fillPaint)
                canvas.drawCircle(pos.x, pos.y, r, strokePaint)
                textPaint.color = marker.color
                textPaint.textSize = r.coerceAtLeast(14f)
                val fontMetrics = textPaint.fontMetrics
                val textY = pos.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.drawText(marker.label ?: "A", pos.x, textY, textPaint)
            }

            // عمود إنارة: دائرة بمركز وأشعة شمسية سماوية
            UrbanToolType.INFRA_LIGHT -> {
                strokePaint.color = marker.color
                strokePaint.strokeWidth = 2.5f
                fillPaint.color = marker.color
                canvas.drawCircle(pos.x, pos.y, r * 0.45f, fillPaint)
                // 8 أشعة
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val x1 = pos.x + cos(angle).toFloat() * (r * 0.65f)
                    val y1 = pos.y + sin(angle).toFloat() * (r * 0.65f)
                    val x2 = pos.x + cos(angle).toFloat() * (r * 1.25f)
                    val y2 = pos.y + sin(angle).toFloat() * (r * 1.25f)
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
            }

            // مانهول صرف صحي: دائرة برتقالية مفرغة بداخلها حلقة مركزية
            UrbanToolType.INFRA_MANHOLE -> {
                strokePaint.color = marker.color
                strokePaint.strokeWidth = 3f
                canvas.drawCircle(pos.x, pos.y, r, strokePaint)
                fillPaint.color = marker.color
                canvas.drawCircle(pos.x, pos.y, r * 0.35f, fillPaint)
            }

            // خزان مياه: دائرتان متحدتا المركز بلون بنفسجي
            UrbanToolType.INFRA_WATER -> {
                strokePaint.color = marker.color
                strokePaint.strokeWidth = 3f
                canvas.drawCircle(pos.x, pos.y, r, strokePaint)
                canvas.drawCircle(pos.x, pos.y, r * 0.55f, strokePaint)
                fillPaint.color = marker.color
                canvas.drawCircle(pos.x, pos.y, r * 0.25f, fillPaint)
            }

            // محول وكابينة كهرباء: مربع أخضر بحدود داكنة
            UrbanToolType.INFRA_ELECTRIC -> {
                fillPaint.color = marker.color
                strokePaint.color = Color.DKGRAY
                strokePaint.strokeWidth = 2f
                val rect = RectF(pos.x - r, pos.y - r, pos.x + r, pos.y + r)
                canvas.drawRoundRect(rect, 4f, 4f, fillPaint)
                canvas.drawRoundRect(rect, 4f, 4f, strokePaint)
            }

            // حاوية قمامة غير ملائمة (تشوه بصري): مربع بنفسجي داكن
            UrbanToolType.POLLUTION_TRASH -> {
                fillPaint.color = marker.color
                val rect = RectF(pos.x - r * 0.9f, pos.y - r * 0.9f, pos.x + r * 0.9f, pos.y + r * 0.9f)
                canvas.drawRect(rect, fillPaint)
            }

            // مظلة سيارة غير مناسبة: مستطيل أزرق سماوي
            UrbanToolType.POLLUTION_SHED -> {
                fillPaint.color = marker.color
                val rect = RectF(pos.x - r * 1.3f, pos.y - r * 0.8f, pos.x + r * 1.3f, pos.y + r * 0.8f)
                canvas.drawRect(rect, fillPaint)
            }

            // عمود كهرباء منتشر غير منتظم: نجمة حمراء مشعة
            UrbanToolType.POLLUTION_POLE -> {
                strokePaint.color = marker.color
                strokePaint.strokeWidth = 3f
                fillPaint.color = marker.color
                canvas.drawCircle(pos.x, pos.y, r * 0.4f, fillPaint)
                for (i in 0 until 6) {
                    val angle = Math.toRadians((i * 60).toDouble())
                    val x1 = pos.x + cos(angle).toFloat() * (r * 0.5f)
                    val y1 = pos.y + sin(angle).toFloat() * (r * 0.5f)
                    val x2 = pos.x + cos(angle).toFloat() * (r * 1.3f)
                    val y2 = pos.y + sin(angle).toFloat() * (r * 1.3f)
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
            }

            else -> {
                // رمز دائري افتراضي
                fillPaint.color = marker.color
                canvas.drawCircle(pos.x, pos.y, r, fillPaint)
            }
        }
    }

    /**
     * رسم خطوط الحدود المتقطعة بنقاط المحطات المرقمة (كما في خريطة حدود الموقع)
     */
    fun renderBoundary(canvas: Canvas, boundary: UrbanElement.BoundaryPath) {
        val pts = boundary.vertices
        if (pts.size < 2) return

        boundaryPaint.color = boundary.color
        boundaryPaint.strokeWidth = boundary.strokeWidth
        boundaryPaint.pathEffect = if (boundary.toolType == UrbanToolType.SITE_BOUNDARY) {
            DashPathEffect(floatArrayOf(18f, 10f), 0f)
        } else null

        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            path.lineTo(pts[i].x, pts[i].y)
        }
        if (boundary.toolType == UrbanToolType.SITE_BOUNDARY && pts.size >= 3) path.close()
        canvas.drawPath(path, boundaryPaint)

        if (boundary.toolType == UrbanToolType.POLLUTION_WIRES) {
            fillPaint.color = Color.WHITE
            strokePaint.color = boundary.color
            strokePaint.strokeWidth = 2f
            val nodeRadius = (boundary.strokeWidth * 0.8f).coerceIn(5f, 12f)
            pts.forEach { point ->
                canvas.drawCircle(point.x, point.y, nodeRadius, fillPaint)
                canvas.drawCircle(point.x, point.y, nodeRadius, strokePaint)
            }
        }

        // رسم نقاط الزوايا مع الترقيم - عند مسافة مخصصة (nodeSpacingPx > 0) تُحسب مواضع العقد
        // المعروضة بشكل منفصل تمامًا عن pts، فتغيير التباعد لا يغيّر شكل الخط المرسوم أبدًا
        // (كان تغيير التباعد يعيد توليد pts نفسها، فيغيّر شكل الحدود المرسومة فعليًا).
        if (boundary.showNodeNumbers) {
            val nodeRadius = 14f
            fillPaint.color = Color.WHITE
            strokePaint.color = boundary.color
            strokePaint.strokeWidth = 2.5f

            val nodePositions = if (boundary.nodeSpacingPx > 1f) resampleForDisplay(pts, boundary.nodeSpacingPx) else pts
            for (i in nodePositions.indices) {
                val pt = nodePositions[i]
                canvas.drawCircle(pt.x, pt.y, nodeRadius, fillPaint)
                canvas.drawCircle(pt.x, pt.y, nodeRadius, strokePaint)

                val fontMetrics = textPaint.fontMetrics
                val textY = pt.y - (fontMetrics.ascent + fontMetrics.descent) / 2f
                textPaint.textSize = 14f
                canvas.drawText((i + 1).toString(), pt.x, textY, textPaint)
            }
        }
    }

    /** Display-only node positions for [points]' real geometry at a target spacing of [stepPx] -
     * every real vertex is always included no matter what, so a numbered node can never miss the
     * path's actual corners/kinks; extra points are added only strictly *between* two vertices
     * that are farther apart than [stepPx], evenly subdividing that one straight segment. Since a
     * point placed along a straight segment between two real vertices sits exactly on the line
     * already drawn there, this never moves the rendered path itself - asking for a smaller
     * spacing than the path's own vertices just adds more markers along the existing straight
     * runs, and asking for a larger one collapses back down to exactly the original vertices
     * (nothing to subdivide), never past them. Internal (not private) so the DXF exporter can
     * reuse the exact same node placement the on-screen renderer uses, instead of re-deriving it
     * separately and risking the two drifting apart. */
    internal fun resampleForDisplay(points: List<PointF>, stepPx: Float): List<PointF> {
        if (points.size < 2) return points
        val result = mutableListOf(points[0])
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val segLen = kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            if (stepPx > 1f && segLen > stepPx) {
                val subdivisions = (segLen / stepPx).toInt()
                for (s in 1..subdivisions) {
                    val dist = s * stepPx
                    if (dist >= segLen - 1f) break // this close to b, let the real vertex below stand in for it
                    val t = dist / segLen
                    result.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
                }
            }
            result.add(b)
        }
        return result
    }
}
