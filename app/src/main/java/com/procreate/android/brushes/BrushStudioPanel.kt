package com.procreate.android.brushes

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.canvas.BrushProperties
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.ui.common.SmoothSwitch

/** Professional, live Brush Studio for the currently selected brush. */
class BrushStudioPanel : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val screenWidthDp = resources.configuration.screenWidthDp
        PanelUi.dockSheet(
            dialog,
            PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT),
            widthDp = if (screenWidthDp >= 900) 500 else 430
        )
        val bottomDialog = dialog as? BottomSheetDialog ?: return
        val window = bottomDialog.window ?: return
        bottomDialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(Color.TRANSPARENT)
        window.setDimAmount(0.015f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val attributes = window.attributes
            attributes.blurBehindRadius = PanelUi.dp(requireContext(), 18)
            window.attributes = attributes
        }
    }

    private val viewModel: CanvasViewModel by activityViewModels()
    private lateinit var previewView: BrushPreviewView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(value: Int) = PanelUi.dp(context, value)

        val original = (viewModel.currentBrush.value ?: BrushProperties()).copy()
        var properties = original.copy()
        val controlSync = mutableListOf<() -> Unit>()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_brush_library_glass)
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(PanelUi.grabberHandle(context))

        fun applyChanges() {
            previewView.setProperties(properties.copy())
            viewModel.setCurrentBrush(properties.copy())
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(PanelUi.panelTitle(context, getString(R.string.brush_studio_title)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(PanelUi.flatButton(context, getString(R.string.brush_reset_changes)) {
            properties = original.copy()
            controlSync.forEach { it() }
            applyChanges()
        })
        header.addView(PanelUi.flatButton(context, getString(R.string.clear)) {
            previewView.clear()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        })
        root.addView(header)

        root.addView(TextView(context).apply {
            setText(R.string.brush_preview_hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 11.5f
            setPadding(dp(20), 0, dp(20), dp(7))
        })

        val previewHeight = if (resources.configuration.screenHeightDp < 520) 128 else 190
        val previewCard = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(previewHeight)
            ).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
                bottomMargin = dp(6)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_brush_preview)
            clipToOutline = true
        }
        previewView = BrushPreviewView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentDescription = getString(R.string.brush_preview_accessibility)
            setProperties(properties.copy())
        }
        previewCard.addView(previewView)
        root.addView(previewCard)

        // One long scroll made every control equally prominent and equally hard to find: reaching
        // tilt meant dragging past eleven sliders that had nothing to do with it. Splitting the
        // studio into named pages, with a rail to move between them, is what turns a settings sheet
        // into an instrument - and it leaves room for each page to explain itself.
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            isFillViewport = true
        }
        val pageHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(14))
        }
        scroll.addView(pageHost)

        val railScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(8))
        }
        railScroll.addView(rail)

        // Content first so Arabic RTL mirrors the rail onto the physical left, matching the
        // brush library next to it.
        body.addView(scroll)
        body.addView(railScroll)
        root.addView(body)

        val pages = mutableListOf<LinearLayout>()
        val railButtons = mutableListOf<TextView>()
        var controls = LinearLayout(context)

        fun showPage(index: Int) {
            pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
            railButtons.forEachIndexed { i, button ->
                button.isSelected = i == index
                button.setBackgroundResource(
                    if (i == index) R.drawable.bg_brush_category_selected else R.drawable.bg_brush_row
                )
                button.setTextColor(ContextCompat.getColor(
                    context, if (i == index) R.color.white else R.color.text_secondary
                ))
            }
            scroll.scrollTo(0, 0)
        }

        /** Open a new page and route every control declared after it into that page. */
        fun page(titleRes: Int) {
            val pageView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                visibility = View.GONE
            }
            pageHost.addView(pageView)
            pages += pageView
            controls = pageView

            val index = pages.size - 1
            rail.addView(TextView(context).apply {
                text = getString(titleRes)
                textSize = 12.5f
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(9), dp(9), dp(9), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3) }
                setOnClickListener { showPage(index) }
                PanelUi.applyPressAnimation(this)
            }.also { railButtons += it })
        }

        fun section(titleRes: Int, subtitleRes: Int? = null) {
            controls.addView(PanelUi.sectionLabel(context, getString(titleRes)))
            if (subtitleRes != null) {
                controls.addView(TextView(context).apply {
                    setText(subtitleRes)
                    setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                    textSize = 11.5f
                    setPadding(dp(18), 0, dp(18), dp(4))
                })
            }
        }

        fun slider(
            labelRes: Int,
            min: Int,
            max: Int,
            value: () -> Int,
            formatter: (Int) -> String,
            onChange: (Int) -> Unit
        ): android.widget.SeekBar {
            val range = (max - min).coerceAtLeast(1)
            val (row, seekBar) = PanelUi.sliderRow(
                context = context,
                label = getString(labelRes),
                max = range,
                initial = (value() - min).coerceIn(0, range),
                valueFormatter = { progress -> formatter(progress + min) }
            ) { progress, fromUser ->
                if (fromUser) {
                    onChange(progress + min)
                    applyChanges()
                }
            }
            controls.addView(row)
            controlSync += { seekBar.progress = (value() - min).coerceIn(0, range) }
            return seekBar
        }

        fun percent(value: Int) = getString(R.string.brush_value_percent, value)

        page(R.string.brush_page_stabilization)
        section(R.string.brush_section_stabilization, R.string.brush_section_stabilization_sub)
        val smoothingBar = slider(
            R.string.brush_smoothing,
            0,
            95,
            { (properties.smoothing * 100).toInt() },
            ::percent
        ) { properties.smoothing = it / 100f }
        controls.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(8))
            fun preset(labelRes: Int, value: Int) {
                addView(PanelUi.flatButton(context, getString(labelRes)) {
                    properties.smoothing = value / 100f
                    smoothingBar.progress = value
                    applyChanges()
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
            preset(R.string.brush_smoothing_raw, 0)
            preset(R.string.brush_smoothing_balanced, 45)
            preset(R.string.brush_smoothing_silky, 82)
        })

        page(R.string.brush_page_stroke)
        section(R.string.brush_section_stroke)
        slider(R.string.brush_setting_size, 1, 200, { properties.size.toInt() },
            { getString(R.string.brush_value_pixels, it) }) { properties.size = it.toFloat() }
        slider(R.string.brush_setting_opacity, 0, 100, { (properties.opacity * 100).toInt() },
            ::percent) { properties.opacity = it / 100f }
        slider(R.string.brush_setting_flow, 0, 100, { (properties.flow * 100).toInt() },
            ::percent) { properties.flow = it / 100f }
        slider(R.string.brush_setting_spacing, 1, 100, { (properties.spacing * 100).toInt() },
            ::percent) { properties.spacing = it / 100f }

        page(R.string.brush_page_shape)
        section(R.string.brush_section_shape, R.string.brush_section_shape_sub)
        slider(R.string.brush_setting_angle, 0, 360, { properties.angle.toInt() },
            { getString(R.string.brush_value_degrees, it) }) { properties.angle = it.toFloat() }
        controls.addView(PanelUi.switchRow(
            context,
            R.drawable.ic_transform,
            getString(R.string.brush_setting_orient),
            getString(R.string.brush_setting_orient_sub),
            properties.orientToStroke
        ) { checked ->
            properties.orientToStroke = checked
            applyChanges()
        }.also { row ->
            // switchRow owns its internal switch; keep reset synchronized by clicking only when
            // its semantic state changed through Reset.
            controlSync += {
                val switch = findSmoothSwitch(row)
                if (switch != null && switch.isChecked != properties.orientToStroke) {
                    switch.isChecked = properties.orientToStroke
                }
            }
        })
        page(R.string.brush_page_dynamics)
        section(R.string.brush_section_dynamics)
        slider(R.string.brush_setting_scatter, 0, 100, { (properties.scatter * 100).toInt() },
            ::percent) { properties.scatter = it / 100f }
        slider(R.string.brush_setting_angle_jitter, 0, 180, { properties.angleJitter.toInt() },
            { getString(R.string.brush_value_degrees, it) }) { properties.angleJitter = it.toFloat() }
        slider(R.string.brush_setting_size_jitter, 0, 100, { (properties.sizeJitter * 100).toInt() },
            ::percent) { properties.sizeJitter = it / 100f }
        slider(R.string.brush_setting_opacity_jitter, 0, 100, { (properties.opacityJitter * 100).toInt() },
            ::percent) { properties.opacityJitter = it / 100f }
        slider(R.string.brush_setting_velocity_min, 0, 100, { (properties.velocitySizeMin * 100).toInt() },
            ::percent) { properties.velocitySizeMin = it / 100f }

        page(R.string.brush_page_pencil)
        section(R.string.brush_section_pressure)
        slider(R.string.brush_setting_pressure_size, 0, 100, { (properties.pressureSizeScale * 100).toInt() },
            ::percent) { properties.pressureSizeScale = it / 100f }
        slider(R.string.brush_setting_pressure_opacity, 0, 100, { (properties.pressureOpacityScale * 100).toInt() },
            ::percent) { properties.pressureOpacityScale = it / 100f }

        section(R.string.brush_section_stylus, R.string.brush_section_stylus_sub)
        slider(R.string.brush_setting_azimuth_tracking, 0, 100, { (properties.azimuthTracking * 100).toInt() },
            ::percent) { properties.azimuthTracking = it / 100f }
        slider(R.string.brush_setting_tilt_size, 0, 200, { (properties.tiltSizeScale * 100).toInt() },
            ::percent) { properties.tiltSizeScale = it / 100f }
        slider(R.string.brush_setting_tilt_opacity, 0, 100, { (properties.tiltOpacityScale * 100).toInt() },
            ::percent) { properties.tiltOpacityScale = it / 100f }

        controls.addView(PanelUi.switchRow(
            context,
            R.drawable.ic_blur,
            getString(R.string.brush_setting_build_up),
            getString(R.string.brush_setting_build_up_sub),
            properties.buildUp
        ) { checked ->
            properties.buildUp = checked
            applyChanges()
        }.also { row ->
            controlSync += {
                val switch = findSmoothSwitch(row)
                if (switch != null && switch.isChecked != properties.buildUp) {
                    switch.isChecked = properties.buildUp
                }
            }
        })

        page(R.string.brush_page_color)
        section(R.string.brush_section_color, R.string.brush_section_color_sub)
        controls.addView(PanelUi.switchRow(
            context,
            R.drawable.ic_colors,
            getString(R.string.brush_setting_color_flow_enabled),
            getString(R.string.brush_setting_color_flow_enabled_sub),
            properties.colorFlowEnabled
        ) { checked ->
            properties.colorFlowEnabled = checked
            // Switching it on with everything at zero would look broken. Give it enough drift to
            // be visible, once, and leave anything the artist has already tuned alone.
            if (checked && properties.colorFlowHue == 0f && properties.colorJitterHue == 0f &&
                properties.colorJitterSaturation == 0f && properties.colorJitterBrightness == 0f
            ) {
                properties.colorFlowHue = 90f
                properties.colorJitterSaturation = 0.12f
                controlSync.forEach { it() }
            }
            applyChanges()
        }.also { row ->
            controlSync += {
                val switch = findSmoothSwitch(row)
                if (switch != null && switch.isChecked != properties.colorFlowEnabled) {
                    switch.isChecked = properties.colorFlowEnabled
                }
            }
        })
        slider(R.string.brush_setting_color_flow, 0, 360, { properties.colorFlowHue.toInt() },
            { getString(R.string.brush_value_degrees, it) }) { properties.colorFlowHue = it.toFloat() }
        slider(R.string.brush_setting_color_jitter_hue, 0, 100, { (properties.colorJitterHue * 100).toInt() },
            ::percent) { properties.colorJitterHue = it / 100f }
        slider(R.string.brush_setting_color_jitter_sat, 0, 100, { (properties.colorJitterSaturation * 100).toInt() },
            ::percent) { properties.colorJitterSaturation = it / 100f }
        slider(R.string.brush_setting_color_jitter_val, 0, 100, { (properties.colorJitterBrightness * 100).toInt() },
            ::percent) { properties.colorJitterBrightness = it / 100f }

        page(R.string.brush_page_texture)
        section(R.string.brush_section_texture)
        slider(R.string.brush_setting_wetness, 0, 100, { (properties.wetness * 100).toInt() },
            ::percent) { properties.wetness = it / 100f }
        slider(R.string.brush_setting_grain, 0, 100, { (properties.grainScale * 100).toInt() },
            ::percent) { properties.grainScale = it / 100f }

        // What this brush *is*, as opposed to what its numbers are. Reading a stack of percentages
        // never tells you that a preset is a cut reed that answers to the barrel; this does, and it
        // is the page to open when a brush behaves in a way the sliders do not explain.
        page(R.string.brush_page_about)
        section(R.string.brush_page_about)
        val aboutRows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        controls.addView(aboutRows)
        fun refreshAbout() {
            aboutRows.removeAllViews()
            BrushFacts.describe(context, properties).forEach { (label, value) ->
                aboutRows.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(TextView(context).apply {
                        text = label
                        textSize = 12.5f
                        setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(context).apply {
                        text = value
                        textSize = 12.5f
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        gravity = Gravity.END
                    })
                })
            }
        }
        refreshAbout()
        controlSync += { refreshAbout() }
        showPage(0)

        root.addView(PanelUi.filledButton(context, getString(R.string.brush_done)) { dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
        })

        return root
    }

    private fun findSmoothSwitch(view: View): SmoothSwitch? {
        if (view is SmoothSwitch) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findSmoothSwitch(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
