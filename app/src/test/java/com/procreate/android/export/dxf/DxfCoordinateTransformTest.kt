package com.procreate.android.export.dxf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Plain-JVM tests (no Android/Robolectric needed) for the one function every DXF emission site
 * must route through - a bug here would silently mirror or misscale every entity in an export. */
class DxfCoordinateTransformTest {

    @Test
    fun `pixels convert to meters using the given ratio`() {
        val p = pixelToDxf(pixelX = 50f, pixelY = 0f, pixelsPerMeter = 5f, originOffsetXPixels = 0f, originOffsetYPixels = 0f)
        assertEquals(10.0, p.x, 1e-6)
    }

    @Test
    fun `Y axis is flipped - Android Y-down becomes DXF Y-up`() {
        val p = pixelToDxf(pixelX = 0f, pixelY = 20f, pixelsPerMeter = 5f, originOffsetXPixels = 0f, originOffsetYPixels = 0f)
        // 20px / 5ppm = 4m, and it must come back negative (moving DOWN the screen must move
        // DOWN in DXF space too when Y-up is the convention, i.e. a smaller/negative Y).
        assertEquals(-4.0, p.y, 1e-6)
    }

    @Test
    fun `origin offset shifts the drawing without changing its scale`() {
        val p = pixelToDxf(pixelX = 100f, pixelY = 100f, pixelsPerMeter = 10f, originOffsetXPixels = 50f, originOffsetYPixels = 50f)
        assertEquals(5.0, p.x, 1e-6)
        assertEquals(-5.0, p.y, 1e-6)
    }

    @Test
    fun `a tiny positive regional scale remains valid and is never replaced silently`() {
        val p = pixelToDxf(pixelX = 100f, pixelY = 0f, pixelsPerMeter = 0.001f, originOffsetXPixels = 0f, originOffsetYPixels = 0f)
        // 0.001f isn't exactly 0.001 in binary32, so the quotient lands ~0.005 below 100_000 no
        // matter how the division is widened - the delta covers that representation error while
        // staying far tighter than any silently-substituted default scale would produce.
        assertEquals(100_000.0, p.x, 1e-1)
    }

    @Test
    fun `zero negative and non-finite scales are rejected`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                pixelToDxf(10f, 0f, invalid, 0f, 0f)
            }
            assertThrows(IllegalArgumentException::class.java) {
                pixelLengthToDxf(10f, invalid)
            }
        }
    }

    @Test
    fun `plain lengths convert with no offset and no flip`() {
        assertEquals(4.0, pixelLengthToDxf(20f, 5f), 1e-6)
    }
}
