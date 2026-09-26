package com.procreate.android.vectorize

import kotlin.math.hypot
import kotlin.math.min

/**
 * Tuning for one vectorisation run. Defaults suit a photographed or scanned site plan; the review
 * UI exposes [detail] so the user can re-run without re-importing when a drawing is unusually
 * faint or unusually busy.
 */
/**
 * How the image should be read.
 *
 * [CENTERLINE] is right for anything drawn - a plan, a sketch, a printed map - because it returns
 * one path per drawn line. [EDGES] is right for a photograph of a real place, where the meaningful
 * geometry is the boundary between regions rather than any drawn stroke.
 */
enum class TraceMode { CENTERLINE, EDGES }

data class VectorizationOptions(
    /** 0..1. Higher keeps more of the weaker gradients, so more (and noisier) paths. */
    val detail: Float = 0.08f,
    val blurRadius: Int = 1,
    /** Douglas-Peucker tolerance in pixels. Larger means blockier but far fewer nodes. */
    val simplifyEpsilon: Float = 2.0f,
    val minPathPixels: Int = 24,
    val closeGapPx: Float = 6f,
    /** Cap on returned paths. A busy scan can trace thousands; a review list that long is
     * unusable, so the longest are kept - length is the best cheap proxy for "this is structure,
     * not speckle". */
    val maxPaths: Int = 120,
    /** Defaults to centreline because this feature exists to import drawings, not photographs. */
    val mode: TraceMode = TraceMode.CENTERLINE,
    /** Sauvola window radius, centreline mode only. Should comfortably exceed the stroke width. */
    val binarizeWindowRadius: Int = 12,
    /** Applies the geometric judgement pass. Off returns the literal trace, which is useful for
     * diagnosing whether a bad result came from detection or from refinement. */
    val refine: Boolean = true,
    val refineOptions: RefineOptions = RefineOptions()
) {
    init {
        require(detail > 0f && detail < 1f) { "detail must be between 0 and 1" }
        require(simplifyEpsilon >= 0f) { "simplifyEpsilon cannot be negative" }
        require(binarizeWindowRadius > 0) { "binarizeWindowRadius must be positive" }
    }
}

data class VectorizationResult(
    val paths: List<TracedPath>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** Total traced before the [VectorizationOptions.maxPaths] cap, so the UI can be honest about
     * what it is not showing. */
    val totalFound: Int
)

/**
 * Turns a raster plan into candidate vector paths, entirely on device.
 *
 * This is deliberately *not* an automatic conversion: it produces suggestions with a confidence
 * score for a human to accept, adjust or reject. An automatic redraw that silently invents a
 * boundary is worse than no feature at all in a drawing that will be used for planning decisions.
 */
object VectorizationPipeline {

    fun run(
        argb: IntArray,
        width: Int,
        height: Int,
        options: VectorizationOptions = VectorizationOptions()
    ): VectorizationResult {
        val mask = when (options.mode) {
            TraceMode.CENTERLINE -> {
                // Ink -> centreline. Binarising first means the threshold adapts to uneven
                // lighting, and thinning collapses each stroke to a single path rather than the
                // two parallel edges an edge detector would return for the same line.
                val luminance = EdgeDetector.toLuminance(argb)
                val blurred = EdgeDetector.boxBlur(luminance, width, height, options.blurRadius)
                val ink = AdaptiveBinarizer.binarize(
                    luminance = blurred,
                    width = width,
                    height = height,
                    windowRadius = options.binarizeWindowRadius
                )
                val thinned = Skeletonizer.thin(ink, width, height)
                EdgeDetector.EdgeMask(width, height, Skeletonizer.removeIsolated(thinned, width, height))
            }

            TraceMode.EDGES -> EdgeDetector.detect(
                argb = argb,
                width = width,
                height = height,
                blurRadius = options.blurRadius,
                keepFraction = options.detail
            )
        }

        val raw = ContourTracer.trace(mask, options.minPathPixels)

        val simplified = raw.mapNotNull { contour ->
            val reduced = PathSimplifier.simplify(contour, options.simplifyEpsilon)
            if (reduced.size < 2) return@mapNotNull null
            val (points, closed) = PathSimplifier.closeIfNearlyClosed(reduced, options.closeGapPx)
            if (points.size < 2) return@mapNotNull null
            TracedPath(points = points, closed = closed, confidence = confidenceOf(points, closed))
        }

        // Tracing is literal; this is where fragments become lines, near-right angles become right,
        // and a duplicate beside a line is dropped. Without it the output is technically faithful
        // to the pixels and useless as a plan.
        val refined = if (options.refine) {
            GeometryRefiner.refine(simplified, options.refineOptions)
        } else simplified

        val ranked = refined.sortedByDescending { it.lengthPx }
        return VectorizationResult(
            paths = ranked.take(options.maxPaths),
            sourceWidth = width,
            sourceHeight = height,
            totalFound = ranked.size
        )
    }

    /**
     * A transparent heuristic, not a model score, and labelled as such in the UI. Long paths are
     * more likely to be real structure than speckle, and a closed ring is a strong signal of a
     * deliberate boundary, so both raise confidence.
     */
    internal fun confidenceOf(points: List<Vec2>, closed: Boolean): Float {
        if (points.size < 2) return 0f
        var length = 0f
        for (i in 1 until points.size) {
            length += hypot(
                (points[i].x - points[i - 1].x).toDouble(),
                (points[i].y - points[i - 1].y).toDouble()
            ).toFloat()
        }
        val lengthScore = min(length / 600f, 1f)
        val closedBonus = if (closed) 0.2f else 0f
        return (lengthScore * 0.8f + closedBonus).coerceIn(0f, 1f)
    }
}
