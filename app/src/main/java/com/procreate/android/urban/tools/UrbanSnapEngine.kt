package com.procreate.android.urban.tools

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** What a snapped point locked onto, so the canvas can show the user *why* it moved. */
enum class SnapKind { NONE, GRID, VERTEX, MIDPOINT, ORTHO }

data class SnapPoint(val x: Float, val y: Float, val kind: SnapKind)

/**
 * Which snap behaviours are armed. Each is independent: a user drawing a site boundary over an
 * imported survey usually wants vertex snapping but not grid, while someone laying out a new
 * block wants the opposite.
 */
data class SnapSettings(
    val grid: Boolean = false,
    val vertex: Boolean = true,
    val midpoint: Boolean = false,
    val ortho: Boolean = false
) {
    val anyEnabled: Boolean get() = grid || vertex || midpoint || ortho
}

/**
 * Pure geometry, deliberately free of any `android.*` import so the rules are unit-testable on the
 * JVM the same way [com.procreate.android.urban.measurement.MeasurementCalculator] is.
 *
 * Everything here works in canvas pixels. The tolerance is supplied already converted from screen
 * pixels by the caller, because a fixed canvas-space tolerance would silently get stricter the
 * further the user zooms out - the same class of bug that made Urban elements hard to reselect.
 *
 * Precedence is deliberate: an explicit point the user already drew (vertex, then midpoint) beats
 * the abstract grid, because landing exactly on an existing corner is almost always the intent
 * when both are within reach.
 */
object UrbanSnapEngine {

    fun snap(
        x: Float,
        y: Float,
        settings: SnapSettings,
        toleranceCanvasPx: Float,
        candidatePoints: List<Pair<Float, Float>> = emptyList(),
        candidateSegments: List<Pair<Pair<Float, Float>, Pair<Float, Float>>> = emptyList(),
        gridSpacingPx: Float = 0f,
        previousPoint: Pair<Float, Float>? = null
    ): SnapPoint {
        if (!settings.anyEnabled || toleranceCanvasPx <= 0f) return SnapPoint(x, y, SnapKind.NONE)

        if (settings.vertex) {
            nearestWithin(x, y, candidatePoints, toleranceCanvasPx)?.let {
                return SnapPoint(it.first, it.second, SnapKind.VERTEX)
            }
        }

        if (settings.midpoint) {
            val midpoints = candidateSegments.map { (a, b) ->
                (a.first + b.first) / 2f to (a.second + b.second) / 2f
            }
            nearestWithin(x, y, midpoints, toleranceCanvasPx)?.let {
                return SnapPoint(it.first, it.second, SnapKind.MIDPOINT)
            }
        }

        // Ortho is applied before grid: it constrains direction from the previous point, and
        // rounding that constrained point to the grid afterwards would tilt it back off-axis.
        if (settings.ortho && previousPoint != null) {
            val constrained = constrainToAxis(previousPoint, x, y)
            if (constrained != null) return SnapPoint(constrained.first, constrained.second, SnapKind.ORTHO)
        }

        if (settings.grid && gridSpacingPx > 0f) {
            val gx = round(x / gridSpacingPx) * gridSpacingPx
            val gy = round(y / gridSpacingPx) * gridSpacingPx
            if (hypot((gx - x).toDouble(), (gy - y).toDouble()) <= toleranceCanvasPx) {
                return SnapPoint(gx, gy, SnapKind.GRID)
            }
        }

        return SnapPoint(x, y, SnapKind.NONE)
    }

    private fun nearestWithin(
        x: Float,
        y: Float,
        points: List<Pair<Float, Float>>,
        tolerance: Float
    ): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestDistance = Double.MAX_VALUE
        for (p in points) {
            val d = hypot((p.first - x).toDouble(), (p.second - y).toDouble())
            if (d <= tolerance && d < bestDistance) {
                bestDistance = d
                best = p
            }
        }
        return best
    }

    /**
     * Locks the segment from [from] to the cursor onto the nearest 45 degree axis, preserving the
     * distance the user actually dragged rather than projecting onto the axis (projection makes
     * the line visibly shrink as the angle correction grows, which reads as the point slipping).
     */
    private fun constrainToAxis(from: Pair<Float, Float>, x: Float, y: Float): Pair<Float, Float>? {
        val dx = (x - from.first).toDouble()
        val dy = (y - from.second).toDouble()
        val distance = hypot(dx, dy)
        if (distance <= 0.0001) return null
        val step = Math.PI / 4.0
        val snappedAngle = round(atan2(dy, dx) / step) * step
        return (from.first + (cos(snappedAngle) * distance).toFloat()) to
            (from.second + (sin(snappedAngle) * distance).toFloat())
    }

    /** True when [angleRadians] already sits on a 45 degree axis within [toleranceRadians]. */
    fun isOnAxis(angleRadians: Double, toleranceRadians: Double = 0.01): Boolean {
        val step = Math.PI / 4.0
        val nearest = round(angleRadians / step) * step
        return abs(angleRadians - nearest) <= toleranceRadians
    }
}
