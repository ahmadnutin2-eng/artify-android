package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.procreate.android.project.UrbanProjectDto
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class ToolMode { DRAW, SMUDGE, BLUR, SELECTION, TRANSFORM }

/** The paper a canvas starts on. Painted into the background layer by [CanvasBackground]. */
enum class CanvasBackgroundStyle { BLANK, DOT_GRID, LINE_GRID, ISOMETRIC }

class CanvasViewModel : ViewModel() {
    private val _layers = MutableLiveData<List<Layer>>(emptyList())
    val layers: LiveData<List<Layer>> = _layers

    private val _backgroundStyle = MutableLiveData(CanvasBackgroundStyle.BLANK)
    val backgroundStyle: LiveData<CanvasBackgroundStyle> = _backgroundStyle

    private val _backgroundColor = MutableLiveData(Color.WHITE)
    val backgroundColor: LiveData<Int> = _backgroundColor

    private val _activeLayerIndex = MutableLiveData<Int>(-1)
    val activeLayerIndex: LiveData<Int> = _activeLayerIndex

    private val _currentBrush = MutableLiveData<BrushProperties>(BrushProperties())
    val currentBrush: LiveData<BrushProperties> = _currentBrush

    /** Identity of the preset the mutable working brush started from. Size/opacity sliders edit a
     * copy of its properties, so comparing the whole object cannot reliably keep the chosen row
     * highlighted after the first adjustment. */
    private val _currentBrushPresetId = MutableLiveData<String?>(null)
    val currentBrushPresetId: LiveData<String?> = _currentBrushPresetId

    private val _currentColor = MutableLiveData<Int>(Color.BLACK)
    val currentColor: LiveData<Int> = _currentColor

    private val _toolMode = MutableLiveData(ToolMode.DRAW)
    val toolMode: LiveData<ToolMode> = _toolMode

    /**
     * Whether this canvas grows to follow the work. In open mode the canvas starts at a
     * comfortable size and gains a band of new paper whenever a stroke lands near an edge, up to
     * what the heap can hold - so there's no wall to run into mid-drawing, without paying up front
     * for an enormous mostly-empty bitmap.
     */
    var isOpenCanvas: Boolean = false

    /** Grid pitch, fixed at creation so an expanding canvas' pattern never shifts phase. */
    private var backgroundPitch: Int = 48

    /** Used by DrawingView to continue the exact same paper pattern beyond allocated open-canvas
     * bitmap bounds. Kept read-only so expansion remains the sole owner of pattern phase. */
    fun backgroundPatternPitch(): Int = backgroundPitch

    private val history = UndoHistory()

    /** One 256px pre-edit snapshot. Only tiles the brush actually reaches are retained. */
    private data class CapturedTile(var left: Int, var top: Int, val before: Bitmap)

    /** Sparse pre-edit state for the stroke currently under the pen. */
    private data class EditCapture(
        val layerId: String,
        val target: PixelTarget,
        /** Undo tiles keep their stroke-start grid even when left/top growth rebases the artwork. */
        var gridOriginX: Int = 0,
        var gridOriginY: Int = 0,
        val tiles: LinkedHashMap<Long, CapturedTile> = LinkedHashMap()
    )

    private var editCapture: EditCapture? = null

    /** Layer structure as of the last committed edit, for building the next [StackEdit]. */
    private var lastStructure: List<LayerState> = emptyList()

    /** True once the user has actually changed something worth persisting (not just opened a blank canvas). */
    var hasUnsavedContent = false
        private set

    /** Monotonic revision used to avoid clearing the dirty flag when an older asynchronous save
     * finishes after the user has already made another edit. */
    private var contentRevision = 0L

    /** DrawingView owns the mutable Urban runtime objects, while this ViewModel owns the shared
     * chronological history. The callback is the narrow bridge used when an Urban entry is
     * undone/redone. */
    private var urbanStateRestorer: ((UrbanProjectDto) -> Unit)? = null

    private var pendingLoadBitmap: Bitmap? = null
    private data class PendingProjectLayers(
        val layers: List<Layer>,
        val activeLayerId: String?,
        val backgroundStyle: CanvasBackgroundStyle,
        val backgroundColor: Int,
        val isOpenCanvas: Boolean
    )
    private var pendingProjectLayers: PendingProjectLayers? = null

    /** Non-null only inside a live room; new layers then automatically belong to this artist. */
    private var collaborationLocalOwnerId: String? = null

    fun setToolMode(mode: ToolMode) {
        _toolMode.value = mode
    }

    fun setUrbanStateRestorer(restorer: (UrbanProjectDto) -> Unit) {
        urbanStateRestorer = restorer
    }

    /** Records one complete Urban operation in the same timeline as raster and layer edits. */
    fun commitUrbanEdit(before: UrbanProjectDto, after: UrbanProjectDto) {
        if (before == after) return
        history.push(UrbanEdit(before, after))
        markDirty()
    }

    /** Marks non-history project settings (for example workspace/overlay visibility) as changed. */
    fun markProjectChanged() = markDirty()

    fun currentContentRevision(): Long = contentRevision

    /** Only the exact revision that was written may become clean. */
    fun markProjectSaved(savedRevision: Long) {
        if (savedRevision == contentRevision) hasUnsavedContent = false
    }

    private fun markDirty() {
        contentRevision += 1L
        hasUnsavedContent = true
    }

    /** Queues a previously-saved flattened artwork to become the starting layer of the next initialize(). */
    fun setPendingLoad(bitmap: Bitmap) {
        pendingLoadBitmap = bitmap
    }

    /** Queues a complete editable layer stack loaded from a versioned project document. */
    fun setPendingProjectLayers(
        layers: List<Layer>,
        activeLayerId: String?,
        backgroundStyle: CanvasBackgroundStyle,
        backgroundColor: Int = Color.WHITE,
        isOpenCanvas: Boolean
    ) {
        require(layers.isNotEmpty()) { "A project must contain at least one raster layer" }
        val width = layers.first().bitmap.width
        val height = layers.first().bitmap.height
        require(layers.all { it.bitmap.width == width && it.bitmap.height == height }) {
            "All project layers must share the canvas dimensions"
        }
        require(layers.all { it.maskBitmap == null ||
            (it.maskBitmap!!.width == width && it.maskBitmap!!.height == height) }) {
            "Every layer mask must match the canvas dimensions"
        }
        pendingProjectLayers = PendingProjectLayers(
            layers, activeLayerId, backgroundStyle, backgroundColor, isOpenCanvas
        )
        pendingLoadBitmap = null
    }

    /**
     * Installs a project that finished decoding after the view was already laid out. Large editable
     * projects are intentionally loaded off the main thread; forcing them through the old pending
     * path required blocking Activity.onCreate and could trigger an ANR before the first frame.
     * This swap itself is small and runs on main, where LiveData and the drawing view expect it.
     */
    fun replaceProjectLayers(
        layers: List<Layer>,
        activeLayerId: String?,
        backgroundStyle: CanvasBackgroundStyle,
        backgroundColor: Int = Color.WHITE,
        openCanvas: Boolean
    ) {
        require(layers.isNotEmpty()) { "A project must contain at least one raster layer" }
        val width = layers.first().bitmap.width
        val height = layers.first().bitmap.height
        require(layers.all { it.bitmap.width == width && it.bitmap.height == height }) {
            "All project layers must share the canvas dimensions"
        }
        require(layers.all { it.maskBitmap == null ||
            (it.maskBitmap!!.width == width && it.maskBitmap!!.height == height) }) {
            "Every layer mask must match the canvas dimensions"
        }

        val incoming = layers.flatMapTo(HashSet()) { layer ->
            buildList {
                add(layer.bitmap)
                layer.maskBitmap?.let(::add)
            }
        }
        _layers.value.orEmpty().forEach { old ->
            if (old.bitmap !in incoming && !old.bitmap.isRecycled) old.bitmap.recycle()
            old.maskBitmap?.let { mask ->
                if (mask !in incoming && !mask.isRecycled) mask.recycle()
            }
        }
        backgroundPitch = CanvasBackground.pitchFor(width, height)
        _backgroundStyle.value = backgroundStyle
        _backgroundColor.value = backgroundColor
        isOpenCanvas = openCanvas
        _layers.value = layers
        _activeLayerIndex.value = layers.indexOfFirst { it.id == activeLayerId }
            .takeIf { it >= 0 } ?: layers.lastIndex
        pendingProjectLayers = null
        pendingLoadBitmap = null
        resetHistoryBaseline()
    }

    /** width/height here already account for DrawingView's own pendingCanvasSize override (the
     * New Canvas dialog's size choice) - that resolution happens in DrawingView.onSizeChanged,
     * one level up, so this method only ever needs the final chosen pixel size. */
    fun initialize(width: Int, height: Int) {
        if (!_layers.value.isNullOrEmpty()) return

        backgroundPitch = CanvasBackground.pitchFor(width, height)

        pendingProjectLayers?.let { pending ->
            _layers.value = pending.layers
            _activeLayerIndex.value = pending.layers.indexOfFirst { it.id == pending.activeLayerId }
                .takeIf { it >= 0 } ?: pending.layers.lastIndex
            _backgroundStyle.value = pending.backgroundStyle
            _backgroundColor.value = pending.backgroundColor
            isOpenCanvas = pending.isOpenCanvas
            pendingProjectLayers = null
            resetHistoryBaseline()
            return
        }

        pendingLoadBitmap?.let { loaded ->
            val layer = Layer(id = UUID.randomUUID().toString(), name = "Layer 1", bitmap = loaded)
            _layers.value = listOf(layer)
            _activeLayerIndex.value = 0
            pendingLoadBitmap = null
            resetHistoryBaseline()
            return
        }

        val layer1 = Layer(
            id = UUID.randomUUID().toString(),
            name = "Layer 1",
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        )
        // Every new project has an explicit Base layer. It is opaque white by default, protected
        // from accidental brush edits, and its colour/paper pattern remain editable in Layers.
        val baseLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Base",
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
            isLocked = true,
            isBackground = true
        )
        CanvasBackground.paint(
            baseLayer.bitmap,
            _backgroundStyle.value ?: CanvasBackgroundStyle.BLANK,
            backgroundPitch,
            _backgroundColor.value ?: Color.WHITE
        )
        _layers.value = listOf(baseLayer, layer1)
        _activeLayerIndex.value = 1
        resetHistoryBaseline()
    }

    // ==================== Background style ====================

    /**
     * Repaint the paper. Only the layer flagged [Layer.isBackground] is touched, and the repaint
     * is pushed as a normal pixel edit so it undoes like anything else.
     */
    fun setBackgroundStyle(style: CanvasBackgroundStyle) {
        _backgroundStyle.value = style
        val layers = _layers.value ?: return
        val index = layers.indexOfFirst { it.isBackground }
        if (index < 0) {
            // Open canvases intentionally have no giant raster background. The procedural paper
            // reads this LiveData directly, so publishing/dirtying is all a style change needs.
            if (isOpenCanvas) {
                _layers.value = layers
                markDirty()
            }
            return
        }

        val layer = layers[index]
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        CanvasBackground.paint(
            layer.bitmap, style, backgroundPitch, _backgroundColor.value ?: Color.WHITE
        )
        pushFullLayerEdit(layer, before)
        _layers.value = layers
        markDirty()
    }

    /** Repaints only the protected Base layer, preserving every artwork layer above it. */
    fun setBackgroundColor(color: Int) {
        if (_backgroundColor.value == color) return
        _backgroundColor.value = color
        val layers = _layers.value ?: return
        val index = layers.indexOfFirst { it.isBackground }
        if (index < 0) {
            _layers.value = layers
            markDirty()
            return
        }
        val layer = layers[index]
        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        CanvasBackground.paint(
            layer.bitmap,
            _backgroundStyle.value ?: CanvasBackgroundStyle.BLANK,
            backgroundPitch,
            color
        )
        pushFullLayerEdit(layer, before)
        _layers.value = layers
        markDirty()
    }

    // ==================== Open canvas ====================

    /** Raised when an open canvas needed to grow and could not, so the view can tell the user why
     * the paper stopped following the pen instead of the strokes just quietly disappearing. */
    var onGrowthBlocked: (() -> Unit)? = null

    /**
     * Grow the canvas if [dirty] (canvas-space bounds of what was just drawn) came close to an
     * edge. Returns the offset every existing pixel was shifted by, so the caller can move the
     * view by the same amount and leave the artwork visually stationary.
     */
    fun expandIfNeeded(dirty: RectF): PointF? {
        if (!isOpenCanvas) return null
        val layers = _layers.value ?: return null
        val base = layers.firstOrNull()?.bitmap ?: return null

        // Growth is quantised to whole grid cells so a dotted or ruled background stays in phase
        // across the seam instead of stepping by a fraction of a cell.
        // Keep patterned paper in phase. Undo owns a movable grid origin, so growth no longer has
        // to be a common multiple of the paper pitch and 256. With relatively-prime values that
        // LCM could exceed 15,000px, turning a tiny edge stroke into a huge allocation that the
        // device correctly refused.
        val quantum = backgroundPitch.coerceAtLeast(1)
        val chunk = ((EXPAND_CHUNK_PX + quantum - 1) / quantum).coerceAtLeast(1) * quantum
        val margin = EXPAND_MARGIN_PX.toFloat()

        // As many whole chunks as it takes to put the drawing inside the paper. One chunk per call
        // (the old rule) could not reach a pen put down far outside the sheet, so the first samples
        // were painted onto nothing - the "open" canvas only opened for someone who crept up to the
        // edge first.
        val wanted = CanvasGrowth.plan(
            dirty.left, dirty.top, dirty.right, dirty.bottom,
            base.width, base.height, margin, chunk
        )
        if (wanted.isNone) return null

        // Cover everything if memory allows; otherwise settle for one chunk per side rather than
        // refusing outright; and if even that does not fit, say so instead of failing silently.
        var plan = wanted
        val bitmapPlanes = layers.sumOf { if (it.maskBitmap == null) 1 else 2 }
        if (!canAfford(base.width + plan.left + plan.right, base.height + plan.top + plan.bottom, bitmapPlanes)) {
            plan = wanted.cappedTo(chunk)
            if (!canAfford(base.width + plan.left + plan.right, base.height + plan.top + plan.bottom, bitmapPlanes)) {
                onGrowthBlocked?.invoke()
                return null
            }
        }
        val growLeft = plan.left
        val growTop = plan.top
        val growRight = plan.right
        val growBottom = plan.bottom
        val newWidth = base.width + growLeft + growRight
        val newHeight = base.height + growTop + growBottom

        // Allocate the complete replacement set before changing a single live layer. A device can
        // still refuse a large native bitmap despite the conservative budget above (fragmented
        // graphics memory is the usual reason). Mutating the stack one layer at a time made such a
        // refusal leave a half-grown document behind. This transaction either succeeds completely
        // or recycles its temporary buffers and keeps the current canvas untouched.
        data class Replacement(val layer: Layer, val content: Bitmap, val mask: Bitmap?)
        val replacements = ArrayList<Replacement>(layers.size)
        try {
            for (layer in layers) {
                val grown = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(grown)
                if (layer.isBackground) {
                    // Paint fresh paper across the whole enlarged sheet first, then lay the old
                    // sheet back on top so the pattern remains seamless.
                    CanvasBackground.paint(
                        grown,
                        _backgroundStyle.value ?: CanvasBackgroundStyle.BLANK,
                        backgroundPitch,
                        _backgroundColor.value ?: Color.WHITE
                    )
                }
                canvas.drawBitmap(layer.bitmap, growLeft.toFloat(), growTop.toFloat(), null)
                val grownMask = layer.maskBitmap?.let { oldMask ->
                    Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888).also { mask ->
                        // New paper should remain revealed; a white mask is the neutral value.
                        mask.eraseColor(Color.WHITE)
                        Canvas(mask).drawBitmap(oldMask, growLeft.toFloat(), growTop.toFloat(), SRC_PAINT)
                    }
                }
                replacements += Replacement(layer, grown, grownMask)
            }

        } catch (_: OutOfMemoryError) {
            replacements.forEach { replacement ->
                if (!replacement.content.isRecycled) replacement.content.recycle()
                replacement.mask?.let { if (!it.isRecycled) it.recycle() }
            }
            onGrowthBlocked?.invoke()
            return null
        }

        val remapped = HashMap<String, Bitmap>(layers.size)
        val remappedMasks = HashMap<String, Bitmap>(layers.size)
        val replacedBitmaps = ArrayList<Bitmap>(layers.size)
        val replacedMasks = ArrayList<Bitmap>(layers.size)
        for (replacement in replacements) {
            val layer = replacement.layer
            replacedBitmaps += layer.bitmap
            layer.maskBitmap?.let(replacedMasks::add)
            layer.bitmap = replacement.content
            layer.maskBitmap = replacement.mask
            remapped[layer.id] = replacement.content
            replacement.mask?.let { remappedMasks[layer.id] = it }
        }

        history.rebaseAfterExpand(growLeft, growTop, remapped, remappedMasks)
        lastStructure.forEach { state ->
            remapped[state.id]?.let { state.bitmap = it }
            remappedMasks[state.id]?.let { state.maskBitmap = it }
        }
        rebaseEditCapture(growLeft, growTop)

        // Android bitmaps use a large native/graphics allocation. Waiting for a later GC after
        // every edge expansion left both the old and grown stacks resident for several seconds;
        // repeated edge strokes briefly pushed the process close to a gigabyte. All history and
        // structural references have now been rebased, so these superseded buffers are safe to
        // release immediately.
        replacedBitmaps.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        replacedMasks.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }

        _layers.value = layers
        markDirty()
        return PointF(growLeft.toFloat(), growTop.toFloat())
    }

    /**
     * Bytes the system currently reports as free, supplied from the view layer because a ViewModel
     * has no Context of its own. Left null in unit tests, where the Java-heap estimate is used.
     */
    var availableMemoryProbe: (() -> Long)? = null

    /** Whether a canvas of this size, times this many layers, fits in memory with room to work in. */
    private fun canAfford(width: Int, height: Int, layerCount: Int): Boolean {
        if (width > MAX_CANVAS_SIDE || height > MAX_CANVAS_SIDE) return false
        // During growth the old and new layer stacks coexist. Sparse undo no longer keeps a full
        // shadow canvas alive, so reserve one additional bitmap for filter/export scratch instead
        // of the previous two full stacks. This is the main reason open paper can grow much farther
        // without the process approaching a gigabyte.
        val bytes = width.toLong() * height.toLong() * 4L * (max(1, layerCount) * 2 + 1)
        if (bytes > MAX_CANVAS_BYTES) return false
        // Bitmap pixels are a native allocation, not a Java-heap one, so deriving this budget from
        // Runtime.maxMemory() measured the wrong pool entirely. On a tablet with a 512 MB Java heap
        // that capped the paper at roughly two expansions while the device still had gigabytes
        // free - experienced as the canvas refusing to follow the pen past the edge, with the
        // "reached its memory limit" hint appearing on a machine that was nowhere near its limit.
        // Ask the system what is actually available and keep to a fraction of it. The free figure
        // already excludes the stack about to be replaced, so requiring the whole new estimate to
        // fit inside it leaves a deliberate margin on top.
        val available = availableMemoryProbe?.invoke()
        val budget = if (available != null && available > 0L) {
            (available * 0.45).toLong()
        } else {
            (Runtime.getRuntime().maxMemory() * 0.42).toLong()
        }
        return bytes <= budget
    }

    // ==================== History ====================

    /** Start a sparse undo capture. Actual tiles are copied immediately before each brush write. */
    fun prepareEdit(layerIndex: Int) {
        val layer = _layers.value?.getOrNull(layerIndex) ?: return
        discardEditCapture()
        val target = if (layer.isEditingMask && layer.maskBitmap != null) {
            PixelTarget.MASK
        } else PixelTarget.CONTENT
        editCapture = EditCapture(layer.id, target)
    }

    /**
     * Preserve every undo tile intersecting [bounds] before the brush mutates it. Repeated dabs in
     * the same tile are free: the first snapshot remains the stroke's true before-image.
     */
    fun captureBeforeEdit(layerIndex: Int, bounds: RectF) {
        if (bounds.isEmpty) return
        val layer = _layers.value?.getOrNull(layerIndex) ?: return
        val capture = editCapture?.takeIf { it.layerId == layer.id } ?: return
        val bitmap = editBitmap(layer, capture.target) ?: return
        val left = max(0, kotlin.math.floor(bounds.left).toInt())
        val top = max(0, kotlin.math.floor(bounds.top).toInt())
        val right = min(bitmap.width, kotlin.math.ceil(bounds.right).toInt())
        val bottom = min(bitmap.height, kotlin.math.ceil(bounds.bottom).toInt())
        if (right <= left || bottom <= top) return

        var tileY = Math.floorDiv(top - capture.gridOriginY, UNDO_TILE_SIZE) *
            UNDO_TILE_SIZE + capture.gridOriginY
        while (tileY < bottom) {
            var tileX = Math.floorDiv(left - capture.gridOriginX, UNDO_TILE_SIZE) *
                UNDO_TILE_SIZE + capture.gridOriginX
            while (tileX < right) {
                val key = tileKey(tileX, tileY)
                if (key !in capture.tiles) {
                    val before = try {
                        snapshotUndoTile(bitmap, tileX, tileY)
                    } catch (_: OutOfMemoryError) {
                        // A partial before-image would make Undo erase only fragments of a stroke.
                        // Disable history for this stroke atomically while allowing paint to continue.
                        discardEditCapture()
                        return
                    }
                    capture.tiles[key] = CapturedTile(tileX, tileY, before)
                }
                tileX += UNDO_TILE_SIZE
            }
            tileY += UNDO_TILE_SIZE
        }
    }

    /**
     * Record the pixels a stroke changed. [dirty] is in canvas space and gets clamped and padded
     * to the layer's bounds; only that rectangle is copied.
     */
    fun commitPixelEdit(layerIndex: Int, dirty: RectF) {
        val layers = _layers.value ?: return
        val layer = layers.getOrNull(layerIndex) ?: return
        val capture = editCapture?.takeIf { it.layerId == layer.id }
        editCapture = null
        if (dirty.isEmpty) {
            capture?.tiles?.values?.forEach { if (!it.before.isRecycled) it.before.recycle() }
            return
        }
        if (capture == null || capture.tiles.isEmpty()) return
        val editedBitmap = editBitmap(layer, capture.target) ?: run {
            capture.tiles.values.forEach { if (!it.before.isRecycled) it.before.recycle() }
            return
        }

        val patches = ArrayList<PixelPatch>(capture.tiles.size)
        try {
            capture.tiles.values.forEach { tile ->
                patches += PixelPatch(
                    tile.left,
                    tile.top,
                    tile.before,
                    snapshotUndoTile(editedBitmap, tile.left, tile.top)
                )
            }
        } catch (_: OutOfMemoryError) {
            // Before-images already transferred to built patches must be released together with
            // their after-images; the rest still belongs to the capture.
            val transferred = patches.mapTo(HashSet()) { it.before }
            patches.forEach(PixelPatch::recycle)
            capture.tiles.values.forEach { tile ->
                if (tile.before !in transferred && !tile.before.isRecycled) tile.before.recycle()
            }
            // The pixels are already safely on the live layer. Under extreme pressure keep the
            // artwork and forgo this one undo step instead of crashing the whole drawing session.
            markDirty()
            return
        }
        history.push(PixelEdit(layer.id, patches, capture.target))
        markDirty()
    }

    /**
     * Re-publishes the layer list after something wrote directly into a layer's bitmap, so the
     * canvas redraws.
     *
     * Needed because a bitmap is mutated in place: the list and the Layer objects are unchanged by
     * reference, so LiveData has nothing to compare and observers would never fire. Used by the
     * adjustments preview, which repaints a layer many times before anything is committed - hence
     * no history entry here, only a redraw.
     */
    fun notifyLayerPixelsChanged() {
        _layers.value = _layers.value
        markDirty()
    }

    /** Record a whole-layer change (fill, transform, adjustment, background repaint). */
    fun commitLayerEdit(layerIndex: Int, before: Bitmap) {
        val layer = _layers.value?.getOrNull(layerIndex) ?: return
        pushFullLayerEdit(layer, before)
    }

    /** Commits a filter/adjustment that touched one bounded area instead of duplicating the whole
     * layer. [before] becomes history-owned on success and must not be recycled by the caller. */
    fun commitLayerRegionEdit(layerIndex: Int, left: Int, top: Int, before: Bitmap): Boolean {
        val layer = _layers.value?.getOrNull(layerIndex) ?: return false
        if (left >= layer.bitmap.width || top >= layer.bitmap.height) return false
        val width = min(before.width, layer.bitmap.width - left)
        val height = min(before.height, layer.bitmap.height - top)
        if (width <= 0 || height <= 0) return false
        val after = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(after).drawBitmap(
            layer.bitmap,
            Rect(left, top, left + width, top + height),
            Rect(0, 0, width, height),
            SRC_PAINT
        )
        history.push(PixelEdit(layer.id, left, top, before, after, PixelTarget.CONTENT))
        discardEditCapture()
        markDirty()
        return true
    }

    fun commitMaskEdit(layerIndex: Int, before: Bitmap) {
        val layer = _layers.value?.getOrNull(layerIndex) ?: return
        val mask = layer.maskBitmap ?: return
        val after = mask.copy(Bitmap.Config.ARGB_8888, false)
        history.push(PixelEdit(layer.id, 0, 0, before, after, PixelTarget.MASK))
        if (editCapture?.layerId == layer.id) discardEditCapture()
        markDirty()
    }

    private fun pushFullLayerEdit(layer: Layer, before: Bitmap) {
        val after = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        history.push(PixelEdit(layer.id, 0, 0, before, after))
        if (editCapture?.layerId == layer.id) discardEditCapture()
        markDirty()
    }

    /**
     * Record a change to the layer *structure* (order, count, flags, opacity, blend mode).
     * Bitmaps are referenced, not copied.
     */
    fun saveState(markDirty: Boolean = true) {
        val current = (_layers.value ?: emptyList()).map { LayerState.of(it) }
        if (lastStructure.isNotEmpty() || current.isNotEmpty()) {
            history.push(StackEdit(lastStructure, current))
        }
        lastStructure = current
        if (markDirty) this.markDirty()
    }

    /** Forget all history and treat the current stack as the starting point. */
    private fun resetHistoryBaseline() {
        history.clear()
        discardEditCapture()
        lastStructure = (_layers.value ?: emptyList()).map { LayerState.of(it) }
        contentRevision = 0L
        hasUnsavedContent = false
    }

    private fun snapshotUndoTile(source: Bitmap, left: Int, top: Int): Bitmap {
        val tile = Bitmap.createBitmap(UNDO_TILE_SIZE, UNDO_TILE_SIZE, Bitmap.Config.ARGB_8888)
        val srcLeft = max(0, left)
        val srcTop = max(0, top)
        val right = min(source.width, left + UNDO_TILE_SIZE)
        val bottom = min(source.height, top + UNDO_TILE_SIZE)
        if (srcLeft < right && srcTop < bottom) {
            val src = Rect(srcLeft, srcTop, right, bottom)
            val dstLeft = srcLeft - left
            val dstTop = srcTop - top
            val dst = Rect(dstLeft, dstTop, dstLeft + src.width(), dstTop + src.height())
            Canvas(tile).drawBitmap(source, src, dst, SRC_PAINT)
        }
        return tile
    }

    private fun editBitmap(layer: Layer, target: PixelTarget): Bitmap? = when (target) {
        PixelTarget.CONTENT -> layer.bitmap
        PixelTarget.MASK -> layer.maskBitmap
    }

    private fun discardEditCapture() {
        editCapture?.tiles?.values?.forEach { tile ->
            if (!tile.before.isRecycled) tile.before.recycle()
        }
        editCapture = null
    }

    private fun rebaseEditCapture(dx: Int, dy: Int) {
        val capture = editCapture ?: return
        if (dx == 0 && dy == 0) return
        capture.gridOriginX += dx
        capture.gridOriginY += dy
        val rebased = LinkedHashMap<Long, CapturedTile>(capture.tiles.size)
        capture.tiles.values.forEach { tile ->
            tile.left += dx
            tile.top += dy
            rebased[tileKey(tile.left, tile.top)] = tile
        }
        capture.tiles.clear()
        capture.tiles.putAll(rebased)
    }

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    fun undo() {
        when (val entry = history.popUndo()) {
            is PixelEdit -> applyPatches(entry, before = true)
            is StackEdit -> applyStructure(entry.before)
            is UrbanEdit -> applyUrbanState(entry.before)
            null -> return
        }
    }

    fun redo() {
        when (val entry = history.popRedo()) {
            is PixelEdit -> applyPatches(entry, before = false)
            is StackEdit -> applyStructure(entry.after)
            is UrbanEdit -> applyUrbanState(entry.after)
            null -> return
        }
    }

    private fun applyPatches(edit: PixelEdit, before: Boolean) {
        val layers = _layers.value ?: return
        val layer = layers.firstOrNull { it.id == edit.layerId } ?: return
        discardEditCapture()
        val target = editBitmap(layer, edit.target) ?: return
        val canvas = Canvas(target)
        edit.patches.forEach { patch ->
            canvas.drawBitmap(
                if (before) patch.before else patch.after,
                patch.left.toFloat(),
                patch.top.toFloat(),
                SRC_PAINT
            )
        }
        _layers.value = layers
        markDirty()
    }

    private fun applyStructure(states: List<LayerState>) {
        val restored = states.map { it.toLayer() }
        lastStructure = states
        _layers.value = restored
        _activeLayerIndex.value = (_activeLayerIndex.value ?: 0).coerceIn(0, max(0, restored.size - 1))
        discardEditCapture()
        markDirty()
    }

    private fun applyUrbanState(state: UrbanProjectDto) {
        urbanStateRestorer?.invoke(state) ?: return
        markDirty()
    }

    // ==================== Layers ====================

    /**
     * Gives each participant a deterministic layer group. Both devices therefore composite
     * translucent simultaneous strokes in the same order, independent of who joined first.
     */
    fun configureCollaboration(
        localId: String,
        localName: String,
        partnerId: String,
        partnerName: String
    ): Boolean {
        val current = _layers.value?.toMutableList() ?: return false
        val first = current.firstOrNull() ?: return false
        collaborationLocalOwnerId = localId
        current.filter { !it.isBackground && it.collaborationOwnerId == null }.forEachIndexed { i, layer ->
            layer.collaborationOwnerId = localId
            if (i == 0) layer.name = localName
        }
        if (current.none { it.collaborationOwnerId == partnerId }) {
            current += Layer(
                id = UUID.randomUUID().toString(),
                name = partnerName,
                bitmap = Bitmap.createBitmap(first.bitmap.width, first.bitmap.height, Bitmap.Config.ARGB_8888),
                isLocked = true,
                collaborationOwnerId = partnerId
            )
        }
        val background = current.filter { it.isBackground }
        val artwork = current.filterNot { it.isBackground }
            .sortedWith(compareBy<Layer> { it.collaborationOwnerId.orEmpty() }.thenBy { it.id })
        val ordered = background + artwork
        _layers.value = ordered
        _activeLayerIndex.value = ordered.indexOfFirst { it.collaborationOwnerId == localId }
            .takeIf { it >= 0 } ?: ordered.lastIndex
        resetHistoryBaseline()
        return true
    }

    /** Copies the finished local contribution inside [dirty], excluding Base and partner layers. */
    fun snapshotLocalCollaborationPatch(dirty: RectF): Triple<Int, Int, Bitmap>? {
        val stack = _layers.value ?: return null
        val owner = collaborationLocalOwnerId ?: return null
        val first = stack.firstOrNull()?.bitmap ?: return null
        val left = kotlin.math.floor(dirty.left).toInt().coerceIn(0, first.width)
        val top = kotlin.math.floor(dirty.top).toInt().coerceIn(0, first.height)
        val right = kotlin.math.ceil(dirty.right).toInt().coerceIn(left, first.width)
        val bottom = kotlin.math.ceil(dirty.bottom).toInt().coerceIn(top, first.height)
        if (right <= left || bottom <= top) return null
        val patch = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(patch)
        canvas.translate(-left.toFloat(), -top.toFloat())
        LayerCompositor.draw(canvas, stack.filter { it.collaborationOwnerId == owner })
        return Triple(left, top, patch)
    }

    /** Replaces the matching rectangle on the partner-owned layer with their authoritative PNG. */
    fun applyRemoteCollaborationPatch(
        participantId: String,
        participantName: String,
        left: Int,
        top: Int,
        patch: Bitmap
    ): Boolean {
        val current = _layers.value?.toMutableList() ?: return false
        val first = current.firstOrNull()?.bitmap ?: return false
        var layer = current.firstOrNull { it.collaborationOwnerId == participantId }
        if (layer == null) {
            layer = Layer(
                id = UUID.randomUUID().toString(),
                name = participantName,
                bitmap = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888),
                isLocked = true,
                collaborationOwnerId = participantId
            )
            current += layer
        }
        val width = min(patch.width, layer.bitmap.width - left)
        val height = min(patch.height, layer.bitmap.height - top)
        if (left < 0 || top < 0 || width <= 0 || height <= 0) return false
        val src = Rect(0, 0, width, height)
        val dst = Rect(left, top, left + width, top + height)
        Canvas(layer.bitmap).drawBitmap(patch, src, dst, Paint().apply {
            blendMode = android.graphics.BlendMode.SRC
        })
        LayerCompositor.invalidateBitmapRegion(layer.bitmap, RectF(dst))
        val backgrounds = current.filter { it.isBackground }
        val artwork = current.filterNot { it.isBackground }
            .sortedWith(compareBy<Layer> { it.collaborationOwnerId.orEmpty() }.thenBy { it.id })
        _layers.value = backgrounds + artwork
        markDirty()
        return true
    }

    fun addLayer(width: Int, height: Int) {
        val currentLayers = _layers.value?.toMutableList() ?: mutableListOf()
        // Ignore the caller's guess and match whatever the canvas has actually grown to, so a new
        // layer on an expanded open canvas isn't created at the original, smaller size.
        val w = currentLayers.firstOrNull()?.bitmap?.width ?: width
        val h = currentLayers.firstOrNull()?.bitmap?.height ?: height
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Layer ${currentLayers.size + 1}",
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
            collaborationOwnerId = collaborationLocalOwnerId
        )
        currentLayers.add(newLayer)
        _layers.value = currentLayers
        _activeLayerIndex.value = currentLayers.size - 1
        saveState()
    }

    fun addLayerFromBitmap(bitmap: Bitmap, layerName: String? = null) {
        val currentLayers = _layers.value?.toMutableList() ?: mutableListOf()
        val newLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = layerName?.trim()?.takeIf { it.isNotEmpty() } ?: "Layer ${currentLayers.size + 1}",
            bitmap = bitmap,
            collaborationOwnerId = collaborationLocalOwnerId
        )
        currentLayers.add(newLayer)
        _layers.value = currentLayers
        _activeLayerIndex.value = currentLayers.size - 1
        saveState()
    }

    /** Merges all visible layers into a single layer, in place of the current stack. */
    fun flattenLayers() {
        val currentLayers = _layers.value ?: return
        if (currentLayers.size <= 1) return
        val flattened = flattenToBitmap() ?: return
        val mergedLayer = Layer(id = UUID.randomUUID().toString(), name = "Flattened", bitmap = flattened)
        _layers.value = listOf(mergedLayer)
        _activeLayerIndex.value = 0
        discardEditCapture()
        saveState()
    }

    /**
     * الدمج لأسفل: دمج الطبقة المحددة مع الطبقة الواقعة تحتها مباشرة مع مراعاة الشفافية والدمج.
     */
    fun mergeDown(upperIndex: Int): Boolean {
        val currentLayers = _layers.value?.toMutableList() ?: return false
        val lowerIndex = upperIndex - 1
        if (upperIndex !in currentLayers.indices || lowerIndex !in currentLayers.indices) return false
        val upper = currentLayers[upperIndex]
        val lower = currentLayers[lowerIndex]
        if (upper.isLocked || lower.isLocked) return false

        val merged = Bitmap.createBitmap(lower.bitmap.width, lower.bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        LayerCompositor.drawMergedPair(canvas, lower, upper)

        lower.bitmap = merged
        // Both masks have already been baked by the compositor. Keeping the lower mask attached
        // would apply it a second time (noticeable on soft gray edges).
        lower.maskBitmap = null
        lower.isEditingMask = false
        currentLayers.removeAt(upperIndex)
        _layers.value = currentLayers
        _activeLayerIndex.value = lowerIndex
        discardEditCapture()
        saveState()
        return true
    }

    /**
     * دمج الطبقات المرئية: دمج كل الطبقات الظاهرة في طبقة واحدة مع الإبقاء على الطبقات المخفية.
     */
    fun mergeVisibleLayers(): Boolean {
        val currentLayers = _layers.value?.toMutableList() ?: return false
        val visibleLayers = currentLayers.filter { it.isVisible && !it.isBackground }
        if (visibleLayers.size <= 1) return false

        val first = visibleLayers.first()
        val merged = Bitmap.createBitmap(first.bitmap.width, first.bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        LayerCompositor.draw(canvas, visibleLayers)

        val targetIndex = currentLayers.indexOf(first)
        val consolidatedLayer = Layer(
            id = UUID.randomUUID().toString(),
            name = "Merged Visible",
            bitmap = merged,
            opacity = 1f,
            blendMode = BlendMode.Normal,
            isVisible = true
        )
        currentLayers.removeAll(visibleLayers)
        val insertIndex = targetIndex.coerceIn(0, currentLayers.size)
        currentLayers.add(insertIndex, consolidatedLayer)

        _layers.value = currentLayers
        _activeLayerIndex.value = insertIndex
        discardEditCapture()
        saveState()
        return true
    }

    /**
     * مضاعفة الطبقة: إنشاء نسخة طبق الأصل من الطبقة المحددة.
     */
    fun duplicateLayer(index: Int): Boolean {
        val currentLayers = _layers.value?.toMutableList() ?: return false
        val src = currentLayers.getOrNull(index) ?: return false
        val copyBitmap = src.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val duplicate = Layer(
            id = UUID.randomUUID().toString(),
            name = "${src.name} (نسخة)",
            bitmap = copyBitmap,
            opacity = src.opacity,
            blendMode = src.blendMode,
            isVisible = src.isVisible,
            isLocked = false,
            isAlphaLocked = src.isAlphaLocked,
            isClippingMask = src.isClippingMask,
            maskBitmap = src.maskBitmap?.copy(Bitmap.Config.ARGB_8888, true),
            isBackground = false,
            collaborationOwnerId = collaborationLocalOwnerId ?: src.collaborationOwnerId
        )
        val targetIndex = index + 1
        currentLayers.add(targetIndex, duplicate)
        _layers.value = currentLayers
        _activeLayerIndex.value = targetIndex
        discardEditCapture()
        saveState()
        return true
    }

    /** Composites all visible layers (respecting opacity/blend mode/clipping masks) into one
     * bitmap - shares LayerCompositor with the live canvas so flattening/exporting can never
     * produce a different result than what's actually shown on screen. */
    fun flattenToBitmap(): Bitmap? {
        val currentLayers = _layers.value ?: return null
        val first = currentLayers.firstOrNull() ?: return null
        val result = Bitmap.createBitmap(first.bitmap.width, first.bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        if (isOpenCanvas) {
            CanvasBackground.paint(
                result,
                _backgroundStyle.value ?: CanvasBackgroundStyle.BLANK,
                backgroundPitch,
                _backgroundColor.value ?: Color.WHITE
            )
        }
        LayerCompositor.draw(canvas, currentLayers)
        return result
    }

    /**
     * إعادة تحجيم محتوى الطبقة النشطة بنسبة [scaleFactor] مع إمكانية تحديد نقطة ارتكاز (pivot).
     * تُستخدم لمعايرة وتطابق الخريطة المستوردة مع المقياس الحقيقي للشبكة.
     */
    fun rescaleActiveLayer(scaleFactor: Float, pivotX: Float = -1f, pivotY: Float = -1f) {
        val currentLayers = _layers.value ?: return
        val activeIndex = _activeLayerIndex.value ?: return
        val layer = currentLayers.getOrNull(activeIndex) ?: return
        if (layer.isBackground || layer.isLocked) return

        val original = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val w = layer.bitmap.width
        val h = layer.bitmap.height
        val px = if (pivotX < 0) w / 2f else pivotX
        val py = if (pivotY < 0) h / 2f else pivotY

        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val matrix = android.graphics.Matrix().apply {
            postScale(scaleFactor, scaleFactor, px, py)
        }
        canvas.drawBitmap(original, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        layer.bitmap = scaled
        commitLayerEdit(activeIndex, original)
        _layers.value = currentLayers
        markDirty()
    }

    fun removeLayer(index: Int) {
        val currentLayers = _layers.value?.toMutableList() ?: return
        if (index in currentLayers.indices && currentLayers.size > 1) {
            val target = currentLayers[index]
            if (target.isBackground) return
            if (target.collaborationOwnerId != null &&
                target.collaborationOwnerId != collaborationLocalOwnerId
            ) return
            currentLayers.removeAt(index)
            _layers.value = currentLayers
            _activeLayerIndex.value = (_activeLayerIndex.value ?: 0).coerceIn(0, currentLayers.size - 1)
            discardEditCapture()
            saveState()
        }
    }

    fun selectLayer(index: Int) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            _activeLayerIndex.value = index
        }
    }

    fun setCurrentBrush(properties: BrushProperties) {
        _currentBrush.value = properties
    }

    fun selectBrushPreset(id: String, properties: BrushProperties) {
        _currentBrushPresetId.value = id
        _currentBrush.value = properties
    }

    fun setCurrentColor(color: Int) {
        _currentColor.value = color
    }

    fun updateLayerOpacity(index: Int, opacity: Float) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            currentLayers[index].opacity = opacity.coerceIn(0f, 1f)
            _layers.value = currentLayers
            // Opacity publishes continuously while a slider moves, so it deliberately does not
            // create dozens of undo entries. Keep the structural baseline in sync so the next
            // real layer action does not unexpectedly restore an old slider position.
            lastStructure = currentLayers.map { LayerState.of(it) }
            markDirty()
        }
    }

    fun setLayerBlendMode(index: Int, blendMode: BlendMode) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            currentLayers[index].blendMode = blendMode
            _layers.value = currentLayers
            saveState()
        }
    }

    fun toggleLayerVisibility(index: Int) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            currentLayers[index].isVisible = !currentLayers[index].isVisible
            _layers.value = currentLayers
            saveState()
        }
    }

    fun toggleLayerLock(index: Int) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            currentLayers[index].isLocked = !currentLayers[index].isLocked
            _layers.value = currentLayers
            saveState()
        }
    }

    fun toggleLayerAlphaLock(index: Int) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            currentLayers[index].isAlphaLocked = !currentLayers[index].isAlphaLocked
            _layers.value = currentLayers
            saveState()
        }
    }

    fun toggleLayerClippingMask(index: Int) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices) {
            currentLayers[index].isClippingMask = !currentLayers[index].isClippingMask
            _layers.value = currentLayers
            saveState()
        }
    }

    /**
     * Adds a real non-destructive layer mask, or toggles whether the brush edits that mask.
     * White is neutral/revealed; painting black hides the upper layer and exposes layers below.
     */
    fun addOrToggleLayerMask(index: Int): Boolean {
        val currentLayers = _layers.value ?: return false
        val layer = currentLayers.getOrNull(index) ?: return false
        if (layer.isBackground) return false
        discardEditCapture()
        if (layer.maskBitmap == null) {
            layer.maskBitmap = Bitmap.createBitmap(
                layer.bitmap.width,
                layer.bitmap.height,
                Bitmap.Config.ARGB_8888
            ).also { it.eraseColor(Color.WHITE) }
            layer.isEditingMask = true
            _currentColor.value = Color.BLACK
            _layers.value = currentLayers
            saveState()
            return true
        }
        layer.isEditingMask = !layer.isEditingMask
        _layers.value = currentLayers
        return layer.isEditingMask
    }

    fun removeLayerMask(index: Int): Boolean {
        val currentLayers = _layers.value ?: return false
        val layer = currentLayers.getOrNull(index) ?: return false
        if (layer.maskBitmap == null) return false
        discardEditCapture()
        layer.maskBitmap = null
        layer.isEditingMask = false
        _layers.value = currentLayers
        saveState()
        return true
    }

    /** Sets clipping explicitly rather than toggling - "use as mask" needs to switch it ON no matter
     * what state the layer was in. */
    fun setLayerClipping(index: Int, clipping: Boolean) {
        val currentLayers = _layers.value ?: return
        if (index in currentLayers.indices && currentLayers[index].isClippingMask != clipping) {
            currentLayers[index].isClippingMask = clipping
            _layers.value = currentLayers
            saveState()
        }
    }

    fun moveLayer(fromIndex: Int, toIndex: Int) {
        val currentLayers = _layers.value?.toMutableList() ?: return
        if (fromIndex !in currentLayers.indices || toIndex !in currentLayers.indices) return
        val layer = currentLayers.removeAt(fromIndex)
        currentLayers.add(toIndex, layer)
        val activeId = _layers.value?.getOrNull(_activeLayerIndex.value ?: -1)?.id
        _layers.value = currentLayers
        _activeLayerIndex.value = currentLayers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        saveState()
    }

    private companion object {
        const val UNDO_TILE_SIZE = 256

        fun tileKey(left: Int, top: Int): Long =
            (top.toLong() shl 32) xor (left.toLong() and 0xFFFF_FFFFL)

        fun gcd(a: Int, b: Int): Int {
            var x = a.coerceAtLeast(1)
            var y = b.coerceAtLeast(1)
            while (y != 0) {
                val remainder = x % y
                x = y
                y = remainder
            }
            return x
        }

        fun lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

        /** How much new paper an expansion adds along one edge, before grid quantisation. */
        const val EXPAND_CHUNK_PX = 1280

        /** How close to an edge a stroke has to land to trigger growth. */
        const val EXPAND_MARGIN_PX = 320

        // Raised well past what the memory budget will ever actually let a device reach - the
        // available-memory check in canAfford() is the real, per-device ceiling; this constant only
        // exists to catch pathological cases (e.g. integer overflow in the size math), so it
        // should never be the thing that makes the canvas feel like it has a wall.
        const val MAX_CANVAS_SIDE = 20000

        /**
         * Absolute ceiling on the layer-stack estimate, regardless of how much a device says it has
         * free. A phone with 12 GB idle would otherwise approve a canvas whose every later filter,
         * export or thumbnail pass has to touch a multi-gigabyte raster; the low-memory killer
         * reaches the process long before the allocation itself fails.
         */
        const val MAX_CANVAS_BYTES = 1_200L * 1024 * 1024

        /** SRC replaces the destination outright - an undo patch must overwrite, not blend over. */
        val SRC_PAINT = Paint().apply { blendMode = android.graphics.BlendMode.SRC }
    }
}
