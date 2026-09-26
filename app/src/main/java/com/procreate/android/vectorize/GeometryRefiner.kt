package com.procreate.android.vectorize

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

data class RefineOptions(
    /** Endpoints closer than this may be joined into one path. */
    val mergeGapPx: Float = 14f,
    /** Two fragments only merge if their headings agree to within this many degrees. */
    val mergeAngleDeg: Float = 14f,
    /** A segment within this many degrees of an axis is treated as having been meant for it. */
    val regularizeAngleDeg: Float = 7f,
    /** Paths lying this close to a longer path along their whole length are duplicates. */
    val duplicateDistancePx: Float = 7f,
    val minLengthPx: Float = 24f,
    val closeGapPx: Float = 10f
)

/**
 * The judgement layer over raw tracing.
 *
 * Tracing answers "which pixels are ink"; it has no notion of what a drawing *means*, so its output
 * is literal: one intended line arrives as several fragments, a corner meant as square arrives at
 * 87 degrees, and a thick stroke can still leave a near-duplicate beside its centreline. None of
 * that is wrong pixel-wise, and all of it is wrong as a plan.
 *
 * These rules encode the assumptions a draughtsman makes automatically - collinear fragments a
 * few pixels apart were one line; a near-right angle was meant to be right; two paths running
 * together for their whole length are one line recorded twice. They are deterministic heuristics,
 * not inference: each one is stated, testable, and can be turned off.
 */
object GeometryRefiner {

    fun refine(paths: List<TracedPath>, options: RefineOptions = RefineOptions()): List<TracedPath> {
        if (paths.isEmpty()) return paths

        var working = paths.filter { it.lengthPx >= options.minLengthPx }
        // Merge before regularising: fragments must become whole lines first, otherwise each
        // fragment gets snapped to its own slightly different axis and they stop lining up.
        working = mergeFragments(working, options)
        // Then close shapes. This runs after collinear merging so a side drawn as two strokes is
        // already one side before the outline is assembled.
        working = chainIntoShapes(working, options)
        working = working.map { regularize(it, options) }
        working = dropDuplicates(working, options)
        return working.filter { it.lengthPx >= options.minLengthPx }
    }

    // ---------------------------------------------------------------- merging

    internal fun mergeFragments(paths: List<TracedPath>, options: RefineOptions): List<TracedPath> {
        val remaining = paths.toMutableList()
        var mergedSomething = true
        // Repeated passes because one merge can create a new adjacency: three fragments of the
        // same line only collapse fully once the first two have become one.
        var guard = 0
        while (mergedSomething && guard++ < 8) {
            mergedSomething = false
            outer@ for (i in remaining.indices) {
                for (j in remaining.indices) {
                    if (i >= j) continue
                    val merged = tryMerge(remaining[i], remaining[j], options) ?: continue
                    remaining[i] = merged
                    remaining.removeAt(j)
                    mergedSomething = true
                    break@outer
                }
            }
        }
        return remaining
    }

    private fun tryMerge(a: TracedPath, b: TracedPath, options: RefineOptions): TracedPath? {
        if (a.closed || b.closed) return null
        if (a.points.size < 2 || b.points.size < 2) return null

        // Four ways two open paths can meet, one per endpoint pairing.
        val candidates = listOf(
            Triple(a.points.last(), b.points.first(), false to false),
            Triple(a.points.last(), b.points.last(), false to true),
            Triple(a.points.first(), b.points.first(), true to false),
            Triple(a.points.first(), b.points.last(), true to true)
        )

        for ((endA, endB, orientation) in candidates) {
            val gap = distance(endA, endB)
            if (gap > options.mergeGapPx) continue

            val (reverseA, reverseB) = orientation
            val pointsA = if (reverseA) a.points.reversed() else a.points
            val pointsB = if (reverseB) b.points.reversed() else b.points

            // Headings at the two ends that would join, compared as undirected lines.
            val headingA = heading(pointsA[pointsA.size - 2], pointsA.last())
            val headingB = heading(pointsB.first(), pointsB[1])
            if (angleBetweenLines(headingA, headingB) > options.mergeAngleDeg) continue

            val joined = pointsA + pointsB
            val (finalPoints, closed) = PathSimplifier.closeIfNearlyClosed(joined, options.closeGapPx)
            return TracedPath(
                points = finalPoints,
                closed = closed,
                confidence = maxOf(a.confidence, b.confidence)
            )
        }
        return null
    }

    // ---------------------------------------------------------------- closing shapes

    /**
     * Joins strokes that meet end to end into a single closed outline.
     *
     * Distinct from [mergeFragments], which only ever joins pieces of one straight line: an
     * outline turns a corner at every join, so the collinearity test correctly refuses it. What
     * makes *this* rule safe is the closure requirement - a chain is only accepted if it comes
     * back to where it started. Two lines that merely touch at a T-junction never close, so they
     * are left as they were rather than being silently fused into one object.
     */
    internal fun chainIntoShapes(paths: List<TracedPath>, options: RefineOptions): List<TracedPath> {
        val pool = paths.filter { !it.closed }.toMutableList()
        val result = paths.filter { it.closed }.toMutableList()

        while (pool.isNotEmpty()) {
            val seed = pool.removeAt(0)
            var chain = seed.points
            val consumed = mutableListOf<TracedPath>()

            var extended = true
            while (extended) {
                extended = false
                for (i in pool.indices) {
                    val oriented = orientToFollow(chain.last(), pool[i], options.mergeGapPx) ?: continue
                    chain = chain + oriented.drop(1)
                    consumed += pool.removeAt(i)
                    extended = true
                    break
                }
            }

            val closes = chain.size >= 4 && distance(chain.first(), chain.last()) <= options.mergeGapPx
            if (closes) {
                result += TracedPath(
                    points = chain.dropLast(1),
                    closed = true,
                    confidence = (seed.confidence + 0.15f).coerceAtMost(1f)
                )
            } else {
                // The chain never came back on itself, so these were separate lines that happen to
                // touch. Put them back exactly as they were.
                result += seed
                result += consumed
            }
        }
        return result
    }

    /** Returns [candidate]'s points ordered to continue from [from], or null if neither end is
     * close enough to join. */
    private fun orientToFollow(from: Vec2, candidate: TracedPath, tolerance: Float): List<Vec2>? {
        if (candidate.points.size < 2) return null
        val startGap = distance(from, candidate.points.first())
        val endGap = distance(from, candidate.points.last())
        return when {
            startGap <= tolerance && startGap <= endGap -> candidate.points
            endGap <= tolerance -> candidate.points.reversed()
            else -> null
        }
    }

    // ---------------------------------------------------------------- angle regularisation

    /**
     * Snaps each segment that is already close to a 45-degree axis onto it exactly, rebuilding the
     * path so it stays connected and every segment keeps its original length.
     *
     * Only near-axis segments move. A deliberately oblique line is left exactly as traced, because
     * forcing everything onto an axis would destroy real geometry to make the output look tidy.
     */
    internal fun regularize(path: TracedPath, options: RefineOptions): TracedPath {
        if (path.points.size < 2) return path
        val step = 45.0
        val out = mutableListOf(path.points.first())

        for (i in 1 until path.points.size) {
            val previous = out.last()
            val original = path.points[i]
            val dx = (original.x - path.points[i - 1].x).toDouble()
            val dy = (original.y - path.points[i - 1].y).toDouble()
            val length = hypot(dx, dy)
            if (length < 1e-4) continue

            val angle = Math.toDegrees(atan2(dy, dx))
            val nearest = round(angle / step) * step
            val delta = abs(angle - nearest)

            val useAngle = if (delta <= options.regularizeAngleDeg) nearest else angle
            val radians = Math.toRadians(useAngle)
            out += Vec2(
                previous.x + (cos(radians) * length).toFloat(),
                previous.y + (sin(radians) * length).toFloat()
            )
        }

        val (points, closed) = if (path.closed) {
            out.toList() to true
        } else {
            PathSimplifier.closeIfNearlyClosed(out, options.closeGapPx)
        }
        return TracedPath(points, closed, path.confidence)
    }

    // ---------------------------------------------------------------- duplicates

    /**
     * Removes a path that runs alongside a longer one for its whole length.
     *
     * Centreline tracing mostly prevents the doubled-line case, but a stroke whose two sides differ
     * in contrast can still leave a short companion beside the real line. Keeping the longer of the
     * pair keeps the one that carries more of the drawing.
     */
    internal fun dropDuplicates(paths: List<TracedPath>, options: RefineOptions): List<TracedPath> {
        val ordered = paths.sortedByDescending { it.lengthPx }
        val kept = mutableListOf<TracedPath>()
        for (candidate in ordered) {
            val duplicate = kept.any { existing -> liesAlong(candidate, existing, options.duplicateDistancePx) }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun liesAlong(candidate: TracedPath, reference: TracedPath, tolerance: Float): Boolean {
        if (candidate.points.isEmpty() || reference.points.size < 2) return false
        val covered = candidate.points.count { point ->
            distanceToPolyline(point, reference.points) <= tolerance
        }
        // "Whole length" rather than "mostly": a path that merely crosses or touches another must
        // survive, or every junction in the drawing would silently delete one of its branches.
        return covered >= (candidate.points.size * 0.9f).toInt().coerceAtLeast(1)
    }

    internal fun distanceToPolyline(point: Vec2, polyline: List<Vec2>): Float {
        var best = Float.MAX_VALUE
        for (i in 1 until polyline.size) {
            val d = distanceToSegment(point, polyline[i - 1], polyline[i])
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(p: Vec2, a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-6f) return distance(p, a)
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared
        t = t.coerceIn(0f, 1f)
        return distance(p, Vec2(a.x + t * dx, a.y + t * dy))
    }

    // ---------------------------------------------------------------- helpers

    private fun distance(a: Vec2, b: Vec2): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun heading(from: Vec2, to: Vec2): Double =
        Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()))

    /** Difference between two headings treated as undirected lines, so 179 and -179 agree. */
    internal fun angleBetweenLines(a: Double, b: Double): Double {
        var diff = abs(a - b) % 180.0
        if (diff > 90.0) diff = 180.0 - diff
        return diff
    }
}
