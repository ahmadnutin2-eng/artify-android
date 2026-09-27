package com.procreate.android.ui.common

/**
 * Where a docked side panel goes, in dp. Pure arithmetic so it can be unit-tested for every screen
 * size, and one source of truth: the brush panel used to size itself a second time after
 * [PanelUi.dockSheet] had clamped it, and on a 360dp phone its x came out negative.
 */
object PanelGeometry {
    /** No interactive element closer than this to a screen edge (design rule). */
    const val EDGE_DP = 16

    data class Placement(val widthDp: Int, val xDp: Int)

    /**
     * The width a side panel may take: [requestedDp], capped so a short (landscape phone) screen
     * keeps about half its canvas visible, and always leaving [EDGE_DP] on both sides.
     */
    fun safeWidthDp(requestedDp: Int, displayWidthDp: Int, displayHeightDp: Int): Int {
        val phoneCap = if (displayHeightDp < 520) {
            (displayWidthDp * 0.52f).toInt().coerceAtLeast(320)
        } else requestedDp
        val edgeCap = (displayWidthDp - 2 * EDGE_DP).coerceAtLeast(1)
        return minOf(requestedDp, phoneCap, edgeCap)
    }

    /**
     * A floating panel docked to one side, [gutterDp] from that side where there is room (the
     * gutter keeps the tool rail visible), shrinking the gutter before the panel, and never
     * closer than [EDGE_DP] to either edge.
     */
    fun floatingPlacement(
        requestedWidthDp: Int,
        gutterDp: Int,
        displayWidthDp: Int,
        displayHeightDp: Int,
        dockRight: Boolean
    ): Placement {
        val width = safeWidthDp(requestedWidthDp, displayWidthDp, displayHeightDp)
        val maxGutter = (displayWidthDp - width - EDGE_DP).coerceAtLeast(EDGE_DP)
        val gutter = gutterDp.coerceIn(EDGE_DP, maxGutter)
        val x = if (dockRight) displayWidthDp - width - gutter else gutter
        return Placement(width, x.coerceAtLeast(EDGE_DP))
    }
}
