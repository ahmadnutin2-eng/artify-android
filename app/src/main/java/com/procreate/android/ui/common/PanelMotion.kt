package com.procreate.android.ui.common

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * One motion vocabulary for every panel, sheet and dialog in the app.
 *
 * Motion here is not decoration. A panel whose rows arrive in order tells the eye where the list
 * starts; a control that eases rather than snaps reads as a physical object; a selection that
 * animates confirms the tap was received. Done inconsistently it does the opposite - six different
 * timings across six panels is exactly what makes an interface feel assembled rather than designed.
 *
 * Two rules the whole file follows:
 *
 * 1. **Durations are short.** Anything past ~300ms on a control the user taps repeatedly stops
 *    feeling responsive and starts feeling slow, no matter how pretty the curve is.
 * 2. **The system animation setting is obeyed.** A user who has turned animations off - for motion
 *    sensitivity, or on a slower device - gets none, everywhere, via [scale]. Ignoring that setting
 *    is an accessibility failure, and it is the reason every helper here routes through one place.
 */
object PanelMotion {

    /** Material 3 "emphasized decelerate": fast out of the gate, long gentle settle. */
    val emphasized: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** Symmetric standard easing for state changes that are not entrances. */
    val standard: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Slight overshoot, for a control confirming it received a tap. */
    val springy: Interpolator = PathInterpolator(0.2f, 0f, 0.1f, 1.2f)

    const val QUICK = 120L
    const val NORMAL = 220L
    const val SLOW = 320L

    /**
     * The user's animation duration scale, where 0 means "animations off".
     *
     * Cached per call rather than once: the setting can change while the app is running, and a
     * stale value would leave animations on for someone who just turned them off.
     */
    fun scale(context: Context): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private fun enabled(context: Context) = scale(context) > 0f

    /**
     * Fades and lifts a panel's rows into place in sequence.
     *
     * The delay per row is capped and the total is bounded: a 40-row list staggered at 30ms each
     * would take more than a second to finish arriving, and the last rows would feel broken rather
     * than choreographed. Past [maxStaggered] rows everything remaining arrives together.
     */
    fun staggerIn(container: ViewGroup, perRow: Long = 26L, maxStaggered: Int = 10) {
        if (!enabled(container.context)) return
        val rise = container.resources.displayMetrics.density * 10f
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = rise
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(perRow * minOf(i, maxStaggered))
                .setDuration(NORMAL)
                .setInterpolator(emphasized)
                .start()
        }
    }

    /**
     * Press feedback for a tappable control.
     *
     * Replaces a 0.88 scale over 90ms with a shallower, slower one: at 12% the control visibly
     * jumps away from the finger, and on a row that fills the panel width that reads as the whole
     * list flinching. 4% is felt more than seen, which is what press feedback should be.
     *
     * Returns the touch event unhandled, so it composes with a click listener rather than
     * replacing it.
     */
    fun press(view: View, depth: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            if (enabled(v.context)) {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(depth).scaleY(depth)
                            .setDuration(QUICK).setInterpolator(standard).start()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(NORMAL).setInterpolator(springy).start()
                }
            }
            false
        }
    }

    /**
     * A brief pulse acknowledging that a control did something with no visible result of its own -
     * flipping the canvas, say, where the change happens elsewhere on screen.
     */
    fun pulse(view: View) {
        if (!enabled(view.context)) return
        view.animate().cancel()
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(NORMAL).setInterpolator(springy).start()
    }

    /**
     * Reveals a view that was hidden, or hides it, without the layout jumping.
     *
     * Used for conditional rows - the custom-size fields in the new-canvas dialog, for instance -
     * where flipping visibility outright makes the whole panel snap to a new height.
     */
    fun setVisible(view: View, visible: Boolean) {
        if (!enabled(view.context)) {
            view.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            if (view.visibility == View.VISIBLE && view.alpha == 1f) return
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(NORMAL).setInterpolator(emphasized).start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate().alpha(0f).setDuration(QUICK).setInterpolator(standard)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Crossfades whatever [block] changes about [view], so a thumbnail or a value swapping out
     * does not blink.
     */
    fun crossfade(view: View, block: () -> Unit) {
        if (!enabled(view.context)) {
            block()
            return
        }
        view.animate().alpha(0.35f).setDuration(QUICK).setInterpolator(standard)
            .withEndAction {
                block()
                view.animate().alpha(1f).setDuration(NORMAL).setInterpolator(emphasized).start()
            }
            .start()
    }
}
