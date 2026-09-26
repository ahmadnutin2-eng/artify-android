package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorFlowTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private val magenta = argb(255, 200, 40, 160)
    private val steady = { 0.5f }

    @Test
    fun `a brush with nothing switched on keeps its colour exactly`() {
        assertEquals(magenta, ColorFlow.shift(magenta, 0f, 0f, 0f, 0f, steady))
    }

    @Test
    fun `the conversion round trips`() {
        for (color in listOf(magenta, argb(255, 12, 200, 90), argb(255, 255, 255, 255), argb(255, 9, 9, 9))) {
            val hsv = ColorFlow.toHsv(color)
            val back = ColorFlow.fromHsv(255, hsv[0], hsv[1], hsv[2])
            assertEquals("round trip lost $color", color, back)
        }
    }

    @Test
    fun `drift is measured per thousand pixels, not per stamp`() {
        assertEquals(0f, ColorFlow.hueDrift(0f, 90f), 0.001f)
        assertEquals(90f, ColorFlow.hueDrift(1000f, 90f), 0.001f)
        assertEquals(45f, ColorFlow.hueDrift(500f, 90f), 0.001f)
    }

    @Test
    fun `a long stroke arrives at a different hue from where it started`() {
        val startHue = ColorFlow.toHsv(
            ColorFlow.shift(magenta, ColorFlow.hueDrift(0f, 120f), 0f, 0f, 0f, steady)
        )[0]
        val endHue = ColorFlow.toHsv(
            ColorFlow.shift(magenta, ColorFlow.hueDrift(1200f, 120f), 0f, 0f, 0f, steady)
        )[0]
        assertTrue("hue did not travel: $startHue -> $endHue", kotlin.math.abs(endHue - startHue) > 100f)
    }

    @Test
    fun `hue wraps round the wheel rather than clamping at either end`() {
        assertEquals(
            ColorFlow.shift(magenta, 30f, 0f, 0f, 0f, steady),
            ColorFlow.shift(magenta, 720f + 30f, 0f, 0f, 0f, steady)
        )
        assertEquals(350f, ColorFlow.wrapDegrees(-10f), 0.001f)
    }

    @Test
    fun `scatter cannot wash a colour out to white or flatten it to grey`() {
        // Both extremes of the generator, which is where an unclamped multiply does its damage.
        for (value in listOf(0f, 1f)) {
            val hsv = ColorFlow.toHsv(ColorFlow.shift(magenta, 0f, 1f, 1f, 1f) { value })
            // Read back after eight-bit packing, because that is what actually reaches the canvas -
            // a floor that only holds in floating point is no floor at all.
            assertTrue("saturation collapsed: ${hsv[1]}", hsv[1] >= 0.06f)
            assertTrue("brightness collapsed: ${hsv[2]}", hsv[2] >= 0.14f)
            assertTrue(hsv[1] <= 1f && hsv[2] <= 1f)
        }
    }

    @Test
    fun `alpha survives the round trip`() {
        val translucent = argb(90, 200, 40, 160)
        val shifted = ColorFlow.shift(translucent, 45f, 0.2f, 0.2f, 0.2f, steady)
        assertEquals(90, (shifted ushr 24) and 0xFF)
    }

    @Test
    fun `two stamps with different luck land on different colours`() {
        var call = 0
        val varying = { if (call++ % 2 == 0) 0.1f else 0.9f }
        val first = ColorFlow.shift(magenta, 0f, 0.4f, 0f, 0f, varying)
        val second = ColorFlow.shift(magenta, 0f, 0.4f, 0f, 0f, varying)
        assertNotEquals(first, second)
    }
}
