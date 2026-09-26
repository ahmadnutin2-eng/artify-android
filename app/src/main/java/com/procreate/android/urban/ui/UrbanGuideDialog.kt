package com.procreate.android.urban.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi

/**
 * دليل الإرشاد المعماري التفاعلي (Urban Design Onboarding Guide)
 * يشرح للمستخدم خطوة بخطوة كيفية استخدام أدوات التخطيط الحضري والمقياس والشبكة.
 */
class UrbanGuideDialog : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = context.getDrawable(R.drawable.bg_panel_rounded)
        }

        // 1. الترويسة والعنوان
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val title = TextView(context).apply {
            text = "دليل أدوات التخطيط الحضري ودراسات الموقع"
            textSize = 19f
            setTextColor(context.getColor(R.color.white))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(title)
        root.addView(header)

        // 2. خطوات الدليل التوضيحية
        val steps = listOf(
            GuideStep(
                number = "١",
                title = "ضبط مقياس الرسم (Scale Calibration)",
                description = "اختر أداة المعايرة أو حدد مسافة معلومة على صورتك الجوية. يتيح لك النظام حساب الأطوال الحقيقية بالمتر ومساحات الفراغات بالمتر المربع تلقائياً."
            ),
            GuideStep(
                number = "٢",
                title = "الشبكة المساحية وبطاقة المعلومات",
                description = "تظهر شبكة خطوط رأسية وعرضية متساوية. توضح لك البطاقة العائمة مساحة كل مربع (مثلاً 50م × 50م = 2500 م²) مع شريط مقياس بياني."
            ),
            GuideStep(
                number = "٣",
                title = "المحاور والأسهم الذكية بنقاط التدفق",
                description = "ارسم المحاور الرئيسية بنقاط متقاربة ورؤوس مخصصة، مع إمكانية إظهار نقاط متسلسلة خلف رأس السهم لتوضيح اتجاه الرؤية والحركة ومحطات المشاهدة (A, B, C)."
            ),
            GuideStep(
                number = "٤",
                title = "التهشير المعماري وترقيم الساحات",
                description = "ظلل الفراغات والساحات بنمط تهشير مائل 45° أو مزارع نقطية، مع ترقيم تلقائي للساحات (1 إلى 18) في دوائر بيضاء هندسية."
            ),
            GuideStep(
                number = "٥",
                title = "مفتاح الخريطة والجداول التلقائية",
                description = "كل عنصر تستخدمه في الرسم يُضاف تلقائياً إلى مفتاح الخريطة في اللوحة الجانبية، مع توليد جداول حصر المساحات والنسب المئوية بضغطة زر."
            )
        )

        val scroll = android.widget.ScrollView(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        for (step in steps) {
            content.addView(buildStepView(context, step))
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 3. زر البدء
        val btnClose = Button(context).apply {
            text = "ابدأ العمل الآن"
            setTextColor(context.getColor(R.color.white))
            setBackgroundResource(R.drawable.bg_btn_primary)
            setOnClickListener {
                dismiss()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(16) }
        root.addView(btnClose, btnParams)

        return root
    }

    private fun buildStepView(context: Context, step: GuideStep): View {
        fun dp(v: Int) = PanelUi.dp(context, v)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val badge = TextView(context).apply {
            text = step.number
            textSize = 16f
            setTextColor(context.getColor(R.color.white))
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_badge_circle)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(12)
            }
        }
        container.addView(badge)

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(context).apply {
            text = step.title
            textSize = 16f
            setTextColor(context.getColor(R.color.white))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val descView = TextView(context).apply {
            text = step.description
            textSize = 13.5f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        }
        textLayout.addView(titleView)
        textLayout.addView(descView)
        container.addView(textLayout)

        return container
    }

    data class GuideStep(val number: String, val title: String, val description: String)

    companion object {
        fun show(fragmentManager: FragmentManager) {
            UrbanGuideDialog().show(fragmentManager, "UrbanGuideDialog")
        }
    }
}
