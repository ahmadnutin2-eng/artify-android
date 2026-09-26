package com.procreate.android.urban.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.model.UrbanScaleConfig

/**
 * نافذة حوارية لمعايرة مقياس الرسم (Scale Calibration).
 * تمكن المستخدم من إدخال الطول الحقيقي بين نقطتين أو اختيار مقياس تخطيطي معتمد.
 */
class ScaleCalibrationDialog(
    private val currentConfig: UrbanScaleConfig,
    private val onScaleUpdated: (UrbanScaleConfig) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val builder = AlertDialog.Builder(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PanelUi.dp(context, 20), PanelUi.dp(context, 16), PanelUi.dp(context, 20), PanelUi.dp(context, 8))
        }

        val titleView = TextView(context).apply {
            text = "معايرة مقياس الرسم (Scale Calibration)"
            textSize = 18f
            setTextColor(context.getColor(R.color.white))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, PanelUi.dp(context, 12))
        }
        root.addView(titleView)

        val descView = TextView(context).apply {
            text = "وفق معايير التخطيط الحضري وأنظمة الـ GIS:\nأدخل الطول الحقيقي بوحدة المتر لحساب معامل التحويل، أو حدد حجم مربع الشبكة المعتمد لدراستك."
            textSize = 14f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, PanelUi.dp(context, 16))
        }
        root.addView(descView)

        // إدخال حجم مربع الشبكة بالمتر
        val gridLabel = TextView(context).apply {
            text = "طول ضلع مربع الشبكة (متر):"
            textSize = 14f
            setTextColor(context.getColor(R.color.white))
        }
        root.addView(gridLabel)

        val inputGridSize = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(currentConfig.gridCellSizeMeters.toInt().toString())
            setTextColor(context.getColor(R.color.white))
        }
        root.addView(inputGridSize)

        // إدخال معامل البكسل لكل متر
        val ppmLabel = TextView(context).apply {
            text = "معامل المقياس (Pixels per Meter):"
            textSize = 14f
            setTextColor(context.getColor(R.color.white))
            setPadding(0, PanelUi.dp(context, 12), 0, 0)
        }
        root.addView(ppmLabel)

        val inputPpm = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(currentConfig.pixelsPerMeter.toString())
            setTextColor(context.getColor(R.color.white))
        }
        root.addView(inputPpm)

        val printRatioLabel = TextView(context).apply {
            text = "مقياس الطباعة/الـ Layout (1 : N):"
            textSize = 14f
            setTextColor(context.getColor(R.color.white))
            setPadding(0, PanelUi.dp(context, 12), 0, 0)
        }
        root.addView(printRatioLabel)

        val inputPrintRatio = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentConfig.standardRatio.toString())
            hint = "مثال: 1000"
            setTextColor(context.getColor(R.color.white))
        }
        root.addView(inputPrintRatio)

        root.addView(TextView(context).apply {
            text = "ملاحظة: القياس وDXF يستخدمان المتر الحقيقي 1:1؛ هذه النسبة لوسم الطباعة فقط."
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, PanelUi.dp(context, 8), 0, 0)
        })

        builder.setView(root)
            .setPositiveButton("حفظ وتطبيق") { _, _ ->
                val gridSize = inputGridSize.text.toString().toFloatOrNull() ?: 50f
                val ppm = inputPpm.text.toString().toFloatOrNull() ?: 5f
                val printRatio = inputPrintRatio.text.toString().toIntOrNull()
                currentConfig.gridCellSizeMeters = gridSize.coerceAtLeast(1f)
                currentConfig.pixelsPerMeter = ppm.takeIf { it.isFinite() && it > 0f }
                    ?: currentConfig.pixelsPerMeter
                currentConfig.standardRatio = printRatio?.takeIf { it > 0 }
                    ?: currentConfig.standardRatio
                currentConfig.isCalibrated = true
                onScaleUpdated(currentConfig)
                Toast.makeText(context, "تم ضبط المقياس: المربع = ${gridSize.toInt()}م × ${gridSize.toInt()}م", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)

        return builder.create().apply {
            window?.setBackgroundDrawableResource(R.drawable.bg_panel_rounded)
        }
    }
}
