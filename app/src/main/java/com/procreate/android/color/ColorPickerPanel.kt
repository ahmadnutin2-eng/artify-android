package com.procreate.android.color

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import kotlin.math.hypot

class ColorPickerPanel : BottomSheetDialogFragment() {

    /** Landscape bottom sheets open collapsed by default - see PanelUi.expandSheet. */
    override fun onStart() {
        super.onStart()
        PanelUi.dockSheet(dialog, PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT))
    }


    private var currentColor: Int = Color.BLACK
    private var alpha: Int = 255
    private val colorHistory = mutableListOf<Int>()
    private val maxHistorySize = 10
    private var onColorSelectedListener: ((Int) -> Unit)? = null

    private lateinit var svView: ColorSVView
    private lateinit var hueSlider: HueSliderView
    private lateinit var hexInput: EditText
    private lateinit var colorPreview: View
    private var suppressHexWatcher = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(PanelUi.grabberHandle(context))
        root.addView(PanelUi.panelTitle(context, getString(R.string.control_colors)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(20))
        }
        root.addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val compactLandscape = resources.configuration.screenHeightDp < 500

        // Large rounded preview swatch with a subtle ring, instead of a plain color rectangle.
        val previewFrame = FrameLayout(context).apply {
            val previewHeight = if (compactLandscape) 42 else 56
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(previewHeight)).apply {
                bottomMargin = dp(if (compactLandscape) 10 else 18)
            }
        }
        colorPreview = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (compactLandscape) 42 else 56)
            )
            // mutate() is required before tinting: getDrawable() can hand back a Drawable that
            // shares its ConstantState with every other bg_swatch_rounded instance in the app,
            // so an un-mutated setTintList() here would leak this swatch's color onto them too.
            background = ContextCompat.getDrawable(context, R.drawable.bg_swatch_rounded)?.mutate()
        }
        previewFrame.addView(colorPreview)
        val previewBorder = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (compactLandscape) 42 else 56)
            )
            background = ContextCompat.getDrawable(context, R.drawable.bg_ring_accent_rect)
        }
        previewFrame.addView(previewBorder)
        // Drag the colour straight out of the wheel and drop it into a shape.
        //
        // Choosing a colour and filling with it were two errands: pick here, close the panel, then
        // find the swatch on the rail and drag from that instead. The colour the artist is looking
        // at is the one they want to put somewhere, so it has to be the thing they can pick up. The
        // drag is flagged global because this panel is its own window; the colour travels in the
        // clip data, since local state does not cross a window boundary.
        previewFrame.contentDescription = getString(R.string.color_drop_hint)
        beginColorDragOnDrag(previewFrame)
        body.addView(previewFrame)
        // Nothing about a coloured rectangle says it can be picked up and carried, so the panel
        // says it. One line, under the thing it describes, rather than a gesture nobody finds.
        body.addView(TextView(context).apply {
            setText(R.string.color_drop_hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        svView = ColorSVView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (compactLandscape) 128 else 220)
            ).apply {
                bottomMargin = dp(if (compactLandscape) 10 else 16)
            }
            setColor(currentColor)
            onColorChanged = { color ->
                currentColor = color
                onColorUpdated(commit = false)
            }
        }
        body.addView(svView)

        hueSlider = HueSliderView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply {
                bottomMargin = dp(12)
            }
            onHueChanged = { hue ->
                svView.setHue(hue)
                currentColor = svView.currentColor()
                onColorUpdated(commit = false)
            }
        }
        body.addView(hueSlider)

        body.addView(PanelUi.sliderRow(context, "Alpha", 255, alpha) { progress, fromUser ->
            if (fromUser) { alpha = progress; onColorUpdated(commit = false) }
        }.first.apply {
            setPadding(0, 0, 0, 0)
        })

        // Hex input
        val hexRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        hexRow.addView(TextView(context).apply {
            text = "HEX"
            setTextAppearance(R.style.TextAppearance_App_SectionLabel)
            layoutParams = LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        hexInput = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = ContextCompat.getDrawable(context, R.drawable.bg_field_outline)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_hint))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setSingleLine(true)
            setText(String.format("%06X", 0xFFFFFF and currentColor))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (suppressHexWatcher) return
                    val hex = s?.toString()?.trim() ?: return
                    if (hex.length == 6 && hex.matches(Regex("[0-9a-fA-F]{6}"))) {
                        val parsed = ("FF$hex").toLong(16).toInt()
                        currentColor = parsed
                        svView.setColor(parsed)
                        val hsv = FloatArray(3)
                        Color.colorToHSV(parsed, hsv)
                        hueSlider.setHue(hsv[0])
                        onColorUpdated(commit = false, updateHex = false)
                    }
                }
            })
        }
        hexRow.addView(hexInput)
        body.addView(hexRow)

        // History
        body.addView(PanelUi.sectionLabel(context, "Recent Colors").apply {
            setPadding(0, dp(20), 0, dp(10))
        })

        val historyRecyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            clipToPadding = false
            adapter = HistoryAdapter(colorHistory) { color ->
                currentColor = color
                svView.setColor(color)
                val hsv = FloatArray(3)
                Color.colorToHSV(color, hsv)
                hueSlider.setHue(hsv[0])
                onColorUpdated(commit = true)
            }
        }
        body.addView(historyRecyclerView)

        // setColor() above fired its onColorChanged callback before that listener was attached,
        // so the preview swatch/hex field would otherwise sit on stock white/000000 until the
        // user's first touch. Sync them once now that every view is wired up.
        onColorUpdated(commit = false)

        return root
    }

    private fun currentColorWithAlpha(): Int =
        Color.argb(alpha, Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor))

    /**
     * @param commit whether this change should be recorded into "recent colors" (only on
     * discrete selections like a history tap, not on every pixel of a drag).
     */
    private fun onColorUpdated(commit: Boolean, updateHex: Boolean = true) {
        colorPreview.backgroundTintList = android.content.res.ColorStateList.valueOf(currentColorWithAlpha())
        onColorSelectedListener?.invoke(currentColorWithAlpha())
        if (updateHex && ::hexInput.isInitialized) {
            suppressHexWatcher = true
            val hex = String.format("%06X", 0xFFFFFF and currentColor)
            if (hexInput.text.toString() != hex) hexInput.setText(hex)
            suppressHexWatcher = false
        }
        if (commit) addToHistory(currentColorWithAlpha())
    }

    private fun addToHistory(color: Int) {
        if (colorHistory.firstOrNull() != color) {
            colorHistory.add(0, color)
            if (colorHistory.size > maxHistorySize) {
                colorHistory.removeAt(colorHistory.lastIndex)
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Commit whatever color the user ended on to history once they close the picker.
        addToHistory(currentColorWithAlpha())
    }

    /**
     * Let [source] be dragged out of the panel as a colour, starting the moment the finger moves.
     *
     * The first version waited for a long press. That reads fine on paper and fails in the hand:
     * the long-press timer is cancelled by the smallest movement past touch slop, and a stylus
     * resting on glass never holds still enough to survive four hundred milliseconds of it. What
     * the artist experienced was a swatch that simply did not respond. Starting on movement instead
     * removes the timer altogether - press and pull, the way a real swatch would come off a
     * palette - and it cannot be cancelled by the very motion that is meant to trigger it.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun beginColorDragOnDrag(source: View) {
        val slop = android.view.ViewConfiguration.get(source.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        source.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
                    // The panel scrolls; without this the ScrollView steals the gesture the moment
                    // the finger moves vertically, which is most of the ways out of this swatch.
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > slop
                    ) {
                        dragging = true
                        val dropped = currentColorWithAlpha()
                        val clip = android.content.ClipData.newPlainText(
                            COLOR_DROP_LABEL,
                            dropped.toString()
                        )
                        val started = view.startDragAndDrop(
                            clip,
                            View.DragShadowBuilder(view),
                            null,
                            View.DRAG_FLAG_GLOBAL
                        )
                        // Step aside so the artist can see the shape they are aiming at. The system
                        // owns the drag once it has begun, so closing the panel does not cancel it.
                        if (started) dismiss()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!dragging) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    fun setOnColorSelectedListener(listener: (Int) -> Unit) {
        onColorSelectedListener = listener
    }

    companion object {
        /** Marks a drag as carrying a colour to drop, rather than text to paste. */
        const val COLOR_DROP_LABEL = "color_drop"
    }
}

class HistoryAdapter(private val colors: List<Int>, private val onClick: (Int) -> Unit) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
    class ViewHolder(val frame: FrameLayout, val swatch: View) : RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        fun dp(v: Int) = PanelUi.dp(context, v)
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(dp(48), dp(48)).apply {
                setMargins(0, 0, dp(10), 0)
            }
        }
        val swatch = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER }
            // mutate() so each recycled cell tints its own Drawable instance instead of a
            // ConstantState shared across every bg_swatch_rounded view (see colorPreview above).
            background = ContextCompat.getDrawable(context, R.drawable.bg_swatch_rounded)?.mutate()
        }
        frame.addView(swatch)
        val border = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply { gravity = Gravity.CENTER }
            background = ContextCompat.getDrawable(context, R.drawable.bg_ring_accent_rect)
            alpha = 0.6f
        }
        frame.addView(border)
        return ViewHolder(frame, swatch)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.swatch.backgroundTintList = android.content.res.ColorStateList.valueOf(colors[position])
        // Bind the listener here (against the live `position` parameter) rather than once in
        // onCreateViewHolder - a ViewHolder's list index can change across rebinds, and
        // RecyclerView.Adapter itself has no `adapterPosition` in scope to close over anyway.
        holder.frame.setOnClickListener { onClick(colors[holder.bindingAdapterPosition]) }
    }

    override fun getItemCount() = colors.size
}
