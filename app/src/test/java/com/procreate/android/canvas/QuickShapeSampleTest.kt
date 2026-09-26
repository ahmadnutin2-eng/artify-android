package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class QuickShapeSampleTest {

    private fun maxGap(points: FloatArray): Float {
        var gap = 0f
        var i = 2
        while (i + 1 < points.size) {
            gap = maxOf(gap, hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1]))
            i += 2
        }
        return gap
    }

    @Test
    fun `a line starts and ends exactly on its end points`() {
        val p = QuickShapeSampler.line(10f, 20f, 110f, 20f, 1f)
        assertEquals(10f, p[0], 0f); assertEquals(20f, p[1], 0f)
        assertEquals(110f, p[p.size - 2], 0f); assertEquals(20f, p[p.size - 1], 0f)
        assertEquals(100f, QuickShapeSampler.length(p), 0.01f)
    }

    @Test
    fun `samples are never further apart than asked`() {
        val line = QuickShapeSampler.line(0f, 0f, 300f, 400f, 2f)
        assertTrue(maxGap(line) <= 2f + 1e-3f)
        val circle = QuickShapeSampler.ellipse(0f, 0f, 200f, 200f, 1.5f)
        assertTrue(maxGap(circle) <= 1.5f + 1e-2f)
    }

    @Test
    fun `closed shapes return to where they started`() {
        val circle = QuickShapeSampler.ellipse(50f, 60f, 30f, 20f, 1f)
        assertEquals(circle[0], circle[circle.size - 2], 1e-4f)
        assertEquals(circle[1], circle[circle.size - 1], 1e-4f)
        val square = QuickShapeSampler.polygon(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f), true, 1f)
        assertEquals(0f, square[square.size - 2], 0f)
        assertEquals(0f, square[square.size - 1], 0f)
    }

    @Test
    fun `a circle's sampled length is its circumference`() {
        val circle = QuickShapeSampler.ellipse(0f, 0f, 100f, 100f, 0.5f)
        assertEquals((2 * PI * 100).toFloat(), QuickShapeSampler.length(circle), 0.5f)
    }

    @Test
    fun `a rectangle's sampled length is its perimeter and every corner is a sample`() {
        val rect = QuickShapeSampler.polygon(floatArrayOf(0f, 0f, 40f, 0f, 40f, 30f, 0f, 30f), true, 3f)
        assertEquals(140f, QuickShapeSampler.length(rect), 0.01f)
        val pairs = rect.toList().chunked(2).map { it[0] to it[1] }.toSet()
        assertTrue(pairs.containsAll(listOf(0f to 0f, 40f to 0f, 40f to 30f, 0f to 30f)))
    }

    @Test
    fun `a huge shape is capped rather than producing unbounded samples`() {
        val circle = QuickShapeSampler.ellipse(0f, 0f, 20000f, 20000f, 0.5f)
        assertTrue(circle.size / 2 <= QuickShapeSampler.MAX_POINTS)
    }

    @Test
    fun `a zero length line still yields a start and an end`() {
        val p = QuickShapeSampler.line(5f, 5f, 5f, 5f, 1f)
        assertTrue(p.size >= 4)
    }
}
