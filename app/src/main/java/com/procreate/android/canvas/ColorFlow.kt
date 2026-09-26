package com.procreate.android.canvas

/**
 * Colour that moves while the stroke is being made.
 *
 * A real loaded brush never lays one colour. Pigment separates, the wash carries more of one
 * component than another, and a single pull across the paper travels from rose into magenta into
 * violet without the painter changing anything. Until now a stroke here was one flat colour from
 * end to end, which is why every wash came out looking printed rather than painted.
 *
 * Two separate things produce that:
 *
 *  - **Drift** moves the hue steadily along the stroke, so a long pull arrives somewhere different
 *    from where it started. This is the large gesture the eye reads as a gradient.
 *  - **Jitter** scatters each stamp a little around wherever the drift currently is. This is the
 *    granularity of pigment, and on its own it only ever looks like noise - it needs the drift
 *    underneath it to read as paint.
 *
 * Kept free of Android's drawing classes so both can be tested directly.
 */
object ColorFlow {

    /**
     * How far the hue has travelled after [distancePx] of stroke.
     *
     * Expressed per thousand canvas pixels rather than per stamp, so the same setting produces the
     * same gradient whether the brush is spaced tightly or loosely, and whether the canvas is small
     * or enormous. A setting tied to stamp count would drift wildly between two brushes that look
     * identical on paper.
     */
    fun hueDrift(distancePx: Float, degreesPerThousandPx: Float): Float {
        if (degreesPerThousandPx == 0f || !distancePx.isFinite()) return 0f
        return distancePx / 1000f * degreesPerThousandPx
    }

    /**
     * [baseColor] moved by [hueDegrees] around the wheel and scattered by the jitter amounts.
     *
     * [random] is supplied rather than drawn inside so a stamp's scatter is reproducible in tests;
     * the engine passes its own generator.
     *
     * Saturation and brightness are scattered multiplicatively and clamped, because a wash that
     * jitters into pure white or flat grey stops looking like the same pigment.
     */
    fun shift(
        baseColor: Int,
        hueDegrees: Float,
        hueJitter: Float,
        saturationJitter: Float,
        brightnessJitter: Float,
        random: () -> Float
    ): Int {
        if (hueDegrees == 0f && hueJitter == 0f && saturationJitter == 0f && brightnessJitter == 0f) {
            return baseColor
        }
        val alpha = (baseColor ushr 24) and 0xFF
        val hsv = toHsv(baseColor)

        val scatter = { amount: Float -> if (amount == 0f) 0f else (random() - 0.5f) * 2f * amount }

        val hue = wrapDegrees(hsv[0] + hueDegrees + scatter(hueJitter) * 180f)
        // Floors high enough to survive eight-bit quantisation, not merely to be non-zero. At a
        // value of 0.06 a colour is fifteen of two hundred and fifty-five, and a few percent of
        // saturation on top of that is smaller than one representable step - so the stamp lands as
        // flat near-black and the scatter has turned pigment into dirt. These are the levels at
        // which a scattered stamp still reads as the colour the artist chose.
        val saturation = (hsv[1] * (1f + scatter(saturationJitter))).coerceIn(0.08f, 1f)
        val value = (hsv[2] * (1f + scatter(brightnessJitter))).coerceIn(0.15f, 1f)

        return fromHsv(alpha, hue, saturation, value)
    }

    fun wrapDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    /**
     * Hue, saturation and value of a packed colour.
     *
     * Written out rather than borrowed from android.graphics.Color so the whole of this file stays
     * testable on a plain JVM, like the rest of the engine's arithmetic. Pulling in an Android
     * emulation layer to test twenty lines of colour conversion would be the wrong trade.
     */
    fun toHsv(color: Int): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val hue = when {
            delta < 1e-6f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val saturation = if (max <= 0f) 0f else delta / max
        return floatArrayOf(wrapDegrees(hue), saturation, max)
    }

    fun fromHsv(alpha: Int, hue: Float, saturation: Float, value: Float): Int {
        val h = wrapDegrees(hue) / 60f
        val c = value * saturation
        val x = c * (1f - kotlin.math.abs((h % 2f) - 1f))
        val m = value - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(component: Float) = ((component + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha and 0xFF shl 24) or
            (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
