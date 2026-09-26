package com.procreate.android.vectorize

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Plain pixel-space point. Deliberately not `android.graphics.PointF` so the whole vectorisation
 * pipeline stays unit-testable on the JVM - the same reasoning behind MeasurementPoint.
 */
data class Vec2(val x: Float, val y: Float)

/** A traced, simplified polyline plus how much the tracer trusts it. */
data class TracedPath(
    val points: List<Vec2>,
    val closed: Boolean,
    /** 0..1. Derived from length and straightness, not from any model. */
    val confidence: Float
) {
    val lengthPx: Float
        get() {
            var total = 0f
            for (i in 1 until points.size) {
                total += hypot((points[i].x - points[i - 1].x).toDouble(), (points[i].y - points[i - 1].y).toDouble()).toFloat()
            }
            return total
        }
}

object PathSimplifier {

    /**
     * Ramer-Douglas-Peucker. A traced contour follows the pixel grid, so it arrives as a staircase
     * of thousands of one-pixel steps; dropping every point that sits within [epsilon] of the line
     * between its neighbours turns that back into the handful of real corners a person drew,
     * which is what makes the result editable rather than a dense unusable blob.
     *
     * Iterative rather than recursive: a long contour can be tens of thousands of points, deep
     * enough to overflow the stack on a real scan.
     */
    fun simplify(points: List<Vec2>, epsilon: Float): List<Vec2> {
        if (points.size < 3 || epsilon <= 0f) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)

        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            if (end <= start + 1) continue

            var maxDistance = 0f
            var index = start
            for (i in (start + 1) until end) {
                val d = perpendicularDistance(points[i], points[start], points[end])
                if (d > maxDistance) {
                    maxDistance = d
                    index = i
                }
            }

            if (maxDistance > epsilon) {
                keep[index] = true
                stack.addLast(start to index)
                stack.addLast(index to end)
            }
        }

        return points.filterIndexed { i, _ -> keep[i] }
    }

    fun perpendicularDistance(point: Vec2, lineStart: Vec2, lineEnd: Vec2): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        // A degenerate segment has no direction to measure against, so fall back to plain distance.
        if (dx == 0f && dy == 0f) {
            return hypot((point.x - lineStart.x).toDouble(), (point.y - lineStart.y).toDouble()).toFloat()
        }
        val numerator = abs(dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
        val denominator = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        return numerator / denominator
    }

    /**
     * Closes a contour whose ends came back near each other. A site boundary traced from a scan
     * almost never lands exactly back on its first pixel, and leaving it open means it can never
     * report an area.
     */
    fun closeIfNearlyClosed(points: List<Vec2>, tolerancePx: Float): Pair<List<Vec2>, Boolean> {
        if (points.size < 4) return points to false
        val gap = hypot(
            (points.last().x - points.first().x).toDouble(),
            (points.last().y - points.first().y).toDouble()
        ).toFloat()
        return if (gap <= tolerancePx) points.dropLast(1) to true else points to false
    }
}
