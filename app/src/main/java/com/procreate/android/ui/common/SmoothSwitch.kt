package com.procreate.android.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import androidx.appcompat.widget.SwitchCompat
import com.procreate.android.R

/**
 * The app-wide on/off control.
 *
 * Programmatic panels previously mixed the platform [android.widget.Switch] and two different
 * Material switch implementations. Besides looking different from screen to screen, the platform
 * widget can show text inside the track and has very weak state feedback in the dark theme. This
 * control keeps the compact thumb-on-track interaction users expect, lets Material animate the
 * thumb between states, and adds one quiet haptic tick for a deliberate tap.
 */
class SmoothSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.switchStyle
) : SwitchCompat(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    init {
        showText = false
        // The visual control is the compact 46 x 28 iOS proportion. The view keeps a 44dp-high
        // hit target, so it stays easy to touch even though the switch itself is deliberately small.
        minimumWidth = dp(46)
        minimumHeight = dp(48)
        switchMinWidth = dp(46)
        switchPadding = dp(10)
        thumbTextPadding = 0
        gravity = Gravity.CENTER_VERTICAL
        splitTrack = false
        trackDrawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            context, R.drawable.switch_track_ios
        )
        thumbDrawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            context, R.drawable.switch_thumb_ios
        )
        // Suppress the theme tint so the state-list drawables keep iOS green/off-grey exactly.
        trackTintList = null
        thumbTintList = null
    }

    override fun performClick(): Boolean {
        val canToggle = isEnabled
        val handled = super.performClick()
        if (canToggle) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animate().cancel()
            scaleX = 0.96f
            scaleY = 0.96f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(PanelMotion.springy)
                .start()
        }
        return handled
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
