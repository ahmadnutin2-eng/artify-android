package com.procreate.android.ai

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi

/**
 * Lets the user supply their own Google Gemini key so plan analysis works in a build that ships no
 * key of its own. See [AiKeyStore] for why the feature is configured rather than hidden.
 *
 * The key is written only to this device's SharedPreferences and is sent nowhere except Google's
 * own API endpoint as the request's auth header.
 */
class AiKeySettingsDialog : DialogFragment() {

    private var onSaved: (() -> Unit)? = null

    fun configure(onSaved: () -> Unit) = apply { this.onSaved = onSaved }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        root.addView(TextView(context).apply {
            text = "إعدادات الذكاء الاصطناعي"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })

        root.addView(TextView(context).apply {
            text = "تحليل المخططات يعمل عبر نموذج Google Gemini. احصل على مفتاح مجاني من " +
                "Google AI Studio والصقه هنا. يُحفظ المفتاح على جهازك فقط ولا يُرسل إلا إلى " +
                "خوادم Google عند التحليل."
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(12), 0, dp(16))
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        if (AiKeyStore.isGeminiFromBuild()) {
            root.addView(TextView(context).apply {
                text = "مفتاح التطوير مُفعّل في هذه النسخة، وله الأولوية على أي مفتاح تُدخله هنا."
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, 0, 0, dp(12))
            })
        }

        val input = EditText(context).apply {
            hint = "الصق مفتاح Gemini هنا"
            // No suggestions/autocorrect: an API key is an opaque token, and an IME "correcting" it
            // silently produces a key that fails auth with no visible cause.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            textSize = 15f
            minHeight = dp(48)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            // A saved key shows masked, so the field proves one is stored without printing it in
            // full on a screen the user may be mirroring or screenshotting.
            AiKeyStore.maskedGemini().takeIf { it.isNotBlank() }?.let { setText(it) }
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }

        fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(96)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (primary) R.color.text_primary else R.color.text_secondary
                )
            )
            if (primary) setBackgroundResource(R.drawable.bg_btn_primary)
            setOnClickListener { onClick() }
        }

        // Withdrawing consent has to be at least as reachable as giving it, so it sits here rather
        // than behind another screen. Shown only once there is a consent to withdraw.
        if (AiPrivacyConsent.hasConsented(context)) {
            root.addView(TextView(context).apply {
                text = "إيقاف رفع الصور"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setOnClickListener {
                    AiPrivacyConsent.revoke(context)
                    Toast.makeText(
                        context,
                        "تم إيقاف رفع الصور. سيُطلب إذنك من جديد عند التحليل القادم.",
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }

        buttonRow.addView(button("كيف أحصل عليه؟", primary = false) {
            openKeyPage(context)
        })
        buttonRow.addView(button("إلغاء", primary = false) { dismiss() })
        buttonRow.addView(button("حفظ", primary = true) {
            val typed = input.text.toString().trim()
            when {
                typed.isBlank() -> Toast.makeText(
                    context, "أدخل المفتاح أولاً", Toast.LENGTH_SHORT
                ).show()
                // The field is pre-filled with a mask, so an untouched field must not overwrite the
                // stored key with a row of bullets that would then fail every request.
                typed.contains('•') -> dismiss()
                else -> {
                    AiKeyStore.setUserGeminiKey(context, typed)
                    Toast.makeText(context, "تم حفظ المفتاح", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                    dismiss()
                }
            }
        })

        root.addView(buttonRow)
        return root
    }

    private fun openKeyPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(
                context, "افتح aistudio.google.com/apikey في المتصفح", Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        fun show(manager: FragmentManager, onSaved: () -> Unit) {
            AiKeySettingsDialog().configure(onSaved).show(manager, "ai_key_settings")
        }
    }
}
