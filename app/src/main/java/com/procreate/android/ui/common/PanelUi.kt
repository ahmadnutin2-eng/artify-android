package com.procreate.android.ui.common

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.procreate.android.R

/**
 * Shared building blocks for the bottom-sheet panels (Actions, Adjustments, Layers, Colors,
 * Brushes, Brush Studio). Every panel used to hand-roll its own plain LinearLayout/Button/SeekBar
 * UI, which meant five slightly different looks and stock widget chrome (a light-grey default
 * SeekBar and a plain black Button look out of place against the app's dark theme). Centralizing
 * the row/slider/header patterns here keeps every panel visually consistent and avoids repeating
 * (and re-breaking) the same layout code five times.
 */
object PanelUi {

    enum class DockSide { LEFT, RIGHT }

    private const val ARG_DOCK_SIDE = "panel_dock_side"

    private const val ARG_ANCHOR_X = "panel_anchor_x"
    private const val ARG_ANCHOR_BOTTOM = "panel_anchor_bottom"

    fun dockArguments(side: DockSide): Bundle = Bundle().apply {
        putString(ARG_DOCK_SIDE, side.name)
    }

    /**
     * Arguments for a panel that should open as a popover attached to [anchor] rather than docked
     * against the edge of the screen.
     *
     * The anchor's position has to travel with the panel because a dialog is its own window and can
     * see nothing of the activity that opened it.
     */
    fun popoverArguments(side: DockSide, anchor: View): Bundle = dockArguments(side).apply {
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        putInt(ARG_ANCHOR_X, location[0] + anchor.width / 2)
        putInt(ARG_ANCHOR_BOTTOM, location[1] + anchor.height)
    }

    /**
     * Turns a docked sheet into a popover hanging beneath its button, and returns the drawable the
     * panel should use as its background so the beak lines up with that button.
     *
     * Returns null when no anchor was supplied, when the screen is too short to hang a panel below
     * a toolbar, or when the content is long enough that a floating card would be worse than a
     * full-height column - a scrolling list pinned to the edge of the screen is easier to use than
     * the same list in a box that cannot be as tall. The caller then keeps whatever background it
     * already had, so a panel that cannot be a popover simply is not one.
     */
    fun popoverSheet(
        dialog: android.app.Dialog?,
        arguments: Bundle?,
        widthDp: Int,
        maxHeightDp: Int
    ): PopoverBackground? {
        val bottomDialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return null
        val window = bottomDialog.window ?: return null
        val sheet = bottomDialog.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return null
        val anchorX = arguments?.getInt(ARG_ANCHOR_X, -1) ?: -1
        val anchorBottom = arguments?.getInt(ARG_ANCHOR_BOTTOM, -1) ?: -1
        if (anchorX < 0 || anchorBottom < 0) return null

        val context = sheet.context
        val display = context.resources.displayMetrics
        val displayWidthDp = (display.widthPixels / display.density).toInt()
        val displayHeightDp = (display.heightPixels / display.density).toInt()
        // A popover needs room for the panel plus the gap above it. Below that, the edge-docked
        // column is the better shape and this declines rather than producing a cramped box.
        if (displayHeightDp < 520 || displayWidthDp < 600) return null

        val panelWidthPx = dp(context, minOf(widthDp, displayWidthDp - 32))
        val gutter = dp(context, 12)
        val top = anchorBottom + dp(context, 6)
        val availableHeight = display.heightPixels - top - gutter
        if (availableHeight < dp(context, 240)) return null
        val panelHeightPx = minOf(dp(context, maxHeightDp), availableHeight)

        var left = anchorX - panelWidthPx / 2
        left = left.coerceIn(gutter, display.widthPixels - panelWidthPx - gutter)

        window.setLayout(panelWidthPx, panelHeightPx)
        window.setGravity(Gravity.LEFT or Gravity.TOP)
        window.attributes = window.attributes.apply {
            x = left
            y = top
        }
        window.setDimAmount(0.06f)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        enterImmersiveMode(window)

        sheet.layoutParams = sheet.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }

        return PopoverBackground(
            fillColor = ContextCompat.getColor(context, R.color.panel_background_solid),
            strokeColor = 0x2EFFFFFF,
            cornerRadius = dp(context, 18).toFloat(),
            beakWidth = dp(context, 22).toFloat(),
            beakHeight = dp(context, 10).toFloat(),
            strokeWidth = context.resources.displayMetrics.density
        ).apply {
            edge = PopoverBackground.Edge.TOP
            // The beak points at the button in the panel's own coordinates.
            beakCenter = (anchorX - left).toFloat()
        }
    }

    fun dockSide(arguments: Bundle?, fallback: DockSide): DockSide =
        arguments?.getString(ARG_DOCK_SIDE)?.let { runCatching { DockSide.valueOf(it) }.getOrNull() }
            ?: fallback

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** A quick, subtle scale-down/scale-up on press - the kind of tactile micro-feedback a
     * ripple alone doesn't give. Always returns false from the touch listener so it never
     * interferes with the view's normal click handling; it's a pure visual side effect. */
    /**
     * Kept as the app-wide name for press feedback, but the behaviour now lives in [PanelMotion] so
     * every control shares one curve and one duration - and so the system "animations off" setting
     * is honoured in a single place instead of being ignored here.
     *
     * The old version scaled to 0.88 in 90ms with no interpolator. At 12% the control visibly jumps
     * away from the finger, and on a full-width row it reads as the list flinching.
     */
    fun applyPressAnimation(view: View) = PanelMotion.press(view)

    /** Edge palettes are fixed in place, so they use a quiet top inset instead of a fake drag
     * handle. A handle on a non-draggable panel suggested a gesture that did nothing. */
    fun grabberHandle(context: Context): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 8))
        }
    }

    fun panelTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_App_PanelTitle)
        setPadding(dp(context, 18), dp(context, 6), dp(context, 18), dp(context, 12))
    }

    fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_App_SectionLabel)
        setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 6))
    }

    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
            topMargin = dp(context, 4)
            bottomMargin = dp(context, 4)
            marginStart = dp(context, 20)
            marginEnd = dp(context, 20)
        }
        setBackgroundColor(ContextCompat.getColor(context, R.color.hairline_color))
    }

    /** A tappable icon + label row, e.g. "Import Photo as Layer" in the Actions panel. */
    fun actionRow(context: Context, iconRes: Int, label: String, subtitle: String? = null, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(context, 8); marginEnd = dp(context, 8)
                topMargin = dp(context, 2); bottomMargin = dp(context, 2)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(context, 52)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            setOnClickListener { onClick() }
        }

        val iconWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(context, 19), dp(context, 19)).apply { gravity = Gravity.CENTER }
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(context, R.color.icon_tint_selector)
        }
        iconWrap.addView(icon)
        row.addView(iconWrap)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            }
        }
        textCol.addView(TextView(context).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_App_RowLabel)
        })
        if (subtitle != null) {
            textCol.addView(TextView(context).apply {
                text = subtitle
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                textSize = 12f
            })
        }
        row.addView(textCol)
        applyPressAnimation(row)

        return row
    }

    /** Same visual layout as [actionRow] but with a trailing [Switch] instead of a click action -
     * for a simple on/off preference like the drawing-sound toggle. */
    fun switchRow(
        context: Context, iconRes: Int, label: String, subtitle: String? = null,
        initialChecked: Boolean, onCheckedChanged: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(context, 8); marginEnd = dp(context, 8)
                topMargin = dp(context, 2); bottomMargin = dp(context, 2)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            isClickable = true
            isFocusable = true
            minimumHeight = dp(context, 52)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }

        val iconWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(context, 19), dp(context, 19)).apply { gravity = Gravity.CENTER }
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(context, R.color.icon_tint_selector)
        }
        iconWrap.addView(icon)
        row.addView(iconWrap)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            }
        }
        textCol.addView(TextView(context).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_App_RowLabel)
        })
        if (subtitle != null) {
            textCol.addView(TextView(context).apply {
                text = subtitle
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                textSize = 12f
            })
        }
        row.addView(textCol)

        val switch = SmoothSwitch(context).apply {
            isChecked = initialChecked
            setOnCheckedChangeListener { _, checked -> onCheckedChanged(checked) }
        }
        row.addView(switch)
        val toggle = { switch.performClick() }
        row.setOnClickListener { toggle() }
        applyPressAnimation(row)

        return row
    }

    /** Same visual layout as [actionRow] but non-interactive (no ripple, no click) - for
     * read-only info like the canvas dimensions in the Actions panel. */
    fun infoRow(context: Context, iconRes: Int, label: String, subtitle: String? = null): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(context, 8); marginEnd = dp(context, 8)
                topMargin = dp(context, 2); bottomMargin = dp(context, 2)
            }
            minimumHeight = dp(context, 52)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }

        val iconWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32))
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(context, 19), dp(context, 19)).apply { gravity = Gravity.CENTER }
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(context, R.color.icon_dim)
        }
        iconWrap.addView(icon)
        row.addView(iconWrap)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            }
        }
        textCol.addView(TextView(context).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 13f
        })
        if (subtitle != null) {
            textCol.addView(TextView(context).apply {
                text = subtitle
                setTextAppearance(R.style.TextAppearance_App_RowLabel)
            })
        }
        row.addView(textCol)

        return row
    }

    /** A label + live value + styled SeekBar, used for Adjustments/Brush Studio/Color Picker sliders. */
    fun sliderRow(
        context: Context,
        label: String,
        max: Int,
        initial: Int,
        valueFormatter: (Int) -> String = { it.toString() },
        /**
         * Called once when the finger lifts, for work too expensive to run per tick - recomputing
         * an adjustment across a full-canvas layer, for instance. [onChange] still fires on every
         * tick for anything cheap enough to preview live.
         */
        onRelease: (Int) -> Unit = {},
        onChange: (Int, Boolean) -> Unit
    ): Pair<View, SeekBar> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 0))
        }
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val labelView = TextView(context).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_App_RowLabel)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(context).apply {
            text = valueFormatter(initial)
            setTextAppearance(R.style.TextAppearance_App_RowValue)
            typeface = Typeface.DEFAULT_BOLD
        }
        labelRow.addView(labelView)
        labelRow.addView(valueView)
        row.addView(labelRow)

        val seekBar = SeekBar(context).apply {
            // Setting progressDrawable/thumb directly (rather than trying to apply the
            // Widget.App.SeekBar *style* via a ContextThemeWrapper) is the reliable way to
            // reskin a widget built in code: SeekBar's default style already defines both of
            // those attributes, and per Android's TypedArray resolution order a theme-injected
            // value loses to the widget's own defStyleAttr-resolved style. A direct property
            // set has no such ambiguity.
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.seekbar_progress)
            thumb = ContextCompat.getDrawable(context, R.drawable.seekbar_thumb)
            thumbOffset = 0
            splitTrack = false
            // Pin the track itself left-to-right while the label above it stays with the locale.
            // Left free, SeekBar mirrors the thumb under Arabic but leaves the progress fill
            // unmirrored, so every value between the two extremes showed the blue starting at one
            // end and the handle standing at the other. A slider is a claim about a quantity; it
            // cannot make that claim twice and differently.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            this.max = max
            this.progress = initial.coerceIn(0, max)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 2)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueView.text = valueFormatter(progress)
                    onChange(progress, fromUser)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onRelease(seekBar?.progress ?: return)
                }
            })
        }
        row.addView(seekBar)
        return row to seekBar
    }

    /** MaterialButton's own (context) constructor already resolves to its filled Material style
     * internally (it hardcodes R.attr.materialButtonStyle as its defStyleAttr), so no manual
     * theme-attribute lookup is needed here. */
    fun filledButton(context: Context, text: String, onClick: () -> Unit): View {
        return com.google.android.material.button.MaterialButton(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.procreate_accent)
            isAllCaps = false
            cornerRadius = dp(context, 14)
            setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10))
            setOnClickListener { onClick() }
        }
    }

    /**
     * Wraps a run that must stay in logical order inside an Arabic (RTL) paragraph - canvas
     * dimensions, coordinates, file sizes, versions.
     *
     * This is not cosmetic. "A4 للطباعة (2480 × 3508)" rendered on screen as
     * "A4 (3508 × 2480) للطباعة": bidi reordering swapped the two numbers around the neutral "×",
     * so the dialog stated the wrong canvas size - portrait presented as landscape. The string in
     * strings.xml was correct all along; only the rendering was wrong, which is why it survived
     * review. Isolating the run pins its direction regardless of the surrounding paragraph.
     */
    /**
     * Opens a bottom sheet fully expanded instead of collapsed at the foot of the screen.
     *
     * This app is locked to landscape, and that is where the default hurts: a BottomSheetDialog in
     * landscape opens COLLAPSED at a small peek height, so a panel appears as a sliver at the very
     * bottom showing its title and perhaps one row. Worse, dragging it - the natural reaction -
     * reads as a dismissal gesture and closes it. A panel in that state is indistinguishable from
     * a feature that was never added, which is exactly how it was reported: "the filters menu does
     * not appear". The filters menu was there; it was 40 pixels tall.
     *
     * Four panels already did this inline and seven did not, because it was written per panel
     * rather than once. Calling it from each fragment's onStart is what keeps the rule in one place.
     *
     * [skipCollapsed] matters too: without it the sheet can settle back into the collapsed sliver
     * on its way to being dismissed.
     */
    fun expandSheet(dialog: android.app.Dialog?, peekDp: Int = 560) {
        val sheet = (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)
            ?.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return

        // Let the sheet use the full height available; otherwise "expanded" still stops at whatever
        // height the collapsed layout pass measured.
        sheet.layoutParams = sheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }

        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = dp(sheet.context, peekDp)
        }
    }

    /**
     * Turns a landscape bottom sheet into a compact edge panel. Toolbars live at the left and right
     * edges, so opening their menus across the middle hides the exact area being edited. Keeping the
     * existing BottomSheetDialogFragment content preserves scrolling/back/outside-dismiss behaviour,
     * while the window and sheet are sized and anchored like a proper tablet palette.
     */
    fun dockSheet(
        dialog: android.app.Dialog?,
        side: DockSide,
        widthDp: Int = 390
    ) {
        val bottomDialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return
        val window = bottomDialog.window ?: return
        val sheet = bottomDialog.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return

        val display = sheet.resources.displayMetrics
        val displayWidthDp = (display.widthPixels / display.density).toInt()
        val displayHeightDp = (display.heightPixels / display.density).toInt()
        // On a phone in landscape, a fixed 390dp palette can cover almost the entire canvas, so
        // the width keeps a useful strip of artwork visible and both screen edges clear.
        val safeWidthDp = PanelGeometry.safeWidthDp(widthDp, displayWidthDp, displayHeightDp)
        window.setLayout(dp(sheet.context, safeWidthDp), ViewGroup.LayoutParams.MATCH_PARENT)
        window.setGravity(
            (if (side == DockSide.LEFT) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
        )
        window.setDimAmount(0.06f)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        enterImmersiveMode(window)
        window.attributes.windowAnimations = if (side == DockSide.LEFT) {
            R.style.Animation_App_SidePanelLeft
        } else {
            R.style.Animation_App_SidePanel
        }

        sheet.layoutParams = sheet.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        // BottomSheet's theme tint was still visible as a lighter slab on some Samsung builds.
        // Side palettes use one neutral surface with no decorative gradient.
        sheet.setBackgroundColor(ContextCompat.getColor(sheet.context, R.color.surface_1))
        com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
    }

    /**
     * Gives drawing windows the whole display. System bars remain available with one edge swipe
     * and disappear again automatically, which preserves navigation without permanently taxing a
     * phone's already short landscape canvas.
     */
    fun enterImmersiveMode(window: android.view.Window?) {
        window ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    fun ltr(text: String): CharSequence =
        androidx.core.text.BidiFormatter.getInstance(java.util.Locale.ENGLISH)
            .unicodeWrap(text, androidx.core.text.TextDirectionHeuristicsCompat.LTR)

    /** Canvas/pixel dimensions, always read left-to-right: "2480 × 3508". */
    fun dimensions(width: Int, height: Int): CharSequence = ltr("$width × $height")

    /**
     * The action row at the foot of a dialog: one prominent primary and one quiet secondary.
     *
     * Exists because the stock AlertDialog buttons are small low-contrast text with no visual
     * weight difference between "Create" and "Cancel" - on a tablet the primary action of a dialog
     * was a 14sp link in the corner. Both buttons here clear the 48dp target, and the primary
     * carries the accent fill so the eye lands on it first.
     *
     * Order follows the platform convention rather than being flipped by hand: the row is laid out
     * with the secondary first and the primary last, and RTL mirroring puts the primary where an
     * Arabic reader expects it.
     */
    fun dialogActions(
        context: Context,
        primaryText: String,
        secondaryText: String,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit = {}
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 4))

        addView(flatButton(context, secondaryText, onSecondary).apply {
            minimumHeight = dp(context, 48)
            minimumWidth = dp(context, 96)
        })
        addView(filledButton(context, primaryText, onPrimary).apply {
            minimumHeight = dp(context, 48)
            minimumWidth = dp(context, 120)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(context, 8) })
    }

    /**
     * An Apple-style inset grouped list: one rounded surface holding rows separated by hairlines,
     * instead of a stack of individually-outlined chips.
     *
     * Six separately-bordered full-width chips read as six unrelated buttons and made the canvas
     * dialog nearly fill a tablet screen. Grouping them into a single card says "these are the
     * choices for one setting" without any extra words.
     */
    fun groupedList(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_panel_rounded)
        clipToOutline = true
    }

    /**
     * A selectable row for [groupedList]: title at the start, an optional trailing value at the
     * end, and an optional subtitle beneath. The trailing value is direction-isolated, so numbers
     * stay readable in Arabic.
     */
    fun choiceRow(
        context: Context,
        title: String,
        trailing: CharSequence? = null,
        subtitle: String? = null,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 56)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            PanelMotion.press(this, depth = 0.985f)
        }

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        if (subtitle != null) {
            labels.addView(TextView(context).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setLineSpacing(dp(context, 2).toFloat(), 1f)
                setPadding(0, dp(context, 2), 0, 0)
            })
        }
        row.addView(labels)

        if (trailing != null) {
            row.addView(TextView(context).apply {
                text = trailing
                textSize = 13.5f
                textDirection = View.TEXT_DIRECTION_LTR
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPaddingRelative(dp(context, 12), 0, 0, 0)
            })
        }
        return row
    }

    /** Paints a [choiceRow] as selected or not. Kept here so every grouped list agrees on what
     * "selected" looks like. */
    fun setRowSelected(row: View, selected: Boolean) {
        val wasSelected = row.getTag(R.id.tag_row_selected) as? Boolean
        row.setTag(R.id.tag_row_selected, selected)
        row.background = ContextCompat.getDrawable(
            row.context,
            if (selected) R.drawable.bg_list_item_selected else R.drawable.bg_list_item_ripple
        )
        // Pulse only on the transition into selected, never on the initial paint - a list that
        // twitches every row as it is built looks broken rather than lively.
        if (selected && wasSelected == false) PanelMotion.pulse(row)
        val labels = (row as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        (labels.getChildAt(0) as? TextView)?.setTextColor(
            ContextCompat.getColor(
                row.context,
                if (selected) R.color.procreate_accent_light else R.color.text_primary
            )
        )
    }

    /** A plain flat text action (Reset/Cancel-style) - built from a styled TextView rather than
     * a MaterialButton "borderless" variant, so it doesn't depend on resolving a library
     * defStyleAttr correctly. */
    fun flatButton(context: Context, text: String, onClick: () -> Unit): View {
        return TextView(context).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 15f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_ripple_flat)
            isClickable = true
            isFocusable = true
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
            setOnClickListener { onClick() }
        }
    }
}
