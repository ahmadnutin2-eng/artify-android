package com.procreate.android.ui.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.canvas.CanvasBackgroundStyle
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.export.ExportManager
import com.procreate.android.ui.common.PanelUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActionsPanel : BottomSheetDialogFragment() {

    private val viewModel: CanvasViewModel by activityViewModels()

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importPhotoAsLayer(it) }
    }

    // The bug-report dialog's own attach flow - separate from pickImageLauncher above, which
    // imports a photo as a canvas layer rather than attaching one to a report. Held at class
    // level (not local to showReportIssueDialog) because registerForActivityResult callbacks
    // have to be registered once per fragment, not per dialog invocation.
    private var reportImagePreview: android.widget.ImageView? = null
    private var reportAttachedBitmap: Bitmap? = null
    private val pickReportImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val bitmap = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@registerForActivityResult
        reportAttachedBitmap = bitmap
        reportImagePreview?.setImageBitmap(bitmap)
        reportImagePreview?.visibility = View.VISIBLE
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        fun dp(v: Int) = PanelUi.dp(context, v)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(PanelUi.grabberHandle(context))
        root.addView(PanelUi.panelTitle(context, getString(R.string.control_actions)))

        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)

        val firstLayer = viewModel.layers.value?.firstOrNull()
        val canvasSize = "${firstLayer?.bitmap?.width ?: 0} × ${firstLayer?.bitmap?.height ?: 0} px"
        content.addView(PanelUi.infoRow(context, R.drawable.ic_info, getString(R.string.action_canvas_info), canvasSize))

        content.addView(PanelUi.divider(context))

        content.addView(buildBackgroundPicker(context))

        content.addView(PanelUi.divider(context))

        content.addView(PanelUi.actionRow(context, R.drawable.ic_image, getString(R.string.action_import_photo)) {
            pickImageLauncher.launch("image/*")
        })
        content.addView(PanelUi.actionRow(context, R.drawable.ic_flatten, getString(R.string.action_flatten)) {
            viewModel.flattenLayers()
            dismiss()
        })

        content.addView(PanelUi.divider(context))

        content.addView(PanelUi.actionRow(context, R.drawable.ic_share, getString(R.string.action_export)) {
            exportAndShare()
            dismiss()
        })
        content.addView(PanelUi.actionRow(context, R.drawable.ic_save, getString(R.string.action_save)) {
            (activity as? CanvasActivity)?.saveArtwork()
            Toast.makeText(requireContext(), getString(R.string.action_save), Toast.LENGTH_SHORT).show()
            dismiss()
        })

        content.addView(PanelUi.divider(context))

        // No "Adjustments" row here: the toolbar's own btn_adjustments already opens
        // ui.canvas.AdjustmentsPanel, which was in the app before this work and previews through a
        // ColorMatrixColorFilter - live on the GPU, where a per-pixel version stalls the slider on
        // a large layer. Hue, invert and black-and-white were added to that panel instead of
        // shipping a second one with the same name.
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_blur,
                getString(R.string.filters_title),
                getString(R.string.filters_sub)
            ) {
                com.procreate.android.adjust.FiltersPanel.show(
                    parentFragmentManager,
                    PanelUi.dockSide(arguments, PanelUi.DockSide.LEFT)
                )
                dismiss()
            }
        )

        content.addView(PanelUi.divider(context))
        content.addView(PanelUi.sectionLabel(context, getString(R.string.orientation_title)))
        content.addView(
            PanelUi.infoRow(
                context, R.drawable.ic_info,
                getString(R.string.orientation_sub)
            )
        )

        val canvasView = (activity as? CanvasActivity)?.drawingViewOrNull()
        val orientation = PanelUi.groupedList(context)
        fun orientationRow(labelRes: Int, action: () -> Unit) {
            orientation.addView(
                PanelUi.choiceRow(context, getString(labelRes)) {
                    action()
                    // The sheet stays open: straightening a canvas is usually several taps -
                    // flip, look, flip back - and dismissing after each one would make that
                    // a chore.
                }
            )
            orientation.addView(PanelUi.divider(context))
        }
        orientationRow(R.string.orientation_rotate_left) { canvasView?.rotateCanvasBy(-90f) }
        orientationRow(R.string.orientation_rotate_right) { canvasView?.rotateCanvasBy(90f) }
        orientationRow(R.string.orientation_flip_h) { canvasView?.flipCanvasHorizontally() }
        orientationRow(R.string.orientation_flip_v) { canvasView?.flipCanvasVertically() }
        orientation.addView(
            PanelUi.choiceRow(context, getString(R.string.orientation_reset)) {
                canvasView?.resetCanvasOrientation()
            }
        )
        content.addView(orientation)

        content.addView(PanelUi.divider(context))

        // The second standing entry point to the subscription (the other is on the gallery
        // toolbar). Its subtitle reports state, so a subscriber can confirm at a glance that they
        // are subscribed rather than having to open the sheet to find out.
        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_info,
                "Artify Pro",
                if (com.procreate.android.billing.Entitlements.isSubscribed(context))
                    "اشتراكك نشط — اضغط للإدارة أو الإلغاء"
                else
                    "افتح تصدير DXF لبرامج CAD"
            ) {
                (activity as? CanvasActivity)?.showSubscriptionFromMenu()
                dismiss()
            }
        )

        content.addView(PanelUi.divider(context))

        content.addView(
            PanelUi.actionRow(
                context, R.drawable.ic_info,
                getString(R.string.action_gesture_guide),
                getString(R.string.action_gesture_guide_sub)
            ) {
                (activity as? CanvasActivity)?.showGestureGuide()
                dismiss()
            }
        )

        val drawingView = (activity as? CanvasActivity)?.drawingViewOrNull()

        // ---------------- Paint bucket ----------------
        // Exposed because no single value works for every drawing: tight line art wants a small gap
        // radius so corners stay sharp, hair and fur need a large one.
        content.addView(PanelUi.divider(context))
        content.addView(PanelUi.sectionLabel(context, getString(R.string.fill_title)))

        var fill = drawingView?.fillOptions
            ?: com.procreate.android.tools.FillPrefs.load(context)
        fun commitFill(updated: com.procreate.android.tools.SmartFill.Options) {
            fill = updated
            drawingView?.fillOptions = updated
            com.procreate.android.tools.FillPrefs.save(context, updated)
        }

        content.addView(PanelUi.infoRow(context, R.drawable.ic_info, getString(R.string.fill_gap_sub)))
        content.addView(
            PanelUi.sliderRow(
                context, getString(R.string.fill_gap),
                com.procreate.android.tools.FillPrefs.MAX_GAP, fill.gapClosing,
                valueFormatter = { getString(R.string.fill_px_format, it) }
            ) { value, fromUser -> if (fromUser) commitFill(fill.copy(gapClosing = value)) }.first
        )
        content.addView(
            PanelUi.sliderRow(
                context, getString(R.string.fill_tolerance),
                com.procreate.android.tools.FillPrefs.MAX_TOLERANCE, fill.tolerance
            ) { value, fromUser -> if (fromUser) commitFill(fill.copy(tolerance = value)) }.first
        )
        content.addView(
            PanelUi.sliderRow(
                context, getString(R.string.fill_expand),
                com.procreate.android.tools.FillPrefs.MAX_EXPAND, fill.expand,
                valueFormatter = { getString(R.string.fill_px_format, it) }
            ) { value, fromUser -> if (fromUser) commitFill(fill.copy(expand = value)) }.first
        )
        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_selection,
                getString(R.string.fill_contiguous),
                getString(R.string.fill_contiguous_sub),
                initialChecked = fill.contiguous
            ) { checked -> commitFill(fill.copy(contiguous = checked)) }
        )

        // ibisPaint's "Display when Zoomed: Smooth / Pixelated". On by default; turning it off shows
        // the real pixels, which is what pixel art and pixel-level checking need.
        content.addView(PanelUi.divider(context))
        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_image,
                getString(R.string.display_smooth_title),
                getString(R.string.display_smooth_sub),
                initialChecked = (drawingView?.displayQuality
                    ?: com.procreate.android.canvas.DisplayQualityPrefs.get(context)) ==
                    com.procreate.android.canvas.DisplayQuality.SMOOTH
            ) { smooth ->
                val quality = if (smooth) com.procreate.android.canvas.DisplayQuality.SMOOTH
                else com.procreate.android.canvas.DisplayQuality.PIXELATED
                com.procreate.android.canvas.DisplayQualityPrefs.set(context, quality)
                drawingView?.displayQuality = quality
            }
        )

        content.addView(PanelUi.divider(context))
        content.addView(PanelUi.sectionLabel(context, getString(R.string.drawing_sound_title)))

        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_sound,
                getString(R.string.action_drawing_sound),
                getString(R.string.action_drawing_sound_sub),
                initialChecked = drawingView?.isDrawingSoundEnabled()
                    ?: com.procreate.android.canvas.DrawingSoundPrefs.isEnabled(context)
            ) { enabled ->
                drawingView?.setDrawingSoundEnabled(enabled)
                    ?: com.procreate.android.canvas.DrawingSoundPrefs.setEnabled(context, enabled)
            }
        )

        val currentVol = drawingView?.getDrawingSoundVolume()
            ?: com.procreate.android.canvas.DrawingSoundPrefs.getVolume(context)
        content.addView(
            PanelUi.sliderRow(
                context,
                getString(R.string.drawing_sound_volume),
                100,
                (currentVol * 100).toInt(),
                valueFormatter = { "$it%" }
            ) { progress, _ ->
                val v = progress / 100f
                drawingView?.setDrawingSoundVolume(v)
                    ?: com.procreate.android.canvas.DrawingSoundPrefs.setVolume(context, v)
            }.first
        )

        var activeProfile = drawingView?.getDrawingSoundProfile()
            ?: com.procreate.android.canvas.DrawingSoundPrefs.getProfile(context)
        fun profileTitle(p: com.procreate.android.canvas.DrawingSoundProfile) = when (p) {
            com.procreate.android.canvas.DrawingSoundProfile.AUTO -> "تلقائي حسب الفرشاة"
            com.procreate.android.canvas.DrawingSoundProfile.VELVET_PENCIL -> "قلم رصاص مخملي ناعم"
            com.procreate.android.canvas.DrawingSoundProfile.SMOOTH_MARKER -> "قلم تحبير سلس"
            com.procreate.android.canvas.DrawingSoundProfile.SOFT_BRUSH -> "ريشة فنية هوائية"
        }
        val profileRow = PanelUi.actionRow(
            context, R.drawable.ic_brush,
            "نمط صوت الرسم",
            profileTitle(activeProfile)
        ) {
            val profiles = com.procreate.android.canvas.DrawingSoundProfile.entries
            val next = profiles[(activeProfile.ordinal + 1) % profiles.size]
            activeProfile = next
            drawingView?.setDrawingSoundProfile(next)
                ?: com.procreate.android.canvas.DrawingSoundPrefs.setProfile(context, next)
            Toast.makeText(context, profileTitle(next), Toast.LENGTH_SHORT).show()
        }
        content.addView(profileRow)

        content.addView(PanelUi.divider(context))
        content.addView(PanelUi.sectionLabel(context, "أدوات الرسم الذكية"))

        content.addView(
            PanelUi.switchRow(
                context, R.drawable.ic_transform,
                "الأشكال الذكية (QuickShape)",
                "التحويل التلقائي للأشكال الهندسية النقية كالدائرة والمستطيل عند التوقف",
                initialChecked = drawingView?.isQuickShapeEnabled ?: true
            ) { enabled ->
                drawingView?.isQuickShapeEnabled = enabled
            }
        )

        var activeSym = drawingView?.symmetryMode ?: com.procreate.android.canvas.DrawingView.SymmetryMode.NONE
        fun symTitle(s: com.procreate.android.canvas.DrawingView.SymmetryMode) = when (s) {
            com.procreate.android.canvas.DrawingView.SymmetryMode.NONE -> "بدون تناظر (معطل)"
            com.procreate.android.canvas.DrawingView.SymmetryMode.VERTICAL -> "تناظر رأسي (مرآة رأسية)"
            com.procreate.android.canvas.DrawingView.SymmetryMode.HORIZONTAL -> "تناظر أفقي"
            com.procreate.android.canvas.DrawingView.SymmetryMode.QUAD -> "تناظر رباعي (ماندالا)"
        }
        val symRow = PanelUi.actionRow(
            context, R.drawable.ic_urban_grid,
            getString(R.string.symmetry_guide),
            symTitle(activeSym)
        ) {
            val modes = com.procreate.android.canvas.DrawingView.SymmetryMode.entries
            val next = modes[(activeSym.ordinal + 1) % modes.size]
            activeSym = next
            drawingView?.symmetryMode = next
            Toast.makeText(context, symTitle(next), Toast.LENGTH_SHORT).show()
        }
        content.addView(symRow)

        // Developer-only: compiled out of behavior entirely in a release build (no key, no
        // network permission there either), so this row simply doesn't appear outside a debug
        // build the developer installed directly.
        if (com.procreate.android.debug.AiBugReporter.isAvailable) {
            content.addView(PanelUi.divider(context))
            content.addView(
                PanelUi.actionRow(
                    context, R.drawable.ic_actions,
                    "الإبلاغ عن مشكلة (تجريبي)",
                    "صف ما لاحظته، وسيكتب الذكاء الاصطناعي تقريرًا جاهزًا للنسخ"
                ) {
                    showReportIssueDialog(context)
                }
            )
        }

        // Rows arrive in sequence rather than all at once. It costs nothing, and it gives the eye
        // a starting point instead of a wall of controls appearing complete.
        com.procreate.android.ui.common.PanelMotion.staggerIn(content)
        return root
    }

    /**
     * A short menu of commands, so it opens as a popover hanging off the button that summoned it -
     * the beak keeps the menu visibly attached to its own control. On a screen too small to hang a
     * panel below a toolbar, [PanelUi.popoverSheet] declines and the edge-docked column is used
     * instead, because a cramped floating box is worse than a plain column.
     */
    override fun onStart() {
        super.onStart()
        val popover = PanelUi.popoverSheet(dialog, arguments, widthDp = 400, maxHeightDp = 620)
        if (popover != null) {
            view?.background = popover
            view?.clipToOutline = false
            // Room for the beak, so the title does not sit under it.
            view?.setPadding(0, PanelUi.dp(requireContext(), 10), 0, 0)
        } else {
            PanelUi.dockSheet(dialog, PanelUi.dockSide(arguments, PanelUi.DockSide.LEFT))
        }
    }

    private fun showReportIssueDialog(context: android.content.Context) {
        reportAttachedBitmap = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PanelUi.dp(context, 16), PanelUi.dp(context, 12), PanelUi.dp(context, 16), PanelUi.dp(context, 8))
        }

        val input = android.widget.EditText(context).apply {
            hint = "مثال: الخط تقطّع عند الرسم بسرعة بفرشاة الفحم..."
            minLines = 3
        }
        container.addView(input)

        // Hidden until an image is actually picked - a vision-capable model reads this directly,
        // so a screenshot of a misplaced button or a garbled table says more than trying to
        // describe it in words.
        val preview = android.widget.ImageView(context).apply {
            visibility = View.GONE
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, PanelUi.dp(context, 160)).apply {
                topMargin = PanelUi.dp(context, 10)
            }
        }
        reportImagePreview = preview
        container.addView(preview)

        val btnAttach = TextView(context).apply {
            text = "إرفاق صورة للخطأ"
            textSize = 13f
            setTextColor(context.getColor(R.color.procreate_accent))
            setPadding(0, PanelUi.dp(context, 10), 0, PanelUi.dp(context, 4))
            setOnClickListener { pickReportImageLauncher.launch("image/*") }
        }
        container.addView(btnAttach)

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("الإبلاغ عن مشكلة")
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("إرسال") { _, _ ->
                val note = input.text?.toString()?.trim().orEmpty()
                val screenshot = reportAttachedBitmap
                if (note.isEmpty() && screenshot == null) return@setPositiveButton
                Toast.makeText(context, "الذكاء الاصطناعي يحلل المشكلة الآن...", Toast.LENGTH_SHORT).show()
                com.procreate.android.debug.AiBugReporter.reportManualIssue(context, note, screenshot) { file ->
                    if (file != null) {
                        // Show the AI's actual analysis right here instead of just a filename -
                        // that silent "saved" toast was the whole reason a stale/broken model id
                        // (see AiBugReporter's MODEL constant) went unnoticed: the report was
                        // being written with no analysis in it and nothing on screen said so.
                        val content = runCatching { file.readText() }.getOrDefault("")
                        androidx.appcompat.app.AlertDialog.Builder(context)
                            .setTitle("تحليل المشكلة")
                            .setMessage(content.ifBlank { "تم الحفظ: ${file.name}" })
                            .setPositiveButton("تم", null)
                            .show()
                    } else {
                        Toast.makeText(context, "تعذّر تحليل التقرير - تحقّق من اتصال الجهاز بالإنترنت", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /**
     * A row of paper choices. Switching repaints the background layer only, so it can be changed
     * at any point without disturbing the artwork stacked above it.
     */
    private fun buildBackgroundPicker(context: android.content.Context): View {
        fun dp(v: Int) = PanelUi.dp(context, v)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(PanelUi.sectionLabel(context, getString(R.string.action_background)))

        val options = listOf(
            R.string.background_blank to CanvasBackgroundStyle.BLANK,
            R.string.background_dots to CanvasBackgroundStyle.DOT_GRID,
            R.string.background_grid to CanvasBackgroundStyle.LINE_GRID,
            R.string.background_isometric to CanvasBackgroundStyle.ISOMETRIC
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        val chips = mutableListOf<TextView>()

        fun styleChip(chip: TextView, active: Boolean) {
            chip.background = ContextCompat.getDrawable(
                context, if (active) R.drawable.bg_list_item_selected else R.drawable.bg_field_outline
            )
            chip.setTextColor(
                ContextCompat.getColor(context, if (active) R.color.procreate_accent_light else R.color.text_primary)
            )
        }

        val current = viewModel.backgroundStyle.value ?: CanvasBackgroundStyle.BLANK
        options.forEachIndexed { index, (labelRes, style) ->
            val chip = TextView(context).apply {
                setText(labelRes)
                textSize = 13.5f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (index != options.lastIndex) dp(6) else 0
                }
                setOnClickListener {
                    viewModel.setBackgroundStyle(style)
                    chips.forEachIndexed { i, c -> styleChip(c, i == index) }
                    (activity as? CanvasActivity)?.drawingViewOrNull()?.invalidate()
                }
            }
            styleChip(chip, style == current)
            chips.add(chip)
            row.addView(chip)
        }
        column.addView(row)
        return column
    }

    private fun importPhotoAsLayer(uri: Uri) {
        val canvasLayer = viewModel.layers.value?.firstOrNull()?.bitmap ?: return
        val canvasWidth = canvasLayer.width
        val canvasHeight = canvasLayer.height

        val input = requireContext().contentResolver.openInputStream(uri) ?: return
        val source = input.use { BitmapFactory.decodeStream(it) } ?: return

        // Scale to fit within the canvas while preserving aspect ratio, then center it on a
        // canvas-sized transparent bitmap so it behaves like every other layer.
        val scale = minOf(canvasWidth.toFloat() / source.width, canvasHeight.toFloat() / source.height, 1f)
        val scaledW = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val layerBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layerBitmap)
        canvas.drawBitmap(scaled, (canvasWidth - scaledW) / 2f, (canvasHeight - scaledH) / 2f, null)

        viewModel.addLayerFromBitmap(layerBitmap)
        dismiss()
    }

    private fun exportAndShare() {
        val flattened = viewModel.flattenToBitmap() ?: run {
            Toast.makeText(requireContext(), "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val filename = "artwork_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val exportManager = ExportManager(requireContext())
        val file = exportManager.exportToPNG(flattened, filename)
        exportManager.shareDirectly(file, "image/png")
    }
}
