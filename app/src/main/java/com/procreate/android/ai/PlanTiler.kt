package com.procreate.android.ai

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One tile of the plan, positioned in the full image's pixel space.
 *
 * [offsetX]/[offsetY] matter as much as the image itself: the model is told them and answers in
 * full-image coordinates, so nothing downstream has to translate per-tile results back. Asking for
 * local coordinates and translating them here would work too, but every translation is a chance to
 * apply the wrong offset to the wrong tile, and that failure is invisible until the drawing is
 * already wrong.
 */
data class PlanTile(
    val column: Int,
    val row: Int,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = offsetX + width
    val bottom: Int get() = offsetY + height
}

/**
 * Cuts a plan into overlapping tiles.
 *
 * A dense site plan holds far more features than a vision model will return for one whole-image
 * call - it spreads its attention over everything at once and comes back with the largest few
 * dozen. Tiling trades API calls for detail: each call sees a smaller area and can afford to
 * describe it properly.
 *
 * The overlap is what makes stitching possible. A road crossing a tile edge appears in both
 * neighbours, so the two fragments can be recognised as one feature instead of each being truncated
 * at the seam.
 */
object PlanTiler {

    const val DEFAULT_OVERLAP_FRACTION = 0.25f

    /**
     * @param targetTileSize preferred tile edge in source pixels. The real tile size is derived
     * from the grid so the tiles tessellate the image exactly rather than leaving a sliver.
     */
    fun tile(
        imageWidth: Int,
        imageHeight: Int,
        targetTileSize: Int = 800,
        overlapFraction: Float = DEFAULT_OVERLAP_FRACTION
    ): List<PlanTile> {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        require(targetTileSize > 0) { "Tile size must be positive" }
        require(overlapFraction >= 0f && overlapFraction < 0.9f) { "Overlap must be in [0, 0.9)" }

        val columns = gridCount(imageWidth, targetTileSize, overlapFraction)
        val rows = gridCount(imageHeight, targetTileSize, overlapFraction)

        val tileWidth = tileEdge(imageWidth, columns, overlapFraction)
        val tileHeight = tileEdge(imageHeight, rows, overlapFraction)
        val strideX = stride(imageWidth, tileWidth, columns)
        val strideY = stride(imageHeight, tileHeight, rows)

        return buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    // Clamping the last tile back inside the image keeps every tile full-sized:
                    // a narrow strip at the edge gives the model too little context to classify
                    // anything in it.
                    val x = (column * strideX).coerceAtMost(max(0, imageWidth - tileWidth))
                    val y = (row * strideY).coerceAtMost(max(0, imageHeight - tileHeight))
                    add(
                        PlanTile(
                            column = column,
                            row = row,
                            offsetX = x,
                            offsetY = y,
                            width = tileWidth.coerceAtMost(imageWidth),
                            height = tileHeight.coerceAtMost(imageHeight)
                        )
                    )
                }
            }
        }
    }

    private fun gridCount(extent: Int, target: Int, overlap: Float): Int {
        if (extent <= target) return 1
        val step = target * (1f - overlap)
        return ceil((extent - target * overlap) / step).toInt().coerceAtLeast(1)
    }

    private fun tileEdge(extent: Int, count: Int, overlap: Float): Int {
        if (count <= 1) return extent
        // extent = count*edge - (count-1)*edge*overlap  =>  edge = extent / (count - (count-1)*overlap)
        val denominator = count - (count - 1) * overlap
        return (extent / denominator).roundToInt().coerceAtLeast(1).coerceAtMost(extent)
    }

    private fun stride(extent: Int, edge: Int, count: Int): Int {
        if (count <= 1) return 0
        return ((extent - edge).toFloat() / (count - 1)).roundToInt().coerceAtLeast(1)
    }

    /** Every pixel of the image must fall inside at least one tile, or features there are lost
     * with nothing to indicate it happened. */
    fun coversEntireImage(tiles: List<PlanTile>, imageWidth: Int, imageHeight: Int): Boolean {
        if (tiles.isEmpty()) return false
        return tiles.minOf { it.offsetX } == 0 &&
            tiles.minOf { it.offsetY } == 0 &&
            tiles.maxOf { it.right } >= imageWidth &&
            tiles.maxOf { it.bottom } >= imageHeight
    }
}

/**
 * The categories extraction is split across.
 *
 * One call per category per tile, because the model reports being materially more accurate when
 * it is looking for one kind of thing: fewer misclassifications, more of the target type found,
 * and cleaner geometry because it is not switching between visual languages mid-answer.
 */
enum class ExtractionCategory(
    val arabicLabel: String,
    val instruction: String,
    val features: Set<PlanFeature>
) {
    BUILDINGS(
        "الكتل المعمارية",
        "All enclosed building footprints: towers, blocks, retail units, department stores, " +
            "pavilions and any roofed structure. Trace the outline of EACH separate structure " +
            "individually - do not merge neighbouring buildings into one shape.",
        setOf(PlanFeature.BUILDING, PlanFeature.HERITAGE_BUILDING, PlanFeature.RUINED_BUILDING)
    ),
    CIRCULATION(
        "المسارات والأروقة",
        "All circulation: roads, pedestrian corridors, branching walkways, bridges, ramps and " +
            "paths. Follow the CENTRELINE of each route. Trace every branch separately, " +
            "including short spurs.",
        setOf(
            PlanFeature.MAIN_ROAD, PlanFeature.SECONDARY_ROAD,
            PlanFeature.DIRT_PATH, PlanFeature.ENTRY
        )
    ),
    LANDSCAPE(
        "المسطحات والاستعمالات",
        "All planting zones, lawns, tree clusters, urban parks, water features, sports fields " +
            "and land-use patches. Trace each distinct area separately rather than grouping all " +
            "greenery into one polygon.",
        setOf(PlanFeature.GREEN_AREA, PlanFeature.PLAZA, PlanFeature.WATER)
    ),
    ANALYSIS(
        "الرسوم التحليلية",
        "Only the drawn ANALYSIS graphics overlaid on the plan: direction arrows, movement axes, " +
            "sightlines, approach indicators, section lines and grid/axis lines. These are " +
            "annotations drawn ON the plan, not physical site features.",
        setOf(
            PlanFeature.MAIN_AXIS, PlanFeature.SECONDARY_AXIS,
            PlanFeature.SITE_BOUNDARY, PlanFeature.CONTOUR
        )
    );
}
