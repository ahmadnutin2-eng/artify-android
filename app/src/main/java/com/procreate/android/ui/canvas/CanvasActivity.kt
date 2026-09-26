package com.procreate.android.ui.canvas

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.procreate.android.R
import com.procreate.android.ArtifyApplication
import com.procreate.android.canvas.BrushType
import com.procreate.android.canvas.CanvasBackgroundStyle
import com.procreate.android.canvas.CanvasViewModel
import com.procreate.android.canvas.DrawingView
import com.procreate.android.canvas.Layer
import com.procreate.android.canvas.LayerCompositor
import com.procreate.android.canvas.ToolMode
import com.procreate.android.brushes.BrushPanel
import com.procreate.android.collaboration.CollaborationEvent
import com.procreate.android.color.ColorPickerPanel
import com.procreate.android.database.Artwork
import com.procreate.android.database.ArtworkDatabase
import com.procreate.android.database.ArtworkRepository
import com.procreate.android.layers.LayersPanel
import com.procreate.android.project.ProjectCanvasDto
import com.procreate.android.project.ProjectDocumentDto
import com.procreate.android.project.ProjectDocumentMapper
import com.procreate.android.project.ProjectDocumentStore
import com.procreate.android.project.ProjectType
import com.procreate.android.project.UrbanProjectDto
import com.procreate.android.tools.SelectionTool
import com.procreate.android.ui.gestures.GestureGuideDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

class CanvasActivity : AppCompatActivity() {

    private val viewModel: CanvasViewModel by viewModels()
    private lateinit var drawingView: DrawingView
    private lateinit var repository: ArtworkRepository
    private lateinit var projectStore: ProjectDocumentStore

    private var currentArtworkId: Long? = null
    private var currentArtwork: Artwork? = null
    private var artworkFileBaseName: String = UUID.randomUUID().toString()
    private var currentProjectType: ProjectType = ProjectType.DRAWING
    private var projectCreatedAtMillis: Long = System.currentTimeMillis()
    private var pendingUrbanProject: UrbanProjectDto? = null
    private var loadedProjectDocument: ProjectDocumentDto? = null
    private var projectLoadWarning: String? = null
    private var interfaceHidden = false
    private var compactCanvasChrome = false
    private var collaborationConfigured = false

    private val collaborationClient
        get() = (application as ArtifyApplication).collaborationClient

    private data class LoadedEditableProject(
        val document: ProjectDocumentDto,
        val layers: List<Layer>
    )

    private data class LoadedArtworkPayload(
        val artwork: Artwork,
        val editable: LoadedEditableProject?,
        val legacyBitmap: Bitmap?,
        val warning: String?
    )

    /** Safe accessor for panels/fragments that need the DrawingView but may run before onCreate finishes. */
    fun drawingViewOrNull(): DrawingView? = if (::drawingView.isInitialized) drawingView else null

    /** Makes a scale/overlay/tool-setting change one reversible Urban history operation. */
    private inline fun updateUrbanProject(change: () -> Unit) {
        val before = drawingView.createUrbanProjectSnapshot()
        change()
        drawingView.commitUrbanProjectChange(before)
    }

    /**
     * Created lazily so a session that never touches a paid feature never connects to Play at all.
     * [com.procreate.android.billing.Entitlements] already has the cached answer from launch, so
     * gating does not wait on this.
     */
    private val billingManager: com.procreate.android.billing.BillingManager by lazy {
        com.procreate.android.billing.BillingManager(this)
    }

    /**
     * Opens the paywall and, if the user subscribes, runs [onUnlocked] so they land where they were
     * going instead of having to find the feature again.
     */
    /** Opened from the actions menu rather than by hitting a wall, so nothing needs resuming. */
    fun showSubscriptionFromMenu() = showSubscription { }

    private fun showSubscription(onUnlocked: () -> Unit) {
        com.procreate.android.billing.SubscriptionDialog.show(
            supportFragmentManager, billingManager
        ) { onUnlocked() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.procreate.android.ui.common.PanelUi.enterImmersiveMode(window)
        com.procreate.android.canvas.BrushTextures.appContext = applicationContext
        repository = ArtworkRepository(ArtworkDatabase.getDatabase(this).artworkDao())
        projectStore = ProjectDocumentStore(this)
        currentProjectType = if (intent.getBooleanExtra(EXTRA_URBAN_MODE, false)) {
            ProjectType.URBAN_DESIGN
        } else ProjectType.DRAWING

        val artworkId = intent.getLongExtra(EXTRA_ARTWORK_ID, -1L)

        setContentView(R.layout.activity_canvas)
        configureResponsiveCanvasChrome()
        applyTopChromeInsets()

        drawingView = findViewById(R.id.drawing_canvas_view)
        drawingView.viewModel = viewModel
        viewModel.setUrbanStateRestorer { snapshot ->
            drawingView.restoreUrbanProjectSnapshot(snapshot)
            syncWorkspaceChromeFromDrawing()
        }
        pendingUrbanProject?.let(drawingView::restoreUrbanProjectSnapshot)

        val canvasWidth = loadedProjectDocument?.canvas?.width
            ?: intent.getIntExtra(EXTRA_CANVAS_WIDTH, -1)
        val canvasHeight = loadedProjectDocument?.canvas?.height
            ?: intent.getIntExtra(EXTRA_CANVAS_HEIGHT, -1)
        if (canvasWidth > 0 && canvasHeight > 0) {
            drawingView.pendingCanvasSize = Pair(canvasWidth, canvasHeight)
        }
        if (loadedProjectDocument == null) {
            intent.getStringExtra(EXTRA_BACKGROUND_STYLE)?.let { name ->
                runCatching { CanvasBackgroundStyle.valueOf(name) }
                    .getOrNull()?.let { viewModel.setBackgroundStyle(it) }
            }
            viewModel.isOpenCanvas = intent.getBooleanExtra(EXTRA_OPEN_CANVAS, false)
        }

        setupLeftToolbar()
        setupRightToolbar()
        setupSliders()
        setupUndoRedo()
        setupToolSelectionSync()
        setupColorDrop()
        setupPressAnimations()
        setupGestures()
        setupCanvasLockButton()
        setupCollaborationIfNeeded()

        // Observe Brush changes to update sliders
        viewModel.currentBrush.observe(this) { brushProps ->
            findViewById<VerticalFillSlider>(R.id.slider_size).progress =
                brushSizeToSliderProgress(brushProps.size)
            findViewById<VerticalFillSlider>(R.id.slider_opacity).progress = (brushProps.opacity * 100).toInt()
        }
        viewModel.currentColor.observe(this) { color ->
            val indicator = findViewById<View>(R.id.color_indicator)
            (indicator.background.mutate() as? GradientDrawable)?.setColor(color)
        }
        viewModel.backgroundStyle.observe(this) { drawingView.invalidate() }
        viewModel.backgroundColor.observe(this) { drawingView.invalidate() }

        // Enter the canvas immediately. A twelve-page modal tutorial on the first blank canvas
        // made the app feel like onboarding software rather than a drawing tool. The animated
        // guide remains available at any time from Actions > Gestures.

        // Arrived straight from the Gallery's "Urban Planning" entry card - skip the manual
        // toolbar tap and start already in Urban CAD mode.
        if (currentProjectType == ProjectType.URBAN_DESIGN || intent.getBooleanExtra(EXTRA_URBAN_MODE, false)) {
            drawingView.post {
                activateUrbanMode()
                intent.getStringExtra(EXTRA_PENDING_ASSET_ID)?.let { assetId ->
                    drawingView.beginArchitecturalAssetPlacement(assetId)
                    com.procreate.android.urban.assets.BuiltInArchitecturalAssets.catalog[assetId]
                        ?.let { asset ->
                            Toast.makeText(
                                this,
                                getString(R.string.library_place_asset, asset.nameAr),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
        }
        projectLoadWarning?.let { warning ->
            drawingView.post { Toast.makeText(this, warning, Toast.LENGTH_LONG).show() }
        }

        if (artworkId != -1L) {
            setProjectLoading(true)
            loadArtworkWithoutBlockingUi(artworkId)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.procreate.android.ui.common.PanelUi.enterImmersiveMode(window)
    }

    /** Reads Room, JSON and every layer bitmap away from the UI thread, then performs one fast swap. */
    private fun loadArtworkWithoutBlockingUi(artworkId: Long) {
        lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                val artwork = repository.getById(artworkId) ?: return@withContext null
                var warning: String? = null
                val editable = artwork.documentPath?.let { documentPath ->
                    runCatching { loadEditableProject(documentPath) }
                        .onFailure { error ->
                            warning = getString(
                                R.string.project_editable_load_failed,
                                error.message.orEmpty()
                            )
                        }
                        .getOrNull()
                }
                val legacy = if (editable == null || editable.layers.isEmpty()) {
                    BitmapFactory.decodeFile(artwork.filePath)?.let { decoded ->
                        decoded.copy(Bitmap.Config.ARGB_8888, true).also {
                            if (it !== decoded) decoded.recycle()
                        }
                    }
                } else null
                LoadedArtworkPayload(artwork, editable, legacy, warning)
            }

            if (payload == null) {
                setProjectLoading(false)
                Toast.makeText(this@CanvasActivity, R.string.project_not_found, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            currentArtwork = payload.artwork
            currentArtworkId = artworkId
            artworkFileBaseName = File(payload.artwork.filePath).nameWithoutExtension
            currentProjectType = payload.artwork.projectType

            val editable = payload.editable
            if (editable != null && editable.layers.isNotEmpty()) {
                loadedProjectDocument = editable.document
                projectCreatedAtMillis = editable.document.createdAtEpochMillis
                pendingUrbanProject = editable.document.urban
                val background = runCatching {
                    CanvasBackgroundStyle.valueOf(editable.document.canvas.backgroundStyle)
                }.getOrDefault(CanvasBackgroundStyle.BLANK)
                viewModel.replaceProjectLayers(
                    layers = editable.layers,
                    activeLayerId = editable.document.activeRasterLayerId,
                    backgroundStyle = background,
                    backgroundColor = editable.document.canvas.backgroundColor,
                    openCanvas = editable.document.canvas.isOpenCanvas
                )
            } else {
                payload.legacyBitmap?.let { bitmap ->
                    val layer = Layer(
                        id = UUID.randomUUID().toString(),
                        name = getString(R.string.layer_default_name),
                        bitmap = bitmap
                    )
                    viewModel.replaceProjectLayers(
                        layers = listOf(layer),
                        activeLayerId = layer.id,
                        backgroundStyle = CanvasBackgroundStyle.BLANK,
                        backgroundColor = android.graphics.Color.WHITE,
                        openCanvas = false
                    )
                }
            }

            pendingUrbanProject?.let(drawingView::restoreUrbanProjectSnapshot)
            drawingView.fitCanvasToScreen()
            if (currentProjectType == ProjectType.URBAN_DESIGN) activateUrbanMode()
            payload.warning?.let { Toast.makeText(this@CanvasActivity, it, Toast.LENGTH_LONG).show() }
            setProjectLoading(false)
            setupCollaborationIfNeeded()
        }
    }

    private fun setupCollaborationIfNeeded() {
        if (!intent.hasExtra(EXTRA_COLLABORATION_SESSION_ID) || collaborationConfigured) return
        val expectedSessionId = intent.getStringExtra(EXTRA_COLLABORATION_SESSION_ID) ?: return
        val session = collaborationClient.currentSession
        if (session == null || session.sessionId != expectedSessionId) {
            findViewById<android.widget.TextView>(R.id.collaboration_status).apply {
                visibility = View.VISIBLE
                text = getString(R.string.online_disconnected)
            }
            return
        }
        if (viewModel.layers.value.isNullOrEmpty()) {
            drawingView.post { setupCollaborationIfNeeded() }
            return
        }

        collaborationConfigured = viewModel.configureCollaboration(
            localId = session.localParticipantId,
            localName = session.localParticipantName,
            partnerId = session.partnerParticipantId,
            partnerName = session.partnerParticipantName
        )
        if (!collaborationConfigured) return

        findViewById<android.widget.TextView>(R.id.collaboration_status).apply {
            visibility = View.VISIBLE
            text = getString(R.string.online_connected_to, session.partnerParticipantName)
            setOnClickListener { confirmLeaveCollaboration() }
        }
        drawingView.onLocalCollaborationStroke = collaborationClient::sendStroke
        drawingView.onLocalCollaborationPatch = { patch ->
            lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(patch.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        output.toByteArray()
                    }
                    collaborationClient.sendPatch(patch.strokeId, patch.left, patch.top, bytes)
                } finally {
                    if (!patch.bitmap.isRecycled) patch.bitmap.recycle()
                }
            }
        }
        collaborationClient.observe(::handleCollaborationEvent)
    }

    private fun handleCollaborationEvent(event: CollaborationEvent) {
        when (event) {
            is CollaborationEvent.PartnerStroke -> drawingView.showRemoteCollaborationStroke(
                event.participantName, event.stroke
            )
            is CollaborationEvent.PartnerPatch -> lifecycleScope.launch(Dispatchers.Default) {
                val decoded = BitmapFactory.decodeByteArray(
                    event.patch.pngBytes, 0, event.patch.pngBytes.size
                )
                val bitmap = decoded?.copy(Bitmap.Config.ARGB_8888, true)
                if (decoded != null && decoded !== bitmap) decoded.recycle()
                if (bitmap != null) withContext(Dispatchers.Main) {
                    viewModel.applyRemoteCollaborationPatch(
                        participantId = event.patch.participantId,
                        participantName = event.patch.participantName,
                        left = event.patch.left,
                        top = event.patch.top,
                        patch = bitmap
                    )
                    drawingView.completeRemoteCollaborationStroke(event.patch.strokeId)
                    bitmap.recycle()
                }
            }
            is CollaborationEvent.PartnerLeft -> {
                findViewById<android.widget.TextView>(R.id.collaboration_status).text =
                    getString(R.string.online_partner_left)
                Toast.makeText(this, R.string.online_partner_left, Toast.LENGTH_LONG).show()
            }
            is CollaborationEvent.Error, CollaborationEvent.Closed -> {
                findViewById<android.widget.TextView>(R.id.collaboration_status).text =
                    getString(R.string.online_disconnected)
            }
            else -> Unit
        }
    }

    private fun confirmLeaveCollaboration() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.online_leave_title)
            .setMessage(R.string.online_leave_message)
            .setPositiveButton(R.string.online_leave) { _, _ ->
                collaborationClient.leave()
                finish()
            }
            .setNegativeButton(R.string.new_canvas_cancel, null)
            .show()
    }

    private fun setProjectLoading(loading: Boolean) {
        findViewById<View>(R.id.canvas_loading_overlay)?.visibility =
            if (loading) View.VISIBLE else View.GONE
    }

    private fun loadEditableProject(documentPath: String): LoadedEditableProject {
        val document = projectStore.load(documentPath)
        val bitmaps = mutableListOf<Bitmap>()
        return try {
            val layers = document.rasterLayers.map { layerDto ->
                val bitmap = projectStore.loadLayerBitmap(documentPath, layerDto)
                bitmaps += bitmap
                val mask = projectStore.loadLayerMask(documentPath, layerDto)
                if (mask != null) bitmaps += mask
                ProjectDocumentMapper.toLayer(layerDto, bitmap, mask)
            }
            LoadedEditableProject(document, layers)
        } catch (error: Throwable) {
            bitmaps.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            throw error
        }
    }

    /** Routes the canvas gestures whose effect reaches beyond the canvas itself. */
    private fun setupGestures() {
        drawingView.gestureListener = object : DrawingView.GestureListener {
            override fun onToggleInterface() = toggleInterface()

            override fun onColorPicked(color: Int) {
                viewModel.setCurrentColor(color)
                val indicator = findViewById<View>(R.id.color_indicator)
                (indicator.background.mutate() as GradientDrawable).setColor(color)
            }

            override fun onBrushChanged(size: Float, opacity: Float) {
                // The sidebar sliders are the same setting by another route, so they have to track
                // the gesture or the two controls will disagree the moment the user looks at them.
                findViewById<VerticalFillSlider>(R.id.slider_size).progress =
                    brushSizeToSliderProgress(size)
                findViewById<VerticalFillSlider>(R.id.slider_opacity).progress = (opacity * 100).toInt()
            }

            override fun onHint(message: String) {
                Toast.makeText(this@CanvasActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * A small, semi-transparent lock toggle pinned to the bottom-left of the screen (unaffected
     * by RTL mirroring - this one is a fixed on-screen anchor, not part of either toolbar). While
     * locked, DrawingView ignores the continuous two-finger pan/zoom/rotate transform entirely
     * (see isCanvasLocked), so a stray second finger resting on the glass while drawing with the
     * other hand can't nudge the view - every other gesture (drawing itself, undo/redo taps,
     * clear-layer swipe, toggle-UI tap) is untouched.
     */
    private var canvasLockButton: android.widget.ImageView? = null

    /** Shared by the bottom-left lock button and the radial color fan's own hub badge, so
     * whichever one the user actually taps keeps both in sync rather than each holding a
     * separate idea of the current state. */
    private fun toggleCanvasLock() {
        drawingView.isCanvasLocked = !drawingView.isCanvasLocked
        val locked = drawingView.isCanvasLocked
        val button = canvasLockButton
        button?.setImageResource(if (locked) R.drawable.ic_canvas_lock_closed else R.drawable.ic_canvas_lock_open)
        button?.imageTintList = android.content.res.ColorStateList.valueOf(
            if (locked) getColor(R.color.procreate_accent) else android.graphics.Color.argb(200, 255, 255, 255)
        )
        button?.alpha = if (locked) 0.95f else 0.55f
        button?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(
            this,
            if (locked) "تم تثبيت اللوحة - لن تتحرك عند اللمس بإصبعين" else "تم إلغاء تثبيت اللوحة",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun setupCanvasLockButton() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val button = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_canvas_lock_open)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.argb(90, 20, 20, 24))
            }
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(200, 255, 255, 255))
            alpha = 0.55f
        }
        canvasLockButton = button
        val size = dp(44)
        val params = android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.LEFT
            leftMargin = dp(16)
            bottomMargin = dp(16)
        }
        button.setOnClickListener { toggleCanvasLock() }
        findViewById<android.widget.FrameLayout>(android.R.id.content).addView(button, params)
    }

    /** Panels fade out of the way for an unobstructed canvas, and fade back on the next four-finger tap. */
    private fun toggleInterface() {
        interfaceHidden = !interfaceHidden
        for (id in CHROME_IDS) {
            val view = findViewById<View>(id)
            val belongsToPainting = id == R.id.toolbar_right || id == R.id.sidebar_slider
            val shouldShow = !interfaceHidden && !(drawingView.isUrbanMode && belongsToPainting)
            val target = if (shouldShow) 1f else 0f
            if (shouldShow) view.visibility = View.VISIBLE
            view.animate().alpha(target).setDuration(180).withEndAction {
                // Gone rather than merely transparent, so a hidden toolbar can't still swallow the
                // taps of someone drawing underneath it.
                view.visibility = if (shouldShow) View.VISIBLE else View.GONE
            }.start()
        }
    }

    /** Painting and Urban CAD are separate workspaces; only controls relevant to the active one
     * remain tappable, while Undo/Redo and project actions stay shared. */
    private fun updateWorkspaceChrome(isUrban: Boolean) {
        val paintingVisibility = if (isUrban || interfaceHidden) View.GONE else View.VISIBLE
        findViewById<View>(R.id.toolbar_right).visibility = paintingVisibility
        findViewById<View>(R.id.sidebar_slider).visibility = paintingVisibility
        intArrayOf(R.id.btn_adjustments, R.id.btn_selection, R.id.btn_transform).forEach { id ->
            val compactSecondaryTool = compactCanvasChrome && id == R.id.btn_adjustments
            findViewById<View>(id).visibility =
                if (isUrban || compactSecondaryTool) View.GONE else View.VISIBLE
        }
        updateUrbanStatusBar(isUrban)
    }

    /** Keeps the always-on CAD readout in step with the tool, scale and snap state. */
    fun updateUrbanStatusBar(isUrban: Boolean = drawingView.isUrbanMode) {
        val bar = findViewById<android.widget.TextView>(R.id.urban_status_bar) ?: return
        if (!isUrban || interfaceHidden) {
            bar.visibility = View.GONE
            return
        }
        val config = drawingView.urbanScaleConfig
        val snap = drawingView.urbanSnapSettings
        val activeSnaps = buildList {
            if (snap.vertex) add("عقد")
            if (snap.grid) add("شبكة")
            if (snap.midpoint) add("منتصفات")
            if (snap.ortho) add("45°")
        }
        val parts = listOf(
            drawingView.activeUrbanTool?.titleAr ?: "تحديد",
            "1:${config.standardRatio}",
            if (config.isCalibrated) "معايَر" else "غير معايَر",
            "التقاط: " + (if (activeSnaps.isEmpty()) "معطّل" else activeSnaps.joinToString("، "))
        )
        bar.text = parts.joinToString("  ·  ")
        bar.visibility = View.VISIBLE
    }

    private fun syncWorkspaceChromeFromDrawing() {
        val urban = drawingView.isUrbanMode
        updateWorkspaceChrome(urban)
        val dock = findViewById<com.procreate.android.urban.ui.UrbanToolbarDock>(R.id.urban_toolbar_dock)
        dock.visibility = if (urban && !interfaceHidden) View.VISIBLE else View.GONE
        if (urban) {
            currentProjectType = ProjectType.URBAN_DESIGN
            drawingView.activeUrbanTool?.let { tool -> dock.updateActiveTool(tool, drawingView.urbanScaleConfig) }
        } else {
            dock.exitToolDrawingMode()
        }
        findViewById<ImageButton>(R.id.btn_urban).isSelected = urban
    }

    /** Opens the animated gesture guide - also reachable from the Actions panel. */
    fun showGestureGuide() {
        GestureGuideDialog.show(supportFragmentManager)
    }

    /**
     * Keeps the toolbar icons visually reflecting which tool is actually active - previously
     * only btn_brush ever showed a "selected" look (baked into the XML) and it never changed,
     * so switching to Smudge/Eraser/Selection/Transform left the UI showing stale state.
     */
    private fun setupToolSelectionSync() {
        val btnBrush = findViewById<ImageButton>(R.id.btn_brush)
        val btnSmudge = findViewById<ImageButton>(R.id.btn_smudge)
        val btnBlur = findViewById<ImageButton>(R.id.btn_blur)
        val btnEraser = findViewById<ImageButton>(R.id.btn_eraser)
        val btnSelection = findViewById<ImageButton>(R.id.btn_selection)
        val btnTransform = findViewById<ImageButton>(R.id.btn_transform)
        val btnUrban = findViewById<ImageButton>(R.id.btn_urban)
        val btnMore = findViewById<ImageButton>(R.id.btn_more_tools)

        fun refresh() {
            val isUrban = drawingViewOrNull()?.isUrbanMode == true
            val mode = viewModel.toolMode.value ?: ToolMode.DRAW
            val isEraser = mode == ToolMode.DRAW && viewModel.currentBrush.value?.type == BrushType.Eraser
            btnBrush.isSelected = mode == ToolMode.DRAW && !isEraser && !isUrban
            btnEraser.isSelected = isEraser && !isUrban
            btnSmudge.isSelected = mode == ToolMode.SMUDGE
            btnBlur.isSelected = mode == ToolMode.BLUR
            btnSelection.isSelected = mode == ToolMode.SELECTION
            btnTransform.isSelected = mode == ToolMode.TRANSFORM
            btnUrban.isSelected = isUrban
            btnMore.isSelected = compactCanvasChrome &&
                (isUrban || mode == ToolMode.SMUDGE || mode == ToolMode.BLUR)
            findViewById<VerticalFillSlider>(R.id.slider_opacity).contentDescription = getString(
                when (mode) {
                    ToolMode.BLUR -> R.string.blur_strength
                    ToolMode.SMUDGE -> R.string.smudge_strength
                    else -> R.string.opacity
                }
            )
            val strengthLabel = findViewById<android.widget.TextView>(R.id.tool_strength_label)
            val strength = ((viewModel.currentBrush.value?.opacity ?: 1f) * 100).toInt()
            when (mode) {
                ToolMode.BLUR -> {
                    strengthLabel.visibility = View.VISIBLE
                    strengthLabel.setTextColor(getColor(R.color.procreate_accent_light))
                    strengthLabel.text = getString(R.string.blur_strength_value, strength)
                }
                ToolMode.SMUDGE -> {
                    strengthLabel.visibility = View.VISIBLE
                    strengthLabel.setTextColor(getColor(R.color.procreate_accent_light))
                    strengthLabel.text = getString(R.string.smudge_strength_value, strength)
                }
                else -> strengthLabel.visibility = View.GONE
            }
        }

        viewModel.toolMode.observe(this) { refresh() }
        viewModel.currentBrush.observe(this) { refresh() }
        viewModel.layers.observe(this) { drawingViewOrNull()?.invalidate() }
    }

    override fun onPause() {
        super.onPause()
        saveArtwork()
    }

    override fun onDestroy() {
        if (collaborationConfigured && !isChangingConfigurations) collaborationClient.leave()
        collaborationClient.observe(null)
        super.onDestroy()
    }

    /** Persists both the editable versioned project and a flattened gallery/legacy preview. */
    fun saveArtwork() {
        if (!viewModel.hasUnsavedContent) return
        val layers = viewModel.layers.value?.toList().orEmpty()
        val firstLayer = layers.firstOrNull() ?: return
        val revision = viewModel.currentContentRevision()
        val savedAt = System.currentTimeMillis()
        val urbanSnapshot = if (currentProjectType == ProjectType.URBAN_DESIGN) {
            drawingView.createUrbanProjectSnapshot()
        } else null
        val rasterLayerDtos = layers.map { layer -> ProjectDocumentMapper.fromLayer(layer) }
        val activeLayerId = layers.getOrNull(viewModel.activeLayerIndex.value ?: -1)?.id
        val document = ProjectDocumentDto(
            projectType = currentProjectType,
            canvas = ProjectCanvasDto(
                width = firstLayer.bitmap.width,
                height = firstLayer.bitmap.height,
                backgroundStyle = (viewModel.backgroundStyle.value ?: CanvasBackgroundStyle.BLANK).name,
                backgroundColor = viewModel.backgroundColor.value ?: android.graphics.Color.WHITE,
                isOpenCanvas = viewModel.isOpenCanvas
            ),
            rasterLayers = rasterLayerDtos,
            activeRasterLayerId = activeLayerId,
            urban = urbanSnapshot,
            createdAtEpochMillis = projectCreatedAtMillis,
            modifiedAtEpochMillis = savedAt
        )
        val bitmapsByLayerId = layers.associate { it.id to it.bitmap }
        val masksByLayerId = layers.mapNotNull { layer ->
            layer.maskBitmap?.let { layer.id to it }
        }.toMap()

        val app = application as ArtifyApplication
        app.persistenceScope.launch {
            app.projectSaveMutex.withLock {
                runCatching {
                    val documentPath = projectStore.save(
                        projectKey = artworkFileBaseName,
                        document = document,
                        bitmapsByLayerId = bitmapsByLayerId,
                        maskBitmapsByLayerId = masksByLayerId
                    )

                    val flattened = Bitmap.createBitmap(
                        firstLayer.bitmap.width,
                        firstLayer.bitmap.height,
                        Bitmap.Config.ARGB_8888
                    )
                    if (document.canvas.isOpenCanvas) {
                        com.procreate.android.canvas.CanvasBackground.paint(
                            flattened,
                            viewModel.backgroundStyle.value ?: CanvasBackgroundStyle.BLANK,
                            viewModel.backgroundPatternPitch(),
                            viewModel.backgroundColor.value ?: android.graphics.Color.WHITE
                        )
                    }
                    LayerCompositor.draw(Canvas(flattened), layers)
                    renderUrbanPreview(flattened, urbanSnapshot)

                    val dir = File(filesDir, "artworks").apply { mkdirs() }
                    val imageFile = File(dir, "$artworkFileBaseName.png")
                    val thumbFile = File(dir, "${artworkFileBaseName}_thumb.png")
                    FileOutputStream(imageFile).use { output ->
                        check(flattened.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    val thumbHeight = (300f * flattened.height / flattened.width).toInt().coerceAtLeast(1)
                    val thumb = Bitmap.createScaledBitmap(flattened, 300, thumbHeight, true)
                    FileOutputStream(thumbFile).use { output ->
                        check(thumb.compress(Bitmap.CompressFormat.PNG, 90, output))
                    }

                    val rowId = withContext(Dispatchers.Main) { currentArtworkId ?: 0L }
                    val artwork = Artwork(
                        id = rowId,
                        name = currentArtwork?.name ?: if (document.projectType == ProjectType.URBAN_DESIGN) {
                            getString(R.string.untitled_urban_project)
                        } else getString(R.string.untitled_artwork),
                        thumbnailPath = thumbFile.absolutePath,
                        filePath = imageFile.absolutePath,
                        createdAt = savedAt,
                        width = flattened.width,
                        height = flattened.height,
                        projectType = document.projectType,
                        documentPath = documentPath
                    )
                    val savedId = repository.insert(artwork)
                    if (thumb !== flattened) thumb.recycle()
                    flattened.recycle()

                    withContext(Dispatchers.Main) {
                        currentArtworkId = savedId
                        currentArtwork = artwork.copy(id = savedId)
                        loadedProjectDocument = document
                        viewModel.markProjectSaved(revision)
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed) {
                            Toast.makeText(
                                this@CanvasActivity,
                                "تعذر حفظ المشروع: ${error.message.orEmpty()}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /** Adds Urban vectors to the flattened preview without changing their editable source data. */
    private fun renderUrbanPreview(bitmap: Bitmap, urban: UrbanProjectDto?) {
        urban ?: return
        val canvas = Canvas(bitmap)
        val config = ProjectDocumentMapper.toScaleConfig(urban.scale)
        if (config.isGridVisible) {
            com.procreate.android.urban.grid.UrbanGridRenderer.renderGrid(
                canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), config
            )
        }
        urban.elements.map(ProjectDocumentMapper::toUrbanElement).forEach { element ->
            when (element) {
                is com.procreate.android.urban.model.UrbanElement.ArrowPath ->
                    com.procreate.android.urban.tools.UrbanArrowRenderer.renderArrow(canvas, element)
                is com.procreate.android.urban.model.UrbanElement.HatchPolygon ->
                    com.procreate.android.urban.tools.UrbanHatchRenderer.renderHatch(canvas, element)
                is com.procreate.android.urban.model.UrbanElement.PointMarker ->
                    com.procreate.android.urban.tools.UrbanSymbolRenderer.renderMarker(canvas, element)
                is com.procreate.android.urban.model.UrbanElement.BoundaryPath ->
                    com.procreate.android.urban.tools.UrbanSymbolRenderer.renderBoundary(canvas, element)
            }
        }
    }

    companion object {
        const val EXTRA_ARTWORK_ID = "extra_artwork_id"
        const val EXTRA_CANVAS_WIDTH = "extra_canvas_width"
        const val EXTRA_CANVAS_HEIGHT = "extra_canvas_height"
        const val EXTRA_BACKGROUND_STYLE = "extra_background_style"
        const val EXTRA_OPEN_CANVAS = "extra_open_canvas"
        const val EXTRA_URBAN_MODE = "extra_urban_mode"
        const val EXTRA_PENDING_ASSET_ID = "extra_pending_asset_id"
        const val EXTRA_COLLABORATION_SESSION_ID = "extra_collaboration_session_id"
        private const val MIN_BRUSH_SIZE = 1f
        private const val MAX_BRUSH_SIZE = 3000f
        private const val BRUSH_SIZE_SLIDER_STEPS = 1000

        /** Everything the four-finger tap hides, leaving only the canvas. */
        private val CHROME_IDS = intArrayOf(
            R.id.toolbar_left, R.id.toolbar_right, R.id.sidebar_slider, R.id.undo_redo_panel, R.id.urban_toolbar_dock
        )
    }

    private fun setupLeftToolbar() {
        findViewById<ImageButton>(R.id.btn_actions).setOnClickListener { anchor ->
            ActionsPanel().apply {
                arguments = com.procreate.android.ui.common.PanelUi.dockArguments(panelSideFor(anchor))
            }.show(supportFragmentManager, "ActionsPanel")
        }
        findViewById<ImageButton>(R.id.btn_library).setOnClickListener {
            openWorkspaceLibrary()
        }
        findViewById<ImageButton>(R.id.btn_adjustments).setOnClickListener(::openAdjustmentsPanel)
        findViewById<ImageButton>(R.id.btn_selection).setOnClickListener { anchor ->
            showSelectionMenu(anchor)
        }
        findViewById<ImageButton>(R.id.btn_transform).setOnClickListener { anchor ->
            showTransformMenu(anchor)
        }
        findViewById<ImageButton>(R.id.btn_urban).setOnClickListener { toggleUrbanWorkspace() }
        findViewById<ImageButton>(R.id.btn_more_tools).setOnClickListener(::showCompactToolsMenu)
        setupUrbanToolbarDock()
    }

    /** Switches the canvas into Urban CAD mode: shared by the manual toolbar button and by
     * arriving straight from the Gallery's "Urban Planning" entry card. */
    private fun activateUrbanMode() {
        currentProjectType = ProjectType.URBAN_DESIGN
        drawingView.isUrbanMode = true
        updateWorkspaceChrome(isUrban = true)
        val dock = findViewById<com.procreate.android.urban.ui.UrbanToolbarDock>(R.id.urban_toolbar_dock)
        dock.visibility = View.VISIBLE
        if (drawingView.activeUrbanTool == null) {
            drawingView.activeUrbanTool = com.procreate.android.urban.model.UrbanToolType.SITE_BOUNDARY
        }
        dock.updateActiveTool(drawingView.activeUrbanTool!!, drawingView.urbanScaleConfig)
        findViewById<ImageButton>(R.id.btn_urban).isSelected = true
        findViewById<ImageButton>(R.id.btn_brush).isSelected = false
        findViewById<ImageButton>(R.id.btn_eraser).isSelected = false
        findViewById<ImageButton>(R.id.btn_smudge).isSelected = false
        Toast.makeText(this, "تم تفعيل بيئة عمل التخطيط الحضري", Toast.LENGTH_SHORT).show()
    }

    private fun setupUrbanToolbarDock() {
        val dock = findViewById<com.procreate.android.urban.ui.UrbanToolbarDock>(R.id.urban_toolbar_dock) ?: return
        dock.onExitUrbanMode = {
            drawingView.isUrbanMode = false
            viewModel.markProjectChanged()
            dock.visibility = View.GONE
            findViewById<ImageButton>(R.id.btn_urban).isSelected = false
            switchToTool(ToolMode.DRAW)
            Toast.makeText(this, "تم الخروج من وضع التخطيط", Toast.LENGTH_SHORT).show()
        }
        dock.onOpenUrbanPanel = {
            openUrbanPanel()
        }
        dock.onToggleInputMode = { mode ->
            updateUrbanProject { drawingView.urbanInputMode = mode }
            Toast.makeText(this, "نمط الإدخال: ${mode.titleAr}", Toast.LENGTH_SHORT).show()
        }
        dock.onStartScaleCalibration = {
            drawingView.isCalibratingScale = true
            Toast.makeText(this, "اسحب بالقلم بين نقطتين معلومتين للمعايرة", Toast.LENGTH_LONG).show()
        }
        dock.onStartCalibrationFromElement = {
            drawingView.isPickingCalibrationVertices = true
            Toast.makeText(this, "اضغط على نقطتين من عنصر مرسوم مسبقاً بمسافة حقيقية معلومة", Toast.LENGTH_LONG).show()
        }
        dock.onDeleteSelectedElement = {
            drawingView.deleteSelectedUrbanElement()
        }
        dock.onClearSelectedElement = {
            drawingView.clearUrbanSelection()
        }
        dock.onEnterSelectionMode = {
            // Same underlying state as "no tool active" - tapping any drawn element already
            // works there, but there was no dedicated, discoverable button for it before now.
            if (drawingView.activeUrbanTool != null) {
                drawingView.activeUrbanTool = null
                drawingView.clearCurrentUrbanPoly()
                dock.exitToolDrawingMode()
            }
            Toast.makeText(this, "اضغط على أي عنصر مرسوم لتحديده وتعديله", Toast.LENGTH_SHORT).show()
        }
        dock.onSelectedElementSizeChanged = { newSize ->
            // setSelectedUrbanElementSize clamps per element type (a boundary/axis' stroke width
            // to 4-80, a marker's radius to 8-200) - the dock's own stepper doesn't know those
            // per-type limits and kept counting past them (e.g. up to 200 for a boundary whose
            // line visually maxes out at 80), so pressing +/- past the real limit looked like
            // resizing had stopped working even though the number on screen kept changing. Reading
            // the actual clamped value back and pushing it into the dock keeps its displayed
            // number - and what the next +/- press starts from - matched to reality.
            drawingView.setSelectedUrbanElementSize(newSize)
            dock.updateSelectedElementInfo(drawingView.selectedUrbanElement, drawingView.selectedUrbanElementSize())
        }
        dock.onSelectedElementColorPickRequested = {
            val colorPicker = ColorPickerPanel()
            colorPicker.setOnColorSelectedListener { color ->
                drawingView.setSelectedUrbanElementColor(color)
                dock.updateSelectedElementInfo(drawingView.selectedUrbanElement, drawingView.selectedUrbanElementSize())
            }
            colorPicker.show(supportFragmentManager, "ColorPickerPanel")
        }
        dock.onToggleGrid = {
            updateUrbanProject {
                drawingView.urbanScaleConfig.isGridVisible = !drawingView.urbanScaleConfig.isGridVisible
            }
            drawingView.invalidate()
        }
        dock.onToggleLegend = {
            updateUrbanProject { drawingView.showLiveLegend = !drawingView.showLiveLegend }
            drawingView.invalidate()
        }
        dock.onCycleScaleCardPlacement = {
            val newPos = drawingView.cycleScaleCardPlacement()
            Toast.makeText(this, "موضع المقياس: ${newPos.titleAr}", Toast.LENGTH_SHORT).show()
        }
        dock.onCycleLegendPlacement = {
            val newPos = drawingView.cycleLegendPlacement()
            Toast.makeText(this, "موضع المفتاح: ${newPos.titleAr}", Toast.LENGTH_SHORT).show()
        }
        dock.onToggleTables = {
            updateUrbanProject { drawingView.showLiveTables = !drawingView.showLiveTables }
            drawingView.invalidate()
        }
        dock.onCycleTablePlacement = {
            val newPos = drawingView.cycleTablePlacement()
            Toast.makeText(this, "موضع الجداول: ${newPos.titleAr}", Toast.LENGTH_SHORT).show()
        }
        dock.onFinishPolygon = {
            drawingView.finishCurrentUrbanPoly()
        }
        dock.onUndoPoint = {
            drawingView.undoLastUrbanPoint()
        }
        dock.onClearPoints = {
            drawingView.clearCurrentUrbanPoly()
        }

        drawingView.onUrbanNodesChanged = { count ->
            dock.updateNodeCount(count)
        }

        dock.onToolSizeChanged = { newSize ->
            updateUrbanProject { drawingView.urbanScaleConfig.activeToolStrokeWidth = newSize }
            drawingView.invalidate()
        }
        dock.onNodeDistanceChanged = { newDist ->
            updateUrbanProject { drawingView.urbanScaleConfig.nodeDistanceMeters = newDist }
            drawingView.invalidate()
        }
        dock.onDeactivateTool = {
            drawingView.activeUrbanTool = null
            drawingView.clearCurrentUrbanPoly()
            drawingView.invalidate()
        }
        dock.onToolColorPickRequested = {
            val colorPicker = ColorPickerPanel()
            colorPicker.setOnColorSelectedListener { color ->
                updateUrbanProject { drawingView.urbanScaleConfig.activeToolColor = color }
                dock.updateColorSwatch(color)
                drawingView.invalidate()
            }
            colorPicker.show(supportFragmentManager, "ColorPickerPanel")
        }

        dock.onOpenToolSettings = {
            val tool = drawingView.activeUrbanTool ?: com.procreate.android.urban.model.UrbanToolType.SITE_BOUNDARY
            val before = drawingView.createUrbanProjectSnapshot()
            val dlg = com.procreate.android.urban.ui.UrbanToolSettingsDialog(
                tool = tool,
                scaleConfig = drawingView.urbanScaleConfig,
                onSettingsApplied = { cfg, color ->
                    drawingView.urbanScaleConfig.activeToolStrokeWidth = cfg.activeToolStrokeWidth
                    drawingView.urbanScaleConfig.nodeDistanceMeters = cfg.nodeDistanceMeters
                    drawingView.urbanScaleConfig.activeToolColor = color
                    drawingView.commitUrbanProjectChange(before)
                    dock.updateColorSwatch(color)
                    drawingView.invalidate()
                }
            )
            dlg.show(supportFragmentManager, "UrbanToolSettingsDialog")
        }

        dock.onOpenExportDialog = {
            val dlg = com.procreate.android.urban.ui.UrbanExportDialog(
                onExportConfirmed = { format, isTransparent, isFullCanvas, jpegQuality, includeOverlays ->
                    val runExport = {
                        drawingView.exportArtwork(
                            format = format,
                            isTransparent = isTransparent,
                            isFullCanvas = isFullCanvas,
                            jpegQuality = jpegQuality,
                            includeOverlays = includeOverlays
                        )
                    }
                    // Only the CAD path gets the summary gate: a PNG/PDF is visually verifiable the
                    // moment it opens, whereas a wrong-scale or empty DXF usually isn't discovered
                    // until it's already been sent on to someone else.
                    if (format == com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.DXF) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("ملخص التصدير إلى AutoCAD")
                            .setMessage(drawingView.urbanExportSummary())
                            .setPositiveButton("تصدير") { _, _ -> runExport() }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    } else runExport()
                }
            )
            dlg.show(supportFragmentManager, "UrbanExportDialog")
        }
        dock.onExportOverlaysRequested = {
            drawingView.exportOverlaySheet(com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PNG, jpegQuality = 95)
        }

        drawingView.onScaleCalibrationMeasured = { distPx, start, end ->
            showScaleCalibrationDialog(distPx, start, end)
        }
        drawingView.onCalibrationVerticesPicked = { distPx, start, end ->
            showScaleCalibrationDialog(distPx, start, end)
        }
        // Selecting a drawn element used to replace the *whole* toolbar dock with a thin editing
        // strip fixed at the dock's screen position (top/side) - correct functionally, but not
        // where a finger that just tapped a shape in the middle of the canvas expects the editing
        // controls to appear. A small popup anchored right next to that tap reads as "this popup
        // belongs to the thing I touched" instead of "something changed somewhere else on screen".
        drawingView.onUrbanElementSelected = { element ->
            if (element != null) {
                val anchor = drawingView.selectedUrbanElementScreenAnchor()
                showUrbanElementEditPopup(element, anchor.x, anchor.y)
            } else {
                dismissUrbanElementEditPopup()
            }
        }
        drawingView.onBatchSelectionCleared = { dismissBatchEditPopup() }
        drawingView.onArchitecturalAssetEditRequested = { instance ->
            openArchitecturalAssetProperties(instance)
        }
        drawingView.onUrbanExportCompleted = { dxf, raster ->
            showExportDestinationDialog(dxf, raster)
        }
        drawingView.onPremiumRequired = { retry -> showSubscription(retry) }
    }

    private val vectorizeImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val bitmap = runCatching {
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, "تعذّر قراءة الصورة", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        com.procreate.android.vectorize.VectorizeReviewDialog.show(
            supportFragmentManager, bitmap
        ) { paths, sourceWidth, sourceHeight ->
            importVectorizedPaths(paths, sourceWidth, sourceHeight)
        }
    }

    fun startImageVectorization() {
        if (!drawingView.isUrbanMode) activateUrbanMode()
        vectorizeImageLauncher.launch("image/*")
    }

    private val analyzePlanLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val bitmap = runCatching {
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, "تعذّر قراءة الصورة", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        com.procreate.android.ai.PlanAnalysisDialog.show(
            supportFragmentManager, bitmap
        ) { features, sourceWidth, sourceHeight ->
            importAnalyzedFeatures(features, sourceWidth, sourceHeight)
        }
    }

    /**
     * Ordered so nothing irreversible happens before the user has agreed to it: consent, then the
     * key, then the picker. Asking for consent last - after they had already chosen a photo - would
     * mean the decision is made with the image already in hand, which is the wrong way round.
     */
    fun startPlanAnalysis() {
        if (!drawingView.isUrbanMode) activateUrbanMode()

        // The paywall comes before the privacy consent and before the picker: asking someone to
        // agree to an upload, or to choose a file, and only then telling them the feature costs
        // money, wastes their time and reads as a trick.
        if (!com.procreate.android.billing.Entitlements.isUnlocked(
                this, com.procreate.android.billing.Entitlements.Feature.PLAN_ANALYSIS
            )
        ) {
            showSubscription { startPlanAnalysis() }
            return
        }

        // Uploading a picture the user chose is a transfer of their personal data to a third party,
        // so it needs an affirmative yes once, not a notice they may never have read.
        if (!com.procreate.android.ai.AiPrivacyConsent.hasConsented(this)) {
            com.procreate.android.ai.AiPrivacyConsentDialog.show(supportFragmentManager) { agreed ->
                if (agreed) requestAnalysisKeyThenPick()
            }
            return
        }
        requestAnalysisKeyThenPick()
    }

    /**
     * A build that ships no API key (every Play release, by design) would otherwise walk the user
     * through picking an image only to dead-end on "no key configured". Asking for the key up front
     * turns that dead end into a one-time setup step, and the analysis continues straight after.
     */
    private fun requestAnalysisKeyThenPick() {
        if (!com.procreate.android.ai.AiKeyStore.hasAnyKey()) {
            com.procreate.android.ai.AiKeySettingsDialog.show(supportFragmentManager) {
                analyzePlanLauncher.launch("image/*")
            }
            return
        }
        analyzePlanLauncher.launch("image/*")
    }

    private fun importAnalyzedFeatures(
        features: List<com.procreate.android.ai.DetectedFeature>,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        val inserted = drawingView.insertAnalyzedFeatures(features, sourceWidth, sourceHeight)
        updateUrbanStatusBar()
        Toast.makeText(this, "تم إدراج $inserted عنصراً قابلاً للتحرير", Toast.LENGTH_LONG).show()
    }

    /**
     * Places accepted paths as ordinary, fully editable boundary elements - not as a locked import
     * layer. Once inserted they behave exactly like hand-drawn geometry: selectable, snappable,
     * undoable and exported to DXF like everything else.
     */
    private fun importVectorizedPaths(
        paths: List<com.procreate.android.vectorize.TracedPath>,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        if (paths.isEmpty()) return
        val inserted = drawingView.insertVectorizedPaths(paths, sourceWidth, sourceHeight)
        updateUrbanStatusBar()
        Toast.makeText(this, "تم إدراج $inserted عنصراً قابلاً للتحرير", Toast.LENGTH_LONG).show()
    }

    /** Files pending a "save as" through the system picker, held because the picker returns
     * asynchronously and the result callback gets only a destination Uri, not the source. */
    private var pendingSaveAsFile: java.io.File? = null

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val source = pendingSaveAsFile
        pendingSaveAsFile = null
        if (uri == null || source == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        }.isSuccess
        Toast.makeText(
            this,
            if (ok) "تم حفظ ${source.name} في الموقع المختار" else "تعذّر الحفظ في الموقع المختار",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Separates the three things "export" used to conflate: the file is already saved to Downloads
     * by this point, so the remaining question is only whether the user also wants it somewhere
     * specific or sent to another app.
     */
    private fun showExportDestinationDialog(dxf: java.io.File, raster: java.io.File?) {
        val options = arrayOf("حفظ في موقع أختاره…", "مشاركة الملف", "تم — الملف في مجلد التنزيلات")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("تم التصدير: ${dxf.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingSaveAsFile = dxf
                        saveAsLauncher.launch(dxf.name)
                    }
                    1 -> {
                        val manager = com.procreate.android.export.ExportManager(this)
                        if (raster != null) {
                            manager.shareMultiple(listOf(dxf, raster), "application/octet-stream")
                        } else {
                            manager.shareDirectly(dxf, "application/octet-stream")
                        }
                    }
                }
            }
            .show()
    }

    /** Switches the crowded tablet chrome to four primary buttons on each side of a phone. */
    private fun configureResponsiveCanvasChrome() {
        val config = resources.configuration
        compactCanvasChrome = config.screenWidthDp < 760 || config.screenHeightDp < 520
        if (!compactCanvasChrome) return

        intArrayOf(
            R.id.btn_library,
            R.id.btn_adjustments,
            R.id.btn_filters,
            R.id.btn_smudge,
            R.id.btn_blur,
            R.id.btn_urban,
            R.id.divider_left_urban
        ).forEach { id -> findViewById<View>(id).visibility = View.GONE }
        findViewById<View>(R.id.btn_more_tools).visibility = View.VISIBLE
        findViewById<com.procreate.android.urban.ui.UrbanToolbarDock>(R.id.urban_toolbar_dock)
            .setCompactMode(true)

        fun tightenToolbar(id: Int) {
            val toolbar = findViewById<android.widget.LinearLayout>(id)
            val horizontalPadding = com.procreate.android.ui.common.PanelUi.dp(this, 2)
            toolbar.setPadding(horizontalPadding, toolbar.paddingTop, horizontalPadding, toolbar.paddingBottom)
            toolbar.layoutParams = toolbar.layoutParams.apply {
                height = com.procreate.android.ui.common.PanelUi.dp(this@CanvasActivity, 44)
            }
            for (index in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(index)
                if (child !is ImageButton) continue
                val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                lp.width = com.procreate.android.ui.common.PanelUi.dp(this, 44)
                lp.height = com.procreate.android.ui.common.PanelUi.dp(this, 44)
                lp.setMargins(0, 0, 0, 0)
                lp.marginStart = 0
                lp.marginEnd = 0
                child.layoutParams = lp
            }
        }
        tightenToolbar(R.id.toolbar_left)
        tightenToolbar(R.id.toolbar_right)

        fun margins(id: Int, start: Int? = null, top: Int? = null, end: Int? = null, bottom: Int? = null) {
            val view = findViewById<View>(id)
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            start?.let(lp::setMarginStart)
            top?.let { lp.topMargin = it }
            end?.let(lp::setMarginEnd)
            bottom?.let { lp.bottomMargin = it }
            view.layoutParams = lp
        }

        val edge = com.procreate.android.ui.common.PanelUi.dp(this, 8)
        // The window already keeps content below a visible status bar. Retaining the tablet's
        // second 8dp top inset on a landscape phone made both pills look noticeably low and took
        // another strip away from the usable canvas. Keep only a very small breathing gap here.
        val compactTopGap = com.procreate.android.ui.common.PanelUi.dp(this, 2)
        // In Arabic layout toolbar_left is the settings/selection group and is now physically on
        // the left; toolbar_right is the painting group and sits over its sliders on the right.
        margins(R.id.toolbar_left, top = compactTopGap, end = edge)
        margins(R.id.toolbar_right, start = edge, top = compactTopGap)
        margins(R.id.sidebar_slider, start = edge)
        margins(R.id.undo_redo_panel, start = edge, bottom = edge)

        // Preserve 48dp touch targets while removing the tablet-only horizontal padding around
        // the vertical controls. The phone rail is intentionally compact, while the complete
        // control surface remains draggable rather than requiring a tiny thumb.
        val compactRail = findViewById<android.widget.LinearLayout>(R.id.sidebar_slider)
        val railVerticalPadding = com.procreate.android.ui.common.PanelUi.dp(this, 4)
        compactRail.setPadding(0, railVerticalPadding, 0, railVerticalPadding)
        (compactRail.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
            ?.let { lp ->
                val railHeightDp = (config.screenHeightDp * 0.46f).roundToInt().coerceIn(148, 180)
                lp.height = com.procreate.android.ui.common.PanelUi.dp(this, railHeightDp)
                lp.verticalBias = 0.5f
                compactRail.layoutParams = lp
            }

        // A 42dp rail is visually lighter than the previous 48dp block. Four dp of vertical inset
        // keeps the rounded track ends away from the enclosing panel, so neither looks cramped.
        val sliderWidth = com.procreate.android.ui.common.PanelUi.dp(this, 42)
        val trackHorizontalPadding = com.procreate.android.ui.common.PanelUi.dp(this, 13)
        val trackVerticalPadding = com.procreate.android.ui.common.PanelUi.dp(this, 4)
        intArrayOf(R.id.slider_size, R.id.slider_opacity).forEach { id ->
            findViewById<VerticalFillSlider>(id).apply {
                layoutParams = layoutParams.apply { width = sliderWidth }
                setPadding(
                    trackHorizontalPadding,
                    trackVerticalPadding,
                    trackHorizontalPadding,
                    trackVerticalPadding
                )
            }
        }

        // Keep the color control proportional to the narrower rail, with a quiet ring around a
        // 22dp swatch: large enough to read instantly, small enough not to split the rail in half.
        val colorIndicator = findViewById<View>(R.id.color_indicator)
        val colorFrame = colorIndicator.parent as? android.widget.FrameLayout
        colorFrame?.let { frame ->
            (frame.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                lp.width = com.procreate.android.ui.common.PanelUi.dp(this, 28)
                lp.height = com.procreate.android.ui.common.PanelUi.dp(this, 28)
                lp.topMargin = com.procreate.android.ui.common.PanelUi.dp(this, 6)
                lp.bottomMargin = com.procreate.android.ui.common.PanelUi.dp(this, 6)
                frame.layoutParams = lp
            }
            frame.getChildAt(0)?.layoutParams = frame.getChildAt(0).layoutParams.apply {
                width = com.procreate.android.ui.common.PanelUi.dp(this@CanvasActivity, 28)
                height = com.procreate.android.ui.common.PanelUi.dp(this@CanvasActivity, 28)
            }
            colorIndicator.layoutParams = colorIndicator.layoutParams.apply {
                width = com.procreate.android.ui.common.PanelUi.dp(this@CanvasActivity, 22)
                height = com.procreate.android.ui.common.PanelUi.dp(this@CanvasActivity, 22)
            }
        }

        val undoRedo = findViewById<android.widget.LinearLayout>(R.id.undo_redo_panel)
        undoRedo.setPadding(0, undoRedo.paddingTop, 0, undoRedo.paddingBottom)
        for (index in 0 until undoRedo.childCount) {
            val child = undoRedo.getChildAt(index)
            if (child !is ImageButton) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            lp.setMargins(0, 0, 0, 0)
            child.layoutParams = lp
        }
        // In Urban mode the contextual dock can be wider than the small primary bar. Put it on a
        // second row so both remain independently tappable instead of occupying the same pixels.
        margins(R.id.urban_toolbar_dock, top = com.procreate.android.ui.common.PanelUi.dp(this, 64))
    }

    /**
     * Keeps every floating control outside status/navigation bars and landscape display cutouts.
     * The canvas remains full-bleed; only the controls receive safe physical edge margins.
     */
    private fun applyTopChromeInsets() {
        val root = findViewById<View>(R.id.canvas_root)
        val topIds = listOf(
            R.id.toolbar_left,
            R.id.toolbar_right,
            R.id.urban_toolbar_dock,
            R.id.collaboration_status
        )
        // The camera cutout lives halfway down a landscape phone edge; applying that inset to the
        // top pills needlessly pushed only the left pill inward. Their own 8dp margins are safe at
        // the top corners and now make both sides optically symmetrical.
        val startIds = listOf(R.id.sidebar_slider, R.id.undo_redo_panel)
        val endIds = emptyList<Int>()
        val bottomIds = listOf(R.id.undo_redo_panel, R.id.urban_status_bar)
        val ids = (topIds + startIds + endIds + bottomIds).distinct()
        val baseMargins = ids.associateWith { id ->
            val lp = findViewById<View>(id)?.layoutParams as? ViewGroup.MarginLayoutParams
            intArrayOf(lp?.topMargin ?: 0, lp?.marginStart ?: 0, lp?.marginEnd ?: 0, lp?.bottomMargin ?: 0)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val rtl = androidx.core.view.ViewCompat.getLayoutDirection(root) ==
                androidx.core.view.ViewCompat.LAYOUT_DIRECTION_RTL
            val startInset = if (rtl) safe.right else safe.left
            val endInset = if (rtl) safe.left else safe.right
            for (id in ids) {
                val view = findViewById<View>(id) ?: continue
                val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                val base = baseMargins[id] ?: continue
                if (id in topIds) lp.topMargin = base[0] + safe.top
                if (id in startIds) lp.marginStart = base[1] + startInset
                if (id in endIds) lp.marginEnd = base[2] + endInset
                if (id in bottomIds) lp.bottomMargin = base[3] + safe.bottom
                view.layoutParams = lp
            }
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    /** Type and unit are picked first, then the canvas owns the point-by-point gesture itself. */
    private fun openMeasurementTool() {
        com.procreate.android.urban.ui.MeasurementToolSheet.show(
            context = this,
            initialUnit = drawingView.measurementDisplayUnit
        ) { kind, unit ->
            drawingView.startMeasurement(kind, unit)
            Toast.makeText(this, "اضغط على اللوحة لتحديد نقاط القياس", Toast.LENGTH_LONG).show()
        }
    }

    private fun openArchitecturalAssetLibrary() {
        com.procreate.android.urban.ui.ArchitecturalAssetPickerDialog.show(supportFragmentManager) { asset ->
            drawingView.beginArchitecturalAssetPlacement(asset.id)
            Toast.makeText(this, "اضغط على اللوحة لوضع: ${asset.nameAr}", Toast.LENGTH_LONG).show()
        }
    }

    /** Opens for a freshly placed resource as well as for one tapped later, so the same editor
     * covers both without the canvas needing to know which case it is. */
    /** The in-workspace library keeps imports, paper styles and reusable elements together. */
    private fun openWorkspaceLibrary() {
        com.procreate.android.ui.library.AssetLibraryDialog()
            .onImportImageSelected { workspaceImageLauncher.launch("image/*") }
            .onOnlineLibrarySelected { openOnlineLibrary() }
            .onBackgroundSelected { style ->
                viewModel.setBackgroundStyle(style)
                drawingView.invalidate()
                val label = when (style) {
                    CanvasBackgroundStyle.BLANK -> R.string.background_blank
                    CanvasBackgroundStyle.DOT_GRID -> R.string.background_dots
                    CanvasBackgroundStyle.LINE_GRID -> R.string.background_grid
                    CanvasBackgroundStyle.ISOMETRIC -> R.string.background_isometric
                }
                Toast.makeText(
                    this,
                    getString(R.string.library_applied_background, getString(label)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .onAssetSelected { asset ->
                if (!drawingView.isUrbanMode) activateUrbanMode()
                drawingView.beginArchitecturalAssetPlacement(asset.id)
                Toast.makeText(
                    this,
                    getString(R.string.library_place_asset, asset.nameAr),
                    Toast.LENGTH_LONG
                ).show()
            }
            .show(supportFragmentManager, "WorkspaceAssetLibrary")
    }

    private val workspaceImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        importWorkspaceImage(uri)
    }

    private fun importWorkspaceImage(uri: android.net.Uri) {
        val canvasLayer = viewModel.layers.value?.firstOrNull()
        if (canvasLayer == null) {
            Toast.makeText(this, R.string.library_image_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val canvasWidth = canvasLayer.bitmap.width
        val canvasHeight = canvasLayer.bitmap.height

        lifecycleScope.launch {
            val importedLayer = withContext(Dispatchers.IO) {
                runCatching {
                    val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Image decoder returned no bitmap")
                    fitImageToCanvas(source, canvasWidth, canvasHeight)
                }.getOrNull()
            }
            if (importedLayer == null) {
                Toast.makeText(this@CanvasActivity, R.string.library_image_import_failed, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.addLayerFromBitmap(importedLayer)
                drawingView.invalidate()
                Toast.makeText(this@CanvasActivity, R.string.library_image_imported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOnlineLibrary() {
        com.procreate.android.ui.library.OpenverseLibraryDialog()
            .onSelected(::importOpenverseImage)
            .show(supportFragmentManager, "OpenverseLibrary")
    }

    private fun importOpenverseImage(image: com.procreate.android.ui.library.OpenverseImage) {
        val canvasLayer = viewModel.layers.value?.firstOrNull()
        if (canvasLayer == null) {
            Toast.makeText(this, R.string.openverse_download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val canvasWidth = canvasLayer.bitmap.width
        val canvasHeight = canvasLayer.bitmap.height
        Toast.makeText(this, R.string.openverse_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val source = runCatching {
                com.procreate.android.ui.library.OpenverseClient.download(image, canvasWidth, canvasHeight)
            }.getOrNull()
            val importedLayer = source?.let {
                withContext(Dispatchers.Default) { fitImageToCanvas(it, canvasWidth, canvasHeight) }
            }
            if (importedLayer == null) {
                Toast.makeText(
                    this@CanvasActivity,
                    R.string.openverse_download_failed,
                    Toast.LENGTH_LONG
                ).show()
            } else {
                viewModel.addLayerFromBitmap(importedLayer, image.layerName())
                drawingView.invalidate()
                Toast.makeText(
                    this@CanvasActivity,
                    R.string.openverse_imported,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun fitImageToCanvas(source: Bitmap, canvasWidth: Int, canvasHeight: Int): Bitmap {
        val scale = minOf(
            canvasWidth.toFloat() / source.width,
            canvasHeight.toFloat() / source.height,
            1f
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scaledWidth == source.width && scaledHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true).also { source.recycle() }
        }
        return Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888).also { layer ->
            Canvas(layer).drawBitmap(
                scaled,
                (canvasWidth - scaledWidth) / 2f,
                (canvasHeight - scaledHeight) / 2f,
                null
            )
            scaled.recycle()
        }
    }

    private fun openArchitecturalAssetProperties(
        instance: com.procreate.android.urban.assets.AssetInstance
    ) {
        com.procreate.android.urban.ui.ArchitecturalAssetPropertiesDialog.show(
            fragmentManager = supportFragmentManager,
            assetInstance = instance,
            defaultColorArgb = com.procreate.android.urban.assets.BuiltInArchitecturalAssets
                .catalog[instance.assetId]?.defaultColorArgb,
            onApply = { updated -> drawingView.updateSelectedArchitecturalAsset(updated) },
            onDelete = { _ -> drawingView.deleteSelectedArchitecturalAsset() }
        )
    }

    private var urbanEditPopup: android.widget.PopupWindow? = null
    private var urbanEditPopupElement: com.procreate.android.urban.model.UrbanElement? = null
    private var urbanEditPopupRefresh: (() -> Unit)? = null
    /** The size and spacing strips are each built inside their own conditional block (a hatch
     * polygon may show only one of them, a path both, a marker only size), so their locals aren't
     * in scope where urbanEditPopupRefresh needs to touch them - these let each strip register
     * its own small refresh callback instead of urbanEditPopupRefresh reaching into stale locals. */
    private var urbanEditPopupSizeRefresh: (() -> Unit)? = null
    private var urbanEditPopupSpacingRefresh: (() -> Unit)? = null

    private fun dismissUrbanElementEditPopup() {
        urbanEditPopup?.dismiss()
        urbanEditPopup = null
        urbanEditPopupElement = null
        urbanEditPopupRefresh = null
    }

    /** The near-tap replacement for the old dock-anchored selection bar: one small popup with two
     * strips (size/spacing, then color+delete) positioned right next to the touch that selected
     * the element, dismissed either by its own delete action or by tapping anywhere else.
     *
     * DrawingView re-fires the selection callback for the *same* element continuously while it's
     * being dragged (so this stays live instead of only updating once the finger lifts) and once
     * more right as the drag ends. Tearing the popup down and rebuilding it fresh on every one of
     * those calls used to run it through the dismiss-and-clear-selection path below on the very
     * first tap - selecting an element would show the popup, and the drag-end resync would
     * immediately dismiss-then-recreate it, which cleared the selection in between because the
     * dismiss listener couldn't tell "replaced for the same still-selected element" apart from
     * "actually dismissed". Refreshing the existing popup's values in place for that case, rather
     * than dismissing and recreating it, sidesteps that entirely and is also just the right
     * behaviour for "this popup should stay put and update live while the shape changes". */
    private class SliderRowViews(val row: View, val seekBar: android.widget.SeekBar, val txtValue: android.widget.TextView)

    /** One labeled drag-slider row shared by the single-element and batch edit popups - just the
     * views; the caller wires its own onSeekBarChangeListener since what a change should *do*
     * differs between them (one element's field vs every element in a batch). */
    private fun buildSliderRow(label: String, initial: Float, min: Int, max: Int): SliderRowViews {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(android.widget.TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, dp(6), 0)
        })
        val seekBar = android.widget.SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = initial.toInt().coerceIn(min, max)
            progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.procreate_accent))
            thumbTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.procreate_accent))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(150), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(seekBar)
        val txtValue = android.widget.TextView(this).apply {
            text = "${initial.toInt()}"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(28), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(txtValue)
        return SliderRowViews(row, seekBar, txtValue)
    }

    private fun showUrbanElementEditPopup(element: com.procreate.android.urban.model.UrbanElement, anchorX: Float, anchorY: Float) {
        if (urbanEditPopup != null && urbanEditPopupElement === element) {
            urbanEditPopupRefresh?.invoke()
            return
        }
        dismissUrbanElementEditPopup()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_toolbar_pill)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        column.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.TextView(this@CanvasActivity).apply {
                text = element.toolType.titleAr
                textSize = 12f
                setTextColor(getColor(R.color.procreate_accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(6))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Now that outside taps no longer auto-dismiss (see the popup's own comment below),
            // this is the only non-destructive way to close the popup without also deselecting
            // by tapping empty canvas.
            addView(android.widget.TextView(this@CanvasActivity).apply {
                text = "✕"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener { dismissUrbanElementEditPopup() }
            })
        })

        // Strip 1: "size" (stroke width for a path/boundary, radius for a point marker) - a
        // hatch polygon has no meaningful stroke width, so it skips this strip entirely rather
        // than showing a slider with nothing to control.
        val isHatch = element is com.procreate.android.urban.model.UrbanElement.HatchPolygon
        // Only BoundaryPath stores a nodeSpacingPx and has a renderer that resamples display
        // nodes from it (see UrbanSymbolRenderer.renderBoundary) - an ArrowPath's numbered
        // stations aren't derived from spacing at all, so showing this slider for one would be a
        // no-op with nothing to actually move.
        val isBoundary = element is com.procreate.android.urban.model.UrbanElement.BoundaryPath
        if (!isHatch) {
            var sizeValue = drawingView.selectedUrbanElementSize() ?: 0f
            val sizeSlider = buildSliderRow("الحجم", sizeValue, 2, 200)
            var suppressSizeCallback = false
            fun applySize(newValue: Float) {
                sizeValue = newValue
                drawingView.setSelectedUrbanElementSize(sizeValue)
                sizeValue = drawingView.selectedUrbanElementSize() ?: sizeValue
                sizeSlider.txtValue.text = "${sizeValue.toInt()}"
                suppressSizeCallback = true
                sizeSlider.seekBar.progress = sizeValue.toInt()
                suppressSizeCallback = false
            }
            sizeSlider.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !suppressSizeCallback) applySize(progress.toFloat())
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
            })
            column.addView(sizeSlider.row)
            urbanEditPopupSizeRefresh = {
                val current = drawingView.selectedUrbanElementSize()
                if (current != null) {
                    sizeValue = current
                    sizeSlider.txtValue.text = "${sizeValue.toInt()}"
                    suppressSizeCallback = true
                    sizeSlider.seekBar.progress = sizeValue.toInt()
                    suppressSizeCallback = false
                }
            }
        } else {
            urbanEditPopupSizeRefresh = null
        }

        // Strip 1b: "spacing" - hatch density for a filled polygon, or the distance between the
        // numbered nodes along a path (resampling the actual vertex list, not just a display
        // number - see DrawingView.setSelectedUrbanElementNodeSpacing). A point marker has
        // neither and shows no spacing strip at all.
        if (isHatch || isBoundary) {
            var spacingValue = (if (isHatch) drawingView.selectedUrbanElementSpacing() else drawingView.selectedUrbanElementNodeSpacingMeters()) ?: 0f
            val spacingRange = if (isHatch) 12 to 120 else 5 to 100
            val spacingSlider = buildSliderRow("التباعد", spacingValue, spacingRange.first, spacingRange.second)
            var suppressSpacingCallback = false
            fun applySpacing(newValue: Float) {
                spacingValue = newValue
                if (isHatch) drawingView.setSelectedUrbanElementSpacing(spacingValue) else drawingView.setSelectedUrbanElementNodeSpacing(spacingValue)
                spacingValue = (if (isHatch) drawingView.selectedUrbanElementSpacing() else drawingView.selectedUrbanElementNodeSpacingMeters()) ?: spacingValue
                spacingSlider.txtValue.text = "${spacingValue.toInt()}"
                suppressSpacingCallback = true
                spacingSlider.seekBar.progress = spacingValue.toInt()
                suppressSpacingCallback = false
            }
            spacingSlider.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !suppressSpacingCallback) applySpacing(progress.toFloat())
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
            })
            column.addView(spacingSlider.row)
            urbanEditPopupSpacingRefresh = {
                val current = if (isHatch) drawingView.selectedUrbanElementSpacing() else drawingView.selectedUrbanElementNodeSpacingMeters()
                if (current != null) {
                    spacingValue = current
                    spacingSlider.txtValue.text = "${spacingValue.toInt()}"
                    suppressSpacingCallback = true
                    spacingSlider.seekBar.progress = spacingValue.toInt()
                    suppressSpacingCallback = false
                }
            }
        } else {
            urbanEditPopupSpacingRefresh = null
        }

        // Strip 1b: select every element of this same tool type at once, for a batch size/
        // spacing/color edit without tapping each one individually.
        column.addView(android.widget.TextView(this).apply {
            text = "▦ تحديد المتشابه"
            textSize = 12f
            setTextColor(getColor(R.color.procreate_accent))
            setBackgroundResource(R.drawable.selector_icon_button)
            setPadding(dp(4), dp(6), dp(4), dp(2))
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                val batch = drawingView.selectSimilarToSelected()
                // dismissUrbanElementEditPopup()'s own listener already clears the single-element
                // selection here (element still matches drawingView.selectedUrbanElement).
                dismissUrbanElementEditPopup()
                if (batch.size > 1) {
                    showBatchEditPopup(batch, anchorX, anchorY)
                } else {
                    Toast.makeText(this@CanvasActivity, "لا توجد عناصر أخرى من نفس النوع", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Strip 2: color + delete.
        val actionRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val colorSwatch = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(element.color)
                setStroke(dp(1), android.graphics.Color.WHITE)
            }
            setOnClickListener {
                val colorPicker = ColorPickerPanel()
                colorPicker.setOnColorSelectedListener { color ->
                    drawingView.setSelectedUrbanElementColor(color)
                    (background as? GradientDrawable)?.setColor(color)
                }
                colorPicker.show(supportFragmentManager, "ColorPickerPanel")
            }
        }
        actionRow.addView(colorSwatch)
        actionRow.addView(android.widget.TextView(this).apply {
            text = "حذف"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.selector_icon_button)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                drawingView.deleteSelectedUrbanElement()
                dismissUrbanElementEditPopup()
            }
        })
        column.addView(actionRow)

        // Not focusable and not outside-touchable: a focusable popup grabs the window's input
        // focus, which was quietly swallowing the very next touch on (or near) the element it's
        // editing - Android read that as an outside touch on the *popup* and dismissed it before
        // DrawingView ever saw the touch, which is what made the element "not let itself be
        // pressed while selected". A plain, non-focusable popup lets every touch outside its own
        // small bounds pass straight through to the canvas, so dragging a vertex or the body
        // right next to (or under) the popup keeps working. It's dismissed only by its own ✕
        // button, a delete, or DrawingView's own selection changing (see onUrbanElementSelected
        // above and clearUrbanSelection's callers) - never by an incidental outside tap.
        val popup = android.widget.PopupWindow(
            column, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, false
        ).apply {
            isOutsideTouchable = false
            isTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        // Dismissing this popup should clear the selection too, EXCEPT when the dismissal is
        // this same function tearing an old popup down to replace it with a new selection's -
        // by then selectedUrbanElement already points at the new element, so the identity check
        // tells the two cases apart instead of the fresh selection wiping itself out immediately.
        popup.setOnDismissListener {
            if (urbanEditPopup === popup) {
                urbanEditPopup = null
                urbanEditPopupElement = null
                urbanEditPopupRefresh = null
                urbanEditPopupSizeRefresh = null
                urbanEditPopupSpacingRefresh = null
            }
            if (drawingView.selectedUrbanElement === element) {
                drawingView.clearUrbanSelection()
            }
        }
        urbanEditPopup = popup
        urbanEditPopupElement = element
        urbanEditPopupRefresh = {
            // Re-reads from the model rather than re-deriving locally, since the change that
            // triggered this refresh - a canvas-side vertex/body drag - already wrote the new
            // value there directly, bypassing applySize()/applySpacing(). Each strip's own
            // suppressed-callback dance lives with it (see urbanEditPopupSizeRefresh/
            // urbanEditPopupSpacingRefresh above), since only one or both may even be present.
            urbanEditPopupSizeRefresh?.invoke()
            urbanEditPopupSpacingRefresh?.invoke()
            (colorSwatch.background as? GradientDrawable)?.setColor(element.color)
        }

        column.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupW = column.measuredWidth
        val popupH = column.measuredHeight
        val screenW = resources.displayMetrics.widthPixels
        val margin = dp(8)
        val x = (anchorX - popupW / 2f).coerceIn(margin.toFloat(), (screenW - popupW - margin).toFloat())
        val aboveY = anchorY - popupH - dp(24)
        val y = if (aboveY < margin) anchorY + dp(24) else aboveY
        popup.showAtLocation(drawingView.rootView, android.view.Gravity.NO_GRAVITY, x.toInt(), y.toInt())
    }

    private var batchEditPopup: android.widget.PopupWindow? = null

    private fun dismissBatchEditPopup() {
        batchEditPopup?.dismiss()
        batchEditPopup = null
    }

    /** "تحديد المتشابه" (select similar) in the single-element popup lands here instead: every
     * element sharing that element's tool type, highlighted together on the canvas, with one
     * slider/color/delete acting on the whole batch at once rather than one element's own fields. */
    private fun showBatchEditPopup(elements: List<com.procreate.android.urban.model.UrbanElement>, anchorX: Float, anchorY: Float) {
        dismissBatchEditPopup()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val sample = elements.first()
        val isHatch = sample is com.procreate.android.urban.model.UrbanElement.HatchPolygon

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_toolbar_pill)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        column.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.TextView(this@CanvasActivity).apply {
                text = "${elements.size} × ${sample.toolType.titleAr}"
                textSize = 12f
                setTextColor(getColor(R.color.procreate_accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(6))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(android.widget.TextView(this@CanvasActivity).apply {
                text = "✕"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.selector_icon_button)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setOnClickListener { dismissBatchEditPopup() }
            })
        })

        val initial = when (sample) {
            is com.procreate.android.urban.model.UrbanElement.HatchPolygon -> sample.hatchSpacing
            is com.procreate.android.urban.model.UrbanElement.ArrowPath -> sample.strokeWidth
            is com.procreate.android.urban.model.UrbanElement.BoundaryPath -> sample.strokeWidth
            is com.procreate.android.urban.model.UrbanElement.PointMarker -> sample.radius
        }
        val slider = buildSliderRow(if (isHatch) "التباعد" else "الحجم", initial, if (isHatch) 12 else 2, if (isHatch) 120 else 200)
        slider.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    drawingView.setBatchElementsSize(progress.toFloat())
                    slider.txtValue.text = "$progress"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })
        column.addView(slider.row)

        val actionRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        actionRow.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(sample.color)
                setStroke(dp(1), android.graphics.Color.WHITE)
            }
            setOnClickListener {
                val colorPicker = ColorPickerPanel()
                colorPicker.setOnColorSelectedListener { color ->
                    drawingView.setBatchElementsColor(color)
                    (background as? GradientDrawable)?.setColor(color)
                }
                colorPicker.show(supportFragmentManager, "ColorPickerPanel")
            }
        })
        actionRow.addView(android.widget.TextView(this).apply {
            text = "حذف الكل"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.selector_icon_button)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                drawingView.deleteBatchElements()
                dismissBatchEditPopup()
            }
        })
        column.addView(actionRow)

        // Non-focusable/non-outside-touchable for the same reason as the single-element popup -
        // see its own comment - so an incidental touch near the batch's highlighted elements
        // doesn't get read as "outside" and dismiss this before it reaches the canvas.
        val popup = android.widget.PopupWindow(
            column, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, false
        ).apply {
            isOutsideTouchable = false
            isTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        popup.setOnDismissListener {
            if (batchEditPopup === popup) batchEditPopup = null
            drawingView.clearBatchSelection()
        }
        batchEditPopup = popup

        column.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupW = column.measuredWidth
        val popupH = column.measuredHeight
        val screenW = resources.displayMetrics.widthPixels
        val margin = dp(8)
        val x = (anchorX - popupW / 2f).coerceIn(margin.toFloat(), (screenW - popupW - margin).toFloat())
        val aboveY = anchorY - popupH - dp(24)
        val y = if (aboveY < margin) anchorY + dp(24) else aboveY
        popup.showAtLocation(drawingView.rootView, android.view.Gravity.NO_GRAVITY, x.toInt(), y.toInt())
    }

    /** Shared by both calibration entry points - a fresh two-point drag, or picking two
     * vertices of an element already drawn on the plan - since both just measure a pixel
     * distance and need the same "what real-world distance is this" dialog afterward. */
    private fun showScaleCalibrationDialog(distPx: Float, start: PointF, end: PointF) {
        val before = drawingView.createUrbanProjectSnapshot()
        val dlg = com.procreate.android.urban.ui.InteractiveScaleDialog(
            pixelDistance = distPx,
            config = drawingView.urbanScaleConfig,
            onScaleConfirmed = { _, _ ->
                // Calibration defines how the current drawing maps to metres. Resizing that same
                // drawing here would immediately invalidate the two-point ratio just confirmed.
                drawingView.recomputeAllUrbanMetrics()
                drawingView.commitUrbanProjectChange(before)
                drawingView.invalidate()
            }
        )
        dlg.show(supportFragmentManager, "InteractiveScaleDialog")
    }

    private fun openUrbanPanel() {
        var panelSnapshot = drawingView.createUrbanProjectSnapshot()
        val urbanPanel = com.procreate.android.urban.ui.UrbanPanel(
            scaleConfig = drawingView.urbanScaleConfig,
            legendManager = drawingView.urbanLegendManager,
            onToolSelected = { tool ->
                drawingView.clearUrbanSelection()
                drawingView.activeUrbanTool = tool
                val dock = findViewById<com.procreate.android.urban.ui.UrbanToolbarDock>(R.id.urban_toolbar_dock)
                dock.updateActiveTool(tool, drawingView.urbanScaleConfig)
                updateUrbanStatusBar()
                findViewById<ImageButton>(R.id.btn_urban).isSelected = true
                findViewById<ImageButton>(R.id.btn_brush).isSelected = false
                findViewById<ImageButton>(R.id.btn_eraser).isSelected = false
                findViewById<ImageButton>(R.id.btn_smudge).isSelected = false
            },
            onScaleConfigChanged = { _ ->
                drawingView.recomputeAllUrbanMetrics()
                drawingView.commitUrbanProjectChange(panelSnapshot)
                panelSnapshot = drawingView.createUrbanProjectSnapshot()
                drawingView.invalidate()
            },
            onStampBitmap = { bitmap ->
                drawingView.stampBitmapOnActiveLayer(bitmap)
            },
            urbanElementsProvider = { drawingView.urbanElements },
            onMeasurementToolRequested = { openMeasurementTool() },
            onAssetLibraryRequested = { openArchitecturalAssetLibrary() },
            snapProvider = { drawingView.urbanSnapSettings },
            onSnapSettingsChanged = {
                drawingView.urbanSnapSettings = it
                updateUrbanStatusBar()
            },
            onVectorizeImageRequested = { startImageVectorization() },
            onAnalyzePlanRequested = { startPlanAnalysis() }
        )
        urbanPanel.updateSelectedTool(drawingView.activeUrbanTool)
        urbanPanel.show(supportFragmentManager, "UrbanPanel")
    }

    private fun showSelectionMenu(anchor: android.view.View) {
        switchToTool(ToolMode.SELECTION)
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.selection_freehand))
        popup.menu.add(0, 2, 1, getString(R.string.selection_rectangle))
        popup.menu.add(0, 3, 2, getString(R.string.selection_ellipse))
        popup.menu.add(0, 4, 3, getString(R.string.selection_invert))
        popup.menu.add(0, 5, 4, getString(R.string.selection_clear))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> drawingView.setSelectionMode(SelectionTool.SelectionMode.FREEHAND)
                2 -> drawingView.setSelectionMode(SelectionTool.SelectionMode.RECTANGLE)
                3 -> drawingView.setSelectionMode(SelectionTool.SelectionMode.ELLIPSE)
                4 -> drawingView.invertSelection()
                5 -> {
                    drawingView.clearSelection()
                    viewModel.setToolMode(ToolMode.DRAW)
                }
            }
            true
        }
        popup.show()
    }

    private fun showTransformMenu(anchor: android.view.View) {
        if (viewModel.toolMode.value != ToolMode.TRANSFORM) {
            viewModel.setToolMode(ToolMode.TRANSFORM)
            if (!drawingView.beginTransform()) {
                viewModel.setToolMode(ToolMode.DRAW)
                Toast.makeText(this, R.string.layer_transform_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.transform_rotate_left))
        popup.menu.add(0, 2, 1, getString(R.string.transform_rotate_right))
        popup.menu.add(0, 3, 2, getString(R.string.transform_flip_h))
        popup.menu.add(0, 4, 3, getString(R.string.transform_flip_v))
        popup.menu.add(0, 5, 4, getString(R.string.transform_apply))
        popup.menu.add(0, 6, 5, getString(R.string.transform_cancel))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> drawingView.rotateTransform90(clockwise = false)
                2 -> drawingView.rotateTransform90(clockwise = true)
                3 -> drawingView.flipTransformHorizontal()
                4 -> drawingView.flipTransformVertical()
                5 -> {
                    drawingView.applyTransform()
                    viewModel.setToolMode(ToolMode.DRAW)
                }
                6 -> {
                    drawingView.cancelTransform()
                    viewModel.setToolMode(ToolMode.DRAW)
                }
            }
            true
        }
        popup.show()
    }

    /** Starts direct manipulation for the selected layer from the Layers panel. */
    fun startTransformForSelectedLayer(): Boolean {
        if (viewModel.toolMode.value == ToolMode.TRANSFORM) drawingView.applyTransform()
        switchToTool(ToolMode.TRANSFORM)
        val started = drawingView.beginTransform()
        if (!started) {
            viewModel.setToolMode(ToolMode.DRAW)
            Toast.makeText(this, R.string.layer_transform_unavailable, Toast.LENGTH_SHORT).show()
        }
        return started
    }

    /** Switches tool mode, baking in any pending transform first so work is never silently lost. */
    private fun switchToTool(mode: ToolMode) {
        drawingView.activeUrbanTool = null
        drawingView.isUrbanMode = false
        updateWorkspaceChrome(isUrban = false)
        findViewById<com.procreate.android.urban.ui.UrbanToolbarDock>(R.id.urban_toolbar_dock)?.visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_urban)?.isSelected = false
        if (viewModel.toolMode.value == ToolMode.TRANSFORM && mode != ToolMode.TRANSFORM) {
            drawingView.applyTransform()
        }
        viewModel.setToolMode(mode)
    }

    private fun setupRightToolbar() {
        findViewById<ImageButton>(R.id.btn_brush).setOnClickListener { anchor ->
            switchToTool(ToolMode.DRAW)
            val brushPanel = BrushPanel().apply {
                arguments = com.procreate.android.ui.common.PanelUi.dockArguments(panelSideFor(anchor))
            }
            brushPanel.show(supportFragmentManager, "BrushPanel")
        }
        findViewById<ImageButton>(R.id.btn_smudge).setOnClickListener { selectSmudgeTool() }
        findViewById<ImageButton>(R.id.btn_blur).setOnClickListener { selectBlurTool() }
        findViewById<ImageButton>(R.id.btn_filters).setOnClickListener(::openFiltersPanel)
        findViewById<ImageButton>(R.id.btn_eraser).setOnClickListener {
            switchToTool(ToolMode.DRAW)
            // Set brush type to eraser
            viewModel.currentBrush.value?.let { currentBrush ->
                currentBrush.type = com.procreate.android.canvas.BrushType.Eraser
                viewModel.setCurrentBrush(currentBrush)
            }
        }
        findViewById<ImageButton>(R.id.btn_layers).setOnClickListener { anchor ->
            LayersPanel.show(supportFragmentManager, panelSideFor(anchor))
        }
        val btnColors = findViewById<ImageButton>(R.id.btn_colors)
        setupRadialColorFan(btnColors)
    }

    private fun openAdjustmentsPanel(anchor: View) {
        com.procreate.android.adjust.AdjustmentsPanel().apply {
            arguments = com.procreate.android.ui.common.PanelUi.dockArguments(panelSideFor(anchor))
        }.show(supportFragmentManager, "AdjustmentsPanel")
    }

    private fun openFiltersPanel(anchor: View) {
        com.procreate.android.adjust.FiltersPanel.show(supportFragmentManager, panelSideFor(anchor))
    }

    private fun selectSmudgeTool() {
        switchToTool(ToolMode.SMUDGE)
    }

    private fun selectBlurTool() {
        switchToTool(ToolMode.BLUR)
        com.procreate.android.ui.common.PanelMotion.pulse(findViewById(R.id.tool_strength_label))
    }

    private fun toggleUrbanWorkspace() {
        if (!drawingView.isUrbanMode) activateUrbanMode() else openUrbanPanel()
    }

    /**
     * A phone in landscape has room for the primary drawing controls, but not for the tablet's
     * full pair of toolbars. Secondary tools live here instead of being clipped, overlapped, or
     * reduced below the 48dp minimum touch target.
     */
    private fun showCompactToolsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.library_title)
        popup.menu.add(0, 2, 1, R.string.control_adjustments)
        popup.menu.add(0, 3, 2, R.string.filters_title)
        popup.menu.add(0, 4, 3, R.string.tool_smudge).apply {
            isCheckable = true
            isChecked = viewModel.toolMode.value == ToolMode.SMUDGE
        }
        popup.menu.add(0, 5, 4, R.string.tool_blur).apply {
            isCheckable = true
            isChecked = viewModel.toolMode.value == ToolMode.BLUR
        }
        popup.menu.add(0, 6, 5, R.string.entry_urban_title).apply {
            isCheckable = true
            isChecked = drawingView.isUrbanMode
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openWorkspaceLibrary()
                2 -> openAdjustmentsPanel(anchor)
                3 -> openFiltersPanel(anchor)
                4 -> selectSmudgeTool()
                5 -> selectBlurTool()
                6 -> toggleUrbanWorkspace()
            }
            true
        }
        popup.show()
    }

    /** Physical edge of the trigger, independent of Arabic/English layout mirroring. */
    private fun panelSideFor(anchor: View): com.procreate.android.ui.common.PanelUi.DockSide {
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val centre = location[0] + anchor.width / 2
        return if (centre < resources.displayMetrics.widthPixels / 2) {
            com.procreate.android.ui.common.PanelUi.DockSide.LEFT
        } else {
            com.procreate.android.ui.common.PanelUi.DockSide.RIGHT
        }
    }

    private var activeColorFan: com.procreate.android.color.RadialColorFan? = null

    private fun applyPickedColor(color: Int) {
        viewModel.setCurrentColor(color)
        val indicator = findViewById<View>(R.id.color_indicator)
        (indicator.background.mutate() as GradientDrawable).setColor(color)
    }

    /**
     * A quick alternative to the full color panel, in the same spirit as Concept's radial color
     * dial: a single tap on the color button opens the ring of swatches right there - drag to a
     * swatch and lift to pick it, or lift anywhere else (or off the ring entirely) to close
     * without picking. It never permanently occupies interface space; it only exists between that
     * tap and the release that follows it. Holding the button instead (past the long-press
     * timeout, without having moved) opens the full color panel for finer control - the two
     * gestures share the same button without one shadowing the other.
     */
    private fun setupRadialColorFan(anchor: ImageButton) {
        val longPressMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressFired = false

        // This dedicated child is above the ConstraintLayout. Adding to android.R.id.content can
        // leave a new view behind the activity layout on some Android builds: it then renders in
        // logs but is invisible and lets gestures fall through to the drawing canvas.
        val overlayContainer = findViewById<android.widget.FrameLayout>(R.id.color_fan_overlay)
        val containerLoc = IntArray(2)
        val anchorLoc = IntArray(2)

        fun dismissFan() {
            // Called from inside the fan's own onTouchEvent (ACTION_UP/CANCEL) - removing it from
            // overlayContainer synchronously, right there, makes Android re-enter this same touch
            // dispatch with a synthesized CANCEL for the view it's mid-removal, which used to
            // call this function a second time on an already-detached view and crash. Clearing
            // the field first means that re-entrant call sees null and does nothing; posting the
            // actual removal defers it past the current touch dispatch entirely.
            val fan = activeColorFan ?: return
            activeColorFan = null
            fan.post { overlayContainer.removeView(fan) }
        }

        fun showFan() {
            if (activeColorFan != null) return
            overlayContainer.getLocationOnScreen(containerLoc)
            anchor.getLocationOnScreen(anchorLoc)
            val anchorX = (anchorLoc[0] - containerLoc[0] + anchor.width / 2f)
            val anchorY = (anchorLoc[1] - containerLoc[1] + anchor.height / 2f)
            // The color button sits in the top toolbar and, in this app's Arabic (RTL) layout,
            // that toolbar is mirrored to whichever screen edge "toolbar_right" ends up on - which
            // in practice has been the LEFT edge, not a fixed side a hardcoded rotation could
            // assume. Rather than guessing, point the ring's own angular midpoint straight at the
            // screen's center from wherever the anchor actually is, so its full circle (and
            // wherever a shade fan ends up extending from it) opens toward the open interior no
            // matter which corner or edge the button is pinned to.
            val centerX = overlayContainer.width / 2f
            val centerY = overlayContainer.height / 2f
            val angleToCenter = Math.toDegrees(kotlin.math.atan2((centerY - anchorY).toDouble(), (centerX - anchorX).toDouble())).toFloat()
            val ringMidpointDeg = 155f // half of the 310° hue arc the ring sweeps before its neutral tail
            val angleOffset = angleToCenter - ringMidpointDeg
            val fan = com.procreate.android.color.RadialColorFan(this)
            // The color button lives right next to a screen edge (it's in the top toolbar), and
            // the full wheel's radius is a sizeable fraction of the screen - requiring the ENTIRE
            // wheel to fit on screen (the previous wheelRadius+margin clearance) pushed its center
            // hundreds of pixels away from the button, which is exactly why it stopped visually
            // reading as "around the button" at all. Only enforcing a partial clearance keeps the
            // wheel anchored close to the button - some of it may clip off the very edge of the
            // screen, which is an acceptable trade-off for a menu that has to open beside an
            // edge-hugging trigger, unlike disconnecting it from that trigger entirely.
            // Match the Concepts reference: the wheel's centre is intentionally near the lower
            // left edge, so only its large upper-right sector occupies the canvas.
            // Concepts' wheel centre is at about (95, 700) on its 1280 x 799 reference canvas.
            // Expressing that as a proportion makes the complete wheel retain the same clipped
            // lower-left placement on every landscape density, rather than shrinking with dp.
            val wheelX = overlayContainer.width * (95f / 1280f)
            val wheelY = overlayContainer.height * (700f / 799f)
            fan.configure(wheelX, wheelY)
            fan.isCanvasLocked = drawingView.isCanvasLocked
            fan.onColorPicked = { color -> applyPickedColor(color) }
            fan.onLockToggleRequested = { toggleCanvasLock() }
            fan.onDismissRequested = { dismissFan() }
            overlayContainer.addView(fan, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            activeColorFan = fan
            anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        fun openFullPicker() {
            val colorPicker = ColorPickerPanel().apply {
                arguments = com.procreate.android.ui.common.PanelUi.dockArguments(panelSideFor(anchor))
            }
            colorPicker.setOnColorSelectedListener { color -> applyPickedColor(color) }
            colorPicker.show(supportFragmentManager, "ColorPickerPanel")
        }

        val longPressRunnable = Runnable { if (!moved) { longPressFired = true; openFullPicker() } }

        // btn_colors is one of the buttons PanelUi.applyPressAnimation() also attaches a touch
        // listener to (for the little press-scale animation) from setupPressAnimations(), called
        // later in onCreate - a View keeps only one OnTouchListener, so that call was silently
        // replacing this entire gesture with a plain scale animation and nothing else, which is
        // why the button did nothing on its own (no crash, no log, it just stopped being this
        // listener). Reproducing that same scale animation here, and leaving btn_colors out of
        // applyPressAnimation's own list below, keeps the visual feedback without the conflict.
        anchor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; moved = false; longPressFired = false
                    anchor.postDelayed(longPressRunnable, longPressMs)
                    anchor.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    true // this button now fully owns its own gesture (tap opens the fan, not a plain click)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop)) {
                        moved = true
                        anchor.removeCallbacks(longPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    anchor.removeCallbacks(longPressRunnable)
                    anchor.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    if (!moved && !longPressFired) showFan()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    anchor.removeCallbacks(longPressRunnable)
                    anchor.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSliders() {
        val sizeSlider = findViewById<VerticalFillSlider>(R.id.slider_size)
        val opacitySlider = findViewById<VerticalFillSlider>(R.id.slider_opacity)

        // A default new canvas is 2400x1800 - for the slider's top end to genuinely be able to
        // stamp a single dot across the whole canvas (not just a fraction of it), the brush's
        // real ceiling needs to be comparable to that, not a small fixed number. Raised together
        // with DrawingView's two-finger brush-adjust gesture, which shares the same ceiling.
        // The brush still reaches 3000px, but the UI is logarithmic: 1-100px receives the fine
        // control artists use most instead of being crushed into the bottom 3% of a linear rail.
        sizeSlider.max = BRUSH_SIZE_SLIDER_STEPS

        sizeSlider.onProgressChanged = { progress, fromUser ->
            if (fromUser) {
                val size = sliderProgressToBrushSize(progress)
                viewModel.currentBrush.value?.let {
                    it.size = size
                    viewModel.setCurrentBrush(it)
                }
                sizeSlider.contentDescription = "${getString(R.string.brush_size)}: ${size.roundToInt()} px"
                // setCurrentBrush() alone doesn't repaint DrawingView - nothing observing
                // currentBrush was wired to invalidate() it, so without this the preview circle
                // below was only ever drawn once (at the initial ACTION_DOWN value) and then sat
                // frozen for the rest of the drag despite the brush size actually changing underneath it.
                drawingView.invalidate()
            }
        }

        opacitySlider.onProgressChanged = { progress, fromUser ->
            if (fromUser) {
                viewModel.currentBrush.value?.let {
                    it.opacity = progress / 100f
                    viewModel.setCurrentBrush(it)
                }
                drawingView.invalidate()
            }
        }

        // While dragging either slider, show the same live brush preview circle the on-canvas
        // two-finger adjust gesture already draws: dragging size grows/shrinks the circle
        // (opacity held fixed), dragging opacity fades it in/out (size held fixed, since only
        // the opacity value is changing underneath it) - it disappears the instant the finger
        // lifts either slider.
        sizeSlider.onTrackingChanged = { tracking -> drawingView.setBrushAdjustPreviewActive(tracking) }
        opacitySlider.onTrackingChanged = { tracking -> drawingView.setBrushAdjustPreviewActive(tracking) }
    }

    /** Small tactile scale-press feedback on every toolbar icon, on top of their ripple. */
    private fun setupPressAnimations() {
        // btn_colors is deliberately excluded here - setupRadialColorFan() already gives it the
        // same press-scale animation from within its own OnTouchListener, since a second one
        // applied here would silently replace (not stack with) that listener and take the radial
        // color fan gesture down with it (setOnTouchListener only ever keeps the last one set).
        val ids = intArrayOf(
            R.id.btn_actions, R.id.btn_library, R.id.btn_adjustments, R.id.btn_more_tools,
            R.id.btn_selection, R.id.btn_transform,
            R.id.btn_brush, R.id.btn_smudge, R.id.btn_blur, R.id.btn_filters,
            R.id.btn_eraser, R.id.btn_layers,
            R.id.btn_undo, R.id.btn_redo
        )
        ids.forEach { id -> com.procreate.android.ui.common.PanelUi.applyPressAnimation(findViewById(id)) }
    }

    /** Procreate's "Color Drop": long-press-drag the color swatch onto the canvas to flood-fill. */
    private fun setupColorDrop() {
        val indicator = findViewById<View>(R.id.color_indicator)
        // The swatch beside the size and opacity sliders is the colour the artist is looking at
        // while they work, so it is where they reach when they want to change it. It used to answer
        // only to a long press, and only by starting a drag, which left a tap on the most obvious
        // colour control in the interface doing nothing at all.
        //
        // The gestures go on the frame around the swatch rather than the swatch itself: the swatch
        // is drawn at 22dp, half the smallest target a finger can be asked to hit, and putting the
        // listeners on the 28dp frame and then growing its touch area to a full 48dp means the
        // colour can be reached without aiming.
        val target: View = (indicator.parent as? View) ?: indicator
        target.isClickable = true
        target.setOnClickListener { openColorWheel(indicator) }
        // Pull the swatch off the rail to drop its colour; a plain tap opens the wheel instead.
        // Like the swatch inside the wheel, this starts on movement rather than after a hold: the
        // long-press timer it used to wait for is cancelled by exactly the small stylus tremor that
        // precedes every real drag.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var dragging = false
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > slop
                    ) {
                        dragging = true
                        val color = viewModel.currentColor.value
                        if (color != null) {
                            val clipData = ClipData.newPlainText(
                                ColorPickerPanel.COLOR_DROP_LABEL,
                                color.toString()
                            )
                            view.startDragAndDrop(
                                clipData,
                                View.DragShadowBuilder(indicator),
                                color,
                                0
                            )
                        }
                    }
                    dragging
                }
                else -> false
            }
        }
        expandTouchTarget(target, com.procreate.android.ui.common.PanelUi.dp(this, 48))

        drawingView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_EXITED -> true
                DragEvent.ACTION_DROP -> {
                    val color = droppedColor(event) ?: return@setOnDragListener false
                    drawingView.performColorDrop(event.x, event.y, color)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }
    }

    /**
     * The colour a drop is carrying.
     *
     * A drag started inside this window can hand it over directly as local state, but one started
     * in the colour panel cannot: that panel is its own window, and local state does not cross a
     * window boundary. Both routes therefore also write the colour into the clip data, and this
     * reads whichever arrived.
     */
    private fun droppedColor(event: DragEvent): Int? {
        (event.localState as? Int)?.let { return it }
        val clip = event.clipData ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.text?.toString()?.trim()?.toIntOrNull()
    }

    /**
     * Grow [view]'s touchable area to at least [minimumPx] square, by handing its parent a
     * TouchDelegate. The view keeps its drawn size; only the region that counts as a hit grows.
     */
    private fun expandTouchTarget(view: View, minimumPx: Int) {
        val parent = view.parent as? View ?: return
        parent.post {
            val bounds = android.graphics.Rect()
            view.getHitRect(bounds)
            val growX = ((minimumPx - bounds.width()) / 2).coerceAtLeast(0)
            val growY = ((minimumPx - bounds.height()) / 2).coerceAtLeast(0)
            if (growX == 0 && growY == 0) return@post
            bounds.inset(-growX, -growY)
            parent.touchDelegate = android.view.TouchDelegate(bounds, view)
        }
    }

    /** Opens the full colour wheel, docked to whichever side [anchor] sits on. */
    private fun openColorWheel(anchor: View) {
        val colorPicker = ColorPickerPanel().apply {
            arguments = com.procreate.android.ui.common.PanelUi.dockArguments(panelSideFor(anchor))
        }
        colorPicker.setOnColorSelectedListener { color -> applyPickedColor(color) }
        colorPicker.show(supportFragmentManager, "ColorPickerPanel")
    }

    private fun setupUndoRedo() {
        findViewById<ImageButton>(R.id.btn_undo).setOnClickListener {
            viewModel.undo()
            drawingView.invalidate()
        }
        findViewById<ImageButton>(R.id.btn_redo).setOnClickListener {
            viewModel.redo()
            drawingView.invalidate()
        }
    }

    private fun brushSizeToSliderProgress(size: Float): Int {
        val normalized = ln(size.coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE).toDouble()) /
            ln(MAX_BRUSH_SIZE.toDouble())
        return (normalized * BRUSH_SIZE_SLIDER_STEPS).roundToInt()
            .coerceIn(0, BRUSH_SIZE_SLIDER_STEPS)
    }

    private fun sliderProgressToBrushSize(progress: Int): Float {
        val normalized = progress.coerceIn(0, BRUSH_SIZE_SLIDER_STEPS).toDouble() /
            BRUSH_SIZE_SLIDER_STEPS
        return exp(normalized * ln(MAX_BRUSH_SIZE.toDouble())).toFloat()
            .coerceIn(MIN_BRUSH_SIZE, MAX_BRUSH_SIZE)
    }

}
