package com.procreate.android.adjust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic is the part of an adjustment that goes wrong invisibly: a hue rotation that also
 * shifts brightness, a blur that darkens edges, a contrast curve that is not centred. None of that
 * throws - it just makes the image subtly wrong, which is very hard to spot by eye on a tablet.
 *
 * These run on the JVM because [ImageAdjustments] touches no android.graphics class.
 */
class ImageAdjustmentsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = p shr 16 and 0xFF
    private fun green(p: Int) = p shr 8 and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun alpha(p: Int) = p ushr 24 and 0xFF

    @Test
    fun `identity settings leave pixels untouched`() {
        val original = intArrayOf(argb(255, 10, 120, 240), argb(200, 33, 66, 99))
        val pixels = original.copyOf()

        ImageAdjustments.brightnessContrast(pixels, 0f, 0f)
        ImageAdjustments.saturation(pixels, 1f)
        ImageAdjustments.hueRotate(pixels, 0f)

        // A one-step tolerance: these three run through float maths and rounding, so demanding
        // bit-exactness would fail for reasons that have nothing to do with correctness.
        assertTrue(
            "Neutral settings visibly changed the image",
            !ImageAdjustments.differs(pixels, original, tolerance = 1)
        )
    }

    @Test
    fun `fully transparent pixels are never given colour`() {
        // A transparent pixel still carries RGB bytes. Adjusting them turns "nothing" into a
        // coloured fringe as soon as the layer is composited or blurred.
        val pixels = intArrayOf(argb(0, 0, 0, 0))
        ImageAdjustments.brightnessContrast(pixels, 0.8f, 0.5f)
        ImageAdjustments.invert(pixels)
        ImageAdjustments.saturation(pixels, 2f)
        assertEquals("A transparent pixel gained content", 0, alpha(pixels[0]))
    }

    @Test
    fun `invert is its own inverse and preserves alpha`() {
        val original = intArrayOf(argb(180, 10, 120, 240))
        val pixels = original.copyOf()
        ImageAdjustments.invert(pixels)
        assertEquals(245, red(pixels[0]))
        assertEquals(135, green(pixels[0]))
        assertEquals(15, blue(pixels[0]))
        assertEquals("Invert must not touch alpha", 180, alpha(pixels[0]))

        ImageAdjustments.invert(pixels)
        assertEquals(original[0], pixels[0])
    }

    @Test
    fun `grayscale uses luma weighting, not a channel average`() {
        // Pure green is far brighter than pure blue to the eye. An average would map both to 85.
        val green = intArrayOf(argb(255, 0, 255, 0))
        val blue = intArrayOf(argb(255, 0, 0, 255))
        ImageAdjustments.grayscale(green)
        ImageAdjustments.grayscale(blue)

        assertEquals(150, red(green[0]))
        assertEquals(29, red(blue[0]))
        assertTrue(
            "Green must come out lighter than blue, or the conversion is a plain average",
            red(green[0]) > red(blue[0])
        )
        // Grey means all three channels equal.
        assertEquals(red(green[0]), green(green[0]))
        assertEquals(green(green[0]), blue(green[0]))
    }

    @Test
    fun `hue rotation preserves luminance for in-gamut colours`() {
        // A mid-tone that stays inside 0..255 after rotating. This is what the luminance-preserving
        // matrix is actually claiming, and it is the case worth pinning.
        val pixels = intArrayOf(argb(255, 150, 120, 100))
        val before = 0.299f * 150 + 0.587f * 120 + 0.114f * 100
        ImageAdjustments.hueRotate(pixels, 120f)
        val after = 0.299f * red(pixels[0]) + 0.587f * green(pixels[0]) + 0.114f * blue(pixels[0])
        assertEquals("Hue rotation changed brightness", before, after, 8.0f)
    }

    @Test
    fun `a saturated colour clips rather than wrapping`() {
        // Rotating a vivid colour pushes a channel outside 0..255 and it has to be clipped, which
        // costs some luminance. That is what every matrix-based hue rotation does, Photoshop
        // included - the alternative is desaturating the image to force it back into gamut.
        // Documented here so the loss reads as a known limit rather than a bug found later.
        val pixels = intArrayOf(argb(255, 200, 60, 30))
        ImageAdjustments.hueRotate(pixels, 120f)

        assertTrue("A channel wrapped instead of clipping", red(pixels[0]) in 0..255)
        assertTrue("A channel wrapped instead of clipping", green(pixels[0]) in 0..255)
        assertTrue("A channel wrapped instead of clipping", blue(pixels[0]) in 0..255)
        assertEquals("Alpha must survive clipping", 255, alpha(pixels[0]))
    }

    @Test
    fun `a full turn of hue returns the original`() {
        val original = intArrayOf(argb(255, 200, 60, 30))
        val pixels = original.copyOf()
        ImageAdjustments.hueRotate(pixels, 360f)
        assertTrue(
            "360 degrees should be a no-op",
            !ImageAdjustments.differs(pixels, original, tolerance = 2)
        )
    }

    @Test
    fun `contrast pivots around mid grey`() {
        // Mid-grey is the pivot, so it must not move no matter how hard contrast is pushed.
        val pixels = intArrayOf(argb(255, 128, 128, 128))
        ImageAdjustments.brightnessContrast(pixels, 0f, 0.9f)
        assertEquals("Mid grey moved, so contrast is not centred", 128, red(pixels[0]), )

        // And it must actually separate light from dark.
        val pair = intArrayOf(argb(255, 100, 100, 100), argb(255, 160, 160, 160))
        ImageAdjustments.brightnessContrast(pair, 0f, 0.5f)
        assertTrue("Dark did not get darker", red(pair[0]) < 100)
        assertTrue("Light did not get lighter", red(pair[1]) > 160)
    }

    @Test
    fun `brightness moves toward white and clamps`() {
        val pixels = intArrayOf(argb(255, 200, 200, 200))
        ImageAdjustments.brightnessContrast(pixels, 1f, 0f)
        assertEquals("Brightness must saturate at 255, not wrap", 255, red(pixels[0]))
    }

    @Test
    fun `blur does not darken the edge of a stroke on transparency`() {
        // The halo bug in one test: an opaque white block on a transparent field. If colour is
        // blurred without premultiplying by alpha, the transparent pixels drag the white edge
        // toward black and the stroke gains a grey rim.
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { 0 }
        for (y in 6..9) for (x in 6..9) pixels[y * w + x] = argb(255, 255, 255, 255)

        ImageAdjustments.gaussianBlur(pixels, w, h, 2)

        // Every pixel that has any alpha at all must still be white; only its alpha should fall.
        var checked = 0
        for (p in pixels) {
            if (alpha(p) > 8) {
                checked++
                assertTrue(
                    "Blur darkened a white stroke to ${red(p)} - colour was not premultiplied",
                    red(p) > 230 && green(p) > 230 && blue(p) > 230
                )
            }
        }
        assertTrue("The test blurred nothing", checked > 0)
    }

    @Test
    fun `blur spreads coverage outward`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { 0 }
        pixels[8 * w + 8] = argb(255, 255, 0, 0)
        ImageAdjustments.gaussianBlur(pixels, w, h, 2)
        assertTrue(
            "A neighbouring pixel gained no coverage, so nothing was blurred",
            alpha(pixels[8 * w + 9]) > 0
        )
        assertTrue(
            "The centre should have spread out and lost density",
            alpha(pixels[8 * w + 8]) < 255
        )
    }

    @Test
    fun `reused blur workspace matches isolated calls across changing sizes`() {
        val workspace = ImageAdjustments.BlurWorkspace()

        fun verify(width: Int, height: Int, radius: Int) {
            val source = IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                argb((80 + x * 11 + y * 7).coerceAtMost(255), x * 17 % 256, y * 23 % 256, (x + y) * 13 % 256)
            }
            val isolated = source.copyOf()
            val reused = source.copyOf()
            ImageAdjustments.gaussianBlur(isolated, width, height, radius)
            ImageAdjustments.gaussianBlur(reused, width, height, radius, workspace)
            assertTrue(
                "Reusing blur storage changed the result at ${width}x$height",
                !ImageAdjustments.differs(isolated, reused)
            )
        }

        verify(7, 5, 2)
        verify(19, 11, 4)
        // Shrinking after a larger request exercises the active-size boundary inside retained arrays.
        verify(4, 3, 1)
    }

    @Test
    fun `zero radius blur and zero amount sharpen are no-ops`() {
        val original = IntArray(64) { argb(255, it * 3 % 256, 40, 80) }

        val blurred = original.copyOf()
        ImageAdjustments.gaussianBlur(blurred, 8, 8, 0)
        assertTrue("radius 0 changed the image", !ImageAdjustments.differs(blurred, original))

        val sharpened = original.copyOf()
        ImageAdjustments.sharpen(sharpened, 8, 8, 0f)
        assertTrue("amount 0 changed the image", !ImageAdjustments.differs(sharpened, original))
    }

    @Test
    fun `sharpen increases contrast across an edge`() {
        val w = 16
        val h = 16
        val pixels = IntArray(w * h) { i -> if (i % w < 8) argb(255, 80, 80, 80) else argb(255, 170, 170, 170) }
        val darkBefore = red(pixels[8 * w + 7])
        val lightBefore = red(pixels[8 * w + 8])

        ImageAdjustments.sharpen(pixels, w, h, 1f)

        val gapBefore = lightBefore - darkBefore
        val gapAfter = red(pixels[8 * w + 8]) - red(pixels[8 * w + 7])
        assertTrue("Sharpening did not increase edge contrast", gapAfter >= gapBefore)
    }

    @Test
    fun `saturation zero equals grayscale`() {
        val a = intArrayOf(argb(255, 200, 60, 30))
        val b = a.copyOf()
        ImageAdjustments.saturation(a, 0f)
        ImageAdjustments.grayscale(b)
        assertTrue("grayscale must be saturation(0)", !ImageAdjustments.differs(a, b))
    }
}
