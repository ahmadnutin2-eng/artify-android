package com.procreate.android.ui.gallery

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.procreate.android.R
import com.procreate.android.canvas.CanvasBackgroundStyle
import com.procreate.android.database.Artwork
import com.procreate.android.database.ArtworkDatabase
import com.procreate.android.database.ArtworkRepository
import com.procreate.android.project.ProjectType
import com.procreate.android.ui.canvas.CanvasActivity
import com.procreate.android.ui.common.PanelUi
import kotlinx.coroutines.launch
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var repository: ArtworkRepository
    private lateinit var adapter: ArtworkAdapter
    private lateinit var emptyState: View

    /**
     * Lazy, so opening the gallery does not connect to Play until the user actually asks about the
     * subscription. [onResume] still refreshes entitlement through it, which is what revokes access
     * after a cancellation made on another device.
     */
    private val billingManager: com.procreate.android.billing.BillingManager by lazy {
        com.procreate.android.billing.BillingManager(this)
    }

    override fun onResume() {
        super.onResume()
        // The app's first screen is the natural place to reconcile with Play: it runs on every
        // launch and every return from the Play subscription page.
        billingManager.start()
    }

    override fun onDestroy() {
        billingManager.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        applySafeAreaInsets()
        repository = ArtworkRepository(ArtworkDatabase.getDatabase(this).artworkDao())

        emptyState = findViewById(R.id.empty_state)
        val recyclerGallery = findViewById<RecyclerView>(R.id.recycler_gallery)
        // A fixed 2-column grid was sized for a phone: on a large tablet it stretched each
        // thumbnail to roughly half the screen, so only two projects were ever visible. Deriving
        // the span count from a target card width keeps the cards a readable, consistent size and
        // lets a wide screen actually show more of them.
        recyclerGallery.layoutManager = GridLayoutManager(this, galleryColumnCount())
        adapter = ArtworkAdapter(emptyList()) { artwork ->
            val intent = Intent(this, CanvasActivity::class.java)
            intent.putExtra(CanvasActivity.EXTRA_ARTWORK_ID, artwork.id)
            startActivity(intent)
        }
        recyclerGallery.adapter = adapter

        findViewById<View>(R.id.entry_painting).setOnClickListener { showNewCanvasDialog() }
        findViewById<View>(R.id.entry_urban).setOnClickListener { openUrbanWorkspace() }
        findViewById<View>(R.id.entry_online).setOnClickListener { showOnlineMatchmaking() }
        findViewById<View>(R.id.entry_library).setOnClickListener { showLibrary() }
        com.procreate.android.ui.common.PanelUi.applyPressAnimation(findViewById(R.id.entry_painting))
        com.procreate.android.ui.common.PanelUi.applyPressAnimation(findViewById(R.id.entry_urban))
        com.procreate.android.ui.common.PanelUi.applyPressAnimation(findViewById(R.id.entry_online))
        com.procreate.android.ui.common.PanelUi.applyPressAnimation(findViewById(R.id.entry_library))

        findViewById<View>(R.id.btn_pro).let { proButton ->
            com.procreate.android.ui.common.PanelUi.applyPressAnimation(proButton)
            proButton.setOnClickListener {
                com.procreate.android.billing.SubscriptionDialog.show(
                    supportFragmentManager, billingManager
                ) { /* Nothing to resume - opened from the menu, not from a blocked action. */ }
            }
        }

        // Establish hierarchy on arrival without a splash screen or a long blocking animation.
        // PanelMotion also obeys the system animator scale, including the accessibility setting
        // that disables motion altogether.
        com.procreate.android.ui.common.PanelMotion.staggerIn(
            findViewById(R.id.gallery_header),
            perRow = 42L,
            maxStaggered = 3
        )

        lifecycleScope.launch {
            repository.allArtworks.collect { artworks ->
                adapter.update(artworks)
                emptyState.visibility = if (artworks.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Target ~260dp per project card, clamped so a phone still gets 2 and a large tablet
     * doesn't shrink cards into thumbnails. */
    private fun galleryColumnCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 260).coerceIn(2, 5)
    }

    /** Keep the gallery title and primary actions clear of status icons, camera cut-outs and the
     * gesture bar. Android 15+ draws edge-to-edge by default, so XML margins alone are not safe. */
    private fun applySafeAreaInsets() {
        val root = findViewById<View>(R.id.gallery_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * A size (or an open canvas that grows as it's drawn on) plus the paper it starts on.
     *
     * [width]/[height] of 0 means "match the device screen", resolved when the dialog is built.
     * [open] marks a canvas that gains new paper whenever a stroke reaches an edge, which is why
     * it can start small: growing later costs nothing, while starting huge spends memory on paper
     * the artist may never reach.
     */
    private data class SizePreset(
        val labelRes: Int,
        /** -1 marks the custom entry, resolved from the width/height fields instead of a fixed size. */
        val width: Int,
        val height: Int,
        val open: Boolean = false,
        val subtitleRes: Int? = null
    )

    private data class BackgroundPreset(val labelRes: Int, val style: CanvasBackgroundStyle)

    private fun showNewCanvasDialog(defaultBackground: CanvasBackgroundStyle = CanvasBackgroundStyle.BLANK) {
        val metrics = resources.displayMetrics
        val compactLandscape = resources.configuration.screenHeightDp < 500
        // Labels carry no numbers any more: each row shows its dimensions in a separate,
        // direction-isolated view at the end of the row. Embedding "(2480 × 3508)" in the label let
        // bidi reorder the two numbers inside an Arabic sentence, so A4 was displayed as
        // "3508 × 2480" - the dialog stating a landscape size for a portrait canvas.
        val sizePresets = listOf(
            SizePreset(R.string.canvas_size_open, 2048, 1536, open = true, subtitleRes = R.string.canvas_size_open_sub),
            SizePreset(R.string.canvas_size_screen, metrics.widthPixels, metrics.heightPixels),
            SizePreset(R.string.canvas_size_square, 2048, 2048),
            SizePreset(R.string.canvas_size_a4, 2480, 3508),
            SizePreset(R.string.canvas_size_wide, 3840, 2160),
            SizePreset(R.string.canvas_size_custom, -1, -1)
        )
        val backgroundPresets = listOf(
            BackgroundPreset(R.string.background_blank, CanvasBackgroundStyle.BLANK),
            BackgroundPreset(R.string.background_dots, CanvasBackgroundStyle.DOT_GRID),
            BackgroundPreset(R.string.background_grid, CanvasBackgroundStyle.LINE_GRID),
            BackgroundPreset(R.string.background_isometric, CanvasBackgroundStyle.ISOMETRIC)
        )

        fun dp(v: Int) = PanelUi.dp(this, v)
        var selectedSize = 0
        var selectedBackground = backgroundPresets.indexOfFirst { it.style == defaultBackground }
            .coerceAtLeast(0)

        fun styleChip(chip: TextView, active: Boolean) {
            chip.background = ContextCompat.getDrawable(
                this, if (active) R.drawable.bg_list_item_selected else R.drawable.bg_field_outline
            )
            chip.setTextColor(ContextCompat.getColor(this, if (active) R.color.procreate_accent_light else R.color.text_primary))
        }

        fun sectionTitle(textRes: Int) = TextView(this).apply {
            setText(textRes)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_secondary))
            setPadding(0, dp(if (compactLandscape) 6 else 12), 0, dp(if (compactLandscape) 5 else 8))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(6))
        }

        // Live shape preview: a rectangle scaled to fit a fixed box while keeping the real aspect
        // ratio, so "A4" and "widescreen" actually *look* tall or wide instead of being two lines
        // of text the user has to do the ratio math on themselves.
        val previewBoxDp = if (compactLandscape) 58 else 84
        val previewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(previewBoxDp), dp(previewBoxDp)).apply {
                bottomMargin = dp(4)
            }
        }
        val previewRect = View(this).apply {
            background = ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_swatch_rounded)
        }
        previewFrame.addView(previewRect, FrameLayout.LayoutParams(dp(previewBoxDp), dp(previewBoxDp)).apply {
            gravity = Gravity.CENTER
        })
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        previewRow.addView(previewFrame)
        content.addView(previewRow)

        // The exact size, under the shape. The preview alone conveys proportion but not scale -
        // a square at 512 and a square at 8000 draw identically - and for a custom size this is the
        // only confirmation that the typed numbers were accepted and clamped.
        val previewCaption = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(previewCaption)

        // Custom size input, only shown once the "Custom" chip is picked.
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4); bottomMargin = dp(4)
            }
        }
        fun sizeField(hintRes: Int, initial: Int) = EditText(this).apply {
            setText(initial.toString())
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_field_outline)
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val customWidthField = sizeField(R.string.custom_width_hint, 2048)
        val customHeightField = sizeField(R.string.custom_height_hint, 2048)
        val customX = TextView(this).apply {
            text = "×"
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        customRow.addView(customWidthField)
        customRow.addView(customX)
        customRow.addView(customHeightField)
        content.addView(customRow)

        fun currentSize(): Pair<Int, Int> {
            val preset = sizePresets[selectedSize]
            if (preset.width != -1) return preset.width to preset.height
            val w = customWidthField.text.toString().toIntOrNull()?.coerceIn(64, 8000) ?: 2048
            val h = customHeightField.text.toString().toIntOrNull()?.coerceIn(64, 8000) ?: 2048
            return w to h
        }

        fun refreshPreview() {
            val (w, h) = currentSize()
            val ratio = w.toFloat() / h.toFloat()
            val boxPx = dp(previewBoxDp)
            val (pw, ph) = if (ratio >= 1f) boxPx to (boxPx / ratio).toInt() else (boxPx * ratio).toInt() to boxPx
            previewRect.layoutParams = FrameLayout.LayoutParams(pw.coerceAtLeast(dp(12)), ph.coerceAtLeast(dp(12))).apply {
                gravity = Gravity.CENTER
            }
            previewRect.requestLayout()
            previewCaption.text = PanelUi.dimensions(w, h)
        }

        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshPreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        customWidthField.addTextChangedListener(watcher)
        customHeightField.addTextChangedListener(watcher)

        content.addView(sectionTitle(R.string.new_canvas_size))
        // One rounded card holding all six choices, rather than six separately-outlined chips that
        // read as six unrelated buttons and pushed the dialog to nearly the full screen height.
        val sizeList = PanelUi.groupedList(this)
        val sizeRows = mutableListOf<View>()
        sizePresets.forEachIndexed { index, preset ->
            val isCustom = preset.width == -1
            val row = PanelUi.choiceRow(
                context = this,
                title = getString(preset.labelRes),
                trailing = if (isCustom) null else PanelUi.dimensions(preset.width, preset.height),
                subtitle = if (compactLandscape) null else preset.subtitleRes?.let { getString(it) }
            ) {
                selectedSize = index
                sizeRows.forEachIndexed { i, r -> PanelUi.setRowSelected(r, i == index) }
                // Faded in and out rather than flipped, so choosing "Custom" does not make the
                // whole dialog snap to a new height.
                com.procreate.android.ui.common.PanelMotion.setVisible(customRow, isCustom)
                refreshPreview()
            }
            if (compactLandscape) {
                row.minimumHeight = dp(44)
                row.setPadding(dp(12), dp(7), dp(12), dp(7))
            }
            PanelUi.setRowSelected(row, index == 0)
            sizeRows.add(row)
            sizeList.addView(row)
            if (index != sizePresets.lastIndex) sizeList.addView(PanelUi.divider(this))
        }
        content.addView(sizeList)

        content.addView(sectionTitle(R.string.new_canvas_background))
        val bgChips = mutableListOf<TextView>()
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backgroundPresets.forEachIndexed { index, preset ->
            val chip = TextView(this).apply {
                setText(preset.labelRes)
                textSize = 13.5f
                gravity = Gravity.CENTER
                // 48dp minimum, and stated rather than left to emerge from padding plus font size -
                // the previous 10dp padding landed these at roughly 40dp.
                minHeight = dp(if (compactLandscape) 40 else 48)
                setPadding(dp(6), dp(if (compactLandscape) 8 else 12), dp(6), dp(if (compactLandscape) 8 else 12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (index != backgroundPresets.lastIndex) dp(6) else 0
                }
                setOnClickListener {
                    selectedBackground = index
                    bgChips.forEachIndexed { i, c -> styleChip(c, i == index) }
                }
            }
            styleChip(chip, index == selectedBackground)
            bgChips.add(chip)
            bgRow.addView(chip)
        }
        content.addView(bgRow)

        refreshPreview()

        // This app is landscape-only, so the canvas presets and preview belong beside each other.
        // The former vertical stack pushed the first preset below the fold and made the dialog
        // look like a stretched phone form on a tablet.
        content.removeAllViews()
        val sizeColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(R.string.new_canvas_size))
            addView(sizeList)
            addView(customRow)
        }
        val detailColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(if (compactLandscape) 8 else 16), 0, 0, 0)
            addView(previewRow)
            addView(previewCaption)
            addView(sectionTitle(R.string.new_canvas_background))
            addView(bgRow)
        }
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(
                    sizeColumn,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    View(this@GalleryActivity).apply {
                        setBackgroundColor(ContextCompat.getColor(this@GalleryActivity, R.color.hairline_color))
                    },
                    LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginStart = dp(14)
                    }
                )
                addView(
                    detailColumn,
                    LinearLayout.LayoutParams(
                        dp(if (compactLandscape) 220 else 265),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // A custom dialog rather than AlertDialog: the stock one supplies its own title styling and
        // a pair of small low-contrast text buttons, which is why the primary action of this screen
        // was a 14sp link in a corner while every other dialog in the app uses the dark panel
        // surface. This keeps one visual language across the app's dialogs.
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_panel_rounded)
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(PanelUi.panelTitle(this, getString(R.string.new_artwork)))
        root.addView(
            ScrollView(this).apply {
                minimumHeight = dp(220)
                addView(content)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        root.addView(
            PanelUi.dialogActions(
                context = this,
                primaryText = getString(R.string.new_canvas_create),
                secondaryText = getString(R.string.new_canvas_cancel),
                onPrimary = {
                    val preset = sizePresets[selectedSize]
                    val (w, h) = currentSize()
                    val intent = Intent(this, CanvasActivity::class.java)
                    intent.putExtra(CanvasActivity.EXTRA_CANVAS_WIDTH, w)
                    intent.putExtra(CanvasActivity.EXTRA_CANVAS_HEIGHT, h)
                    intent.putExtra(CanvasActivity.EXTRA_BACKGROUND_STYLE, backgroundPresets[selectedBackground].style.name)
                    intent.putExtra(CanvasActivity.EXTRA_OPEN_CANVAS, preset.open)
                    dialog.dismiss()
                    startActivity(intent)
                },
                onSecondary = { dialog.dismiss() }
            )
        )
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.let { window ->
            val availableWidth = metrics.widthPixels - dp(32)
            // Keep actions above gesture/navigation bars on devices whose DisplayMetrics include
            // the system bar area. A capped landscape dialog also avoids a tall column of empty
            // surface on large tablets.
            val availableHeight = (metrics.heightPixels - dp(if (compactLandscape) 32 else 64))
                .coerceAtLeast(dp(280))
            window.setLayout(
                minOf(dp(if (compactLandscape) 720 else 680), availableWidth),
                minOf(dp(520), availableHeight)
            )
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setDimAmount(0.28f)
        }
        com.procreate.android.ui.common.PanelMotion.staggerIn(content)
    }

    /** Opens the single organised library entry from the Gallery. */
    private fun showLibrary() {
        com.procreate.android.ui.library.AssetLibraryDialog()
            .onBackgroundSelected { style -> showNewCanvasDialog(style) }
            .onAssetSelected { asset ->
                val intent = Intent(this, CanvasActivity::class.java).apply {
                    putExtra(CanvasActivity.EXTRA_CANVAS_WIDTH, 3600)
                    putExtra(CanvasActivity.EXTRA_CANVAS_HEIGHT, 2700)
                    putExtra(CanvasActivity.EXTRA_BACKGROUND_STYLE, CanvasBackgroundStyle.BLANK.name)
                    putExtra(CanvasActivity.EXTRA_URBAN_MODE, true)
                    putExtra(CanvasActivity.EXTRA_OPEN_CANVAS, true)
                    putExtra(CanvasActivity.EXTRA_PENDING_ASSET_ID, asset.id)
                }
                startActivity(intent)
            }
            .show(supportFragmentManager, "AssetLibrary")
    }

    /** Availability-based two-person matching. The WebSocket survives the move into Canvas. */
    private fun showOnlineMatchmaking() {
        val client = (application as com.procreate.android.ArtifyApplication).collaborationClient
        val configuredUrl = com.procreate.android.collaboration.CollaborationConfig.serverUrl(this)
        fun dp(value: Int) = PanelUi.dp(this, value)

        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        var matched = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_panel_rounded)
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(PanelUi.panelTitle(this, getString(R.string.online_title)))
        root.addView(TextView(this).apply {
            setText(R.string.online_subtitle)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_secondary))
            setPadding(dp(20), 0, dp(20), dp(14))
        })
        root.addView(TextView(this).apply {
            setText(R.string.online_privacy_note)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_hint))
            setPadding(dp(20), 0, dp(20), dp(12))
        })

        val nameField = EditText(this).apply {
            setText(com.procreate.android.collaboration.CollaborationConfig.displayName(this@GalleryActivity))
            hint = getString(R.string.online_name_hint)
            maxLines = 1
            background = ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_field_outline)
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_hint))
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        root.addView(nameField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(20), 0, dp(20), dp(10)) })

        val serverField = EditText(this).apply {
            setText(configuredUrl)
            hint = getString(R.string.online_server_hint)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = ContextCompat.getDrawable(this@GalleryActivity, R.drawable.bg_field_outline)
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text_hint))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            visibility = if (configuredUrl.isBlank()) View.VISIBLE else View.GONE
        }
        root.addView(serverField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(20), 0, dp(20), dp(10)) })

        val status = TextView(this).apply {
            setText(if (configuredUrl.isBlank()) R.string.online_server_required else R.string.online_ready)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.procreate_accent_light))
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        root.addView(status)

        root.addView(PanelUi.dialogActions(
            context = this,
            primaryText = getString(R.string.online_search),
            secondaryText = getString(R.string.new_canvas_cancel),
            onPrimary = {
                val name = nameField.text.toString().trim()
                val url = (if (serverField.visibility == View.VISIBLE) {
                    serverField.text.toString()
                } else configuredUrl).trim()
                val validUrl = url.startsWith("wss://") ||
                    (com.procreate.android.BuildConfig.DEBUG && url.startsWith("ws://"))
                if (!validUrl) {
                    status.setText(R.string.online_server_invalid)
                } else {
                    com.procreate.android.collaboration.CollaborationConfig.setServerUrl(this, url)
                    status.setText(R.string.online_connecting)
                    client.observe { event ->
                        when (event) {
                            com.procreate.android.collaboration.CollaborationEvent.Connecting ->
                                status.setText(R.string.online_connecting)
                            com.procreate.android.collaboration.CollaborationEvent.Searching ->
                                status.setText(R.string.online_searching)
                            is com.procreate.android.collaboration.CollaborationEvent.Matched -> {
                                matched = true
                                dialog.dismiss()
                                startActivity(Intent(this, CanvasActivity::class.java).apply {
                                    putExtra(CanvasActivity.EXTRA_CANVAS_WIDTH, event.session.canvasWidth)
                                    putExtra(CanvasActivity.EXTRA_CANVAS_HEIGHT, event.session.canvasHeight)
                                    putExtra(CanvasActivity.EXTRA_BACKGROUND_STYLE, CanvasBackgroundStyle.BLANK.name)
                                    putExtra(CanvasActivity.EXTRA_OPEN_CANVAS, false)
                                    putExtra(CanvasActivity.EXTRA_COLLABORATION_SESSION_ID, event.session.sessionId)
                                })
                            }
                            is com.procreate.android.collaboration.CollaborationEvent.Error ->
                                status.text = getString(R.string.online_error, event.message)
                            com.procreate.android.collaboration.CollaborationEvent.Closed ->
                                status.setText(R.string.online_disconnected)
                            else -> Unit
                        }
                    }
                    client.search(name, 2048, 2048)
                }
            },
            onSecondary = { dialog.dismiss() }
        ))
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            if (!matched) {
                client.cancelSearch()
                client.observe(null)
            }
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(minOf(dp(520), resources.displayMetrics.widthPixels - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setDimAmount(0.32f)
        }
    }

    /** Urban CAD entry: opens the canvas directly on a plain sheet, with urban mode already
     * active - no size/background dialog, since the urban toolkit draws its own grid, scale bar
     * and legend rather than relying on the painting background styles.
     *
     * This used to hardcode a fixed 4000x3000 sheet with the open-canvas flag never set, so a
     * site plan that grew past that boundary hit a hard wall - the map felt "limited" no matter
     * how far the artist actually needed to work, and every pixel of that 4000x3000 sheet (times
     * every layer) was paid for up front even for a small sketch. Starting smaller and marking it
     * open lets it grow the same way the painting canvas already does: new paper is added only
     * when a stroke actually reaches near an edge, quantised to the grid so nothing shifts phase. */
    private fun openUrbanWorkspace() {
        val intent = Intent(this, CanvasActivity::class.java).apply {
            putExtra(CanvasActivity.EXTRA_CANVAS_WIDTH, 3600)
            putExtra(CanvasActivity.EXTRA_CANVAS_HEIGHT, 2700)
            putExtra(CanvasActivity.EXTRA_BACKGROUND_STYLE, CanvasBackgroundStyle.BLANK.name)
            putExtra(CanvasActivity.EXTRA_URBAN_MODE, true)
            putExtra(CanvasActivity.EXTRA_OPEN_CANVAS, true)
        }
        startActivity(intent)
    }

    class ArtworkAdapter(
        private var artworks: List<Artwork>,
        private val onClick: (Artwork) -> Unit
    ) : RecyclerView.Adapter<ArtworkAdapter.ArtworkViewHolder>() {

        fun update(newArtworks: List<Artwork>) {
            artworks = newArtworks
            notifyDataSetChanged()
        }

        class ArtworkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val titleText: TextView = view.findViewById(R.id.tv_title)
            val projectTypeText: TextView = view.findViewById(R.id.tv_project_type)
            val thumbnail: ImageView = view.findViewById(R.id.iv_thumbnail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtworkViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artwork, parent, false)
            return ArtworkViewHolder(view)
        }

        override fun onBindViewHolder(holder: ArtworkViewHolder, position: Int) {
            val artwork = artworks[position]
            holder.titleText.text = artwork.name
            holder.projectTypeText.visibility = if (artwork.projectType == ProjectType.URBAN_DESIGN) {
                View.VISIBLE
            } else View.GONE
            Glide.with(holder.thumbnail)
                .load(File(artwork.thumbnailPath))
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .centerCrop()
                .into(holder.thumbnail)
            holder.itemView.setOnClickListener { onClick(artwork) }
        }

        override fun getItemCount() = artworks.size
    }
}
