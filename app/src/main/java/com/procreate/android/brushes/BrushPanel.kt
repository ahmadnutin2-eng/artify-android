package com.procreate.android.brushes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.procreate.android.R
import com.procreate.android.canvas.BrushProperties
import com.procreate.android.canvas.BrushTipType
import com.procreate.android.canvas.BrushType
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.canvas.ToolMode
import com.procreate.android.database.ArtworkDatabase
import com.procreate.android.database.CustomBrushEntity
import com.procreate.android.database.CustomBrushRepository
import com.procreate.android.ui.common.PanelGlass
import com.procreate.android.ui.common.PanelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BrushPanel : BottomSheetDialogFragment() {

    private val viewModel: CanvasViewModel by activityViewModels()
    private lateinit var repository: CustomBrushRepository

    /** The blurred copy of the canvas behind this panel; null until the view is built. */
    private var glassBackdrop: ImageView? = null

    private var coverCard: LinearLayout? = null
    private var coverAccent: FrameLayout? = null
    private var coverWatermark: TextView? = null
    private var coverTitle: TextView? = null
    private var coverTagline: TextView? = null
    private var coverSample: ImageView? = null

    private lateinit var setsRecyclerView: RecyclerView
    private lateinit var brushesRecyclerView: RecyclerView
    private lateinit var setAdapter: BrushSetAdapter

    private val builtInSets = BrushLibrary.getDefaultBrushSets()
    private var brushSets: List<BrushSet> = builtInSets
    private var selectedSetIndex = 0
    private var compactLayout = false

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImport(it) }
    }

    override fun onStart() {
        super.onStart()
        val screenWidthDp = resources.configuration.screenWidthDp
        val screenHeightDp = resources.configuration.screenHeightDp
        val paletteWidth = (screenWidthDp * 0.355f).toInt().coerceIn(330, 620)
        val dockSide = PanelUi.dockSide(arguments, PanelUi.DockSide.RIGHT)
        PanelUi.dockSheet(
            dialog,
            dockSide,
            widthDp = paletteWidth
        )
        val bottomDialog = dialog as? BottomSheetDialog ?: return
        bottomDialog.setCanceledOnTouchOutside(true)
        isCancelable = true
        val window = bottomDialog.window ?: return
        val topMarginDp = (screenHeightDp * 0.072f).toInt().coerceIn(36, 76)
        val bottomMarginDp = (screenHeightDp * 0.015f).toInt().coerceIn(6, 16)
        val panelHeightDp = (screenHeightDp - topMarginDp - bottomMarginDp).coerceAtLeast(320)
        val panelWidthPx = PanelUi.dp(requireContext(), paletteWidth)
        val sideGutterDp = (screenWidthDp * 0.055f).toInt().coerceIn(64, 94)
        val sideGutterPx = PanelUi.dp(requireContext(), sideGutterDp)
        window.setLayout(panelWidthPx, PanelUi.dp(requireContext(), panelHeightDp))
        window.setGravity(Gravity.LEFT or Gravity.TOP)
        window.attributes = window.attributes.apply {
            x = if (dockSide == PanelUi.DockSide.RIGHT) {
                resources.displayMetrics.widthPixels - panelWidthPx - sideGutterPx
            } else {
                sideGutterPx
            }
            y = PanelUi.dp(requireContext(), topMarginDp)
        }
        bottomDialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(Color.TRANSPARENT)
        window.setDimAmount(0.015f)
        // Real background blur on Android 12+, with the translucent palette as a deterministic
        // fallback when the device disables cross-window blur (for battery or accessibility).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val attributes = window.attributes
            attributes.blurBehindRadius = PanelUi.dp(requireContext(), 18)
            window.attributes = attributes
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        com.procreate.android.canvas.BrushTextures.appContext = context.applicationContext
        fun dp(v: Int) = PanelUi.dp(context, v)
        val compact = resources.configuration.screenWidthDp < 760 ||
            resources.configuration.screenHeightDp < 520
        val referencePanelWidthDp = (resources.configuration.screenWidthDp * 0.355f)
            .toInt()
            .coerceIn(330, 620)
        val categoryRailWidthDp = (referencePanelWidthDp * 0.37f).toInt()
        compactLayout = compact
        repository = CustomBrushRepository(ArtworkDatabase.getDatabase(context).customBrushDao())
        val activePresetId = viewModel.currentBrushPresetId.value
        val activeProperties = viewModel.currentBrush.value
        builtInSets.indexOfFirst { set ->
            set.brushes.any { brush ->
                (activePresetId != null && brush.id == activePresetId) ||
                    (activePresetId == null && brush.properties == activeProperties)
            }
        }.takeIf { it >= 0 }?.let { selectedSetIndex = it }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // The panel is a pane of frosted glass laid over the artwork, not a dark slab on top of it:
        // a blurred copy of the canvas underneath sits behind the content, and the tinted outline
        // drawable goes over it to supply the wash, the hairline and the rounded corners.
        val glass = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val shell = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_brush_library_glass)
            // Over the children, so the pane keeps a visible edge instead of ending in nothing.
            foreground = ContextCompat.getDrawable(context, R.drawable.bg_panel_glass_edge)
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(glass)
            addView(root)
        }
        glassBackdrop = glass
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            minimumHeight = dp(if (compact) 43 else 56)
            setPadding(dp(10), 0, dp(12), 0)
        }
        val title = TextView(context).apply {
            text = getString(R.string.control_brushes)
            setTextAppearance(R.style.TextAppearance_App_PanelTitle)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(iconButton(context, R.drawable.ic_plus, "استيراد فرشاة") {
            importLauncher.launch("*/*")
        })
        header.addView(title)
        root.addView(header)
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ContextCompat.getColor(context, R.color.hairline_color))
        })

        val body = LinearLayout(context).apply {
            // The reference hierarchy remains useful even on a phone: a narrow discipline rail
            // on one side and texture-led brush previews on the other. A horizontal chip strip
            // made the panel look like a generic settings sheet and consumed a full row.
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(body)

        setsRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(categoryRailWidthDp),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            clipToPadding = false
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            setPadding(dp(4), dp(5), dp(4), dp(8))
            background = ContextCompat.getDrawable(context, R.drawable.bg_brush_category_rail)
        }
        setAdapter = BrushSetAdapter(brushSets, selectedSetIndex, compact) { index ->
            selectedSetIndex = index
            setAdapter.selectedIndex = index
            setAdapter.notifyDataSetChanged()
            updateBrushes()
        }
        setsRecyclerView.adapter = setAdapter

        brushesRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            itemAnimator = null
            setPadding(0, dp(4), 0, dp(8))
        }

        // The cover card introduces the chosen set above its brushes, the way a printed pack of
        // pens is introduced by its sleeve rather than by a line of text. It is pinned above the
        // list instead of scrolling with it, so the set you are inside of never leaves the screen.
        val coverHeightDp = if (compact) 74 else 104
        val titleView = TextView(context).apply {
            textSize = if (compact) 15f else 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        val taglineView = TextView(context).apply {
            textSize = if (compact) 10f else 11.5f
            setTextColor(Color.argb(200, 255, 255, 255))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        // A single Arabic letter set very large and very faint behind the title, the way the
        // reference packs brand their covers. It is decoration with a job: it makes each set's
        // card recognisable at a glance from across the panel, before any text is read.
        val watermark = TextView(context).apply {
            textSize = if (compact) 46f else 68f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.argb(60, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply { marginStart = dp(6) }
        }
        val textStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(titleView)
            addView(taglineView)
        }
        val accentBlock = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.56f)
            addView(watermark)
            addView(textStack)
        }
        coverWatermark = watermark
        // Paper, not more panel. In the reference the sample half of a cover card is a light plate
        // carrying the artwork, and the contrast against the colour block is what makes the card
        // read as a printed sleeve rather than another region of the dark interface.
        val sampleView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.44f)
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.rgb(242, 240, 234))
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToOutline = true
            background = ContextCompat.getDrawable(context, R.drawable.bg_brush_set_cover)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(coverHeightDp)
            ).apply {
                marginStart = dp(10); marginEnd = dp(10)
                topMargin = dp(6); bottomMargin = dp(6)
            }
            addView(accentBlock)
            addView(sampleView)
        }
        coverCard = card
        coverAccent = accentBlock
        coverTitle = titleView
        coverTagline = taglineView
        coverSample = sampleView

        val brushColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(card)
            addView(brushesRecyclerView)
        }
        brushesRecyclerView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        // LinearLayout mirrors children under Arabic RTL. Adding the preview column first leaves
        // the discipline rail on the physical left, while individual rows remain right-to-left.
        body.addView(brushColumn)
        body.addView(setsRecyclerView)

        updateBrushes()

        lifecycleScope.launch {
            repository.all.collect { entities ->
                val customBrushes = entities.map { it.toBrush() }
                val customSet = BrushSet("my_brushes", getString(R.string.brush_set_my_brushes), customBrushes)
                // Put built-in sets first so full catalog is immediately visible
                brushSets = builtInSets + listOf(customSet)
                setAdapter.updateSets(brushSets)
                if (selectedSetIndex >= brushSets.size) selectedSetIndex = 0
                val presetId = viewModel.currentBrushPresetId.value
                val active = viewModel.currentBrush.value
                brushSets.indexOfFirst { set ->
                    set.brushes.any { brush ->
                        (presetId != null && brush.id == presetId) ||
                            (presetId == null && brush.properties == active)
                    }
                }.takeIf { it >= 0 }?.let { selectedSetIndex = it }
                setAdapter.selectedIndex = selectedSetIndex
                setAdapter.notifyDataSetChanged()
                updateBrushes()
            }
        }

        return shell
    }

    /**
     * Fill the glass once the panel has a size and a position, because the backdrop is a copy of
     * the part of the canvas this panel happens to be covering.
     */
    override fun onResume() {
        super.onResume()
        val backdrop = glassBackdrop ?: return
        val host = activity?.window ?: return
        PanelGlass.install(host, backdrop, backdrop, Color.TRANSPARENT)
    }

    /** Routes an imported file to the right path: a .brush/.brushset is a ZIP archive (see
     * BrushsetImporter), anything else is treated as a plain shape image. */
    private fun handleImport(uri: Uri) {
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            if (BrushsetImporter.looksLikeZip(context, uri)) {
                val shapes = BrushsetImporter.extractShapes(context, uri)
                if (shapes.isEmpty()) {
                    toast(getString(R.string.brush_import_zip_empty))
                    return@launch
                }
                val entities = BrushsetImporter.saveAsCustomBrushes(context, shapes)
                entities.forEach { repository.insert(it) }
                toast(getString(R.string.brush_import_count_success, entities.size))
            } else {
                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    toast(getString(R.string.brush_import_unsupported))
                    return@launch
                }
                importSingleImage(bitmap)
                toast(getString(R.string.brush_import_count_success, 1))
            }
        }
    }

    private suspend fun importSingleImage(bitmap: Bitmap) {
        val context = requireContext()
        val dir = File(context.filesDir, "custom_brushes").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        repository.insert(
            CustomBrushEntity(
                id = id,
                name = getString(R.string.brush_imported_default_name),
                tipImagePath = file.absolutePath,
                size = 32f, opacity = 1f, hardness = 1f, spacing = 0.12f,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun toast(message: String) {
        withContext(Dispatchers.Main) {
            if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun iconButton(context: android.content.Context, iconRes: Int, description: String, onClick: () -> Unit): ImageButton {
        val size = PanelUi.dp(context, 36)
        return ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(PanelUi.dp(context, 9), PanelUi.dp(context, 9), PanelUi.dp(context, 9), PanelUi.dp(context, 9))
            background = ContextCompat.getDrawable(context, R.drawable.selector_icon_button)
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(context, R.color.icon_tint_selector)
            contentDescription = description
            setOnClickListener { onClick() }
            PanelUi.applyPressAnimation(this)
        }
    }

    /** Re-dress the cover for whichever set is now open. */
    private fun bindCover(set: BrushSet?) {
        val card = coverCard ?: return
        if (set == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val accent = BrushSetIdentity.accentFor(set)
        // A gradient rather than a flat fill: the same colour lit from one corner reads as a
        // printed sleeve, where a solid rectangle reads as a swatch.
        coverAccent?.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TR_BL,
            intArrayOf(BrushSetIdentity.lighten(accent), accent, BrushSetIdentity.darken(accent))
        )
        coverWatermark?.text = BrushSetIdentity.glyphFor(set)
        // The emoji many sets carry in their name is a label for the rail, not a headline; the
        // cover sets the name large and does not need it shouting twice.
        coverTitle?.text = set.name.trim()
        coverTagline?.apply {
            text = set.tagline ?: getString(R.string.brush_set_count, set.brushes.size)
            visibility = View.VISIBLE
        }
        val sample = coverSample ?: return
        sample.post {
            val w = sample.width
            val h = sample.height
            if (w > 0 && h > 0) sample.setImageBitmap(BrushPreviewRenderer.getSetMontage(set, w, h))
        }
    }

    private fun updateBrushes() {
        bindCover(brushSets.getOrNull(selectedSetIndex))
        val brushes = brushSets.getOrNull(selectedSetIndex)?.brushes ?: emptyList()
        brushesRecyclerView.adapter = BrushAdapter(
            brushes,
            compact = compactLayout,
            selectedBrushId = viewModel.currentBrushPresetId.value,
            selectedBrush = viewModel.currentBrush.value,
            onClick = { brush ->
                viewModel.setToolMode(ToolMode.DRAW)
                viewModel.selectBrushPreset(brush.id, brush.properties.copy())
                (brushesRecyclerView.adapter as? BrushAdapter)?.selectBrush(
                    brush.id,
                    brush.properties
                )
            },
            onLongClick = { brush ->
                viewModel.setToolMode(ToolMode.DRAW)
                viewModel.selectBrushPreset(brush.id, brush.properties.copy())
                BrushStudioPanel().apply {
                    arguments = PanelUi.dockArguments(
                        PanelUi.dockSide(this@BrushPanel.arguments, PanelUi.DockSide.RIGHT)
                    )
                }.show(parentFragmentManager, "BrushStudioPanel")
                dismiss()
            }
        )
    }
}

private fun CustomBrushEntity.toBrush() = Brush(
    id = id, name = name, category = "Custom",
    properties = BrushProperties(
        type = BrushType.Paint,
        size = size, opacity = opacity, hardness = hardness, spacing = spacing,
        tipType = BrushTipType.CUSTOM, customTipPath = tipImagePath,
        customGrainPath = grainImagePath,
        // A texture that is imported and then left at zero depth is the same as no texture at all.
        grainScale = if (grainImagePath != null) 0.6f else 0f
    )
)

class BrushSetAdapter(
    private var sets: List<BrushSet>,
    var selectedIndex: Int,
    private val compact: Boolean,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<BrushSetAdapter.ViewHolder>() {

    fun updateSets(newSets: List<BrushSet>) {
        sets = newSets
        notifyDataSetChanged()
    }

    class ViewHolder(
        val row: LinearLayout,
        val tagView: View,
        val iconView: ImageView,
        val textView: TextView
    ) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        fun dp(v: Int) = PanelUi.dp(context, v)
        val screenHeightDp = context.resources.configuration.screenHeightDp
        val rowHeightDp = if (compact) {
            (screenHeightDp * 0.070f).toInt().coerceIn(34, 42)
        } else {
            (screenHeightDp * 0.055f).toInt().coerceIn(48, 58)
        }
        val iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(if (compact) 15 else 18), dp(if (compact) 15 else 18)).apply {
                marginEnd = dp(if (compact) 7 else 9)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val textView = TextView(context).apply {
            textSize = if (compact) 12f else 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            maxLines = if (compact) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        // The colour tag authors of the reference packs had to fake by prefixing set names with
        // coloured emoji. Making it a real element keeps it out of the name, where it was being
        // read aloud by screen readers and truncated along with the title.
        val tagView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(if (compact) 15 else 20)).apply {
                marginEnd = dp(7)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(rowHeightDp)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // Capsules need air between them, or they fuse back into the column they replaced.
                bottomMargin = dp(4)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(tagView)
            addView(iconView)
            addView(textView)
        }
        return ViewHolder(row, tagView, iconView, textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.row.context
        val set = sets[position]
        holder.textView.text = set.name
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val selected = position == selectedIndex
        holder.row.background = ContextCompat.getDrawable(
            context, if (selected) R.drawable.bg_brush_category_selected else R.drawable.bg_brush_row
        )
        holder.tagView.setBackgroundColor(BrushSetIdentity.accentFor(set))
        holder.iconView.setImageResource(categoryIcon(set.id))
        holder.iconView.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, if (selected) R.color.white else R.color.icon_dim)
        )
        holder.textView.setTextColor(
            ContextCompat.getColor(context, if (selected) R.color.white else R.color.text_secondary)
        )
        holder.row.setOnClickListener { onClick(position) }
    }

    override fun getItemCount() = sets.size

    private fun categoryIcon(id: String): Int = when {
        id == "my_brushes" -> R.drawable.ic_plus
        id.contains("water") -> R.drawable.ic_blur
        id.contains("air") || id.contains("spray") -> R.drawable.ic_smudge
        id.contains("texture") || id.contains("charcoal") || id.contains("earth") -> R.drawable.ic_image
        id.contains("luminance") -> R.drawable.ic_colors
        else -> R.drawable.ic_brush
    }
}

class BrushAdapter(
    private val brushes: List<Brush>,
    private val compact: Boolean,
    private var selectedBrushId: String?,
    private var selectedBrush: BrushProperties?,
    private val onClick: (Brush) -> Unit,
    private val onLongClick: (Brush) -> Unit
) : RecyclerView.Adapter<BrushAdapter.ViewHolder>() {

    class ViewHolder(
        val root: LinearLayout,
        val nameView: TextView,
        val factsView: TextView,
        val strokeImage: ImageView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        fun dp(v: Int) = PanelUi.dp(context, v)
        val screenHeightDp = context.resources.configuration.screenHeightDp
        val rowHeightDp = if (compact) {
            (screenHeightDp * 0.120f).toInt().coerceIn(54, 66)
        } else {
            (screenHeightDp * 0.105f).toInt().coerceIn(84, 112)
        }
        val previewHeightDp = (rowHeightDp - if (compact) 22 else 28).coerceAtLeast(38)

        val nameView = TextView(context).apply {
            textSize = if (compact) 12.5f else 14.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val strokeImage = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(previewHeightDp)
            ).apply { topMargin = dp(1) }
            setPadding(dp(2), 0, dp(2), 0)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // What the tip is cut like and which of the pen's axes the brush answers to. Two presets
        // can share a name, a swatch and every visible slider and still behave differently in the
        // hand; without this line the only way to find that out is to draw with both.
        val factsView = TextView(context).apply {
            textSize = if (compact) 9.5f else 10.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(rowHeightDp)
            setPadding(dp(if (compact) 11 else 14), dp(if (compact) 5 else 8), dp(if (compact) 11 else 14), dp(3))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(1)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_brush_row_premium)
            addView(nameView)
            addView(factsView)
            addView(strokeImage)
        }
        return ViewHolder(root, nameView, factsView, strokeImage)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val brush = brushes[position]
        holder.nameView.text = brush.name
        val selected = brush.id == selectedBrushId ||
            (selectedBrushId == null && brush.properties == selectedBrush)
        holder.root.background = ContextCompat.getDrawable(
            holder.root.context,
            if (selected) R.drawable.bg_brush_row_selected else R.drawable.bg_brush_row_premium
        )
        holder.nameView.setTextColor(ContextCompat.getColor(
            holder.root.context,
            if (selected) R.color.white else R.color.text_primary
        ))
        holder.factsView.text = BrushFacts.chips(holder.root.context, brush.properties)
            .joinToString("  ·  ")
        holder.factsView.setTextColor(ContextCompat.getColor(
            holder.root.context,
            if (selected) R.color.white else R.color.text_hint
        ))

        // Render at the size the row will actually show, not at a fixed 6:1 strip. The strip was
        // far wider than the space it landed in, so FIT_CENTER shrank it to fit the width and left
        // the stroke occupying a fraction of the available height - every brush in the library read
        // as the same thin hairline no matter how broad its tip really was.
        bindPreview(holder.strokeImage, brush)
        holder.root.contentDescription = brush.name

        holder.root.setOnClickListener { onClick(brush) }
        holder.root.setOnLongClickListener { onLongClick(brush); true }
    }

    /**
     * Draw [brush]'s swatch at the view's own measured size, waiting for a layout pass if the row
     * has not been measured yet. The tag guards against a recycled row receiving the bitmap a
     * previous brush asked for.
     */
    private fun bindPreview(image: ImageView, brush: Brush) {
        image.tag = brush.id
        val width = image.width
        val height = image.height
        if (width > 0 && height > 0) {
            image.setImageBitmap(BrushPreviewRenderer.getPreview(brush, width, height))
            return
        }
        image.post {
            if (image.tag != brush.id) return@post
            val w = image.width
            val h = image.height
            if (w > 0 && h > 0) image.setImageBitmap(BrushPreviewRenderer.getPreview(brush, w, h))
        }
    }

    override fun getItemCount() = brushes.size

    fun selectBrush(id: String, properties: BrushProperties) {
        val oldPosition = brushes.indexOfFirst { brush ->
            brush.id == selectedBrushId ||
                (selectedBrushId == null && brush.properties == selectedBrush)
        }
        selectedBrushId = id
        selectedBrush = properties
        val newPosition = brushes.indexOfFirst { it.id == id }
        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition)
    }
}
