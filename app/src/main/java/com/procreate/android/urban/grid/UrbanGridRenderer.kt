package com.procreate.android.urban.grid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.procreate.android.urban.model.UrbanScaleConfig
import java.util.Locale
import kotlin.math.ceil

/**
 * محرك رسم شبكة التخطيط الحضري المتعامدة (Urban Ortho Grid).
 * يحسب المسافات بدقة بناءً على معامل المقياس وحجم المربعات بالمتر.
 * يرسم أرقام إحداثيات الأبعاد (Dimension Coordinates) على حواف الشبكة.
 */
object UrbanGridRenderer {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.parseColor("#88AAAAAA")
    }

    private val coordLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 12f
        textAlign = Paint.Align.CENTER
    }

    private val coordLabelVertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 12f
        textAlign = Paint.Align.RIGHT
    }

    /**
     * @param screenScale current on-screen zoom (DrawingView's canvasMatrix scale). Coordinate
     * labels are drawn in the same canvas-space this whole call operates in, so without
     * counter-scaling their text size they'd grow to fill the screen at high zoom and shrink to
     * unreadable specks zoomed out - dividing by screenScale keeps them a constant, legible size
     * on screen regardless of how far in or out the sheet is zoomed, the way any CAD tool's
     * annotations behave.
     */
    fun renderGrid(
        canvas: Canvas,
        canvasWidth: Float,
        canvasHeight: Float,
        config: UrbanScaleConfig,
        screenScale: Float = 1f
    ) {
        if (!config.isGridVisible) return

        val cellPx = config.metersToPixels(config.gridCellSizeMeters)
        val screenCellPx = cellPx * if (screenScale > 0.001f) screenScale else 1f
        if (cellPx < 10f) return // تجنب الكثافة العالية جداً عند التصغير الشديد

        if (screenCellPx < 8f) return

        gridPaint.color = config.gridColor
        gridPaint.alpha = config.gridAlpha

        val safeScale = if (screenScale > 0.001f) screenScale else 1f
        coordLabelPaint.textSize = 12f / safeScale
        coordLabelVertPaint.textSize = 12f / safeScale
        val labelStep = ceil(60f / screenCellPx).toInt().coerceAtLeast(1)

        // 1. رسم الخطوط الرأسية
        var x = 0f
        var colIndex = 0
        while (x <= canvasWidth) {
            val paintToUse = if (colIndex % 5 == 0) axisPaint else gridPaint
            canvas.drawLine(x, 0f, x, canvasHeight, paintToUse)
            x += cellPx
            colIndex++
        }

        // 2. رسم الخطوط الأفقية
        var y = 0f
        var rowIndex = 0
        while (y <= canvasHeight) {
            val paintToUse = if (rowIndex % 5 == 0) axisPaint else gridPaint
            canvas.drawLine(0f, y, canvasWidth, y, paintToUse)
            y += cellPx
            rowIndex++
        }

        // 3. رسم أرقام الإحداثيات على الحافة العلوية (Dimension Coordinates - Top Edge)
        // Offsets are counter-scaled too, for the same reason as the text size - a fixed 4px
        // canvas-space gap would visually vanish zoomed out and become a huge gap zoomed in.
        val topOffset = 4f / safeScale
        val leftOffset = 6f / safeScale
        val leftBaselineOffset = 4f / safeScale
        x = 0f
        colIndex = 0
        val cellMeters = config.gridCellSizeMeters
        while (x <= canvasWidth) {
            if (colIndex % labelStep == 0) {
                val meters = colIndex * cellMeters
                val label = formatCoordLabel(meters)
            // Top edge label: draw above the grid, offset upward
                canvas.drawText(label, x, -topOffset, coordLabelPaint)
            }
            x += cellPx
            colIndex++
        }

        // 4. رسم أرقام الإحداثيات على الحافة اليسرى (Left Edge)
        y = 0f
        rowIndex = 0
        while (y <= canvasHeight) {
            if (rowIndex % labelStep == 0) {
                val meters = rowIndex * cellMeters
                val label = formatCoordLabel(meters)
            // Left edge label: draw to the left of the grid
                canvas.drawText(label, -leftOffset, y + leftBaselineOffset, coordLabelVertPaint)
            }
            y += cellPx
            rowIndex++
        }
    }

    /**
     * Format a coordinate label in meters: 0, 50, 100, 150...
     * For values >= 1000 show km.
     */
    private fun formatCoordLabel(meters: Float): String {
        return if (meters >= 1000f) {
            String.format(Locale.US, "%.1fكم", meters / 1000f)
        } else {
            "${meters.toInt()}م"
        }
    }
}
