package com.procreate.android.vectorize

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.procreate.android.R
import com.procreate.android.ui.common.PanelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The review step for image vectorisation.
 *
 * Nothing reaches the drawing without an explicit confirmation here. The detected paths are shown
 * over the source image, each tappable, and the detail slider re-runs the trace so a faint or busy
 * scan can be tuned without going back to pick the image again.
 */
class VectorizeReviewDialog : DialogFragment() {

    private var sourceBitmap: Bitmap? = null
    private var onAccepted: ((List<TracedPath>, Int, Int) -> Unit)? = null

    private lateinit var preview: VectorizePreviewView
    private lateinit var summary: TextView
    private var detail = 0.08f
    private var busy = false

    fun configure(bitmap: Bitmap, onAccepted: (List<TracedPath>, Int, Int) -> Unit) = apply {
        this.sourceBitmap = downscaleForTracing(bitmap)
        this.onAccepted = onAccepted
    }

    /**
     * Caps the working image before any analysis runs.
     *
     * A modern tablet camera produces roughly 12 megapixels. Tracing that directly would allocate
     * the pixel buffer plus four float arrays of the same length - well over 200MB - and reliably
     * run the app out of memory. Detail beyond about 1600px on the long edge also does not survive
     * simplification, so the cap costs nothing in output quality. All traced coordinates are then
     * in this scaled space, which is exactly what the caller maps from.
     */
    private fun downscaleForTracing(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_TRACE_DIMENSION) return source
        val factor = MAX_TRACE_DIMENSION.toFloat() / longest
        val width = (source.width * factor).toInt().coerceAtLeast(1)
        val height = (source.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), android.R.style.Theme_Material_NoActionBar_Fullscreen)
    }

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
            text = "تحويل صورة إلى عناصر متجهية"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        root.addView(TextView(context).apply {
            text = "اضغط على أي مسار لقبوله أو رفضه. المسارات المقبولة بلون التمييز، والمرفوضة باهتة."
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(12))
        })

        preview = VectorizePreviewView(context)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        summary = TextView(context).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.procreate_accent_light))
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(summary)
        preview.onSelectionChanged = { acceptedCount, total ->
            summary.text = "محدد: $acceptedCount من $total مسار"
        }

        root.addView(TextView(context).apply {
            text = "مستوى التفاصيل — أعلى يلتقط خطوطاً أخفت وضوضاء أكثر"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            setPadding(0, dp(8), 0, dp(4))
        })
        root.addView(SeekBar(context).apply {
            max = 100
            progress = 8
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                // Re-run only when the finger lifts: tracing a multi-megapixel scan on every
                // intermediate value would make the slider unusable.
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    detail = (sb?.progress ?: 8).coerceIn(1, 60) / 100f
                    runVectorization()
                }
            })
        })

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
            minWidth = dp(110)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(
                ContextCompat.getColor(context, if (primary) R.color.white else R.color.text_secondary)
            )
            setBackgroundResource(
                if (primary) R.drawable.bg_list_item_selected else R.drawable.bg_ripple_flat
            )
            setOnClickListener { action() }
        }
        buttons.addView(button("إلغاء", primary = false) { dismiss() })
        buttons.addView(button("تحديد الكل", primary = false) { preview.selectAll() }
            .apply { (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8) })
        buttons.addView(button("إدراج المحدد", primary = true) { acceptSelection() })
        root.addView(buttons)

        runVectorization()
        return root
    }

    private fun runVectorization() {
        val bitmap = sourceBitmap ?: return
        if (busy) return
        busy = true
        summary.text = "جاري تحليل الصورة…"
        viewLifecycleOwner.lifecycleScope.launch {
            // Tracing is CPU-bound and can take seconds on a large scan, so it never touches the
            // main thread - the dialog stays responsive and cancellable throughout.
            val result = withContext(Dispatchers.Default) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                VectorizationPipeline.run(
                    argb = pixels,
                    width = bitmap.width,
                    height = bitmap.height,
                    options = VectorizationOptions(detail = detail)
                )
            }
            preview.setContent(bitmap, result)
            if (result.totalFound > result.paths.size) {
                summary.text = "${summary.text}  (عُرضت أطول ${result.paths.size} من ${result.totalFound})"
            }
            busy = false
        }
    }

    private fun acceptSelection() {
        val bitmap = sourceBitmap ?: return
        val chosen = preview.acceptedPaths()
        if (chosen.isEmpty()) {
            summary.text = "لم تحدد أي مسار بعد"
            return
        }
        onAccepted?.invoke(chosen, bitmap.width, bitmap.height)
        dismiss()
    }

    companion object {
        private const val TAG = "VectorizeReviewDialog"

        /** Long-edge cap for the traced image. See [downscaleForTracing]. */
        private const val MAX_TRACE_DIMENSION = 1600

        fun show(
            fragmentManager: FragmentManager,
            bitmap: Bitmap,
            onAccepted: (paths: List<TracedPath>, sourceWidth: Int, sourceHeight: Int) -> Unit
        ) {
            VectorizeReviewDialog().configure(bitmap, onAccepted).show(fragmentManager, TAG)
        }
    }
}
