package com.procreate.android.urban.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class UrbanSnapEngineTest {

    private val allOff = SnapSettings(grid = false, vertex = false, midpoint = false, ortho = false)

    @Test
    fun `nothing moves when every snap mode is off`() {
        val result = UrbanSnapEngine.snap(
            x = 13.7f, y = 41.2f, settings = allOff, toleranceCanvasPx = 50f,
            candidatePoints = listOf(0f to 0f), gridSpacingPx = 10f
        )
        assertEquals(13.7f, result.x, 1e-4f)
        assertEquals(41.2f, result.y, 1e-4f)
        assertEquals(SnapKind.NONE, result.kind)
    }

    @Test
    fun `a vertex inside the tolerance wins over the grid`() {
        val result = UrbanSnapEngine.snap(
            x = 102f, y = 99f,
            settings = SnapSettings(grid = true, vertex = true),
            toleranceCanvasPx = 12f,
            candidatePoints = listOf(104f to 96f),
            gridSpacingPx = 100f
        )
        assertEquals(SnapKind.VERTEX, result.kind)
        assertEquals(104f, result.x, 1e-4f)
        assertEquals(96f, result.y, 1e-4f)
    }

    @Test
    fun `the grid only captures a point already within tolerance of an intersection`() {
        val near = UrbanSnapEngine.snap(
            x = 104f, y = 97f, settings = SnapSettings(grid = true, vertex = false),
            toleranceCanvasPx = 12f, gridSpacingPx = 100f
        )
        assertEquals(SnapKind.GRID, near.kind)
        assertEquals(100f, near.x, 1e-4f)
        assertEquals(100f, near.y, 1e-4f)

        val far = UrbanSnapEngine.snap(
            x = 140f, y = 150f, settings = SnapSettings(grid = true, vertex = false),
            toleranceCanvasPx = 12f, gridSpacingPx = 100f
        )
        assertEquals(SnapKind.NONE, far.kind)
        assertEquals(140f, far.x, 1e-4f)
    }

    @Test
    fun `ortho locks to an axis while preserving the dragged distance`() {
        val from = 0f to 0f
        // 100 px out at roughly 10 degrees - should land flat on the 0 degree axis.
        val result = UrbanSnapEngine.snap(
            x = 98.5f, y = 17.4f,
            settings = SnapSettings(vertex = false, ortho = true),
            toleranceCanvasPx = 12f,
            previousPoint = from
        )
        assertEquals(SnapKind.ORTHO, result.kind)
        assertEquals(0f, result.y, 1e-3f)
        val original = hypot(98.5, 17.4)
        val snapped = hypot(result.x.toDouble(), result.y.toDouble())
        assertEquals("distance must be preserved, not projected", original, snapped, 1e-3)
    }

    @Test
    fun `ortho reaches the diagonal axis too`() {
        val result = UrbanSnapEngine.snap(
            x = 70f, y = 74f,
            settings = SnapSettings(vertex = false, ortho = true),
            toleranceCanvasPx = 12f,
            previousPoint = 0f to 0f
        )
        assertEquals(SnapKind.ORTHO, result.kind)
        assertEquals("45 degrees means equal components", result.x, result.y, 1e-3f)
    }

    @Test
    fun `midpoint snapping targets the centre of a segment`() {
        val result = UrbanSnapEngine.snap(
            x = 49f, y = 3f,
            settings = SnapSettings(vertex = false, midpoint = true),
            toleranceCanvasPx = 10f,
            candidateSegments = listOf((0f to 0f) to (100f to 0f))
        )
        assertEquals(SnapKind.MIDPOINT, result.kind)
        assertEquals(50f, result.x, 1e-4f)
        assertEquals(0f, result.y, 1e-4f)
    }

    @Test
    fun `ortho does nothing without a previous point to measure from`() {
        val result = UrbanSnapEngine.snap(
            x = 33f, y = 71f,
            settings = SnapSettings(vertex = false, ortho = true),
            toleranceCanvasPx = 12f,
            previousPoint = null
        )
        assertEquals(SnapKind.NONE, result.kind)
        assertEquals(33f, result.x, 1e-4f)
    }

    @Test
    fun `a zero or negative tolerance disables snapping entirely`() {
        val result = UrbanSnapEngine.snap(
            x = 101f, y = 101f, settings = SnapSettings(grid = true),
            toleranceCanvasPx = 0f, gridSpacingPx = 100f
        )
        assertEquals(SnapKind.NONE, result.kind)
    }

    @Test
    fun `axis detection agrees with the constraint it enforces`() {
        assertTrue(UrbanSnapEngine.isOnAxis(0.0))
        assertTrue(UrbanSnapEngine.isOnAxis(Math.PI / 4.0))
        assertTrue(UrbanSnapEngine.isOnAxis(Math.PI / 2.0))
        assertTrue(!UrbanSnapEngine.isOnAxis(Math.PI / 6.0))
    }
}
