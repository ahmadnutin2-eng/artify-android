package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio faults are the ones least likely to be caught by listening: a DC offset is inaudible on a
 * tablet speaker but eats headroom and can thump on headphones; a synth that never truly reaches
 * zero leaves a faint hiss under a silent app; clipping only shows up on the loudest stroke.
 *
 * All of it is arithmetic, and [PencilGrainSynth] imports nothing from android.media, so all of it
 * is testable here rather than by ear.
 */
class PencilGrainSynthTest {

    private val rate = 22050

    private fun rms(buf: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / n).toFloat()
    }

    private fun mean(buf: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i]
        return (sum / n).toFloat()
    }

    @Test
    fun `silent before any stroke`() {
        val synth = PencilGrainSynth(rate)
        val out = FloatArray(1024)
        synth.render(out, out.size, travelledPixels = 0f)
        assertTrue("Output must be silent before drawing", out.all { it == 0f })
    }

    @Test
    fun `a stationary pen makes no sound`() {
        // The pen resting on paper is silent. This is what the old engine needed an 80 ms
        // "has it moved lately" timer to fake; here it falls out of scheduling grains by distance.
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(0.8f)
        synth.setMotion(0f, 0.8f)

        val out = FloatArray(4096)
        synth.render(out, out.size, travelledPixels = 0f)
        assertEquals("A held, unmoving pen must be silent", 0f, rms(out, out.size), 1e-6f)
    }

    @Test
    fun `movement produces sound`() {
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(0.7f)
        synth.setMotion(900f, 0.7f)

        val out = FloatArray(4096)
        synth.render(out, out.size, travelledPixels = 180f)
        assertTrue("Moving the pen must produce sound", rms(out, out.size) > 1e-4f)
    }

    @Test
    fun `faster strokes are denser and louder`() {
        fun energyFor(travel: Float, speed: Float): Float {
            val synth = PencilGrainSynth(rate)
            synth.beginStroke(0.7f)
            synth.setMotion(speed, 0.7f)
            val out = FloatArray(8192)
            synth.render(out, out.size, travelledPixels = travel)
            return rms(out, out.size)
        }

        val slow = energyFor(40f, 200f)
        val fast = energyFor(400f, 2000f)
        assertTrue("A fast stroke should carry more energy than a slow one ($slow vs $fast)", fast > slow)
    }

    @Test
    fun `heavier pressure is louder than light`() {
        fun energyFor(pressure: Float): Float {
            val synth = PencilGrainSynth(rate)
            synth.beginStroke(pressure)
            synth.setMotion(800f, pressure)
            val out = FloatArray(8192)
            synth.render(out, out.size, travelledPixels = 200f)
            return rms(out, out.size)
        }
        assertTrue("Pressing harder should sound heavier", energyFor(1f) > energyFor(0.15f))
    }

    @Test
    fun `output never clips`() {
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(1f)
        synth.setMotion(6000f, 1f)
        val out = FloatArray(16384)
        // Far more travel than a real buffer would carry, to force maximum grain overlap.
        synth.render(out, out.size, travelledPixels = 9000f)
        assertTrue("Samples must stay within -1..1", out.all { it in -1f..1f })
    }

    @Test
    fun `no DC offset`() {
        // A constant offset is inaudible yet steals headroom and thumps when playback starts or
        // stops. The high-pass exists to prevent it; this is the assertion that it works.
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(0.8f)
        synth.setMotion(1500f, 0.8f)
        val out = FloatArray(16384)
        synth.render(out, out.size, travelledPixels = 1200f)
        assertTrue("Mean sample should sit at zero, was ${mean(out, out.size)}", abs(mean(out, out.size)) < 0.01f)
    }

    @Test
    fun `sound stops after the stroke ends`() {
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(0.9f)
        synth.setMotion(1500f, 0.9f)
        val out = FloatArray(4096)
        synth.render(out, out.size, travelledPixels = 400f)

        synth.endStroke()
        // One buffer for the grains still in flight to finish, then it must be fully quiet.
        synth.render(out, out.size, travelledPixels = 0f)
        synth.render(out, out.size, travelledPixels = 0f)
        assertEquals("Lifting the pen must leave silence", 0f, rms(out, out.size), 1e-6f)
    }

    @Test
    fun `lifting the pen emits no transient`() {
        // The old engine fired a bright chirp on every lift. Repeated on every stroke, that is the
        // detail that grates over a long session, so there must not be a burst at the end.
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(0.9f)
        synth.setMotion(1200f, 0.9f)
        val during = FloatArray(2048)
        synth.render(during, during.size, travelledPixels = 220f)
        val energyDuring = rms(during, during.size)

        synth.endStroke()
        val after = FloatArray(2048)
        synth.render(after, after.size, travelledPixels = 0f)
        assertTrue(
            "The tail after lifting must not be louder than the stroke itself",
            rms(after, after.size) <= energyDuring
        )
    }

    @Test
    fun `reset returns the synth to silence`() {
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(1f)
        synth.setMotion(2000f, 1f)
        val out = FloatArray(2048)
        synth.render(out, out.size, travelledPixels = 500f)

        synth.reset()
        synth.render(out, out.size, travelledPixels = 0f)
        assertTrue("reset() must silence everything immediately", out.all { it == 0f })
    }

    @Test
    fun `identical input gives identical output`() {
        // Determinism keeps the sound reproducible between runs, which is what makes any of these
        // energy comparisons meaningful rather than flaky.
        fun run(): FloatArray {
            val synth = PencilGrainSynth(rate, seed = 12345)
            synth.beginStroke(0.6f)
            synth.setMotion(1000f, 0.6f)
            val out = FloatArray(2048)
            synth.render(out, out.size, travelledPixels = 250f)
            return out
        }
        assertTrue("Same seed and input must give the same samples", run().contentEquals(run()))
    }

    @Test
    fun `stays quiet overall`() {
        // This sound accompanies drawing; it is not the subject of it. A hard ceiling here is what
        // stops a future tweak from quietly making it loud again.
        val synth = PencilGrainSynth(rate)
        synth.beginStroke(1f)
        synth.setMotion(3000f, 1f)
        val out = FloatArray(8192)
        synth.render(out, out.size, travelledPixels = 2000f)
        assertTrue("Peak level is too high for a background texture", out.maxOf { abs(it) } < 0.35f)
    }
}
