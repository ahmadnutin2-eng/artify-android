package com.procreate.android.urban.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.procreate.android.R
import com.procreate.android.ui.common.SmoothSwitch
import com.procreate.android.urban.assets.AssetInstance
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bottom sheet for editing one placed architectural resource without coupling the editor to the
 * canvas. The caller remains responsible for committing the returned immutable [AssetInstance]
 * to project history, and for deleting the id returned by [onDelete].
 */
class ArchitecturalAssetPropertiesDialog : BottomSheetDialogFragment() {

    private lateinit var sourceInstance: AssetInstance
    private var onApply: ((AssetInstance) -> Unit)? = null
    private var onDelete: ((String) -> Unit)? = null

    private var workingScale = 1.0
    private var workingRotation = 0.0
    private var workingOpacity = 1f
    private var workingVisible = true
    private var workingLocked = false
    private var useDefaultColor = true
    private var workingOverrideColor = FALLBACK_OVERRIDE_COLOR
    private var defaultColor = FALLBACK_OVERRIDE_COLOR

    private lateinit var defaultColorSwitch: SmoothSwitch
    private lateinit var colorInputLayout: TextInputLayout
    private lateinit var colorInput: TextInputEditText
    private lateinit var colorPreview: View
    private val swatchViews = mutableListOf<Pair<Int, View>>()
    private var updatingColorInput = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceInstance = readInstance(requireArguments())
        defaultColor = if (requireArguments().getBoolean(ARG_HAS_DEFAULT_COLOR)) {
            requireArguments().getInt(ARG_DEFAULT_COLOR)
        } else {
            FALLBACK_OVERRIDE_COLOR
        }

        workingScale = savedInstanceState?.getDouble(STATE_SCALE)
            ?: sourceInstance.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        workingRotation = savedInstanceState?.getDouble(STATE_ROTATION)
            ?: sourceInstance.normalizedRotationDegrees.coerceIn(MIN_ROTATION, MAX_ROTATION)
        workingOpacity = savedInstanceState?.getFloat(STATE_OPACITY)
            ?: sourceInstance.opacity.coerceIn(0f, 1f)
        workingVisible = savedInstanceState?.getBoolean(STATE_VISIBLE)
            ?: sourceInstance.isVisible
        workingLocked = savedInstanceState?.getBoolean(STATE_LOCKED)
            ?: sourceInstance.isLocked
        useDefaultColor = savedInstanceState?.getBoolean(STATE_USE_DEFAULT_COLOR)
            ?: (sourceInstance.colorOverrideArgb == null)
        workingOverrideColor = savedInstanceState?.getInt(STATE_OVERRIDE_COLOR)
            ?: sourceInstance.colorOverrideArgb
            ?: defaultColor
    }

    override fun onStart() {
        super.onStart()
        val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
        val sheet = bottomSheetDialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isFitToContents = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(ContextCompat.getColor(context, R.color.surface_1))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(context, 760), (resources.displayMetrics.heightPixels * 0.92f).toInt())
            )
        }

        root.addView(buildGrabber(context))
        root.addView(buildHeader(context))
        root.addView(divider(context))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 20))
        }
        content.addView(buildScaleControl(context))
        content.addView(buildRotationControl(context))
        content.addView(buildOpacityControl(context))
        content.addView(buildVisibilityControls(context))
        content.addView(buildColorControls(context))

        root.addView(NestedScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(divider(context))
        root.addView(buildActions(context))
        syncColorControls(updateText = true)
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::defaultColorSwitch.isInitialized) {
            useDefaultColor = defaultColorSwitch.isChecked
        }
        outState.putDouble(STATE_SCALE, workingScale)
        outState.putDouble(STATE_ROTATION, workingRotation)
        outState.putFloat(STATE_OPACITY, workingOpacity)
        outState.putBoolean(STATE_VISIBLE, workingVisible)
        outState.putBoolean(STATE_LOCKED, workingLocked)
        outState.putBoolean(STATE_USE_DEFAULT_COLOR, useDefaultColor)
        outState.putInt(STATE_OVERRIDE_COLOR, workingOverrideColor)
        super.onSaveInstanceState(outState)
    }

    /** Assigns the direct callbacks before the sheet is shown. */
    fun setCallbacks(
        onApply: (AssetInstance) -> Unit,
        onDelete: (String) -> Unit
    ): ArchitecturalAssetPropertiesDialog = apply {
        this.onApply = onApply
        this.onDelete = onDelete
    }

    private fun buildGrabber(context: Context): View = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 2).toFloat()
            setColor(ContextCompat.getColor(context, R.color.surface_4))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(context, 42), dp(context, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(context, 10)
            bottomMargin = dp(context, 8)
        }
    }

    private fun buildHeader(context: Context): View {
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            text = "خصائص العنصر"
            textSize = 19f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        textColumn.addView(TextView(context).apply {
            text = "عدّل الحجم والدوران والمظهر"
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(context, 3), 0, 0)
        })

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 20), 0, dp(context, 12), dp(context, 10))
            addView(textColumn)
            addView(outlinedButton(context, "إغلاق").apply {
                contentDescription = "إغلاق خصائص العنصر"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)))
        }
    }

    private fun buildScaleControl(context: Context): View {
        val valueLabel = valueLabel(context)
        fun updateLabel() {
            valueLabel.text = String.format(Locale.US, "× %.1f", workingScale)
        }
        updateLabel()
        return controlSection(
            context = context,
            title = "الحجم",
            description = "من 0.1 إلى 10 أضعاف الحجم الأصلي",
            valueLabel = valueLabel,
            control = styledSlider(context).apply {
                valueFrom = MIN_SCALE.toFloat()
                valueTo = MAX_SCALE.toFloat()
                stepSize = 0.1f
                value = workingScale.toFloat().coerceIn(valueFrom, valueTo)
                contentDescription = "حجم العنصر"
                addOnChangeListener { _, newValue, _ ->
                    workingScale = newValue.toDouble()
                    updateLabel()
                }
            }
        )
    }

    private fun buildRotationControl(context: Context): View {
        val valueLabel = valueLabel(context)
        fun updateLabel() {
            valueLabel.text = "${workingRotation.roundToInt()}°"
        }
        updateLabel()
        return controlSection(
            context = context,
            title = "الدوران",
            description = "زاوية العنصر من 0° إلى 359°",
            valueLabel = valueLabel,
            control = styledSlider(context).apply {
                valueFrom = MIN_ROTATION.toFloat()
                valueTo = MAX_ROTATION.toFloat()
                stepSize = 1f
                value = workingRotation.toFloat().coerceIn(valueFrom, valueTo)
                contentDescription = "زاوية دوران العنصر"
                addOnChangeListener { _, newValue, _ ->
                    workingRotation = newValue.toDouble()
                    updateLabel()
                }
            }
        )
    }

    private fun buildOpacityControl(context: Context): View {
        val valueLabel = valueLabel(context)
        fun updateLabel() {
            valueLabel.text = "${(workingOpacity * 100f).roundToInt()}٪"
        }
        updateLabel()
        return controlSection(
            context = context,
            title = "العتامة",
            description = "0٪ مخفي و100٪ ظاهر بالكامل",
            valueLabel = valueLabel,
            control = styledSlider(context).apply {
                valueFrom = 0f
                valueTo = 100f
                stepSize = 1f
                value = (workingOpacity * 100f).coerceIn(valueFrom, valueTo)
                contentDescription = "عتامة العنصر"
                addOnChangeListener { _, newValue, _ ->
                    workingOpacity = (newValue / 100f).coerceIn(0f, 1f)
                    updateLabel()
                }
            }
        )
    }

    private fun buildVisibilityControls(context: Context): View {
        val container = sectionContainer(context)
        container.addView(sectionTitle(context, "الحالة"))
        container.addView(SmoothSwitch(context).apply {
            text = "إظهار العنصر"
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            isChecked = workingVisible
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            contentDescription = "إظهار العنصر أو إخفاؤه"
            setOnCheckedChangeListener { _, checked -> workingVisible = checked }
        }, matchWidthWrapHeight())
        container.addView(SmoothSwitch(context).apply {
            text = "قفل العنصر"
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            isChecked = workingLocked
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            contentDescription = "منع تحريك العنصر أو تعديله على اللوحة"
            setOnCheckedChangeListener { _, checked -> workingLocked = checked }
        }, matchWidthWrapHeight())
        container.addView(helperText(context, "العنصر المقفول يبقى ظاهرًا، لكن لا يمكن تحريكه حتى إلغاء القفل."))
        return container
    }

    private fun buildColorControls(context: Context): View {
        swatchViews.clear()
        val container = sectionContainer(context)
        container.addView(sectionTitle(context, "اللون"))

        defaultColorSwitch = SmoothSwitch(context).apply {
            text = "استخدام اللون الافتراضي"
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            isChecked = useDefaultColor
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            contentDescription = "استخدام لون المورد الأصلي"
            setOnCheckedChangeListener { _, checked ->
                useDefaultColor = checked
                syncColorControls(updateText = false)
            }
        }
        container.addView(defaultColorSwitch, matchWidthWrapHeight())

        val paletteRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 10))
        }
        COLOR_PALETTE.forEachIndexed { index, color ->
            val swatch = View(context).apply {
                isClickable = true
                isFocusable = true
                minimumWidth = dp(context, 48)
                minimumHeight = dp(context, 48)
                contentDescription = "لون بديل ${index + 1}"
                setOnClickListener {
                    workingOverrideColor = color
                    useDefaultColor = false
                    defaultColorSwitch.isChecked = false
                    syncColorControls(updateText = true)
                }
            }
            swatchViews += color to swatch
            paletteRow.addView(swatch, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply {
                marginEnd = dp(context, 6)
            })
        }
        container.addView(paletteRow, matchWidthWrapHeight())

        colorInput = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(9))
            maxLines = 1
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = dp(context, 56)
            contentDescription = "قيمة اللون البديل بالنظام الست عشري"
            doAfterTextChanged { editable ->
                if (updatingColorInput || useDefaultColor) return@doAfterTextChanged
                val entered = editable?.toString().orEmpty()
                val parsed = parseArgb(entered)
                if (parsed != null) {
                    workingOverrideColor = parsed
                    colorInputLayout.error = null
                    syncColorControls(updateText = false)
                } else {
                    colorInputLayout.error = if (entered.length >= 6) {
                        "أدخل اللون بصيغة #RRGGBB أو #AARRGGBB"
                    } else {
                        null
                    }
                }
            }
        }
        colorPreview = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        colorInputLayout = TextInputLayout(
            context,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "قيمة اللون"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            prefixText = "#"
            addView(colorInput)
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(colorPreview, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply {
                marginEnd = dp(context, 10)
            })
            addView(colorInputLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(inputRow, matchWidthWrapHeight())
        container.addView(helperText(context, "يمكن إدخال RRGGBB، أو AARRGGBB لتحديد شفافية اللون نفسه."))
        return container
    }

    private fun buildActions(context: Context): View {
        val deleteButton = outlinedButton(context, "حذف العنصر").apply {
            setTextColor(ContextCompat.getColor(context, R.color.danger))
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.danger))
            contentDescription = "حذف العنصر من المخطط"
            setOnClickListener { confirmDelete() }
        }
        val applyButton = MaterialButton(context).apply {
            text = "تطبيق التعديلات"
            textSize = 14f
            isAllCaps = false
            minHeight = dp(context, 52)
            minimumHeight = dp(context, 52)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.procreate_accent)
            )
            rippleColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.ripple_light)
            )
            contentDescription = "تطبيق تعديلات خصائص العنصر"
            setOnClickListener { applyChanges() }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 16))
            addView(deleteButton, LinearLayout.LayoutParams(0, dp(context, 52), 1f).apply {
                marginEnd = dp(context, 8)
            })
            addView(applyButton, LinearLayout.LayoutParams(0, dp(context, 52), 1.35f))
        }
    }

    private fun applyChanges() {
        val selectedColor = if (defaultColorSwitch.isChecked) {
            null
        } else {
            parseArgb(colorInput.text?.toString().orEmpty()) ?: workingOverrideColor
        }
        val updated = sourceInstance.copy(
            scale = workingScale.coerceIn(MIN_SCALE, MAX_SCALE),
            rotationDegrees = workingRotation.coerceIn(MIN_ROTATION, MAX_ROTATION),
            colorOverrideArgb = selectedColor,
            opacity = workingOpacity.coerceIn(0f, 1f),
            isVisible = workingVisible,
            isLocked = workingLocked
        )
        onApply?.invoke(updated)
        dismiss()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("حذف العنصر؟")
            .setMessage("سيُحذف هذا العنصر من المخطط. يمكن للمحرر إتاحة التراجع بعد الحذف.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حذف") { _, _ ->
                onDelete?.invoke(sourceInstance.id)
                dismiss()
            }
            .show()
    }

    private fun syncColorControls(updateText: Boolean) {
        if (!::defaultColorSwitch.isInitialized || !::colorInputLayout.isInitialized) return
        val overrideEnabled = !useDefaultColor
        colorInputLayout.isEnabled = overrideEnabled
        colorInput.isEnabled = overrideEnabled
        colorInputLayout.alpha = if (overrideEnabled) 1f else 0.55f
        swatchViews.forEach { (color, view) ->
            view.isEnabled = overrideEnabled
            view.alpha = if (overrideEnabled) 1f else 0.45f
            val selected = overrideEnabled && color == workingOverrideColor
            view.background = colorCircleDrawable(
                context = requireContext(),
                color = color,
                selected = selected
            )
            view.isSelected = selected
        }
        val previewColor = if (useDefaultColor) defaultColor else workingOverrideColor
        colorPreview.background = colorCircleDrawable(
            context = requireContext(),
            color = previewColor,
            selected = false
        )
        if (updateText) {
            updatingColorInput = true
            colorInput.setText(formatArgbWithoutHash(workingOverrideColor))
            colorInput.setSelection(colorInput.text?.length ?: 0)
            updatingColorInput = false
            colorInputLayout.error = null
        }
    }

    private fun controlSection(
        context: Context,
        title: String,
        description: String,
        valueLabel: TextView,
        control: View
    ): View {
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(context, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueLabel)
        }
        return sectionContainer(context).apply {
            addView(heading, matchWidthWrapHeight())
            addView(helperText(context, description))
            addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)))
        }
    }

    private fun sectionContainer(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 14).toFloat()
            setColor(ContextCompat.getColor(context, R.color.surface_2))
            setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.outline_subtle))
        }
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 12)
        }
    }

    private fun sectionTitle(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun helperText(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setPadding(0, dp(context, 3), 0, dp(context, 5))
    }

    private fun valueLabel(context: Context): TextView = TextView(context).apply {
        textSize = 13.5f
        setTextColor(ContextCompat.getColor(context, R.color.procreate_accent_light))
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(context, 64)
        minHeight = dp(context, 48)
    }

    private fun styledSlider(context: Context): Slider = Slider(context).apply {
        minimumHeight = dp(context, 48)
        thumbTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.procreate_accent_light)
        )
        trackActiveTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.procreate_accent)
        )
        trackInactiveTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.surface_4)
        )
        haloTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.ripple_accent)
        )
    }

    private fun outlinedButton(context: Context, label: String): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.outline_subtle)
            )
            rippleColor = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.ripple_light)
            )
        }

    private fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.divider_color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1))
    }

    private fun colorCircleDrawable(
        context: Context,
        color: Int,
        selected: Boolean
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            dp(context, if (selected) 3 else 1),
            ContextCompat.getColor(
                context,
                if (selected) R.color.procreate_accent_light else R.color.text_secondary
            )
        )
    }

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val TAG = "ArchitecturalAssetPropertiesDialog"

        private const val ARG_ID = "id"
        private const val ARG_ASSET_ID = "asset_id"
        private const val ARG_X_METERS = "x_meters"
        private const val ARG_Y_METERS = "y_meters"
        private const val ARG_SCALE = "scale"
        private const val ARG_ROTATION = "rotation"
        private const val ARG_HAS_OVERRIDE_COLOR = "has_override_color"
        private const val ARG_OVERRIDE_COLOR = "override_color"
        private const val ARG_OPACITY = "opacity"
        private const val ARG_VISIBLE = "visible"
        private const val ARG_LOCKED = "locked"
        private const val ARG_Z_INDEX = "z_index"
        private const val ARG_HAS_DEFAULT_COLOR = "has_default_color"
        private const val ARG_DEFAULT_COLOR = "default_color"

        private const val STATE_SCALE = "state_scale"
        private const val STATE_ROTATION = "state_rotation"
        private const val STATE_OPACITY = "state_opacity"
        private const val STATE_VISIBLE = "state_visible"
        private const val STATE_LOCKED = "state_locked"
        private const val STATE_USE_DEFAULT_COLOR = "state_use_default_color"
        private const val STATE_OVERRIDE_COLOR = "state_override_color"

        private const val MIN_SCALE = 0.1
        private const val MAX_SCALE = 10.0
        private const val MIN_ROTATION = 0.0
        private const val MAX_ROTATION = 359.0
        private val FALLBACK_OVERRIDE_COLOR = 0xFF455A64.toInt()
        private val COLOR_PALETTE = intArrayOf(
            0xFF263238.toInt(),
            0xFF6D4C41.toInt(),
            0xFF2E7D32.toInt(),
            0xFF1565C0.toInt(),
            0xFFF57C00.toInt(),
            0xFFC62828.toInt()
        )

        /**
         * Opens the editor for [assetInstance]. [defaultColorArgb] is used only for the preview of
         * the "default color" option; choosing that option always returns a null color override.
         */
        fun show(
            fragmentManager: FragmentManager,
            assetInstance: AssetInstance,
            defaultColorArgb: Int? = null,
            onApply: (AssetInstance) -> Unit,
            onDelete: (String) -> Unit
        ): ArchitecturalAssetPropertiesDialog {
            return ArchitecturalAssetPropertiesDialog().apply {
                arguments = writeArguments(assetInstance, defaultColorArgb)
                setCallbacks(onApply = onApply, onDelete = onDelete)
                show(fragmentManager, TAG)
            }
        }

        private fun writeArguments(
            instance: AssetInstance,
            defaultColorArgb: Int?
        ): Bundle = Bundle().apply {
            putString(ARG_ID, instance.id)
            putString(ARG_ASSET_ID, instance.assetId)
            putDouble(ARG_X_METERS, instance.xMeters)
            putDouble(ARG_Y_METERS, instance.yMeters)
            putDouble(ARG_SCALE, instance.scale)
            putDouble(ARG_ROTATION, instance.rotationDegrees)
            putBoolean(ARG_HAS_OVERRIDE_COLOR, instance.colorOverrideArgb != null)
            instance.colorOverrideArgb?.let { putInt(ARG_OVERRIDE_COLOR, it) }
            putFloat(ARG_OPACITY, instance.opacity)
            putBoolean(ARG_VISIBLE, instance.isVisible)
            putBoolean(ARG_LOCKED, instance.isLocked)
            putInt(ARG_Z_INDEX, instance.zIndex)
            putBoolean(ARG_HAS_DEFAULT_COLOR, defaultColorArgb != null)
            defaultColorArgb?.let { putInt(ARG_DEFAULT_COLOR, it) }
        }

        private fun readInstance(arguments: Bundle): AssetInstance = AssetInstance(
            id = requireNotNull(arguments.getString(ARG_ID)) { "Missing asset instance id" },
            assetId = requireNotNull(arguments.getString(ARG_ASSET_ID)) { "Missing catalog asset id" },
            xMeters = arguments.getDouble(ARG_X_METERS),
            yMeters = arguments.getDouble(ARG_Y_METERS),
            scale = arguments.getDouble(ARG_SCALE, 1.0),
            rotationDegrees = arguments.getDouble(ARG_ROTATION),
            colorOverrideArgb = if (arguments.getBoolean(ARG_HAS_OVERRIDE_COLOR)) {
                arguments.getInt(ARG_OVERRIDE_COLOR)
            } else {
                null
            },
            opacity = arguments.getFloat(ARG_OPACITY, 1f),
            isVisible = arguments.getBoolean(ARG_VISIBLE, true),
            isLocked = arguments.getBoolean(ARG_LOCKED),
            zIndex = arguments.getInt(ARG_Z_INDEX)
        )

        private fun parseArgb(input: String): Int? {
            val normalized = input.trim().removePrefix("#")
            if (normalized.length != 6 && normalized.length != 8) return null
            if (normalized.any { it !in '0'..'9' && it.uppercaseChar() !in 'A'..'F' }) return null
            val withAlpha = if (normalized.length == 6) "FF$normalized" else normalized
            return withAlpha.toLongOrNull(16)?.toInt()
        }

        private fun formatArgbWithoutHash(color: Int): String =
            String.format(Locale.US, "%08X", color.toLong() and 0xFFFFFFFFL)

        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).roundToInt()
    }
}
