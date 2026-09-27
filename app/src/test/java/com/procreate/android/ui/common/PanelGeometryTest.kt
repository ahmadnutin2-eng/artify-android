package com.procreate.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review finding H2: the brush panel must stay on screen, 16dp from both edges, at every size. */
class PanelGeometryTest {

    private val edge = PanelGeometry.EDGE_DP

    private fun brushPanel(widthDp: Int, heightDp: Int, dockRight: Boolean = true) =
        PanelGeometry.floatingPlacement(
            requestedWidthDp = (widthDp * 0.355f).toInt().coerceIn(330, 620),
            gutterDp = (widthDp * 0.055f).toInt().coerceIn(64, 94),
            displayWidthDp = widthDp,
            displayHeightDp = heightDp,
            dockRight = dockRight
        )

    @Test
    fun `a portrait phone no longer puts the panel off screen`() {
        // The review's case: 360 - 330 - 64 was negative.
        val placement = brushPanel(360, 740)
        assertTrue("x=${placement.xDp}", placement.xDp >= edge)
        assertTrue(placement.xDp + placement.widthDp <= 360 - edge)
    }

    @Test
    fun `every common screen keeps both edges clear on either side`() {
        val screens = listOf(320 to 568, 360 to 740, 411 to 891, 740 to 360, 800 to 1280, 1280 to 800, 1848 to 2960, 2960 / 2 to 1848 / 2)
        for ((w, h) in screens) for (right in listOf(true, false)) {
            val p = brushPanel(w, h, right)
            assertTrue("$w x $h right=$right: $p", p.xDp >= edge)
            assertTrue("$w x $h right=$right: $p", p.xDp + p.widthDp <= w - edge)
            assertTrue(p.widthDp > 0)
        }
    }

    @Test
    fun `a large tablet keeps the requested width and the full gutter`() {
        val p = brushPanel(1280, 800)
        assertEquals(454, p.widthDp)
        assertEquals(1280 - 454 - 70, p.xDp)
    }

    @Test
    fun `a landscape phone keeps about half of its canvas visible`() {
        val p = brushPanel(740, 360)
        assertTrue(p.widthDp <= (740 * 0.52f).toInt())
    }
}
