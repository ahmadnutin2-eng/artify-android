package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class StrokeSmoothingTest {

    @Test
    fun `zero smoothing follows the pen exactly`() {
        assertEquals(1f, StrokeSmoothing.response(0f, 8L), 0f)
    }

    @Test
    fun `stronger smoothing responds more gently`() {
        val balanced = StrokeSmoothing.response(0.45f, 8L)
        val verySmooth = StrokeSmoothing.response(0.9f, 8L)
        assertTrue(verySmooth < balanced)
        assertTrue(verySmooth > 0f)
    }

    @Test
    fun `physical response is stable at 60 and 120 hertz`() {
        val at120 = 1f - (1f - StrokeSmoothing.response(0.75f, 8L)).pow(15)
        val at60 = 1f - (1f - StrokeSmoothing.response(0.75f, 16L)).pow(8)
        assertEquals(at120, at60, 0.06f)
    }

    @Test
    fun `no smoothing means the drawn point never trails the pen`() {
        assertEquals(0f, StrokeSmoothing.maxTrail(0f, 40f), 0f)
    }

    @Test
    fun `trail grows with smoothing and with brush size but stays short`() {
        val light = StrokeSmoothing.maxTrail(0.3f, 20f)
        val heavy = StrokeSmoothing.maxTrail(0.95f, 20f)
        assertTrue(heavy > light)
        assertTrue(StrokeSmoothing.maxTrail(0.95f, 120f) > heavy)
        // The whole point is that the catch-up at lift is short enough to vanish into the stroke;
        // an unbounded filter at 0.95 settles hundreds of pixels behind.
        assertTrue(heavy < 60f)
    }

    @Test
    fun `an unbounded filter trails much further than the cap allows`() {
        // Twenty 120 Hz samples of a pen moving 10 canvas px each, at maximum smoothing.
        var smoothed = 0f
        var pen = 0f
        repeat(20) {
            pen += 10f
            smoothed += (pen - smoothed) * StrokeSmoothing.response(0.95f, 8L)
        }
        assertTrue(pen - smoothed > StrokeSmoothing.maxTrail(0.95f, 20f))
    }

    @Test
    fun `edge pan is idle in the center and reveals both sides`() {
        assertEquals(0f, OpenCanvasEdgePan.translationDelta(500f, 1000f, 100f, 500f, 16L), 0f)
        assertTrue(OpenCanvasEdgePan.translationDelta(10f, 1000f, 100f, 500f, 16L) > 0f)
        assertTrue(OpenCanvasEdgePan.translationDelta(990f, 1000f, 100f, 500f, 16L) < 0f)
    }

    @Test
    fun `edge pan is eased rather than jumping at activation line`() {
        val shallow = OpenCanvasEdgePan.translationDelta(90f, 1000f, 100f, 500f, 16L)
        val deep = OpenCanvasEdgePan.translationDelta(10f, 1000f, 100f, 500f, 16L)
        assertTrue(deep > shallow)
        assertTrue(shallow < 0.1f)
    }
}
