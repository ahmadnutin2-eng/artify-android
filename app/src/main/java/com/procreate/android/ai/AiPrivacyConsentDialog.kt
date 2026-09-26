package com.procreate.android.ai

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi

/**
 * The prominent disclosure shown before the first plan upload, and the affirmative action that
 * records consent. See [AiPrivacyConsent] for why a notice on its own was not enough.
 *
 * The wording is deliberately concrete - which data, to whom, what they may do with it, and what
 * is *not* sent - because a disclosure that only says "data may be shared with third parties"
 * tells the user nothing they can actually decide on.
 */
class AiPrivacyConsentDialog : DialogFragment() {

    private var onDecision: ((Boolean) -> Unit)? = null

    fun configure(onDecision: (Boolean) -> Unit) = apply { this.onDecision = onDecision }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar).apply {
            // Consent must be a decision, not something dismissed by accident with a stray tap.
            setCanceledOnTouchOutside(false)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }

        root.addView(TextView(context).apply {
            text = "قبل رفع الصورة"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })

        fun body(text: String, bold: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = 14f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                ContextCompat.getColor(
                    context, if (bold) R.color.text_primary else R.color.text_secondary
                )
            )
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }

        val scroll = ScrollView(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        inner.addView(body("ماذا يُرسل", bold = true))
        inner.addView(body("الصورة التي تختارها أنت فقط، لحظة اختيارها. لا تُرسل رسوماتك، ولا مشاريعك، ولا أي صورة أخرى من جهازك."))

        inner.addView(body("إلى أين", bold = true))
        inner.addView(body("إلى خوادم Google (نموذج Gemini)، عبر اتصال مشفّر، باستخدام المفتاح الذي أدخلته أنت."))

        inner.addView(body("ما قد يحدث لها هناك", bold = true))
        inner.addView(body("الطبقة المجانية من Gemini تسمح لـ Google باستخدام ما يُرسل إليها لتحسين منتجاتها. إن كان المخطط سرّياً أو يخص عميلاً، لا ترفعه."))

        inner.addView(body("ما لا يجمعه التطبيق", bold = true))
        inner.addView(body("لا يجمع هويتك، ولا موقعك، ولا معرّف جهازك، ولا جهات اتصالك — لا شيء من ذلك موجود في التطبيق أصلاً."))

        inner.addView(body("يمكنك التراجع في أي وقت من «إعدادات الذكاء الاصطناعي»، وبقية أدوات التطبيق تعمل كاملة بدون هذه الميزة."))

        scroll.addView(inner)
        // WRAP_CONTENT, not height=0 with a weight. A dialog window sizes itself to its content, so
        // the root LinearLayout measures as WRAP_CONTENT - and a weighted child in a wrap_content
        // LinearLayout divides up *leftover* space, of which there is none. The scroll view would
        // have measured to zero height and the entire disclosure would have been invisible between
        // the title and the buttons: a consent screen that shows nothing to consent to. The window
        // still caps at the screen, and the scroll view scrolls inside whatever it is given.
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(104)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(
                ContextCompat.getColor(
                    context, if (primary) R.color.text_primary else R.color.text_secondary
                )
            )
            if (primary) setBackgroundResource(R.drawable.bg_btn_primary)
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        // "Not now" is a real, equal choice - not a greyed-out formality next to the accept button.
        row.addView(button("ليس الآن", primary = false) {
            onDecision?.invoke(false)
            dismiss()
        })
        row.addView(button("أوافق على الرفع", primary = true) {
            AiPrivacyConsent.grant(requireContext())
            onDecision?.invoke(true)
            dismiss()
        })
        root.addView(row)

        return root
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        // Backing out is a "no", never an implicit yes.
        onDecision?.invoke(false)
        super.onCancel(dialog)
    }

    companion object {
        fun show(manager: FragmentManager, onDecision: (Boolean) -> Unit) {
            AiPrivacyConsentDialog().configure(onDecision).show(manager, "ai_privacy_consent")
        }
    }
}
