package com.procreate.android.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bucket's whole job is deciding where to stop, and every way it gets that wrong is silent: it
 * floods the canvas, or it leaves a halo, or it stops short. None of that throws.
 */
class SmartFillTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val clear = 0x00000000

    private fun canvas(w: Int, h: Int, fill: Int = white) = IntArray(w * h) { fill }

    private fun IntArray.set(w: Int, x: Int, y: Int, c: Int) { this[y * w + x] = c }
    private fun IntArray.at(w: Int, x: Int, y: Int) = this[y * w + x]
    private fun BooleanArray.at(w: Int, x: Int, y: Int) = this[y * w + x]

    /** A vertical line down column [x], with a [gap]-pixel hole at [gapY]. */
    private fun verticalLineWithGap(px: IntArray, w: Int, h: Int, x: Int, gapY: Int, gap: Int) {
        for (y in 0 until h) {
            if (y in gapY until gapY + gap) continue
            px.set(w, x, y, black)
        }
    }

    // ---------------------------------------------------------------- the reported problem

    @Test
    fun `a plain fill escapes through a gap - the bug being fixed`() {
        // Establishes that the test setup really does contain a leak, so the pass below means
        // something. Without this, gap closing could be doing nothing and still look correct.
        val w = 40; val h = 20
        val px = canvas(w, h)
        verticalLineWithGap(px, w, h, x = 20, gapY = 9, gap = 3)

        val mask = SmartFill.computeMask(px, w, h, 5, 10, SmartFill.Options(gapClosing = 0, expand = 0))!!
        assertTrue("Without gap closing the fill must leak to the far side", mask.at(w, 35, 10))
    }

    @Test
    fun `gap closing keeps the fill on its own side`() {
        val w = 40; val h = 20
        val px = canvas(w, h)
        verticalLineWithGap(px, w, h, x = 20, gapY = 9, gap = 3)

        val mask = SmartFill.computeMask(px, w, h, 5, 10, SmartFill.Options(gapClosing = 4, expand = 0))!!
        assertTrue("The clicked side must still fill", mask.at(w, 5, 10))
        assertTrue("and right up to the line", mask.at(w, 19, 3))
        assertFalse("but must not cross the gap", mask.at(w, 35, 10))
        assertFalse(mask.at(w, 25, 10))
    }

    @Test
    fun `a gap wider than the setting is still an opening`() {
        // Gap closing seals up to 2 * gapClosing. A 12-wide gap against a setting of 3 must stay
        // open - silently sealing everything would make the tool unpredictable.
        val w = 40; val h = 30
        val px = canvas(w, h)
        verticalLineWithGap(px, w, h, x = 20, gapY = 9, gap = 12)

        val mask = SmartFill.computeMask(px, w, h, 5, 15, SmartFill.Options(gapClosing = 3, expand = 0))!!
        assertTrue("A gap this wide is a real opening", mask.at(w, 35, 15))
    }

    @Test
    fun `hair - many strokes with gaps between them`() {
        // The case described: a shape drawn as separate strokes, each pair a few pixels apart.
        // Filling inside must not escape between any of them.
        val w = 60; val h = 40
        val px = canvas(w, h)
        // Outline with a deliberately broken right edge, drawn as dashes.
        for (x in 10..50) { px.set(w, x, 8, black); px.set(w, x, 32, black) }
        for (y in 8..32) px.set(w, 10, y, black)
        for (y in 8..32) if ((y / 3) % 2 == 0) px.set(w, 50, y, black) // dashed: 3 on, 3 off

        val leaky = SmartFill.computeMask(px, w, h, 30, 20, SmartFill.Options(gapClosing = 0, expand = 0))!!
        assertTrue("setup check: the dashes really do leak", leaky.at(w, 58, 20))

        val sealedMask = SmartFill.computeMask(px, w, h, 30, 20, SmartFill.Options(gapClosing = 3, expand = 0))!!
        assertTrue("the inside fills", sealedMask.at(w, 30, 20))
        assertTrue("including near the broken edge", sealedMask.at(w, 48, 20))
        assertFalse("and nothing escapes past it", sealedMask.at(w, 58, 20))
    }

    // ---------------------------------------------------------------- growing back

    @Test
    fun `sealing does not leave a halo along a real line`() {
        // Step 4 exists for this: after filling inside a thickened barrier, the region must be
        // grown back to touch the line, or every fill is ringed by unpainted pixels.
        val w = 30; val h = 30
        val px = canvas(w, h)
        for (y in 0 until h) px.set(w, 15, y, black)

        val mask = SmartFill.computeMask(px, w, h, 5, 15, SmartFill.Options(gapClosing = 5, expand = 0))!!
        assertTrue("must reach the pixel adjacent to the line", mask.at(w, 14, 15))
        assertFalse("but not paint over the line itself", mask.at(w, 15, 15))
    }

    @Test
    fun `expand slides the fill under an antialiased edge`() {
        val w = 30; val h = 30
        val px = canvas(w, h)
        for (y in 0 until h) {
            px.set(w, 15, y, 0xFF808080.toInt()) // the soft edge of a line
            px.set(w, 16, y, black)
        }

        val flat = SmartFill.computeMask(px, w, h, 5, 15, SmartFill.Options(gapClosing = 0, expand = 0))!!
        assertFalse("without expand the soft pixel stays unpainted", flat.at(w, 15, 15))

        val expanded = SmartFill.computeMask(px, w, h, 5, 15, SmartFill.Options(gapClosing = 0, expand = 1))!!
        assertTrue("expand must cover the soft edge", expanded.at(w, 15, 15))
    }

    @Test
    fun `expand of one does not punch through a one pixel line`() {
        // Growing into the line art is bounded; it must not reach the far side of a thin line and
        // start a second, unwanted fill there.
        val w = 30; val h = 10
        val px = canvas(w, h)
        for (y in 0 until h) px.set(w, 15, y, black)

        val mask = SmartFill.computeMask(px, w, h, 5, 5, SmartFill.Options(gapClosing = 0, expand = 1))!!
        assertTrue("reaches the line", mask.at(w, 15, 5))
        assertFalse("but stops there", mask.at(w, 16, 5))
    }

    // ---------------------------------------------------------------- basics

    @Test
    fun `tolerance decides what counts as the same region`() {
        val w = 10; val h = 10
        val px = canvas(w, h)
        for (y in 0 until h) px.set(w, 5, y, 0xFFF0F0F0.toInt()) // nearly white

        val strict = SmartFill.computeMask(px, w, h, 0, 5, SmartFill.Options(tolerance = 4, gapClosing = 0, expand = 0))!!
        assertFalse("a strict tolerance treats the near-white as a boundary", strict.at(w, 9, 5))

        val loose = SmartFill.computeMask(px, w, h, 0, 5, SmartFill.Options(tolerance = 40, gapClosing = 0, expand = 0))!!
        assertTrue("a loose one passes straight through it", loose.at(w, 9, 5))
    }

    @Test
    fun `a click outside the image fills nothing`() {
        val px = canvas(10, 10)
        assertNull(SmartFill.computeMask(px, 10, 10, -1, 5))
        assertNull(SmartFill.computeMask(px, 10, 10, 10, 5))
    }

    @Test
    fun `filling an enclosed shape does not touch the outside`() {
        val w = 20; val h = 20
        val px = canvas(w, h)
        for (i in 5..14) {
            px.set(w, i, 5, black); px.set(w, i, 14, black)
            px.set(w, 5, i, black); px.set(w, 14, i, black)
        }

        val mask = SmartFill.computeMask(px, w, h, 10, 10, SmartFill.Options(gapClosing = 4, expand = 0))!!
        assertTrue(mask.at(w, 10, 10))
        assertFalse("outside the box must be untouched", mask.at(w, 1, 1))
        assertFalse(mask.at(w, 18, 18))
    }

    @Test
    fun `non contiguous mode fills every matching area`() {
        val w = 20; val h = 10
        val px = canvas(w, h)
        for (y in 0 until h) px.set(w, 10, y, black) // two separate white halves

        val contiguous = SmartFill.computeMask(px, w, h, 2, 5, SmartFill.Options(contiguous = true, gapClosing = 0, expand = 0))!!
        assertFalse(contiguous.at(w, 18, 5))

        val global = SmartFill.computeMask(px, w, h, 2, 5, SmartFill.Options(contiguous = false))!!
        assertTrue("non-contiguous reaches the far half too", global.at(w, 18, 5))
        assertFalse("but still not the line", global.at(w, 10, 5))
    }

    @Test
    fun `transparent pixels are matched on alpha alone`() {
        // Two fully transparent pixels can carry completely different RGB. Comparing it would split
        // one empty area into several for a reason nothing on screen explains.
        val w = 10; val h = 10
        val px = IntArray(w * h) { if (it % 2 == 0) clear else 0x00FF0000 }

        val mask = SmartFill.computeMask(px, w, h, 0, 0, SmartFill.Options(tolerance = 0, gapClosing = 0, expand = 0))!!
        assertTrue("all transparent pixels are one region", mask.all { it })
    }

    @Test
    fun `the selection clip keeps the fill inside it`() {
        val w = 30; val h = 30
        val px = canvas(w, h)
        val mask = SmartFill.computeMask(px, w, h, 10, 10, SmartFill.Options(gapClosing = 2, expand = 2)) { x, y ->
            x in 5..15 && y in 5..15
        }!!
        assertTrue(mask.at(w, 10, 10))
        assertFalse("growth must respect the selection too", mask.at(w, 20, 10))
        assertFalse(mask.at(w, 16, 10))
    }

    @Test
    fun `fill reports whether it changed anything`() {
        val w = 10; val h = 10
        val px = canvas(w, h)
        assertTrue(SmartFill.fill(px, w, h, 5, 5, red))
        assertEquals(red, px.at(w, 5, 5))
        assertFalse("filling the same colour again changes nothing", SmartFill.fill(px, w, h, 5, 5, red))
    }

    @Test
    fun `a one pixel image does not crash`() {
        val px = intArrayOf(white)
        assertTrue(SmartFill.fill(px, 1, 1, 0, 0, red, SmartFill.Options(gapClosing = 4, expand = 2)))
        assertEquals(red, px[0])
    }

    @Test
    fun `gap closing never loses the clicked pixel itself`() {
        // A narrow corridor is entirely within the seal radius, so the flood in step 3 finds
        // nothing. The click must still do something rather than silently failing.
        val w = 30; val h = 30
        val px = canvas(w, h)
        for (y in 0 until h) { px.set(w, 14, y, black); px.set(w, 16, y, black) }

        val mask = SmartFill.computeMask(px, w, h, 15, 15, SmartFill.Options(gapClosing = 6, expand = 0))
        assertTrue("a click inside a thin corridor must still fill it", mask != null && mask.at(w, 15, 15))
    }
}
