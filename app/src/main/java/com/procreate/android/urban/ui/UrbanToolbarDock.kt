package com.procreate.android.urban.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.model.UrbanCategory
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanInputMode
import com.procreate.android.urban.model.UrbanScaleConfig
import com.procreate.android.urban.model.UrbanToolType

/**
 * شريط الأدوات العائم الذكي للتخطيط الحضري (Contextual Urban Toolbar Dock).
 * يتكيف تلقائياً:
 * 1. نمط الاستعراض العام (Default Navigation): يظهر أدوات الشبكة والمقياس والجداول والمفتاح والتصدير.
 * 2. نمط الأداة النشطة (Active Drawing Tool): يُخفي العناصر العامة ويركز فقط على الأداة المختارة
 *    مع أزرار سريعة للتحكم بالمقاس (Size [-] [+])، والمسافة (Spacing [-] [+])، واللون، وإنهاء الرسم.
 */
class UrbanToolbarDock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private fun dp(v: Int) = PanelUi.dp(context, v)

    // Callbacks
    var onExitUrbanMode: (() -> Unit)? = null
    var onOpenUrbanPanel: (() -> Unit)? = null
    var onToggleInputMode: ((UrbanInputMode) -> Unit)? = null
    var onStartScaleCalibration: (() -> Unit)? = null
    var onStartCalibrationFromElement: (() -> Unit)? = null
    var onDeleteSelectedElement: (() -> Unit)? = null
    var onClearSelectedElement: (() -> Unit)? = null
    var onEnterSelectionMode: (() -> Unit)? = null
    var onToggleGrid: (() -> Unit)? = null
    var onToggleLegend: (() -> Unit)? = null
    var onCycleLegendPlacement: (() -> Unit)? = null
    var onToggleTables: (() -> Unit)? = null
    var onCycleTablePlacement: (() -> Unit)? = null
    var onCycleScaleCardPlacement: (() -> Unit)? = null
    var onOpenToolSettings: (() -> Unit)? = null
    var onOpenExportDialog: (() -> Unit)? = null
    var onExportOverlaysRequested: (() -> Unit)? = null
    var onFinishPolygon: (() -> Unit)? = null
    var onUndoPoint: (() -> Unit)? = null
    var onClearPoints: (() -> Unit)? = null
    var onToolSizeChanged: ((Float) -> Unit)? = null
    var onNodeDistanceChanged: ((Float) -> Unit)? = null
    var onToolColorPickRequested: (() -> Unit)? = null
    var onDeactivateTool: (() -> Unit)? = null
    var onSelectedElementSizeChanged: ((Float) -> Unit)? = null
    var onSelectedElementColorPickRequested: (() -> Unit)? = null

    private var currentMode = UrbanInputMode.POINT_BY_POINT
    private var activeTool: UrbanToolType? = null
    private var scaleConfig: UrbanScaleConfig? = null
    private var compactMode = false

    // Layout containers
    private val defaultBarGroup: LinearLayout
    private val activeToolBarGroup: LinearLayout

    // Active tool specific views
    private val txtActiveToolName: TextView
    private val btnInputModeActive: Button
    private val txtToolSize: TextView
    private val txtNodeSpacing: TextView
    private val spacingContainer: LinearLayout
    private val colorSwatchView: View
    private val nodeActionsGroup: LinearLayout
    private val txtNodeCount: TextView
    private val selectionInfoGroup: LinearLayout
    private val txtSelectionInfo: TextView
    private lateinit var txtSelectedSize: TextView
    private lateinit var selectedColorSwatchView: View
    private var selectedSizeValue: Float = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_toolbar_pill)
        elevation = dp(8).toFloat()
        setPadding(dp(8), dp(4), dp(8), dp(4))

        // ==================== 1. المجموعة الافتراضية (Default Bar Group) ====================
        defaultBarGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            // Intentionally empty here: every child of this group is built below, after the
            // removeAllViews() call, as compact icon quick-actions. A previous version also
            // constructed a full set of emoji text buttons at this point, which that same call
            // discarded a few lines later - so they cost layout work on every dock creation and
            // could never appear on screen.
        }
        addView(defaultBarGroup)

        // الهاتف لا يتسع لشريط من عشرة أزرار نصية. نحتفظ بكل الوظائف في لوحة الأدوات،
        // ونقدّم هنا اختصارات بصرية ثابتة يسهل تمييزها أثناء الرسم.
        defaultBarGroup.removeAllViews()
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_close, "خروج", R.color.text_secondary) {
            onExitUrbanMode?.invoke()
        })
        // "تحديد" comes first and stays visually distinct (accent tint) - a plain, always-visible
        // affordance for "you can tap an existing shape to edit it", instead of that only being
        // discoverable by already having no tool active or by trying it and finding out it works.
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_urban_select, "تحديد", R.color.procreate_accent) {
            onEnterSelectionMode?.invoke()
        })
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_urban_map, "أدوات", R.color.procreate_accent) {
            onOpenUrbanPanel?.invoke()
        })
        defaultBarGroup.addView(createDivider())
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_urban_scale, "مقياس") {
            onStartScaleCalibration?.invoke()
        })
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_urban_grid, "شبكة") {
            onToggleGrid?.invoke()
        })
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_layers, "مفتاح") {
            onToggleLegend?.invoke()
        })
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_urban_table, "جداول") {
            onToggleTables?.invoke()
        })
        // زر واحد مختصر لتدوير مواضع الثلاثة عناصر (المقياس/المفتاح/الجداول) معاً - كان تدوير
        // كل عنصر مخبأً خلف ضغطة طويلة منفصلة على زر إظهاره/إخفائه (غير مكتشف أصلاً)، وكان هناك
        // أيضاً قسم مكرر في لوحة الأدوات السفلية لنفس الغرض بالضبط. زر واحد هنا يفتح قائمة صغيرة
        // بثلاثة أزرار بسيطة يكفي.
        val btnPositions = createUrbanQuickAction(R.drawable.ic_urban_position, "مواضع") {}
        btnPositions.setOnClickListener { showPlacementPopup(btnPositions) }
        defaultBarGroup.addView(btnPositions)
        defaultBarGroup.addView(createDivider())
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_share, "تصدير", R.color.procreate_accent) {
            onOpenExportDialog?.invoke()
        })
        // A dedicated one-tap export for just the scale card + legend + tables together, on
        // their own sheet at 2x resolution - separate from "تصدير" above, which exports the map
        // itself (with or without these baked onto it via includeOverlays).
        defaultBarGroup.addView(createUrbanQuickAction(R.drawable.ic_urban_table, "تصدير الجداول", R.color.procreate_accent) {
            onExportOverlaysRequested?.invoke()
        })

        // ==================== 2. مجموعة الأداة النشطة المركزة (Active Tool Bar Group) ====================
        activeToolBarGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE

            // زر إلغاء اختيار الأداة والعودة للشريط العام
            val btnBack = TextView(context).apply {
                text = "✕ إنهاء"
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    exitToolDrawingMode()
                    onDeactivateTool?.invoke()
                }
            }
            addView(btnBack)
            addView(createDivider())

            // اسم الأداة مع إمكانية التبديل بنقرة
            val toolBtn = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.selector_icon_button)
                minimumHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { onOpenUrbanPanel?.invoke() }

                val ic = ImageView(context).apply {
                    setImageResource(R.drawable.ic_urban_map)
                    imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.procreate_accent))
                    layoutParams = LayoutParams(dp(20), dp(20))
                }
                addView(ic)

                // The one label on this bar that answers "what am I about to draw", so it is
                // deliberately the largest thing on it. Everything else was the same 12sp, which
                // left the bar with no focal point at all.
                txtActiveToolName = TextView(context).apply {
                    text = "أداة الرسم"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(8), 0, dp(4), 0)
                }
                addView(txtActiveToolName)
            }
            addView(toolBtn)
            addView(createDivider())

            // نمط الإدخال (نقر الأركان / سحب حر)
            btnInputModeActive = Button(context).apply {
                text = "نقر الأركان"
                applyDockIcon(R.drawable.ic_urban_position)
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    currentMode = if (currentMode == UrbanInputMode.POINT_BY_POINT) {
                        UrbanInputMode.CONTINUOUS_DRAG
                    } else {
                        UrbanInputMode.POINT_BY_POINT
                    }
                    val pointByPoint = currentMode == UrbanInputMode.POINT_BY_POINT
                    text = if (pointByPoint) "نقر" else "سحب"
                    applyDockIcon(if (pointByPoint) R.drawable.ic_urban_position else R.drawable.ic_brush)
                    onToggleInputMode?.invoke(currentMode)
                }
            }
            addView(btnInputModeActive)
            addView(createDivider())

            // تحكم سريع بحجم العنصر / السماكة (Size Stepper)
            val sizeGroup = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)

                val btnMinus = createSmallStepperButton("－") {
                    val cfg = scaleConfig ?: return@createSmallStepperButton
                    cfg.activeToolStrokeWidth = (cfg.activeToolStrokeWidth - 2f).coerceAtLeast(2f)
                    updateSizeText()
                    onToolSizeChanged?.invoke(cfg.activeToolStrokeWidth)
                }
                addView(btnMinus)

                txtToolSize = TextView(context).apply {
                    text = "الحجم: 14"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { onOpenToolSettings?.invoke() }
                }
                addView(txtToolSize)

                val btnPlus = createSmallStepperButton("＋") {
                    val cfg = scaleConfig ?: return@createSmallStepperButton
                    cfg.activeToolStrokeWidth = (cfg.activeToolStrokeWidth + 2f).coerceAtMost(80f)
                    updateSizeText()
                    onToolSizeChanged?.invoke(cfg.activeToolStrokeWidth)
                }
                addView(btnPlus)
            }
            addView(sizeGroup)
            addView(createDivider())

            // تحكم سريع بمسافة الترقيم بالمتر (Node Spacing Stepper)
            spacingContainer = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)

                val btnMinus = createSmallStepperButton("－") {
                    val cfg = scaleConfig ?: return@createSmallStepperButton
                    cfg.nodeDistanceMeters = (cfg.nodeDistanceMeters - 5f).coerceAtLeast(5f)
                    updateSpacingText()
                    onNodeDistanceChanged?.invoke(cfg.nodeDistanceMeters)
                }
                addView(btnMinus)

                txtNodeSpacing = TextView(context).apply {
                    text = "المسافة: 20م"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { onOpenToolSettings?.invoke() }
                }
                addView(txtNodeSpacing)

                val btnPlus = createSmallStepperButton("＋") {
                    val cfg = scaleConfig ?: return@createSmallStepperButton
                    cfg.nodeDistanceMeters = (cfg.nodeDistanceMeters + 5f).coerceAtMost(200f)
                    updateSpacingText()
                    onNodeDistanceChanged?.invoke(cfg.nodeDistanceMeters)
                }
                addView(btnPlus)
            }
            addView(spacingContainer)
            addView(createDivider())

            // مربع اللون السريع للأداة
            val colorGroup = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { onToolColorPickRequested?.invoke() }

                colorSwatchView = View(context).apply {
                    layoutParams = LayoutParams(dp(16), dp(16))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.RED)
                        setStroke(dp(1), Color.WHITE)
                    }
                }
                addView(colorSwatchView)
            }
            addView(colorGroup)

            // أزرار التحكم بالنقاط (تظهر عند وجود نقاط نشطة)
            nodeActionsGroup = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(dp(4), 0, dp(2), 0)

                addView(createDivider())

                txtNodeCount = TextView(context).apply {
                    text = "0 نقطة"
                    textSize = 11f
                    setTextColor(context.getColor(R.color.procreate_accent))
                    setPadding(0, 0, dp(4), 0)
                }
                addView(txtNodeCount)

                val btnDone = Button(context).apply {
                    text = "✓ إتمام"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.bg_btn_primary)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    setOnClickListener { onFinishPolygon?.invoke() }
                }
                addView(btnDone)

                val btnUndo = Button(context).apply {
                    text = "↩"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setBackgroundResource(R.drawable.selector_icon_button)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnClickListener { onUndoPoint?.invoke() }
                }
                addView(btnUndo)

                val btnCancel = Button(context).apply {
                    text = "✕"
                    textSize = 11f
                    setTextColor(context.getColor(R.color.text_secondary))
                    setBackgroundResource(R.drawable.selector_icon_button)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    setOnClickListener { onClearPoints?.invoke() }
                }
                addView(btnCancel)
            }
            addView(nodeActionsGroup)
        }
        addView(activeToolBarGroup)

        // ==================== 3. شريط إعدادات العنصر المحدد ====================
        // عنصر شقيق مستقل عن المجموعتين أعلاه، لا داخل إحداهما - كان معشّشاً داخل
        // defaultBarGroup فقط، فكان يختفي تماماً كلما كانت أداة رسم نشطة (activeToolBarGroup
        // ظاهرة بدلاً منه)، رغم أن تحديد عنصر مرسوم مسبقاً للتعديل ممكن الآن حتى وأداة نشطة
        // (انظر CanvasActivity/DrawingView: النقر على عنصر موجود يتجاوز الأداة النشطة مؤقتاً
        // طالما لا يوجد رسم شكل جديد قيد التنفيذ). ظهوره/اختفاؤه مستقل تماماً عن أي المجموعتين
        // الأخريين ظاهرة حالياً.
        selectionInfoGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundResource(R.drawable.selector_icon_button)
            setPadding(dp(8), dp(4), dp(4), dp(4))

            txtSelectionInfo = TextView(context).apply {
                text = "محدد"
                textSize = 12f
                setTextColor(context.getColor(R.color.procreate_accent))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, dp(6), 0)
            }
            addView(txtSelectionInfo)

            // A compact completion affordance makes this a self-contained contextual bar:
            // users do not need to hunt for empty canvas space to return to drawing tools.
            val btnDone = TextView(context).apply {
                text = "تم"
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(7), dp(3), dp(7), dp(3))
                setOnClickListener { onClearSelectedElement?.invoke() }
            }
            addView(btnDone)

            val btnSizeMinus = createSmallStepperButton("－") {
                selectedSizeValue = (selectedSizeValue - 2f).coerceAtLeast(2f)
                updateSelectedSizeText()
                onSelectedElementSizeChanged?.invoke(selectedSizeValue)
            }
            addView(btnSizeMinus)

            txtSelectedSize = TextView(context).apply {
                text = "الحجم: -"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(dp(4), 0, dp(4), 0)
            }
            addView(txtSelectedSize)

            val btnSizePlus = createSmallStepperButton("＋") {
                selectedSizeValue = (selectedSizeValue + 2f).coerceAtMost(200f)
                updateSelectedSizeText()
                onSelectedElementSizeChanged?.invoke(selectedSizeValue)
            }
            addView(btnSizePlus)

            selectedColorSwatchView = View(context).apply {
                layoutParams = LayoutParams(dp(16), dp(16)).apply { marginStart = dp(8); marginEnd = dp(6) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                    setStroke(dp(1), Color.WHITE)
                }
                setOnClickListener { onSelectedElementColorPickRequested?.invoke() }
            }
            addView(selectedColorSwatchView)

            val btnDeleteSelected = Button(context).apply {
                text = ""
                applyDockIcon(R.drawable.ic_delete)
                contentDescription = "حذف العنصر المحدد"
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { onDeleteSelectedElement?.invoke() }
            }
            addView(btnDeleteSelected)
        }
        addView(selectionInfoGroup)
    }

    /**
     * One consistent leading-icon treatment for every dock button, replacing the per-button emoji
     * these used to carry: an emoji renders at whatever size and colour the system font picks, so
     * it never matched the app's own icon set or tint, and it shifted between devices. Using
     * *Relative* bounds keeps the icon on the correct side under RTL.
     */
    private fun Button.applyDockIcon(iconRes: Int, tint: Int = Color.WHITE) {
        val icon = context.getDrawable(iconRes)?.mutate() ?: return
        icon.setTint(tint)
        icon.setBounds(0, 0, dp(16), dp(16))
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = dp(6)
    }

    /**
     * A group separator, not decoration. It used to be a bright 18dp line with 3dp of air on each
     * side, repeated eight times across one row - close enough to the controls that the eye read
     * the whole bar as undifferentiated stripes. Widening the margins does the grouping work and
     * lets the line itself drop to a hairline.
     */
    private fun createDivider(): View {
        return View(context).apply {
            layoutParams = LayoutParams(dp(1), dp(24)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
            setBackgroundColor(context.getColor(R.color.hairline_color))
        }
    }

    /** A compact icon-and-label control sized for a phone toolbar.  The text remains visible so
     * the symbols stay learnable rather than becoming an opaque row of glyphs. */
    private fun createUrbanQuickAction(
        iconRes: Int,
        label: String,
        tintRes: Int = R.color.icon_color,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        // 48dp is the Material minimum touch target; this was 42dp wide with 2dp of vertical
        // padding, which is both hard to hit and visually cramped on a tablet held at arm's length.
        minimumWidth = dp(52)
        minimumHeight = dp(48)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundResource(R.drawable.selector_icon_button)
        contentDescription = label
        setOnClickListener { onClick() }
        if (onLongClick != null) {
            setOnLongClickListener {
                onLongClick()
                true
            }
        }
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(tintRes))
            layoutParams = LayoutParams(dp(20), dp(20))
        })
        addView(TextView(context).apply {
            text = label
            // Was 9sp - below Material's smallest label size and genuinely unreadable at tablet
            // viewing distance, which is a large part of why this bar felt like visual noise.
            textSize = 11f
            setTextColor(context.getColor(tintRes))
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        })
    }

    /** One small popup, anchored under the "مواضع" button, replacing what used to be two
     * separate and half-hidden ways to move the scale card/legend/tables: a long-press gesture
     * nobody found, and a duplicate three-row section buried at the bottom of the tools panel. */
    private fun showPlacementPopup(anchor: View) {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundResource(R.drawable.bg_toolbar_pill)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        lateinit var popup: android.widget.PopupWindow

        fun row(iconRes: Int, label: String, action: () -> Unit): View {
            return LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(10), dp(8), dp(14), dp(8))
                setOnClickListener {
                    action()
                    popup.dismiss()
                }
                addView(ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.procreate_accent))
                    layoutParams = LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    setTextColor(Color.WHITE)
                })
            }
        }

        column.addView(row(R.drawable.ic_urban_scale, "موضع المقياس") { onCycleScaleCardPlacement?.invoke() })
        column.addView(row(R.drawable.ic_layers, "موضع المفتاح") { onCycleLegendPlacement?.invoke() })
        column.addView(row(R.drawable.ic_urban_table, "موضع الجداول") { onCycleTablePlacement?.invoke() })

        popup = android.widget.PopupWindow(
            column,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            // A PopupWindow with no background can fail to dismiss on an outside tap on some
            // Android versions - a transparent one is the standard fix.
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        popup.showAsDropDown(anchor, 0, dp(4))
    }

    /** A stepper is tapped repeatedly to nudge a value, so an undersized target here costs more
     * than anywhere else on the bar - a miss interrupts the adjustment entirely. */
    private fun createSmallStepperButton(label: String, action: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.selector_icon_button)
            minWidth = dp(40)
            minHeight = dp(40)
            minimumWidth = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { action() }
        }
    }

    private fun updateSizeText() {
        val size = scaleConfig?.activeToolStrokeWidth?.toInt() ?: 14
        txtToolSize.text = "الحجم: $size"
    }

    private fun updateSpacingText() {
        val dist = scaleConfig?.nodeDistanceMeters?.toInt() ?: 20
        txtNodeSpacing.text = "المسافة: ${dist}م"
    }

    /**
     * تحديث الأداة النشطة وتفعيل النمط المركز
     */
    fun updateActiveTool(tool: UrbanToolType, config: UrbanScaleConfig) {
        this.activeTool = tool
        this.scaleConfig = config

        // Spacing is the padding's job, not a literal space character in the string.
        txtActiveToolName.text = tool.titleAr
        updateSizeText()
        updateSpacingText()

        // إظهار المسافة فقط للأدوات المعتمدة على المحطات والعقد
        val hasSpacing = !compactMode &&
            (tool.category == UrbanCategory.AXES || tool.category == UrbanCategory.BOUNDARIES)
        spacingContainer.visibility = if (hasSpacing) View.VISIBLE else View.GONE

        // تحديث لون الأداة
        val c = if (config.activeToolColor != 0) config.activeToolColor else tool.defaultColor
        (colorSwatchView.background as? GradientDrawable)?.setColor(c)

        // التحويل للشريط المركز
        defaultBarGroup.visibility = View.GONE
        activeToolBarGroup.visibility = View.VISIBLE
    }

    /**
     * الخروج من نمط الأداة المركزة والعودة للشريط العام
     */
    fun exitToolDrawingMode() {
        activeTool = null
        activeToolBarGroup.visibility = View.GONE
        defaultBarGroup.visibility = View.VISIBLE
        nodeActionsGroup.visibility = View.GONE
    }

    fun updateColorSwatch(color: Int) {
        scaleConfig?.activeToolColor = color
        (colorSwatchView.background as? GradientDrawable)?.setColor(color)
    }

    /** Shows/hides the selection settings bar in the default bar as an element is tapped for
     * editing or deselected - only meaningful while no drawing tool is focused. [currentSize] is
     * whatever [com.procreate.android.canvas.DrawingView.selectedUrbanElementSize] reports for
     * that element (stroke width / hatch spacing / marker radius), so the stepper starts from the
     * element's real current value instead of a stale or default one. */
    fun updateSelectedElementInfo(element: UrbanElement?, currentSize: Float?) {
        if (element == null) {
            selectionInfoGroup.visibility = View.GONE
            // Restore whichever work mode was active before selection.  The contextual bar is
            // deliberately separate so it never competes for width with the main toolbar.
            if (activeTool == null) {
                defaultBarGroup.visibility = View.VISIBLE
                activeToolBarGroup.visibility = View.GONE
            } else {
                defaultBarGroup.visibility = View.GONE
                activeToolBarGroup.visibility = View.VISIBLE
            }
            return
        }
        // Selecting an element replaces the broad toolbar with one calm, thin editing strip.
        // This keeps resize, colour and delete immediately reachable even on narrow screens.
        defaultBarGroup.visibility = View.GONE
        activeToolBarGroup.visibility = View.GONE
        selectionInfoGroup.visibility = View.VISIBLE
        txtSelectionInfo.text = "✓ ${element.toolType.titleAr}"
        selectedSizeValue = currentSize ?: 0f
        updateSelectedSizeText()
        (selectedColorSwatchView.background as? GradientDrawable)?.setColor(element.color)
    }

    private fun updateSelectedSizeText() {
        txtSelectedSize.text = "الحجم: ${selectedSizeValue.toInt()}"
    }

    fun updateNodeCount(count: Int) {
        if (count > 0) {
            nodeActionsGroup.visibility = View.VISIBLE
            txtNodeCount.text = "$count نقطة"
        } else {
            nodeActionsGroup.visibility = View.GONE
        }
    }

    /**
     * Keeps the Urban workspace usable on a landscape phone. The full tablet bar remains intact;
     * only duplicated secondary shortcuts are hidden because the Tools panel exposes the same
     * controls. Primary actions and all in-progress drawing actions stay visible.
     */
    fun setCompactMode(compact: Boolean) {
        compactMode = compact
        if (!compact) return

        // Default group: legend, tables, placement and overlay-only export are also in Tools.
        intArrayOf(6, 7, 8, 9, 11).forEach { index ->
            defaultBarGroup.getChildAt(index)?.visibility = View.GONE
        }
        // Active tool: input mode and node spacing remain available in tool settings.
        intArrayOf(4, 5, 8, 9).forEach { index ->
            activeToolBarGroup.getChildAt(index)?.visibility = View.GONE
        }
        setPadding(dp(5), dp(3), dp(5), dp(3))
    }
}
