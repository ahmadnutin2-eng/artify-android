package com.procreate.android.urban.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.measurement.MeasurementKind
import com.procreate.android.urban.model.DistanceUnit

/**
 * A compact, lifecycle-neutral picker for starting a measurement gesture.
 *
 * The sheet deliberately returns only the selected type and display unit. Gesture ownership,
 * undo/redo and persistence stay with the canvas host, so this component can be attached without
 * coupling it to [androidx.fragment.app.FragmentManager] or a specific activity.
 */
object MeasurementToolSheet {

    fun show(
        context: Context,
        initialKind: MeasurementKind = MeasurementKind.POLYLINE_LENGTH,
        initialUnit: DistanceUnit = DistanceUnit.METER,
        onConfirmed: (kind: MeasurementKind, unit: DistanceUnit) -> Unit
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val content = buildContent(
            context = context,
            dialog = dialog,
            initialKind = initialKind,
            initialUnit = initialUnit,
            onConfirmed = onConfirmed
        )
        dialog.setContentView(content)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
        dialog.show()
        return dialog
    }

    private fun buildContent(
        context: Context,
        dialog: BottomSheetDialog,
        initialKind: MeasurementKind,
        initialUnit: DistanceUnit,
        onConfirmed: (MeasurementKind, DistanceUnit) -> Unit
    ): View {
        fun dp(value: Int) = PanelUi.dp(context, value)

        var selectedKind = initialKind
        var selectedUnit = initialUnit

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), 0, dp(20), dp(20))
            // Keep the content transparent so the themed BottomSheet surface preserves its
            // rounded top corners instead of being covered by a rectangular child background.
            setBackgroundColor(Color.TRANSPARENT)
        }

        root.addView(PanelUi.grabberHandle(context))
        root.addView(TextView(context).apply {
            text = "أداة القياس"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        })
        root.addView(TextView(context).apply {
            text = "اختر نوع القياس ووحدة العرض. تُحسب النتيجة وفق مقياس المشروع الحالي."
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.START
            setLineSpacing(0f, 1.12f)
            setPadding(0, 0, 0, dp(18))
        })

        root.addView(sectionLabel(context, "نوع القياس"))
        val kindGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val distanceButton = choiceButton(context, "قياس المسافة").apply {
            contentDescription = "اختيار قياس المسافة"
        }
        val areaButton = choiceButton(context, "قياس المساحة").apply {
            contentDescription = "اختيار قياس المساحة"
        }
        kindGroup.addView(distanceButton, weightedButtonParams())
        kindGroup.addView(areaButton, weightedButtonParams())
        kindGroup.check(
            if (initialKind == MeasurementKind.POLYGON_AREA) areaButton.id else distanceButton.id
        )
        root.addView(
            kindGroup,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MIN_TOUCH_TARGET_DP)
            ).apply { bottomMargin = dp(18) }
        )

        root.addView(sectionLabel(context, "وحدة العرض"))
        val unitGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val centimeterButton = choiceButton(context, "cm").apply {
            contentDescription = "سنتيمتر"
        }
        val meterButton = choiceButton(context, "m").apply {
            contentDescription = "متر"
        }
        val kilometerButton = choiceButton(context, "km").apply {
            contentDescription = "كيلومتر"
        }
        unitGroup.addView(centimeterButton, weightedButtonParams())
        unitGroup.addView(meterButton, weightedButtonParams())
        unitGroup.addView(kilometerButton, weightedButtonParams())
        unitGroup.check(
            when (initialUnit) {
                DistanceUnit.CENTIMETER -> centimeterButton.id
                DistanceUnit.METER -> meterButton.id
                DistanceUnit.KILOMETER -> kilometerButton.id
            }
        )
        root.addView(
            unitGroup,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MIN_TOUCH_TARGET_DP)
            ).apply { bottomMargin = dp(12) }
        )

        val guidance = TextView(context).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.procreate_accent_light))
            gravity = Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(
                color = ContextCompat.getColor(context, R.color.accent_softer),
                radiusPx = dp(12).toFloat()
            )
        }
        fun updateGuidance() {
            guidance.text = when (selectedKind) {
                MeasurementKind.POLYLINE_LENGTH ->
                    "اضغط نقطتين أو أكثر لقياس طول المسار بوحدة ${selectedUnit.symbol}."

                MeasurementKind.POLYGON_AREA ->
                    "اضغط ثلاث نقاط أو أكثر لقياس المساحة بوحدة ${selectedUnit.symbol}²."
            }
        }
        updateGuidance()
        root.addView(
            guidance,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        )

        kindGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedKind = if (checkedId == areaButton.id) {
                MeasurementKind.POLYGON_AREA
            } else {
                MeasurementKind.POLYLINE_LENGTH
            }
            updateGuidance()
        }
        unitGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedUnit = when (checkedId) {
                centimeterButton.id -> DistanceUnit.CENTIMETER
                kilometerButton.id -> DistanceUnit.KILOMETER
                else -> DistanceUnit.METER
            }
            updateGuidance()
        }

        val startButton = MaterialButton(context).apply {
            text = "بدء القياس"
            contentDescription = "بدء القياس بالإعدادات المحددة"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(52)
            minimumHeight = dp(52)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.procreate_accent)
            )
            setOnClickListener {
                onConfirmed(selectedKind, selectedUnit)
                dialog.dismiss()
            }
        }
        root.addView(
            startButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )

        val cancelButton = TextView(context).apply {
            text = "إلغاء"
            contentDescription = "إلغاء اختيار أداة القياس"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            isClickable = true
            isFocusable = true
            minHeight = dp(MIN_TOUCH_TARGET_DP)
            minimumHeight = dp(MIN_TOUCH_TARGET_DP)
            background = ContextCompat.getDrawable(context, R.drawable.bg_ripple_flat)
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MIN_TOUCH_TARGET_DP)
            ).apply { topMargin = dp(4) }
        )

        return root
    }

    private fun sectionLabel(context: Context, value: String): TextView = TextView(context).apply {
        text = value
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ContextCompat.getColor(context, R.color.text_hint))
        gravity = Gravity.START
        setPadding(0, 0, 0, PanelUi.dp(context, 8))
    }

    private fun choiceButton(context: Context, value: String): MaterialButton {
        val accent = ContextCompat.getColor(context, R.color.procreate_accent)
        val surface = ContextCompat.getColor(context, R.color.surface_2)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val checkedStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        return MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = View.generateViewId()
            text = value
            isAllCaps = false
            isCheckable = true
            textSize = 14f
            minHeight = PanelUi.dp(context, MIN_TOUCH_TARGET_DP)
            minimumHeight = PanelUi.dp(context, MIN_TOUCH_TARGET_DP)
            insetTop = 0
            insetBottom = 0
            strokeWidth = PanelUi.dp(context, 1)
            backgroundTintList = ColorStateList(
                checkedStates,
                intArrayOf(accent, surface)
            )
            strokeColor = ColorStateList(
                checkedStates,
                intArrayOf(accent, ContextCompat.getColor(context, R.color.divider_color))
            )
            setTextColor(
                ColorStateList(
                    checkedStates,
                    intArrayOf(Color.WHITE, secondary)
                )
            )
        }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.MATCH_PARENT,
        1f
    )

    private fun roundedBackground(color: Int, radiusPx: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    private const val MIN_TOUCH_TARGET_DP = 48
}
