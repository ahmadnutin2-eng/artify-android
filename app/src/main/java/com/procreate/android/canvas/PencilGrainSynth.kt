package com.procreate.android.canvas

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * ملفات الصوت لأنماط أدوات الرسم المختلفة (Sound Profiles).
 */
enum class DrawingSoundProfile {
    AUTO,          // يختار خامة الصوت تلقائيًا بحسب نوع الفرشاة والأداة الحالية
    VELVET_PENCIL, // قلم رصاص مخملي ناعم يحاكي احتكاك الورق القطني الفاخر
    SMOOTH_MARKER, // قلم تحبير وسكتش فائق النعومة والانسيابية
    SOFT_BRUSH     // ريشة فنية هوائية رقيقة
}

/**
 * Granular synthesis of pencil-on-paper, as a pure DSP core with no `android.media` import.
 *
 * Upgraded with:
 * 1. Attack-ramped grain envelopes: Grains start strictly at zero and ramp up smoothly,
 *    eliminating all micro-clicks, crackles, and mechanical buzzing.
 * 2. Two-pole gentle Butterworth-style low-pass filter: removes high-frequency static hiss
 *    and produces a soothing, warm, velvety ASMR paper friction sound.
 * 3. Sound profile customization and master volume scaling.
 */
class PencilGrainSynth(private val sampleRate: Int, seed: Int = 0x51EED) {

    companion object {
        /** Plenty for the densest fast stroke; past this the ear cannot separate them anyway. */
        const val MAX_GRAINS = 28

        /** Canvas pixels between grains at the reference speed. Smaller = denser texture. */
        const val PIXELS_PER_GRAIN = 5.2f

        /** Grain length bounds in milliseconds. Short enough to read as an impact, not a tone. */
        const val GRAIN_MS_MIN = 2.4f
        const val GRAIN_MS_MAX = 8.8f

        /** Ceiling on the mixed output before the final gain. Kept low on purpose: this sound sits
         * under the drawing, it is not the subject of it. */
        const val PEAK = 0.16f
    }

    private val grainPos = IntArray(MAX_GRAINS)
    private val grainLen = IntArray(MAX_GRAINS)
    private val grainAttackLen = IntArray(MAX_GRAINS)
    private val grainPeakAmp = FloatArray(MAX_GRAINS)
    private val grainAmp = FloatArray(MAX_GRAINS)
    private val grainDecay = FloatArray(MAX_GRAINS)
    /** Read cursor into [source] for each grain, or -1 for a grain made of noise. */
    private val grainSrcPos = IntArray(MAX_GRAINS)
    private var grainCount = 0

    /**
     * Recorded pencil-on-paper PCM to cut grains from, normalised to -1..1. Null falls back to
     * white-noise grains.
     *
     * A grain of real material carries graphite and paper fibre in its timbre; noise only ever
     * sounds like hiss, however carefully it is shaped. The scheduling above is unchanged either
     * way, so everything that made the sound behave - silence when still, density following speed -
     * still holds.
     */
    @Volatile var source: FloatArray? = null

    /** Picks a fresh window of [source] per grain, so consecutive grains are not the same snippet. */
    private fun sourceStartFor(length: Int): Int {
        val src = source ?: return -1
        if (src.size <= length + 1) return -1
        return (nextUnit() * (src.size - length - 1)).toInt()
    }

    private var rng = seed
    private var distanceSinceGrain = 0f

    @Volatile private var speed = 0f
    @Volatile private var pressure = 0f
    @Volatile private var active = false

    /** Master volume multiplier (0.0 to 1.0), defaulting to a gentle soothing level. */
    var volume: Float = 0.85f

    /** Current sound profile. */
    var profile: DrawingSoundProfile = DrawingSoundProfile.AUTO

    /** Material selected by the current brush while [profile] remains AUTO. */
    var automaticProfile: DrawingSoundProfile = DrawingSoundProfile.VELVET_PENCIL

    /** Dual-pole filters shaping the grain shower into warm paper rather than harsh fizz. */
    private var lowPass1 = 0f
    private var lowPass2 = 0f
    private var highPassState = 0f
    private var highPassPrev = 0f

    /** Sub-sample carry, so a slow stroke does not lose the fraction of a grain it earned. */
    private var pendingDistance = 0f

    private fun nextFloat(): Float {
        rng = rng xor (rng shl 13)
        rng = rng xor (rng ushr 17)
        rng = rng xor (rng shl 5)
        return ((rng ushr 8) and 0xFFFF) / 32768f - 1f
    }

    private fun nextUnit(): Float = (nextFloat() + 1f) * 0.5f

    /** @param speedPxPerSec how fast the tip is moving across the canvas. */
    fun setMotion(speedPxPerSec: Float, pressureValue: Float) {
        speed = speedPxPerSec.coerceIn(0f, 6000f)
        pressure = pressureValue.coerceIn(0f, 1f)
        active = true
    }

    fun beginStroke(pressureValue: Float) {
        pressure = pressureValue.coerceIn(0f, 1f)
        speed = 0f
        active = true
    }

    fun endStroke() {
        active = false
        speed = 0f
        distanceSinceGrain = 0f
        pendingDistance = 0f
    }

    fun reset() {
        endStroke()
        grainCount = 0
        lowPass1 = 0f
        lowPass2 = 0f
        highPassState = 0f
        highPassPrev = 0f
    }

    private fun spawnGrain() {
        val slot = if (grainCount < MAX_GRAINS) grainCount++ else {
            var oldest = 0
            var most = -1f
            for (i in 0 until grainCount) {
                val progress = grainPos[i] / grainLen[i].toFloat()
                if (progress > most) { most = progress; oldest = i }
            }
            oldest
        }

        val lengthMs = GRAIN_MS_MIN + nextUnit() * (GRAIN_MS_MAX - GRAIN_MS_MIN)
        val totalLen = (lengthMs * sampleRate / 1000f).toInt().coerceAtLeast(4)
        grainLen[slot] = totalLen
        grainPos[slot] = 0

        // Attack ramp: smooth linear or raised onset to prevent any step discontinuity / click
        val attack = (totalLen * 0.20f).toInt().coerceIn(1, 14)
        grainAttackLen[slot] = attack

        val heaviness = 0.45f + pressure * 0.55f
        val peak = (0.35f + nextUnit() * 0.65f) * heaviness
        grainPeakAmp[slot] = peak
        grainAmp[slot] = 0f // Starts strictly at zero!

        // Exponential decay reaching about -40 dB over the post-attack duration
        val decayLen = max(1, totalLen - attack)
        grainDecay[slot] = exp(-4.6f / decayLen)

        grainSrcPos[slot] = sourceStartFor(totalLen)
    }

    /**
     * Renders [count] samples into [out] as floats in roughly -1..1.
     *
     * @param travelledPixels canvas distance covered by this buffer, used to schedule grains.
     */
    fun render(out: FloatArray, count: Int, travelledPixels: Float) {
        val perSample = if (count > 0) travelledPixels / count else 0f

        // Filter coefficients tuned for velvety warmth
        val effectiveProfile = if (profile == DrawingSoundProfile.AUTO) automaticProfile else profile
        val (lpFactor1, lpFactor2) = when (effectiveProfile) {
            DrawingSoundProfile.AUTO          -> 0.26f to 0.28f // automaticProfile never stays AUTO
            DrawingSoundProfile.VELVET_PENCIL -> 0.26f to 0.28f
            DrawingSoundProfile.SMOOTH_MARKER -> 0.18f to 0.22f
            DrawingSoundProfile.SOFT_BRUSH     -> 0.14f to 0.18f
        }

        for (i in 0 until count) {
            if (active) {
                pendingDistance += perSample
                val spacing = PIXELS_PER_GRAIN * (1f + speed / 4200f)
                while (pendingDistance >= spacing) {
                    pendingDistance -= spacing
                    spawnGrain()
                }
            }

            var sum = 0f
            var w = 0
            while (w < grainCount) {
                val pos = grainPos[w]
                val len = grainLen[w]
                if (pos >= len) {
                    grainCount--
                    grainPos[w] = grainPos[grainCount]
                    grainLen[w] = grainLen[grainCount]
                    grainAttackLen[w] = grainAttackLen[grainCount]
                    grainPeakAmp[w] = grainPeakAmp[grainCount]
                    grainAmp[w] = grainAmp[grainCount]
                    grainDecay[w] = grainDecay[grainCount]
                    grainSrcPos[w] = grainSrcPos[grainCount]
                    continue
                }

                val attack = grainAttackLen[w]
                val currentAmp: Float
                if (pos < attack) {
                    // Smooth attack ramp from 0 up to peakAmp
                    currentAmp = (pos.toFloat() / attack) * grainPeakAmp[w]
                    grainAmp[w] = currentAmp
                } else {
                    // Smooth exponential decay
                    currentAmp = grainAmp[w] * grainDecay[w]
                    grainAmp[w] = currentAmp
                }

                // Recorded material when it is loaded, noise otherwise. The envelope, scheduling and
                // filtering are identical for both, so the fallback differs only in timbre.
                val src = source
                val srcStart = grainSrcPos[w]
                val material = if (src != null && srcStart >= 0 && srcStart + pos < src.size) {
                    src[srcStart + pos]
                } else {
                    nextFloat()
                }
                sum += material * currentAmp
                grainPos[w] = pos + 1
                w++
            }

            // Dual-pole lowpass filter: eliminates harsh buzz and leaves warm velvety paper glide
            lowPass1 += (sum - lowPass1) * lpFactor1
            lowPass2 += (lowPass1 - lowPass2) * lpFactor2

            // High-pass filter: eliminates DC offset and deep muddy rumble
            val hp = 0.985f * (highPassState + lowPass2 - highPassPrev)
            highPassPrev = lowPass2
            highPassState = hp

            val outputGain = PEAK * volume.coerceIn(0f, 1.5f)
            out[i] = (hp * outputGain).coerceIn(-1f, 1f)
        }
    }

    /** True while any grain is still sounding - lets the host skip work when fully silent. */
    fun isSounding(): Boolean = grainCount > 0 || (active && abs(highPassState) > 1e-4f)
}
