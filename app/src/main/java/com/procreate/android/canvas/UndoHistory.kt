package com.procreate.android.canvas

import android.graphics.Bitmap
import com.procreate.android.project.ProjectPointDto
import com.procreate.android.project.UrbanCoordinateSpace
import com.procreate.android.project.UrbanElementDto
import com.procreate.android.project.UrbanProjectDto

/**
 * One reversible change.
 *
 * History used to be a stack of full deep copies of *every* layer, pushed after every single
 * stroke. On a 6000x4000 canvas that is ~96 MB per layer per stroke - enough to stutter on the
 * first mark and run the heap out within a dozen. Splitting history into the two things that
 * actually change fixes that:
 *
 *  - [PixelEdit] copies only the rectangle a stroke actually touched, on the one layer it touched.
 *  - [StackEdit] records layer *structure* (order, names, flags, opacity) and shares bitmap
 *    references rather than copying them, because reordering or renaming a layer never alters a
 *    pixel. Undo runs strictly newest-first, so by the time a StackEdit is reached every pixel
 *    change made after it has already been rolled back and the shared bitmaps hold the right
 *    content.
 */
sealed class HistoryEntry {
    abstract val byteSize: Long
}

/** One independently-owned before/after tile within a raster edit. */
class PixelPatch(
    var left: Int,
    var top: Int,
    val before: Bitmap,
    val after: Bitmap
) {
    val byteSize: Long = before.allocationByteCount.toLong() + after.allocationByteCount.toLong()

    fun recycle() {
        if (!before.isRecycled) before.recycle()
        if (!after.isRecycled) after.recycle()
    }
}

enum class PixelTarget { CONTENT, MASK }

/**
 * A raster edit made from small independent patches.
 *
 * A single rectangle is catastrophically expensive for a long diagonal stroke: a thin line from
 * corner to corner would retain two copies of almost the entire canvas. Tiles make the cost follow
 * the pixels the brush actually visited, which is essential when the open canvas has grown large.
 */
class PixelEdit(
    val layerId: String,
    val patches: List<PixelPatch>,
    val target: PixelTarget = PixelTarget.CONTENT
) : HistoryEntry() {
    init { require(patches.isNotEmpty()) { "A pixel edit needs at least one patch" } }
    override val byteSize: Long = patches.sumOf(PixelPatch::byteSize)

    /** Compatibility helper for whole-layer/filter edits that naturally are one rectangle. */
    constructor(
        layerId: String,
        left: Int,
        top: Int,
        before: Bitmap,
        after: Bitmap,
        target: PixelTarget = PixelTarget.CONTENT
    ) : this(
        layerId,
        listOf(PixelPatch(left, top, before, after)),
        target
    )

    fun recycle() = patches.forEach(PixelPatch::recycle)
}

/** An immutable record of one layer's identity and display settings - no pixel copy. */
class LayerState(
    val id: String,
    val name: String,
    var bitmap: Bitmap,
    val opacity: Float,
    val blendMode: BlendMode,
    val isVisible: Boolean,
    val isLocked: Boolean,
    val isAlphaLocked: Boolean,
    val isClippingMask: Boolean,
    var maskBitmap: Bitmap?,
    val isEditingMask: Boolean,
    val isBackground: Boolean
) {
    fun toLayer() = Layer(
        id = id, name = name, bitmap = bitmap, opacity = opacity, blendMode = blendMode,
        isVisible = isVisible, isLocked = isLocked, isAlphaLocked = isAlphaLocked,
        isClippingMask = isClippingMask, maskBitmap = maskBitmap,
        isEditingMask = isEditingMask, isBackground = isBackground
    )

    companion object {
        fun of(layer: Layer) = LayerState(
            layer.id, layer.name, layer.bitmap, layer.opacity, layer.blendMode,
            layer.isVisible, layer.isLocked, layer.isAlphaLocked, layer.isClippingMask,
            layer.maskBitmap, layer.isEditingMask, layer.isBackground
        )
    }
}

/** A change to the layer stack itself: added, removed, reordered, flattened, retitled, restyled. */
class StackEdit(
    val before: List<LayerState>,
    val after: List<LayerState>
) : HistoryEntry() {
    // Bitmaps are shared, not copied, so the only real cost is the small wrapper objects.
    override val byteSize: Long = 256L * (before.size + after.size)
}

/**
 * One reversible Urban/CAD mutation. Urban geometry is already represented by small immutable
 * project DTOs, so keeping the before/after values here is both lossless and dramatically cheaper
 * than copying the canvas bitmaps. Using the project representation also guarantees that undo and
 * on-disk restore exercise the same model boundary.
 */
class UrbanEdit(
    var before: UrbanProjectDto,
    var after: UrbanProjectDto
) : HistoryEntry() {
    override val byteSize: Long = estimateBytes(before) + estimateBytes(after)

    private fun estimateBytes(state: UrbanProjectDto): Long {
        val points = state.elements.sumOf { element ->
            when (element) {
                is UrbanElementDto.ArrowPath -> element.points.size + element.stations.size
                is UrbanElementDto.HatchPolygon -> element.vertices.size
                is UrbanElementDto.BoundaryPath -> element.vertices.size
                is UrbanElementDto.PointMarker -> 1
            }
        } + state.measurements.sumOf { it.points.size }
        return 512L + state.elements.size * 192L +
            state.measurements.size * 160L + state.architecturalAssets.size * 192L + points * 16L
    }
}

/**
 * Bounded undo/redo storage. Entries are evicted oldest-first once the total exceeds a budget
 * derived from the heap, so a large canvas trades history depth for staying alive rather than
 * dying at a fixed entry count that means wildly different things at different canvas sizes.
 */
class UndoHistory {

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var undoBytes = 0L

    /** Roughly a third of the heap, floored and capped so tiny and huge devices both behave. */
    private val budgetBytes: Long = (Runtime.getRuntime().maxMemory() / 3)
        .coerceIn(24L * 1024 * 1024, 224L * 1024 * 1024)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(entry: HistoryEntry) {
        undoStack.addLast(entry)
        undoBytes += entry.byteSize
        recycleAll(redoStack)
        redoStack.clear()
        while (undoBytes > budgetBytes && undoStack.size > 1) {
            val evicted = undoStack.removeFirst()
            undoBytes -= evicted.byteSize
            recycle(evicted)
        }
    }

    fun popUndo(): HistoryEntry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        undoBytes -= entry.byteSize
        redoStack.addLast(entry)
        return entry
    }

    fun popRedo(): HistoryEntry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        undoBytes += entry.byteSize
        return entry
    }

    fun clear() {
        recycleAll(undoStack)
        recycleAll(redoStack)
        undoStack.clear()
        redoStack.clear()
        undoBytes = 0
    }

    /**
     * Rebase every stored entry after the canvas grew: patch origins move with the content, and
     * structure entries are re-pointed at the layers' new, larger bitmaps. Without this, undoing
     * past an expansion would restore the old, smaller bitmaps into a canvas that had outgrown them.
     */
    fun rebaseAfterExpand(
        dx: Int,
        dy: Int,
        bitmapsById: Map<String, Bitmap>,
        masksById: Map<String, Bitmap> = emptyMap()
    ) {
        for (entry in undoStack + redoStack) {
            when (entry) {
                is PixelEdit -> {
                    entry.patches.forEach { patch ->
                        patch.left += dx
                        patch.top += dy
                    }
                }
                is StackEdit -> {
                    (entry.before + entry.after).forEach { state ->
                        bitmapsById[state.id]?.let { state.bitmap = it }
                        masksById[state.id]?.let { state.maskBitmap = it }
                    }
                }
                is UrbanEdit -> {
                    entry.before = entry.before.translatedCanvasCoordinates(dx.toFloat(), dy.toFloat())
                    entry.after = entry.after.translatedCanvasCoordinates(dx.toFloat(), dy.toFloat())
                }
            }
        }
    }

    private fun recycleAll(entries: Iterable<HistoryEntry>) = entries.forEach(::recycle)

    /** Pixel patches own their bitmaps; structural entries only borrow live layer bitmaps. */
    private fun recycle(entry: HistoryEntry) {
        if (entry is PixelEdit) entry.recycle()
    }

    private fun UrbanProjectDto.translatedCanvasCoordinates(dx: Float, dy: Float): UrbanProjectDto {
        if (coordinateSpace != UrbanCoordinateSpace.CANVAS_PIXELS || (dx == 0f && dy == 0f)) return this
        fun ProjectPointDto.shifted() = ProjectPointDto(x + dx, y + dy)
        val shiftedElements = elements.map { element ->
            when (element) {
                is UrbanElementDto.ArrowPath -> element.copy(
                    points = element.points.map { it.shifted() },
                    stations = element.stations.map { it.copy(point = it.point.shifted()) }
                )
                is UrbanElementDto.HatchPolygon -> element.copy(vertices = element.vertices.map { it.shifted() })
                is UrbanElementDto.PointMarker -> element.copy(position = element.position.shifted())
                is UrbanElementDto.BoundaryPath -> element.copy(vertices = element.vertices.map { it.shifted() })
            }
        }
        val shiftedMeasurements = measurements.map { measurement ->
            measurement.copy(points = measurement.points.map { it.shifted() })
        }
        val ppm = scale.pixelsPerMeter.takeIf { it.isFinite() && it > 0f } ?: 1f
        val shiftedAssets = architecturalAssets.map { asset ->
            asset.copy(
                xMeters = asset.xMeters + dx / ppm,
                yMeters = asset.yMeters + dy / ppm
            )
        }
        return copy(
            scale = scale.copy(scaleBarMapPosition = scale.scaleBarMapPosition?.shifted()),
            elements = shiftedElements,
            measurements = shiftedMeasurements,
            architecturalAssets = shiftedAssets
        )
    }
}
