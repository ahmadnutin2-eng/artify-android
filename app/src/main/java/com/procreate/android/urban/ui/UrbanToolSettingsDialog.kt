package com.procreate.android.urban.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.model.UrbanScaleConfig
import com.procreate.android.urban.model.UrbanToolType

/**
 * نافذة حوارية متكاملة لضبط إعدادات وتحجيم الأداة الحضرية النشطة (Urban Tool Customizer).
 * تتيح للمستخدم ضبط:
 * - حجم وسماكة العنصر (Element Size / Stroke Width).
 * - مسافة ترقيم العقد بالمتر (Node Numbering Spacing) لتحديد الكثافة بدقة متساوية.
 * - اختيار الألوان والأنماط المعمارية.
 */
class UrbanToolSettingsDialog(
    private val tool: UrbanToolType,
    private val scaleConfig: UrbanScaleConfig,
    private val onSettingsApplied: (UrbanScaleConfig, Int) -> Unit
) : DialogFragment() {

    private var selectedColor = tool.defaultColor
    private var currentStrokeWidth = scaleConfig.activeToolStrokeWidth
    private var currentNodeDistance = scaleConfig.nodeDistanceMeters

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        fun dp(v: Int) = PanelUi.dp(ctx, v)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
            setBackgroundResource(R.drawable.bg_panel_rounded)
        }

        // 1. عنوان الأداة
        val title = TextView(ctx).apply {
            text = "⚙ إعدادات وتحجيم: ${tool.titleAr}"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "تخصيص أبعاد الأداة، وسماكة الخط، ومسافات الترقيم الهندسية المتساوية"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(subtitle)

        // 2. شريط تحجيم سماكة العنصر / الخط
        val sizeLabel = TextView(ctx).apply {
            text = "سماكة وحجم العنصر: ${currentStrokeWidth.toInt()} بكسل"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        root.addView(sizeLabel)

        val sizeBar = SeekBar(ctx).apply {
            max = 40
            progress = currentStrokeWidth.toInt().coerceIn(2, 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val p = prog.coerceAtLeast(2)
                    currentStrokeWidth = p.toFloat()
                    sizeLabel.text = "سماكة وحجم العنصر: $p بكسل"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            setPadding(0, dp(8), 0, dp(14))
        }
        root.addView(sizeBar)

        // 3. شريط مسافة ترقيم العقد بالمتر (Node Distance)
        val spacingLabel = TextView(ctx).apply {
            text = "مسافة ترقيم العقد: كل ${currentNodeDistance.toInt()} متراً"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        root.addView(spacingLabel)

        val spacingBar = SeekBar(ctx).apply {
            max = 100
            progress = currentNodeDistance.toInt().coerceIn(5, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val p = prog.coerceAtLeast(5)
                    currentNodeDistance = p.toFloat()
                    spacingLabel.text = "مسافة ترقيم العقد: كل $p متراً"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            setPadding(0, dp(8), 0, dp(14))
        }
        root.addView(spacingBar)

        // 4. ألوان سريعة معتمدة في المخططات
        val colorLabel = TextView(ctx).apply {
            text = "لون العنصر:"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(colorLabel)

        val colorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val palette = listOf(
            tool.defaultColor,
            0xFFE53935.toInt(), // أحمر
            0xFF1E88E5.toInt(), // أزرق
            0xFFFF9800.toInt(), // برتقالي
            0xFF43A047.toInt(), // أخضر
            0xFF8E24AA.toInt(), // بنفسجي
            0xFF424242.toInt(), // رمادي داكن
            0xFFD7CCC8.toInt()  // ترابي تراثي
        )
        for (c in palette) {
            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(2), if (c == selectedColor) Color.WHITE else Color.TRANSPARENT)
                }
                setOnClickListener {
                    selectedColor = c
                    for (i in 0 until colorRow.childCount) {
                        val child = colorRow.getChildAt(i)
                        (child.background as? GradientDrawable)?.setStroke(
                            dp(2),
                            if (palette[i] == c) Color.WHITE else Color.TRANSPARENT
                        )
                    }
                }
            }
            colorRow.addView(swatch)
        }
        root.addView(colorRow)

        // 5. زر التأكيد والحفظ (✓ OK)
        val btnOk = Button(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            text = "✓ حفظ وتطبيق الإعدادات (OK)"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_btn_primary)
            setOnClickListener {
                scaleConfig.activeToolStrokeWidth = currentStrokeWidth
                scaleConfig.nodeDistanceMeters = currentNodeDistance
                onSettingsApplied(scaleConfig, selectedColor)
                Toast.makeText(ctx, "تم تطبيق إعدادات: ${tool.titleAr}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
        root.addView(btnOk)

        return AlertDialog.Builder(ctx)
            .setView(root)
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }
}
