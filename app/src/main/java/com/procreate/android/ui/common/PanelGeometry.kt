package com.procreate.android.ui.common

/**
 * Where a docked side panel goes, in dp. Pure arithmetic so it can be unit-tested for every screen
 * size, and one source of truth: the brush panel used to size itself a second time after
 * [PanelUi.dockSheet] had clamped it, and on a 360dp phone its x came out negative.
 */
object PanelGeometry {
    /** No interactive element closer than this to a screen edge (design rule). */
    const val EDGE_DP = 16
    const val TOOL_RAIL_GUTTER_DP = 64
    const val MIN_VISIBLE_CANVAS_FRACTION = 0.40f

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
        val maximumOccupiedWidth = (displayWidthDp * (1f - MIN_VISIBLE_CANVAS_FRACTION)).toInt()
        val maximumGutter = (maximumOccupiedWidth - 1).coerceAtLeast(EDGE_DP)
        val gutter = gutterDp.coerceIn(EDGE_DP, maximumGutter)
        val canvasCap = (maximumOccupiedWidth - gutter).coerceAtLeast(1)
        val width = minOf(
            safeWidthDp(requestedWidthDp, displayWidthDp, displayHeightDp),
            canvasCap
        )
        val x = if (dockRight) displayWidthDp - width - gutter else gutter
        return Placement(width, x.coerceAtLeast(EDGE_DP))
    }

    /** The one production formula used by [BrushPanel] and its geometry tests. */
    fun brushPanelPlacement(
        displayWidthDp: Int,
        displayHeightDp: Int,
        dockRight: Boolean
    ): Placement = floatingPlacement(
        requestedWidthDp = (displayWidthDp * 0.355f).toInt().coerceIn(330, 620),
        gutterDp = (displayWidthDp * 0.055f).toInt().coerceIn(TOOL_RAIL_GUTTER_DP, 94),
        displayWidthDp = displayWidthDp,
        displayHeightDp = displayHeightDp,
        dockRight = dockRight
    )

    /** Keeps a usable preview column even when split-screen forces the whole palette very narrow. */
    fun brushCategoryRailWidthDp(panelWidthDp: Int): Int {
        val maximumRailWidth = (panelWidthDp - 72).coerceAtLeast(52)
        return (panelWidthDp * 0.37f).toInt().coerceIn(52, maximumRailWidth)
    }
}
