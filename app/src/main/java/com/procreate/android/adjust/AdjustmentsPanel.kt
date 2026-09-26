package com.procreate.android.adjust

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.canvas.BitmapAlphaBounds
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.ui.common.PanelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Corrective adjustments for exactly the selected layer. Colour, blur and sharpen all share one
 * preview/undo path, so switching controls never accidentally filters the merged canvas.
 *
 * Only the non-transparent content rectangle (plus a blur halo) is copied. That distinction is
 * important on the open canvas: an imported 1200px photo must not allocate arrays for a 10k sheet.
 */
class AdjustmentsPanel : BottomSheetDialogFragment() {

    private val viewModel: CanvasViewModel by activityViewModels()

    private var original: IntArray? = null
    private var layerIndex = -1
    private var regionLeft = 0
    private var regionTop = 0
    private var width = 0
    private var height = 0

    private var brightness = 0f
    private var contrast = 0f
    private var saturation = 1f
    private var hue = 0f
    private var blur = 0
    private var sharpen = 0f
    private var invert = false
    private var mono = false

    private var applied = false
    private var previewJob: Job? = null
    private var statusView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(PanelUi.grabberHandle(context))
        root.addView(PanelUi.panelTitle(context, getString(R.string.adjustments_title)))

        val index = viewModel.activeLayerIndex.value
        val layer = index?.let { viewModel.layers.value?.getOrNull(it) }
        when {
            layer == null -> return root.apply { addView(messageView(getString(R.string.adjustments_no_layer))) }
            layer.isLocked -> return root.apply { addView(messageView(getString(R.string.layer_filter_locked))) }
        }
        if (layer.isEditingMask) viewModel.addOrToggleLayerMask(index)
        val contentBounds = BitmapAlphaBounds.find(layer.bitmap)
            ?: return root.apply { addView(messageView(getString(R.string.layer_filter_empty))) }

        layerIndex = index
        val region = BitmapAlphaBounds.expanded(
            contentBounds, 96, layer.bitmap.width, layer.bitmap.height
        )
        regionLeft = region.left
        regionTop = region.top
        width = region.width()
        height = region.height()
        val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(snapshot).drawBitmap(
            layer.bitmap,
            Rect(regionLeft, regionTop, region.right, region.bottom),
            Rect(0, 0, width, height),
            null
        )
        original = IntArray(width * height).also {
            snapshot.getPixels(it, 0, width, 0, 0, width, height)
        }
        snapshot.recycle()

        statusView = TextView(context).apply {
            text = layer.name
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.procreate_accent_light))
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        root.addView(statusView)

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(PanelUi.sectionLabel(context, getString(R.string.adjustments_colour)))
        content.addView(signedSlider(getString(R.string.adjust_brightness), 200, 100) {
            brightness = it / 100f
        })
        content.addView(signedSlider(getString(R.string.adjust_contrast), 200, 100) {
            contrast = it / 100f
        })
        content.addView(plainSlider(getString(R.string.adjust_saturation), 200, 100) {
            saturation = it / 100f
        })
        content.addView(signedSlider(getString(R.string.adjust_hue), 360, 180) {
            hue = it.toFloat()
        })

        content.addView(PanelUi.sectionLabel(context, getString(R.string.adjustments_focus)))
        content.addView(plainSlider(getString(R.string.adjust_blur), 40, 0) { blur = it })
        content.addView(plainSlider(getString(R.string.adjust_sharpen), 100, 0) {
            sharpen = it / 100f
        })

        content.addView(PanelUi.sectionLabel(context, getString(R.string.adjustments_effects)))
        val effects = PanelUi.groupedList(context)
        val invertRow = PanelUi.choiceRow(context, getString(R.string.adjust_invert)) {}
        val monoRow = PanelUi.choiceRow(context, getString(R.string.adjust_mono)) {}
        invertRow.setOnClickListener {
            invert = !invert
            PanelUi.setRowSelected(invertRow, invert)
            recompute()
        }
        monoRow.setOnClickListener {
            mono = !mono
            PanelUi.setRowSelected(monoRow, mono)
            recompute()
        }
        PanelUi.setRowSelected(invertRow, false)
        PanelUi.setRowSelected(monoRow, false)
        effects.addView(invertRow)
        effects.addView(PanelUi.divider(context))
        effects.addView(monoRow)
        content.addView(effects)

        root.addView(
            NestedScrollView(context).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            PanelUi.dialogActions(
                context,
                getString(R.string.adjustments_apply),
                getString(R.string.new_canvas_cancel),
                onPrimary = { applyAndClose() },
                onSecondary = { dismiss() }
            )
        )
        return root
    }

    override fun onStart() {
        super.onStart()
        PanelUi.dockSheet(dialog, PanelUi.dockSide(arguments, PanelUi.DockSide.LEFT))
    }

    private fun messageView(message: String) = TextView(requireContext()).apply {
        text = message
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        val p = PanelUi.dp(context, 20)
        setPadding(p, PanelUi.dp(context, 8), p, p)
    }

    private fun plainSlider(label: String, max: Int, initial: Int, onChange: (Int) -> Unit): View =
        PanelUi.sliderRow(
            requireContext(), label, max, initial,
            onRelease = { value -> onChange(value); recompute() }
        ) { _, _ -> }.first

    private fun signedSlider(label: String, max: Int, centre: Int, onChange: (Int) -> Unit): View =
        PanelUi.sliderRow(
            requireContext(), label, max, centre,
            valueFormatter = { (it - centre).toString() },
            onRelease = { value -> onChange(value - centre); recompute() }
        ) { _, _ -> }.first

    private data class Settings(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val hue: Float,
        val blur: Int,
        val sharpen: Float,
        val invert: Boolean,
        val mono: Boolean
    )

    private fun settings() = Settings(
        brightness, contrast, saturation, hue, blur, sharpen, invert, mono
    )

    private fun calculate(src: IntArray, current: Settings): IntArray {
        val work = src.copyOf()
        if (current.brightness != 0f || current.contrast != 0f) {
            ImageAdjustments.brightnessContrast(work, current.brightness, current.contrast)
        }
        if (current.saturation != 1f) ImageAdjustments.saturation(work, current.saturation)
        if (current.hue != 0f) ImageAdjustments.hueRotate(work, current.hue)
        if (current.blur > 0) ImageAdjustments.gaussianBlur(work, width, height, current.blur)
        if (current.sharpen > 0f) ImageAdjustments.sharpen(work, width, height, current.sharpen)
        if (current.mono) ImageAdjustments.grayscale(work)
        if (current.invert) ImageAdjustments.invert(work)
        return work
    }

    private fun recompute() {
        val src = original ?: return
        val current = settings()
        previewJob?.cancel()
        statusView?.text = getString(R.string.filters_applying)
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { calculate(src, current) }
            if (!isActive) return@launch
            writePreview(result)
            statusView?.text = getString(R.string.adjustments_preview_ready)
        }
    }

    private fun writePreview(pixels: IntArray) {
        val layer = viewModel.layers.value?.getOrNull(layerIndex) ?: return
        layer.bitmap.setPixels(pixels, 0, width, regionLeft, regionTop, width, height)
        viewModel.notifyLayerPixelsChanged()
        (activity as? com.procreate.android.ui.canvas.CanvasActivity)?.drawingViewOrNull()?.invalidate()
    }

    private fun applyAndClose() {
        val src = original ?: return dismiss()
        val current = settings()
        previewJob?.cancel()
        statusView?.text = getString(R.string.filters_applying)
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { calculate(src, current) }
            if (!isActive) return@launch
            writePreview(result)
            if (!ImageAdjustments.differs(result, src)) {
                Toast.makeText(requireContext(), R.string.adjustments_no_change, Toast.LENGTH_SHORT).show()
                applied = true
                dismiss()
                return@launch
            }
            val before = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(src, 0, width, 0, 0, width, height)
            }
            if (!viewModel.commitLayerRegionEdit(layerIndex, regionLeft, regionTop, before)) {
                before.recycle()
                return@launch
            }
            applied = true
            dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        previewJob?.cancel()
        if (!applied) original?.let { writePreview(it) }
        original = null
        statusView = null
        super.onDismiss(dialog)
    }

    companion object {
        fun show(
            manager: FragmentManager,
            side: PanelUi.DockSide = PanelUi.DockSide.LEFT
        ) {
            AdjustmentsPanel().apply { arguments = PanelUi.dockArguments(side) }
                .show(manager, "adjustments")
        }
    }
}
