package com.procreate.android.canvas

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a snapped QuickShape into a dense polyline the brush engine can be fed sample by sample,
 * so a snapped shape is painted by the brush itself: its size, texture, opacity and eraser mode.
 *
 * Works on plain floats (x0, y0, x1, y1, ...) so it runs on the JVM without android.graphics.
 * Closed shapes end exactly on their first point. Every polygon corner is a sample, so corners
 * stay sharp instead of being cut by the spacing walk.
 */
internal object QuickShapeSampler {

    /** Upper bound on samples for one shape; a huge circle widens its spacing instead. */
    const val MAX_POINTS = 4096

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, spacing: Float): FloatArray =
        polygon(floatArrayOf(x0, y0, x1, y1), closed = false, spacing = spacing)

    fun polygon(vertices: FloatArray, closed: Boolean, spacing: Float): FloatArray {
        require(vertices.size >= 4 && vertices.size % 2 == 0) { "need at least two vertices" }
        val count = vertices.size / 2
        val edges = if (closed) count else count - 1
        var total = 0f
        for (e in 0 until edges) total += edgeLength(vertices, e, count)
        val step = effectiveSpacing(spacing, total)

        val out = FloatList()
        for (e in 0 until edges) {
            val ax = vertices[2 * e]
            val ay = vertices[2 * e + 1]
            val b = (e + 1) % count
            val bx = vertices[2 * b]
            val by = vertices[2 * b + 1]
            val steps = max(1, ceil(hypot(bx - ax, by - ay) / step).toInt())
            // Each edge contributes its start vertex and interior points; the next edge supplies
            // the shared corner, and the final end point is added once below.
            for (i in 0 until steps) {
                val t = i / steps.toFloat()
                out.add(ax + (bx - ax) * t, ay + (by - ay) * t)
            }
        }
        val last = if (closed) 0 else count - 1
        out.add(vertices[2 * last], vertices[2 * last + 1])
        return out.toArray()
    }

    /**
     * An ellipse starting at its rightmost point and running clockwise on screen (y grows down),
     * the same start and direction Path.addOval uses.
     */
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, spacing: Float): FloatArray {
        val a = max(rx, 0.01f)
        val b = max(ry, 0.01f)
        // Ramanujan's approximation of the perimeter; plenty for deciding how many samples to take.
        val h = ((a - b) * (a - b)) / ((a + b) * (a + b))
        val perimeter = (PI * (a + b) * (1 + 3 * h / (10 + sqrt(4 - 3 * h)))).toFloat()
        val step = effectiveSpacing(spacing, perimeter)
        val n = ceil(perimeter / step).toInt().coerceIn(16, MAX_POINTS - 1)
        val out = FloatList()
        for (i in 0 until n) {
            val angle = 2.0 * PI * i / n
            out.add(cx + a * cos(angle).toFloat(), cy + b * sin(angle).toFloat())
        }
        out.add(cx + a, cy) // exactly back to the start
        return out.toArray()
    }

    fun length(points: FloatArray): Float {
        var total = 0f
        var i = 2
        while (i + 1 < points.size) {
            total += hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
            i += 2
        }
        return total
    }

    private fun effectiveSpacing(spacing: Float, totalLength: Float): Float =
        max(max(spacing, 0.05f), totalLength / (MAX_POINTS - 1))

    private fun edgeLength(v: FloatArray, e: Int, count: Int): Float {
        val b = (e + 1) % count
        return hypot(v[2 * b] - v[2 * e], v[2 * b + 1] - v[2 * e + 1])
    }

    /** A growable float buffer, to avoid boxing thousands of samples. */
    private class FloatList {
        private var data = FloatArray(256)
        private var size = 0
        fun add(x: Float, y: Float) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
