package com.procreate.android.ui.library

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.procreate.android.R
import com.procreate.android.canvas.CanvasBackground
import com.procreate.android.canvas.CanvasBackgroundStyle
import com.procreate.android.ui.common.PanelMotion
import com.procreate.android.ui.common.PanelUi
import com.procreate.android.urban.assets.ArchitecturalAssetMetadata
import com.procreate.android.urban.assets.ArchitecturalAssetPreviewView
import com.procreate.android.urban.assets.BuiltInArchitecturalAssets

/** One ordered home for every built-in paper and architectural asset. */
class AssetLibraryDialog : DialogFragment() {
    private var onBackground: ((CanvasBackgroundStyle) -> Unit)? = null
    private var onAsset: ((ArchitecturalAssetMetadata) -> Unit)? = null
    private var onImportImage: (() -> Unit)? = null
    private var onOpenOnlineLibrary: (() -> Unit)? = null

    fun onBackgroundSelected(callback: (CanvasBackgroundStyle) -> Unit) = apply { onBackground = callback }
    fun onAssetSelected(callback: (ArchitecturalAssetMetadata) -> Unit) = apply { onAsset = callback }
    fun onImportImageSelected(callback: () -> Unit) = apply { onImportImage = callback }
    fun onOnlineLibrarySelected(callback: () -> Unit) = apply { onOpenOnlineLibrary = callback }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_panel_rounded)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(PanelUi.panelTitle(context, getString(R.string.library_title)))
        root.addView(TextView(context).apply {
            setText(R.string.library_subtitle)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(16), 0, dp(16), dp(10))
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        if (onImportImage != null) {
            content.addView(sectionTitle(getString(R.string.library_import)))
            content.addView(PanelUi.actionRow(
                context,
                R.drawable.ic_image,
                getString(R.string.action_import_photo),
                getString(R.string.library_import_photo_sub)
            ) {
                dismiss()
                onImportImage?.invoke()
            })
            if (onOpenOnlineLibrary != null) {
                content.addView(PanelUi.actionRow(
                    context,
                    R.drawable.ic_online,
                    getString(R.string.library_online_title),
                    getString(R.string.library_online_subtitle)
                ) {
                    dismiss()
                    onOpenOnlineLibrary?.invoke()
                })
            }
        }
        content.addView(sectionTitle(getString(R.string.library_backgrounds)))
        val paperGrid = GridLayout(context).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val papers = listOf(
            CanvasBackgroundStyle.BLANK to R.string.background_blank,
            CanvasBackgroundStyle.DOT_GRID to R.string.background_dots,
            CanvasBackgroundStyle.LINE_GRID to R.string.background_grid,
            CanvasBackgroundStyle.ISOMETRIC to R.string.background_isometric
        )
        papers.forEach { (style, label) ->
            paperGrid.addView(paperCard(style, getString(label)), GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(paperGrid)

        content.addView(sectionTitle(getString(R.string.library_architectural_assets)).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        val assetGrid = GridLayout(context).apply {
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        BuiltInArchitecturalAssets.catalog.assets.forEach { asset ->
            assetGrid.addView(assetCard(asset), GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        content.addView(assetGrid)

        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(PanelUi.filledButton(context, getString(R.string.library_close)) { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                gravity = Gravity.END
                setMargins(dp(16), dp(8), dp(16), dp(8))
            })

        return Dialog(context, android.R.style.Theme_Material_Dialog_NoActionBar).apply {
            setContentView(root)
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            minOf(dp(820), resources.displayMetrics.widthPixels - dp(32)),
            minOf(dp(650), resources.displayMetrics.heightPixels - dp(36))
        )
    }

    private fun sectionTitle(label: String) = TextView(requireContext()).apply {
        text = label
        textSize = 15f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun paperCard(style: CanvasBackgroundStyle, label: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(132)
            background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(PaperPreview(context, style), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(86)
            ))
            addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setPadding(0, dp(7), 0, 0)
            })
            setOnClickListener { dismiss(); onBackground?.invoke(style) }
            PanelMotion.press(this)
        }
    }

    private fun assetCard(asset: ArchitecturalAssetMetadata): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(92)
            background = ContextCompat.getDrawable(context, R.drawable.bg_list_item_ripple)
            setPadding(dp(8), dp(8), dp(10), dp(8))
            addView(ArchitecturalAssetPreviewView(context).apply { this.asset = asset },
                LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(TextView(context).apply {
                    text = asset.nameAr
                    textSize = 13f
                    maxLines = 2
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                })
                addView(TextView(context).apply {
                    text = asset.nameEn
                    textSize = 11f
                    maxLines = 1
                    setTextColor(ContextCompat.getColor(context, R.color.text_hint))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { dismiss(); onAsset?.invoke(asset) }
            PanelMotion.press(this)
        }
    }

    private fun dp(value: Int) = PanelUi.dp(requireContext(), value)

    private class PaperPreview(context: android.content.Context, private val style: CanvasBackgroundStyle) : View(context) {
        private var bitmap: Bitmap? = null
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density
            color = 0x33000000
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            bitmap?.recycle()
            bitmap = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                CanvasBackground.paint(it, style, CanvasBackground.pitchFor(w, h))
            } else null
        }

        override fun onDraw(canvas: Canvas) {
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        }

        override fun onDetachedFromWindow() {
            bitmap?.recycle()
            bitmap = null
            super.onDetachedFromWindow()
        }
    }
}
