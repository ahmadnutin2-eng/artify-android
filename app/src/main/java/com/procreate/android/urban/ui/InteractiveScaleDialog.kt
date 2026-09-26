package com.procreate.android.urban.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.model.DistanceUnit
import com.procreate.android.urban.model.UrbanScaleConfig

/**
 * نافذة حوارية تفاعلية تظهر بعد قياس مسافة بالقلم (2-Point Calibration Pen).
 * تتيح إدخال القيمة الحقيقية واختيار الوحدة (cm, m, km) وتحديد حجم شبكة المربعات.
 */
class InteractiveScaleDialog(
    private val pixelDistance: Float,
    private val config: UrbanScaleConfig,
    private val onScaleConfirmed: (UrbanScaleConfig, Boolean) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        fun dp(v: Int) = PanelUi.dp(ctx, v)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(14))
            setBackgroundResource(R.drawable.bg_panel_rounded)
        }

        // العنوان
        val title = TextView(ctx).apply {
            text = "ضبط مقياس الرسم بالقلم"
            textSize = 17f
            setTextColor(ctx.getColor(R.color.white))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(title)

        // عرض المسافة المقاسة بالبكسل
        val measuredInfo = TextView(ctx).apply {
            text = "المسافة المقاسة على اللوحة: %.1f بكسل".format(pixelDistance)
            textSize = 13f
            setTextColor(ctx.getColor(R.color.procreate_accent))
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(measuredInfo)

        // سطر إدخال المسافة الحقيقية والوحدة
        val realDistLabel = TextView(ctx).apply {
            text = "أدخل الطول الحقيقي للمسافة المقاسة:"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_secondary))
        }
        root.addView(realDistLabel)

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }

        val inputVal = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (config.isCalibrated) "%.1f".format(config.pixelsToMeters(pixelDistance)) else "25.0")
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_field_outline)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 15f
        }
        inputRow.addView(inputVal)

        val spacer = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        }
        inputRow.addView(spacer)

        val unitSpinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(44))
            val units = DistanceUnit.values()
            val adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                units.map { it.titleAr }
            )
            this.adapter = adapter
            setSelection(DistanceUnit.METER.ordinal)
            setBackgroundResource(R.drawable.bg_field_outline)
        }
        inputRow.addView(unitSpinner)
        root.addView(inputRow)

        // إدخال حجم مربع الشبكة بالمتر
        val gridLabel = TextView(ctx).apply {
            text = "حجم مربع الشبكة التخطيطية (متر):"
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(2))
        }
        root.addView(gridLabel)

        val inputGridSize = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(config.gridCellSizeMeters.toInt().toString())
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_field_outline)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 15f
        }
        root.addView(inputGridSize)

        val calibrationNote = TextView(ctx).apply {
            text = "تُطبّق المعايرة على القياس والتصدير دون تغيير حجم الرسم الأصلي. ملف DXF يخرج دائمًا بوحدات المتر 1:1."
            setTextColor(ctx.getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(dp(4), dp(8), 0, dp(4))
        }
        root.addView(calibrationNote)

        // زر التأكيد
        val btnConfirm = Button(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(14) }
            text = "تأكيد وضبط المقياس (Enter)"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_btn_primary)
            setOnClickListener {
                val value = inputVal.text.toString().toFloatOrNull() ?: 25f
                val unit = DistanceUnit.values().getOrElse(unitSpinner.selectedItemPosition) { DistanceUnit.METER }
                val gridSize = inputGridSize.text.toString().toFloatOrNull() ?: 50f

                config.calibrateWithUnit(pixelDistance, value, unit)
                config.gridCellSizeMeters = gridSize.coerceAtLeast(1f)
                onScaleConfirmed(config, false)
                Toast.makeText(ctx, "تمت المعايرة: 1م = %.2f بكسل".format(config.pixelsPerMeter), Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
        root.addView(btnConfirm)

        return AlertDialog.Builder(ctx)
            .setView(root)
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }
}
