package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class StrokeInputStatsTest {

    @Test
    fun `median pressure ignores a light touch down and lift`() {
        val stats = StrokeInputStats()
        listOf(0.1f, 0.6f, 0.62f, 0.58f, 0.61f, 0.05f).forEach { stats.add(it, 0f, 0f) }
        // Sorted: 0.05 0.1 0.58 0.6 0.61 0.62 - the middle pair averages to 0.59.
        assertEquals(0.59f, stats.medianPressure(), 1e-4f)
    }

    @Test
    fun `fallbacks apply before any sample`() {
        val stats = StrokeInputStats()
        assertEquals(0.7f, stats.medianPressure(0.7f), 0f)
        assertEquals(0.3f, stats.meanAzimuth(0.3f), 0f)
    }

    @Test
    fun `the barrel direction averages across the wrap instead of flipping`() {
        val stats = StrokeInputStats()
        val nearPi = PI.toFloat() - 0.1f
        stats.add(1f, 0f, nearPi)
        stats.add(1f, 0f, -nearPi)
        val mean = stats.meanAzimuth()
        assertEquals(0f, StylusResponse.angleDeltaRadians(PI.toFloat(), mean), 1e-4f)
    }

    @Test
    fun `reset starts a new stroke and the buffer grows without losing samples`() {
        val stats = StrokeInputStats()
        repeat(1000) { stats.add(0.9f, 0.2f, 0f) }
        assertEquals(1000, stats.size)
        stats.reset()
        stats.add(0.4f, 0f, 0f)
        assertEquals(0.4f, stats.medianPressure(), 0f)
    }
}
