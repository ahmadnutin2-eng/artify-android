package com.procreate.android.urban.legend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import com.procreate.android.urban.model.LegendItem
import com.procreate.android.urban.model.UrbanCategory
import com.procreate.android.urban.model.UrbanToolType

/**
 * محرك رسم مفتاح الخريطة المعماري (Map Legend Renderer)
 * يولد بطاقة مفتاح خريطة متكاملة مطابقة للمخططات المرفقة، جاهزة للإلصاق على المشروع.
 */
object LegendRenderer {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#333333")
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 22f
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }

    private val itemTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    /**
     * رسم مفتاح الخريطة في موضع محدد على الـ Canvas
     */
    fun renderLegendCard(
        canvas: Canvas,
        x: Float,
        y: Float,
        items: List<LegendItem>,
        cardWidth: Float = 280f
    ): Float {
        val visibleItems = items.filter { it.isVisibleInLegend }
        if (visibleItems.isEmpty()) return 0f

        val itemHeight = 32f
        val cardHeight = 60f + (visibleItems.size * itemHeight)
        val rect = RectF(x, y, x + cardWidth, y + cardHeight)

        // 1. الخلفية البيضاء والإطار الأسود الأنيق
        canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
        canvas.drawRoundRect(rect, 8f, 8f, borderPaint)

        // 2. العنوان: "مفتاح الخريطة:"
        canvas.drawText("مفتاح الخريطة:", x + cardWidth - 16f, y + 34f, titlePaint)

        // 3. رسم كل عنصر برمزه ولونه ومسماه
        var currentY = y + 66f
        for (item in visibleItems) {
            val symbolX = x + 30f
            val symbolY = currentY - 6f

            renderLegendSymbol(canvas, symbolX, symbolY, item)

            // نص اسم العنصر
            canvas.drawText(item.nameAr, x + cardWidth - 16f, currentY, itemTextPaint)

            currentY += itemHeight
        }

        return cardHeight
    }

    /**
     * رسم مصغر الرمز في مفتاح الخريطة
     */
    private fun renderLegendSymbol(canvas: Canvas, cx: Float, cy: Float, item: LegendItem) {
        symbolPaint.color = item.color

        when (item.toolType.category) {
            UrbanCategory.AXES -> {
                // خط ومثلث سهم
                symbolPaint.style = Paint.Style.STROKE
                symbolPaint.strokeWidth = 4f
                if (item.toolType == UrbanToolType.PRIMARY_AXIS) {
                    symbolPaint.pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
                } else {
                    symbolPaint.pathEffect = null
                }
                canvas.drawLine(cx - 16f, cy, cx + 16f, cy, symbolPaint)

                // رأس سهم
                symbolPaint.style = Paint.Style.FILL
                symbolPaint.pathEffect = null
                canvas.drawCircle(cx + 16f, cy, 5f, symbolPaint)
            }
            UrbanCategory.HATCHING, UrbanCategory.BUILDINGS -> {
                // مستطيل ملون / مهشر
                symbolPaint.style = Paint.Style.FILL
                val rect = RectF(cx - 16f, cy - 8f, cx + 16f, cy + 8f)
                canvas.drawRect(rect, symbolPaint)
                symbolPaint.style = Paint.Style.STROKE
                symbolPaint.strokeWidth = 1.5f
                symbolPaint.color = Color.BLACK
                canvas.drawRect(rect, symbolPaint)
            }
            UrbanCategory.BOUNDARIES -> {
                // خط متقطع أحمر
                symbolPaint.style = Paint.Style.STROKE
                symbolPaint.strokeWidth = 3f
                symbolPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)
                canvas.drawLine(cx - 16f, cy, cx + 16f, cy, symbolPaint)
                symbolPaint.pathEffect = null
            }
            else -> {
                // رمز نقطي
                symbolPaint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, 7f, symbolPaint)
            }
        }
    }

    /**
     * إنشاء صورة Bitmap كاملة لمفتاح الخريطة لاستخدامها كطبقة أو للمشاركة
     */
    fun createLegendBitmap(items: List<LegendItem>): Bitmap? {
        val visibleItems = items.filter { it.isVisibleInLegend }
        if (visibleItems.isEmpty()) return null

        val cardWidth = 320
        val cardHeight = (70 + visibleItems.size * 36)
        val bitmap = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        renderLegendCard(canvas, 0f, 0f, visibleItems)
        return bitmap
    }
}
