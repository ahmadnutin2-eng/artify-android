package com.procreate.android.ai

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Reconciles features reported by more than one tile.
 *
 * Overlapping tiles are what make stitching possible, and also what make duplicates inevitable: a
 * building inside the overlap band is seen twice, and a road crossing a seam comes back as two
 * fragments. Both have to be resolved before anything is drawn, or the user gets a plan with
 * doubled buildings and roads that stop at invisible lines.
 *
 * Deliberately conservative. Wrongly merging two adjacent buildings into one destroys real
 * information and is hard to notice; leaving a seam is visible and the user can fix it in seconds.
 * Every rule here therefore requires positive evidence before combining anything.
 */
object FeatureMerger {

    data class Options(
        /** Two shapes of the same type whose points sit this close are the same thing seen twice. */
        val duplicateTolerance: Float = 0.012f,
        /** Endpoints of two line fragments closer than this may be the same route. */
        val joinTolerance: Float = 0.02f,
        /** Fragments only join if their headings agree to within this many degrees. */
        val joinAngleDegrees: Float = 25f
    )

    fun merge(features: List<DetectedFeature>, options: Options = Options()): List<DetectedFeature> {
        if (features.size < 2) return features
        val sane = rejectDegenerate(features)
        if (sane.size < 2) return sane
        val deduplicated = dropDuplicates(sane, options)
        return joinClippedLines(deduplicated, options)
    }

    /**
     * Throws away runs of identical shapes laid out on a regular lattice.
     *
     * A vision model pushed to be exhaustive can slip into a repetition loop: instead of stopping
     * when it runs out of real features, it keeps emitting the same rectangle at a fixed stride,
     * every one at full confidence. Observed on a live plan, it produced several hundred identical
     * 30x30 squares in a perfect grid.
     *
     * The prompt now tells it not to, but a prompt is a request, not a guarantee - and this failure
     * is far too damaging to leave to good behaviour, because the output is superficially valid and
     * would bury a real drawing under hundreds of invented blocks. Identical size plus evenly
     * spaced centres is a signature no real site plan produces.
     */
    internal fun rejectDegenerate(
        features: List<DetectedFeature>,
        minimumRun: Int = 8
    ): List<DetectedFeature> {
        val rectangles = features.filter { it.rectangle != null }
        if (rectangles.size < minimumRun) return features

        val suspect = rectangles
            .groupBy { spec ->
                val r = spec.rectangle!!
                // Quantised so near-identical sizes group together.
                Math.round(r.width * 2000f) to Math.round(r.height * 2000f)
            }
            .filterValues { group ->
                group.size >= minimumRun && evenlySpaced(group.mapNotNull { it.rectangle })
            }
            .values
            .flatten()
            .toSet()

        return if (suspect.isEmpty()) features else features.filterNot { it in suspect }
    }

    /** True when the centres sit on a regular lattice along either axis. */
    private fun evenlySpaced(specs: List<RectangleSpec>): Boolean {
        if (specs.size < 3) return false
        fun regular(values: List<Float>): Boolean {
            val sorted = values.distinct().sorted()
            if (sorted.size < 3) return false
            val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it > 1e-4f }
            if (gaps.size < 2) return false
            val mean = gaps.average().toFloat()
            if (mean <= 0f) return false
            // Every gap within a few percent of the mean means a machine-regular stride.
            return gaps.all { abs(it - mean) / mean < 0.08f }
        }
        return regular(specs.map { it.centerX }) || regular(specs.map { it.centerY })
    }

    // ---------------------------------------------------------------- duplicates

    internal fun dropDuplicates(
        features: List<DetectedFeature>,
        options: Options
    ): List<DetectedFeature> {
        // Highest confidence first, so when two tiles disagree the more certain reading is the one
        // that survives rather than whichever happened to be processed first.
        val ordered = features.sortedByDescending { it.confidence }
        val kept = mutableListOf<DetectedFeature>()
        for (candidate in ordered) {
            val isDuplicate = kept.any { existing -> sameThing(candidate, existing, options) }
            if (!isDuplicate) kept += candidate
        }
        return kept
    }

    private fun sameThing(a: DetectedFeature, b: DetectedFeature, options: Options): Boolean {
        if (a.feature != b.feature) return false
        val pointsA = resolvedPoints(a)
        val pointsB = resolvedPoints(b)
        if (pointsA.isEmpty() || pointsB.isEmpty()) return false

        // Centroid distance is a cheap reject for the overwhelming majority of pairs.
        val centroidA = centroid(pointsA)
        val centroidB = centroid(pointsB)
        if (distance(centroidA, centroidB) > options.duplicateTolerance * 4f) return false

        // Markers have no extent, so proximity alone decides.
        if (a.geometry == PlanGeometry.POINT && b.geometry == PlanGeometry.POINT) {
            return distance(pointsA.first(), pointsB.first()) <= options.duplicateTolerance
        }

        val coveredAB = fractionCovered(pointsA, pointsB, options.duplicateTolerance)
        val coveredBA = fractionCovered(pointsB, pointsA, options.duplicateTolerance)
        // Both directions must agree: one shape lying along part of a much larger one is a
        // genuinely different feature, not a duplicate of it.
        return coveredAB >= 0.8f && coveredBA >= 0.8f
    }

    private fun fractionCovered(
        from: List<Pair<Float, Float>>,
        to: List<Pair<Float, Float>>,
        tolerance: Float
    ): Float {
        if (from.isEmpty()) return 0f
        val near = from.count { point -> distanceToPolyline(point, to) <= tolerance }
        return near.toFloat() / from.size
    }

    // ---------------------------------------------------------------- clipped lines

    /**
     * Joins line fragments that were cut by a tile edge.
     *
     * Only fragments the model itself flagged as clipped are considered. A route that genuinely
     * ends mid-plan - a cul-de-sac, a path stopping at a building - must keep its endpoint, and
     * without the flag there is no way to tell that apart from a seam.
     */
    internal fun joinClippedLines(
        features: List<DetectedFeature>,
        options: Options
    ): List<DetectedFeature> {
        val result = features.toMutableList()
        var joined = true
        var guard = 0
        while (joined && guard++ < 12) {
            joined = false
            outer@ for (i in result.indices) {
                val a = result[i]
                if (a.geometry != PlanGeometry.LINE || !a.clippedAtEdge) continue
                for (j in result.indices) {
                    if (i >= j) continue
                    val b = result[j]
                    if (b.geometry != PlanGeometry.LINE || !b.clippedAtEdge) continue
                    if (a.feature != b.feature) continue
                    val combined = tryJoin(a, b, options) ?: continue
                    result[i] = combined
                    result.removeAt(j)
                    joined = true
                    break@outer
                }
            }
        }
        return result
    }

    private fun tryJoin(
        a: DetectedFeature,
        b: DetectedFeature,
        options: Options
    ): DetectedFeature? {
        if (a.points.size < 2 || b.points.size < 2) return null
        val pairings = listOf(
            Triple(a.points.last(), b.points.first(), false to false),
            Triple(a.points.last(), b.points.last(), false to true),
            Triple(a.points.first(), b.points.first(), true to false),
            Triple(a.points.first(), b.points.last(), true to true)
        )
        for ((endA, endB, orientation) in pairings) {
            if (distance(endA, endB) > options.joinTolerance) continue
            val (reverseA, reverseB) = orientation
            val pointsA = if (reverseA) a.points.reversed() else a.points
            val pointsB = if (reverseB) b.points.reversed() else b.points

            val headingA = heading(pointsA[pointsA.size - 2], pointsA.last())
            val headingB = heading(pointsB.first(), pointsB[1])
            if (angleBetweenLines(headingA, headingB) > options.joinAngleDegrees) continue

            return a.copy(
                points = pointsA + pointsB,
                confidence = maxOf(a.confidence, b.confidence),
                // The join consumed the clip on both sides; whether the combined run is still open
                // at a further seam is decided by the fragments that remain.
                clippedAtEdge = false
            )
        }
        return null
    }

    // ---------------------------------------------------------------- geometry helpers

    /** Rectangles and circles carry their shape parametrically, so they are expanded before any
     * comparison that needs actual points. */
    internal fun resolvedPoints(feature: DetectedFeature): List<Pair<Float, Float>> = when {
        feature.rectangle != null -> PlanFeatureMapper.rectangleCorners(feature.rectangle)
        feature.circle != null -> PlanFeatureMapper.circlePoints(feature.circle, segments = 16)
        else -> feature.points
    }

    private fun centroid(points: List<Pair<Float, Float>>): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (p in points) { x += p.first; y += p.second }
        return (x / points.size) to (y / points.size)
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble()).toFloat()

    private fun distanceToPolyline(point: Pair<Float, Float>, polyline: List<Pair<Float, Float>>): Float {
        if (polyline.size == 1) return distance(point, polyline.first())
        var best = Float.MAX_VALUE
        for (i in 1 until polyline.size) {
            val d = distanceToSegment(point, polyline[i - 1], polyline[i])
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(
        p: Pair<Float, Float>,
        a: Pair<Float, Float>,
        b: Pair<Float, Float>
    ): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-9f) return distance(p, a)
        var t = ((p.first - a.first) * dx + (p.second - a.second) * dy) / lengthSquared
        t = t.coerceIn(0f, 1f)
        return distance(p, (a.first + t * dx) to (a.second + t * dy))
    }

    private fun heading(from: Pair<Float, Float>, to: Pair<Float, Float>): Double =
        Math.toDegrees(kotlin.math.atan2((to.second - from.second).toDouble(), (to.first - from.first).toDouble()))

    internal fun angleBetweenLines(a: Double, b: Double): Double {
        var diff = abs(a - b) % 180.0
        if (diff > 90.0) diff = 180.0 - diff
        return diff
    }
}
