package com.procreate.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review finding H2: the brush panel must stay on screen, 16dp from both edges, at every size. */
class PanelGeometryTest {

    private val edge = PanelGeometry.EDGE_DP

    private fun brushPanel(widthDp: Int, heightDp: Int, dockRight: Boolean = true) =
        PanelGeometry.brushPanelPlacement(widthDp, heightDp, dockRight)

    @Test
    fun `a portrait phone no longer puts the panel off screen`() {
        // The review's case: 360 - 330 - 64 was negative.
        val placement = brushPanel(360, 740)
        assertTrue("x=${placement.xDp}", placement.xDp >= edge)
        assertTrue(placement.xDp + placement.widthDp <= 360 - edge)
    }

    @Test
    fun `every common screen keeps both edges clear on either side`() {
        val screens = listOf(
            320 to 568, 360 to 640, 360 to 740, 411 to 891, 640 to 360, 740 to 360,
            800 to 1280, 1280 to 800, 1848 to 2960, 2960 / 2 to 1848 / 2
        )
        for ((w, h) in screens) for (right in listOf(true, false)) {
            val p = brushPanel(w, h, right)
            assertTrue("$w x $h right=$right: $p", p.xDp >= edge)
            assertTrue("$w x $h right=$right: $p", p.xDp + p.widthDp <= w - edge)
            val visibleCanvas = if (right) p.xDp else w - p.xDp - p.widthDp
            assertTrue(
                "$w x $h right=$right leaves only $visibleCanvas dp: $p",
                visibleCanvas >= (w * PanelGeometry.MIN_VISIBLE_CANVAS_FRACTION).toInt()
            )
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

    @Test
    fun `narrow split screen preserves the tool rail gutter`() {
        for (width in listOf(360, 640)) for (right in listOf(true, false)) {
            val p = brushPanel(width, 640, right)
            val railGutter = if (right) width - p.xDp - p.widthDp else p.xDp
            assertTrue("width=$width right=$right: $p", railGutter >= PanelGeometry.TOOL_RAIL_GUTTER_DP)
        }
    }

    @Test
    fun `narrow split screen leaves a usable brush preview column`() {
        val panelWidth = brushPanel(360, 640).widthDp
        val railWidth = PanelGeometry.brushCategoryRailWidthDp(panelWidth)
        assertTrue("panel=$panelWidth rail=$railWidth", railWidth >= 52)
        assertTrue("panel=$panelWidth rail=$railWidth", panelWidth - railWidth >= 72)
    }
}
