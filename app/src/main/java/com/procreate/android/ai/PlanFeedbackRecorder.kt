package com.procreate.android.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the model's own assessment of each run so it accumulates instead of evaporating.
 *
 * Every analysis produces information about how well the pipeline is working - what it missed, what
 * it fabricated, which prompt wording misfired - and until now all of it vanished the moment the
 * dialog closed. Written to a file, those notes become a record that can be read back later and
 * acted on, rather than the same lesson being rediscovered on the next plan.
 *
 * Notes are the model's opinion, not measurements. They are stored verbatim and clearly attributed
 * so nobody later mistakes a suggestion for a finding.
 */
class PlanFeedbackRecorder(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "plan_feedback").apply { mkdirs() }

    data class RunSummary(
        val detail: String,
        val completedCalls: Int,
        val failedCalls: Int,
        val featureCount: Int,
        val featuresByType: Map<String, Int>,
        val warnings: List<String>,
        val imageWidth: Int,
        val imageHeight: Int
    )

    /**
     * Asks the model to review the run it just performed and writes its answer to disk.
     *
     * Runs after the user already has their result, so a failure here costs nothing: the analysis
     * is complete either way, and a note that could not be written is not worth interrupting anyone
     * over.
     */
    fun record(summary: RunSummary, analysis: String?): File? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(directory, "run_$stamp.md")

        val text = buildString {
            appendLine("# تقرير تحليل مخطط - $stamp")
            appendLine()
            appendLine("## معطيات التشغيل")
            appendLine("- مستوى التفصيل: ${summary.detail}")
            appendLine("- أبعاد الصورة: ${summary.imageWidth} × ${summary.imageHeight} بكسل")
            appendLine("- النداءات: ${summary.completedCalls} (فشل ${summary.failedCalls})")
            appendLine("- العناصر المستخرجة: ${summary.featureCount}")
            if (summary.featuresByType.isNotEmpty()) {
                appendLine()
                appendLine("### التوزيع حسب النوع")
                summary.featuresByType.entries.sortedByDescending { it.value }.forEach { (type, count) ->
                    appendLine("- $type: $count")
                }
            }
            // A run whose image was too small was never going to produce detail, and that belongs
            // in the record next to the result it explains.
            val longestEdge = maxOf(summary.imageWidth, summary.imageHeight)
            if (longestEdge < RESOLUTION_ADVICE_THRESHOLD) {
                appendLine()
                appendLine("> **ملاحظة تلقائية:** الحافة الأطول للصورة $longestEdge بكسل، وهي أقل من " +
                    "$RESOLUTION_ADVICE_THRESHOLD. دقة الإدخال هي العامل المحدِّد الأكبر للجودة، " +
                    "فالتفاصيل غير الموجودة في الصورة لا يمكن استخراجها مهما تحسّن النظام.")
            }
            if (summary.warnings.isNotEmpty()) {
                appendLine()
                appendLine("## ملاحظات المحلّل (${summary.warnings.size})")
                summary.warnings.take(30).forEach { appendLine("- $it") }
                if (summary.warnings.size > 30) appendLine("- … و${summary.warnings.size - 30} أخرى")
            }
            if (!analysis.isNullOrBlank()) {
                appendLine()
                appendLine("## تقييم النموذج للنتيجة")
                appendLine()
                appendLine("> ما يلي رأي النموذج نفسه، لا قياساً موضوعياً.")
                appendLine()
                appendLine(analysis.trim())
            }
        }

        return runCatching {
            file.writeText(text)
            trimOldReports()
            file
        }.getOrNull()
    }

    fun reports(): List<File> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Keeps the newest reports only - this is a working log, not an archive, and it should never
     * grow without bound on the user's device. */
    private fun trimOldReports() {
        val all = reports()
        if (all.size <= MAX_REPORTS) return
        all.drop(MAX_REPORTS).forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val MAX_REPORTS = 20
        private const val RESOLUTION_ADVICE_THRESHOLD = 2000

        /**
         * What the model is asked to write. Framed around what would make the NEXT run better,
         * because a critique of geometry the user can already see is worth less than a concrete
         * change to the extraction itself.
         */
        val REVIEW_PROMPT = """
            You have just finished extracting vector geometry from this site plan for a CAD app.
            IMAGE 1 is the original plan. IMAGE 2 is what the app reconstructed from your output.

            Write a short engineering review for the developer, in Arabic, covering:

            1. الدقة: ما الذي أصبته وما الذي أخطأت فيه في إعادة البناء؟ كن محدداً.
            2. الأسباب: ما سبب كل خطأ - دقة الصورة، أم صياغة التعليمات، أم حد قدرتك؟
            3. تحسينات مقترحة على التعليمات أو على صيغة المخرجات، قابلة للتنفيذ.
            4. ما الذي لا يمكن تحسينه بالتعليمات وحدها ويحتاج تغييراً في المدخلات؟

            Be concrete and honest, including about your own limits. Plain text, no JSON.
            Keep it under 400 words.
        """.trimIndent()
    }
}
