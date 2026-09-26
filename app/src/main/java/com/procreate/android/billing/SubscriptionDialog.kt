package com.procreate.android.billing

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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi

/**
 * The paywall. Shown when someone reaches a subscriber-only feature, and from settings.
 *
 * Prices come from [BillingManager.plans], which come from Play - never from a string in the app.
 * Play sets the amount and currency per country, so a hardcoded price would be wrong for almost
 * everyone and would also drift the moment the price is edited in the Console.
 */
class SubscriptionDialog : DialogFragment() {

    private var billing: BillingManager? = null
    private var onClosed: (() -> Unit)? = null
    private var plansContainer: LinearLayout? = null
    private var statusView: TextView? = null

    fun configure(billing: BillingManager, onClosed: () -> Unit) = apply {
        this.billing = billing
        this.onClosed = onClosed
    }

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
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }

        root.addView(TextView(context).apply {
            text = "Artify Pro"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })

        val scroll = ScrollView(context)
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        inner.addView(TextView(context).apply {
            text = "الرسم وجميع الفرش وتحليل المخططات بالذكاء الاصطناعي تبقى مجانية. " +
                "يفتح الاشتراك التصدير الهندسي:"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(12), 0, dp(12))
        })

        fun feature(title: String, detail: String) {
            inner.addView(TextView(context).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, dp(8), 0, 0)
            })
            inner.addView(TextView(context).apply {
                text = detail
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setLineSpacing(dp(3).toFloat(), 1f)
            })
        }

        feature(
            "تصدير DXF",
            "افتح عملك في AutoCAD وبرامج CAD الأخرى — بطبقات منفصلة لكل نوع عنصر، " +
                "ومقياس حقيقي بالمتر 1:1، ومفتاح الخريطة والقياسات كما رسمتها."
        )
        feature(
            "صورة مرافقة عالية الدقة",
            "تُصدَّر مع الملف تلقائياً، فتظهر ضربات الفرشاة التي لا تُحوَّل إلى خطوط متجهة."
        )

        statusView = TextView(context).apply {
            text = "جارٍ تحميل الأسعار…"
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(16), 0, dp(8))
        }
        inner.addView(statusView)

        plansContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(plansContainer)

        inner.addView(TextView(context).apply {
            text = "يتجدد الاشتراك تلقائياً. يمكنك إلغاؤه في أي وقت من Google Play، " +
                "ويستمر حتى نهاية المدة المدفوعة."
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(16), 0, 0)
        })

        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(context).apply {
            text = "ليس الآن"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        bindPlans()
        return root
    }

    override fun onStart() {
        super.onStart()
        val manager = billing ?: return
        manager.onPlansLoaded = { activity?.runOnUiThread { bindPlans() } }
        manager.onPurchaseResult = { success, cancelled ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(requireContext(), "تم تفعيل Artify Pro", Toast.LENGTH_LONG).show()
                    onClosed?.invoke()
                    dismiss()
                } else if (!cancelled) {
                    statusView?.text = "تعذّر إتمام الشراء. حاول مرة أخرى."
                }
            }
        }
        manager.start()
    }

    override fun onStop() {
        // Cleared here rather than in onDestroy: the manager outlives this dialog, and a stale
        // callback into a detached fragment is how a purchase completed after dismissal crashes.
        billing?.onPlansLoaded = null
        billing?.onPurchaseResult = null
        super.onStop()
    }

    private fun bindPlans() {
        val context = context ?: return
        val container = plansContainer ?: return
        val manager = billing ?: return
        fun dp(v: Int) = PanelUi.dp(context, v)

        container.removeAllViews()

        // A subscriber opening this from the menu wants to see where they stand and how to cancel,
        // not to be sold the thing they already bought. Play also expects a subscription app to
        // make management reachable from inside the app.
        if (Entitlements.isSubscribed(context)) {
            statusView?.text = "اشتراكك في Artify Pro نشط. شكراً لدعمك."
            container.addView(TextView(context).apply {
                text = "إدارة الاشتراك أو إلغاؤه"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setPadding(dp(20), dp(16), dp(20), dp(16))
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_btn_primary)
                setOnClickListener { openPlaySubscriptions() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
            return
        }

        val plans = manager.plans
        if (plans.isEmpty()) {
            // Not necessarily an error: it is also what an unreleased Play product looks like, so
            // the message says what to check instead of blaming the connection.
            statusView?.text = "الأسعار غير متاحة حالياً. تأكد من اتصالك ومن تسجيل الدخول إلى Google Play."
            return
        }
        statusView?.text = "اختر خطة:"

        val monthly = plans.firstOrNull { !it.isYearly }
        plans.forEach { plan ->
            // Savings are computed from Play's own numbers rather than stated as a fixed
            // percentage, so the badge stays true after any price change in the Console.
            val saving = if (plan.isYearly && monthly != null && monthly.priceMicros > 0) {
                val yearAtMonthly = monthly.priceMicros * 12.0
                val percent = ((yearAtMonthly - plan.priceMicros) / yearAtMonthly * 100).toInt()
                if (percent in 1..99) "  ·  وفّر $percent%" else ""
            } else ""

            container.addView(TextView(context).apply {
                text = if (plan.isYearly) "سنوي — ${plan.formattedPrice}$saving"
                else "شهري — ${plan.formattedPrice}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = dp(56)
                setPadding(dp(20), dp(16), dp(20), dp(16))
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_btn_primary)
                setOnClickListener {
                    val act = activity ?: return@setOnClickListener
                    if (!manager.launchPurchase(act, plan)) {
                        statusView?.text = "تعذّر فتح شاشة الدفع. تأكد من تحديث تطبيق Google Play."
                    }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
    }

    /**
     * Opens Play's page for this exact subscription. Cancellation lives there and only there -
     * Play does not let an app cancel on the user's behalf, so pointing at the right page is the
     * most an app can honestly offer.
     */
    private fun openPlaySubscriptions() {
        val context = context ?: return
        val uri = android.net.Uri.parse(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=${BillingManager.PRODUCT_ID}&package=${context.packageName}"
        )
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(
                    context,
                    "افتح تطبيق Google Play ثم: الحساب ← المدفوعات والاشتراكات",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    companion object {
        fun show(manager: FragmentManager, billing: BillingManager, onClosed: () -> Unit) {
            SubscriptionDialog().configure(billing, onClosed).show(manager, "subscription")
        }
    }
}
