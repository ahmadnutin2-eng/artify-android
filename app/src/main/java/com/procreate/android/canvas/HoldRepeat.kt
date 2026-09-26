package com.procreate.android.canvas

/**
 * Timing for a gesture that repeats while it is held: hold two fingers to keep undoing, three to
 * keep redoing - Procreate's "tap and hold to rapidly step through recent changes".
 *
 * Why this matters more than it sounds: a single tap per undo means stepping back through twenty
 * strokes is twenty separate two-finger taps. That is the single most repeated action in sketching,
 * and doing it one tap at a time is the kind of friction that makes an app feel homemade.
 *
 * The curve is deliberate:
 *  - [INITIAL_DELAY_MS] before the first repeat, so an ordinary tap never turns into a run of
 *    undos. Below roughly a third of a second people undo one step too many and lose work.
 *  - Then repeats that start slow and accelerate to [MIN_INTERVAL_MS], so a short hold steps a few
 *    changes precisely while a long hold travels fast.
 *
 * Pure arithmetic with no Android types, so the curve is tested rather than felt out on a device.
 */
object HoldRepeat {

    /** Time held before the first repeat fires. A plain tap is well under this. */
    const val INITIAL_DELAY_MS = 420L

    /** The first repeat interval, before acceleration. */
    const val START_INTERVAL_MS = 220L

    /** The fastest it ever goes - about 12 steps a second. */
    const val MIN_INTERVAL_MS = 80L

    /** Repeats before the interval reaches [MIN_INTERVAL_MS]. */
    const val RAMP_STEPS = 12

    /**
     * Delay before repeat number [repeatIndex] fires, counting from 0 for the first one.
     *
     * Eases from [START_INTERVAL_MS] down to [MIN_INTERVAL_MS] over [RAMP_STEPS], then holds.
     */
    fun intervalFor(repeatIndex: Int): Long {
        if (repeatIndex <= 0) return START_INTERVAL_MS
        if (repeatIndex >= RAMP_STEPS) return MIN_INTERVAL_MS
        val t = repeatIndex.toFloat() / RAMP_STEPS
        // Quadratic ease-out: most of the speed-up happens early, so a hold feels like it picks up
        // immediately rather than dawdling through a linear ramp.
        val eased = 1f - (1f - t) * (1f - t)
        return (START_INTERVAL_MS - (START_INTERVAL_MS - MIN_INTERVAL_MS) * eased).toLong()
    }

    /** Total time from the finger landing until repeat [repeatIndex] has fired. Used by tests to
     * pin the overall feel, not just the individual gaps. */
    fun elapsedAfter(repeatIndex: Int): Long {
        var total = INITIAL_DELAY_MS
        for (i in 0..repeatIndex) total += intervalFor(i)
        return total
    }
}
