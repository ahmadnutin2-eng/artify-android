package com.procreate.android.canvas

import kotlin.math.exp

/** Pure stroke-input math, kept independent of Android so it can be tested at several sample rates. */
object StrokeSmoothing {

    /**
     * Time-correct response of the stabilized point to a raw input sample.
     *
     * A fixed percentage per MotionEvent makes the same brush feel twice as laggy on a 60 Hz
     * screen as it does on a 120 Hz digitizer. Converting strength to a time constant keeps the
     * physical response stable across devices while retaining a deliberately strong 95% setting.
     */
    fun response(strength: Float, elapsedMillis: Long): Float {
        val s = strength.coerceIn(0f, 0.95f)
        if (s <= 0f) return 1f
        val dt = elapsedMillis.coerceIn(1L, 48L).toFloat()
        val timeConstantMs = 4f + 180f * s * s
        return (1f - exp(-dt / timeConstantMs)).coerceIn(0.01f, 1f)
    }

    /**
     * The furthest, in canvas pixels, the stabilized point is allowed to trail the pen.
     *
     * A pure time-constant filter puts no bound on how far behind it can fall. At 0.95 on a 120 Hz
     * digitizer the response is about 5% per sample, so a quick stroke settles roughly twenty
     * samples behind the nib - on the order of two hundred canvas pixels. Everything after the last
     * turn the pen made is then still unpainted when it lifts, and the lift has to cover all of it
     * in one straight run: the stroke visibly ends in a straight tail that ignores the curve just
     * drawn, and the effect gets worse the higher the smoothing is set.
     *
     * Bounding the trail leaves the filter's character intact - jitter is a few pixels wide and is
     * still absorbed - while keeping the catch-up at lift short enough to disappear into the
     * stroke. The allowance grows with the brush, because a wide tip hides a longer correction.
     */
    fun maxTrail(strength: Float, brushSizePx: Float): Float {
        val s = strength.coerceIn(0f, 0.95f)
        if (s <= 0f) return 0f
        return 3f + 40f * s + brushSizePx.coerceAtLeast(0f) * 0.35f
    }
}

/** Edge-following used only by open canvases while a stroke is active. */
object OpenCanvasEdgePan {

    /**
     * Screen-space translation for one axis. Positive moves the sheet toward the far edge when the
     * pen is near the start; negative reveals more world beyond the end edge.
     */
    fun translationDelta(
        position: Float,
        extent: Float,
        activationZone: Float,
        maxSpeedPerSecond: Float,
        elapsedMillis: Long
    ): Float {
        if (extent <= 0f || activationZone <= 0f || maxSpeedPerSecond <= 0f) return 0f
        val zone = activationZone.coerceAtMost(extent * 0.35f)
        val penetration = when {
            position < zone -> (zone - position) / zone
            position > extent - zone -> -((position - (extent - zone)) / zone)
            else -> 0f
        }.coerceIn(-1f, 1f)
        if (penetration == 0f) return 0f
        val eased = penetration * kotlin.math.abs(penetration)
        val seconds = elapsedMillis.coerceIn(1L, 48L) / 1000f
        return eased * maxSpeedPerSecond * seconds
    }
}
