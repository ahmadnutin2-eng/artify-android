package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusResponseTest {

    private val halfPi = (Math.PI / 2).toFloat()

    @Test
    fun `an upright pen is not tilted at all`() {
        assertEquals(0f, StylusResponse.tiltFactor(0f), 0f)
    }

    @Test
    fun `tilt reaches full effect within the range a hand can actually reach`() {
        // Sixty degrees is about as far over as anyone draws, so that is where the response tops out.
        assertEquals(1f, StylusResponse.tiltFactor((Math.PI / 3).toFloat()), 0.001f)
        assertEquals(1f, StylusResponse.tiltFactor(halfPi), 0f)
        assertTrue(StylusResponse.tiltFactor((Math.PI / 6).toFloat()) < 0.6f)
    }

    @Test
    fun `nonsense tilt values cannot move the brush`() {
        assertEquals(0f, StylusResponse.tiltFactor(Float.NaN), 0f)
        assertEquals(0f, StylusResponse.tiltFactor(-1f), 0f)
    }

    @Test
    fun `without tracking the nib keeps the brush's own cut`() {
        assertEquals(70f, StylusResponse.nibRotationDegrees(1.2f, 0f, 70f), 0f)
    }

    @Test
    fun `a fully tracking nib agrees with stroke direction when the pen points that way`() {
        // Azimuth is clockwise from up; a barrel pointing right is the engine's zero direction.
        assertEquals(0f, StylusResponse.nibRotationDegrees(halfPi, 1f, 0f), 0.001f)
        // Pointing straight up is a quarter turn back from that.
        assertEquals(-90f, StylusResponse.nibRotationDegrees(0f, 1f, 0f), 0.001f)
    }

    @Test
    fun `the fixed cut offsets whatever the barrel contributes`() {
        assertEquals(70f, StylusResponse.nibRotationDegrees(halfPi, 1f, 70f), 0.001f)
    }

    @Test
    fun `partial tracking lands between the fixed cut and the hand`() {
        val full = StylusResponse.nibRotationDegrees(0f, 1f, 0f)
        val half = StylusResponse.nibRotationDegrees(0f, 0.5f, 0f)
        assertEquals(full / 2f, half, 0.001f)
    }

    @Test
    fun `interpolating barrel direction takes the short way round the wrap`() {
        val pi = Math.PI.toFloat()
        // Just under +pi to just over -pi is a small turn of the wrist, not a half rotation.
        val delta = StylusResponse.angleDeltaRadians(pi - 0.1f, -pi + 0.1f)
        assertEquals(0.2f, delta, 0.001f)
        assertTrue(kotlin.math.abs(delta) < 1f)
    }

    @Test
    fun `angle delta is symmetric`() {
        val forward = StylusResponse.angleDeltaRadians(0.3f, 1.1f)
        val back = StylusResponse.angleDeltaRadians(1.1f, 0.3f)
        assertEquals(forward, -back, 0.0001f)
    }
}
