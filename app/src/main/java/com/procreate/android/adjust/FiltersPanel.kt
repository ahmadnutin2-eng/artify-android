package com.procreate.android.adjust

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.procreate.android.R
import com.procreate.android.canvas.BitmapAlphaBounds
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.ui.common.PanelMotion
import com.procreate.android.ui.common.PanelUi

/**
 * Creative looks for the active layer, presented as a dedicated side panel that leaves the canvas
 * fully visible.
 *
 * Features:
 * - Docks as a sleek right/end side panel.
 * - Live real-time canvas updates immediately on tapping any filter.
 * - A dedicated "بلا" (None / Original) card to instantly revert to the original state.
 * - Non-destructive cancel: dismissing or cancelling safely restores untouched pixels.
 */
class FiltersPanel : DialogFragment() {

    private val viewModel: CanvasViewModel by activityViewModels()

    private var layerIndex = -1
    private var regionLeft = 0
    private var regionTop = 0
    private var width = 0
    private var height = 0

    private var originalBitmap: Bitmap? = null
    private var originalPixels: IntArray? = null

    private var selected: ImageFilters.Filter? = null
    private var strength = 1f
    private var applied = false
    private var originalTransferredToHistory = false
    private var filterJob: Job? = null

    private var statusView: TextView? = null
    private var noneCard: View? = null
    private val cards = mutableMapOf<ImageFilters.Filter, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val side = PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT)
        setStyle(
            STYLE_NO_TITLE,
            if (side == PanelUi.DockSide.LEFT) R.style.Theme_Artify_SidePanelLeft
            else R.style.Theme_Artify_SidePanel
        )
    }

    override fun onStart() {
        super.onStart()
        val side = PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT)
        dialog?.window?.let { window ->
            val dm = resources.displayMetrics
            val panelWidthDp = 330
            val panelWidthPx = (panelWidthDp * dm.density).toInt()
            window.setLayout(panelWidthPx, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setGravity(
                (if (side == PanelUi.DockSide.LEFT) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
            )
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.06f)
            window.attributes?.windowAnimations = if (side == PanelUi.DockSide.LEFT) {
                R.style.Animation_App_SidePanelLeft
            } else {
                R.style.Animation_App_SidePanel
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                context,
                if (PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT) == PanelUi.DockSide.LEFT) {
                    R.drawable.bg_side_panel_left
                } else {
                    R.drawable.bg_side_panel
                }
            )
            elevation = dp(16).toFloat()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // The side window is edge-to-edge on Samsung tablets. Keep the title out of the status
        // icons and, critically, keep Apply/Cancel above the navigation/gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Header with Title, Subtitle, and Close (X) button
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(14), dp(8))
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = getString(R.string.filters_title)
            setTextAppearance(R.style.TextAppearance_App_PanelTitle)
        })
        titleCol.addView(TextView(context).apply {
            text = getString(R.string.filters_sub)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleCol)

        val closeBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(context, R.color.icon_color)
            background = ContextCompat.getDrawable(context, R.drawable.bg_ripple_flat)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { cancelAndClose() }
        }
        header.addView(closeBtn)
        root.addView(header)
        root.addView(PanelUi.divider(context))

        val index = viewModel.activeLayerIndex.value
        val layer = index?.let { viewModel.layers.value?.getOrNull(it) }
        if (layer == null) {
            root.addView(TextView(context).apply {
                text = getString(R.string.adjustments_no_layer)
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(dp(20), dp(20), dp(20), dp(20))
            })
            return root
        }

        if (layer.isLocked) {
            root.addView(messageView(getString(R.string.layer_filter_locked)))
            return root
        }
        // Adjustments target image pixels, never the grayscale mask channel. Make that target
        // explicit in the Layers panel too so the highlighted thumbnail agrees with the result.
        if (layer.isEditingMask) viewModel.addOrToggleLayerMask(index)
        val contentBounds = BitmapAlphaBounds.find(layer.bitmap)
        if (contentBounds == null) {
            root.addView(messageView(getString(R.string.layer_filter_empty)))
            return root
        }

        layerIndex = index
        // Blur and glow need transparent pixels around the visible content in order to spread
        // naturally. Keep that halo, but do not duplicate a 10k open canvas just because the
        // imported photo occupies a small rectangle in its middle.
        val padding = maxOf(96, minOf(contentBounds.width(), contentBounds.height()) / 32)
            .coerceAtMost(256)
        val region = BitmapAlphaBounds.expanded(
            contentBounds, padding, layer.bitmap.width, layer.bitmap.height
        )
        regionLeft = region.left
        regionTop = region.top
        width = region.width()
        height = region.height()

        // Snapshot only the active content region for preview, rollback and undo. On an open
        // canvas this avoids several full-canvas bitmap/IntArray allocations per filter tap.
        val origBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(origBmp).drawBitmap(
            layer.bitmap,
            Rect(regionLeft, regionTop, regionLeft + width, regionTop + height),
            Rect(0, 0, width, height),
            null
        )
        originalBitmap = origBmp
        val origPixels = IntArray(width * height)
        origBmp.getPixels(origPixels, 0, width, 0, 0, width, height)
        originalPixels = origPixels

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(12))
        }

        statusView = TextView(context).apply {
            text = "${layer.name}  •  ${getString(R.string.filter_none)}"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.procreate_accent_light))
            setPadding(dp(20), dp(4), dp(20), dp(6))
        }
        content.addView(statusView)

        val thumbSource = buildThumbnailSource(origBmp)

        // Grid of filter cards (3 columns for a clean 330dp side sheet)
        val columns = 3
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }

        var row: LinearLayout? = null
        var cardIndex = 0

        fun nextRow(): LinearLayout {
            val r = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            grid.addView(r)
            return r
        }

        // Card 0: "بلا" (None / Original state)
        row = nextRow()
        val noneTile = noneCard(thumbSource)
        noneCard = noneTile
        row.addView(noneTile)
        cardIndex++

        // Filter cards (1..15)
        ImageFilters.Filter.entries.forEach { filter ->
            if (cardIndex % columns == 0) {
                row = nextRow()
            }
            val card = filterCard(filter, thumbSource)
            cards[filter] = card
            row?.addView(card)
            cardIndex++
        }

        content.addView(grid)

        // Filter strength slider
        content.addView(PanelUi.sectionLabel(context, getString(R.string.filters_strength)))
        content.addView(
            PanelUi.sliderRow(
                context, getString(R.string.filters_strength), 100, 100,
                valueFormatter = { "$it%" },
                onRelease = { value ->
                    strength = value / 100f
                    val current = selected
                    if (current != null) {
                        applyLivePreview(current)
                    }
                }
            ) { _, _ -> }.first
        )

        root.addView(
            NestedScrollView(context).apply {
                addView(content)
                isFillViewport = true
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Action Buttons: Apply & Cancel
        root.addView(
            PanelUi.dialogActions(
                context = context,
                primaryText = getString(R.string.adjustments_apply),
                secondaryText = getString(R.string.new_canvas_cancel),
                onPrimary = { applyAndClose() },
                onSecondary = { cancelAndClose() }
            )
        )

        refreshSelection()
        PanelMotion.staggerIn(grid, perRow = 18L)
        return root
    }

    private fun buildThumbnailSource(bitmap: Bitmap): Pair<IntArray, Pair<Int, Int>> {
        val maxEdge = 96
        val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
        val tw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val th = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val pixels = IntArray(tw * th)
        small.getPixels(pixels, 0, tw, 0, 0, tw, th)
        if (small !== bitmap) small.recycle()
        return pixels to (tw to th)
    }

    private fun messageView(message: String) = TextView(requireContext()).apply {
        text = message
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(PanelUi.dp(context, 20), PanelUi.dp(context, 20), PanelUi.dp(context, 20), PanelUi.dp(context, 20))
    }

    /**
     * Card for "بلا" (None) which restores the layer to its original state.
     */
    private fun noneCard(source: Pair<IntArray, Pair<Int, Int>>): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val (srcPixels, dims) = source
        val (tw, th) = dims
        val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).apply {
            setPixels(srcPixels, 0, tw, 0, 0, tw, th)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            layoutParams = ViewGroup.MarginLayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        }

        card.addView(ImageView(context).apply {
            setImageBitmap(thumb)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
            clipToOutline = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_swatch_rounded)
        })

        card.addView(TextView(context).apply {
            text = getString(R.string.filter_none)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })

        PanelMotion.press(card)
        card.setOnClickListener {
            selected = null
            filterJob?.cancel()
            restoreOriginal()
            refreshSelection()
            statusView?.text = getString(R.string.filter_none)
        }
        return card
    }

    private fun filterCard(
        filter: ImageFilters.Filter,
        source: Pair<IntArray, Pair<Int, Int>>
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val (srcPixels, dims) = source
        val (tw, th) = dims
        val preview = srcPixels.copyOf()
        ImageFilters.apply(preview, tw, th, filter)
        val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).apply {
            setPixels(preview, 0, tw, 0, 0, tw, th)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            layoutParams = ViewGroup.MarginLayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        }

        card.addView(ImageView(context).apply {
            setImageBitmap(thumb)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
            clipToOutline = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_swatch_rounded)
        })

        card.addView(TextView(context).apply {
            text = getString(labelFor(filter))
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })

        PanelMotion.press(card)
        card.setOnClickListener {
            if (selected == filter) {
                // Tapping active filter toggles back to None (Original)
                selected = null
                filterJob?.cancel()
                restoreOriginal()
                statusView?.text = getString(R.string.filter_none)
            } else {
                selected = filter
                applyLivePreview(filter)
            }
            refreshSelection()
        }
        return card
    }

    /**
     * Immediately applies the filter to the active layer with live canvas update.
     */
    private fun applyLivePreview(filter: ImageFilters.Filter) {
        val orig = originalPixels ?: return
        val layer = viewModel.layers.value?.getOrNull(layerIndex) ?: return
        filterJob?.cancel()
        statusView?.text = getString(R.string.filters_applying)
        val w = width
        val h = height
        val currentStrength = strength

        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                val buf = orig.copyOf()
                ImageFilters.apply(buf, w, h, filter, currentStrength)
                buf
            }
            if (isActive) {
                layer.bitmap.setPixels(filtered, 0, w, regionLeft, regionTop, w, h)
                viewModel.notifyLayerPixelsChanged()
                (activity as? com.procreate.android.ui.canvas.CanvasActivity)?.drawingViewOrNull()?.invalidate()
                val filterName = getString(labelFor(filter))
                val pct = (currentStrength * 100).toInt()
                statusView?.text = "$filterName ($pct%)"
            }
        }
    }

    /**
     * Restores layer pixels back to their untouched original state.
     */
    private fun restoreOriginal() {
        val orig = originalPixels ?: return
        val layer = viewModel.layers.value?.getOrNull(layerIndex) ?: return
        layer.bitmap.setPixels(orig, 0, width, regionLeft, regionTop, width, height)
        viewModel.notifyLayerPixelsChanged()
        (activity as? com.procreate.android.ui.canvas.CanvasActivity)?.drawingViewOrNull()?.invalidate()
    }

    private fun refreshSelection() {
        val isNone = (selected == null)
        noneCard?.let { card ->
            card.background = ContextCompat.getDrawable(
                requireContext(),
                if (isNone) R.drawable.bg_list_item_selected else R.drawable.bg_list_item_ripple
            )
            if (isNone) PanelMotion.pulse(card)
        }

        for ((filter, card) in cards) {
            val isOn = filter == selected
            card.background = ContextCompat.getDrawable(
                requireContext(),
                if (isOn) R.drawable.bg_list_item_selected else R.drawable.bg_list_item_ripple
            )
            if (isOn) PanelMotion.pulse(card)
        }
    }

    /**
     * Bakes the filter into the active layer and records undo history.
     */
    private fun applyAndClose() {
        filterJob?.cancel()
        val chosen = selected
        val orig = originalBitmap
        val source = originalPixels
        if (chosen == null || orig == null || source == null) {
            applied = true
            dismiss()
            return
        }
        val currentStrength = strength
        statusView?.text = getString(R.string.filters_applying)
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            // Always calculate the final state here. A fast Apply tap may cancel an in-flight
            // preview, and committing whatever happened to be on the bitmap would otherwise make
            // the chosen card appear to do nothing while still consuming an Undo step.
            val result = withContext(Dispatchers.Default) {
                source.copyOf().also {
                    ImageFilters.apply(it, width, height, chosen, currentStrength)
                }
            }
            if (!isActive) return@launch
            val layer = viewModel.layers.value?.getOrNull(layerIndex) ?: return@launch
            layer.bitmap.setPixels(result, 0, width, regionLeft, regionTop, width, height)
            if (ImageAdjustments.differs(result, source)) {
                if (!viewModel.commitLayerRegionEdit(layerIndex, regionLeft, regionTop, orig)) {
                    restoreOriginal()
                    return@launch
                }
                // UndoHistory now owns this bitmap as the "before" patch.
                originalTransferredToHistory = true
            }
            applied = true
            viewModel.notifyLayerPixelsChanged()
            dismiss()
        }
    }

    private fun cancelAndClose() {
        applied = false
        filterJob?.cancel()
        restoreOriginal()
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        filterJob?.cancel()
        if (!applied) {
            restoreOriginal()
        }
        if (!originalTransferredToHistory) {
            originalBitmap?.recycle()
        }
        originalBitmap = null
        originalPixels = null
        cards.clear()
        noneCard = null
        super.onDismiss(dialog)
    }

    private fun labelFor(filter: ImageFilters.Filter): Int = when (filter) {
        ImageFilters.Filter.SOFT_BLUR -> R.string.filter_soft_blur
        ImageFilters.Filter.MOTION_BLUR -> R.string.filter_motion_blur
        ImageFilters.Filter.ZOOM_BLUR -> R.string.filter_zoom_blur
        ImageFilters.Filter.FROSTED_GLASS -> R.string.filter_frosted_glass
        ImageFilters.Filter.SEPIA -> R.string.filter_sepia
        ImageFilters.Filter.VINTAGE -> R.string.filter_vintage
        ImageFilters.Filter.NOIR -> R.string.filter_noir
        ImageFilters.Filter.POSTERIZE -> R.string.filter_posterize
        ImageFilters.Filter.VIGNETTE -> R.string.filter_vignette
        ImageFilters.Filter.GRAIN -> R.string.filter_grain
        ImageFilters.Filter.BLOOM -> R.string.filter_bloom
        ImageFilters.Filter.COOL -> R.string.filter_cool
        ImageFilters.Filter.WARM -> R.string.filter_warm
        ImageFilters.Filter.SKETCH -> R.string.filter_sketch
        ImageFilters.Filter.CHROMATIC -> R.string.filter_chromatic
        ImageFilters.Filter.HALFTONE -> R.string.filter_halftone
        ImageFilters.Filter.COLOR_BALANCE -> R.string.filter_color_balance
        ImageFilters.Filter.OIL_GLAZE -> R.string.filter_oil_glaze
        ImageFilters.Filter.NEON_GLOW -> R.string.filter_neon_glow
    }

    companion object {
        fun show(manager: FragmentManager, side: PanelUi.DockSide = PanelUi.DockSide.RIGHT) {
            FiltersPanel().apply { arguments = PanelUi.dockArguments(side) }.show(manager, "filters")
        }
    }
}
