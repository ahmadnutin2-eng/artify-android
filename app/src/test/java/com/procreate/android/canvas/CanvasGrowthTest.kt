package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasGrowthTest {

    private val chunk = 640
    private val margin = 120f

    private fun plan(l: Float, t: Float, r: Float, b: Float, w: Int = 2000, h: Int = 1500) =
        CanvasGrowth.plan(l, t, r, b, w, h, margin, chunk)

    @Test
    fun `drawing well inside the paper adds nothing`() {
        assertTrue(plan(500f, 500f, 900f, 900f).isNone)
    }

    @Test
    fun `approaching an edge grows by one chunk`() {
        val p = plan(100f, 500f, 400f, 900f)
        assertEquals(chunk, p.left)
        assertEquals(0, p.right)
    }

    @Test
    fun `starting far outside the paper grows enough to reach it`() {
        // The reported failure: the pen goes down 1500px left of the paper. One chunk (640) cannot
        // reach that, so the first samples were painted onto nothing and lost.
        val p = plan(-1500f, 500f, -1400f, 600f)
        val shiftedLeft = -1500f + p.left
        assertTrue("dirty rect must land inside the grown paper with margin", shiftedLeft >= margin)
        assertEquals("growth is whole chunks so the paper grid stays in phase", 0, p.left % chunk)
    }

    @Test
    fun `growth lands the drawing inside on every side`() {
        val w = 2000
        val h = 1500
        val p = CanvasGrowth.plan(-2600f, -900f, w + 3100f, h + 2200f, w, h, margin, chunk)
        assertTrue(-2600f + p.left >= margin)
        assertTrue(-900f + p.top >= margin)
        assertTrue(w + 3100f + p.left <= w + p.left + p.right - margin)
        assertTrue(h + 2200f + p.top <= h + p.top + p.bottom - margin)
    }

    @Test
    fun `growth is always a whole number of chunks`() {
        val p = plan(-3000f, -10f, 5000f, 4000f)
        assertEquals(0, p.left % chunk)
        assertEquals(0, p.top % chunk)
        assertEquals(0, p.right % chunk)
        assertEquals(0, p.bottom % chunk)
    }

    @Test
    fun `an overshoot of a single pixel still adds one whole chunk`() {
        val p = plan(margin - 1f, 500f, 600f, 900f)
        assertEquals(chunk, p.left)
    }

    @Test
    fun `an overshoot of exactly one chunk adds exactly one`() {
        val p = plan(margin - chunk, 500f, 600f, 900f)
        assertEquals(chunk, p.left)
    }

    @Test
    fun `the memory fallback caps every side at one chunk`() {
        val big = plan(-5000f, -5000f, 9000f, 9000f)
        val capped = big.cappedTo(chunk)
        assertEquals(chunk, capped.left)
        assertEquals(chunk, capped.top)
        assertEquals(chunk, capped.right)
        assertEquals(chunk, capped.bottom)
    }

    @Test
    fun `a capped plan never exceeds the plan it came from`() {
        val small = plan(100f, 500f, 400f, 900f)
        assertEquals(small, small.cappedTo(chunk))
    }
}
