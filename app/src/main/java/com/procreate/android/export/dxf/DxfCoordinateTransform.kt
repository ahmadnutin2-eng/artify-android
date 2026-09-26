package com.procreate.android.export.dxf

/**
 * The one and only place canvas-pixel coordinates become DXF world-space coordinates. Every
 * emission site (arrows, hatches, markers, node numbers, scale bar, legend, table text, the
 * raster IMAGE anchor) must route through this same function - if even one call site
 * reimplements the pixels->meters conversion or the Y-flip inline, that one entity type ends up
 * mirrored or misscaled independently of everything else, which is a far uglier bug to spot than
 * the whole drawing being flipped consistently.
 *
 * Two things happen here, both required:
 *  1. pixels -> meters, via the exact same ratio UrbanScaleConfig already uses on-screen (the
 *     caller passes pixelsPerMeter directly so this file stays free of any android.* import).
 *  2. Y-axis flip: Android's Canvas has Y growing downward from the top-left; DXF/CAD has Y
 *     growing upward. Skipping this produces a drawing that opens in AutoCAD vertically mirrored
 *     - technically valid DXF, but wrong, and easy to miss if never actually opened in a CAD tool.
 *
 * [originOffsetX]/[originOffsetY] (in the same pixel space as [pixelX]/[pixelY]) let the caller
 * re-anchor the whole drawing near world-origin (0,0) instead of sitting at whatever arbitrary
 * canvas-pixel coordinates the user happened to draw at - keeps $EXTMIN/$EXTMAX small and avoids
 * float-precision issues far from the origin.
 */
fun pixelToDxf(
    pixelX: Float,
    pixelY: Float,
    pixelsPerMeter: Float,
    originOffsetXPixels: Float,
    originOffsetYPixels: Float
): DxfPoint {
    require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0f) {
        "pixelsPerMeter must be finite and greater than zero"
    }
    require(pixelX.isFinite() && pixelY.isFinite() &&
        originOffsetXPixels.isFinite() && originOffsetYPixels.isFinite()) {
        "DXF coordinates and origin offsets must be finite"
    }
    val worldX = (pixelX - originOffsetXPixels) / pixelsPerMeter
    val worldY = -(pixelY - originOffsetYPixels) / pixelsPerMeter
    return DxfPoint(worldX.toDouble(), worldY.toDouble())
}

/** Same conversion for a plain distance (no origin offset, no flip - a length has no direction to
 * flip) - used for radii, stroke widths, text heights, hatch spacing, etc. */
fun pixelLengthToDxf(pixelLength: Float, pixelsPerMeter: Float): Double {
    require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0f) {
        "pixelsPerMeter must be finite and greater than zero"
    }
    require(pixelLength.isFinite() && pixelLength >= 0f) {
        "pixelLength must be finite and non-negative"
    }
    return (pixelLength / pixelsPerMeter).toDouble()
}
