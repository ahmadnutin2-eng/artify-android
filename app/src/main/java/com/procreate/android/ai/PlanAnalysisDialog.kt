package com.procreate.android.ai

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Consent, then analysis, then review.
 *
 * The consent step is not a formality: unlike every other feature in this app, this one uploads the
 * user's drawing to a third party, and on a free tier that provider may train on it. Someone
 * working on a real site plan has to be able to make that decision knowingly, each time, before
 * anything leaves the device - so the upload only happens after an explicit tap, and the provider
 * is named.
 */
class PlanAnalysisDialog : DialogFragment() {

    private var sourceBitmap: Bitmap? = null
    private var onAccepted: ((List<DetectedFeature>, Int, Int) -> Unit)? = null

    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var actionButton: TextView
    private lateinit var scanView: PlanScanView
    private val selected = mutableSetOf<Int>()
    private var analysis: PlanAnalysis? = null
    private var analysing = false
    private var selectedDetail = PlanAnalysisOrchestrator.Detail.THOROUGH
    private var analysisJob: kotlinx.coroutines.Job? = null

    fun configure(bitmap: Bitmap, onAccepted: (List<DetectedFeature>, Int, Int) -> Unit) = apply {
        this.sourceBitmap = bitmap
        this.onAccepted = onAccepted
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), android.R.style.Theme_Material_NoActionBar_Fullscreen)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.addView(TextView(context).apply {
            text = "تحليل المخطط بالذكاء الاصطناعي"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })

        val client = PlanVisionClient()
        val providers = client.availableProviders()

        statusView = TextView(context).apply {
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, dp(12))
            // A message aimed at whoever is actually reading it. Telling a Play user to edit
            // local.properties and rebuild describes a machine they do not have; the route that
            // works for them is entering their own key, which AiKeySettingsDialog handles.
            text = if (providers.isEmpty()) {
                "لم يتم إعداد مفتاح الذكاء الاصطناعي بعد.\n" +
                    "افتح إعدادات الذكاء الاصطناعي وأدخل مفتاح Google Gemini الخاص بك (مجاني)."
            } else {
                "سيتم رفع صورة المخطط إلى: ${providers.first().displayName}\n\n${providers.first().privacyNote}"
            }
        }
        root.addView(statusView)

        // The detail choice is a real trade the user has to make: a 4x4 survey is 65 requests
        // against a free allowance of about 250 a day, so it is offered up front with its cost
        // stated rather than buried or chosen silently on their behalf.
        if (providers.isNotEmpty()) {
            root.addView(TextView(context).apply {
                text = "مستوى التفصيل"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(4), 0, dp(6))
            })
            val detailRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val estimateView = TextView(context).apply {
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                setPadding(0, dp(4), 0, dp(10))
            }
            val chips = mutableListOf<TextView>()
            fun refreshEstimate() {
                val bmp = sourceBitmap
                val calls = if (bmp != null) selectedDetail.estimateCalls(bmp.width, bmp.height) else 0
                estimateView.text = "≈ $calls نداء · الحد المجاني نحو 250 نداءً يومياً"
            }
            PlanAnalysisOrchestrator.Detail.entries.forEach { option ->
                val chip = TextView(context).apply {
                    text = option.arabicLabel
                    textSize = 13.5f
                    minHeight = dp(44)
                    minimumHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnClickListener {
                        selectedDetail = option
                        chips.forEach { c ->
                            val isActive = c.text == option.arabicLabel
                            c.setBackgroundResource(
                                if (isActive) R.drawable.bg_list_item_selected else R.drawable.bg_field_outline
                            )
                            c.setTextColor(
                                ContextCompat.getColor(
                                    context,
                                    if (isActive) R.color.procreate_accent_light else R.color.text_primary
                                )
                            )
                        }
                        refreshEstimate()
                    }
                }
                val isDefault = option == selectedDetail
                chip.setBackgroundResource(
                    if (isDefault) R.drawable.bg_list_item_selected else R.drawable.bg_field_outline
                )
                chip.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (isDefault) R.color.procreate_accent_light else R.color.text_primary
                    )
                )
                chips += chip
                detailRow.addView(chip, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) })
            }
            root.addView(detailRow)
            root.addView(estimateView)
            refreshEstimate()
        }

        // Side by side rather than stacked: a plan is the thing being judged here, and on a tablet
        // in landscape a stacked layout gave it barely half the height while the list sat mostly
        // empty. The list is narrow by nature - short rows of text - so it takes the smaller share.
        scanView = PlanScanView(context)
        sourceBitmap?.let { scanView.setImage(it) }

        listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val workArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // The layout is stated left-to-right so "image on the right" holds regardless of the
            // RTL locale flipping the rest of the dialog.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        workArea.addView(
            NestedScrollView(context).apply { addView(listContainer) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        workArea.addView(
            scanView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2.2f).apply {
                marginStart = dp(12)
            }
        )
        root.addView(
            workArea,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        fun button(label: String, primary: Boolean, action: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(120)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(ContextCompat.getColor(context, if (primary) R.color.white else R.color.text_secondary))
            setBackgroundResource(if (primary) R.drawable.bg_list_item_selected else R.drawable.bg_ripple_flat)
            setOnClickListener { action() }
        }

        // Cancelling a 65-call run must actually stop it, not just close the window and leave the
        // requests burning through the day's quota in the background.
        buttons.addView(button("إلغاء", primary = false) {
            analysisJob?.cancel()
            dismiss()
        })
        actionButton = button("تحليل الصورة", primary = true) { onActionPressed() }
        if (providers.isEmpty()) actionButton.isEnabled = false
        buttons.addView(actionButton.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
        })
        root.addView(buttons)

        return root
    }

    private fun onActionPressed() {
        if (analysis == null) runAnalysis() else acceptSelection()
    }

    private fun runAnalysis() {
        val bitmap = sourceBitmap ?: return
        if (analysing) return
        analysing = true
        actionButton.isEnabled = false
        listContainer.removeAllViews()

        val detail = selectedDetail
        val calls = detail.estimateCalls(bitmap.width, bitmap.height)
        statusView.text = "بدء التحليل (${detail.arabicLabel})\nعدد النداءات المتوقعة: $calls"

        scanView.startScanning()

        analysisJob = viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                PlanAnalysisOrchestrator().analyze(
                    bitmap = bitmap,
                    detail = detail,
                    onFeaturesFound = { batch ->
                        // Drawn as they arrive, so the plan visibly rebuilds during the run rather
                        // than appearing all at once when it ends.
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            scanView.addFeatures(batch)
                        }
                    }
                ) { progress ->
                    // Hopping back to the main thread per update keeps the bar honest: a long run
                    // that showed nothing until it finished would be indistinguishable from a hang.
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        statusView.text = buildString {
                            append("${progress.completedCalls}/${progress.totalCalls} · ${progress.stage}")
                            append("\nعناصر حتى الآن: ${progress.featuresSoFar}")
                        }
                    }
                }
            }
            analysing = false
            actionButton.isEnabled = true
            scanView.finishScanning()
            showOutcome(outcome)
            recordFeedback(outcome, bitmap, detail)
        }
    }

    private fun showOutcome(outcome: PlanAnalysisOrchestrator.Outcome) {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        analysis = PlanAnalysis(outcome.features, outcome.warnings)
        listContainer.removeAllViews()
        selected.clear()

        statusView.text = buildString {
            append("اكتمل: ${outcome.completedCalls} نداء")
            if (outcome.failedCalls > 0) append(" · فشل ${outcome.failedCalls}")
            append("\nعُثر على ${outcome.features.size} عنصراً")
            if (outcome.legend.isNotEmpty()) append(" · قُرئ مفتاح الخريطة (${outcome.legend.size} سطراً)")
            if (outcome.warnings.isNotEmpty()) {
                append("\n\nملاحظات (${outcome.warnings.size}):")
                outcome.warnings.take(3).forEach { append("\n• $it") }
            }
        }

        if (outcome.features.isEmpty()) {
            actionButton.text = "إعادة التحليل"
            analysis = null
            return
        }

        // Grouped by kind so a result with two hundred elements can actually be reviewed, rather
        // than presented as one flat list nobody will read to the end of.
        outcome.features.withIndex()
            .groupBy { it.value.feature }
            .forEach { (feature, entries) ->
                listContainer.addView(TextView(context).apply {
                    text = "${feature.arabicLabel} (${entries.size})"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, R.color.procreate_accent_light))
                    setPadding(0, dp(12), 0, dp(4))
                })
                entries.forEach { (index, detected) ->
                    if (detected.confidence >= 0.5f) selected += index
                    listContainer.addView(CheckBox(context).apply {
                        isChecked = detected.confidence >= 0.5f
                        text = buildString {
                            append(detected.note ?: feature.arabicLabel)
                            append("  (${detected.geometry.name.lowercase()}, ")
                            append("${(detected.confidence * 100).toInt()}%)")
                        }
                        textSize = 13f
                        minHeight = dp(44)
                        minimumHeight = dp(44)
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected += index else selected -= index
                        }
                    })
                }
            }
        actionButton.text = "إدراج المحدد (${selected.size})"
    }

    private fun showFeatures(result: PlanVisionClient.Result.Success) {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)
        val features = result.analysis.features

        listContainer.removeAllViews()
        selected.clear()

        val summary = buildString {
            append("${result.provider.displayName}: عُثر على ${features.size} عنصراً")
            if (result.analysis.warnings.isNotEmpty()) {
                append("\nاستُبعد ${result.analysis.warnings.size}:")
                result.analysis.warnings.take(4).forEach { append("\n• $it") }
            }
        }
        statusView.text = summary

        if (features.isEmpty()) {
            // Showing what the model actually said turns "it didn't work" into something the user
            // can report and I can act on, instead of a dead end.
            result.analysis.rawModelText?.take(400)?.let { raw ->
                statusView.text = "${statusView.text}\n\nرد النموذج:\n$raw"
            }
            actionButton.text = "إعادة التحليل"
            analysis = null
            return
        }

        features.forEachIndexed { index, detected ->
            // Confident findings start selected so the common path is review-then-confirm, but
            // every one remains individually reversible.
            if (detected.confidence >= 0.5f) selected += index
            listContainer.addView(CheckBox(context).apply {
                isChecked = detected.confidence >= 0.5f
                text = buildString {
                    append(detected.feature.arabicLabel)
                    detected.note?.let { append(" — $it") }
                    append("  (${detected.geometry.name.lowercase()}, ")
                    append("ثقة ${(detected.confidence * 100).toInt()}%)")
                }
                textSize = 14f
                minHeight = dp(48)
                minimumHeight = dp(48)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += index else selected -= index
                }
            })
        }
        actionButton.text = "إدراج المحدد"
    }

    /**
     * Files the model's review of the run it just did.
     *
     * Runs after the user already has their result and never blocks them: this is a developer log,
     * so a failed review is a missing note, not a failed analysis. It costs one extra request, and
     * only when there is actually something to review.
     */
    private fun recordFeedback(
        outcome: PlanAnalysisOrchestrator.Outcome,
        source: android.graphics.Bitmap,
        detail: PlanAnalysisOrchestrator.Detail
    ) {
        if (outcome.features.isEmpty()) return
        val reconstruction = scanView.renderReconstruction() ?: return
        val context = context ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val review = withContext(Dispatchers.IO) {
                runCatching { PlanVisionClient().reviewReconstruction(source, reconstruction) }.getOrNull()
            }
            withContext(Dispatchers.IO) {
                PlanFeedbackRecorder(context).record(
                    summary = PlanFeedbackRecorder.RunSummary(
                        detail = detail.arabicLabel,
                        completedCalls = outcome.completedCalls,
                        failedCalls = outcome.failedCalls,
                        featureCount = outcome.features.size,
                        featuresByType = outcome.features
                            .groupingBy { it.feature.arabicLabel }
                            .eachCount(),
                        warnings = outcome.warnings,
                        imageWidth = source.width,
                        imageHeight = source.height
                    ),
                    analysis = review
                )
            }
        }
    }

    private fun acceptSelection() {
        val bitmap = sourceBitmap ?: return
        val features = analysis?.features ?: return
        val chosen = features.filterIndexed { index, _ -> index in selected }
        if (chosen.isEmpty()) {
            statusView.text = "لم تحدد أي عنصر."
            return
        }
        onAccepted?.invoke(chosen, bitmap.width, bitmap.height)
        dismiss()
    }

    companion object {
        private const val TAG = "PlanAnalysisDialog"

        fun show(
            fragmentManager: FragmentManager,
            bitmap: Bitmap,
            onAccepted: (features: List<DetectedFeature>, sourceWidth: Int, sourceHeight: Int) -> Unit
        ) {
            PlanAnalysisDialog().configure(bitmap, onAccepted).show(fragmentManager, TAG)
        }
    }
}
