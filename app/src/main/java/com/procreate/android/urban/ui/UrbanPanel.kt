package com.procreate.android.urban.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.legend.DynamicLegendManager
import com.procreate.android.urban.legend.LegendRenderer
import com.procreate.android.urban.model.UrbanScaleConfig
import com.procreate.android.urban.model.UrbanToolType
import com.procreate.android.urban.tables.SpatialTableGenerator

/**
 * اللوحة الرئيسية لأدوات التخطيط الحضري ودراسات الموقع (Urban Design Panel).
 * تتيح الوصول الكامل للمقياس، والشبكة، والمحاور، والتهشير، ومفتاح الخريطة، وجداول الحصر.
 */
class UrbanPanel(
    private val scaleConfig: UrbanScaleConfig,
    private val legendManager: DynamicLegendManager,
    private val onToolSelected: (UrbanToolType) -> Unit,
    private val onScaleConfigChanged: (UrbanScaleConfig) -> Unit,
    private val onStampBitmap: (android.graphics.Bitmap) -> Unit,
    /** Live read of whatever's actually drawn on the plan right now, so the plaza/pathway
     * tables report the real site instead of illustrative sample numbers. */
    private val urbanElementsProvider: () -> List<com.procreate.android.urban.model.UrbanElement>,
    private val onMeasurementToolRequested: () -> Unit,
    private val onAssetLibraryRequested: () -> Unit,
    /** Read live rather than captured, so reopening the panel always shows the real current state. */
    private val snapProvider: () -> com.procreate.android.urban.tools.SnapSettings,
    private val onSnapSettingsChanged: (com.procreate.android.urban.tools.SnapSettings) -> Unit,
    private val onVectorizeImageRequested: () -> Unit,
    private val onAnalyzePlanRequested: () -> Unit
) : BottomSheetDialogFragment() {

    private val toolRows = linkedMapOf<UrbanToolType, View>()
    private var selectedTool: UrbanToolType? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        toolRows.clear()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_panel_rounded)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(PanelUi.grabberHandle(context))
        root.addView(PanelUi.panelTitle(context, "أدوات التخطيط الحضري (Urban Design)"))

        val scroll = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)

        // 1. شريط إرشادات ومقياس الرسم
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_info,
                "دليل استخدام أدوات التخطيط الحضري",
                "تعرف على كيفية ضبط المقياس والمحاور وجداول الحصر"
            ) {
                UrbanGuideDialog.show(parentFragmentManager)
                dismiss()
            }
        )

        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_adjustments,
                "معايرة مقياس الرسم والشبكة (Scale)",
                "الحالي: المربع = ${scaleConfig.gridCellSizeMeters.toInt()}م × ${scaleConfig.gridCellSizeMeters.toInt()}م"
            ) {
                ScaleCalibrationDialog(scaleConfig) { updated ->
                    onScaleConfigChanged(updated)
                }.show(parentFragmentManager, "ScaleCalibrationDialog")
                dismiss()
            }
        )

        val gridStatus = if (scaleConfig.isGridVisible) "مفعلة" else "معطلة"
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_selection,
                "شبكة المربعات المساحية (Grid)",
                "حالة الشبكة: $gridStatus"
            ) {
                scaleConfig.isGridVisible = !scaleConfig.isGridVisible
                onScaleConfigChanged(scaleConfig)
                Toast.makeText(context, if (scaleConfig.isGridVisible) "تم تفعيل الشبكة" else "تم إخفاء الشبكة", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        )

        val cardStatus = if (scaleConfig.isScaleCardVisible) "ظاهرة" else "مخفية"
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_actions,
                "بطاقة مقياس الرسم والمساحة (Scale Card)",
                "توضح مساحة المربع ושريط المقياس: $cardStatus"
            ) {
                scaleConfig.isScaleCardVisible = !scaleConfig.isScaleCardVisible
                onScaleConfigChanged(scaleConfig)
                dismiss()
            }
        )

        content.addView(PanelUi.divider(context))

        // 2. فئات الأدوات الحضرية
        content.addView(buildSectionHeader(context, "أدوات المحاور والأسهم (Axes & Sightlines)"))
        content.addView(buildToolRow(context, UrbanToolType.PRIMARY_AXIS, "محور رئيسي أزرق بنقاط متقاربة ورؤوس ونقاط تدفق"))
        content.addView(buildToolRow(context, UrbanToolType.SECONDARY_AXIS, "محور ثانوي أحمر متقطع ورؤوس أسهم متفرعة"))
        content.addView(buildToolRow(context, UrbanToolType.ENTRY_ARROW, "أسهم مداخل القرية العريضة"))
        content.addView(buildToolRow(context, UrbanToolType.REGIONAL_ROAD, "مسارات واتجاهات إقليمية (إلى أبها / إلى تمنيه)"))
        content.addView(buildToolRow(context, UrbanToolType.STATION_BADGE, "محطات رؤية بصرية (A, B, C)"))
        content.addView(buildToolRow(context, UrbanToolType.SITE_BOUNDARY, "خط حدود الموقع المتقطع مع عقد مرقمة"))
        content.addView(buildToolRow(context, UrbanToolType.CONTOUR_LINE, "خطوط مناسيب (كونتور) لطبوغرافيا الموقع"))

        content.addView(PanelUi.divider(context))

        content.addView(buildSectionHeader(context, "الساحات والتهشير المعماري (Plazas & Hatching)"))
        content.addView(buildToolRow(context, UrbanToolType.PLAZA_HATCH, "الساحات العامة والفراغات (تهشير مائل 45° وترقيم 1-18)"))
        content.addView(buildToolRow(context, UrbanToolType.FARM_HATCH, "مزارع ومساحات خضراء (تهشير نقطي)"))
        content.addView(buildToolRow(context, UrbanToolType.DIRT_PATH, "ممرات ترابية وشداخات داخلية"))
        content.addView(buildToolRow(context, UrbanToolType.ASPHALT_ROAD, "طرق أسفلتية معبدة"))
        content.addView(buildToolRow(context, UrbanToolType.HERITAGE_BUILDING, "كتل مباني تراثية (بيج ترابي)"))
        content.addView(buildToolRow(context, UrbanToolType.MODERN_BUILDING, "كتل مباني حديثة (أصفر)"))

        content.addView(PanelUi.divider(context))

        content.addView(buildSectionHeader(context, "البنية التحتية والتشوّه البصري (Site Elements)"))
        content.addView(buildToolRow(context, UrbanToolType.INFRA_LIGHT, "أعمدة إنارة (رمز شعاعي)"))
        content.addView(buildToolRow(context, UrbanToolType.INFRA_MANHOLE, "مانهول صرف صحي"))
        content.addView(buildToolRow(context, UrbanToolType.INFRA_WATER, "خزان مياه / بئر"))
        content.addView(buildToolRow(context, UrbanToolType.INFRA_ELECTRIC, "محول / كابينة كهرباء"))
        content.addView(buildToolRow(context, UrbanToolType.POLLUTION_RUIN, "مباني متهدمة تمثل تشوه بصري"))
        content.addView(buildToolRow(context, UrbanToolType.POLLUTION_WIRES, "شبكة خطوط كهرباء هوائية عشوائية"))
        content.addView(buildToolRow(context, UrbanToolType.POLLUTION_SHED, "مظلات سيارات غير مناسبة للطابع"))
        content.addView(buildToolRow(context, UrbanToolType.POLLUTION_TRASH, "حاويات قمامة غير ملائمة للطابع"))
        content.addView(buildToolRow(context, UrbanToolType.POLLUTION_POLE, "أعمدة كهرباء منتشرة غير منتظمة"))

        content.addView(PanelUi.divider(context))

        content.addView(buildSectionHeader(context, "التحويل الآلي (Auto-Trace)"))
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_image,
                "تحويل صورة مخطط إلى عناصر متجهية",
                "استخراج الحدود والطرق من صورة، مع مراجعة كل مسار قبل إدراجه"
            ) {
                onVectorizeImageRequested()
                dismiss()
            }
        )

        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_info,
                "تحليل المخطط بالذكاء الاصطناعي",
                "يفهم الطرق والمباني والساحات ويرسمها بأدواتها — يرفع الصورة لخادم خارجي"
            ) {
                onAnalyzePlanRequested()
                dismiss()
            }
        )

        // Reachable at any time, not only on the first run: a key can expire, hit its quota, or be
        // replaced, and without a standing entry point the only way back to it would be to revoke
        // the stored one. Shown in every build so the developer build can check what is configured.
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_info,
                "إعدادات الذكاء الاصطناعي",
                if (com.procreate.android.ai.AiKeyStore.hasAnyKey())
                    "المفتاح مُعدّ — اضغط لتغييره"
                else
                    "أدخل مفتاح Gemini المجاني لتفعيل تحليل المخططات"
            ) {
                com.procreate.android.ai.AiKeySettingsDialog.show(parentFragmentManager) {}
                dismiss()
            }
        )

        content.addView(PanelUi.divider(context))

        content.addView(buildSectionHeader(context, "الالتقاط (Snap)"))
        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_urban_select,
                "الالتقاط إلى العقد الموجودة",
                "يلتصق المؤشر بزوايا العناصر المرسومة مسبقاً",
                initialChecked = snapProvider().vertex
            ) { enabled -> onSnapSettingsChanged(snapProvider().copy(vertex = enabled)) }
        )
        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_urban_grid,
                "الالتقاط إلى الشبكة",
                "يلتصق المؤشر بتقاطعات مربعات الشبكة",
                initialChecked = snapProvider().grid
            ) { enabled -> onSnapSettingsChanged(snapProvider().copy(grid = enabled)) }
        )
        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_urban_position,
                "الالتقاط إلى منتصف الأضلاع",
                "يلتصق المؤشر بمنتصف كل ضلع مرسوم",
                initialChecked = snapProvider().midpoint
            ) { enabled -> onSnapSettingsChanged(snapProvider().copy(midpoint = enabled)) }
        )
        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_transform,
                "تقييد الزوايا (Ortho 45°)",
                "يجبر كل ضلع جديد على أقرب زاوية من مضاعفات 45 درجة",
                initialChecked = snapProvider().ortho
            ) { enabled -> onSnapSettingsChanged(snapProvider().copy(ortho = enabled)) }
        )

        content.addView(PanelUi.divider(context))

        content.addView(buildSectionHeader(context, "القياس ومكتبة الموارد (Measure & Assets)"))
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_urban_scale,
                "أداة القياس (Measure)",
                "قياس مسافة أو مساحة بوحدة قابلة للاختيار وفق مقياس المشروع"
            ) {
                onMeasurementToolRequested()
                dismiss()
            }
        )
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_image,
                "مكتبة الموارد المعمارية (Assets)",
                "أشجار وسيارات وأشخاص وأثاث حضري بأبعاد مترية حقيقية"
            ) {
                onAssetLibraryRequested()
                dismiss()
            }
        )

        content.addView(PanelUi.divider(context))

        // 3. مفتاح الخريطة والجداول التلقائية
        content.addView(buildSectionHeader(context, "مفتاح الخريطة والجداول الإحصائية"))

        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_share,
                "إدراج مفتاح الخريطة (Legend Card)",
                "إلصاق مفتاح الخريطة المولد تلقائياً كبطاقة على اللوحة"
            ) {
                val items = legendManager.legendItems.value ?: emptyList()
                val legendBmp = LegendRenderer.createLegendBitmap(items)
                if (legendBmp != null) {
                    onStampBitmap(legendBmp)
                    Toast.makeText(context, "تم إدراج مفتاح الخريطة على اللوحة", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "لم يتم رسم أي عناصر بعد لتوليد المفتاح!", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }
        )

        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_layers,
                "توليد جدول مساحات الساحات العامة",
                "حساب مساحة كل ساحة (م²) والنسبة المئوية % والإجمالي"
            ) {
                val plazas = urbanElementsProvider().filterIsInstance<com.procreate.android.urban.model.UrbanElement.HatchPolygon>()
                    .filter { it.toolType == UrbanToolType.PLAZA_HATCH }
                if (plazas.isEmpty()) {
                    Toast.makeText(context, "لم ترسم أي ساحة عامة بعد على اللوحة", Toast.LENGTH_SHORT).show()
                } else {
                    val tableBmp = SpatialTableGenerator.generatePlazaTableBitmap(plazas)
                    onStampBitmap(tableBmp)
                    Toast.makeText(context, "تم إدراج جدول الساحات العامة على اللوحة", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }
        )

        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_save,
                "توليد جدول مساحات الممرات والمسارات",
                "حساب مساحة الممرات الأسفلتية والترابية المرسومة فعلياً ونسبها المئوية"
            ) {
                val hatches = urbanElementsProvider().filterIsInstance<com.procreate.android.urban.model.UrbanElement.HatchPolygon>()
                val asphaltArea = hatches.filter { it.toolType == UrbanToolType.ASPHALT_ROAD }.sumOf { it.areaSqMeters.toDouble() }.toFloat()
                val dirtArea = hatches.filter { it.toolType == UrbanToolType.DIRT_PATH }.sumOf { it.areaSqMeters.toDouble() }.toFloat()
                if (asphaltArea <= 0f && dirtArea <= 0f) {
                    Toast.makeText(context, "لم ترسم أي ممر أسفلتي أو ترابي بعد على اللوحة", Toast.LENGTH_SHORT).show()
                } else {
                    val tableBmp = SpatialTableGenerator.generateOtherAreasTableBitmap(
                        listOf("ممرات أسفلتية" to asphaltArea, "ممرات ترابية" to dirtArea).filter { it.second > 0f }
                    )
                    onStampBitmap(tableBmp)
                    Toast.makeText(context, "تم إدراج جدول الممرات على اللوحة", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }
        )

        content.addView(buildLegendEditSection(context))

        // Where to move the scale/legend/table cards used to be a section here too - now a
        // single "مواضع" button in the toolbar dock opens one small popup for all three, so this
        // long scrollable panel doesn't also carry a second, easy-to-miss copy of the same three
        // controls (see UrbanToolbarDock.showPlacementPopup).

        com.procreate.android.ui.common.PanelMotion.staggerIn(content)
        return root
    }

    /** Landscape bottom sheets open collapsed by default - see PanelUi.expandSheet. */
    override fun onStart() {
        super.onStart()
        PanelUi.dockSheet(dialog, PanelUi.DockSide.LEFT, widthDp = 440)
    }

    private fun buildSectionHeader(context: android.content.Context, title: String): View {
        fun dp(v: Int) = PanelUi.dp(context, v)
        return TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(context.getColor(R.color.procreate_accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_urban_section_header)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(14), dp(14), dp(14), dp(4))
            }
        }
    }

    /** A live, editable list of what's actually in the legend right now - renaming an item or
     * hiding it from the printed legend used to be possible only in [DynamicLegendManager]'s own
     * code (updateItemName/toggleVisibility existed but nothing in the UI ever called them). */
    private fun buildLegendEditSection(context: android.content.Context): View {
        fun dp(v: Int) = PanelUi.dp(context, v)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildSectionHeader(context, "عناصر مفتاح الخريطة الحالية (تعديل الأسماء والإظهار)"))

        val items = legendManager.legendItems.value.orEmpty()
        if (items.isEmpty()) {
            val empty = TextView(context).apply {
                text = "لا توجد عناصر مرسومة بعد لتظهر في المفتاح"
                textSize = 12.5f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(20), dp(2), dp(20), dp(10))
            }
            column.addView(empty)
            return column
        }

        for (item in items) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(6), dp(16), dp(6))
            }
            val swatch = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(item.color)
                    setStroke(dp(1), android.graphics.Color.WHITE)
                }
            }
            row.addView(swatch)

            val label = TextView(context).apply {
                text = item.nameAr
                textSize = 13f
                alpha = if (item.isVisibleInLegend) 1f else 0.4f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            val btnRename = TextView(context).apply {
                text = "✏"
                textSize = 15f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener {
                    val input = android.widget.EditText(context).apply {
                        setText(item.nameAr)
                        setSelection(text.length)
                    }
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle("إعادة تسمية عنصر المفتاح")
                        .setView(input)
                        .setPositiveButton("حفظ") { _, _ ->
                            val newName = input.text?.toString()?.trim().orEmpty()
                            if (newName.isNotEmpty()) {
                                legendManager.updateItemName(item.toolType, newName)
                                label.text = newName
                            }
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
            }
            row.addView(btnRename)

            val btnVisibility = android.widget.ImageView(context).apply {
                setImageResource(if (item.isVisibleInLegend) R.drawable.ic_eye else R.drawable.ic_eye_off)
                imageTintList = ContextCompat.getColorStateList(context, R.color.icon_tint_selector)
                contentDescription = "إظهار أو إخفاء العنصر في المفتاح"
                background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setOnClickListener {
                    // toggleVisibility mutates this same LegendItem object in place (it's read
                    // straight from the manager's live list, not a copy), so the new state is
                    // read back from it afterward rather than re-derived here.
                    legendManager.toggleVisibility(item.toolType)
                    val nowVisible = item.isVisibleInLegend
                    setImageResource(if (nowVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
                    label.alpha = if (nowVisible) 1f else 0.4f
                }
            }
            row.addView(btnVisibility)

            column.addView(row)
        }
        return column
    }

    private fun buildToolRow(
        context: android.content.Context,
        tool: UrbanToolType,
        subtitle: String
    ): View {
        fun dp(value: Int) = PanelUi.dp(context, value)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(3)
                bottomMargin = dp(3)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_urban_tool_row)
            isClickable = true
            isFocusable = true
            contentDescription = "${tool.titleAr}. $subtitle"
            setPadding(dp(8), dp(8), dp(14), dp(8))
        }

        row.addView(UrbanToolPreviewView(context).apply {
            this.tool = tool
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = dp(14)
            }
        }
        textColumn.addView(TextView(context).apply {
            text = tool.titleAr
            setTextAppearance(R.style.TextAppearance_App_RowLabel)
        })
        textColumn.addView(TextView(context).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            textSize = 12.5f
        })
        row.addView(textColumn)

        row.isSelected = tool == selectedTool
        row.setOnClickListener {
            updateSelectedTool(tool)
            onToolSelected(tool)
            Toast.makeText(context, "تم اختيار: ${tool.titleAr}", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        toolRows[tool] = row
        return row
    }

    /** Updates the highlighted row when the host restores or changes the active Urban tool. */
    fun updateSelectedTool(tool: UrbanToolType?) {
        selectedTool = tool
        toolRows.forEach { (type, row) -> row.isSelected = type == tool }
    }
}
