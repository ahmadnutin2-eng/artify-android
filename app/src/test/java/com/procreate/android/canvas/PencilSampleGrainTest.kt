package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The recorded-sample grain path. It is a fallback-shaped feature - real material when the decode
 * lands, noise until then - and both halves have to behave identically in every way except timbre.
 */
class PencilSampleGrainTest {

    private val rate = 22050

    /** Stand-in for decoded pencil material: a tone is easy to recognise in the output. */
    private fun fakeSource(n: Int = 20000): FloatArray =
        FloatArray(n) { sin(it * 0.05).toFloat() * 0.8f }

    private fun rms(buf: FloatArray, n: Int): Float {
        var s = 0.0
        for (i in 0 until n) s += buf[i].toDouble() * buf[i]
        return sqrt(s / n).toFloat()
    }

    private fun mean(buf: FloatArray, n: Int): Float {
        var s = 0.0
        for (i in 0 until n) s += buf[i]
        return (s / n).toFloat()
    }

    private fun render(source: FloatArray?, travel: Float = 300f, n: Int = 8192): FloatArray {
        val synth = PencilGrainSynth(rate, seed = 777)
        synth.source = source
        synth.beginStroke(0.7f)
        synth.setMotion(1200f, 0.7f)
        val out = FloatArray(n)
        synth.render(out, n, travel)
        return out
    }

    @Test
    fun `the recording changes the sound`() {
        // If both paths produced the same samples, the sample would not actually be in use.
        val noise = render(null)
        val recorded = render(fakeSource())
        var differs = false
        for (i in noise.indices) if (abs(noise[i] - recorded[i]) > 1e-4f) { differs = true; break }
        assertTrue("Sample grains must not be identical to noise grains", differs)
    }

    @Test
    fun `a stationary pen is silent with the recording loaded too`() {
        // The rule that matters most, and the one a looping sample player would break: no movement,
        // no sound. Grains are scheduled by distance, so this must hold for either material.
        val synth = PencilGrainSynth(rate)
        synth.source = fakeSource()
        synth.beginStroke(0.8f)
        synth.setMotion(0f, 0.8f)
        val out = FloatArray(4096)
        synth.render(out, out.size, travelledPixels = 0f)
        assertEquals(0f, rms(out, out.size), 1e-6f)
    }

    @Test
    fun `sample grains never clip`() {
        val synth = PencilGrainSynth(rate)
        synth.source = FloatArray(20000) { if (it % 2 == 0) 1f else -1f } // worst case material
        synth.beginStroke(1f)
        synth.setMotion(6000f, 1f)
        val out = FloatArray(16384)
        synth.render(out, out.size, travelledPixels = 9000f)
        assertTrue("Samples must stay within -1..1", out.all { it in -1f..1f })
    }

    @Test
    fun `sample grains carry no DC offset`() {
        // A recording can have its own offset; the high-pass has to remove it just as it does for
        // noise, or playback would thump on headphones.
        val synth = PencilGrainSynth(rate)
        synth.source = FloatArray(20000) { 0.5f } // constant: pure DC
        synth.beginStroke(0.8f)
        synth.setMotion(1500f, 0.8f)
        val out = FloatArray(16384)
        synth.render(out, out.size, travelledPixels = 1200f)
        assertTrue("Mean must sit at zero, was ${mean(out, out.size)}", abs(mean(out, out.size)) < 0.01f)
    }

    @Test
    fun `an empty or tiny recording falls back to noise instead of going silent`() {
        // A decode that yields almost nothing must not leave the app mute.
        for (src in listOf(FloatArray(0), FloatArray(4))) {
            val out = render(src)
            assertTrue("A degenerate sample must fall back to noise", rms(out, out.size) > 1e-4f)
        }
    }

    @Test
    fun `the recording can be attached mid-stroke without a discontinuity`() {
        // The audio thread swaps the source in between buffers while a stroke may be in progress.
        // Grains already in flight keep their own material; the change must not produce a jump.
        val synth = PencilGrainSynth(rate, seed = 99)
        synth.beginStroke(0.7f)
        synth.setMotion(1200f, 0.7f)
        val first = FloatArray(2048)
        synth.render(first, first.size, 120f)

        synth.source = fakeSource()
        val second = FloatArray(2048)
        synth.render(second, second.size, 120f)

        val seam = abs(second[0] - first[first.size - 1])
        assertTrue("Swapping material mid-stroke must not step the signal ($seam)", seam < 0.25f)
        assertTrue(second.all { it in -1f..1f })
    }

    @Test
    fun `speed still drives density with the recording loaded`() {
        val slow = render(fakeSource(), travel = 40f)
        val fast = render(fakeSource(), travel = 400f)
        assertTrue("Faster strokes must still carry more energy", rms(fast, fast.size) > rms(slow, slow.size))
    }
}
