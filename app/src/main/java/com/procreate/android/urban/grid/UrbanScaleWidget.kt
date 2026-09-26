package com.procreate.android.urban.grid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.procreate.android.urban.model.UrbanScaleConfig
import java.util.Locale

/**
 * شريط مقياس الرسم المعماري الرسمي (Official Architectural Graphic Scale Bar).
 * يرسم شريط مقياس بياني حقيقي مع:
 * - كتل متناوبة أبيض/أسود متعددة بالمتر (Alternating Black/White Blocks)
 * - تقسيم فرعي للكتلة الأولى (Subdivided First Block - 5 تقسيمات)
 * - نسبة المقياس (1:1000, 1:500, etc.)
 * - سهم الشمال (North Arrow) مع حرف N
 * - تحويل حقيقي أمتار→بكسلات بدون أي قطع اصطناعي (No coerceIn clamping)
 */
object UrbanScaleWidget {

    // ── Card background & border ──
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#333333")
    }

    // ── Scale bar paints ──
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val barBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.BLACK
    }

    // ── Text paints ──
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f
        textAlign = Paint.Align.CENTER
    }

    private val ratioTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 18f
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }

    private val metaTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        textSize = 13f
        textAlign = Paint.Align.RIGHT
    }

    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 16f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    /**
     * Selects a "nice" step in meters for the scale bar based on the current
     * pixels-per-meter so that each block is visually between 30px and 120px.
     * Returns the step in whole meters.
     */
    private fun chooseStepMeters(config: UrbanScaleConfig): Float {
        val niceSteps = floatArrayOf(1f, 2f, 5f, 10f, 20f, 25f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f)
        for (step in niceSteps) {
            val px = config.metersToPixels(step)
            if (px in 30f..120f) return step
        }
        // Fallback: use grid cell size / 2
        return (config.gridCellSizeMeters / 2f).coerceAtLeast(1f)
    }

    /**
     * رسم بطاقة شريط المقياس المعماري الرسمي
     *
     * @param canvas الـ Canvas لرسم عليه (يمكن أن يكون canvas-space أو HUD)
     * @param x إحداثي X لبداية البطاقة (أعلى يسار)
     * @param y إحداثي Y لبداية البطاقة (أعلى يسار)
     * @param config إعدادات المقياس الحضري الحالية
     */
    /** The card's footprint at the current scale, so a caller can anchor it at any corner
     * without duplicating the sizing math below (which depends on the chosen step size, and so
     * changes with pixelsPerMeter). The print ratio is annotation only; model space remains 1:1. */
    fun measureCardSize(config: UrbanScaleConfig): android.graphics.PointF {
        val stepPx = config.metersToPixels(chooseStepMeters(config))
        val barTotalWidth = stepPx * 4 + stepPx
        return android.graphics.PointF(24f * 2 + barTotalWidth + 50f + 20f, 120f)
    }

    fun renderCard(
        canvas: Canvas,
        x: Float,
        y: Float,
        config: UrbanScaleConfig
    ) {
        if (!config.isScaleCardVisible) return

        val stepMeters = chooseStepMeters(config)
        val stepPx = config.metersToPixels(stepMeters) // TRUE conversion, no clamping
        val numBlocks = 4
        val subDivisions = 5

        val barHeight = 12f
        val subBlockPx = stepPx / subDivisions

        // Card sizing - kept in sync with measureCardSize() above.
        val barTotalWidth = stepPx * numBlocks + stepPx // subdiv block + main blocks
        val cardPaddingH = 24f
        val cardPaddingV = 18f
        val northArrowWidth = 50f
        val cardWidth = cardPaddingH * 2 + barTotalWidth + northArrowWidth + 20f
        val cardHeight = 120f

        val rect = RectF(x, y, x + cardWidth, y + cardHeight)

        // 1. White background + black border (architectural drawing block style)
        canvas.drawRoundRect(rect, 6f, 6f, bgPaint)
        canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

        // ── Model-space and paper/layout scale labels ──
        val ratioText = "CAD 1:1 (m)"
        canvas.drawText(ratioText, x + cardWidth - cardPaddingH, y + 22f, ratioTitlePaint)

        // ── Grid cell info (second line) ──
        val cellM = config.gridCellSizeMeters.toInt()
        val infoText = "طباعة 1:${config.standardRatio}  |  شبكة: ${cellM}م  |  ${String.format(Locale.US, "%.2f", config.cellAreaHectares())} هكتار"
        canvas.drawText(infoText, x + cardWidth - cardPaddingH, y + 38f, metaTextPaint)

        // ── Scale Bar (positioned below the labels) ──
        val barStartX = x + cardPaddingH
        val barStartY = y + 52f

        // 2a. Subdivided first block (extends left of 0, drawn as sub-blocks)
        val subOriginX = barStartX + stepPx // The "0" mark position
        for (s in 0 until subDivisions) {
            val segStartX = barStartX + s * subBlockPx
            val segRect = RectF(segStartX, barStartY, segStartX + subBlockPx, barStartY + barHeight)
            canvas.drawRect(segRect, if (s % 2 == 0) blackPaint else whitePaint)
        }

        // 2b. Main blocks (right of 0 mark)
        for (i in 0 until numBlocks) {
            val segStartX = subOriginX + i * stepPx
            val segRect = RectF(segStartX, barStartY, segStartX + stepPx, barStartY + barHeight)
            canvas.drawRect(segRect, if (i % 2 == 0) blackPaint else whitePaint)
        }

        // 2c. Outer border of entire scale bar
        val fullBarRect = RectF(barStartX, barStartY, subOriginX + numBlocks * stepPx, barStartY + barHeight)
        canvas.drawRect(fullBarRect, barBorderPaint)

        // ── Tick marks and labels ──
        val tickTop = barStartY + barHeight
        val tickBottom = tickTop + 5f
        val textY = tickBottom + 14f

        // Sub-division ticks (small)
        for (s in 0..subDivisions) {
            val tx = barStartX + s * subBlockPx
            canvas.drawLine(tx, tickTop, tx, tickBottom, barBorderPaint)
        }

        // Subdivided block label (left of 0) - show the total meter value
        val subLabel = formatMeterLabel(stepMeters)
        canvas.drawText(subLabel, barStartX, textY, labelPaint)

        // 0 label at origin
        canvas.drawText("0", subOriginX, textY, labelPaint)

        // Main block ticks + labels
        for (i in 1..numBlocks) {
            val tx = subOriginX + i * stepPx
            canvas.drawLine(tx, tickTop, tx, tickBottom, barBorderPaint)
            val meterVal = stepMeters * i
            canvas.drawText(formatMeterLabel(meterVal), tx, textY, labelPaint)
        }

        // Unit label centered below bar
        val unitLabelPaint = Paint(labelPaint).apply { textSize = 11f; textAlign = Paint.Align.CENTER }
        val barCenterX = (barStartX + subOriginX + numBlocks * stepPx) / 2f
        canvas.drawText("(م/متر)", barCenterX, textY + 14f, unitLabelPaint)

        // ── North Arrow (right side of card) ──
        val northCX = x + cardWidth - cardPaddingH - northArrowWidth / 2f
        val northBaseY = y + cardHeight - 22f
        val northTipY = y + 48f
        val arrowHalfW = 8f

        // Arrow body (filled triangle pointing up)
        val arrowPath = Path().apply {
            moveTo(northCX, northTipY)
            lineTo(northCX - arrowHalfW, northBaseY)
            lineTo(northCX + arrowHalfW, northBaseY)
            close()
        }
        // Left half black, right half white (classic architectural north arrow)
        canvas.save()
        canvas.clipRect(northCX - arrowHalfW - 2, northTipY - 2, northCX, northBaseY + 2)
        canvas.drawPath(arrowPath, blackPaint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(northCX, northTipY - 2, northCX + arrowHalfW + 2, northBaseY + 2)
        canvas.drawPath(arrowPath, whitePaint)
        canvas.drawPath(arrowPath, barBorderPaint)
        canvas.restore()

        // Re-draw full outline on top
        canvas.drawPath(arrowPath, barBorderPaint)

        // "N" letter above arrow
        canvas.drawText("N", northCX, northTipY - 6f, northPaint)
    }

    private fun formatMeterLabel(meters: Float): String {
        return if (meters >= 1000f) {
            String.format(Locale.US, "%.0fكم", meters / 1000f)
        } else if (meters == meters.toInt().toFloat()) {
            "${meters.toInt()}"
        } else {
            String.format(Locale.US, "%.1f", meters)
        }
    }
}
