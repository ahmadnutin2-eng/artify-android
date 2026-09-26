package com.procreate.android.urban.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.procreate.android.R
import com.procreate.android.ui.common.SmoothSwitch
import com.procreate.android.ui.common.PanelUi

/**
 * نافذة التصدير المعماري عالي الدقة (Advanced Urban CAD Export Dialog).
 * تتيح تصدير المخطط ودراسات الموقع بصيغ (PDF, PNG, JPEG) وبخلفية شفافة أو بيضاء،
 * مع خيار تصدير كامل اللوحة أو تحديد كادر مخصص.
 */
class UrbanExportDialog(
    private val onExportConfirmed: (format: ExportFormat, isTransparent: Boolean, isFullCanvas: Boolean, jpegQuality: Int, includeOverlays: Boolean) -> Unit
) : DialogFragment() {

    /** DXF: a real, editable AutoCAD drawing (layers/colours/lines, not a flattened picture) built
     * straight from the Urban Design element data - see UrbanDxfExporter. */
    enum class ExportFormat { PDF, PNG, JPEG, DXF }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        fun dp(v: Int) = PanelUi.dp(ctx, v)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
            setBackgroundResource(R.drawable.bg_panel_rounded)
        }

        // 1. العنوان
        val title = TextView(ctx).apply {
            text = "تصدير المخطط المعماري ودراسة الموقع"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "تصدير عالي الدقة بمواصفات تخطيطية معتمدة قابلة للطباعة والتداول"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(subtitle)

        // 2. صيغة التصدير (PDF / PNG / JPEG)
        val formatLabel = TextView(ctx).apply {
            text = "صيغة المستند أو الصورة:"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(formatLabel)

        val rgFormat = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val rbPdf = RadioButton(ctx).apply {
            text = "PDF (معماري)"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbPng = RadioButton(ctx).apply {
            text = "PNG (عالي الدقة)"
            setTextColor(Color.WHITE)
            isChecked = true
            id = View.generateViewId()
        }
        val rbJpg = RadioButton(ctx).apply {
            text = "JPEG"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbDxf = RadioButton(ctx).apply {
            text = "DXF (AutoCAD - قابل للتعديل)"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        rgFormat.addView(rbPdf)
        rgFormat.addView(rbPng)
        rgFormat.addView(rbJpg)
        rgFormat.addView(rbDxf)
        root.addView(rgFormat)

        val dxfNote = TextView(ctx).apply {
            text = "DXF: يصدّر عناصر التخطيط الحضري (المحاور والحدود والتهشير والرموز) كخطوط وطبقات حقيقية قابلة للتعديل في AutoCAD؛ طبقات الرسم الحر تُصدَّر كصورة ملحقة بجانبه."
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(10))
            visibility = View.GONE
        }
        root.addView(dxfNote)
        // 3. خيارات لون الخلفية (شفافة / بيضاء)
        val bgLabel = TextView(ctx).apply {
            text = "نوع ولون الخلفية:"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(bgLabel)

        val rgBg = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val rbTransparent = RadioButton(ctx).apply {
            text = "شفافة (Transparent)"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbWhite = RadioButton(ctx).apply {
            text = "بيضاء مصمتة (White)"
            setTextColor(Color.WHITE)
            isChecked = true
            id = View.generateViewId()
        }
        rgBg.addView(rbWhite)
        rgBg.addView(rbTransparent)
        root.addView(rgBg)

        // 4. نطاق التصدير (كامل اللوحة / نافذة العرض الحالية)
        val scopeLabel = TextView(ctx).apply {
            text = "نطاق مساحة التصدير:"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(scopeLabel)

        val rgScope = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        }
        val rbFull = RadioButton(ctx).apply {
            text = "كامل اللوحة (Full Canvas)"
            setTextColor(Color.WHITE)
            isChecked = true
            id = View.generateViewId()
        }
        val rbCrop = RadioButton(ctx).apply {
            text = "نافذة العرض الحالية (Current View)"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        rgScope.addView(rbFull)
        rgScope.addView(rbCrop)
        root.addView(rgScope)

        // 5. تضمين ملحقات المخطط
        val chkOverlays = SmoothSwitch(ctx).apply {
            text = "تضمين مفتاح الخريطة والجداول وشريط المقياس في الملف"
            setTextColor(Color.WHITE)
            isChecked = true
            textSize = 13f
            setPadding(dp(4), 0, 0, dp(14))
        }
        root.addView(chkOverlays)

        rgFormat.setOnCheckedChangeListener { _, checkedId ->
            val isDxf = checkedId == rbDxf.id
            dxfNote.visibility = if (isDxf) View.VISIBLE else View.GONE
            chkOverlays.text = if (isDxf) {
                "تضمين مفتاح العناصر كطبقة نصية قابلة للتعديل"
            } else {
                "تضمين مفتاح الخريطة والجداول وشريط المقياس في الملف"
            }
            rgBg.isEnabled = !isDxf
            rbWhite.isEnabled = !isDxf
            rbTransparent.isEnabled = !isDxf
            rgScope.isEnabled = !isDxf
            rbFull.isEnabled = !isDxf
            rbCrop.isEnabled = !isDxf
        }

        // 6. زر البدء والتصدير
        val btnExport = Button(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            text = "تصدير المستند الآن"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_btn_primary)
            setOnClickListener {
                val format = when {
                    rbPdf.isChecked -> ExportFormat.PDF
                    rbJpg.isChecked -> ExportFormat.JPEG
                    rbDxf.isChecked -> ExportFormat.DXF
                    else -> ExportFormat.PNG
                }
                val isTransparent = rbTransparent.isChecked
                val isFull = rbFull.isChecked
                val includeOverlays = chkOverlays.isChecked

                onExportConfirmed(format, isTransparent, isFull, 95, includeOverlays)
                dismiss()
            }
        }
        root.addView(btnExport)

        return AlertDialog.Builder(ctx)
            .setView(root)
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }
}
