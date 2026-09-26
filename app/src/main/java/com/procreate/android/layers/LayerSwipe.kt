package com.procreate.android.layers

import kotlin.math.abs

/**
 * What a horizontal swipe across a layer row means.
 *
 * Procreate puts its most repeated layer actions on swipes rather than in menus, because opening a
 * panel to toggle Alpha Lock forty times an hour is the kind of friction that adds up. This is the
 * same idea adapted to one finger, since this app is touch-first rather than pencil-first.
 *
 * Direction is expressed in *layout* terms, not screen terms: the panel is Arabic and therefore
 * right-to-left, so "towards the end of the row" is leftward on screen here and rightward in an
 * English build. Deciding that here, once, keeps it out of the touch handler.
 */
object LayerSwipe {

    enum class Action {
        /** Toggle Alpha Lock - the most repeated layer toggle there is. */
        ALPHA_LOCK,

        /** Reveal delete and duplicate. */
        QUICK_ACTIONS,

        NONE
    }

    /** How far a swipe must travel, in dp, before it counts as one rather than as a tap. */
    const val THRESHOLD_DP = 56f

    /** Past this, the row is committed - used to snap the row open instead of springing it back. */
    const val COMMIT_DP = 96f

    /**
     * @param dx horizontal travel in pixels, positive meaning rightward on screen.
     * @param dy vertical travel, used only to reject what is really a scroll.
     * @param density pixels per dp.
     * @param isRtl whether the panel is laid out right-to-left.
     */
    fun classify(dx: Float, dy: Float, density: Float, isRtl: Boolean): Action {
        val threshold = THRESHOLD_DP * density
        if (abs(dx) < threshold) return Action.NONE
        // A swipe that is mostly vertical is the list being scrolled, whatever its horizontal drift.
        if (abs(dy) > abs(dx) * 0.7f) return Action.NONE

        // Towards the start of the row (right in RTL, left in LTR) is Alpha Lock; the other way
        // reveals the destructive actions, which are better guarded behind a second tap.
        val towardsStart = if (isRtl) dx > 0 else dx < 0
        return if (towardsStart) Action.ALPHA_LOCK else Action.QUICK_ACTIONS
    }

    /**
     * How far the row should visibly follow the finger, in pixels.
     *
     * Beyond [COMMIT_DP] the row resists, so the gesture communicates its own limit rather than
     * letting the row slide off the panel.
     */
    fun followOffset(dx: Float, density: Float): Float {
        val limit = COMMIT_DP * density
        if (abs(dx) <= limit) return dx
        val overshoot = abs(dx) - limit
        // Rubber band: each further pixel of travel moves the row by less than one.
        val damped = limit + overshoot * 0.25f
        return if (dx < 0) -damped else damped
    }
}
