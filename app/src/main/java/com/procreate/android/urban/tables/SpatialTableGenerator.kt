package com.procreate.android.urban.tables

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.procreate.android.urban.model.UrbanElement
import java.util.Locale

/**
 * مولد الجداول الإحصائية المكانية التلقائي (Spatial Tables Generator)
 * ينتج جداول حصر المساحات والنسب المئوية وأطوال الطرق مطابقة للمخططات.
 */
object SpatialTableGenerator {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#424242") // شريط العنوان رمادي داكن
    }

    private val altRowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5F5F5") // صف متبادل رمادي فاتح
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#CCCCCC")
    }

    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val cellTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        textSize = 14f
        textAlign = Paint.Align.CENTER
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 18f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    data class TableRow(
        val label: String,
        val valueFormatted: String,
        val percentageFormatted: String
    )

    /**
     * توليد صورة جدول الساحات العامة من قائمة المضلعات المهشرة
     */
    fun generatePlazaTableBitmap(plazas: List<UrbanElement.HatchPolygon>): Bitmap {
        val rows = mutableListOf<TableRow>()
        var totalArea = 0f

        for (p in plazas) {
            totalArea += p.areaSqMeters
        }
        val safeTotal = if (totalArea < 0.1f) 1f else totalArea

        for (p in plazas) {
            val name = p.plazaName ?: ("ساحة رقم ${p.plazaNumber ?: (rows.size + 1)}")
            val areaStr = String.format(Locale.US, "%,.2f", p.areaSqMeters)
            val pct = (p.areaSqMeters / safeTotal) * 100f
            val pctStr = String.format(Locale.US, "%.2f%%", pct)
            rows.add(TableRow(name, areaStr, pctStr))
        }

        // صف الإجمالي
        val totalAreaStr = String.format(Locale.US, "%,.2f", totalArea)
        val totalRow = TableRow("الإجمالي", totalAreaStr, "100.00%")

        return renderTable(
            title = "خريطة الساحات العامة",
            col1Title = "النسبة (%)",
            col2Title = "المساحة (م²)",
            col3Title = "التصنيف",
            rows = rows,
            totalRow = totalRow
        )
    }

    /**
     * توليد صورة جدول المساحات الأخرى (كل ما ليس ساحة عامة: ممرات أسفلتية وترابية، مزارع،
     * مبانٍ...) من قائمة (تسمية، مساحة) ديناميكية بدلاً من تصنيفين ثابتين فقط - هذا هو ما كان
     * يجعل الجدول "جامدًا لا يتغير" فعليًا: كان يحصر الأسفلت والتراب فقط ويتجاهل أي نوع تهشير
     * آخر (كالمزارع والمباني) كليًا مهما أُضيف منه، فلا يظهر ولا يُحدَّث الجدول لتلك العناصر.
     * الآن أي نوع تهشير جديد غير الساحات يُجمع ويُدرج هنا تلقائيًا.
     */
    fun generateOtherAreasTableBitmap(areasByType: List<Pair<String, Float>>): Bitmap {
        val total = areasByType.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.1f)
        val rows = areasByType.map { (label, area) ->
            val pct = (area / total) * 100f
            TableRow(label, String.format(Locale.US, "%,.2f", area), String.format(Locale.US, "%.1f%%", pct))
        }
        val totalRow = TableRow("الإجمالي", String.format(Locale.US, "%,.2f", total), "100.0%")

        return renderTable(
            title = "جدول المساحات الأخرى",
            col1Title = "النسبة",
            col2Title = "المساحة (م²)",
            col3Title = "التصنيف",
            rows = rows,
            totalRow = totalRow
        )
    }

    /**
     * رسم الجدول الهندسي متطابقاً مع التصميم المعروض بالصور
     */
    private fun renderTable(
        title: String,
        col1Title: String,
        col2Title: String,
        col3Title: String,
        rows: List<TableRow>,
        totalRow: TableRow
    ): Bitmap {
        val tableWidth = 420
        val rowHeight = 28f
        val headerHeight = 32f
        val titleSpace = 38f
        val totalRowsCount = rows.size + 2 // header + rows + total
        val tableHeight = (titleSpace + totalRowsCount * rowHeight + 16).toInt()

        val bitmap = Bitmap.createBitmap(tableWidth, tableHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. العنوان الرئيسي
        canvas.drawText(title, tableWidth / 2f, 26f, titlePaint)

        val tableTop = titleSpace
        val tableBottom = tableTop + totalRowsCount * rowHeight
        val col1Width = 100f // النسبة
        val col2Width = 130f // المساحة / الطول
        val col3Width = tableWidth - col1Width - col2Width // التصنيف

        // 2. ترويسة الجدول
        val headerRect = RectF(0f, tableTop, tableWidth.toFloat(), tableTop + headerHeight)
        canvas.drawRect(headerRect, headerBgPaint)

        val headerTextY = tableTop + 21f
        canvas.drawText(col1Title, col1Width / 2f, headerTextY, headerTextPaint)
        canvas.drawText(col2Title, col1Width + (col2Width / 2f), headerTextY, headerTextPaint)
        canvas.drawText(col3Title, col1Width + col2Width + (col3Width / 2f), headerTextY, headerTextPaint)

        // 3. الصفوف
        var curY = tableTop + headerHeight
        for (i in rows.indices) {
            val row = rows[i]
            val rowRect = RectF(0f, curY, tableWidth.toFloat(), curY + rowHeight)
            if (i % 2 == 1) {
                canvas.drawRect(rowRect, altRowBgPaint)
            }
            val textY = curY + 19f
            canvas.drawText(row.percentageFormatted, col1Width / 2f, textY, cellTextPaint)
            canvas.drawText(row.valueFormatted, col1Width + (col2Width / 2f), textY, cellTextPaint)
            canvas.drawText(row.label, col1Width + col2Width + (col3Width / 2f), textY, cellTextPaint)

            canvas.drawLine(0f, curY + rowHeight, tableWidth.toFloat(), curY + rowHeight, gridLinePaint)
            curY += rowHeight
        }

        // 4. صف الإجمالي
        val totalRect = RectF(0f, curY, tableWidth.toFloat(), curY + rowHeight)
        canvas.drawRect(totalRect, altRowBgPaint)
        val boldCellPaint = Paint(cellTextPaint).apply { isFakeBoldText = true }
        val textY = curY + 19f
        canvas.drawText(totalRow.percentageFormatted, col1Width / 2f, textY, boldCellPaint)
        canvas.drawText(totalRow.valueFormatted, col1Width + (col2Width / 2f), textY, boldCellPaint)
        canvas.drawText(totalRow.label, col1Width + col2Width + (col3Width / 2f), textY, boldCellPaint)

        // 5. حدود الأعمدة والإطار الخارجي
        canvas.drawLine(col1Width, tableTop, col1Width, curY + rowHeight, gridLinePaint)
        canvas.drawLine(col1Width + col2Width, tableTop, col1Width + col2Width, curY + rowHeight, gridLinePaint)
        val outerBorder = RectF(0f, tableTop, tableWidth.toFloat(), curY + rowHeight)
        val outerPaint = Paint(gridLinePaint).apply { strokeWidth = 1.5f; color = Color.parseColor("#888888") }
        canvas.drawRect(outerBorder, outerPaint)

        return bitmap
    }
}
