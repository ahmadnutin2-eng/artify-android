package com.procreate.android.layers

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.procreate.android.R
import com.procreate.android.canvas.BlendMode
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.canvas.Layer
import com.procreate.android.layers.LayerStackLogic.moveItem
import com.procreate.android.ui.common.CheckerDrawable
import com.procreate.android.ui.common.PanelMotion
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.ui.common.SmoothSwitch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The layers list, docked beside its toolbar button on the RIGHT edge of the screen.
 *
 * It used to be a bottom sheet, which in landscape opens as a wide slab across the middle of the
 * screen, hiding the very drawing being organised.
 *
 * Layout choices, all in service of "simple and smooth" (Procreate is the reference):
 *  - One compact row per layer: thumbnail on a chequerboard, name, blend/opacity summary, an eye.
 *  - Nothing else is on screen until asked for. Tapping the *selected* layer again - or its chevron -
 *    opens a drawer with opacity, blend mode, the three toggles and "use as mask". Every setting
 *    the old panel showed on every row is still there; it just isn't all shouting at once.
 *  - A clipped layer is indented, carries a corner arrow and says which layer it is clipped to, so
 *    the clipping relationship is visible instead of having to be inferred from the canvas.
 */
class LayersPanel : DialogFragment(), LayerAdapter.Host {

    private val viewModel: CanvasViewModel by activityViewModels()

    private lateinit var adapter: LayerAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var touchHelper: ItemTouchHelper
    private var countView: TextView? = null
    private var firstPublish = true

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
            window.setLayout(PanelUi.dp(requireContext(), PANEL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
            window.setGravity(
                (if (side == PanelUi.DockSide.LEFT) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
            )
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.05f)
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
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        root.addView(buildHeader(context))
        root.addView(PanelUi.divider(context))

        recycler = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(8), dp(8), dp(8), dp(16))
            // No change animation: a row that fades out and back in every time its opacity slider
            // moves reads as flicker, and it also fights the drag that is in progress.
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        adapter = LayerAdapter(this)
        recycler.adapter = adapter

        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(recycler)
        root.addView(recycler)

        root.addView(TextView(context).apply {
            text = getString(R.string.layer_drag_hint)
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            setPadding(dp(16), dp(6), dp(16), dp(12))
        })

        viewModel.layers.observe(viewLifecycleOwner) { refreshList() }
        viewModel.activeLayerIndex.observe(viewLifecycleOwner) { refreshList() }
        return root
    }

    // ------------------------------------------------------------------ header

    private fun buildHeader(context: Context): View {
        fun dp(v: Int) = PanelUi.dp(context, v)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(10))
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(context).apply {
            text = getString(R.string.control_layers)
            setTextAppearance(R.style.TextAppearance_App_PanelTitle)
        })
        countView = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(countView)
        header.addView(titleCol)

        // The one primary action gets the accent; everything secondary lives in the overflow menu,
        // where it costs no screen space.
        header.addView(headerButton(context, R.drawable.ic_plus, R.string.layer_add, accent = true) {
            viewModel.layers.value?.firstOrNull()?.bitmap?.let { bmp ->
                viewModel.addLayer(bmp.width, bmp.height)
                recycler.post { recycler.smoothScrollToPosition(0) }
            }
        })
        header.addView(headerButton(context, R.drawable.ic_more, R.string.control_actions) { showOverflow(it) })
        header.addView(headerButton(context, R.drawable.ic_close, R.string.new_canvas_cancel) { dismiss() })
        return header
    }

    private fun headerButton(
        context: Context, icon: Int, description: Int, accent: Boolean = false, onClick: (View) -> Unit
    ): View {
        fun dp(v: Int) = PanelUi.dp(context, v)
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setImageResource(icon)
            contentDescription = getString(description)
            if (accent) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, R.color.procreate_accent))
                }
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else {
                background = ContextCompat.getDrawable(context, R.drawable.bg_ripple_flat)
                imageTintList = ContextCompat.getColorStateList(context, R.color.icon_color)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(it) }
            PanelMotion.press(this)
        }
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_DUPLICATE, 0, R.string.layer_duplicate)
            menu.add(0, MENU_MERGE_DOWN, 1, R.string.layer_merge_down)
            menu.add(0, MENU_MERGE_VISIBLE, 2, R.string.layer_merge_visible)
            menu.add(0, MENU_DELETE, 3, R.string.layer_delete)
            setOnMenuItemClickListener { item ->
                val active = viewModel.activeLayerIndex.value
                when (item.itemId) {
                    MENU_DUPLICATE -> active?.let { viewModel.duplicateLayer(it) }
                    MENU_MERGE_DOWN -> active?.let {
                        if (!viewModel.mergeDown(it)) toast(R.string.layer_merge_down_none)
                    }
                    MENU_MERGE_VISIBLE -> viewModel.mergeVisibleLayers()
                    MENU_DELETE -> active?.let {
                        if ((viewModel.layers.value?.size ?: 0) <= 1) toast(R.string.layer_delete_last)
                        else viewModel.removeLayer(it)
                    }
                }
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------------ list

    private fun refreshList() {
        val stack = viewModel.layers.value ?: return
        val activeId = stack.getOrNull(viewModel.activeLayerIndex.value ?: -1)?.id
        adapter.submit(stack, activeId)
        countView?.text = getString(R.string.layers_count, stack.size)
        if (firstPublish && stack.isNotEmpty()) {
            firstPublish = false
            val pos = adapter.displayPositionOf(activeId)
            if (pos > 0) recycler.post { recycler.scrollToPosition(pos) }
        }
    }

    private fun flagsOf(stack: List<Layer>) =
        stack.map { LayerStackLogic.Flags(it.isClippingMask, it.isVisible, it.isBackground) }

    private inline fun withIndex(layerId: String, action: (Int) -> Unit) {
        val stack = viewModel.layers.value ?: return
        val index = stack.indexOfFirst { it.id == layerId }
        if (index != -1) action(index)
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ LayerAdapter.Host

    override fun onSelect(layerId: String) = withIndex(layerId) { viewModel.selectLayer(it) }

    override fun onOpacity(layerId: String, opacity: Float) =
        withIndex(layerId) { viewModel.updateLayerOpacity(it, opacity) }

    override fun onVisibility(layerId: String) = withIndex(layerId) { viewModel.toggleLayerVisibility(it) }

    override fun onLock(layerId: String) = withIndex(layerId) { viewModel.toggleLayerLock(it) }

    override fun onAlphaLock(layerId: String) = withIndex(layerId) { viewModel.toggleLayerAlphaLock(it) }

    override fun onClipping(layerId: String) {
        val before = viewModel.layers.value ?: return
        val beforeIndex = before.indexOfFirst { it.id == layerId }
        val beforeLayer = before.getOrNull(beforeIndex) ?: return
        if (!beforeLayer.isClippingMask) {
            val proposed = flagsOf(before).toMutableList()
            proposed[beforeIndex] = proposed[beforeIndex].copy(isClippingMask = true)
            if (LayerStackLogic.clipBaseIndex(proposed, beforeIndex) < 0) {
                // Turning on clipping with no base makes a layer disappear completely. Treat that
                // as an invalid operation rather than accepting it and merely explaining the loss.
                toast(R.string.layer_clip_no_base)
                return
            }
        }
        viewModel.toggleLayerClippingMask(beforeIndex)
        // Say what just happened. The old panel flipped a flag and left the user to work out from
        // the canvas whether anything had changed - which is how "clipping mask does nothing"
        // looked whenever the layer beneath was fully opaque.
        val stack = viewModel.layers.value ?: return
        val index = stack.indexOfFirst { it.id == layerId }
        val layer = stack.getOrNull(index) ?: return
        if (!layer.isClippingMask) return
        val base = LayerStackLogic.clipBaseIndex(flagsOf(stack), index)
        toast(
            if (base >= 0) getString(R.string.layer_clipped_to, stack[base].name)
            else getString(R.string.layer_clip_no_base)
        )
    }

    override fun onBlend(layerId: String, anchor: View, current: BlendMode) {
        PopupMenu(requireContext(), anchor).apply {
            BlendMode.entries.forEachIndexed { i, mode ->
                menu.add(0, i, i, blendLabelRes(mode)).apply {
                    isCheckable = true
                    isChecked = mode == current
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                val mode = BlendMode.entries[item.itemId]
                if (mode != current) withIndex(layerId) { viewModel.setLayerBlendMode(it, mode) }
                true
            }
            show()
        }
    }

    /** A true layer mask: black hides this layer, white reveals it, without deleting pixels. */
    override fun onUseAsMask(layerId: String) {
        val stack = viewModel.layers.value ?: return
        val index = stack.indexOfFirst { it.id == layerId }
        if (index < 0) return
        val hadMask = stack[index].maskBitmap != null
        val editing = viewModel.addOrToggleLayerMask(index)
        toast(
            when {
                !hadMask -> R.string.layer_mask_added
                editing -> R.string.layer_mask_editing
                else -> R.string.layer_pixels_editing
            }
        )
    }

    private fun openLayerTool(layerId: String, action: (FragmentManager, PanelUi.DockSide) -> Unit) {
        val stack = viewModel.layers.value ?: return
        val index = stack.indexOfFirst { it.id == layerId }
        if (index < 0) return
        viewModel.selectLayer(index)
        val manager = parentFragmentManager
        val side = PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT)
        dismiss()
        activity?.window?.decorView?.post { action(manager, side) }
    }

    override fun onAdjustments(layerId: String) = openLayerTool(layerId) { manager, side ->
        com.procreate.android.adjust.AdjustmentsPanel.show(manager, side)
    }

    override fun onFilters(layerId: String) = openLayerTool(layerId) { manager, side ->
        com.procreate.android.adjust.FiltersPanel.show(manager, side)
    }

    override fun onTransform(layerId: String) = openLayerTool(layerId) { _, _ ->
        (activity as? com.procreate.android.ui.canvas.CanvasActivity)?.startTransformForSelectedLayer()
    }

    override fun onBackgroundColor(layerId: String) {
        val picker = com.procreate.android.color.ColorPickerPanel()
        picker.setOnColorSelectedListener { color -> viewModel.setBackgroundColor(color) }
        picker.show(parentFragmentManager, "BaseColorPicker")
    }

    override fun onStartDrag(holder: RecyclerView.ViewHolder) = touchHelper.startDrag(holder)

    /** Revealed by swiping a row the other way: the destructive actions, one tap further away. */
    override fun onQuickActions(layerId: String, anchor: View) {
        val stack = viewModel.layers.value ?: return
        val index = stack.indexOfFirst { it.id == layerId }
        if (index < 0) return
        viewModel.selectLayer(index)

        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_ADJUSTMENTS, 0, R.string.adjustments_title)
            menu.add(0, MENU_FILTERS, 1, R.string.filters_title)
            if (!stack[index].isBackground && !stack[index].isEditingMask) {
                menu.add(0, MENU_TRANSFORM, 2, R.string.tool_transform)
            }
            menu.add(0, MENU_DUPLICATE, 3, R.string.layer_duplicate)
            menu.add(0, MENU_MERGE_DOWN, 4, R.string.layer_merge_down)
            if (stack[index].maskBitmap != null) {
                menu.add(0, MENU_REMOVE_MASK, 5, R.string.layer_mask_remove)
            }
            menu.add(0, MENU_DELETE, 6, R.string.layer_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ADJUSTMENTS -> onAdjustments(layerId)
                    MENU_FILTERS -> onFilters(layerId)
                    MENU_TRANSFORM -> onTransform(layerId)
                    MENU_DUPLICATE -> viewModel.duplicateLayer(index)
                    MENU_MERGE_DOWN -> if (!viewModel.mergeDown(index)) toast(R.string.layer_merge_down_none)
                    MENU_REMOVE_MASK -> viewModel.removeLayerMask(index)
                    MENU_DELETE ->
                        if (stack.size <= 1) toast(R.string.layer_delete_last)
                        else viewModel.removeLayer(index)
                }
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------------ drag and drop

    /**
     * Reorders the adapter's own list while the finger moves and commits to the view model once, on
     * release.
     *
     * The previous version called moveLayer on every crossing. Each call republished the whole
     * layer list, the observer rebound every row with notifyDataSetChanged, and RecyclerView lost
     * the item being dragged a fraction of a second after it was lifted - "the selection cancels
     * after a very short time", exactly as reported. Nothing outside this panel needs to know about
     * an intermediate position, so nothing is told until the drop.
     */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        private var dragFrom = RecyclerView.NO_POSITION

        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            // The paper stays at the bottom. Dragging it up would cover every layer beneath.
            if ((vh as? LayerAdapter.VH)?.isBackgroundRow == true) return makeMovementFlags(0, 0)
            return super.getMovementFlags(rv, vh)
        }

        override fun canDropOver(
            rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = (target as? LayerAdapter.VH)?.isBackgroundRow != true

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.moveItemLocally(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                dragFrom = vh.bindingAdapterPosition
                adapter.isDragging = true
                vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // Lift: the row grows a little and floats above its neighbours, so it reads as held.
                vh.itemView.animate().scaleX(1.03f).scaleY(1.03f).translationZ(PanelUi.dp(vh.itemView.context, 10).toFloat())
                    .setDuration(PanelMotion.QUICK).setInterpolator(PanelMotion.standard).start()
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                .setDuration(PanelMotion.NORMAL).setInterpolator(PanelMotion.springy).start()

            val to = vh.bindingAdapterPosition
            val from = dragFrom
            dragFrom = RecyclerView.NO_POSITION
            adapter.isDragging = false

            val size = viewModel.layers.value?.size ?: 0
            if (to != RecyclerView.NO_POSITION) {
                LayerStackLogic.dropToStackMove(from, to, size)?.let { (a, b) ->
                    viewModel.moveLayer(a, b)
                }
            }
            // Resync from the source of truth in case anything was published while dragging.
            refreshList()
        }
    }

    companion object {
        private const val PANEL_WIDTH_DP = 344
        private const val MENU_DUPLICATE = 1
        private const val MENU_MERGE_DOWN = 2
        private const val MENU_MERGE_VISIBLE = 3
        private const val MENU_DELETE = 4
        private const val MENU_REMOVE_MASK = 5
        private const val MENU_ADJUSTMENTS = 6
        private const val MENU_FILTERS = 7
        private const val MENU_TRANSFORM = 8

        fun show(manager: FragmentManager, side: PanelUi.DockSide = PanelUi.DockSide.RIGHT) {
            if (manager.findFragmentByTag(TAG) != null) return
            LayersPanel().apply { arguments = PanelUi.dockArguments(side) }.show(manager, TAG)
        }

        private const val TAG = "LayersPanel"
    }
}

/** String resource for a blend mode's user-facing name. */
fun blendLabelRes(mode: BlendMode): Int = when (mode) {
    BlendMode.Normal -> R.string.blend_normal
    BlendMode.Multiply -> R.string.blend_multiply
    BlendMode.Screen -> R.string.blend_screen
    BlendMode.Overlay -> R.string.blend_overlay
    BlendMode.Darken -> R.string.blend_darken
    BlendMode.Lighten -> R.string.blend_lighten
    BlendMode.ColorDodge -> R.string.blend_color_dodge
    BlendMode.ColorBurn -> R.string.blend_color_burn
    BlendMode.HardLight -> R.string.blend_hard_light
    BlendMode.SoftLight -> R.string.blend_soft_light
    BlendMode.Difference -> R.string.blend_difference
    BlendMode.Exclusion -> R.string.blend_exclusion
    BlendMode.Hue -> R.string.blend_hue
    BlendMode.Saturation -> R.string.blend_saturation
    BlendMode.Color -> R.string.blend_color
    BlendMode.Luminosity -> R.string.blend_luminosity
    BlendMode.Add -> R.string.blend_add
    BlendMode.Subtract -> R.string.blend_subtract
}

class LayerAdapter(private val host: Host) : RecyclerView.Adapter<LayerAdapter.VH>() {

    interface Host {
        fun onSelect(layerId: String)
        fun onOpacity(layerId: String, opacity: Float)
        fun onBlend(layerId: String, anchor: View, current: BlendMode)
        fun onVisibility(layerId: String)
        fun onLock(layerId: String)
        fun onAlphaLock(layerId: String)
        fun onClipping(layerId: String)
        fun onUseAsMask(layerId: String)
        fun onAdjustments(layerId: String)
        fun onFilters(layerId: String)
        fun onTransform(layerId: String)
        fun onBackgroundColor(layerId: String)
        fun onQuickActions(layerId: String, anchor: View)
        fun onStartDrag(holder: RecyclerView.ViewHolder)
    }

    /** Everything that decides how a row looks. Layers are mutated in place, so the previous state
     * cannot be read back from the Layer objects - it has to be captured here to diff against. */
    private data class Sig(
        val id: String, val name: String, val opacity: Float, val blend: BlendMode,
        val visible: Boolean, val locked: Boolean, val alphaLock: Boolean, val clip: Boolean,
        val hasMask: Boolean, val editingMask: Boolean,
        val isBackground: Boolean, val generation: Int, val maskGeneration: Int,
        val active: Boolean, val expanded: Boolean,
        val clipBaseName: String?
    )

    private var stack: List<Layer> = emptyList()
    private var display: MutableList<Layer> = mutableListOf()
    private var sigs: MutableList<Sig> = mutableListOf()
    private var recycler: RecyclerView? = null
    private val thumbs = HashMap<String, Pair<Int, Bitmap>>()
    private val maskThumbs = HashMap<String, Pair<Int, Bitmap>>()

    var activeLayerId: String? = null
        private set
    private var expandedId: String? = null

    /** True while the user has a row picked up. Updates from outside are ignored until it is dropped. */
    var isDragging = false

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    fun displayPositionOf(layerId: String?): Int = display.indexOfFirst { it.id == layerId }

    fun submit(newStack: List<Layer>, newActiveId: String?) {
        if (isDragging) return
        stack = newStack
        activeLayerId = newActiveId

        val newDisplay = newStack.reversed().toMutableList()
        val newSigs = buildSigs(newDisplay)
        val oldSigs = sigs

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldSigs.size
            override fun getNewListSize() = newSigs.size
            override fun areItemsTheSame(o: Int, n: Int) = oldSigs[o].id == newSigs[n].id
            override fun areContentsTheSame(o: Int, n: Int) = oldSigs[o] == newSigs[n]
        })
        display = newDisplay
        sigs = newSigs
        diff.dispatchUpdatesTo(this)

        val liveIds = newStack.mapTo(HashSet()) { it.id }
        thumbs.keys.filterNot(liveIds::contains).forEach { id ->
            thumbs.remove(id)?.second?.let { if (!it.isRecycled) it.recycle() }
        }
        maskThumbs.keys.filterNot(liveIds::contains).forEach { id ->
            maskThumbs.remove(id)?.second?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun buildSigs(rows: List<Layer>): MutableList<Sig> {
        val flags = stack.map { LayerStackLogic.Flags(it.isClippingMask, it.isVisible, it.isBackground) }
        return rows.map { layer ->
            val stackIndex = stack.indexOfFirst { it.id == layer.id }
            val base = LayerStackLogic.clipBaseIndex(flags, stackIndex)
            Sig(
                id = layer.id, name = layer.name, opacity = layer.opacity, blend = layer.blendMode,
                visible = layer.isVisible, locked = layer.isLocked, alphaLock = layer.isAlphaLocked,
                clip = layer.isClippingMask, hasMask = layer.maskBitmap != null,
                editingMask = layer.isEditingMask, isBackground = layer.isBackground,
                generation = layer.bitmap.generationId,
                maskGeneration = layer.maskBitmap?.generationId ?: 0,
                active = layer.id == activeLayerId,
                expanded = layer.id == expandedId,
                clipBaseName = if (base >= 0) stack[base].name else null
            )
        }.toMutableList()
    }

    /** Live reorder while a row is being dragged. Only this adapter's own lists change. */
    fun moveItemLocally(from: Int, to: Int) {
        display.moveItem(from, to)
        sigs.moveItem(from, to)
        notifyItemMoved(from, to)
    }

    private fun setExpanded(layerId: String?) {
        expandedId = layerId
        recycler?.let {
            TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(PanelMotion.NORMAL))
        }
        submit(stack, activeLayerId)
    }

    // ------------------------------------------------------------------ view holder

    class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        val ctx: Context = card.context
        var boundId: String? = null
        var isBackgroundRow = false

        val clipArrow = ImageView(ctx)
        val thumbFrame = FrameLayout(ctx)
        val thumb = ImageView(ctx)
        val maskFrame = FrameLayout(ctx)
        val maskThumb = ImageView(ctx)
        val nameView = TextView(ctx)
        val lockBadge = ImageView(ctx)
        val alphaBadge = TextView(ctx)
        val subtitleView = TextView(ctx)
        val clipInfoView = TextView(ctx)
        val chevron = ImageView(ctx)
        val eyeBtn = ImageView(ctx)
        val handle = ImageView(ctx)

        val drawer = LinearLayout(ctx)
        val opacityLabel = TextView(ctx)
        val opacityBar = SeekBar(ctx)
        val blendRow = LinearLayout(ctx)
        val blendValue = TextView(ctx)
        lateinit var alphaRow: ToggleRow
        lateinit var clipRow: ToggleRow
        lateinit var lockRow: ToggleRow
        lateinit var maskRow: View
        lateinit var adjustmentsRow: View
        lateinit var filtersRow: View
        lateinit var transformRow: View
        lateinit var backgroundColorRow: View
    }

    class ToggleRow(context: Context, title: String, subtitle: String) {
        val view = LinearLayout(context)
        private val switch = SmoothSwitch(context)
        var onToggled: (() -> Unit)? = null

        init {
            fun dp(v: Int) = PanelUi.dp(context, v)
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            view.minimumHeight = dp(52)
            view.setPadding(dp(12), dp(6), dp(12), dp(6))
            view.background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            view.isClickable = true
            view.isFocusable = true
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
            col.addView(TextView(context).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            })
            view.addView(col)
            view.addView(switch)
            view.setOnClickListener { switch.performClick() }
            PanelMotion.press(view)
            setChecked(false)
        }

        /** Sets the switch to match the layer without firing [onToggled] - the state came from the layer. */
        fun setChecked(checked: Boolean) {
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = checked
            switch.setOnCheckedChangeListener { _, _ -> onToggled?.invoke() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        fun dp(v: Int) = PanelUi.dp(ctx, v)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        val h = VH(card)

        val mainRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(8), dp(6), dp(4), dp(6))
        }

        h.clipArrow.apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(4) }
            setImageResource(R.drawable.ic_clip_arrow)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.procreate_accent_light))
            visibility = View.GONE
        }
        mainRow.addView(h.clipArrow)

        h.thumbFrame.apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) }
            background = CheckerDrawable(dp(6).toFloat())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
        }
        h.thumb.layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        h.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
        h.thumbFrame.addView(h.thumb)
        mainRow.addView(h.thumbFrame)

        // A layer mask is a separate grayscale image, not a badge or a clipping relationship.
        // Showing it beside the content thumbnail matches Photoshop/Procreate and makes it clear
        // whether the brush is editing pixels or visibility.
        h.maskFrame.apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) }
            background = CheckerDrawable(dp(6).toFloat())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            visibility = View.GONE
            contentDescription = ctx.getString(R.string.layer_mask_badge)
        }
        h.maskThumb.layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        h.maskThumb.scaleType = ImageView.ScaleType.FIT_CENTER
        h.maskFrame.addView(h.maskThumb)
        mainRow.addView(h.maskFrame)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        h.nameView.apply {
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameRow.addView(h.nameView)
        h.lockBadge.apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(4) }
            setImageResource(R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.text_secondary))
            visibility = View.GONE
        }
        nameRow.addView(h.lockBadge)
        h.alphaBadge.apply {
            text = "α"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.procreate_accent_light))
            setPadding(dp(4), 0, 0, 0)
            visibility = View.GONE
        }
        nameRow.addView(h.alphaBadge)
        textCol.addView(nameRow)

        h.subtitleView.apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        textCol.addView(h.subtitleView)
        h.clipInfoView.apply {
            textSize = 11.5f
            maxLines = 2
            visibility = View.GONE
        }
        textCol.addView(h.clipInfoView)
        mainRow.addView(textCol)

        h.chevron.apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.icon_dim))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_ripple_flat)
            isClickable = true
        }
        mainRow.addView(h.chevron)

        h.eyeBtn.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.icon_tint_selector)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_ripple_flat)
            isClickable = true
        }
        mainRow.addView(h.eyeBtn)

        // Swipe the row itself: towards the start toggles Alpha Lock, the other way reveals delete
        // and duplicate. Procreate puts its most repeated layer actions on swipes for the same
        // reason - opening a drawer to flip Alpha Lock forty times an hour is real friction.
        attachSwipe(h, mainRow)

        h.handle.apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(48))
            setPadding(dp(6), dp(12), dp(6), dp(12))
            setImageResource(R.drawable.ic_drag_handle)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.icon_dim))
            // A handle starts the drag the instant it is touched - no waiting out the long-press
            // timeout. Long-pressing anywhere on the row still works too.
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) host.onStartDrag(h)
                false
            }
        }
        mainRow.addView(h.handle)
        card.addView(mainRow)

        buildDrawer(h)
        card.addView(h.drawer)
        return h
    }

    /**
     * Horizontal swipe handling for one row.
     *
     * Deliberately a touch listener on the row rather than an ItemTouchHelper swipe: the helper is
     * already installed for vertical drag-to-reorder, and giving it swipe flags as well makes the
     * two gestures compete - a slightly diagonal reorder starts sliding the row sideways instead.
     * [LayerSwipe] decides what counts as which, and is tested.
     */
    private fun attachSwipe(h: VH, row: View) {
        val ctx = h.ctx
        val density = ctx.resources.displayMetrics.density
        val isRtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

        var downX = 0f
        var downY = 0f
        var tracking = false

        row.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    tracking = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!tracking && abs(dx) > slop && abs(dx) > abs(dy)) {
                        tracking = true
                        // Once this is a horizontal swipe, stop the list scrolling underneath it.
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (tracking) {
                        v.translationX = LayerSwipe.followOffset(dx, density)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!tracking) return@setOnTouchListener false
                    tracking = false
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    v.animate().translationX(0f)
                        .setDuration(PanelMotion.NORMAL)
                        .setInterpolator(PanelMotion.springy)
                        .start()
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val id = h.boundId
                        when (LayerSwipe.classify(dx, dy, density, isRtl)) {
                            LayerSwipe.Action.ALPHA_LOCK -> id?.let {
                                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                host.onAlphaLock(it)
                            }
                            LayerSwipe.Action.QUICK_ACTIONS -> id?.let {
                                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                host.onQuickActions(it, v)
                            }
                            LayerSwipe.Action.NONE -> Unit
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun buildDrawer(h: VH) {
        val ctx = h.ctx
        fun dp(v: Int) = PanelUi.dp(ctx, v)
        h.drawer.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(4), dp(4), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.surface_2))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), dp(4)) }
        }

        h.opacityLabel.apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(dp(12), dp(10), dp(12), 0)
        }
        h.drawer.addView(h.opacityLabel)
        h.opacityBar.apply {
            max = 100
            progressDrawable = ContextCompat.getDrawable(ctx, R.drawable.seekbar_progress)
            thumb = ContextCompat.getDrawable(ctx, R.drawable.seekbar_thumb)
            thumbOffset = 0
            splitTrack = false
            setPadding(dp(14), dp(6), dp(14), dp(10))
        }
        h.drawer.addView(h.opacityBar)

        h.blendRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_list_item_ripple)
            isClickable = true
        }
        h.blendRow.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.layer_blend)
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        h.blendValue.apply {
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(ctx, R.color.procreate_accent_light))
            compoundDrawablePadding = dp(4)
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down, 0)
            compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.procreate_accent_light))
        }
        h.blendRow.addView(h.blendValue)
        h.drawer.addView(h.blendRow)

        h.adjustmentsRow = PanelUi.actionRow(
            ctx, R.drawable.ic_adjustments,
            ctx.getString(R.string.adjustments_title),
            ctx.getString(R.string.adjustments_sub)
        ) { h.boundId?.let { host.onAdjustments(it) } }
        h.filtersRow = PanelUi.actionRow(
            ctx, R.drawable.ic_filters,
            ctx.getString(R.string.filters_title),
            ctx.getString(R.string.filters_sub)
        ) { h.boundId?.let { host.onFilters(it) } }
        h.transformRow = PanelUi.actionRow(
            ctx, R.drawable.ic_transform,
            ctx.getString(R.string.tool_transform),
            ctx.getString(R.string.layer_transform_sub)
        ) { h.boundId?.let { host.onTransform(it) } }
        h.backgroundColorRow = PanelUi.actionRow(
            ctx, R.drawable.ic_colors,
            ctx.getString(R.string.layer_base_color),
            ctx.getString(R.string.layer_base_color_sub)
        ) { h.boundId?.let { host.onBackgroundColor(it) } }
        h.drawer.addView(h.backgroundColorRow)
        h.drawer.addView(h.adjustmentsRow)
        h.drawer.addView(h.filtersRow)
        h.drawer.addView(h.transformRow)

        h.alphaRow = ToggleRow(ctx, ctx.getString(R.string.layer_alpha_lock), ctx.getString(R.string.layer_alpha_lock_sub))
        h.clipRow = ToggleRow(ctx, ctx.getString(R.string.layer_clipping), ctx.getString(R.string.layer_clipping_sub))
        h.lockRow = ToggleRow(ctx, ctx.getString(R.string.layer_lock), ctx.getString(R.string.layer_lock_sub))
        h.drawer.addView(h.clipRow.view)
        h.drawer.addView(h.alphaRow.view)
        h.drawer.addView(h.lockRow.view)

        h.maskRow = PanelUi.actionRow(
            ctx, R.drawable.ic_layers,
            ctx.getString(R.string.layer_use_as_mask),
            ctx.getString(R.string.layer_use_as_mask_sub)
        ) { h.boundId?.let { host.onUseAsMask(it) } }
        h.drawer.addView(h.maskRow)
    }

    // ------------------------------------------------------------------ binding

    override fun onBindViewHolder(h: VH, position: Int) {
        val layer = display[position]
        val sig = sigs[position]
        val ctx = h.ctx
        fun dp(v: Int) = PanelUi.dp(ctx, v)
        val id = layer.id
        h.boundId = id
        h.isBackgroundRow = layer.isBackground

        h.card.background = ContextCompat.getDrawable(
            ctx, if (sig.active) R.drawable.bg_list_item_selected else R.drawable.bg_list_item_ripple
        )

        // Clipped layers are pulled in and marked, so the relationship is readable at a glance.
        val clipped = layer.isClippingMask
        h.clipArrow.visibility = if (clipped) View.VISIBLE else View.GONE
        (h.card.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = if (clipped) dp(18) else 0

        h.nameView.text = if (layer.isEditingMask) {
            ctx.getString(R.string.layer_editing_mask_format, layer.name)
        } else layer.name
        val normalSubtitle = ctx.getString(
            R.string.layer_subtitle_format,
            ctx.getString(blendLabelRes(layer.blendMode)),
            (layer.opacity * 100).roundToInt()
        )
        h.subtitleView.text = if (layer.maskBitmap != null) {
            "$normalSubtitle  ·  ${ctx.getString(R.string.layer_mask_badge)}"
        } else normalSubtitle
        h.lockBadge.visibility = if (layer.isLocked) View.VISIBLE else View.GONE
        h.alphaBadge.visibility = if (layer.isAlphaLocked) View.VISIBLE else View.GONE

        if (clipped) {
            h.clipInfoView.visibility = View.VISIBLE
            val hasBase = sig.clipBaseName != null
            h.clipInfoView.text = if (hasBase) ctx.getString(R.string.layer_clipped_to, sig.clipBaseName)
            else ctx.getString(R.string.layer_clip_no_base)
            h.clipInfoView.setTextColor(
                ContextCompat.getColor(ctx, if (hasBase) R.color.procreate_accent_light else R.color.danger)
            )
        } else {
            h.clipInfoView.visibility = View.GONE
        }

        h.thumb.setImageBitmap(thumbFor(layer))
        val dim = if (layer.isVisible) 1f else 0.45f
        h.thumbFrame.alpha = dim
        val mask = layer.maskBitmap
        h.maskFrame.visibility = if (mask == null) View.GONE else View.VISIBLE
        h.maskFrame.alpha = dim
        if (mask != null) h.maskThumb.setImageBitmap(maskThumbFor(layer.id, mask))
        (h.thumbFrame.layoutParams as LinearLayout.LayoutParams).marginEnd =
            dp(if (mask == null) 10 else 4)

        fun outline(selected: Boolean) = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            setStroke(
                dp(if (selected) 2 else 1),
                ContextCompat.getColor(
                    ctx,
                    if (selected) R.color.procreate_accent else R.color.outline_subtle
                )
            )
        }
        h.thumbFrame.foreground = outline(sig.active && !layer.isEditingMask)
        h.maskFrame.foreground = outline(sig.active && layer.isEditingMask)
        h.thumbFrame.setOnClickListener {
            host.onSelect(id)
            if (layer.isEditingMask) host.onUseAsMask(id)
        }
        h.maskFrame.setOnClickListener {
            host.onSelect(id)
            if (!layer.isEditingMask) host.onUseAsMask(id)
        }
        h.nameView.alpha = dim

        h.eyeBtn.setImageResource(if (layer.isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
        h.eyeBtn.alpha = if (layer.isVisible) 1f else 0.6f
        h.eyeBtn.setOnClickListener { host.onVisibility(id) }

        val expanded = sig.expanded
        h.drawer.visibility = if (expanded) View.VISIBLE else View.GONE
        h.chevron.rotation = if (expanded) 180f else 0f
        h.chevron.setOnClickListener {
            host.onSelect(id)
            setExpanded(if (expandedId == id) null else id)
        }

        // First tap selects; a tap on the already-selected layer opens or closes its drawer.
        h.card.setOnClickListener {
            if (activeLayerId == id) setExpanded(if (expandedId == id) null else id)
            else host.onSelect(id)
        }

        if (expanded) bindDrawer(h, layer)
    }

    private fun bindDrawer(h: VH, layer: Layer) {
        val ctx = h.ctx
        val id = layer.id

        h.opacityLabel.text = ctx.getString(
            R.string.layer_opacity_format, ctx.getString(R.string.opacity), (layer.opacity * 100).roundToInt()
        )
        // Never write into a slider the finger is holding: the observer republishes on every tick.
        if (!h.opacityBar.isPressed) h.opacityBar.progress = (layer.opacity * 100).roundToInt()
        h.opacityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    h.opacityLabel.text = ctx.getString(
                        R.string.layer_opacity_format, ctx.getString(R.string.opacity), progress
                    )
                    host.onOpacity(id, progress / 100f)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        h.blendValue.text = ctx.getString(blendLabelRes(layer.blendMode))
        h.blendRow.setOnClickListener { host.onBlend(id, h.blendValue, layer.blendMode) }

        h.alphaRow.onToggled = { host.onAlphaLock(id) }
        h.clipRow.onToggled = { host.onClipping(id) }
        h.lockRow.onToggled = { host.onLock(id) }
        h.alphaRow.setChecked(layer.isAlphaLocked)
        h.clipRow.setChecked(layer.isClippingMask)
        h.lockRow.setChecked(layer.isLocked)

        // The paper has no neighbour it could meaningfully mask, and clipping it is meaningless.
        h.maskRow.visibility = if (layer.isBackground) View.GONE else View.VISIBLE
        h.clipRow.view.visibility = if (layer.isBackground) View.GONE else View.VISIBLE
        h.transformRow.visibility = if (layer.isBackground || layer.isEditingMask) View.GONE else View.VISIBLE
        h.backgroundColorRow.visibility = if (layer.isBackground) View.VISIBLE else View.GONE
    }

    private fun thumbFor(layer: Layer): Bitmap {
        val gen = layer.bitmap.generationId
        thumbs[layer.id]?.let { (g, bmp) -> if (g == gen && !bmp.isRecycled) return bmp }
        val src = layer.bitmap
        val scale = THUMB_EDGE.toFloat() / max(src.width, src.height)
        val tw = max(1, (src.width * scale).toInt())
        val th = max(1, (src.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
        val bmp = if (scaled === src) src.copy(Bitmap.Config.ARGB_8888, false) else scaled
        thumbs.remove(layer.id)?.second?.let { old ->
            if (old !== bmp && !old.isRecycled) old.recycle()
        }
        thumbs[layer.id] = gen to bmp
        return bmp
    }

    private fun maskThumbFor(layerId: String, source: Bitmap): Bitmap {
        val gen = source.generationId
        maskThumbs[layerId]?.let { (g, bmp) -> if (g == gen && !bmp.isRecycled) return bmp }
        val scale = THUMB_EDGE.toFloat() / max(source.width, source.height)
        val tw = max(1, (source.width * scale).toInt())
        val th = max(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, tw, th, true)
        val thumbnail = if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, false) else scaled
        return thumbnail.also { bitmap ->
            maskThumbs.remove(layerId)?.second?.let { old ->
                if (old !== bitmap && !old.isRecycled) old.recycle()
            }
            maskThumbs[layerId] = gen to bitmap
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        (thumbs.values + maskThumbs.values).forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        thumbs.clear()
        maskThumbs.clear()
        recycler = null
    }

    override fun getItemCount() = display.size

    private companion object {
        /** Twice the on-screen size, so thumbnails stay sharp on a high-density tablet. */
        const val THUMB_EDGE = 120
    }
}
