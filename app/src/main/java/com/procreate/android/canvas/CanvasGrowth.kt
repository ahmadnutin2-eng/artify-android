package com.procreate.android.canvas

import kotlin.math.ceil

/**
 * How much paper an open canvas has to add so that a piece of drawing lands safely inside it.
 * Pure arithmetic, so it is tested rather than trusted.
 *
 * The previous rule added exactly one chunk per side per call. That is fine for a stroke creeping
 * toward an edge, and wrong for the case that matters on a canvas advertised as open: putting the
 * pen down far outside the current paper. One chunk is not enough to reach it, the first samples
 * were painted onto nothing and discarded, and the user experienced it as "I have to draw near the
 * edge first to make it open". The plan below adds as many whole chunks as it takes.
 */
object CanvasGrowth {

    data class Plan(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isNone: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0

        /** The same plan capped at one chunk per side - the fallback when memory is short. */
        fun cappedTo(chunk: Int): Plan = Plan(
            minOf(left, chunk), minOf(top, chunk), minOf(right, chunk), minOf(bottom, chunk)
        )
    }

    /**
     * @param dirty* the bounds, in current canvas coordinates, that must end up inside the paper.
     * @param margin how much clear paper to leave beyond the drawing.
     * @param chunk the growth quantum; every side grows by a whole multiple of it, so a ruled or
     *              dotted background stays in phase across the seam.
     */
    fun plan(
        dirtyLeft: Float,
        dirtyTop: Float,
        dirtyRight: Float,
        dirtyBottom: Float,
        width: Int,
        height: Int,
        margin: Float,
        chunk: Int
    ): Plan {
        require(chunk > 0) { "chunk must be positive" }
        return Plan(
            left = chunksFor(margin - dirtyLeft, chunk),
            top = chunksFor(margin - dirtyTop, chunk),
            right = chunksFor(dirtyRight - (width - margin), chunk),
            bottom = chunksFor(dirtyBottom - (height - margin), chunk)
        )
    }

    /** Whole chunks needed to cover [overshoot] pixels; zero when the paper already reaches. */
    private fun chunksFor(overshoot: Float, chunk: Int): Int =
        if (overshoot <= 0f) 0 else ceil(overshoot / chunk).toInt() * chunk
}
