package com.procreate.android.ui.canvas

import android.graphics.ColorMatrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.ui.common.PanelUi

/**
 * Brightness/Contrast/Saturation adjustment for the active layer, previewed live via a
 * ColorMatrixColorFilter and baked into the bitmap only when "Apply" is pressed.
 */
class AdjustmentsPanel : BottomSheetDialogFragment() {

    private val viewModel: CanvasViewModel by activityViewModels()

    private var brightness = 0f // -100..100
    private var contrast = 0f   // -100..100
    private var saturation = 0f // -100..100
    private var hue = 0f        // -180..180
    private var invert = false
    private var mono = false

    /** Landscape bottom sheets open collapsed by default - see PanelUi.expandSheet. */
    override fun onStart() {
        super.onStart()
        PanelUi.dockSheet(dialog, PanelUi.dockSide(arguments, PanelUi.DockSide.LEFT))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(PanelUi.grabberHandle(context))
        root.addView(PanelUi.panelTitle(context, getString(R.string.control_adjustments)))

        fun updatePreview() {
            val drawingView = (activity as? CanvasActivity)?.drawingViewOrNull() ?: return
            drawingView.setAdjustmentPreview(
                buildColorMatrix(brightness, contrast, saturation, hue, invert, mono)
            )
        }

        root.addView(PanelUi.sliderRow(
            context, getString(R.string.adjustment_brightness), 200, (brightness + 100).toInt(),
            valueFormatter = { (it - 100).toString() }
        ) { progress, fromUser -> if (fromUser) { brightness = (progress - 100).toFloat(); updatePreview() } }.first)

        root.addView(PanelUi.sliderRow(
            context, getString(R.string.adjustment_contrast), 200, (contrast + 100).toInt(),
            valueFormatter = { (it - 100).toString() }
        ) { progress, fromUser -> if (fromUser) { contrast = (progress - 100).toFloat(); updatePreview() } }.first)

        root.addView(PanelUi.sliderRow(
            context, getString(R.string.adjustment_saturation), 200, (saturation + 100).toInt(),
            valueFormatter = { (it - 100).toString() }
        ) { progress, fromUser -> if (fromUser) { saturation = (progress - 100).toFloat(); updatePreview() } }.first)

        // Hue, invert and black-and-white join the same ColorMatrix rather than being computed per
        // pixel: expressed as a matrix they ride the existing GPU preview and cost nothing extra,
        // where a per-pixel version would stall the slider on a full-canvas layer.
        root.addView(PanelUi.sliderRow(
            context, getString(R.string.adjust_hue), 360, (hue + 180).toInt(),
            valueFormatter = { (it - 180).toString() }
        ) { progress, fromUser -> if (fromUser) { hue = (progress - 180).toFloat(); updatePreview() } }.first)

        val toggles = PanelUi.groupedList(context)
        val invertRow = PanelUi.choiceRow(context, getString(R.string.adjust_invert)) {}
        val monoRow = PanelUi.choiceRow(context, getString(R.string.adjust_mono)) {}
        invertRow.setOnClickListener {
            invert = !invert
            PanelUi.setRowSelected(invertRow, invert)
            updatePreview()
        }
        monoRow.setOnClickListener {
            mono = !mono
            PanelUi.setRowSelected(monoRow, mono)
            updatePreview()
        }
        PanelUi.setRowSelected(invertRow, false)
        PanelUi.setRowSelected(monoRow, false)
        toggles.addView(invertRow)
        toggles.addView(PanelUi.divider(context))
        toggles.addView(monoRow)
        root.addView(toggles, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(20), dp(12), dp(20), 0) })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(20), dp(12), dp(24))
        }
        buttonRow.addView(PanelUi.flatButton(context, getString(R.string.adjustment_reset)) {
            brightness = 0f; contrast = 0f; saturation = 0f
            hue = 0f; invert = false; mono = false
            (activity as? CanvasActivity)?.drawingViewOrNull()?.cancelAdjustmentPreview()
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        buttonRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        buttonRow.addView(PanelUi.filledButton(context, getString(R.string.adjustment_apply)) {
            (activity as? CanvasActivity)?.drawingViewOrNull()?.applyAdjustment()
            dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(buttonRow)

        return root
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // If the sheet is dismissed without an explicit Apply/Reset tap (e.g. swipe down),
        // don't leave a half-adjusted preview stuck on the canvas.
        (activity as? CanvasActivity)?.drawingViewOrNull()?.cancelAdjustmentPreview()
    }

    private fun buildColorMatrix(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        hue: Float = 0f,
        invert: Boolean = false,
        mono: Boolean = false
    ): ColorMatrix {
        val result = ColorMatrix()

        // Black and white wins over the saturation slider: asking for both is contradictory, and
        // silently blending them would leave the toggle looking broken at high saturation.
        val sat = ColorMatrix()
        sat.setSaturation(if (mono) 0f else 1f + saturation / 100f)
        result.postConcat(sat)

        if (hue != 0f) {
            // The standard luminance-preserving hue rotation, same maths as ImageAdjustments.
            val rad = Math.toRadians(hue.toDouble())
            val cosA = kotlin.math.cos(rad).toFloat()
            val sinA = kotlin.math.sin(rad).toFloat()
            result.postConcat(ColorMatrix(floatArrayOf(
                0.213f + cosA * 0.787f - sinA * 0.213f,
                0.715f - cosA * 0.715f - sinA * 0.715f,
                0.072f - cosA * 0.072f + sinA * 0.928f, 0f, 0f,

                0.213f - cosA * 0.213f + sinA * 0.143f,
                0.715f + cosA * 0.285f + sinA * 0.140f,
                0.072f - cosA * 0.072f - sinA * 0.283f, 0f, 0f,

                0.213f - cosA * 0.213f - sinA * 0.787f,
                0.715f - cosA * 0.715f + sinA * 0.715f,
                0.072f + cosA * 0.928f + sinA * 0.072f, 0f, 0f,

                0f, 0f, 0f, 1f, 0f
            )))
        }

        if (invert) {
            // Negating each colour channel and offsetting by 255; the alpha row is left alone so
            // transparent areas stay transparent rather than turning into an opaque rectangle.
            result.postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        val c = 1f + contrast / 100f
        val t = (1f - c) * 128f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(contrastMatrix)

        val b = brightness / 100f * 255f
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(brightnessMatrix)

        return result
    }
}
