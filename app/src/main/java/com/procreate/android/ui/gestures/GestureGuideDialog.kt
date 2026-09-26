package com.procreate.android.ui.gestures

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import kotlin.math.abs

/**
 * The animated gesture guide: one gesture per page, demonstrated by [GestureTutorialView].
 *
 * Opened on demand from the Actions panel. Pages advance by button or by swipe; every page loops
 * its animation until the user moves on, so there's no "wait, what did it just do?" moment.
 */
class GestureGuideDialog : DialogFragment() {

    private lateinit var tutorialView: GestureTutorialView
    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var nextButton: TextView
    private lateinit var backButton: TextView

    private val steps = GestureStep.ordered
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_darker))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ---- Header: heading on one side, a way out on the other ----
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(4))
        }
        header.addView(TextView(context).apply {
            text = getString(R.string.gesture_guide_title)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(PanelUi.flatButton(context, getString(R.string.gesture_guide_skip)) { finish() })
        root.addView(header)

        // ---- The animation, given as much room as the screen can spare ----
        tutorialView = GestureTutorialView(context)
        val stage = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(
                tutorialView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        attachSwipePaging(stage)
        root.addView(stage)

        // ---- Caption ----
        titleView = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(4), dp(28), dp(6))
        }
        descriptionView = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 14.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(32), 0, dp(32), dp(14))
        }
        root.addView(titleView)
        root.addView(descriptionView)

        // ---- Progress dots ----
        dotsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        repeat(steps.size) { dotsRow.addView(makeDot(context)) }
        root.addView(dotsRow)

        // ---- Navigation ----
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), dp(24))
        }
        backButton = PanelUi.flatButton(context, getString(R.string.gesture_guide_back)) { go(-1) } as TextView
        backButton.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nextButton = TextView(context).apply {
            textSize = 15.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(ContextCompat.getColor(context, R.color.procreate_accent))
            }
            setPadding(dp(34), dp(13), dp(34), dp(13))
            isClickable = true
            setOnClickListener { go(1) }
        }
        PanelUi.applyPressAnimation(nextButton)
        nav.addView(backButton)
        nav.addView(nextButton)
        root.addView(nav)

        applyStep(animateIn = false)
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun makeDot(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(PanelUi.dp(context, 7), PanelUi.dp(context, 7)).apply {
            marginStart = PanelUi.dp(context, 4)
            marginEnd = PanelUi.dp(context, 4)
        }
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    }

    /** Horizontal swipes page through the guide, the way any onboarding flow is expected to. */
    private fun attachSwipePaging(target: View) {
        var downX = 0f
        var downY = 0f
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val threshold = PanelUi.dp(view.context, 48)
                    if (abs(dx) > threshold && abs(dx) > abs(dy)) {
                        // The guide reads right-to-left in Arabic, but paging follows the physical
                        // direction of the swipe, which is what the hand expects either way.
                        go(if (dx < 0) 1 else -1)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun go(delta: Int) {
        val next = index + delta
        if (next < 0) return
        if (next >= steps.size) {
            finish()
            return
        }
        index = next
        applyStep(animateIn = true)
    }

    private fun applyStep(animateIn: Boolean) {
        val step = steps[index]
        tutorialView.step = step
        titleView.setText(step.titleRes)
        descriptionView.setText(step.descriptionRes)

        val accent = ContextCompat.getColor(requireContext(), R.color.procreate_accent_light)
        val idle = ContextCompat.getColor(requireContext(), R.color.text_disabled)
        for (i in 0 until dotsRow.childCount) {
            val dot = dotsRow.getChildAt(i)
            (dot.background as GradientDrawable).setColor(if (i == index) accent else idle)
            // The current dot is a touch larger as well as brighter, so the position is readable
            // without relying on colour alone.
            dot.scaleX = if (i == index) 1.35f else 1f
            dot.scaleY = dot.scaleX
        }

        backButton.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = getString(
            if (index == steps.lastIndex) R.string.gesture_guide_done else R.string.gesture_guide_next
        )

        if (animateIn) {
            // A short rise-and-fade on the caption marks the page change without stalling anyone
            // who is tapping through quickly.
            for (view in listOf(titleView, descriptionView)) {
                view.alpha = 0f
                view.translationY = PanelUi.dp(requireContext(), 12).toFloat()
                view.animate().alpha(1f).translationY(0f).setDuration(220).start()
            }
            tutorialView.alpha = 0.25f
            tutorialView.animate().alpha(1f).setDuration(260).start()
        }
    }

    private fun finish() {
        markSeen(requireContext())
        dismissAllowingStateLoss()
    }

    companion object {
        private const val PREFS = "procreate_prefs"
        private const val KEY_SEEN = "gesture_guide_seen"

        fun show(fragmentManager: FragmentManager) {
            GestureGuideDialog().show(fragmentManager, "GestureGuideDialog")
        }

        /** True until the guide has been completed or skipped once on this device. */
        fun shouldAutoShow(context: Context): Boolean =
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        fun markSeen(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SEEN, true).apply()
        }
    }
}
