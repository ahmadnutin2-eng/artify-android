package com.procreate.android.adjust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFiltersTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = p shr 16 and 0xFF
    private fun green(p: Int) = p shr 8 and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun alpha(p: Int) = p ushr 24 and 0xFF

    private fun field(w: Int, h: Int, colour: Int) = IntArray(w * h) { colour }

    @Test
    fun `every filter leaves transparent pixels transparent`() {
        // The single failure mode all ten share: a transparent pixel still holds RGB bytes, and a
        // filter that rewrites them turns empty canvas into a coloured wash the moment the layer
        // is composited.
        for (filter in ImageFilters.Filter.entries) {
            val pixels = IntArray(64) { 0 }
            ImageFilters.apply(pixels, 8, 8, filter)
            assertTrue(
                "$filter gave colour to fully transparent pixels",
                pixels.all { alpha(it) == 0 }
            )
        }
    }

    @Test
    fun `every filter changes something at full strength`() {
        // Guards against a filter that silently does nothing - which looks exactly like a filter
        // that is applied but too subtle to see.
        for (filter in ImageFilters.Filter.entries) {
            val original = IntArray(32 * 32) { i ->
                argb(255, (i * 7) % 256, (i * 3) % 256, (i * 11) % 256)
            }
            val pixels = original.copyOf()
            ImageFilters.apply(pixels, 32, 32, filter)
            assertTrue("$filter had no effect", ImageAdjustments.differs(pixels, original, 1))
        }
    }

    @Test
    fun `zero strength is a no-op for every filter`() {
        for (filter in ImageFilters.Filter.entries) {
            val original = IntArray(256) { i -> argb(255, i % 256, 128, 64) }
            val pixels = original.copyOf()
            ImageFilters.apply(pixels, 16, 16, filter, strength = 0f)
            assertTrue("$filter changed the image at strength 0", !ImageAdjustments.differs(pixels, original))
        }
    }

    @Test
    fun `strength scales the effect`() {
        val base = field(16, 16, argb(255, 200, 120, 60))

        val half = base.copyOf()
        ImageFilters.apply(half, 16, 16, ImageFilters.Filter.SEPIA, strength = 0.5f)
        val full = base.copyOf()
        ImageFilters.apply(full, 16, 16, ImageFilters.Filter.SEPIA, strength = 1f)

        val deltaHalf = kotlin.math.abs(red(half[0]) - red(base[0]))
        val deltaFull = kotlin.math.abs(red(full[0]) - red(base[0]))
        assertTrue("Half strength should move less than full", deltaHalf < deltaFull)
        assertTrue("Half strength should still move something", deltaHalf > 0)
    }

    @Test
    fun `grain is deterministic`() {
        // A filter whose preview differs from what it applies is worse than no preview.
        val a = field(16, 16, argb(255, 128, 128, 128))
        val b = a.copyOf()
        ImageFilters.grain(a, 22)
        ImageFilters.grain(b, 22)
        assertTrue("Grain must produce the same result every run", !ImageAdjustments.differs(a, b))
    }

    @Test
    fun `vignette darkens corners and spares the centre`() {
        val w = 33
        val h = 33
        val pixels = field(w, h, argb(255, 200, 200, 200))
        ImageFilters.vignette(pixels, w, h, 0.85f)

        val centre = pixels[(h / 2) * w + (w / 2)]
        val corner = pixels[0]
        assertEquals("The centre must not be touched", 200, red(centre))
        assertTrue("The corner should be noticeably darker", red(corner) < 150)
    }

    @Test
    fun `posterize collapses to the requested number of levels`() {
        val pixels = IntArray(256) { i -> argb(255, i, i, i) }
        ImageFilters.posterize(pixels, 4)
        val distinct = pixels.map { red(it) }.toSet()
        assertEquals("Expected exactly 4 output levels", 4, distinct.size)
        assertTrue("Posterize should still span the full range", distinct.contains(0) && distinct.contains(255))
    }

    @Test
    fun `temperature warms and cools in opposite directions`() {
        val warm = field(4, 4, argb(255, 128, 128, 128))
        val cool = warm.copyOf()
        ImageFilters.temperature(warm, 28)
        ImageFilters.temperature(cool, -28)

        assertTrue("Warm must raise red", red(warm[0]) > 128)
        assertTrue("Warm must lower blue", blue(warm[0]) < 128)
        assertTrue("Cool must lower red", red(cool[0]) < 128)
        assertTrue("Cool must raise blue", blue(cool[0]) > 128)
        assertEquals("Neither should touch green", 128, green(warm[0]))
    }

    @Test
    fun `noir produces neutral grey`() {
        val pixels = field(8, 8, argb(255, 200, 60, 30))
        ImageFilters.apply(pixels, 8, 8, ImageFilters.Filter.NOIR)
        assertEquals(red(pixels[0]), green(pixels[0]))
        assertEquals(green(pixels[0]), blue(pixels[0]))
    }

    @Test
    fun `sepia warms a neutral grey`() {
        val pixels = field(8, 8, argb(255, 128, 128, 128))
        ImageFilters.apply(pixels, 8, 8, ImageFilters.Filter.SEPIA)
        assertTrue("Sepia must be warmer than it is cool", red(pixels[0]) > blue(pixels[0]))
        assertNotEquals("Sepia must not leave the pixel neutral", red(pixels[0]), blue(pixels[0]))
    }

    @Test
    fun `filters never produce out-of-range channels`() {
        // Clamping bugs show up as wrapped channels: a highlight that should be white turning
        // black. Feeding each filter both extremes is the cheapest way to catch it.
        for (filter in ImageFilters.Filter.entries) {
            val pixels = intArrayOf(
                argb(255, 0, 0, 0), argb(255, 255, 255, 255),
                argb(255, 255, 0, 0), argb(255, 0, 0, 255)
            )
            ImageFilters.apply(pixels, 2, 2, filter)
            for (p in pixels) {
                assertTrue("$filter produced a red outside 0..255", red(p) in 0..255)
                assertTrue("$filter produced a green outside 0..255", green(p) in 0..255)
                assertTrue("$filter produced a blue outside 0..255", blue(p) in 0..255)
                assertEquals("$filter altered alpha", 255, alpha(p))
            }
        }
    }
}
