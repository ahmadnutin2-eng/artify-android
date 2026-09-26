package com.procreate.android.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.ScaleGestureDetector
import android.view.View
import com.procreate.android.collaboration.LiveStrokeEvent
import com.procreate.android.collaboration.LocalStrokePatch
import com.procreate.android.collaboration.StrokePhase
import android.view.ViewConfiguration
import com.procreate.android.R
import com.procreate.android.tools.FillTool
import com.procreate.android.tools.SelectionTool
import com.procreate.android.tools.TransformTool
import com.procreate.android.project.ProjectDocumentMapper
import com.procreate.android.project.UrbanCoordinateSpace
import com.procreate.android.project.UrbanProjectDto
import com.procreate.android.urban.grid.UrbanGridRenderer
import com.procreate.android.urban.grid.UrbanScaleWidget
import com.procreate.android.urban.legend.DynamicLegendManager
import com.procreate.android.urban.legend.LegendRenderer
import com.procreate.android.urban.model.ArrowHeadType
import com.procreate.android.urban.model.HatchStyle
import com.procreate.android.urban.model.OverlayPlacement
import com.procreate.android.urban.model.UrbanCategory
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanGeometryKind
import com.procreate.android.urban.model.UrbanInputMode
import com.procreate.android.urban.model.UrbanStrokePattern
import com.procreate.android.urban.model.UrbanScaleConfig
import com.procreate.android.urban.model.UrbanToolType
import com.procreate.android.urban.tables.SpatialTableGenerator
import com.procreate.android.urban.tools.UrbanArrowRenderer
import com.procreate.android.urban.tools.UrbanHatchRenderer
import com.procreate.android.urban.tools.UrbanSymbolRenderer
import com.procreate.android.urban.assets.ArchitecturalAssetRenderer
import com.procreate.android.urban.assets.AssetInstance
import com.procreate.android.urban.assets.AssetRenderOptions
import com.procreate.android.urban.assets.AssetViewport
import com.procreate.android.urban.assets.BuiltInArchitecturalAssets
import com.procreate.android.urban.measurement.MeasurementCalculator
import com.procreate.android.urban.measurement.MeasurementElement
import com.procreate.android.urban.measurement.MeasurementFormatter
import com.procreate.android.urban.measurement.MeasurementKind
import com.procreate.android.urban.measurement.MeasurementPoint
import com.procreate.android.urban.measurement.MeasurementResult
import com.procreate.android.urban.model.DistanceUnit
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The canvas surface: drawing, the view transform (pan/zoom/rotate), and the full gesture set.
 *
 * | Gesture                          | Action                          |
 * |----------------------------------|---------------------------------|
 * | One finger drag                  | Draw                            |
 * | One finger hold (still)          | Eyedropper                      |
 * | Two fingers drag                 | Pan, with fling momentum        |
 * | Two fingers pinch                | Zoom                            |
 * | Two fingers twist                | Rotate                          |
 * | Two finger tap                   | Undo                            |
 * | Three finger tap                 | Redo                            |
 * | Four finger tap                  | Show/hide the interface         |
 * | Two fingers hold, then drag      | Brush size and opacity          |
 * | Three fingers swipe down         | Clear the active layer          |
 * | Pinch in past the fit scale      | Snap the canvas back to fit     |
 *
 * The ones whose effect reaches outside the canvas are reported through [GestureListener].
 */
class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface GestureListener {
        fun onToggleInterface()
        fun onColorPicked(color: Int)
        fun onBrushChanged(size: Float, opacity: Float)
        fun onHint(message: String)
    }

    var gestureListener: GestureListener? = null

    /** Assigned only for an online room; ordinary canvases pay no networking/copying cost. */
    var onLocalCollaborationStroke: ((LiveStrokeEvent) -> Unit)? = null
    var onLocalCollaborationPatch: ((LocalStrokePatch) -> Unit)? = null

    private data class RemoteStrokePreview(
        val participantName: String,
        val path: Path,
        val paint: Paint,
        var lastX: Float,
        var lastY: Float
    )

    private val remoteStrokePreviews = LinkedHashMap<String, RemoteStrokePreview>()

    fun showRemoteCollaborationStroke(participantName: String, event: LiveStrokeEvent) {
        when (event.phase) {
            StrokePhase.START -> {
                val previewColor = if (event.eraser) Color.WHITE else event.color
                remoteStrokePreviews[event.strokeId] = RemoteStrokePreview(
                    participantName = participantName,
                    path = Path().apply { moveTo(event.x, event.y) },
                    paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = previewColor
                        alpha = (event.opacity.coerceIn(0f, 1f) * 210).toInt().coerceIn(45, 210)
                        strokeWidth = (event.size * event.pressure.coerceIn(0.15f, 1f)).coerceAtLeast(1f)
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        if (event.eraser) pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
                    },
                    lastX = event.x,
                    lastY = event.y
                )
            }
            StrokePhase.MOVE, StrokePhase.END -> {
                remoteStrokePreviews[event.strokeId]?.let { preview ->
                    val midX = (preview.lastX + event.x) / 2f
                    val midY = (preview.lastY + event.y) / 2f
                    preview.path.quadTo(preview.lastX, preview.lastY, midX, midY)
                    preview.lastX = event.x
                    preview.lastY = event.y
                    preview.paint.strokeWidth =
                        (event.size * event.pressure.coerceIn(0.15f, 1f)).coerceAtLeast(1f)
                }
            }
        }
        invalidate()
    }

    fun completeRemoteCollaborationStroke(strokeId: String) {
        remoteStrokePreviews.remove(strokeId)
        invalidate()
    }

    private val brushEngine = BrushEngine()
    /** Runs the primary engine plus one mirror engine per symmetry branch for every stroke. */
    private val symmetryStroke = SymmetryStroke(brushEngine)
    /** Union of every branch's dirty rectangle for the batch being painted. */
    private val batchDirty = RectF()
    val selectionTool = SelectionTool()
    val transformTool = TransformTool()
    var viewModel: CanvasViewModel? = null
        set(value) {
            field = value
            // When an open canvas needs more paper than memory allows, say so. Without this the
            // paper simply stops following the pen and strokes vanish with no explanation.
            value?.onGrowthBlocked = { showGrowthBlockedHint() }
            // Layer bitmaps are native allocations, so how much more paper this device can afford
            // is a question about system memory, which only something holding a Context can answer.
            value?.availableMemoryProbe = { systemAvailableMemoryBytes() }
            brushEngine.beforeWrite = if (value == null) null else { bounds ->
                val index = value.activeLayerIndex.value
                if (index != null) value.captureBeforeEdit(index, bounds)
            }
        }

    private val activityManager: android.app.ActivityManager by lazy {
        context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    }
    private val systemMemoryInfo = android.app.ActivityManager.MemoryInfo()

    /**
     * Free system memory, minus the level at which Android starts killing background processes, so
     * the canvas stops growing while the device is still comfortable rather than at the point where
     * the next allocation would take the whole process down with it.
     */
    private fun systemAvailableMemoryBytes(): Long {
        activityManager.getMemoryInfo(systemMemoryInfo)
        if (systemMemoryInfo.lowMemory) return 0L
        return (systemMemoryInfo.availMem - systemMemoryInfo.threshold).coerceAtLeast(0L)
    }

    private var lastGrowthHintAt = 0L

    private fun showGrowthBlockedHint() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastGrowthHintAt < 4000L) return
        lastGrowthHintAt = now
        post { gestureListener?.onHint(context.getString(R.string.canvas_growth_blocked)) }
    }

    /**
     * How the canvas is interpolated while zoomed in - ibisPaint's "Display when Zoomed". Purely a
     * display setting: nothing about the artwork changes, so it is safe to flip at any time.
     */
    var displayQuality: DisplayQuality = DisplayQualityPrefs.get(context)
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Sampling for this frame. Interpolation only matters when magnifying; when zoomed out the
     * layers are being minified, where bilinear is right whichever mode the user chose.
     */
    private fun currentSampling(): LayerCompositor.Sampling {
        // Bicubic display samples a 4x4 neighbourhood for every screen pixel. It looks excellent
        // when inspecting a still image, but doing that work while the pen or a transform is
        // moving spends GPU time on pixels that will be replaced a few milliseconds later. Use
        // bilinear interactively, then the final invalidate after release restores bicubic.
        val interactionActive = isDrawing || multiTouchMaxPointers > 0 || brushAdjustActive ||
            flingRunning || rotationFlingRunning
        if (interactionActive) return LayerCompositor.Sampling.BILINEAR
        val magnified = currentScale() > 1.05f
        return when {
            !magnified -> LayerCompositor.Sampling.BILINEAR
            displayQuality == DisplayQuality.PIXELATED -> LayerCompositor.Sampling.NEAREST
            else -> LayerCompositor.Sampling.BICUBIC
        }
    }
    // The context lets the engine decode the bundled pencil recording it cuts its grains from.
    private val drawingAudioEngine = DrawingAudioEngine(context).apply {
        setSoundEnabled(DrawingSoundPrefs.isEnabled(context))
        setVolume(DrawingSoundPrefs.getVolume(context))
        setSoundProfile(DrawingSoundPrefs.getProfile(context))
    }

    fun setDrawingSoundEnabled(enabled: Boolean) {
        drawingAudioEngine.setSoundEnabled(enabled)
        DrawingSoundPrefs.setEnabled(context, enabled)
    }

    fun isDrawingSoundEnabled(): Boolean = drawingAudioEngine.isSoundEnabled()

    fun setDrawingSoundVolume(volume: Float) {
        drawingAudioEngine.setVolume(volume)
        DrawingSoundPrefs.setVolume(context, volume)
    }

    fun getDrawingSoundVolume(): Float = drawingAudioEngine.getVolume()

    fun setDrawingSoundProfile(profile: DrawingSoundProfile) {
        drawingAudioEngine.setSoundProfile(profile)
        DrawingSoundPrefs.setProfile(context, profile)
    }

    fun getDrawingSoundProfile(): DrawingSoundProfile = drawingAudioEngine.getSoundProfile()

    // ── Symmetry / Mirror Guide ──
    enum class SymmetryMode { NONE, VERTICAL, HORIZONTAL, QUAD }
    var symmetryMode: SymmetryMode = SymmetryMode.NONE
        set(value) {
            field = value
            invalidate()
        }

    // ── QuickShape Engine (Procreate-style Snap to Perfect Shapes) ──
    var isQuickShapeEnabled: Boolean = true
    private val currentStrokePoints = mutableListOf<PointF>()
    private val strokePointPool = mutableListOf<PointF>()
    private var strokePointCount = 0
    private var activeQuickShape: QuickShape? = null
    /** Pen input of the current stroke, so a snapped shape keeps the weight it was drawn with. */
    private val strokeInput = StrokeInputStats()
    // Captured when the shape snaps and used for both its preview and its commit, so they match.
    private var quickShapePressure = 1f
    private var quickShapeTilt = 0f
    private var quickShapeAzimuth = 0f
    private val quickShapeHoldMs = 450L
    private val quickShapeRunnable = Runnable {
        if (!isDrawing || !isQuickShapeEnabled || isUrbanMode) return@Runnable
        val shape = QuickShapeEngine.detect(currentStrokePoints)
        if (shape != null) {
            activeQuickShape = shape
            quickShapePressure = strokeInput.medianPressure(downPressure)
            quickShapeTilt = strokeInput.medianTilt()
            quickShapeAzimuth = strokeInput.meanAzimuth()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val name = if (context.resources.configuration.locales[0].language == "ar") shape.nameAr() else shape.nameEn()
            gestureListener?.onHint(context.getString(R.string.quickshape_snapped, name))
            invalidate()
        }
    }

    private fun resetStrokePoints(x: Float, y: Float) {
        currentStrokePoints.clear()
        strokePointCount = 0
        addStrokePoint(x, y)
    }

    /** Reuses point objects across strokes; a stylus can otherwise create hundreds every second. */
    private fun addStrokePoint(x: Float, y: Float) {
        val point = strokePointPool.getOrNull(strokePointCount)
            ?: PointF().also(strokePointPool::add)
        point.set(x, y)
        currentStrokePoints.add(point)
        strokePointCount++
    }

    // Urban Design & Site Analysis Engine
    val urbanScaleConfig = UrbanScaleConfig()
    val urbanLegendManager = DynamicLegendManager()
    val urbanElements = mutableListOf<UrbanElement>()
    val urbanMeasurements = mutableListOf<MeasurementElement>()
    val architecturalAssets = mutableListOf<AssetInstance>()
    private val architecturalAssetRenderer = ArchitecturalAssetRenderer()
    private val measurementFormatter = MeasurementFormatter(maximumFractionDigits = 2)
    var measurementDisplayUnit: DistanceUnit = DistanceUnit.METER
        private set
    private var pendingArchitecturalAssetId: String? = null
    private var selectedArchitecturalAssetId: String? = null
    private var assetGestureBefore: UrbanProjectDto? = null
    private var assetDragLastPoint = PointF()
    private var assetSelectionDownPoint = PointF()
    private var isDraggingArchitecturalAsset = false
    var onArchitecturalAssetEditRequested: ((AssetInstance) -> Unit)? = null
    /** Raised with the finished CAD export so the host can offer save-as/share. */
    var onUrbanExportCompleted: ((dxf: java.io.File, raster: java.io.File?) -> Unit)? = null

    /**
     * Raised when a subscriber-only action is attempted without a subscription. The activity shows
     * the paywall and invokes the supplied block if the user subscribes, so the action they asked
     * for actually happens rather than being silently dropped.
     */
    var onPremiumRequired: ((retry: () -> Unit) -> Unit)? = null
    var activeUrbanTool: UrbanToolType? = null

    // The spatial table bitmap was being rebuilt from scratch (text layout, canvas drawing, a
    // fresh bitmap allocation) on *every* onDraw call - and onDraw runs continuously during a
    // pan/zoom/rotate gesture, since each move sample calls invalidate(). That's what read as
    // panning/zooming being obstructed: real per-frame work competing with the gesture itself.
    // The table only actually changes when the plaza list does, so it's rebuilt on demand and
    // reused every other frame instead.
    private var cachedPlazaTable: Bitmap? = null
    private var cachedPlazaTableSignature: Int = 0
    private fun getCachedPlazaTable(plazas: List<UrbanElement.HatchPolygon>): Bitmap {
        val signature = plazas.hashCode()
        val cached = cachedPlazaTable
        if (cached != null && cachedPlazaTableSignature == signature) return cached
        val fresh = SpatialTableGenerator.generatePlazaTableBitmap(plazas)
        cachedPlazaTable = fresh
        cachedPlazaTableSignature = signature
        return fresh
    }

    // Every hatch-polygon type that ISN'T a plaza (dirt/asphalt paths, farms, heritage/modern
    // buildings, and whatever else gets added later) is grouped dynamically by tool type here,
    // rather than hardcoding just asphalt+dirt - a fixed two-category table was the actual bug
    // behind "the areas table is frozen, it doesn't reflect newly added elements": any polygon
    // of a type outside those two (a farm hatch, a building) was silently excluded from every
    // table, so drawing more of them never changed anything on screen.
    private var cachedOtherAreasTable: Bitmap? = null
    private var cachedOtherAreasSignature: Int = 0
    private fun getCachedOtherAreasTable(areas: List<Pair<String, Float>>): Bitmap {
        val signature = areas.hashCode()
        val cached = cachedOtherAreasTable
        if (cached != null && cachedOtherAreasSignature == signature) return cached
        val fresh = SpatialTableGenerator.generateOtherAreasTableBitmap(areas)
        cachedOtherAreasTable = fresh
        cachedOtherAreasSignature = signature
        return fresh
    }
    private fun otherAreasByType(): List<Pair<String, Float>> =
        urbanElements.filterIsInstance<UrbanElement.HatchPolygon>()
            .filter { it.toolType != UrbanToolType.PLAZA_HATCH }
            .groupBy { it.toolType }
            .map { (type, list) -> type.titleAr to list.sumOf { it.areaSqMeters.toDouble() }.toFloat() }
            .filter { it.second > 0.05f }
    /** Freezes pan/zoom/rotate so a stray second finger (resting a palm, an accidental brush
     * with the other hand) can never nudge the view mid-stroke - single-finger drawing and every
     * tap/swipe multi-touch shortcut (undo, redo, toggle UI, clear layer) still work exactly as
     * before; only the continuous two-finger transform itself is disabled while this is on. */
    var isCanvasLocked: Boolean = false
    var isUrbanMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                currentUrbanPoints.clear()
                activeUrbanTool = null
                isCalibratingScale = false
                isPickingCalibrationVertices = false
                pendingArchitecturalAssetId = null
                selectedArchitecturalAssetId = null
                assetGestureBefore = null
                isDraggingArchitecturalAsset = false
                if (selectedUrbanElement != null) {
                    selectedUrbanElement = null
                    onUrbanElementSelected?.invoke(null)
                }
            }
            invalidate()
        }
    var urbanInputMode: UrbanInputMode = UrbanInputMode.POINT_BY_POINT
    var urbanSnapSettings: com.procreate.android.urban.tools.SnapSettings =
        com.procreate.android.urban.tools.SnapSettings(grid = false, vertex = false)
    /** Last snap that actually moved the cursor, so the canvas can mark it while drawing. */
    private var lastSnapKind: com.procreate.android.urban.tools.SnapKind =
        com.procreate.android.urban.tools.SnapKind.NONE
    private var lastSnapPoint: PointF? = null
    // Visible by default while drawing (still toggleable off) rather than hidden-by-default -
    // the whole point of a live table/legend is to be seen as the plan is built up, not opted
    // into after the fact.
    var showLiveLegend: Boolean = true
    var showLiveTables: Boolean = true
    var legendPlacement: OverlayPlacement = OverlayPlacement.BOTTOM_RIGHT
    var tablePlacement: OverlayPlacement = OverlayPlacement.BOTTOM_LEFT
    var scaleCardPlacement: OverlayPlacement = OverlayPlacement.BOTTOM_LEFT

    var isCalibratingScale: Boolean = false
    private var calibStart: PointF? = null
    private var calibEnd: PointF? = null
    var onScaleCalibrationMeasured: ((pixelDistance: Float, start: PointF, end: PointF) -> Unit)? = null
    var onUrbanNodesChanged: ((Int) -> Unit)? = null

    // Selecting and editing an already-placed Urban element: reachable whenever the toolbar
    // dock is showing its default bar (activeUrbanTool == null) rather than a focused drawing
    // tool. Tapping a shape selects it; dragging a vertex handle reshapes/resizes it; dragging
    // the body moves the whole element; long-pressing a vertex removes it, long-pressing the
    // body between two vertices inserts one - this is how the user adds/removes points on an
    // element that was already drawn, without redrawing it from scratch.
    var selectedUrbanElement: UrbanElement? = null
        private set
    var onUrbanElementSelected: ((UrbanElement?) -> Unit)? = null
    /** Screen-space (view-local) point of the touch that most recently selected an element - so
     * the edit popup can appear right next to where the finger actually tapped, instead of at a
     * fixed spot in the toolbar. Only updated when a *new* selection is made, not on every drag
     * sample, so the popup doesn't chase the shape around while it's being reshaped. */
    private val selectedUrbanElementScreenPoint = PointF()
    fun selectedUrbanElementScreenAnchor(): PointF = PointF(selectedUrbanElementScreenPoint.x, selectedUrbanElementScreenPoint.y)
    private var selectedUrbanVertexIndex: Int = -1
    private var isDraggingUrbanElement = false
    /** Decided once on ACTION_DOWN and held for the rest of that gesture: a tap that lands
     * directly on an already-drawn element, while a tool is active but no shape is mid-draw,
     * edits that element instead of starting a new one - reported as a real gap ("لا يوجد زر عائم
     * لتعديل حجم العناصر", screenshot showed a drawing tool still active with no way to reach an
     * existing shape's settings bar without first backing out of the tool entirely). */
    private var routeUrbanTapToSelection = false
    private val urbanDragLastPoint = PointF()
    private val urbanSelectionDownPoint = PointF()
    /** One history entry per drag/reshape/long-press gesture, never one per move sample. */
    private var urbanGestureBefore: UrbanProjectDto? = null
    private var urbanLongPressAction: (() -> Unit)? = null
    private val urbanLongPressRunnable = Runnable {
        urbanLongPressAction?.invoke()
        urbanLongPressAction = null
    }
    /** Also used to calibrate the scale from an already-drawn element: pick two of its vertices
     * as the known-distance endpoints instead of drawing a fresh two-point gesture. */
    var isPickingCalibrationVertices: Boolean = false
        set(value) {
            field = value
            calibVertexPick = null
            invalidate()
        }
    var onCalibrationVerticesPicked: ((pixelDistance: Float, start: PointF, end: PointF) -> Unit)? = null
    private var calibVertexPick: PointF? = null

    private var currentUrbanPoints = mutableListOf<PointF>()
    private var plazaCounter = 1
    private val urbanDownPoint = PointF()

    /** Captures the editable Urban workspace without retaining any mutable PointF/list aliases. */
    fun createUrbanProjectSnapshot(): UrbanProjectDto = UrbanProjectDto(
        coordinateSpace = UrbanCoordinateSpace.CANVAS_PIXELS,
        scale = ProjectDocumentMapper.fromScaleConfig(urbanScaleConfig),
        settings = ProjectDocumentMapper.fromUrbanSettings(
            isUrbanMode = isUrbanMode,
            inputMode = urbanInputMode,
            activeTool = activeUrbanTool,
            showLiveLegend = showLiveLegend,
            showLiveTables = showLiveTables,
            legendPlacement = legendPlacement,
            tablePlacement = tablePlacement,
            scaleCardPlacement = scaleCardPlacement
        ),
        elements = urbanElements.map(ProjectDocumentMapper::fromUrbanElement),
        measurements = urbanMeasurements.map(ProjectDocumentMapper::fromMeasurement),
        architecturalAssets = architecturalAssets.map(ProjectDocumentMapper::fromArchitecturalAsset)
    )

    /** Replaces the runtime Urban workspace from a project/history snapshot. */
    fun restoreUrbanProjectSnapshot(snapshot: UrbanProjectDto) {
        require(snapshot.coordinateSpace == UrbanCoordinateSpace.CANVAS_PIXELS) {
            "This build can only restore CANVAS_PIXELS Urban coordinates"
        }
        currentUrbanPoints.clear()
        selectedUrbanElement = null
        selectedUrbanVertexIndex = -1
        batchSelectedElements.clear()
        ProjectDocumentMapper.applyScaleConfig(snapshot.scale, urbanScaleConfig)
        isUrbanMode = snapshot.settings.isUrbanMode
        urbanInputMode = ProjectDocumentMapper.decodeInputMode(snapshot.settings)
        activeUrbanTool = if (isUrbanMode) ProjectDocumentMapper.decodeActiveTool(snapshot.settings) else null
        showLiveLegend = snapshot.settings.showLiveLegend
        showLiveTables = snapshot.settings.showLiveTables
        legendPlacement = ProjectDocumentMapper.decodeLegendPlacement(snapshot.settings)
        tablePlacement = ProjectDocumentMapper.decodeTablePlacement(snapshot.settings)
        scaleCardPlacement = ProjectDocumentMapper.decodeScaleCardPlacement(snapshot.settings)
        urbanElements.clear()
        urbanElements.addAll(snapshot.elements.map(ProjectDocumentMapper::toUrbanElement))
        urbanMeasurements.clear()
        urbanMeasurements.addAll(snapshot.measurements.map(ProjectDocumentMapper::toMeasurement))
        architecturalAssets.clear()
        architecturalAssets.addAll(snapshot.architecturalAssets.map(ProjectDocumentMapper::toArchitecturalAsset))
        selectedArchitecturalAssetId = null
        pendingArchitecturalAssetId = null
        plazaCounter = (urbanElements.filterIsInstance<UrbanElement.HatchPolygon>()
            .mapNotNull { it.plazaNumber }.maxOrNull() ?: 0) + 1
        // Stored metric fields are caches, never source-of-truth. Recompute from geometry and
        // the restored scale so older saves and undo snapshots cannot leave stale totals behind.
        recomputeAllUrbanMetrics()
        cachedPlazaTable = null
        cachedOtherAreasTable = null
        onUrbanNodesChanged?.invoke(0)
        onUrbanElementSelected?.invoke(null)
        onBatchSelectionCleared?.invoke()
        invalidate()
    }

    /** Commits a caller-owned before snapshot after it has changed scale/settings/elements. */
    fun commitUrbanProjectChange(before: UrbanProjectDto) {
        viewModel?.commitUrbanEdit(before, createUrbanProjectSnapshot())
    }

    private val canvasMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val mappedScreenPoint = FloatArray(2)
    private val gestureCanvasPoint = PointF()
    private val touchCanvasPoint = PointF()
    private val sampleCanvasPoint = PointF()
    private val expansionCanvasPoint = PointF()
    /** Separate from [sampleCanvasPoint] because the lift settle reads it across feedSample calls. */
    private val settleCanvasPoint = PointF()

    /**
     * Cells this stroke has already filled, packed as column and row into one long.
     *
     * A square-Kufic stroke crosses the same cell many times as the hand wobbles inside it. Filling
     * on every crossing would be wasted work and, on a semi-transparent colour, would darken the
     * cell a little more each time - the bar would come out blotchy where the hand lingered.
     */
    private val gridCellsFilledThisStroke = HashSet<Long>()
    private var lastGridX = 0f
    private var lastGridY = 0f
    private val gridCellPaint = Paint().apply { isAntiAlias = false }
    private val visibleCanvasPoints = FloatArray(8)
    private val visibleCanvasRect = RectF()
    private val scaleGestureDetector: ScaleGestureDetector

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapMoveThresholdPx = 10f * density
    private val tapTimeoutMs = 260L
    // Increased to 3000ms (3 full seconds) per user requirement to ensure accidental touches never trigger eyedropper
    private val holdTimeoutMs = 3000L

    // ---- Single-finger stroke state ----
    private var isDrawing = false
    private var collaborationStrokeId: String? = null
    private var lastCollaborationPreviewAt = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastEventTime = 0L
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var smoothedVelocity = 0f
    private var smoothedPressure = 1f
    private var lastEdgePanTime = 0L
    private var edgePenX = 0f
    private var edgePenY = 0f
    private var edgePenPressure = 1f
    private var edgePanScheduled = false
    private var lastSmudgeX = 0f
    private var lastSmudgeY = 0f
    // The first dab is deferred until the touch is known not to be a hold, so a long-press that
    // becomes an eyedropper never leaves a stray dot behind on the layer.
    private var pendingDot = false
    private var downCanvasX = 0f
    private var downCanvasY = 0f
    private var downPressure = 1f
    private val strokeDirty = RectF()

    // ---- Multi-touch session state ----
    private var multiTouchDownTime = 0L
    private var multiTouchMaxPointers = 0
    private var multiTouchMoved = false
    private var lastRotationAngle = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var sessionStartFocusX = 0f
    private var sessionStartFocusY = 0f
    // Rotation only starts applying once the *cumulative* raw angle change since the gesture
    // began crosses a real threshold. Without this, an ordinary two-finger pan reads as rotation
    // too: two fingers are naturally held with some horizontal offset between them, so a sideways
    // pan carries slight per-finger timing asymmetry along that same axis, which the raw
    // finger-to-finger angle picks up as "twisting" every single frame - vertical pans don't have
    // this problem since they're perpendicular to the fingers' resting offset. A deadzone lets a
    // deliberate twist still engage normally while filtering out that incidental noise.
    private var rotationEngaged = false
    private var unengagedRotation = 0f

    // ---- Rotation momentum ----
    private var angularVelocity = 0f
    private var lastRotationFocusX = 0f
    private var lastRotationFocusY = 0f
    private var rotationFlingRunning = false

    // ---- Quick-pinch detection ----
    private var scaleAtGestureStart = 1f
    private var gestureStartTime = 0L
    private var previousFitState: FloatArray? = null  // matrix values before last fit-to-screen

    // ---- Angle snapping ----
    private var lastSnappedAngle = Float.NaN
    private var isAngleSnapped = false

    // ---- Modes entered by holding still ----
    private var eyedropperActive = false
    private var eyedropperColor = Color.TRANSPARENT
    private val eyedropperPoint = PointF()
    private var brushAdjustActive = false

    /** Shows/hides the same brush-size-and-opacity preview circle (drawHud's brushAdjustActive
     * block) that the on-canvas two-finger adjust gesture already draws - reused here so the
     * size/opacity sliders in the side toolbar can show a live preview of the real brush while
     * being dragged, instead of duplicating that rendering. */
    fun setBrushAdjustPreviewActive(active: Boolean) {
        brushAdjustActive = active
        invalidate()
    }
    private var brushAdjustStartX = 0f
    private var brushAdjustStartY = 0f
    private var brushAdjustStartSize = 10f
    private var brushAdjustStartOpacity = 1f

    // ---- Pan momentum ----
    private var flingVelocityX = 0f
    private var flingVelocityY = 0f
    private var lastPanTime = 0L
    private var flingRunning = false
    // Rubber-band: when zoomed past limits, this tracks how far "over" the user went so
    // the view can spring back smoothly on release.
    private var rubberBandScale = 1f

    // Reusable scratch bitmap for Alpha Lock: a batch of samples is drawn here first (normal,
    // unconstrained), then composited onto the real layer with SRC_ATOP so it only lands on
    // already-opaque pixels.
    private var alphaLockScratch: Bitmap? = null
    private var alphaLockOriginal: Bitmap? = null

    // Snapshot of the active layer's bitmap taken when a transform begins - the live preview
    // is drawn through transformTool's matrix, and the original layer is hidden while this is set.
    private var transformOriginalBitmap: Bitmap? = null
    private var transformLayerIndex: Int = -1
    private val transformSourceBounds = RectF()
    private enum class TransformDragMode { NONE, MOVE, SCALE_UNIFORM, SCALE_X, SCALE_Y, ROTATE }
    private var transformDragMode = TransformDragMode.NONE
    private val transformStartMatrix = Matrix()
    private val transformGestureCenter = PointF()
    private var transformStartX = 0f
    private var transformStartY = 0f
    private var transformStartDistance = 1f
    private var transformStartAngle = 0f
    private var transformAxisAngle = 0f
    private var transformStartAxisProjection = 1f

    private val transformOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3D8BFF")
    }
    private val transformOutlineContrastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(210, 0, 0, 0)
        strokeJoin = Paint.Join.ROUND
    }
    private val transformHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val transformHandleContrastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 0, 0, 0)
    }
    private val transformHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3D8BFF")
    }
    private val transformRotatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34C759")
    }
    private val transformActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#242427")
    }
    private val transformActionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val transformClearPaint = Paint().apply {
        blendMode = android.graphics.BlendMode.CLEAR
    }

    // Live color-adjustment preview (brightness/contrast/saturation) for the active layer.
    private var adjustmentPreviewFilter: android.graphics.ColorFilter? = null
    private var adjustmentPreviewLayerIndex: Int = -1
    private val previewFilterForLayer: (Int) -> android.graphics.ColorFilter? = { index ->
        if (index == adjustmentPreviewLayerIndex) adjustmentPreviewFilter else null
    }

    private val selectionContrastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 0, 0, 0)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // Always-visible outline around the canvas bounds, so the user can see exactly where the
    // paper ends even when it's larger than the screen.
    private val canvasBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#55FFFFFF")
    }

    private val srcAtopPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = android.graphics.BlendMode.SRC_ATOP
    }
    /** SRC replaces the destination outright, rather than blending over it. */
    private val srcPaint = Paint().apply { blendMode = android.graphics.BlendMode.SRC }
    /** Lays an accumulated uniform-coverage stroke onto the layer at the brush's own opacity. */
    private val strokeFoldPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = android.graphics.BlendMode.SRC_OVER
    }
    private val bakePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val hudFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        BrushTextures.appContext = context.applicationContext
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (brushAdjustActive || isCanvasLocked) return true
                // While a transform is live, a pinch means "resize the thing I'm transforming",
                // not "zoom the sheet". This branch was missing entirely, which is why an imported
                // image could be dragged but never resized: TransformTool.scale() was implemented
                // and correct, but no gesture ever reached it - the canvas zoom swallowed them all.
                if (isTransformGestureActive()) {
                    val pivot = screenToCanvas(detector.focusX, detector.focusY, gestureCanvasPoint)
                    transformTool.scale(detector.scaleFactor, pivot.x, pivot.y)
                    invalidate()
                    return true
                }
                val current = currentScale()
                if (current <= 0f) return true
                // Rubber-band: allow slight overshoot past min/max scale with resistance,
                // rather than hard clamping. The overshoot is settled back on gesture end.
                val targetScale = current * detector.scaleFactor
                val min = minScale()
                val max = MAX_SCALE
                val applied = if (targetScale < min) {
                    // Below minimum: apply with heavy damping (rubber band)
                    val overshoot = min / targetScale
                    val dampedFactor = 1f + (detector.scaleFactor - 1f) * (1f / (1f + overshoot * 2f))
                    dampedFactor
                } else if (targetScale > max) {
                    // Above maximum: apply with heavy damping
                    val overshoot = targetScale / max
                    val dampedFactor = 1f + (detector.scaleFactor - 1f) * (1f / (1f + overshoot * 2f))
                    dampedFactor
                } else {
                    detector.scaleFactor
                }
                canvasMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                brushEngine.renderScale = currentScale()
                invalidate()
                return true
            }
        })
    }

    // ==================== View transform ====================

    private fun currentScale(): Float {
        canvasMatrix.getValues(matrixValues)
        return hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat()
    }

    private fun canvasSize(): Pair<Int, Int>? {
        val bmp = viewModel?.layers?.value?.firstOrNull()?.bitmap ?: return null
        return bmp.width to bmp.height
    }

    /** Scale at which the whole canvas is comfortably visible, with a small margin. */
    private fun fitScale(): Float {
        val (cw, ch) = canvasSize() ?: return 1f
        if (width == 0 || height == 0) return 1f
        return min(width / cw.toFloat(), height / ch.toFloat()) * 0.92f
    }

    /** Below this the artwork is a speck on screen, so it's the zoom-out floor. */
    private fun minScale(): Float = fitScale() * 0.15f

    /** Centre the canvas at its fit scale. */
    fun fitCanvasToScreen(animated: Boolean = false) {
        val (cw, ch) = canvasSize() ?: return
        val s = fitScale()
        val target = Matrix().apply {
            postScale(s, s)
            postTranslate((width - cw * s) / 2f, (height - ch * s) / 2f)
        }
        if (animated) {
            animateMatrixTo(target)
        } else {
            canvasMatrix.set(target)
            brushEngine.renderScale = currentScale()
            invalidate()
        }
    }

    private fun animateMatrixTo(target: Matrix) {
        val from = FloatArray(9).also { canvasMatrix.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        val current = FloatArray(9)
        val startNanos = System.nanoTime()
        // Stop any running flings so they don't fight the animation.
        stopFling()
        stopRotationFling()
        postOnAnimation(object : Runnable {
            override fun run() {
                val elapsed = (System.nanoTime() - startNanos) / 1_000_000f
                val raw = (elapsed / SETTLE_DURATION_MS).coerceIn(0f, 1f)
                if (raw >= 1f) {
                    canvasMatrix.set(target)
                    brushEngine.renderScale = currentScale()
                    invalidate()
                    return
                }
                // Fluid cubic ease-out: fast dynamic start that settles smoothly into exact resting spot
                val inv = 1f - raw
                val t = 1f - inv * inv * inv
                for (i in 0 until 9) current[i] = from[i] + (to[i] - from[i]) * t
                canvasMatrix.setValues(current)
                brushEngine.renderScale = currentScale()
                invalidate()
                postOnAnimation(this)
            }
        })
    }

    /** Maps a screen-space point (already accounting for pan/zoom/rotation) into canvas space. */
    /** True while the transform tool holds a live snapshot, i.e. gestures belong to the layer
     * being transformed rather than to the canvas view. */
    private fun isTransformGestureActive(): Boolean =
        viewModel?.toolMode?.value == ToolMode.TRANSFORM && transformOriginalBitmap != null

    fun screenToCanvas(screenX: Float, screenY: Float): PointF =
        screenToCanvas(screenX, screenY, PointF())

    /** Allocation-free form used by pen, transform and expansion hot paths. */
    private fun screenToCanvas(screenX: Float, screenY: Float, out: PointF): PointF {
        canvasMatrix.invert(inverseMatrix)
        mappedScreenPoint[0] = screenX
        mappedScreenPoint[1] = screenY
        inverseMatrix.mapPoints(mappedScreenPoint)
        out.set(mappedScreenPoint[0], mappedScreenPoint[1])
        return out
    }

    /** Screen corners mapped into canvas coordinates, reused to avoid per-frame allocations. */
    private fun visibleCanvasBounds(): RectF {
        visibleCanvasPoints[0] = 0f; visibleCanvasPoints[1] = 0f
        visibleCanvasPoints[2] = width.toFloat(); visibleCanvasPoints[3] = 0f
        visibleCanvasPoints[4] = width.toFloat(); visibleCanvasPoints[5] = height.toFloat()
        visibleCanvasPoints[6] = 0f; visibleCanvasPoints[7] = height.toFloat()
        canvasMatrix.invert(inverseMatrix)
        inverseMatrix.mapPoints(visibleCanvasPoints)
        var left = visibleCanvasPoints[0]
        var top = visibleCanvasPoints[1]
        var right = left
        var bottom = top
        var i = 2
        while (i < visibleCanvasPoints.size) {
            left = min(left, visibleCanvasPoints[i])
            right = max(right, visibleCanvasPoints[i])
            top = min(top, visibleCanvasPoints[i + 1])
            bottom = max(bottom, visibleCanvasPoints[i + 1])
            i += 2
        }
        visibleCanvasRect.set(left, top, right, bottom)
        return visibleCanvasRect
    }

    // ==================== Touch dispatch ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Stay in multi-touch handling until the whole gesture ends, even once it drops back to a
        // single finger - otherwise lifting one of two fingers would start drawing with the other.
        if (event.pointerCount > 1 || multiTouchMaxPointers > 0) {
            handleMultiTouch(event)
            return true
        }
        handleSingleTouch(event)
        return true
    }

    private fun handleMultiTouch(event: MotionEvent) {
        if (isDrawing) cancelStroke()
        cancelHolds()
        stopFling()

        if (!brushAdjustActive) scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (multiTouchMaxPointers == 0) {
                    multiTouchDownTime = event.eventTime
                    gestureStartTime = event.eventTime
                    multiTouchMoved = false
                    sessionStartFocusX = focusX(event)
                    sessionStartFocusY = focusY(event)
                    rotationEngaged = false
                    unengagedRotation = 0f
                    scaleAtGestureStart = currentScale()
                    // Two fingers held still become the brush size/opacity control.
                    postDelayed(brushAdjustRunnable, holdTimeoutMs)
                }
                // Restart the repeat timer on every extra finger, so the pointer count it reads is
                // the final one - two fingers mean undo, three mean redo, and the third often lands
                // a few milliseconds after the first two.
                removeCallbacks(repeatStartRunnable)
                removeCallbacks(repeatRunnable)
                postDelayed(repeatStartRunnable, HoldRepeat.INITIAL_DELAY_MS)
                stopFling()
                stopRotationFling()
                multiTouchMaxPointers = max(multiTouchMaxPointers, event.pointerCount)
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                lastRotationAngle = rotationAngleBetween(event)
                lastPanTime = event.eventTime
            }

            MotionEvent.ACTION_MOVE -> {
                val fx = focusX(event)
                val fy = focusY(event)

                if (hypot((fx - sessionStartFocusX).toDouble(), (fy - sessionStartFocusY).toDouble()) > tapMoveThresholdPx) {
                    multiTouchMoved = true
                    removeCallbacks(brushAdjustRunnable)
                    // Any real movement means this is a pan, pinch or scrub, not a hold.
                    stopRepeat()
                }

                if (brushAdjustActive) {
                    updateBrushAdjust(fx, fy)
                } else if (isTransformGestureActive()) {
                    // Two fingers belong to the transform while one is live, so the sheet must not
                    // pan or rotate underneath it - otherwise resizing an imported image also
                    // slides the whole drawing, which reads as the image refusing to be placed.
                    // Scale arrives separately via the ScaleGestureDetector.
                    val angle = rotationAngleBetween(event)
                    var deltaAngle = angle - lastRotationAngle
                    if (deltaAngle > 180f) deltaAngle -= 360f
                    if (deltaAngle < -180f) deltaAngle += 360f
                    val pivot = screenToCanvas(fx, fy, gestureCanvasPoint)
                    transformTool.rotate(deltaAngle, pivot.x, pivot.y)
                    lastRotationAngle = angle
                    invalidate()
                } else if (!isCanvasLocked) {
                    // Pan runs concurrently with zoom and rotate - Procreate treats all
                    // three as a unified input stream from the same two-finger gesture.
                    val dx = fx - lastFocusX
                    val dy = fy - lastFocusY
                    canvasMatrix.postTranslate(dx, dy)
                    val dt = (event.eventTime - lastPanTime).coerceAtLeast(1L)
                    // Blended into a running estimate so one jittery sample can't define the fling.
                    flingVelocityX = flingVelocityX * 0.6f + (dx / dt) * 0.4f
                    flingVelocityY = flingVelocityY * 0.6f + (dy / dt) * 0.4f

                    val angle = rotationAngleBetween(event)
                    var deltaAngle = angle - lastRotationAngle
                    // Normalize into [-180, 180] so the wraparound at +-180 doesn't cause a jump.
                    if (deltaAngle > 180f) deltaAngle -= 360f
                    if (deltaAngle < -180f) deltaAngle += 360f

                    if (!rotationEngaged) {
                        unengagedRotation += deltaAngle
                        if (abs(unengagedRotation) > ROTATION_ENGAGE_DEGREES) {
                            rotationEngaged = true
                            canvasMatrix.postRotate(unengagedRotation, fx, fy)
                            multiTouchMoved = true
                        }
                    } else {
                        canvasMatrix.postRotate(deltaAngle, fx, fy)
                        // Track angular velocity for rotation fling
                        angularVelocity = angularVelocity * 0.6f + (deltaAngle / dt) * 0.4f
                        lastRotationFocusX = fx
                        lastRotationFocusY = fy
                    }

                    // Angle snapping: when the canvas rotation is near a cardinal angle
                    // (0°, 90°, 180°, 270°), gently snap to it with haptic feedback.
                    if (rotationEngaged) {
                        applyAngleSnapping(fx, fy)
                    }

                    lastRotationAngle = angle
                    brushEngine.renderScale = currentScale()
                }

                lastFocusX = fx
                lastFocusY = fy
                lastPanTime = event.eventTime
                postInvalidateOnAnimation()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor onto the pointers that are staying, so the remaining fingers don't
                // yank the canvas by the departing finger's contribution to the centroid.
                if (event.pointerCount - 1 >= 2) {
                    lastFocusX = focusX(event, skipIndex = event.actionIndex)
                    lastFocusY = focusY(event, skipIndex = event.actionIndex)
                    // Recalculate rotation excluding the departing finger so the angle
                    // doesn't jump when the geometry of the remaining pointers differs.
                    lastRotationAngle = rotationAngleBetweenSkipping(event, event.actionIndex)
                    // Reset session start to the new focus so tap detection stays accurate.
                    sessionStartFocusX = lastFocusX
                    sessionStartFocusY = lastFocusY
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(brushAdjustRunnable)
                // A hold that already stepped through history has done its job; the release must not
                // also count as a tap and take one more step than the user saw.
                val didRepeat = stopRepeat()
                if (!brushAdjustActive && !didRepeat) {
                    val quick = !multiTouchMoved && (event.eventTime - multiTouchDownTime) < tapTimeoutMs
                    val totalDx = lastFocusX - sessionStartFocusX
                    val totalDy = lastFocusY - sessionStartFocusY
                    when {
                        quick -> fireTapShortcut(multiTouchMaxPointers)
                        multiTouchMaxPointers >= 3 && totalDy > SWIPE_MIN_DP * density &&
                            abs(totalDy) > abs(totalDx) * 1.5f -> clearActiveLayer()
                        multiTouchMaxPointers == 2 -> {
                            // Quick-pinch to fit: if the scale changed rapidly inward in a short gesture,
                            // or outward when already fitted, snap to fit-to-screen / restore previous view.
                            // Gated on the gesture having stayed roughly in place - without that, an
                            // entirely ordinary fast pan-while-zooming (the normal way anyone uses a
                            // two-finger gesture) could satisfy the duration+scale-ratio check on its own
                            // and get reinterpreted as "snap to fit", discarding exactly where the user
                            // just repositioned the view to.
                            val gestureDuration = event.eventTime - gestureStartTime
                            val scaleRatio = currentScale() / scaleAtGestureStart
                            val panDistance = hypot(totalDx.toDouble(), totalDy.toDouble())
                            val stayedInPlace = panDistance < QUICK_PINCH_MAX_PAN_DP * density
                            val isQuickPinchIn = stayedInPlace && gestureDuration < QUICK_PINCH_MAX_MS && scaleRatio < QUICK_PINCH_THRESHOLD
                            val isQuickPinchOut = stayedInPlace && gestureDuration < QUICK_PINCH_MAX_MS && scaleRatio > 1.35f &&
                                previousFitState != null && abs(scaleAtGestureStart - fitScale()) / fitScale() < 0.1f
                            if (isQuickPinchIn || isQuickPinchOut) {
                                quickPinchFit()
                            } else {
                                settleZoom()
                                startFling()
                                if (rotationEngaged && abs(angularVelocity) > ROTATION_FLING_MIN_SPEED) {
                                    startRotationFling()
                                }
                            }
                        }
                    }
                }
                brushAdjustActive = false
                isAngleSnapped = false
                lastSnappedAngle = Float.NaN
                multiTouchMaxPointers = 0
                multiTouchMoved = false
                invalidate()
            }
        }
    }

    // ==================== Hold to repeat undo / redo ====================

    /**
     * Holding two fingers keeps undoing, three keeps redoing - Procreate's "tap and hold to rapidly
     * step through recent changes".
     *
     * Without it, stepping back twenty strokes is twenty separate two-finger taps, which is the most
     * repeated action in sketching. [HoldRepeat] owns the timing curve so it can be tested.
     */
    private var repeatCount = 0
    private var repeatUndo = true

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val vm = viewModel ?: return
            val moved = if (repeatUndo) {
                if (!vm.canUndo) return
                vm.undo(); true
            } else {
                if (!vm.canRedo) return
                vm.redo(); true
            }
            if (!moved) return
            // Recorded so the release handler knows a hold happened and does not then fire the
            // single-tap shortcut on top of it, undoing one step more than the user watched.
            repeatFired = true
            invalidate()
            postDelayed(this, HoldRepeat.intervalFor(repeatCount))
            repeatCount++
        }
    }

    /** Called once the fingers have been down long enough that this is a hold, not a tap. */
    private val repeatStartRunnable = Runnable {
        // Only a still, unmoved two- or three-finger hold: a pinch or pan must never start undoing.
        if (multiTouchMoved || brushAdjustActive) return@Runnable
        when (multiTouchMaxPointers) {
            2 -> repeatUndo = true
            3 -> repeatUndo = false
            else -> return@Runnable
        }
        repeatCount = 0
        // Announce it, because a run of silent undos is indistinguishable from the app malfunctioning.
        gestureListener?.onHint(
            context.getString(if (repeatUndo) R.string.undo else R.string.redo)
        )
        repeatRunnable.run()
    }

    private var repeatFired = false

    private fun stopRepeat(): Boolean {
        removeCallbacks(repeatStartRunnable)
        removeCallbacks(repeatRunnable)
        val fired = repeatFired
        repeatFired = false
        repeatCount = 0
        return fired
    }

    private fun fireTapShortcut(pointers: Int) {
        when {
            pointers >= 4 -> gestureListener?.onToggleInterface()
            pointers == 3 -> {
                viewModel?.redo()
                gestureListener?.onHint(context.getString(R.string.redo))
            }
            pointers == 2 -> {
                viewModel?.undo()
                gestureListener?.onHint(context.getString(R.string.undo))
            }
        }
        invalidate()
    }

    /** Pinching in past the zoom floor snaps back to it. Also springs back from max zoom overshoot. */
    private fun settleZoom() {
        val scale = currentScale()
        val floor = minScale()
        if (scale < floor) {
            val target = Matrix(canvasMatrix)
            val s = floor / scale
            val fx = width / 2f
            val fy = height / 2f
            target.postScale(s, s, fx, fy)
            animateMatrixTo(target)
        } else if (scale > MAX_SCALE) {
            val target = Matrix(canvasMatrix)
            val s = MAX_SCALE / scale
            val fx = width / 2f
            val fy = height / 2f
            target.postScale(s, s, fx, fy)
            animateMatrixTo(target)
        }
    }

    private fun clearActiveLayer() {
        val vm = viewModel ?: return
        val index = vm.activeLayerIndex.value ?: return
        val layer = vm.layers.value?.getOrNull(index) ?: return
        if (layer.isLocked) return
        val target = if (layer.isEditingMask) layer.maskBitmap else layer.bitmap
        target ?: return
        val before = target.copy(Bitmap.Config.ARGB_8888, false)
        if (layer.isEditingMask) {
            target.eraseColor(Color.WHITE)
            vm.commitMaskEdit(index, before)
            gestureListener?.onHint(context.getString(R.string.layer_mask_cleared))
            invalidate()
            return
        }
        if (layer.isBackground) {
            CanvasBackground.paint(layer.bitmap, vm.backgroundStyle.value ?: CanvasBackgroundStyle.BLANK)
        } else {
            layer.bitmap.eraseColor(Color.TRANSPARENT)
        }
        vm.commitLayerEdit(index, before)
        gestureListener?.onHint(context.getString(R.string.gesture_layer_cleared))
        invalidate()
    }

    private fun focusX(event: MotionEvent, skipIndex: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sum += event.getX(i); count++
        }
        return if (count == 0) lastFocusX else sum / count
    }

    private fun focusY(event: MotionEvent, skipIndex: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sum += event.getY(i); count++
        }
        return if (count == 0) lastFocusY else sum / count
    }

    /** Angle (degrees) of the line between the first two active pointers, for two-finger rotation. */
    private fun rotationAngleBetween(event: MotionEvent): Float {
        if (event.pointerCount < 2) return lastRotationAngle
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    /**
     * Angle between the first two *remaining* pointers, skipping [skipIndex].
     * Used when a pointer lifts so the rotation doesn't jump from the old pair geometry
     * to the new one.
     */
    private fun rotationAngleBetweenSkipping(event: MotionEvent, skipIndex: Int): Float {
        val remaining = mutableListOf<Int>()
        for (i in 0 until event.pointerCount) {
            if (i != skipIndex) remaining.add(i)
        }
        if (remaining.size < 2) return lastRotationAngle
        val dx = event.getX(remaining[1]) - event.getX(remaining[0])
        val dy = event.getY(remaining[1]) - event.getY(remaining[0])
        return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    // ==================== Angle snapping ====================

    /** Returns the current canvas rotation (degrees). */
    private fun canvasRotation(): Float {
        canvasMatrix.getValues(matrixValues)
        return Math.toDegrees(
            Math.atan2(matrixValues[Matrix.MSKEW_Y].toDouble(), matrixValues[Matrix.MSCALE_X].toDouble())
        ).toFloat()
    }

    /**
     * If the canvas rotation is within ±[SNAP_ZONE_DEGREES] of a cardinal angle (0°, 90°, 180°,
     * 270°), snap to it and provide haptic feedback the first time.
     */
    private fun applyAngleSnapping(focusX: Float, focusY: Float) {
        val rotation = canvasRotation()
        val cardinals = floatArrayOf(0f, 90f, -90f, 180f, -180f)
        for (cardinal in cardinals) {
            var delta = rotation - cardinal
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            if (abs(delta) < SNAP_ZONE_DEGREES) {
                if (!isAngleSnapped || lastSnappedAngle != cardinal) {
                    // Snap: rotate the difference away
                    canvasMatrix.postRotate(-delta, focusX, focusY)
                    isAngleSnapped = true
                    lastSnappedAngle = cardinal
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                return
            }
        }
        // Left the snap zone
        if (isAngleSnapped) {
            isAngleSnapped = false
            lastSnappedAngle = Float.NaN
        }
    }

    // ==================== Pan momentum ====================

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!flingRunning) return
            // One frame's travel, decayed per frame - Procreate-style smooth glide that tapers
            // gently over about a second.
            canvasMatrix.postTranslate(flingVelocityX * FRAME_MS, flingVelocityY * FRAME_MS)
            flingVelocityX *= FLING_FRICTION
            flingVelocityY *= FLING_FRICTION
            invalidate()
            if (hypot(flingVelocityX.toDouble(), flingVelocityY.toDouble()) > FLING_MIN_SPEED) {
                postOnAnimation(this)
            } else {
                flingRunning = false
            }
        }
    }

    private fun startFling() {
        if (hypot(flingVelocityX.toDouble(), flingVelocityY.toDouble()) < FLING_MIN_SPEED) return
        flingRunning = true
        postOnAnimation(flingRunnable)
    }

    private fun stopFling() {
        flingRunning = false
        flingVelocityX = 0f
        flingVelocityY = 0f
        removeCallbacks(flingRunnable)
    }

    // ==================== Explicit canvas orientation ====================

    /**
     * Rotating, flipping and resetting the *view* of the canvas - the artwork's pixels are never
     * touched, so none of this is undoable and none of it marks the project dirty.
     *
     * Two-finger rotation already exists and snaps to cardinals, but a gesture cannot land on
     * exactly 90° on demand, and it offers no way to mirror the view at all. Flipping is the
     * oldest trick in drawing for catching a composition that has drifted out of balance: errors
     * the eye has stopped noticing become obvious the moment the image is mirrored.
     */
    fun rotateCanvasBy(degrees: Float) {
        cancelMomentum()
        canvasMatrix.postRotate(degrees, width / 2f, height / 2f)
        invalidate()
    }

    /** Mirrors the view across the vertical axis. */
    fun flipCanvasHorizontally() = mirrorCanvas(-1f, 1f)

    /** Mirrors the view across the horizontal axis. */
    fun flipCanvasVertically() = mirrorCanvas(1f, -1f)

    private fun mirrorCanvas(sx: Float, sy: Float) {
        cancelMomentum()
        // Scaled about the view's centre so the artwork stays where the user is looking; scaling
        // about the origin would throw it off screen.
        canvasMatrix.postScale(sx, sy, width / 2f, height / 2f)
        invalidate()
    }

    /**
     * Returns the view to upright, undoing any rotation and mirroring while keeping the current
     * zoom and position - after several flips it is otherwise genuinely hard to find "straight"
     * again by hand.
     */
    fun resetCanvasOrientation() {
        cancelMomentum()
        canvasMatrix.getValues(matrixValues)
        val a = matrixValues[Matrix.MSCALE_X]
        val b = matrixValues[Matrix.MSKEW_X]
        val c = matrixValues[Matrix.MSKEW_Y]
        val d = matrixValues[Matrix.MSCALE_Y]
        // |determinant| is the area scale regardless of rotation or mirroring; its square root is
        // the uniform zoom to restore. A negative determinant means the view is currently mirrored.
        val scale = kotlin.math.sqrt(abs(a * d - b * c)).coerceAtLeast(0.01f)
        val tx = matrixValues[Matrix.MTRANS_X]
        val ty = matrixValues[Matrix.MTRANS_Y]

        // Rebuild the matrix upright about the same on-screen point the canvas centre occupies now,
        // so the drawing does not jump somewhere else when straightened.
        val cx = width / 2f
        val cy = height / 2f
        val mappedX = a * cx + b * cy + tx
        val mappedY = c * cx + d * cy + ty

        canvasMatrix.reset()
        canvasMatrix.postScale(scale, scale)
        canvasMatrix.postTranslate(mappedX - cx * scale, mappedY - cy * scale)
        invalidate()
    }

    private fun cancelMomentum() {
        rotationFlingRunning = false
        angularVelocity = 0f
        stopFling()
    }

    // ==================== Rotation momentum ====================

    private val rotationFlingRunnable = object : Runnable {
        override fun run() {
            if (!rotationFlingRunning) return
            canvasMatrix.postRotate(
                angularVelocity * FRAME_MS,
                lastRotationFocusX, lastRotationFocusY
            )
            angularVelocity *= ROTATION_FLING_FRICTION
            invalidate()
            if (abs(angularVelocity) > ROTATION_FLING_MIN_SPEED) {
                postOnAnimation(this)
            } else {
                rotationFlingRunning = false
            }
        }
    }

    private fun startRotationFling() {
        rotationFlingRunning = true
        postOnAnimation(rotationFlingRunnable)
    }

    private fun stopRotationFling() {
        rotationFlingRunning = false
        angularVelocity = 0f
        removeCallbacks(rotationFlingRunnable)
    }

    // ==================== Quick-pinch to fit ====================

    /**
     * A fast pinch inward snaps the canvas to fit-to-screen, just like Procreate's quick-pinch.
     * If already at fit-scale and the user quick-pinches outward, restore the previous view.
     */
    private fun quickPinchFit() {
        val current = currentScale()
        val fit = fitScale()
        val isNearFit = abs(current - fit) / fit < 0.1f
        val prev = previousFitState
        if (isNearFit && prev != null) {
            val target = Matrix().apply { setValues(prev) }
            previousFitState = null
            animateMatrixTo(target)
            gestureListener?.onHint(context.getString(R.string.gesture_canvas_fit))
            return
        }
        // Save current state so a subsequent quick-pinch can restore it
        val saved = FloatArray(9)
        canvasMatrix.getValues(saved)
        previousFitState = saved
        fitCanvasToScreen(animated = true)
        gestureListener?.onHint(context.getString(R.string.gesture_canvas_fit))
    }

    // ==================== Hold gestures ====================

    private val eyedropperRunnable = Runnable {
        val color = sampleCompositeColor(downCanvasX, downCanvasY) ?: return@Runnable
        eyedropperActive = true
        eyedropperColor = color
        gestureListener?.onColorPicked(color)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private val brushAdjustRunnable = Runnable {
        if (multiTouchMaxPointers != 2 || multiTouchMoved) return@Runnable
        val brush = viewModel?.currentBrush?.value ?: return@Runnable
        brushAdjustActive = true
        brushAdjustStartX = lastFocusX
        brushAdjustStartY = lastFocusY
        brushAdjustStartSize = brush.size
        brushAdjustStartOpacity = brush.opacity
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun updateBrushAdjust(fx: Float, fy: Float) {
        val brush = viewModel?.currentBrush?.value ?: return
        // Radial distance from the hold point scales size multiplicatively (about one screen-inch
        // out doubles it) - dragging away in *any* direction grows the preview circle, and easing
        // back toward the hold point shrinks it back down, rather than only responding to
        // horizontal travel. Vertical travel maps linearly onto opacity, independent of the radius.
        val dx = fx - brushAdjustStartX
        val dy = fy - brushAdjustStartY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val sizeFactor = Math.pow(2.0, (distance / (90f * density)).toDouble()).toFloat()
        brush.size = (brushAdjustStartSize * sizeFactor).coerceIn(1f, 3000f)
        brush.opacity = (brushAdjustStartOpacity - dy / (220f * density)).coerceIn(0.02f, 1f)
        viewModel?.setCurrentBrush(brush)
        gestureListener?.onBrushChanged(brush.size, brush.opacity)
        invalidate()
    }

    private fun cancelHolds() {
        removeCallbacks(eyedropperRunnable)
        removeCallbacks(brushAdjustRunnable)
        if (eyedropperActive) {
            eyedropperActive = false
            invalidate()
        }
    }

    /**
     * The colour actually visible at a canvas point. The stack is composited into a one-pixel
     * bitmap through the same [LayerCompositor] the screen uses, so what the eyedropper reports is
     * exactly what the eye sees - blend modes, opacity and clipping masks included.
     */
    private fun sampleCompositeColor(canvasX: Float, canvasY: Float): Int? {
        val layers = viewModel?.layers?.value ?: return null
        val base = layers.firstOrNull()?.bitmap ?: return null
        val px = canvasX.toInt()
        val py = canvasY.toInt()
        if (px !in 0 until base.width || py !in 0 until base.height) return null

        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(probe)
        canvas.translate(-px.toFloat(), -py.toFloat())
        LayerCompositor.draw(canvas, layers)
        val sampled = probe.getPixel(0, 0)
        probe.recycle()
        return if (Color.alpha(sampled) == 0) null
        else Color.rgb(Color.red(sampled), Color.green(sampled), Color.blue(sampled))
    }

    // ==================== Urban Design Touch & Actions ====================

    fun startMeasurement(kind: MeasurementKind, unit: DistanceUnit) {
        pendingArchitecturalAssetId = null
        selectedArchitecturalAssetId = null
        selectedUrbanElement = null
        currentUrbanPoints.clear()
        measurementDisplayUnit = unit
        activeUrbanTool = when (kind) {
            MeasurementKind.POLYLINE_LENGTH -> UrbanToolType.MEASURE_DISTANCE
            MeasurementKind.POLYGON_AREA -> UrbanToolType.MEASURE_AREA
        }
        onUrbanNodesChanged?.invoke(0)
        onUrbanElementSelected?.invoke(null)
        invalidate()
    }

    /** Arms one catalog resource for a single tap placement in real-world metre coordinates. */
    fun beginArchitecturalAssetPlacement(assetId: String) {
        BuiltInArchitecturalAssets.catalog.requireAsset(assetId)
        activeUrbanTool = null
        currentUrbanPoints.clear()
        selectedUrbanElement = null
        selectedArchitecturalAssetId = null
        pendingArchitecturalAssetId = assetId
        onUrbanNodesChanged?.invoke(0)
        onUrbanElementSelected?.invoke(null)
        invalidate()
    }

    fun selectedArchitecturalAsset(): AssetInstance? = selectedArchitecturalAssetId?.let { id ->
        architecturalAssets.firstOrNull { it.id == id }
    }

    fun updateSelectedArchitecturalAsset(updated: AssetInstance) {
        val index = architecturalAssets.indexOfFirst { it.id == selectedArchitecturalAssetId }
        if (index < 0 || updated.id != selectedArchitecturalAssetId) return
        val before = createUrbanProjectSnapshot()
        architecturalAssets[index] = updated
        commitUrbanProjectChange(before)
        invalidate()
    }

    fun deleteSelectedArchitecturalAsset() {
        val id = selectedArchitecturalAssetId ?: return
        val before = createUrbanProjectSnapshot()
        if (!architecturalAssets.removeAll { it.id == id }) return
        selectedArchitecturalAssetId = null
        commitUrbanProjectChange(before)
        invalidate()
    }

    private fun handleArchitecturalAssetPlacement(event: MotionEvent, x: Float, y: Float) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        val assetId = pendingArchitecturalAssetId ?: return
        val before = createUrbanProjectSnapshot()
        val ppm = urbanScaleConfig.pixelsPerMeter
        require(ppm.isFinite() && ppm > 0f) { "Invalid project scale" }
        val instance = AssetInstance(
            id = java.util.UUID.randomUUID().toString(),
            assetId = assetId,
            xMeters = x / ppm.toDouble(),
            yMeters = y / ppm.toDouble(),
            zIndex = (architecturalAssets.maxOfOrNull { it.zIndex } ?: -1) + 1
        )
        architecturalAssets.add(instance)
        pendingArchitecturalAssetId = null
        selectedArchitecturalAssetId = instance.id
        commitUrbanProjectChange(before)
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
        onArchitecturalAssetEditRequested?.invoke(instance)
    }

    private fun hitTestArchitecturalAsset(x: Float, y: Float): AssetInstance? {
        val ppm = urbanScaleConfig.pixelsPerMeter
        if (!ppm.isFinite() || ppm <= 0f) return null
        val worldX = x / ppm.toDouble()
        val worldY = y / ppm.toDouble()
        val toleranceMeters = (14f * density / currentScale().coerceAtLeast(0.05f)) / ppm
        return architecturalAssets.asSequence()
            .filter { it.isVisible }
            .sortedWith(compareByDescending<AssetInstance> { it.zIndex })
            .firstOrNull { instance ->
                BuiltInArchitecturalAssets.catalog[instance.assetId]?.let { metadata ->
                    instance.containsWorldPoint(metadata, worldX, worldY, toleranceMeters.toDouble())
                } == true
            }
    }

    /** Returns true while an asset selection/move gesture owns this touch sequence. */
    private fun handleArchitecturalAssetSelection(event: MotionEvent, x: Float, y: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTestArchitecturalAsset(x, y) ?: run {
                    if (selectedArchitecturalAssetId != null) {
                        selectedArchitecturalAssetId = null
                        invalidate()
                    }
                    return false
                }
                selectedArchitecturalAssetId = hit.id
                if (selectedUrbanElement != null) {
                    selectedUrbanElement = null
                    onUrbanElementSelected?.invoke(null)
                }
                assetSelectionDownPoint.set(x, y)
                assetDragLastPoint.set(x, y)
                isDraggingArchitecturalAsset = !hit.isLocked
                assetGestureBefore = if (isDraggingArchitecturalAsset) createUrbanProjectSnapshot() else null
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val selected = selectedArchitecturalAsset() ?: return false
                if (!isDraggingArchitecturalAsset) return true
                val ppm = urbanScaleConfig.pixelsPerMeter
                val dxMeters = (x - assetDragLastPoint.x) / ppm.toDouble()
                val dyMeters = (y - assetDragLastPoint.y) / ppm.toDouble()
                assetDragLastPoint.set(x, y)
                val index = architecturalAssets.indexOfFirst { it.id == selected.id }
                if (index >= 0) architecturalAssets[index] = selected.translateBy(dxMeters, dyMeters)
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val selected = selectedArchitecturalAsset()
                assetGestureBefore?.let(::commitUrbanProjectChange)
                assetGestureBefore = null
                isDraggingArchitecturalAsset = false
                if (event.actionMasked == MotionEvent.ACTION_UP && selected != null) {
                    val moved = hypot(
                        (x - assetSelectionDownPoint.x).toDouble(),
                        (y - assetSelectionDownPoint.y).toDouble()
                    )
                    if (moved <= touchSlop) onArchitecturalAssetEditRequested?.invoke(selected)
                }
                invalidate()
                return selected != null
            }
        }
        return false
    }

    private fun handleCalibrationTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                calibStart = PointF(x, y)
                calibEnd = PointF(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                calibEnd = PointF(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val start = calibStart
                val end = PointF(x, y)
                calibEnd = end
                if (start != null) {
                    val dist = hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
                    if (dist > 8f) {
                        onScaleCalibrationMeasured?.invoke(dist, start, end)
                    }
                }
                isCalibratingScale = false
                calibStart = null
                calibEnd = null
                invalidate()
            }
        }
    }

    // ==================== Urban Element Selection & Editing ====================

    /** The mutable point list backing an element's shape, or null for a single-point marker. */
    private fun urbanElementPoints(element: UrbanElement): MutableList<PointF>? = when (element) {
        is UrbanElement.ArrowPath -> element.points
        is UrbanElement.HatchPolygon -> element.vertices
        is UrbanElement.BoundaryPath -> element.vertices
        is UrbanElement.PointMarker -> null
    }

    private fun minVertexCountFor(element: UrbanElement): Int = element.toolType.definition.minimumPoints

    private fun isClosedUrbanElement(element: UrbanElement): Boolean =
        element is UrbanElement.HatchPolygon || element.toolType == UrbanToolType.SITE_BOUNDARY

    /** The little draggable handle used to resize a point marker's radius, offset to the side
     * so it never sits on top of the marker itself. */
    private fun pointMarkerHandlePosition(marker: UrbanElement.PointMarker): PointF =
        PointF(marker.position.x + marker.radius + RESIZE_HANDLE_OFFSET_DP * density, marker.position.y)

    private fun distancePointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq > 0f) (((px - ax) * abx + (py - ay) * aby) / lenSq).coerceIn(0f, 1f) else 0f
        val cx = ax + abx * t
        val cy = ay + aby * t
        return hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
    }

    private fun isPointInPolygon(px: Float, py: Float, vertices: List<PointF>): Boolean {
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val vi = vertices[i]
            val vj = vertices[j]
            if ((vi.y > py) != (vj.y > py)) {
                val slopeX = vj.x + (py - vj.y) / (vi.y - vj.y) * (vi.x - vj.x)
                if (px < slopeX) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun isNearPolyline(x: Float, y: Float, points: List<PointF>, strokeWidth: Float, tolerance: Float, closed: Boolean): Boolean {
        if (points.size < 2) {
            val p = points.firstOrNull() ?: return false
            return hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= tolerance
        }
        val n = points.size
        val segCount = if (closed) n else n - 1
        for (i in 0 until segCount) {
            val a = points[i]
            val b = points[(i + 1) % n]
            if (distancePointToSegment(x, y, a.x, a.y, b.x, b.y) <= strokeWidth / 2f + tolerance) return true
        }
        return false
    }

    /** Finds the topmost (last-drawn) element under a tap, within a generous touch tolerance -
     * a filled hatch polygon counts a tap anywhere inside it, everything else only near its
     * outline/stroke. */
    private fun hitTestUrbanElement(x: Float, y: Float): UrbanElement? {
        // Fixed canvas-space tolerance corresponds to fewer actual screen pixels once zoomed out,
        // so it's divided by the current zoom to keep the effective on-screen tolerance constant.
        val tolerance = (URBAN_HIT_TOLERANCE_DP * density) / currentScale().coerceAtLeast(0.05f)
        for (element in urbanElements.asReversed()) {
            val hit = when (element) {
                is UrbanElement.PointMarker ->
                    hypot((x - element.position.x).toDouble(), (y - element.position.y).toDouble()) <= element.radius + tolerance
                is UrbanElement.ArrowPath -> isNearPolyline(x, y, element.points, element.strokeWidth, tolerance, closed = false)
                is UrbanElement.BoundaryPath -> isNearPolyline(
                    x, y, element.vertices, element.strokeWidth, tolerance, closed = isClosedUrbanElement(element)
                )
                is UrbanElement.HatchPolygon ->
                    isPointInPolygon(x, y, element.vertices) || isNearPolyline(x, y, element.vertices, 4f, tolerance, closed = true)
            }
            if (hit) return element
        }
        return null
    }

    private fun findUrbanVertexIndexAt(element: UrbanElement, x: Float, y: Float): Int? {
        val points = urbanElementPoints(element) ?: return null
        val touchR = URBAN_VERTEX_TOUCH_RADIUS_DP * density
        points.forEachIndexed { index, p ->
            if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= touchR) return index
        }
        return null
    }

    /** Recomputes the length/area a moved or reshaped element reports, so the legend and the
     * spatial tables stay accurate after an edit instead of showing the value from when it was
     * first drawn. */
    private fun recomputeUrbanElementMetrics(element: UrbanElement) {
        fun pathLengthMeters(points: List<PointF>): Float {
            var totalPx = 0f
            for (i in 1 until points.size) {
                val p1 = points[i - 1]; val p2 = points[i]
                totalPx += hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()).toFloat()
            }
            return urbanScaleConfig.pixelsToMeters(totalPx)
        }
        when (element) {
            is UrbanElement.ArrowPath -> element.lengthMeters = pathLengthMeters(element.points)
            is UrbanElement.BoundaryPath -> {
                element.lengthMeters = pathLengthMeters(element.vertices)
                if (element.toolType == UrbanToolType.SITE_BOUNDARY && element.vertices.size >= 3) {
                    val first = element.vertices.first()
                    val last = element.vertices.last()
                    element.lengthMeters += urbanScaleConfig.pixelsToMeters(
                        hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble()).toFloat()
                    )
                }
            }
            is UrbanElement.HatchPolygon -> {
                var areaPx = 0f
                val n = element.vertices.size
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    areaPx += element.vertices[i].x * element.vertices[j].y
                    areaPx -= element.vertices[j].x * element.vertices[i].y
                }
                element.areaSqMeters = urbanScaleConfig.pixelAreaToSqMeters(abs(areaPx) / 2f)
            }
            is UrbanElement.PointMarker -> {}
        }
    }

    /** Rebuilds every derived length/area after calibration, load, or undo/redo. */
    fun recomputeAllUrbanMetrics() {
        urbanElements.forEach(::recomputeUrbanElementMetrics)
        urbanLegendManager.recomputeFromElements(urbanElements)
        cachedPlazaTable = null
        cachedOtherAreasTable = null
        invalidate()
    }

    private fun deleteUrbanVertex(element: UrbanElement, index: Int) {
        val points = urbanElementPoints(element) ?: return
        if (points.size <= minVertexCountFor(element)) {
            gestureListener?.onHint(context.getString(R.string.urban_min_points_reached))
            return
        }
        if (index !in points.indices) return
        points.removeAt(index)
        recomputeUrbanElementMetrics(element)
        urbanLegendManager.recomputeFromElements(urbanElements)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun insertUrbanVertexNear(element: UrbanElement, x: Float, y: Float) {
        val points = urbanElementPoints(element) ?: return
        if (points.size < 2) return
        var bestIndex = 0
        var bestDist = Float.MAX_VALUE
        val n = points.size
        val segCount = if (isClosedUrbanElement(element)) n else n - 1
        for (i in 0 until segCount) {
            val a = points[i]
            val b = points[(i + 1) % n]
            val d = distancePointToSegment(x, y, a.x, a.y, b.x, b.y)
            if (d < bestDist) { bestDist = d; bestIndex = i }
        }
        points.add(bestIndex + 1, PointF(x, y))
        recomputeUrbanElementMetrics(element)
        urbanLegendManager.recomputeFromElements(urbanElements)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Deletes the whole selected element - exposed for a toolbar/panel "delete" action. */
    fun deleteSelectedUrbanElement() {
        val element = selectedUrbanElement ?: return
        val before = createUrbanProjectSnapshot()
        urbanElements.remove(element)
        selectedUrbanElement = null
        selectedUrbanVertexIndex = -1
        onUrbanElementSelected?.invoke(null)
        urbanLegendManager.recomputeFromElements(urbanElements)
        commitUrbanProjectChange(before)
        invalidate()
    }

    fun clearUrbanSelection() {
        if (selectedUrbanElement == null) return
        selectedUrbanElement = null
        selectedUrbanVertexIndex = -1
        onUrbanElementSelected?.invoke(null)
        invalidate()
    }

    /** The one number the floating selection bar's size stepper edits - stroke thickness for a
     * path/boundary, hatch spacing for a polygon, radius for a point marker. Dragging a vertex or
     * the point-marker resize handle changes the same underlying field; this is just the other,
     * coarser way to reach it, and what the stepper shows has to start from whatever that drag
     * last left it at. */
    fun selectedUrbanElementSize(): Float? = when (val e = selectedUrbanElement) {
        is UrbanElement.ArrowPath -> e.strokeWidth
        is UrbanElement.BoundaryPath -> e.strokeWidth
        is UrbanElement.HatchPolygon -> e.hatchSpacing
        is UrbanElement.PointMarker -> e.radius
        null -> null
    }

    fun setSelectedUrbanElementSize(size: Float) {
        if (selectedUrbanElement == null) return
        val before = createUrbanProjectSnapshot()
        when (val e = selectedUrbanElement) {
            is UrbanElement.ArrowPath -> e.strokeWidth = size.coerceIn(4f, 80f)
            is UrbanElement.BoundaryPath -> e.strokeWidth = size.coerceIn(4f, 80f)
            is UrbanElement.HatchPolygon -> e.hatchSpacing = size.coerceIn(12f, 120f)
            is UrbanElement.PointMarker -> e.radius = size.coerceIn(8f, 200f)
            null -> return
        }
        commitUrbanProjectChange(before)
        invalidate()
    }

    /** Hatch spacing specifically - kept as its own strip rather than folded into "size" above,
     * since a hatched polygon has no meaningful stroke-width/radius to speak of and the two were
     * being asked for as separate controls ("a size strip and a spacing strip, whichever applies
     * per element"). Only a HatchPolygon has this field, so this is null/no-op for anything else. */
    fun selectedUrbanElementSpacing(): Float? = (selectedUrbanElement as? UrbanElement.HatchPolygon)?.hatchSpacing

    fun setSelectedUrbanElementSpacing(spacing: Float) {
        val e = selectedUrbanElement as? UrbanElement.HatchPolygon ?: return
        val before = createUrbanProjectSnapshot()
        e.hatchSpacing = spacing.coerceIn(12f, 120f)
        commitUrbanProjectChange(before)
        invalidate()
    }

    /** The *other* meaning of "spacing" - not hatch density, but how far apart the numbered
     * nodes drawn along a boundary path are. This used to rebuild the path's actual vertex list
     * to a new even spacing, which - since UrbanSymbolRenderer.renderBoundary draws its connecting
     * line through that exact same list - visibly redrew the boundary's shape any time spacing
     * changed. It now reads/writes BoundaryPath.nodeSpacingPx instead: a display-only value the
     * renderer resamples *separately* from the real vertices, so the drawn line's shape can never
     * change no matter how the numbered nodes are spaced. Only BoundaryPath has this - an
     * ArrowPath's numbered stations (A/B/C) aren't derived from its point list the same way, so
     * there's nothing here for this control to usefully change on one. */
    fun selectedUrbanElementNodeSpacingMeters(): Float? {
        val boundary = selectedUrbanElement as? UrbanElement.BoundaryPath ?: return null
        val points = boundary.vertices.takeIf { it.size >= 2 } ?: return null
        if (boundary.nodeSpacingPx > 1f) return urbanScaleConfig.pixelsToMeters(boundary.nodeSpacingPx)
        // Unset (0): report the vertices' own current average spacing, so the slider starts
        // from what's actually on screen right now (one node per vertex) instead of from zero.
        var totalPx = 0f
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            totalPx += hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
        }
        return urbanScaleConfig.pixelsToMeters(totalPx / (points.size - 1))
    }

    fun setSelectedUrbanElementNodeSpacing(meters: Float) {
        val boundary = selectedUrbanElement as? UrbanElement.BoundaryPath ?: return
        val before = createUrbanProjectSnapshot()
        boundary.nodeSpacingPx = urbanScaleConfig.metersToPixels(meters.coerceIn(5f, 200f))
        commitUrbanProjectChange(before)
        invalidate()
    }

    // ==================== Batch editing of same-type elements ====================

    private var batchSelectedElements: MutableList<UrbanElement> = mutableListOf()
    /** Fired whenever the batch selection is cleared from *this* side (tapping empty canvas,
     * selecting a different single element) rather than from the batch popup's own close button,
     * so that popup - which has no other way to know the model moved out from under it - can
     * dismiss itself instead of lingering on screen with nothing behind it anymore. */
    var onBatchSelectionCleared: (() -> Unit)? = null

    fun isBatchSelectionActive(): Boolean = batchSelectedElements.isNotEmpty()

    /** Selects every element on the sheet sharing the currently-selected element's tool type, so
     * a size/spacing/color change can be applied to all of them in one motion instead of tapping
     * each one individually. Returns the batch, or empty if nothing is selected. */
    fun selectSimilarToSelected(): List<UrbanElement> {
        val current = selectedUrbanElement ?: return emptyList()
        batchSelectedElements = urbanElements.filter { it.toolType == current.toolType }.toMutableList()
        invalidate()
        return batchSelectedElements
    }

    fun clearBatchSelection() {
        if (batchSelectedElements.isEmpty()) return
        batchSelectedElements.clear()
        onBatchSelectionCleared?.invoke()
        invalidate()
    }

    /** Same size/spacing split as the single-element controls, applied uniformly across the
     * whole batch - every element in a batch shares one tool type, so they all resolve the same
     * branch below. */
    fun setBatchElementsSize(value: Float) {
        if (batchSelectedElements.isEmpty()) return
        val before = createUrbanProjectSnapshot()
        for (e in batchSelectedElements) {
            when (e) {
                is UrbanElement.ArrowPath -> e.strokeWidth = value.coerceIn(4f, 80f)
                is UrbanElement.BoundaryPath -> e.strokeWidth = value.coerceIn(4f, 80f)
                is UrbanElement.HatchPolygon -> e.hatchSpacing = value.coerceIn(12f, 120f)
                is UrbanElement.PointMarker -> e.radius = value.coerceIn(8f, 200f)
            }
        }
        urbanLegendManager.recomputeFromElements(urbanElements)
        commitUrbanProjectChange(before)
        invalidate()
    }

    fun setBatchElementsColor(color: Int) {
        if (batchSelectedElements.isEmpty()) return
        val before = createUrbanProjectSnapshot()
        for (e in batchSelectedElements) e.color = color
        urbanLegendManager.recomputeFromElements(urbanElements)
        commitUrbanProjectChange(before)
        invalidate()
    }

    fun deleteBatchElements() {
        if (batchSelectedElements.isEmpty()) return
        val before = createUrbanProjectSnapshot()
        val toRemove = batchSelectedElements.toHashSet()
        urbanElements.removeAll(toRemove)
        batchSelectedElements.clear()
        urbanLegendManager.recomputeFromElements(urbanElements)
        commitUrbanProjectChange(before)
        invalidate()
    }

    fun setSelectedUrbanElementColor(color: Int) {
        val element = selectedUrbanElement ?: return
        val before = createUrbanProjectSnapshot()
        element.color = color
        urbanLegendManager.recomputeFromElements(urbanElements)
        commitUrbanProjectChange(before)
        invalidate()
    }

    /**
     * Active whenever the Urban toolbar dock is showing its default bar rather than a focused
     * drawing tool - lets a previously drawn element be tapped to select it, then reshaped
     * (drag a vertex handle), moved (drag the body), resized (a marker's dedicated handle),
     * had a point added (long-press the body) or removed (long-press a vertex), all without
     * redrawing it from scratch.
     */
    private fun handleUrbanSelectionTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                urbanSelectionDownPoint.set(x, y)
                urbanDragLastPoint.set(x, y)
                isDraggingUrbanElement = false
                selectedUrbanVertexIndex = -1
                urbanGestureBefore = null
                removeCallbacks(urbanLongPressRunnable)
                urbanLongPressAction = null

                val current = selectedUrbanElement
                if (current is UrbanElement.PointMarker) {
                    val handle = pointMarkerHandlePosition(current)
                    if (hypot((x - handle.x).toDouble(), (y - handle.y).toDouble()) <= URBAN_VERTEX_TOUCH_RADIUS_DP * density) {
                        urbanGestureBefore = createUrbanProjectSnapshot()
                        selectedUrbanVertexIndex = RESIZE_HANDLE_INDEX
                        isDraggingUrbanElement = true
                        return
                    }
                }
                if (current != null) {
                    val vIndex = findUrbanVertexIndexAt(current, x, y)
                    if (vIndex != null) {
                        urbanGestureBefore = createUrbanProjectSnapshot()
                        selectedUrbanVertexIndex = vIndex
                        isDraggingUrbanElement = true
                        urbanLongPressAction = { deleteUrbanVertex(current, vIndex) }
                        postDelayed(urbanLongPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                        return
                    }
                    val reselectTolerance = (URBAN_HIT_TOLERANCE_DP * density) / currentScale().coerceAtLeast(0.05f)
                    val bodyHit = when (current) {
                        is UrbanElement.PointMarker ->
                            hypot((x - current.position.x).toDouble(), (y - current.position.y).toDouble()) <= current.radius + reselectTolerance
                        else -> urbanElementPoints(current)?.let {
                            isNearPolyline(x, y, it, 30f, reselectTolerance, isClosedUrbanElement(current)) ||
                                (isClosedUrbanElement(current) && isPointInPolygon(x, y, it))
                        } ?: false
                    }
                    if (bodyHit) {
                        urbanGestureBefore = createUrbanProjectSnapshot()
                        isDraggingUrbanElement = true
                        if (current !is UrbanElement.PointMarker) {
                            urbanLongPressAction = { insertUrbanVertexNear(current, x, y) }
                            postDelayed(urbanLongPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        return
                    }
                }

                val hit = hitTestUrbanElement(x, y)
                if (hit != null) {
                    selectedUrbanElement = hit
                    selectedUrbanElementScreenPoint.set(event.x, event.y)
                    onUrbanElementSelected?.invoke(hit)
                    isDraggingUrbanElement = true
                    urbanGestureBefore = createUrbanProjectSnapshot()
                    clearBatchSelection()
                } else if (current != null) {
                    selectedUrbanElement = null
                    onUrbanElementSelected?.invoke(null)
                } else if (batchSelectedElements.isNotEmpty()) {
                    clearBatchSelection()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val element = selectedUrbanElement ?: return
                val moved = hypot((x - urbanSelectionDownPoint.x).toDouble(), (y - urbanSelectionDownPoint.y).toDouble())
                if (moved > touchSlop) removeCallbacks(urbanLongPressRunnable)
                if (!isDraggingUrbanElement) return

                val dx = x - urbanDragLastPoint.x
                val dy = y - urbanDragLastPoint.y
                urbanDragLastPoint.set(x, y)

                when {
                    selectedUrbanVertexIndex == RESIZE_HANDLE_INDEX && element is UrbanElement.PointMarker -> {
                        val newRadius = hypot((x - element.position.x).toDouble(), (y - element.position.y).toDouble()).toFloat() - RESIZE_HANDLE_OFFSET_DP * density
                        element.radius = newRadius.coerceIn(8f, 200f)
                    }
                    selectedUrbanVertexIndex >= 0 -> {
                        val points = urbanElementPoints(element) ?: return
                        if (selectedUrbanVertexIndex < points.size) {
                            val p = points[selectedUrbanVertexIndex]
                            p.x += dx
                            p.y += dy
                        }
                        recomputeUrbanElementMetrics(element)
                    }
                    else -> {
                        when (element) {
                            is UrbanElement.PointMarker -> { element.position.x += dx; element.position.y += dy }
                            else -> urbanElementPoints(element)?.forEach { it.x += dx; it.y += dy }
                        }
                    }
                }
                // Keep drag frames light; legend/table metadata is committed at gesture end. The
                // selection callback is cheap by contrast (a couple of TextViews in the floating
                // edit popup), and firing it every move is what makes that popup track the
                // element live instead of only refreshing once the finger lifts.
                onUrbanElementSelected?.invoke(element)
                postInvalidateOnAnimation()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(urbanLongPressRunnable)
                urbanLongPressAction = null
                // A vertex/body/resize-handle drag changes the element's size or shape without
                // going through the floating settings bar's own stepper - re-firing the same
                // selection callback here is what keeps that stepper's displayed number (and the
                // color swatch) in sync with whatever the drag just left it at.
                if (isDraggingUrbanElement) {
                    urbanLegendManager.recomputeFromElements(urbanElements)
                    onUrbanElementSelected?.invoke(selectedUrbanElement)
                }
                urbanGestureBefore?.let(::commitUrbanProjectChange)
                urbanGestureBefore = null
                isDraggingUrbanElement = false
                selectedUrbanVertexIndex = -1
            }
        }
    }

    /**
     * Alternative scale calibration: instead of dragging two fresh points, pick two vertices
     * that belong to an already-drawn element (e.g. two corners of a boundary whose real-world
     * distance the user knows) and enter that known distance to derive pixelsPerMeter - same
     * destination ([onCalibrationVerticesPicked]) as the two-point drag gesture.
     */
    private fun handleCalibrationVertexPick(event: MotionEvent, x: Float, y: Float) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        val hit = hitTestUrbanElement(x, y)
        // Copies whichever vertex/position is picked rather than keeping a live reference to
        // it - the element could be dragged/reshaped by the time the second point is picked,
        // and the first calibration point must stay put.
        val snapped = hit?.let { element ->
            val points = urbanElementPoints(element)
            val raw = if (points != null) {
                findUrbanVertexIndexAt(element, x, y)?.let { points[it] }
                    ?: points.minByOrNull { hypot((x - it.x).toDouble(), (y - it.y).toDouble()) }
            } else if (element is UrbanElement.PointMarker) element.position else null
            raw?.let { PointF(it.x, it.y) }
        } ?: PointF(x, y)

        val first = calibVertexPick
        if (first == null) {
            calibVertexPick = snapped
            gestureListener?.onHint(context.getString(R.string.urban_calibration_pick_second))
            invalidate()
        } else {
            val dist = hypot((snapped.x - first.x).toDouble(), (snapped.y - first.y).toDouble()).toFloat()
            if (dist > 4f) {
                onCalibrationVerticesPicked?.invoke(dist, first, snapped)
            }
            calibVertexPick = null
            isPickingCalibrationVertices = false
            invalidate()
        }
    }

    /** Draws the dashed highlight outline plus draggable vertex/resize handles for the
     * currently selected Urban element. */
    private fun renderUrbanSelectionHandles(canvas: Canvas, element: UrbanElement) {
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = Color.parseColor("#00E5FF")
            pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
        }
        val contrastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * density
            color = Color.argb(215, 0, 0, 0)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
        val handleContrast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(225, 0, 0, 0)
        }
        val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = Color.parseColor("#00E5FF")
        }
        val handleR = URBAN_VERTEX_VISUAL_RADIUS_DP * density

        when (element) {
            is UrbanElement.PointMarker -> {
                canvas.drawCircle(element.position.x, element.position.y, element.radius + 6f * density, contrastPaint)
                canvas.drawCircle(element.position.x, element.position.y, element.radius + 6f * density, highlightPaint)
                val handle = pointMarkerHandlePosition(element)
                canvas.drawLine(element.position.x, element.position.y, handle.x, handle.y, contrastPaint)
                canvas.drawLine(element.position.x, element.position.y, handle.x, handle.y, highlightPaint)
                canvas.drawCircle(handle.x, handle.y, handleR + 2.5f * density, handleContrast)
                canvas.drawCircle(handle.x, handle.y, handleR, handleFill)
                canvas.drawCircle(handle.x, handle.y, handleR, handleStroke)
            }
            else -> {
                val points = urbanElementPoints(element) ?: return
                if (points.size >= 2) {
                    val path = Path()
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    if (isClosedUrbanElement(element)) path.close()
                    canvas.drawPath(path, contrastPaint)
                    canvas.drawPath(path, highlightPaint)
                }
                for (p in points) {
                    canvas.drawCircle(p.x, p.y, handleR + 2.5f * density, handleContrast)
                    canvas.drawCircle(p.x, p.y, handleR, handleFill)
                    canvas.drawCircle(p.x, p.y, handleR, handleStroke)
                }
            }
        }
    }

    /** A simpler, non-draggable outline for every element in a "select similar" batch - solid
     * amber rather than the single-selection's dashed cyan, and no vertex handles, since batch
     * editing only ever touches uniform properties (size/spacing/color) through the popup, never
     * individual reshaping. */
    private fun renderBatchSelectionHighlight(canvas: Canvas, elements: List<UrbanElement>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = Color.parseColor("#FFB300")
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        val contrast = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * density
            color = Color.argb(215, 0, 0, 0)
            strokeJoin = Paint.Join.ROUND
        }
        for (element in elements) {
            when (element) {
                is UrbanElement.PointMarker -> {
                    canvas.drawCircle(element.position.x, element.position.y, element.radius + 6f * density, contrast)
                    canvas.drawCircle(element.position.x, element.position.y, element.radius + 6f * density, paint)
                }
                else -> {
                    val points = urbanElementPoints(element) ?: continue
                    if (points.size < 2) continue
                    val path = Path()
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
                    if (isClosedUrbanElement(element)) path.close()
                    canvas.drawPath(path, contrast)
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    /**
     * Resolves the snap for one touch sample. The candidate lists are only materialised for the
     * modes actually switched on - continuous-drag fires a touch sample every few milliseconds, so
     * walking every element's vertices on each one would be real work done for nothing whenever
     * the user has only grid or ortho armed (or snapping off entirely).
     */
    private fun applyUrbanSnap(x: Float, y: Float): PointF {
        val settings = urbanSnapSettings
        if (!settings.anyEnabled) {
            lastSnapKind = com.procreate.android.urban.tools.SnapKind.NONE
            lastSnapPoint = null
            return PointF(x, y)
        }

        val needsGeometry = settings.vertex || settings.midpoint
        val points = if (needsGeometry) buildList {
            for (element in urbanElements) {
                urbanElementPoints(element)?.forEach { add(it.x to it.y) }
            }
            currentUrbanPoints.forEach { add(it.x to it.y) }
        } else emptyList()

        val segments = if (settings.midpoint) buildList {
            for (element in urbanElements) {
                val pts = urbanElementPoints(element) ?: continue
                for (i in 1 until pts.size) {
                    add((pts[i - 1].x to pts[i - 1].y) to (pts[i].x to pts[i].y))
                }
            }
        } else emptyList()

        val result = com.procreate.android.urban.tools.UrbanSnapEngine.snap(
            x = x,
            y = y,
            settings = settings,
            // Same scale-awareness the element hit test needs: a fixed canvas-space tolerance
            // would quietly demand more precision the further out the user zooms.
            toleranceCanvasPx = (URBAN_SNAP_TOLERANCE_DP * density) / currentScale().coerceAtLeast(0.05f),
            candidatePoints = points,
            candidateSegments = segments,
            gridSpacingPx = urbanScaleConfig.metersToPixels(urbanScaleConfig.gridCellSizeMeters),
            previousPoint = currentUrbanPoints.lastOrNull()?.let { it.x to it.y }
        )
        lastSnapKind = result.kind
        lastSnapPoint = if (result.kind == com.procreate.android.urban.tools.SnapKind.NONE) {
            null
        } else PointF(result.x, result.y)
        return PointF(result.x, result.y)
    }

    private val snapIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF00E5FF")
    }

    /**
     * A snap that silently relocates the user's point is indistinguishable from the app being
     * inaccurate, so the lock is drawn: a square marks a real vertex or midpoint, a circle marks
     * the abstract grid or an ortho axis. Counter-scaled so it stays the same size on screen.
     */
    private fun drawSnapIndicator(canvas: Canvas) {
        val point = lastSnapPoint ?: return
        if (currentUrbanPoints.isEmpty() && activeUrbanTool == null) return
        val scale = currentScale().coerceAtLeast(0.05f)
        val radius = (9f * density) / scale
        snapIndicatorPaint.strokeWidth = (1.8f * density) / scale
        when (lastSnapKind) {
            com.procreate.android.urban.tools.SnapKind.VERTEX,
            com.procreate.android.urban.tools.SnapKind.MIDPOINT ->
                canvas.drawRect(point.x - radius, point.y - radius, point.x + radius, point.y + radius, snapIndicatorPaint)
            com.procreate.android.urban.tools.SnapKind.GRID,
            com.procreate.android.urban.tools.SnapKind.ORTHO ->
                canvas.drawCircle(point.x, point.y, radius, snapIndicatorPaint)
            com.procreate.android.urban.tools.SnapKind.NONE -> return
        }
    }

    private fun handleUrbanTouch(event: MotionEvent, rawX: Float, rawY: Float) {
        val tool = activeUrbanTool ?: return
        // Snapping is resolved once, here, rather than inside each input-mode branch: every branch
        // below then works from the same corrected coordinates and none of them can drift apart.
        val snapped = applyUrbanSnap(rawX, rawY)
        val x = snapped.x
        val y = snapped.y

        when (urbanInputMode) {
            UrbanInputMode.POINT_BY_POINT -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        urbanDownPoint.set(x, y)
                    }
                    MotionEvent.ACTION_UP -> {
                        val d = hypot((x - urbanDownPoint.x).toDouble(), (y - urbanDownPoint.y).toDouble())
                        if (d < tapMoveThresholdPx) {
                            if (tool.definition.geometryKind == UrbanGeometryKind.POINT) {
                                currentUrbanPoints.clear()
                                currentUrbanPoints.add(PointF(x, y))
                                if (finalizeUrbanElement(tool)) {
                                    currentUrbanPoints.clear()
                                    onUrbanNodesChanged?.invoke(0)
                                }
                            } else {
                                // A point landing right on top of the previous one (finger jitter,
                                // an accidental double-tap near the same spot) is what made the
                                // last segment of an arrow near-zero-length - its direction is
                                // essentially touch noise, which is exactly what made the
                                // arrowhead angle (computed from the last two points) swing
                                // wildly, and made boundary nodes look unevenly spaced since a
                                // "corner" that's really just jitter sits almost on top of the
                                // real one before it. Filtering it out here, at the source, fixes
                                // both without touching how nodes are displayed.
                                val last = currentUrbanPoints.lastOrNull()
                                if (last == null || hypot((x - last.x).toDouble(), (y - last.y).toDouble()) >= tapMoveThresholdPx) {
                                    currentUrbanPoints.add(PointF(x, y))
                                    onUrbanNodesChanged?.invoke(currentUrbanPoints.size)
                                }
                            }
                            invalidate()
                        }
                    }
                }
            }
            UrbanInputMode.CONTINUOUS_DRAG -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        currentUrbanPoints.clear()
                        currentUrbanPoints.add(PointF(x, y))
                        onUrbanNodesChanged?.invoke(currentUrbanPoints.size)
                        invalidate()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val stepPx = (urbanScaleConfig.nodeDistanceMeters * urbanScaleConfig.pixelsPerMeter).coerceAtLeast(18f)
                        val last = currentUrbanPoints.lastOrNull()
                        if (last == null || hypot((x - last.x).toDouble(), (y - last.y).toDouble()) >= stepPx) {
                            currentUrbanPoints.add(PointF(x, y))
                            onUrbanNodesChanged?.invoke(currentUrbanPoints.size)
                            invalidate()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        // Unlike every ACTION_MOVE point above (spacing-gated by stepPx), this
                        // finishing point used to be appended unconditionally - a tiny movement
                        // right as the finger lifted produced a near-zero-length final segment,
                        // the same root cause as the arrowhead-angle and uneven-node-spacing
                        // reports. The last ACTION_MOVE point already stands in for the stroke's
                        // end, so this one is only added if it's actually somewhere new.
                        val last = currentUrbanPoints.lastOrNull()
                        if (last == null || hypot((x - last.x).toDouble(), (y - last.y).toDouble()) >= tapMoveThresholdPx) {
                            currentUrbanPoints.add(PointF(x, y))
                        }
                        if (finalizeUrbanElement(tool)) {
                            currentUrbanPoints.clear()
                            onUrbanNodesChanged?.invoke(0)
                        }
                        invalidate()
                    }
                }
            }
        }
    }

    fun finishCurrentUrbanPoly() {
        val tool = activeUrbanTool ?: return
        if (finalizeUrbanElement(tool)) {
            currentUrbanPoints.clear()
            onUrbanNodesChanged?.invoke(0)
        }
        invalidate()
    }

    fun undoLastUrbanPoint() {
        if (currentUrbanPoints.isNotEmpty()) {
            currentUrbanPoints.removeAt(currentUrbanPoints.size - 1)
            onUrbanNodesChanged?.invoke(currentUrbanPoints.size)
            invalidate()
        }
    }

    fun clearCurrentUrbanPoly() {
        currentUrbanPoints.clear()
        onUrbanNodesChanged?.invoke(0)
        invalidate()
    }

    fun cycleLegendPlacement(): OverlayPlacement {
        val before = createUrbanProjectSnapshot()
        legendPlacement = when (legendPlacement) {
            OverlayPlacement.BOTTOM_RIGHT -> OverlayPlacement.BOTTOM_LEFT
            OverlayPlacement.BOTTOM_LEFT -> OverlayPlacement.TOP_LEFT
            OverlayPlacement.TOP_LEFT -> OverlayPlacement.TOP_RIGHT
            OverlayPlacement.TOP_RIGHT -> OverlayPlacement.BOTTOM_RIGHT
            OverlayPlacement.CUSTOM -> OverlayPlacement.BOTTOM_RIGHT
        }
        commitUrbanProjectChange(before)
        invalidate()
        return legendPlacement
    }

    fun cycleTablePlacement(): OverlayPlacement {
        val before = createUrbanProjectSnapshot()
        tablePlacement = when (tablePlacement) {
            OverlayPlacement.BOTTOM_LEFT -> OverlayPlacement.BOTTOM_RIGHT
            OverlayPlacement.BOTTOM_RIGHT -> OverlayPlacement.TOP_RIGHT
            OverlayPlacement.TOP_RIGHT -> OverlayPlacement.TOP_LEFT
            OverlayPlacement.TOP_LEFT -> OverlayPlacement.BOTTOM_LEFT
            OverlayPlacement.CUSTOM -> OverlayPlacement.BOTTOM_LEFT
        }
        commitUrbanProjectChange(before)
        invalidate()
        return tablePlacement
    }

    fun cycleScaleCardPlacement(): OverlayPlacement {
        val before = createUrbanProjectSnapshot()
        scaleCardPlacement = when (scaleCardPlacement) {
            OverlayPlacement.BOTTOM_LEFT -> OverlayPlacement.TOP_LEFT
            OverlayPlacement.TOP_LEFT -> OverlayPlacement.TOP_RIGHT
            OverlayPlacement.TOP_RIGHT -> OverlayPlacement.BOTTOM_RIGHT
            OverlayPlacement.BOTTOM_RIGHT -> OverlayPlacement.BOTTOM_LEFT
            OverlayPlacement.CUSTOM -> OverlayPlacement.BOTTOM_LEFT
        }
        commitUrbanProjectChange(before)
        invalidate()
        return scaleCardPlacement
    }

    /** Resolves an [OverlayPlacement] to a canvas-space top-left anchor for a card of the given
     * footprint, with a fixed sheet margin - shared by the scale card, legend and tables so all
     * three corner-cycle consistently. */
    private fun anchorFor(placement: OverlayPlacement, cardW: Float, cardH: Float, canvasW: Float, canvasH: Float, margin: Float): PointF {
        val x = when (placement) {
            OverlayPlacement.TOP_LEFT, OverlayPlacement.BOTTOM_LEFT -> margin
            else -> canvasW - cardW - margin
        }
        val y = when (placement) {
            OverlayPlacement.TOP_LEFT, OverlayPlacement.TOP_RIGHT -> margin
            else -> canvasH - cardH - margin
        }
        return PointF(x, y)
    }

    private fun computeOverlayCoords(placement: OverlayPlacement, w: Float, h: Float): Pair<Float, Float> {
        val pad = 20f * density
        val topPad = 80f * density
        return when (placement) {
            OverlayPlacement.TOP_RIGHT -> Pair(width - w - pad, topPad)
            OverlayPlacement.TOP_LEFT -> Pair(pad, topPad)
            OverlayPlacement.BOTTOM_LEFT -> Pair(pad, height - h - pad)
            OverlayPlacement.BOTTOM_RIGHT, OverlayPlacement.CUSTOM -> Pair(width - w - pad, height - h - pad)
        }
    }

    private fun finalizeUrbanElement(tool: UrbanToolType): Boolean {
        val definition = tool.definition
        if (!definition.createsElement) return false
        if (!definition.hasEnoughPoints(currentUrbanPoints.size)) {
            gestureListener?.onHint(context.getString(R.string.urban_min_points_reached))
            return false
        }

        val before = createUrbanProjectSnapshot()
        val chosenColor = if (urbanScaleConfig.activeToolColor != 0) {
            urbanScaleConfig.activeToolColor
        } else tool.defaultColor
        val chosenStroke = urbanScaleConfig.activeToolStrokeWidth.coerceIn(4f, 80f)

        if (tool == UrbanToolType.MEASURE_DISTANCE || tool == UrbanToolType.MEASURE_AREA) {
            val kind = if (tool == UrbanToolType.MEASURE_AREA) {
                MeasurementKind.POLYGON_AREA
            } else {
                MeasurementKind.POLYLINE_LENGTH
            }
            urbanMeasurements.add(
                MeasurementElement(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = kind,
                    points = currentUrbanPoints.map { MeasurementPoint(it.x.toDouble(), it.y.toDouble()) },
                    displayUnit = measurementDisplayUnit
                )
            )
            commitUrbanProjectChange(before)
            return true
        }

        val element: UrbanElement = when {
            definition.geometryKind == UrbanGeometryKind.POINT -> {
                val stationLabel = if (tool == UrbanToolType.STATION_BADGE) {
                    val number = urbanElements.count { it.toolType == UrbanToolType.STATION_BADGE }
                    ('A'.code + number % 26).toChar().toString()
                } else null
                UrbanElement.PointMarker(
                    id = java.util.UUID.randomUUID().toString(),
                    toolType = tool,
                    color = chosenColor,
                    position = PointF(currentUrbanPoints.last().x, currentUrbanPoints.last().y),
                    label = stationLabel,
                    radius = (chosenStroke * 1.5f).coerceIn(10f, 90f)
                )
            }

            tool == UrbanToolType.SITE_BOUNDARY ||
                tool == UrbanToolType.CONTOUR_LINE ||
                tool == UrbanToolType.POLLUTION_WIRES -> {
                UrbanElement.BoundaryPath(
                    id = java.util.UUID.randomUUID().toString(),
                    toolType = tool,
                    color = chosenColor,
                    vertices = currentUrbanPoints.mapTo(mutableListOf()) { PointF(it.x, it.y) },
                    strokeWidth = chosenStroke,
                    showNodeNumbers = tool == UrbanToolType.SITE_BOUNDARY
                )
            }

            definition.geometryKind == UrbanGeometryKind.PATH -> {
                val isDotted = definition.preview.strokePattern == UrbanStrokePattern.DOTTED
                val isDashed = definition.preview.strokePattern == UrbanStrokePattern.DASHED
                UrbanElement.ArrowPath(
                    id = java.util.UUID.randomUUID().toString(),
                    toolType = tool,
                    color = chosenColor,
                    points = currentUrbanPoints.mapTo(mutableListOf()) { PointF(it.x, it.y) },
                    strokeWidth = chosenStroke,
                    isDotted = isDotted,
                    isDashed = isDashed,
                    hasTrailingDots = isDotted || isDashed,
                    arrowHeadType = if (tool == UrbanToolType.ENTRY_ARROW) {
                        ArrowHeadType.CHEVRON_WIDE
                    } else ArrowHeadType.LARGE_TRIANGLE
                )
            }

            definition.geometryKind == UrbanGeometryKind.POLYGON -> {
                val style = when (tool) {
                    UrbanToolType.PLAZA_HATCH, UrbanToolType.DIRT_PATH -> HatchStyle.DIAGONAL_45
                    UrbanToolType.FARM_HATCH -> HatchStyle.STIPPLE_DOTS
                    UrbanToolType.POLLUTION_RUIN -> HatchStyle.CROSS_HATCH
                    else -> HatchStyle.SOLID_FILL
                }
                UrbanElement.HatchPolygon(
                    id = java.util.UUID.randomUUID().toString(),
                    toolType = tool,
                    color = chosenColor,
                    vertices = currentUrbanPoints.mapTo(mutableListOf()) { PointF(it.x, it.y) },
                    hatchStyle = style,
                    hatchSpacing = (chosenStroke * 2f).coerceIn(12f, 120f),
                    plazaNumber = if (tool == UrbanToolType.PLAZA_HATCH) plazaCounter++ else null
                )
            }

            else -> return false
        }

        recomputeUrbanElementMetrics(element)
        urbanElements.add(element)
        urbanLegendManager.registerElement(element)
        commitUrbanProjectChange(before)
        return true
    }

    /** إلصاق صورة مفتاح الخريطة أو الجداول كطبقة على اللوحة */
    fun stampBitmapOnActiveLayer(bitmap: Bitmap, x: Float = 60f, y: Float = 60f) {
        val vm = viewModel ?: return
        val index = vm.activeLayerIndex.value ?: return
        val layers = vm.layers.value ?: return
        if (index !in layers.indices) return
        val layer = layers[index]
        val original = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val canvas = Canvas(layer.bitmap)
        canvas.drawBitmap(bitmap, x, y, Paint(Paint.FILTER_BITMAP_FLAG))
        vm.commitLayerEdit(index, original)
        invalidate()
    }

    /** إعادة تحجيم الطبقة النشطة لتطابق المقياس الحقيقي المقاس */
    fun rescaleActiveLayer(factor: Float, pivotX: Float = -1f, pivotY: Float = -1f) {
        viewModel?.rescaleActiveLayer(factor, pivotX, pivotY)
        invalidate()
    }

    /**
     * تصدير المخطط المعماري ودراسة الموقع بجودة عالية (PDF, PNG, JPEG)
     * مع خيارات التحكم بالشفافية وكامل اللوحة أو الكادر المخصص.
     */
    fun exportArtwork(
        format: com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat,
        isTransparent: Boolean,
        isFullCanvas: Boolean,
        jpegQuality: Int,
        includeOverlays: Boolean
    ) {
        // DXF is a real vector CAD file, not a flattened bitmap - it has nothing to do with the
        // Bitmap/Canvas flow the rest of this function builds, so it's handled entirely
        // separately and returns before any of that work happens.
        if (format == com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.DXF) {
            exportUrbanDxf(includeOverlays)
            return
        }
        val vm = viewModel ?: return
        val layers = vm.layers.value ?: return
        val firstLayer = layers.firstOrNull()?.bitmap ?: return

        val (exportW, exportH) = if (isFullCanvas) {
            Pair(firstLayer.width, firstLayer.height)
        } else {
            Pair(width.coerceAtLeast(1), height.coerceAtLeast(1))
        }

        val exportBmp = Bitmap.createBitmap(exportW, exportH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(exportBmp)

        if (!isTransparent) {
            canvas.drawColor(Color.WHITE)
        }
        val exportLayers = if (isTransparent) layers.filterNot { it.isBackground } else layers

        if (isFullCanvas) {
            LayerCompositor.draw(canvas, exportLayers)
            renderUrbanExportContent(canvas, exportW.toFloat(), exportH.toFloat(), includeOverlays)
        } else {
            canvas.save()
            canvas.concat(canvasMatrix)
            LayerCompositor.draw(canvas, exportLayers)
            renderUrbanExportContent(
                canvas, firstLayer.width.toFloat(), firstLayer.height.toFloat(), includeOverlays
            )
            canvas.restore()
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val filename = "UrbanCAD_$timestamp"
        val exportManager = com.procreate.android.export.ExportManager(context)

        val exportedFile = when (format) {
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PDF -> {
                exportManager.exportToPDF(exportBmp, filename)
            }
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PNG -> {
                exportManager.exportToPNG(exportBmp, filename)
            }
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.JPEG -> {
                exportManager.exportToJPEG(exportBmp, filename, jpegQuality)
            }
            // Unreachable - DXF already returned at the top of this function - but the `when`
            // still needs every enum case to stay exhaustive now that DXF exists.
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.DXF -> return
        }

        val mime = when (format) {
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PDF -> "application/pdf"
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PNG -> "image/png"
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.JPEG -> "image/jpeg"
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.DXF -> return
        }

        android.widget.Toast.makeText(context, "تم التصدير بنجاح: ${exportedFile.name}", android.widget.Toast.LENGTH_LONG).show()
        exportManager.shareDirectly(exportedFile, mime)
    }

    /** Urban geometry is the plan itself and is always exported. The checkbox controls only
     * presentation cards (legend/tables/scale), matching the wording in UrbanExportDialog. */
    private fun renderUrbanExportContent(
        canvas: Canvas,
        canvasWidth: Float,
        canvasHeight: Float,
        includePresentationCards: Boolean
    ) {
        if (urbanScaleConfig.isGridVisible) {
            UrbanGridRenderer.renderGrid(canvas, canvasWidth, canvasHeight, urbanScaleConfig)
        }
        urbanElements.forEach { element ->
            when (element) {
                is UrbanElement.ArrowPath -> UrbanArrowRenderer.renderArrow(canvas, element)
                is UrbanElement.HatchPolygon -> UrbanHatchRenderer.renderHatch(canvas, element)
                is UrbanElement.PointMarker -> UrbanSymbolRenderer.renderMarker(canvas, element)
                is UrbanElement.BoundaryPath -> UrbanSymbolRenderer.renderBoundary(canvas, element)
            }
        }
        if (!includePresentationCards) return

        class ExportCard(val width: Float, val height: Float, val draw: (Float, Float) -> Unit)
        val cardsByCorner = linkedMapOf<OverlayPlacement, MutableList<ExportCard>>()
        fun addCard(placement: OverlayPlacement, card: ExportCard) {
            cardsByCorner.getOrPut(placement) { mutableListOf() }.add(card)
        }

        if (urbanScaleConfig.isScaleCardVisible) {
            val size = UrbanScaleWidget.measureCardSize(urbanScaleConfig)
            addCard(scaleCardPlacement, ExportCard(size.x, size.y) { x, y ->
                UrbanScaleWidget.renderCard(canvas, x, y, urbanScaleConfig)
            })
        }
        if (showLiveLegend) {
            val items = urbanLegendManager.legendItems.value ?: emptyList()
            val width = 280f
            val height = 60f + (items.size * 32f).coerceAtLeast(64f)
            addCard(legendPlacement, ExportCard(width, height) { x, y ->
                LegendRenderer.renderLegendCard(canvas, x, y, items, width)
            })
        }
        if (showLiveTables) {
            val plazas = urbanElements.filterIsInstance<UrbanElement.HatchPolygon>()
                .filter { it.toolType == UrbanToolType.PLAZA_HATCH }
            if (plazas.isNotEmpty()) {
                val bitmap = getCachedPlazaTable(plazas)
                addCard(tablePlacement, ExportCard(bitmap.width.toFloat(), bitmap.height.toFloat()) { x, y ->
                    canvas.drawBitmap(bitmap, x, y, null)
                })
            }
            val otherAreas = otherAreasByType()
            if (otherAreas.isNotEmpty()) {
                val bitmap = getCachedOtherAreasTable(otherAreas)
                addCard(tablePlacement, ExportCard(bitmap.width.toFloat(), bitmap.height.toFloat()) { x, y ->
                    canvas.drawBitmap(bitmap, x, y, null)
                })
            }
        }

        val gap = 14f
        val margin = 40f
        cardsByCorner.forEach { (placement, cards) ->
            val groupWidth = cards.maxOf { it.width }
            val groupHeight = cards.sumOf { it.height.toDouble() }.toFloat() + gap * (cards.size - 1)
            val anchor = anchorFor(placement, groupWidth, groupHeight, canvasWidth, canvasHeight, margin)
            val stackDown = placement == OverlayPlacement.TOP_LEFT || placement == OverlayPlacement.TOP_RIGHT
            var y = if (stackDown) anchor.y else anchor.y + groupHeight
            cards.forEach { card ->
                if (!stackDown) y -= card.height
                val x = if (placement == OverlayPlacement.TOP_RIGHT || placement == OverlayPlacement.BOTTOM_RIGHT) {
                    anchor.x + groupWidth - card.width
                } else anchor.x
                card.draw(x, y)
                if (stackDown) y += card.height + gap else y -= gap
            }
        }
    }

    /**
     * Brings reviewed, accepted vector paths into the drawing as real elements.
     *
     * The source image is scaled to fit the current canvas while preserving its aspect ratio, so
     * an imported plan lands at a sensible size instead of at raw pixel coordinates that could sit
     * far outside the visible sheet. The whole insert is one undo step: an import the user doesn't
     * like should take one tap to reverse, not one per path.
     */
    fun insertVectorizedPaths(
        paths: List<com.procreate.android.vectorize.TracedPath>,
        sourceWidth: Int,
        sourceHeight: Int
    ): Int {
        if (paths.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return 0
        val canvasSize = canvasSize() ?: return 0
        val (canvasW, canvasH) = canvasSize
        val scale = minOf(canvasW.toFloat() / sourceWidth, canvasH.toFloat() / sourceHeight)
        val offsetX = (canvasW - sourceWidth * scale) / 2f
        val offsetY = (canvasH - sourceHeight * scale) / 2f

        val before = createUrbanProjectSnapshot()
        var inserted = 0
        for (traced in paths) {
            val vertices = traced.points.map {
                PointF(offsetX + it.x * scale, offsetY + it.y * scale)
            }
            if (vertices.size < 2) continue
            urbanElements.add(
                UrbanElement.BoundaryPath(
                    id = java.util.UUID.randomUUID().toString(),
                    toolType = UrbanToolType.SITE_BOUNDARY,
                    color = UrbanToolType.SITE_BOUNDARY.defaultColor,
                    vertices = vertices.toMutableList(),
                    // An auto-traced contour can carry many nodes; numbering them all would bury
                    // the drawing under labels the user never asked for.
                    showNodeNumbers = false
                )
            )
            inserted++
        }
        if (inserted == 0) return 0
        recomputeAllUrbanMetrics()
        commitUrbanProjectChange(before)
        invalidate()
        return inserted
    }

    /**
     * Inserts elements the user accepted from an AI plan reading.
     *
     * Shares the single-undo-step property with the local vectoriser: an import the user turns out
     * not to want should cost one tap to reverse, never one per element.
     */
    fun insertAnalyzedFeatures(
        features: List<com.procreate.android.ai.DetectedFeature>,
        sourceWidth: Int,
        sourceHeight: Int
    ): Int {
        if (features.isEmpty()) return 0
        val canvasSize = canvasSize() ?: return 0
        val (canvasW, canvasH) = canvasSize
        val elements = com.procreate.android.ai.PlanFeatureMapper.toElements(
            features = features,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            placement = com.procreate.android.ai.PlanFeatureMapper.Placement(canvasW, canvasH)
        )
        if (elements.isEmpty()) return 0

        val before = createUrbanProjectSnapshot()
        urbanElements.addAll(elements)
        recomputeAllUrbanMetrics()
        commitUrbanProjectChange(before)
        invalidate()
        return elements.size
    }

    /**
     * A plain-language account of exactly what the DXF will contain, shown before the export runs.
     * Worth the extra tap because a DXF that silently came out empty, or at the wrong scale, only
     * reveals itself after the file has been opened in AutoCAD on another machine.
     */
    fun urbanExportSummary(): String {
        val ppm = urbanScaleConfig.pixelsPerMeter
        val byType = urbanElements.groupingBy { it.toolType.titleAr }.eachCount()
        val lines = mutableListOf<String>()
        lines += "الوحدة: متر (1:1 في فضاء النموذج)"
        lines += "مقياس العرض: 1:${urbanScaleConfig.standardRatio}"
        lines += "المعايرة: ${if (urbanScaleConfig.isCalibrated) "معايَر" else "افتراضي غير معايَر"}"
        lines += ""
        lines += "عناصر التخطيط: ${urbanElements.size}"
        byType.entries.sortedByDescending { it.value }.take(8).forEach { (name, count) ->
            lines += "   • $name: $count"
        }
        if (byType.size > 8) lines += "   • (+${byType.size - 8} نوعاً آخر)"
        lines += "القياسات: ${urbanMeasurements.size}"
        lines += "الموارد المعمارية: ${architecturalAssets.size}"
        lines += "الطبقات المصدَّرة: ${urbanElements.map { it.toolType.name }.distinct().size + 5}"

        val allPoints = urbanElements.mapNotNull { urbanElementPoints(it) }.flatten()
        if (allPoints.isNotEmpty() && ppm > 0f) {
            val widthM = (allPoints.maxOf { it.x } - allPoints.minOf { it.x }) / ppm
            val heightM = (allPoints.maxOf { it.y } - allPoints.minOf { it.y }) / ppm
            lines += ""
            lines += "امتداد المخطط: ${"%.1f".format(widthM)} م × ${"%.1f".format(heightM)} م"
        } else if (urbanElements.isEmpty()) {
            lines += ""
            lines += "تحذير: لا توجد عناصر تخطيط — سيكون الملف فارغاً."
        }
        return lines.joinToString("\n")
    }

    /** Exports the Urban Design plan as a real, editable AutoCAD drawing - every element becomes
     * actual DXF geometry on a layer matching its tool type (see UrbanDxfExporter), never a
     * flattened picture. The general free-hand painting layers (unrelated to Urban Design) are
     * flattened via the same LayerCompositor.draw(...) call exportArtwork already uses and saved
     * as a sidecar PNG that the DXF's own IMAGE entity references by relative filename - a DXF
     * file can never embed raster pixel data inline, only point at an external file, so both
     * files have to be shared together for the picture to actually show up when reopened.
     * [includeOverlays] controls the legend annotation only (matching its on-screen checkbox
     * label) - the raster sidecar is always included, since vectorizing brush strokes was never
     * on the table to begin with. */
    private fun exportUrbanDxf(includeOverlays: Boolean) {
        // Checked before the scale-calibration prompt: making someone answer a technical question
        // about their drawing and only then telling them the export is paid is the wrong order.
        if (!com.procreate.android.billing.Entitlements.isUnlocked(
                context, com.procreate.android.billing.Entitlements.Feature.DXF_EXPORT
            )
        ) {
            // A View cannot show a DialogFragment; the activity owns the billing manager and the
            // fragment manager, so it decides what to show and re-runs the export if they subscribe.
            onPremiumRequired?.invoke { exportUrbanDxf(includeOverlays) }
            return
        }
        if (!urbanScaleConfig.isCalibrated) {
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("تأكيد مقياس DXF")
                .setMessage(
                    "لم تتم معايرة الرسم بعد. سيُستخدم المقياس الافتراضي " +
                        "(${urbanScaleConfig.pixelsPerMeter} بكسل/متر)، وسيبقى Model Space بالمتر 1:1."
                )
                .setPositiveButton("تصدير بالافتراضي") { _, _ ->
                    performUrbanDxfExport(includeOverlays)
                }
                .setNegativeButton("إلغاء والمعايرة أولًا", null)
                .show()
            return
        }
        performUrbanDxfExport(includeOverlays)
    }

    private fun performUrbanDxfExport(includeOverlays: Boolean) {
        val vm = viewModel ?: return
        val layers = vm.layers.value ?: return
        val firstLayer = layers.firstOrNull()?.bitmap ?: return

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val filename = "UrbanCAD_$timestamp"
        val exportManager = com.procreate.android.export.ExportManager(context)

        // A generated paper/background layer is presentation chrome, not a brush reference.
        // Including it made an Urban-only project look like it contained raster paint and could
        // place an opaque white image over the CAD vectors in some readers.
        val paintLayers = layers.filterNot { it.isBackground }
        val rasterBmp = Bitmap.createBitmap(firstLayer.width, firstLayer.height, Bitmap.Config.ARGB_8888)
        LayerCompositor.draw(Canvas(rasterBmp), paintLayers)
        // Urban Design elements live in their own vector list (urbanElements), entirely separate
        // from these paint layers - a project drawn purely in Urban CAD mode, never touched with
        // the free-hand brush, flattens to a fully transparent bitmap here. Sharing/saving that
        // as a PNG produced exactly the "blank white image" confusion: a transparent PNG previews
        // as solid white in most share-sheet thumbnails and file browsers. Skip the raster sidecar
        // entirely when there's nothing actually painted, rather than always attaching one.
        val rasterFile = if (hasVisibleContent(rasterBmp)) exportManager.exportToPNG(rasterBmp, "${filename}_raster") else null
        val brushRasterInfo = rasterFile?.let {
            com.procreate.android.export.UrbanDxfExporter.BrushRasterInfo(
                relativeFileName = it.name, pixelWidth = rasterBmp.width, pixelHeight = rasterBmp.height,
                anchorXPixels = 0f, anchorYPixels = 0f
            )
        }

        val legendItems = if (includeOverlays) (urbanLegendManager.legendItems.value ?: emptyList()) else emptyList()
        val dxfText = com.procreate.android.export.UrbanDxfExporter.export(
            urbanElements = urbanElements,
            scaleConfig = urbanScaleConfig,
            legendItems = legendItems,
            brushRaster = brushRasterInfo,
            measurements = urbanMeasurements,
            architecturalAssets = architecturalAssets
        )
        val dxfFile = exportManager.exportToDXF(dxfText, filename)

        // Actually save to the device's Downloads folder (findable in any file manager
        // afterward) rather than only ever offering the share sheet - that's the "save locally"
        // step that was missing before.
        val savedDxfUri = exportManager.saveToDownloads(dxfFile, "application/dxf")
        rasterFile?.let { exportManager.saveToDownloads(it, "image/png") }

        val message = buildString {
            append(if (savedDxfUri != null) "تم حفظ DXF في مجلد التنزيلات: ${dxfFile.name}" else "تم إنشاء DXF: ${dxfFile.name}")
            if (rasterFile != null) append(" (مع صورة الرسم الحر ${rasterFile.name})")
        }
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()

        // Handing the finished files up lets the host offer "save somewhere else" via the system
        // file picker, which a View cannot launch itself. Without a host attached this falls back
        // to the previous share-sheet behaviour so the export is never a dead end.
        val host = onUrbanExportCompleted
        if (host != null) {
            host(dxfFile, rasterFile)
            return
        }
        // A generic MIME broadens which apps the share chooser actually offers - a narrow/unusual
        // type like "application/dxf" filters out most apps that would otherwise happily accept
        // (and let the user re-save) an arbitrary file, which read as "there's no save option"
        // even though the file itself exported correctly.
        if (rasterFile != null) {
            exportManager.shareMultiple(listOf(dxfFile, rasterFile), "application/octet-stream")
        } else {
            exportManager.shareDirectly(dxfFile, "application/octet-stream")
        }
    }

    /** Exact row scan. A sparse sample can miss a one-pixel architectural line and silently drop
     * the entire brush-reference sidecar, so export correctness is worth the one-off linear pass. */
    private fun hasVisibleContent(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) {
                if (pixel ushr 24 != 0) return true
            }
        }
        return false
    }

    /** Exports the scale card, legend and spatial tables together as one sheet, separate from
     * the map itself - for handing over or printing the key/tables on their own rather than only
     * ever getting them baked onto the drawing via exportArtwork's includeOverlays. Rendered at
     * 2x the UI's own pixel sizes for a sharper result than a straight screen-resolution capture. */
    fun exportOverlaySheet(format: com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat, jpegQuality: Int) {
        val plazas = urbanElements.filterIsInstance<UrbanElement.HatchPolygon>()
            .filter { it.toolType == UrbanToolType.PLAZA_HATCH }
        val otherAreas = otherAreasByType()
        val legendItems = urbanLegendManager.legendItems.value ?: emptyList()
        val scaleVisible = urbanScaleConfig.isScaleCardVisible
        val scaleSize = if (scaleVisible) UrbanScaleWidget.measureCardSize(urbanScaleConfig) else PointF(0f, 0f)
        val tableBmp = if (plazas.isNotEmpty()) SpatialTableGenerator.generatePlazaTableBitmap(plazas) else null
        // Plazas and every other hatch-polygon type (paths, farms, buildings...) are separate
        // area tables - the sheet used to only ever build the plaza one, so any plan whose only
        // area elements were e.g. farms exported with no "جدول المساحات" at all even though they
        // were drawn on the map.
        val pathwaysTableBmp = if (otherAreas.isNotEmpty())
            SpatialTableGenerator.generateOtherAreasTableBitmap(otherAreas) else null
        val legendBmp = LegendRenderer.createLegendBitmap(legendItems)

        if (!scaleVisible && tableBmp == null && pathwaysTableBmp == null && legendBmp == null) {
            android.widget.Toast.makeText(context, "لا يوجد مقياس أو مفتاح أو جداول لتصديرها", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val padding = 40f
        val gap = 30f
        val contentWidth = maxOf(scaleSize.x, tableBmp?.width?.toFloat() ?: 0f, pathwaysTableBmp?.width?.toFloat() ?: 0f, legendBmp?.width?.toFloat() ?: 0f)
        var contentHeight = 0f
        if (scaleVisible) contentHeight += scaleSize.y + gap
        if (legendBmp != null) contentHeight += legendBmp.height + gap
        if (tableBmp != null) contentHeight += tableBmp.height + gap
        if (pathwaysTableBmp != null) contentHeight += pathwaysTableBmp.height + gap
        contentHeight = (contentHeight - gap).coerceAtLeast(0f)

        val exportScale = 2f
        val sheetW = ((contentWidth + padding * 2) * exportScale).toInt().coerceAtLeast(1)
        val sheetH = ((contentHeight + padding * 2) * exportScale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(sheetW, sheetH, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(bmp)
        sheetCanvas.drawColor(Color.WHITE)
        sheetCanvas.scale(exportScale, exportScale)

        var cursorY = padding
        if (scaleVisible) {
            UrbanScaleWidget.renderCard(sheetCanvas, padding, cursorY, urbanScaleConfig)
            cursorY += scaleSize.y + gap
        }
        legendBmp?.let {
            sheetCanvas.drawBitmap(it, padding, cursorY, null)
            cursorY += it.height + gap
        }
        tableBmp?.let {
            sheetCanvas.drawBitmap(it, padding, cursorY, null)
            cursorY += it.height + gap
        }
        pathwaysTableBmp?.let {
            sheetCanvas.drawBitmap(it, padding, cursorY, null)
        }

        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val filename = "UrbanCAD_Overlays_$timestamp"
        val exportManager = com.procreate.android.export.ExportManager(context)
        val exportedFile = when (format) {
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PDF -> exportManager.exportToPDF(bmp, filename)
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PNG -> exportManager.exportToPNG(bmp, filename)
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.JPEG -> exportManager.exportToJPEG(bmp, filename, jpegQuality)
            // This sheet is only ever called with PNG - DXF export is a completely separate flow
            // (exportUrbanDxf) - kept here only so the `when` stays exhaustive.
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.DXF -> return
        }
        val mime = when (format) {
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PDF -> "application/pdf"
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.PNG -> "image/png"
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.JPEG -> "image/jpeg"
            com.procreate.android.urban.ui.UrbanExportDialog.ExportFormat.DXF -> return
        }
        android.widget.Toast.makeText(context, "تم تصدير الجداول والمفاتيح: ${exportedFile.name}", android.widget.Toast.LENGTH_LONG).show()
        exportManager.shareDirectly(exportedFile, mime)
    }

    // ==================== Single-finger drawing ====================

    private fun handleSingleTouch(event: MotionEvent) {
        // Open canvas: put paper under the pen BEFORE the first sample is placed. This used to
        // happen only while moving, one chunk at a time, so a pen put down anywhere outside the
        // sheet - or a simple tap out there - landed on nothing and the "open" canvas stayed shut
        // until the user crept up to an edge first. It has to run before the point is converted to
        // canvas coordinates, because growing on the left or top shifts every canvas coordinate.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !isUrbanMode) {
            val tool = viewModel?.toolMode?.value
            if (tool == null || tool == ToolMode.DRAW || tool == ToolMode.SMUDGE || tool == ToolMode.BLUR) {
                expandForUpcomingSamples(event)
            }
        }

        // A physically finite screen should not become the boundary of an open drawing. When the
        // stylus reaches its rim, gently move the world underneath it; the point then continues in
        // canvas space and the normal growth path allocates paper ahead of the stroke. Two-finger
        // pan remains available and this never runs on fixed-size canvases.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && isDrawing && !isUrbanMode &&
            viewModel?.isOpenCanvas == true
        ) {
            autoPanOpenCanvasAtEdge(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && isDrawing && !isUrbanMode &&
            viewModel?.isOpenCanvas == true
        ) {
            stopEdgePan()
            // The lift sample can be farther out than the last MOVE on some stylus drivers. Make
            // room for it before the endpoint catch-up below, otherwise that final segment is the
            // only part of an edge-crossing stroke that can still be clipped.
            expandForUpcomingSamples(event)
        }
        val point = screenToCanvas(event.x, event.y, touchCanvasPoint)
        var x = point.x
        var y = point.y
        val pressure = event.pressure.coerceIn(0.05f, 1f)

        // The Urban CAD sheet is an open/expanding canvas like the painting one - grow it before
        // placing a node, calibration point or drag near its current edge, the same way brush
        // strokes already do, and keep using the post-growth point so nothing here works against
        // stale coordinates from before the paper (and everything already drawn on it) shifted.
        if (isUrbanMode) {
            val adjusted = expandForUrbanPoint(x, y)
            x = adjusted.x
            y = adjusted.y
        }

        if (isCalibratingScale) {
            handleCalibrationTouch(event, x, y)
            return
        }

        if (isPickingCalibrationVertices) {
            handleCalibrationVertexPick(event, x, y)
            return
        }

        if (isUrbanMode && pendingArchitecturalAssetId != null) {
            handleArchitecturalAssetPlacement(event, x, y)
            return
        }

        if (isUrbanMode && activeUrbanTool != null) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                routeUrbanTapToSelection = currentUrbanPoints.isEmpty() && hitTestUrbanElement(x, y) != null
            }
            if (routeUrbanTapToSelection) {
                handleUrbanSelectionTouch(event, x, y)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    routeUrbanTapToSelection = false
                }
                return
            }
            handleUrbanTouch(event, x, y)
            return
        }

        if (isUrbanMode && activeUrbanTool == null) {
            if (handleArchitecturalAssetSelection(event, x, y)) return
            handleUrbanSelectionTouch(event, x, y)
            return
        }

        when (viewModel?.toolMode?.value) {
            ToolMode.SELECTION -> { handleSelectionTouch(event, x, y); return }
            ToolMode.TRANSFORM -> { handleTransformTouch(event, x, y); return }
            else -> Unit
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                val vm = viewModel ?: return
                brushEngine.color = vm.currentColor.value ?: Color.BLACK
                brushEngine.properties = vm.currentBrush.value ?: BrushProperties()
                brushEngine.renderScale = currentScale()
                // Only a stylus reports which way its barrel points. Deciding this once per stroke
                // keeps a brush that takes its nib angle from the hand from snapping to straight up
                // the moment a finger touches the canvas.
                brushEngine.stylusAzimuthAvailable =
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                // The symmetry axis is fixed for the whole stroke at the paper's centre as it is
                // now; if an open canvas grows mid-stroke the axis moves with the artwork.
                val paper = vm.layers.value?.getOrNull(vm.activeLayerIndex.value ?: -1)?.bitmap
                    ?: vm.layers.value?.firstOrNull()?.bitmap
                val mirrorVertical =
                    symmetryMode == SymmetryMode.VERTICAL || symmetryMode == SymmetryMode.QUAD
                val mirrorHorizontal =
                    symmetryMode == SymmetryMode.HORIZONTAL || symmetryMode == SymmetryMode.QUAD
                symmetryStroke.begin(
                    vertical = mirrorVertical && paper != null,
                    horizontal = mirrorHorizontal && paper != null,
                    centerX = (paper?.width ?: 0) / 2f,
                    centerY = (paper?.height ?: 0) / 2f,
                    x = x, y = y, pressure = pressure,
                    tilt = stylusTilt(event), azimuth = stylusAzimuth(event)
                )
                symmetryStroke.resetDirty()
                strokeInput.reset()
                strokeInput.add(pressure, stylusTilt(event), stylusAzimuth(event))
                drawingAudioEngine.setBrushMaterial(brushEngine.properties.type, vm.toolMode.value ?: ToolMode.DRAW)
                vm.activeLayerIndex.value?.let { vm.prepareEdit(it) }

                isDrawing = true
                pendingDot = true
                strokeDirty.setEmpty()
                resetAlphaLockBuffers()
                downCanvasX = x
                downCanvasY = y
                downPressure = pressure
                smoothedX = x
                smoothedY = y
                smoothedVelocity = 0f
                smoothedPressure = pressure
                lastSmudgeX = x
                lastSmudgeY = y
                gridCellsFilledThisStroke.clear()
                lastGridX = x
                lastGridY = y
                lastTouchX = event.x
                lastTouchY = event.y
                lastEventTime = event.eventTime
                lastEdgePanTime = event.eventTime
                edgePenX = event.x
                edgePenY = event.y
                edgePenPressure = pressure
                drawingAudioEngine.startStroke(pressure)

                if (onLocalCollaborationStroke != null) {
                    collaborationStrokeId = java.util.UUID.randomUUID().toString()
                    lastCollaborationPreviewAt = event.eventTime
                    emitLocalCollaborationPoint(StrokePhase.START, x, y, pressure)
                }

                resetStrokePoints(x, y)
                activeQuickShape = null
                removeCallbacks(quickShapeRunnable)
                if (isQuickShapeEnabled && !isUrbanMode) {
                    postDelayed(quickShapeRunnable, quickShapeHoldMs)
                }

                if (!isUrbanMode) {
                    eyedropperPoint.set(event.x, event.y)
                    postDelayed(eyedropperRunnable, holdTimeoutMs)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return
                if (eyedropperActive) {
                    // Once the loupe is up, dragging keeps re-sampling instead of painting.
                    sampleCompositeColor(x, y)?.let {
                        eyedropperColor = it
                        gestureListener?.onColorPicked(it)
                    }
                    eyedropperPoint.set(event.x, event.y)
                    invalidate()
                    return
                }
                val distFromDown = hypot((event.x - eyedropperPoint.x).toDouble(), (event.y - eyedropperPoint.y).toDouble())
                if (distFromDown > touchSlop * 0.75f) {
                    removeCallbacks(eyedropperRunnable)
                }

                addStrokePoint(x, y)
                removeCallbacks(quickShapeRunnable)
                if (isQuickShapeEnabled && !isUrbanMode) {
                    postDelayed(quickShapeRunnable, quickShapeHoldMs)
                }

                expandForUpcomingSamples(event)

                // Moving stroke: clear pendingDot so it does not stamp an extra static dab
                pendingDot = false

                paintBatch { canvas, target ->
                    val activeMode = viewModel?.toolMode?.value
                    // drawBlurSegment already covers and masks the complete segment from the last
                    // accepted point to the newest one. Replaying every 120 Hz historical sample
                    // made the same area run through a multi-pass blur many times in one UI frame;
                    // on a large brush that is enough to starve input dispatch and trigger an ANR.
                    // Coalescing blur keeps the identical continuous path with one blur operation.
                    if (activeMode == ToolMode.BLUR) {
                        feedSample(
                            canvas, target, event.x, event.y, pressure, event.eventTime,
                            stylusTilt(event), stylusAzimuth(event)
                        )
                        return@paintBatch
                    }
                    // Every batched sample is replayed, not just the newest. On a 120Hz+ digitizer
                    // most of a fast stroke's shape lives in these historical points; dropping them
                    // is what left long straight chords between widely-spaced dabs. The barrel axes
                    // are read per sample for the same reason: a wrist turning through a letter
                    // does most of its turning inside a single batch.
                    for (h in 0 until event.historySize) {
                        feedSample(
                            canvas, target,
                            event.getHistoricalX(h), event.getHistoricalY(h),
                            event.getHistoricalPressure(h).coerceIn(0.05f, 1f),
                            event.getHistoricalEventTime(h),
                            stylusTilt(event, h), stylusAzimuth(event, h)
                        )
                    }
                    feedSample(
                        canvas, target, event.x, event.y, pressure, event.eventTime,
                        stylusTilt(event), stylusAzimuth(event)
                    )
                }
                if (event.eventTime - lastCollaborationPreviewAt >= 32L) {
                    val live = screenToCanvas(event.x, event.y)
                    emitLocalCollaborationPoint(StrokePhase.MOVE, live.x, live.y, pressure)
                    lastCollaborationPreviewAt = event.eventTime
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawingAudioEngine.endStroke()
                removeCallbacks(eyedropperRunnable)
                removeCallbacks(quickShapeRunnable)
                if (!isDrawing) return
                if (eyedropperActive) {
                    eyedropperActive = false
                    isDrawing = false
                    pendingDot = false
                    symmetryStroke.end(null, null)
                    invalidate()
                    return
                }

                if (activeQuickShape != null) {
                    val shape = activeQuickShape!!
                    activeQuickShape = null
                    commitQuickShape(shape)
                } else {
                    paintBatch { canvas, target ->
                        if (pendingDot) {
                            // Stationary tap: lay down a single clean dab
                            pendingDot = false
                            val mode = viewModel?.toolMode?.value
                            if (mode != ToolMode.SMUDGE && mode != ToolMode.BLUR) {
                                val module = gridModuleSize()
                                if (module != null) {
                                    // A tap on a Kufic grid sets a single module, which is how the
                                    // isolated squares in a composition are placed.
                                    fillGridCell(canvas, downCanvasX, downCanvasY, module)
                                } else {
                                    symmetryStroke.stampDot(
                                        canvas, target, downCanvasX, downCanvasY, downPressure,
                                        stylusTilt(event), stylusAzimuth(event)
                                    )
                                }
                            }
                        } else {
                            // Consume the lift coordinate before flushing the spline. Without this,
                            // stabilization intentionally trails the pen and a stroke lifted at the
                            // display/canvas edge appears to stop short.
                            settleStabilizerAtLift(
                                canvas, target, event.x, event.y, pressure, event.eventTime,
                                stylusTilt(event), stylusAzimuth(event)
                            )
                            symmetryStroke.end(canvas, target)
                        }
                    }
                }
                isDrawing = false
                stopEdgePan()
                finishStroke()
                emitLocalCollaborationPoint(StrokePhase.END, x, y, pressure)
                collaborationStrokeId = null
            }
        }
    }

    /**
     * Replace the freehand stroke with the shape it snapped to, painted by the brush itself.
     *
     * The freehand line was already on the layer by the time the pen paused, so it is first put
     * back to the pixels the gesture started from; drawing the shape over it left the wobbly
     * original and the clean shape side by side. The shape is then fed through the same engines a
     * stroke uses, which gives it the brush's size in canvas pixels (not scaled by zoom), its tip,
     * grain, the stroke's median pressure, eraser behaviour, uniform-coverage opacity and symmetry. The
     * gesture remains a single undo step whose before-image is the untouched layer.
     */
    private fun commitQuickShape(shape: QuickShape) {
        val vm = viewModel ?: return
        val index = vm.activeLayerIndex.value ?: return

        val restored = vm.restoreCapturedTiles(index)
        if (!restored.isEmpty) {
            val layer = vm.layers.value?.getOrNull(index)
            val edited = if (layer?.isEditingMask == true) layer.maskBitmap else layer?.bitmap
            edited?.let { LayerCompositor.invalidateBitmapRegion(it, restored) }
            strokeDirty.union(restored)
            invalidateCanvasRect(restored)
        }
        // Scratch buffers still hold the freehand stroke; start them again from the restored layer.
        resetAlphaLockBuffers()
        gridCellsFilledThisStroke.clear()

        val pressure = quickShapePressure
        val tilt = quickShapeTilt
        val azimuth = quickShapeAzimuth
        val points = QuickShapeEngine.sample(shape, brushEngine.shapeSampleSpacing(pressure, tilt))
        if (points.size < 4) return
        symmetryStroke.restart(points[0], points[1], pressure, tilt, azimuth)

        paintBatch { canvas, target ->
            val mode = vm.toolMode.value
            val module = gridModuleSize()
            when {
                mode == ToolMode.SMUDGE || mode == ToolMode.BLUR -> {
                    // These tools process a whole segment per call; one call per sub-pixel sample
                    // would re-blur the same area thousands of times.
                    val minStep = max(2f, brushEngine.properties.size * 0.25f)
                    var fromX = points[0]
                    var fromY = points[1]
                    var i = 2
                    while (i < points.size) {
                        val x = points[i]
                        val y = points[i + 1]
                        val last = i + 2 >= points.size
                        if (last || hypot(x - fromX, y - fromY) >= minStep) {
                            if (mode == ToolMode.SMUDGE) {
                                symmetryStroke.smudgeSegment(canvas, target, fromX, fromY, x, y, pressure)
                            } else {
                                symmetryStroke.blurSegment(canvas, target, fromX, fromY, x, y, pressure)
                            }
                            fromX = x
                            fromY = y
                        }
                        i += 2
                    }
                }
                module != null -> {
                    lastGridX = points[0]
                    lastGridY = points[1]
                    var i = 2
                    while (i < points.size) {
                        paintGridSegment(canvas, points[i], points[i + 1], module)
                        i += 2
                    }
                }
                else -> {
                    var i = 2
                    while (i < points.size) {
                        symmetryStroke.strokeTo(canvas, target, points[i], points[i + 1], pressure, 0f, tilt, azimuth)
                        i += 2
                    }
                    symmetryStroke.end(canvas, target)
                }
            }
        }
    }

    private fun emitLocalCollaborationPoint(
        phase: StrokePhase,
        x: Float,
        y: Float,
        pressure: Float
    ) {
        val id = collaborationStrokeId ?: return
        val vm = viewModel ?: return
        val brush = vm.currentBrush.value ?: BrushProperties()
        onLocalCollaborationStroke?.invoke(LiveStrokeEvent(
            strokeId = id,
            phase = phase,
            x = x,
            y = y,
            pressure = pressure,
            color = vm.currentColor.value ?: Color.BLACK,
            size = brush.size,
            opacity = brush.opacity,
            eraser = brush.type == BrushType.Eraser
        ))
    }

    /**
     * The side of one square-Kufic module, in canvas pixels, or null when this brush is ordinary.
     *
     * Derived from the paper's own grid pitch rather than a setting of its own, so the letters land
     * on the squares the artist can see. A Kufic composition that ignored the ruling underneath it
     * would be the one thing this style cannot be.
     */
    private fun gridModuleSize(): Float? {
        val divisions = brushEngine.properties.gridSnapDivisions
        if (divisions <= 0) return null
        val pitch = viewModel?.backgroundPatternPitch()?.toFloat() ?: return null
        return (pitch / divisions).coerceAtLeast(2f)
    }

    /**
     * Fill every grid cell the segment from the last point to ([toX], [toY]) passes through.
     *
     * Stepping along the segment rather than filling only at the sample points is what keeps a fast
     * diagonal from coming out as a dotted line of cells: the hand can cross several modules
     * between two touch samples, and every one of them belongs to the bar.
     */
    private fun paintGridSegment(canvas: Canvas, toX: Float, toY: Float, module: Float) {
        val fromX = lastGridX
        val fromY = lastGridY
        lastGridX = toX
        lastGridY = toY
        val distance = hypot((toX - fromX).toDouble(), (toY - fromY).toDouble()).toFloat()
        val steps = ceil(distance / (module * 0.4f)).toInt().coerceIn(1, 512)
        for (step in 0..steps) {
            val t = if (steps == 0) 0f else step / steps.toFloat()
            fillGridCell(canvas, fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, module)
        }
    }

    private fun fillGridCell(canvas: Canvas, x: Float, y: Float, module: Float) {
        fillGridCellAt(canvas, x, y, module)
        // A symmetric Kufic composition sets the mirrored module too.
        symmetryStroke.forEachMirroredPoint(x, y) { mx, my -> fillGridCellAt(canvas, mx, my, module) }
    }

    private fun fillGridCellAt(canvas: Canvas, x: Float, y: Float, module: Float) {
        val column = floor(x / module).toInt()
        val row = floor(y / module).toInt()
        // Pack both coordinates into one key; a canvas can be tens of thousands of cells across,
        // so the halves are kept wide enough that no realistic column can collide with a row.
        val key = (column.toLong() shl 32) xor (row.toLong() and 0xFFFFFFFFL)
        if (!gridCellsFilledThisStroke.add(key)) return

        val left = column * module
        val top = row * module
        val rect = RectF(left, top, left + module, top + module)
        brushEngine.beforeWrite?.invoke(rect)
        gridCellPaint.color = brushEngine.color
        gridCellPaint.alpha = (brushEngine.properties.opacity * 255f).toInt().coerceIn(1, 255)
        gridCellPaint.blendMode = if (brushEngine.properties.type == BrushType.Eraser) {
            android.graphics.BlendMode.DST_OUT
        } else {
            android.graphics.BlendMode.SRC_OVER
        }
        // Whole pixels, no anti-aliasing: the edge of a module must meet its neighbour exactly, or
        // a run of cells shows seams where the soft edges overlap.
        canvas.drawRect(rect, gridCellPaint)
        brushEngine.dirtyRect.union(rect)
    }

    /**
     * How far the pen is laid over, in radians from perpendicular.
     *
     * Android reports this on the tilt axis for any stylus that measures it; a finger or a mouse
     * reports nothing, and zero - dead upright - is the right neutral for them, because every
     * tilt-driven term multiplies by it.
     */
    private fun stylusTilt(event: MotionEvent, historyIndex: Int = -1): Float {
        val raw = if (historyIndex >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, 0, historyIndex)
        } else {
            event.getAxisValue(MotionEvent.AXIS_TILT, 0)
        }
        return if (raw.isFinite()) raw.coerceIn(0f, (Math.PI / 2).toFloat()) else 0f
    }

    /**
     * Which way the barrel points, in radians clockwise from straight up the screen.
     *
     * Unlike tilt there is no safe neutral here - zero is a real direction - so whether this value
     * means anything is carried separately on the engine, decided from the tool type at the start
     * of the stroke.
     */
    private fun stylusAzimuth(event: MotionEvent, historyIndex: Int = -1): Float {
        val raw = if (historyIndex >= 0) {
            event.getHistoricalOrientation(0, historyIndex)
        } else {
            event.getOrientation(0)
        }
        return if (raw.isFinite()) raw else 0f
    }

    /**
     * Bring the stabilized point all the way onto the lift coordinate.
     *
     * Consuming the lift as a single full-response sample used to close the gap in one move, which
     * meant the last stretch of every smoothed stroke was painted as one straight segment: pressure
     * and velocity were sampled once for the whole distance, so a tapered brush ended in a blunt
     * bar rather than closing. Walking the remainder out in short steps paints it as ordinary
     * stroke, at the brush's own spacing, with pressure easing to the value the pen lifted at.
     */
    private fun settleStabilizerAtLift(
        canvas: Canvas,
        target: Bitmap,
        screenX: Float,
        screenY: Float,
        pressure: Float,
        eventTime: Long,
        tilt: Float,
        azimuth: Float
    ) {
        val p = screenToCanvas(screenX, screenY, settleCanvasPoint)
        val remaining = hypot((p.x - smoothedX).toDouble(), (p.y - smoothedY).toDouble()).toFloat()
        // One step is enough when the point is already on the pen; beyond that, a step every few
        // pixels, bounded so a large correction can never turn one lift into unbounded work.
        val steps = ceil(remaining / 4f).toInt().coerceIn(1, 24)
        for (i in 1..steps) {
            // Equal increments that land exactly on the lift point at the final step.
            feedSample(
                canvas, target, screenX, screenY, pressure, eventTime, tilt, azimuth,
                forcedResponse = 1f / (steps - i + 1).toFloat()
            )
        }
    }

    /** Convert one raw touch sample to canvas space, stabilize it, and stamp along to it. */
    private fun feedSample(
        canvas: Canvas,
        target: Bitmap,
        screenX: Float,
        screenY: Float,
        pressure: Float,
        eventTime: Long,
        /** Radians from perpendicular; zero for input that does not measure it. */
        tilt: Float = 0f,
        /** Radians clockwise from up; meaningful only when the engine says a barrel was reported. */
        azimuth: Float = 0f,
        /**
         * Overrides the time-based response for this one sample. Used only while draining the
         * stabilizer at lift, where the point has to arrive at the pen on a schedule rather than
         * by its own time constant.
         */
        forcedResponse: Float? = null
    ) {
        val dt = (eventTime - lastEventTime).coerceAtLeast(0L)
        if (dt > 0L) {
            val rawDist = hypot((screenX - lastTouchX).toDouble(), (screenY - lastTouchY).toDouble()).toFloat()
            val rawVelocity = rawDist / dt
            smoothedVelocity = smoothedVelocity * 0.75f + rawVelocity.coerceIn(0f, 6f) * 0.25f
            drawingAudioEngine.updateMotion(smoothedVelocity * 1000f, smoothedPressure, rawDist)
        }
        lastTouchX = screenX
        lastTouchY = screenY
        lastEventTime = eventTime
        // Genuine pen samples only; the lift drain repeats the final sample and would skew it.
        if (forcedResponse == null) strokeInput.add(pressure, tilt, azimuth)

        val p = screenToCanvas(screenX, screenY, sampleCanvasPoint)
        // Stroke stabilization: the drawn point trails the raw touch point.
        val smoothing = brushEngine.properties.smoothing.coerceIn(0f, 0.95f)
        val response = forcedResponse ?: StrokeSmoothing.response(smoothing, dt)
        smoothedX += (p.x - smoothedX) * response
        smoothedY += (p.y - smoothedY) * response
        if (forcedResponse == null) {
            // Keep the stabilized point within a bounded distance of the pen. Left unbounded, a
            // fast stroke at high smoothing leaves a long unpainted trail that the lift then has to
            // cross in one straight segment.
            val trail = StrokeSmoothing.maxTrail(smoothing, brushEngine.properties.size)
            val tx = p.x - smoothedX
            val ty = p.y - smoothedY
            val lag = hypot(tx.toDouble(), ty.toDouble()).toFloat()
            if (lag > trail) {
                val pull = (lag - trail) / lag
                smoothedX += tx * pull
                smoothedY += ty * pull
            }
        }
        val pressureResponse = forcedResponse ?: 0.35f
        smoothedPressure += (pressure - smoothedPressure) * pressureResponse

        if (viewModel?.toolMode?.value == ToolMode.SMUDGE) {
            symmetryStroke.smudgeSegment(canvas, target, lastSmudgeX, lastSmudgeY, smoothedX, smoothedY, smoothedPressure)
            lastSmudgeX = smoothedX
            lastSmudgeY = smoothedY
        } else if (viewModel?.toolMode?.value == ToolMode.BLUR) {
            symmetryStroke.blurSegment(canvas, target, lastSmudgeX, lastSmudgeY, smoothedX, smoothedY, smoothedPressure)
            lastSmudgeX = smoothedX
            lastSmudgeY = smoothedY
        } else {
            val module = gridModuleSize()
            if (module != null) {
                // A square-Kufic pen does not stamp; it fills the squares it crosses. Stabilization
                // still runs above, so the hand is steadied before the position is quantised - the
                // artist aims at a cell rather than fighting to hold still inside one.
                paintGridSegment(canvas, smoothedX, smoothedY, module)
                return
            }
            // Every mirrored branch runs in its own engine with its own spline; see SymmetryStroke.
            symmetryStroke.strokeTo(
                canvas, target, smoothedX, smoothedY, smoothedPressure, smoothedVelocity,
                tilt, azimuth
            )
        }
    }

    /**
     * Run one batch of stamps against the right target and repaint only what it touched.
     *
     * Alpha Lock paints into a scratch buffer and folds the result onto the layer with SRC_ATOP,
     * so new paint can only land where the layer already had some. Smudge and Eraser skip that:
     * smudge reads the same pixels it smears (an empty scratch would give it nothing to sample)
     * and the eraser only ever removes existing alpha, so both are already implicitly constrained.
     */
    private fun paintBatch(block: (Canvas, Bitmap) -> Unit) {
        val vm = viewModel ?: return
        val index = vm.activeLayerIndex.value ?: return
        val layer = vm.layers.value?.getOrNull(index) ?: return
        if (layer.isLocked || !layer.isVisible) return
        val targetBitmap = if (layer.isEditingMask) layer.maskBitmap else layer.bitmap
        targetBitmap ?: return

        // Blur joins smudge in bypassing Alpha Lock, and for the same reason: it reads the very
        // pixels it rewrites, and an empty scratch buffer would give it nothing to sample - it
        // would blur transparency into the layer instead of softening what is there.
        val readsExistingPixels =
            vm.toolMode.value == ToolMode.SMUDGE || vm.toolMode.value == ToolMode.BLUR
        val useAlphaLock = !layer.isEditingMask && layer.isAlphaLocked && !readsExistingPixels &&
            brushEngine.properties.type != BrushType.Eraser

        /*
         * Uniform coverage: a stroke that does not darken where it crosses itself.
         *
         * This is the difference between a marker and an airbrush, and `buildUp` has been sitting
         * in BrushProperties describing it while nothing anywhere read the value - which is a large
         * part of why every preset in the library draws with the same character no matter what its
         * other settings say. A pen that doubles back over its own line and leaves a darker patch
         * is not a pen.
         *
         * The fix is the same two-buffer trick Alpha Lock already uses: let the stroke accumulate
         * at full strength in a scratch, keep an untouched copy of the layer, and fold the scratch
         * down once per batch at the brush's opacity. Overlaps saturate inside the scratch instead
         * of compounding on the artwork.
         */
        val uniformCoverage = !brushEngine.properties.buildUp && !readsExistingPixels &&
            brushEngine.properties.type != BrushType.Eraser
        val useScratch = useAlphaLock || uniformCoverage
        // The master opacity is applied at fold time for a uniform brush, so the stamps themselves
        // must go down at full strength; otherwise it would be applied twice.
        symmetryStroke.setDeferStrokeOpacity(uniformCoverage)

        val selectionPath = selectionTool.getSelectionPath()
        val hasSelection = !selectionPath.isEmpty

        symmetryStroke.resetDirty()

        if (useScratch) {
            // The whole stroke accumulates in the scratch and is folded back against an untouched
            // copy of the layer each batch. Compositing SRC_ATOP straight onto the live layer
            // would re-apply every earlier batch wherever a stroke crossed itself, darkening the
            // overlap; restoring the original first makes each fold idempotent - and both draws
            // are clipped to just what this batch painted, so the cost stays local.
            val original = getAlphaLockOriginal(targetBitmap)
            val scratch = getAlphaLockScratch(targetBitmap.width, targetBitmap.height)
            val scratchCanvas = Canvas(scratch)

            if (hasSelection) {
                scratchCanvas.save()
                scratchCanvas.clipPath(selectionPath)
            }
            block(scratchCanvas, scratch)
            if (hasSelection) scratchCanvas.restore()

            val painted = symmetryStroke.collectDirty(batchDirty)
            if (!painted.isEmpty) {
                val layerCanvas = Canvas(targetBitmap)
                layerCanvas.save()
                layerCanvas.clipRect(painted)
                layerCanvas.drawBitmap(original, 0f, 0f, srcPaint)
                // Alpha Lock still clips the fold to what the layer already covered; a uniform
                // brush on an unlocked layer simply lays the accumulated stroke down.
                val fold = if (useAlphaLock) srcAtopPaint else strokeFoldPaint
                fold.alpha = if (uniformCoverage) {
                    (brushEngine.properties.opacity * 255f).toInt().coerceIn(1, 255)
                } else {
                    255
                }
                layerCanvas.drawBitmap(scratch, 0f, 0f, fold)
                fold.alpha = 255
                layerCanvas.restore()
            }
        } else {
            val canvas = Canvas(targetBitmap)
            if (hasSelection) {
                canvas.save()
                canvas.clipPath(selectionPath)
            }
            block(canvas, targetBitmap)
            if (hasSelection) canvas.restore()
        }

        val painted = symmetryStroke.collectDirty(batchDirty)
        if (!painted.isEmpty) {
            strokeDirty.union(painted)
            // Oversized layers are displayed through a viewport tile cache. Retain every
            // unchanged tile and refresh only those the current batch actually touched.
            LayerCompositor.invalidateBitmapRegion(targetBitmap, painted)
            invalidateCanvasRect(painted)
        }
    }

    /** Commit the stroke to history and, on an open canvas, grow the paper if it reached an edge. */
    private fun finishStroke() {
        val vm = viewModel ?: return
        val index = vm.activeLayerIndex.value ?: return
        if (strokeDirty.isEmpty) return

        vm.commitPixelEdit(index, strokeDirty)
        val strokeId = collaborationStrokeId
        if (strokeId != null && onLocalCollaborationPatch != null) {
            vm.snapshotLocalCollaborationPatch(strokeDirty)?.let { (left, top, patch) ->
                onLocalCollaborationPatch?.invoke(LocalStrokePatch(strokeId, left, top, patch))
            }
        }
        // Belt-and-suspenders: expandForUpcomingSamples already grows the paper ahead of each
        // move batch, so this mostly only fires for a stroke's very last point on the rare path
        // that doesn't route through it.
        applyExpansionOffset(vm.expandIfNeeded(strokeDirty))
        strokeDirty.setEmpty()
        invalidate()
    }

    /**
     * Grow the paper *before* painting a batch of samples, not after, on an open canvas. Painting
     * writes straight onto the layer's fixed-size bitmap each move sample; if growth only ever
     * happened once the stroke finished, a single continuous stroke that crossed off the old
     * edge would have everything past that edge silently clipped away by the bitmap's own bounds
     * - already lost by the time there was a finished stroke to grow the paper for. Scanning this
     * batch's samples (including the historical ones the digitizer queued up) and expanding for
     * their combined reach up front means paintBatch always starts against paper that's already
     * the right size, with nothing painted, then discarded, in between.
     */
    private fun expandForUpcomingSamples(event: MotionEvent) {
        val vm = viewModel ?: return
        if (!vm.isOpenCanvas) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun consider(screenX: Float, screenY: Float) {
            val p = screenToCanvas(screenX, screenY, expansionCanvasPoint)
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        for (h in 0 until event.historySize) consider(event.getHistoricalX(h), event.getHistoricalY(h))
        consider(event.x, event.y)

        val margin = brushEngine.properties.size / 2f + 8f
        applyExpansionOffset(vm.expandIfNeeded(RectF(minX - margin, minY - margin, maxX + margin, maxY + margin)))
    }

    /** Existing content just moved by [offset] in canvas space (the paper grew); shifting the
     * view by the same amount leaves the artwork exactly where the user last saw it. */
    private fun applyExpansionOffset(offset: PointF?) {
        offset ?: return
        canvasMatrix.preTranslate(-offset.x, -offset.y)
        symmetryStroke.offset(offset.x, offset.y)
        smoothedX += offset.x; smoothedY += offset.y
        downCanvasX += offset.x; downCanvasY += offset.y
        lastSmudgeX += offset.x; lastSmudgeY += offset.y
        lastGridX += offset.x; lastGridY += offset.y
        // Cell keys are canvas positions, so growth invalidates every one of them. Growth is
        // quantised to whole grid cells, so the artwork stays in phase; only this bookkeeping has
        // to start again, at worst re-filling the one cell the pen is standing in.
        gridCellsFilledThisStroke.clear()
        if (!strokeDirty.isEmpty) strokeDirty.offset(offset.x, offset.y)
        currentStrokePoints.forEach { point -> point.offset(offset.x, offset.y) }
        // A detected shape contains copies of pre-growth coordinates. It will be detected again
        // from the rebased point list if the artist continues holding; keeping the stale copy would
        // draw it displaced from the stroke it replaces.
        activeQuickShape = null
        if (!selectionTool.getSelectionPath().isEmpty) {
            selectionTool.getSelectionPath().offset(offset.x, offset.y)
        }
        shiftUrbanState(offset.x, offset.y)
        // Preserve Alpha Lock's stroke-start mask and accumulated paint across the same growth.
        // Recreating these from the live layer would let pixels painted earlier in this stroke
        // become a new mask, making Alpha Lock leak into previously transparent space.
        val grownLayer = viewModel?.layers?.value?.firstOrNull()?.bitmap
        if (grownLayer != null) {
            alphaLockScratch = rebaseStrokeBuffer(
                alphaLockScratch, grownLayer.width, grownLayer.height, offset.x, offset.y
            )
            alphaLockOriginal = rebaseStrokeBuffer(
                alphaLockOriginal, grownLayer.width, grownLayer.height, offset.x, offset.y
            )
        }
        gestureListener?.onHint(context.getString(R.string.gesture_canvas_expanded))
    }

    private fun rebaseStrokeBuffer(
        old: Bitmap?, width: Int, height: Int, dx: Float, dy: Float
    ): Bitmap? {
        old ?: return null
        if (old.width == width && old.height == height) return old
        return try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { grown ->
                Canvas(grown).drawBitmap(old, dx, dy, null)
                if (!old.isRecycled) old.recycle()
            }
        } catch (_: OutOfMemoryError) {
            if (!old.isRecycled) old.recycle()
            null
        }
    }

    /** Moves an open sheet under a pen held near the viewport rim. */
    private fun autoPanOpenCanvasAtEdge(event: MotionEvent) {
        edgePenX = event.x
        edgePenY = event.y
        edgePenPressure = event.pressure.coerceIn(0.05f, 1f)
        val previous = lastEdgePanTime.takeIf { it > 0L } ?: event.eventTime
        val elapsed = (event.eventTime - previous).coerceAtLeast(1L)
        lastEdgePanTime = event.eventTime
        val moved = applyEdgePan(edgePenX, edgePenY, elapsed)
        if (moved) scheduleEdgePan() else stopEdgePan(resetClock = false)
    }

    private fun applyEdgePan(screenX: Float, screenY: Float, elapsed: Long): Boolean {
        val zone = 52f * density
        val speed = 520f * density
        val dx = OpenCanvasEdgePan.translationDelta(screenX, width.toFloat(), zone, speed, elapsed)
        val dy = OpenCanvasEdgePan.translationDelta(screenY, height.toFloat(), zone, speed, elapsed)
        if (dx != 0f || dy != 0f) {
            canvasMatrix.postTranslate(dx, dy)
            postInvalidateOnAnimation()
            return true
        }
        return false
    }

    /**
     * MotionEvent updates stop once a stylus is physically against the glass edge. Continue on the
     * display clock so the sheet and the ink both keep travelling until the pen lifts.
     */
    private val edgePanRunnable = object : Runnable {
        override fun run() {
            edgePanScheduled = false
            val vm = viewModel
            if (!isDrawing || isUrbanMode || eyedropperActive || vm?.isOpenCanvas != true) return
            val now = android.os.SystemClock.uptimeMillis()
            val previous = lastEdgePanTime.takeIf { it > 0L } ?: now
            val elapsed = (now - previous).coerceAtLeast(1L)
            lastEdgePanTime = now
            if (!applyEdgePan(edgePenX, edgePenY, elapsed)) return

            expandForScreenPoint(edgePenX, edgePenY)
            pendingDot = false
            removeCallbacks(quickShapeRunnable)
            activeQuickShape = null
            paintBatch { canvas, target ->
                feedSample(canvas, target, edgePenX, edgePenY, edgePenPressure, now)
            }
            val point = screenToCanvas(edgePenX, edgePenY, expansionCanvasPoint)
            addStrokePoint(point.x, point.y)
            scheduleEdgePan()
        }
    }

    private fun scheduleEdgePan() {
        if (edgePanScheduled) return
        edgePanScheduled = true
        postOnAnimation(edgePanRunnable)
    }

    private fun stopEdgePan(resetClock: Boolean = true) {
        if (edgePanScheduled) removeCallbacks(edgePanRunnable)
        edgePanScheduled = false
        if (resetClock) lastEdgePanTime = 0L
    }

    private fun expandForScreenPoint(screenX: Float, screenY: Float) {
        val vm = viewModel ?: return
        if (!vm.isOpenCanvas) return
        val p = screenToCanvas(screenX, screenY, expansionCanvasPoint)
        val margin = brushEngine.properties.size / 2f + 12f
        applyExpansionOffset(vm.expandIfNeeded(RectF(
            p.x - margin, p.y - margin, p.x + margin, p.y + margin
        )))
    }

    /** Every Urban element's point/vertex/marker coordinates live in canvas space too, same as
     * a brush stroke - when growth shifts the whole sheet (growing on the left or top edge),
     * every already-placed node has to move with it, or the site plan would visibly jump. */
    private fun shiftUrbanState(dx: Float, dy: Float) {
        for (element in urbanElements) {
            when (element) {
                is UrbanElement.ArrowPath -> {
                    element.points.forEach { it.x += dx; it.y += dy }
                    element.stations.forEach { it.point.x += dx; it.point.y += dy }
                }
                is UrbanElement.HatchPolygon -> element.vertices.forEach { it.x += dx; it.y += dy }
                is UrbanElement.BoundaryPath -> element.vertices.forEach { it.x += dx; it.y += dy }
                is UrbanElement.PointMarker -> { element.position.x += dx; element.position.y += dy }
            }
        }
        for (index in urbanMeasurements.indices) {
            val measurement = urbanMeasurements[index]
            urbanMeasurements[index] = measurement.copy(
                points = measurement.points.map { point ->
                    MeasurementPoint(point.x + dx, point.y + dy)
                }
            )
        }
        val ppm = urbanScaleConfig.pixelsPerMeter
        require(ppm.isFinite() && ppm > 0f) { "Invalid project scale" }
        for (index in architecturalAssets.indices) {
            architecturalAssets[index] = architecturalAssets[index].translateBy(
                dx / ppm.toDouble(),
                dy / ppm.toDouble()
            )
        }
        currentUrbanPoints.forEach { it.x += dx; it.y += dy }
        calibStart?.let { it.x += dx; it.y += dy }
        calibEnd?.let { it.x += dx; it.y += dy }
        calibVertexPick?.let { it.x += dx; it.y += dy }
        urbanDownPoint.x += dx; urbanDownPoint.y += dy
        urbanDragLastPoint.x += dx; urbanDragLastPoint.y += dy
        urbanSelectionDownPoint.x += dx; urbanSelectionDownPoint.y += dy
    }

    /** Same proactive-growth idea as [expandForUpcomingSamples], but for a single canvas-space
     * point (an urban tool tap or drag sample) instead of a batch of brush stroke samples -
     * returns the point adjusted for any growth that just happened, since the growth may have
     * shifted the very point the caller is about to use. */
    private fun expandForUrbanPoint(x: Float, y: Float): PointF {
        val vm = viewModel ?: return PointF(x, y)
        if (!vm.isOpenCanvas) return PointF(x, y)
        val margin = 60f * density
        val offset = vm.expandIfNeeded(RectF(x - margin, y - margin, x + margin, y + margin))
        applyExpansionOffset(offset)
        return if (offset != null) PointF(x + offset.x, y + offset.y) else PointF(x, y)
    }

    private fun cancelStroke() {
        isDrawing = false
        stopEdgePan()
        pendingDot = false
        symmetryStroke.end(null, null)
        val index = viewModel?.activeLayerIndex?.value
        if (!strokeDirty.isEmpty && index != null) {
            viewModel?.commitPixelEdit(index, strokeDirty)
        }
        strokeDirty.setEmpty()
    }

    /** Repaint only the screen area a canvas-space rectangle maps onto. */
    private fun invalidateCanvasRect(rect: RectF) {
        val mapped = RectF(rect)
        canvasMatrix.mapRect(mapped)
        val pad = 3f
        invalidate(
            (mapped.left - pad).toInt(), (mapped.top - pad).toInt(),
            (mapped.right + pad).toInt() + 1, (mapped.bottom + pad).toInt() + 1
        )
    }

    private fun getAlphaLockScratch(w: Int, h: Int): Bitmap {
        val existing = alphaLockScratch
        if (existing != null && existing.width == w && existing.height == h) return existing
        val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        alphaLockScratch = fresh
        return fresh
    }

    /** The alpha-locked layer as it stood before this stroke started, taken once per stroke. */
    private fun getAlphaLockOriginal(source: Bitmap): Bitmap {
        val existing = alphaLockOriginal
        if (existing != null && existing.width == source.width && existing.height == source.height) return existing
        val fresh = source.copy(Bitmap.Config.ARGB_8888, false)
        alphaLockOriginal = fresh
        return fresh
    }

    /** Reset the Alpha Lock buffers so the next stroke starts from the layer's current state. */
    private fun resetAlphaLockBuffers() {
        alphaLockOriginal = null
        alphaLockScratch?.eraseColor(Color.TRANSPARENT)
    }

    // ==================== Color Drop (flood fill) ====================

    /**
     * Bucket settings - tolerance, gap closing, edge expansion.
     *
     * Held on the view rather than read from preferences per drop so a change made in the settings
     * sheet applies to the very next drop, and so a drop costs no disk read.
     */
    var fillOptions: com.procreate.android.tools.SmartFill.Options =
        com.procreate.android.tools.FillPrefs.load(context)

    /** Drops (flood fills) [color] onto the active layer at the given screen coordinates -
     * the drop target for dragging the color indicator onto the canvas ("Color Drop"). */
    fun performColorDrop(screenX: Float, screenY: Float, color: Int) {
        val vm = viewModel ?: return
        val activeIndex = vm.activeLayerIndex.value ?: return
        val layer = vm.layers.value?.getOrNull(activeIndex) ?: return
        if (layer.isLocked || !layer.isVisible) return

        val point = screenToCanvas(screenX, screenY)
        val px = point.x.toInt()
        val py = point.y.toInt()
        if (px !in 0 until layer.bitmap.width || py !in 0 until layer.bitmap.height) return

        var clipRegion: android.graphics.Region? = null
        val selectionPath = selectionTool.getSelectionPath()
        if (!selectionPath.isEmpty) {
            clipRegion = android.graphics.Region()
            clipRegion.setPath(selectionPath, android.graphics.Region(0, 0, layer.bitmap.width, layer.bitmap.height))
        }

        val before = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val changed: Boolean
        if (layer.isAlphaLocked) {
            val working = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            changed = FillTool.floodFill(working, px, py, color, fillOptions, clipRegion)
            if (changed) Canvas(layer.bitmap).drawBitmap(working, 0f, 0f, srcAtopPaint)
            working.recycle()
        } else {
            changed = FillTool.floodFill(layer.bitmap, px, py, color, fillOptions, clipRegion)
        }

        // A drop that painted nothing must not push an undo step - pressing undo afterwards would
        // then appear to do nothing, and the user would press it again and lose real work.
        if (!changed) {
            before.recycle()
            return
        }
        vm.commitLayerEdit(activeIndex, before)
        invalidate()
    }

    // ==================== Selection tool ====================

    private fun handleSelectionTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> selectionTool.startSelection(x, y)
            MotionEvent.ACTION_MOVE -> selectionTool.updateSelection(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> selectionTool.endSelection()
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    fun setSelectionMode(mode: SelectionTool.SelectionMode) {
        selectionTool.currentMode = mode
    }

    fun hasSelection(): Boolean = !selectionTool.getSelectionPath().isEmpty

    fun clearSelection() {
        selectionTool.getSelectionPath().reset()
        invalidate()
    }

    fun invertSelection() {
        // Invert against the canvas, not the view: a selection lives in canvas space, and on a
        // canvas bigger than the screen the two are not the same rectangle.
        val (cw, ch) = canvasSize() ?: return
        selectionTool.invertSelection(cw.toFloat(), ch.toFloat())
        invalidate()
    }

    // ==================== Transform tool ====================

    private var transformLastX = 0f
    private var transformLastY = 0f

    private fun handleTransformTouch(event: MotionEvent, x: Float, y: Float) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (transformOriginalBitmap == null) beginTransform()
                val geometry = transformGeometry() ?: return
                val hitRadius = 24f * density / currentScale().coerceAtLeast(0.01f)
                if (distanceTo(x, y, geometry.flipH.x, geometry.flipH.y) <= hitRadius) {
                    transformDragMode = TransformDragMode.NONE
                    flipTransformHorizontal()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    return
                }
                if (distanceTo(x, y, geometry.flipV.x, geometry.flipV.y) <= hitRadius) {
                    transformDragMode = TransformDragMode.NONE
                    flipTransformVertical()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    return
                }
                transformDragMode = when {
                    distanceTo(x, y, geometry.rotate.x, geometry.rotate.y) <= hitRadius ->
                        TransformDragMode.ROTATE
                    geometry.corners.anyPointWithin(x, y, hitRadius) ->
                        TransformDragMode.SCALE_UNIFORM
                    geometry.horizontalMids.anyPointWithin(x, y, hitRadius) ->
                        TransformDragMode.SCALE_X
                    geometry.verticalMids.anyPointWithin(x, y, hitRadius) ->
                        TransformDragMode.SCALE_Y
                    pointInsideQuad(x, y, geometry.corners) -> TransformDragMode.MOVE
                    else -> TransformDragMode.NONE
                }
                transformStartMatrix.set(transformTool.getMatrix())
                transformGestureCenter.set(geometry.center.x, geometry.center.y)
                transformLastX = x
                transformLastY = y
                transformStartX = x
                transformStartY = y
                transformStartDistance = distanceTo(x, y, geometry.center.x, geometry.center.y)
                    .coerceAtLeast(1f)
                transformStartAngle = angleFrom(geometry.center, x, y)
                transformAxisAngle = angleFrom(
                    geometry.center,
                    geometry.center.x + geometry.corners[2] - geometry.corners[0],
                    geometry.center.y + geometry.corners[3] - geometry.corners[1]
                )
                val axisRadians = Math.toRadians(transformAxisAngle.toDouble())
                val axisX = kotlin.math.cos(axisRadians).toFloat()
                val axisY = kotlin.math.sin(axisRadians).toFloat()
                val dx = x - geometry.center.x
                val dy = y - geometry.center.y
                transformStartAxisProjection = when (transformDragMode) {
                    TransformDragMode.SCALE_X -> abs(dx * axisX + dy * axisY)
                    TransformDragMode.SCALE_Y -> abs(dx * -axisY + dy * axisX)
                    else -> 1f
                }.coerceAtLeast(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                when (transformDragMode) {
                    TransformDragMode.MOVE -> {
                        transformTool.move(x - transformLastX, y - transformLastY)
                        transformLastX = x
                        transformLastY = y
                    }
                    TransformDragMode.SCALE_UNIFORM -> {
                        val factor = (distanceTo(x, y, transformGestureCenter.x, transformGestureCenter.y) /
                            transformStartDistance).coerceIn(0.04f, 25f)
                        transformTool.getMatrix().set(transformStartMatrix)
                        transformTool.scale(
                            factor, transformGestureCenter.x, transformGestureCenter.y
                        )
                    }
                    TransformDragMode.SCALE_X -> {
                        val radians = Math.toRadians(transformAxisAngle.toDouble())
                        val axisX = kotlin.math.cos(radians).toFloat()
                        val axisY = kotlin.math.sin(radians).toFloat()
                        val projection = abs(
                            (x - transformGestureCenter.x) * axisX +
                                (y - transformGestureCenter.y) * axisY
                        )
                        val factor = (projection / transformStartAxisProjection).coerceIn(0.04f, 25f)
                        transformTool.getMatrix().set(transformStartMatrix)
                        transformTool.scaleAlongAxes(
                            factor, 1f, transformGestureCenter.x, transformGestureCenter.y,
                            transformAxisAngle
                        )
                    }
                    TransformDragMode.SCALE_Y -> {
                        val radians = Math.toRadians(transformAxisAngle.toDouble())
                        val axisX = kotlin.math.cos(radians).toFloat()
                        val axisY = kotlin.math.sin(radians).toFloat()
                        val projection = abs(
                            (x - transformGestureCenter.x) * -axisY +
                                (y - transformGestureCenter.y) * axisX
                        )
                        val factor = (projection / transformStartAxisProjection).coerceIn(0.04f, 25f)
                        transformTool.getMatrix().set(transformStartMatrix)
                        transformTool.scaleAlongAxes(
                            1f, factor, transformGestureCenter.x, transformGestureCenter.y,
                            transformAxisAngle
                        )
                    }
                    TransformDragMode.ROTATE -> {
                        var delta = angleFrom(transformGestureCenter, x, y) - transformStartAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        transformTool.getMatrix().set(transformStartMatrix)
                        transformTool.rotate(
                            delta, transformGestureCenter.x, transformGestureCenter.y
                        )
                    }
                    TransformDragMode.NONE -> Unit
                }
                if (transformDragMode != TransformDragMode.NONE) invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> transformDragMode = TransformDragMode.NONE
        }
    }

    /** Snapshot the active layer and start a fresh transform on it. */
    fun beginTransform(): Boolean {
        val layers = viewModel?.layers?.value ?: return false
        val activeIndex = viewModel?.activeLayerIndex?.value ?: return false
        if (activeIndex !in layers.indices) return false
        val layer = layers[activeIndex]
        if (layer.isBackground || layer.isLocked || !layer.isVisible || layer.isEditingMask) return false
        val bounds = BitmapAlphaBounds.find(layer.bitmap) ?: return false
        val content = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        Canvas(content).drawBitmap(
            layer.bitmap,
            bounds,
            Rect(0, 0, bounds.width(), bounds.height()),
            srcPaint
        )
        transformOriginalBitmap?.let { if (!it.isRecycled) it.recycle() }
        transformLayerIndex = activeIndex
        transformOriginalBitmap = content
        transformSourceBounds.set(bounds)
        transformTool.reset()
        transformTool.move(bounds.left.toFloat(), bounds.top.toFloat())
        transformDragMode = TransformDragMode.NONE
        invalidate()
        return true
    }

    private fun transformPivot(): Pair<Float, Float> {
        val geometry = transformGeometry()
        return if (geometry != null) Pair(geometry.center.x, geometry.center.y)
        else Pair(width / 2f, height / 2f)
    }

    fun rotateTransform90(clockwise: Boolean) {
        val (px, py) = transformPivot()
        transformTool.rotate(if (clockwise) 90f else -90f, px, py)
        invalidate()
    }

    fun flipTransformHorizontal() {
        val geometry = transformGeometry() ?: return
        val angle = angleFrom(
            geometry.center,
            geometry.center.x + geometry.corners[2] - geometry.corners[0],
            geometry.center.y + geometry.corners[3] - geometry.corners[1]
        )
        transformTool.scaleAlongAxes(-1f, 1f, geometry.center.x, geometry.center.y, angle)
        invalidate()
    }

    fun flipTransformVertical() {
        val geometry = transformGeometry() ?: return
        val angle = angleFrom(
            geometry.center,
            geometry.center.x + geometry.corners[2] - geometry.corners[0],
            geometry.center.y + geometry.corners[3] - geometry.corners[1]
        )
        transformTool.scaleAlongAxes(1f, -1f, geometry.center.x, geometry.center.y, angle)
        invalidate()
    }

    /** Bake the pending transform into the active layer's bitmap and commit it to undo history. */
    fun applyTransform() {
        val original = transformOriginalBitmap
        val layers = viewModel?.layers?.value
        if (original != null && layers != null && transformLayerIndex in layers.indices) {
            val layer = layers[transformLayerIndex]
            val newBounds = transformedContentBounds()
            val dirty = RectF(transformSourceBounds).apply {
                union(newBounds)
                inset(-4f, -4f)
            }
            viewModel?.prepareEdit(transformLayerIndex)
            viewModel?.captureBeforeEdit(transformLayerIndex, dirty)
            val destination = Canvas(layer.bitmap)
            destination.drawRect(transformSourceBounds, transformClearPaint)
            destination.drawBitmap(original, transformTool.getMatrix(), bakePaint)
            LayerCompositor.invalidateBitmapRegion(layer.bitmap, dirty)
            viewModel?.commitPixelEdit(transformLayerIndex, dirty)
        }
        original?.let { if (!it.isRecycled) it.recycle() }
        transformOriginalBitmap = null
        transformLayerIndex = -1
        transformTool.reset()
        transformSourceBounds.setEmpty()
        transformDragMode = TransformDragMode.NONE
        invalidate()
    }

    /** Discard the pending transform, restoring the layer to how it was before. */
    fun cancelTransform() {
        transformOriginalBitmap?.let { if (!it.isRecycled) it.recycle() }
        transformOriginalBitmap = null
        transformLayerIndex = -1
        transformTool.reset()
        transformSourceBounds.setEmpty()
        transformDragMode = TransformDragMode.NONE
        invalidate()
    }

    private data class TransformGeometry(
        val corners: FloatArray,
        val horizontalMids: FloatArray,
        val verticalMids: FloatArray,
        val center: PointF,
        val rotate: PointF,
        val flipH: PointF,
        val flipV: PointF
    )

    private fun transformGeometry(): TransformGeometry? {
        val bitmap = transformOriginalBitmap ?: return null
        val corners = floatArrayOf(
            0f, 0f,
            bitmap.width.toFloat(), 0f,
            bitmap.width.toFloat(), bitmap.height.toFloat(),
            0f, bitmap.height.toFloat()
        )
        transformTool.getMatrix().mapPoints(corners)
        val center = PointF(
            (corners[0] + corners[2] + corners[4] + corners[6]) / 4f,
            (corners[1] + corners[3] + corners[5] + corners[7]) / 4f
        )
        val topMid = PointF((corners[0] + corners[2]) / 2f, (corners[1] + corners[3]) / 2f)
        val bottomMid = PointF((corners[6] + corners[4]) / 2f, (corners[7] + corners[5]) / 2f)
        val edgeX = corners[2] - corners[0]
        val edgeY = corners[3] - corners[1]
        val edgeLength = hypot(edgeX.toDouble(), edgeY.toDouble()).toFloat().coerceAtLeast(1f)
        val tangentX = edgeX / edgeLength
        val tangentY = edgeY / edgeLength
        val normalX = edgeY / edgeLength
        val normalY = -edgeX / edgeLength
        val unit = density / currentScale().coerceAtLeast(0.01f)
        val rotate = PointF(topMid.x + normalX * 48f * unit, topMid.y + normalY * 48f * unit)
        val actionBaseX = bottomMid.x - normalX * 48f * unit
        val actionBaseY = bottomMid.y - normalY * 48f * unit
        val flipH = PointF(actionBaseX - tangentX * 24f * unit, actionBaseY - tangentY * 24f * unit)
        val flipV = PointF(actionBaseX + tangentX * 24f * unit, actionBaseY + tangentY * 24f * unit)
        return TransformGeometry(
            corners = corners,
            horizontalMids = floatArrayOf(
                (corners[0] + corners[6]) / 2f, (corners[1] + corners[7]) / 2f,
                (corners[2] + corners[4]) / 2f, (corners[3] + corners[5]) / 2f
            ),
            verticalMids = floatArrayOf(topMid.x, topMid.y, bottomMid.x, bottomMid.y),
            center = center,
            rotate = rotate,
            flipH = flipH,
            flipV = flipV
        )
    }

    private fun transformedContentBounds(): RectF {
        val bitmap = transformOriginalBitmap ?: return RectF()
        return RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).also {
            transformTool.getMatrix().mapRect(it)
        }
    }

    private fun drawTransformOverlay(canvas: Canvas) {
        val geometry = transformGeometry() ?: return
        val scale = currentScale().coerceAtLeast(0.01f)
        val unit = density / scale
        transformOutlineContrastPaint.strokeWidth = 5f * unit
        transformOutlinePaint.strokeWidth = 1.7f * unit
        transformOutlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f * unit, 6f * unit), 0f)
        transformHandleStrokePaint.strokeWidth = 2f * unit
        val handleRadius = 9f * unit
        val actionRadius = 13f * unit

        val path = Path().apply {
            moveTo(geometry.corners[0], geometry.corners[1])
            lineTo(geometry.corners[2], geometry.corners[3])
            lineTo(geometry.corners[4], geometry.corners[5])
            lineTo(geometry.corners[6], geometry.corners[7])
            close()
        }
        // A dark continuous rail plus a blue dashed rail remains readable on white paper, dark
        // artwork, and mixed photographs. A single light outline disappeared on white canvases.
        canvas.drawPath(path, transformOutlineContrastPaint)
        canvas.drawPath(path, transformOutlinePaint)
        val topMidX = geometry.verticalMids[0]
        val topMidY = geometry.verticalMids[1]
        canvas.drawLine(topMidX, topMidY, geometry.rotate.x, geometry.rotate.y, transformOutlineContrastPaint)
        canvas.drawLine(topMidX, topMidY, geometry.rotate.x, geometry.rotate.y, transformOutlinePaint)

        fun drawHandles(points: FloatArray) {
            var i = 0
            while (i < points.size) {
                canvas.drawCircle(points[i], points[i + 1], handleRadius + 2.5f * unit, transformHandleContrastPaint)
                canvas.drawCircle(points[i], points[i + 1], handleRadius, transformHandleFillPaint)
                canvas.drawCircle(points[i], points[i + 1], handleRadius, transformHandleStrokePaint)
                i += 2
            }
        }
        drawHandles(geometry.corners)
        drawHandles(geometry.horizontalMids)
        drawHandles(geometry.verticalMids)
        canvas.drawCircle(
            geometry.rotate.x,
            geometry.rotate.y,
            handleRadius * 1.12f + 2.5f * unit,
            transformHandleContrastPaint
        )
        canvas.drawCircle(geometry.rotate.x, geometry.rotate.y, handleRadius * 1.12f, transformRotatePaint)

        transformActionTextPaint.textSize = 14f * unit
        listOf(geometry.flipH to "\u2194", geometry.flipV to "\u2195").forEach { (point, label) ->
            canvas.drawCircle(point.x, point.y, actionRadius, transformActionPaint)
            val fm = transformActionTextPaint.fontMetrics
            canvas.drawText(label, point.x, point.y - (fm.ascent + fm.descent) / 2f, transformActionTextPaint)
        }
    }

    private fun drawSelectionOverlay(canvas: Canvas, path: Path) {
        val unit = density / currentScale().coerceAtLeast(0.01f)
        selectionContrastPaint.strokeWidth = 4.5f * unit
        selectionPaint.strokeWidth = 2f * unit
        selectionPaint.pathEffect = DashPathEffect(floatArrayOf(10f * unit, 7f * unit), 0f)
        canvas.drawPath(path, selectionContrastPaint)
        canvas.drawPath(path, selectionPaint)
    }

    private fun distanceTo(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    private fun angleFrom(center: PointF, x: Float, y: Float): Float =
        Math.toDegrees(kotlin.math.atan2((y - center.y).toDouble(), (x - center.x).toDouble())).toFloat()

    private fun FloatArray.anyPointWithin(x: Float, y: Float, radius: Float): Boolean {
        var i = 0
        while (i < size) {
            if (distanceTo(x, y, this[i], this[i + 1]) <= radius) return true
            i += 2
        }
        return false
    }

    private fun pointInsideQuad(x: Float, y: Float, corners: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val next = (i + 1) % 4
            val ax = corners[i * 2]
            val ay = corners[i * 2 + 1]
            val bx = corners[next * 2]
            val by = corners[next * 2 + 1]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            if (abs(cross) < 0.001f) continue
            val current = if (cross > 0f) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    // ==================== Adjustments (brightness/contrast/saturation) ====================

    /** Live-previews a color matrix on the active layer without modifying its bitmap yet. */
    fun setAdjustmentPreview(colorMatrix: android.graphics.ColorMatrix?) {
        adjustmentPreviewLayerIndex = viewModel?.activeLayerIndex?.value ?: -1
        adjustmentPreviewFilter = colorMatrix?.let { android.graphics.ColorMatrixColorFilter(it) }
        invalidate()
    }

    /** Bakes the currently-previewed adjustment into the active layer's bitmap. */
    fun applyAdjustment() {
        val filter = adjustmentPreviewFilter
        val layers = viewModel?.layers?.value
        if (filter != null && layers != null && adjustmentPreviewLayerIndex in layers.indices) {
            val layer = layers[adjustmentPreviewLayerIndex]
            val original = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val canvas = Canvas(layer.bitmap)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(original, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
            viewModel?.commitLayerEdit(adjustmentPreviewLayerIndex, original)
        }
        adjustmentPreviewFilter = null
        adjustmentPreviewLayerIndex = -1
        invalidate()
    }

    fun cancelAdjustmentPreview() {
        adjustmentPreviewFilter = null
        adjustmentPreviewLayerIndex = -1
        invalidate()
    }

    // ==================== Rendering ====================

    private fun renderMeasurementAnnotation(
        canvas: Canvas,
        measurement: MeasurementElement,
        isPreview: Boolean = false
    ) {
        val points = measurement.points
        if (points.size < measurement.kind.minimumPointCount) return
        val inverseZoom = 1f / currentScale().coerceAtLeast(0.05f)
        val color = Color.parseColor("#00BCD4")
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.5f * density * inverseZoom
            this.color = color
            if (isPreview) pathEffect = DashPathEffect(
                floatArrayOf(8f * density * inverseZoom, 5f * density * inverseZoom),
                0f
            )
        }
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            if (measurement.kind == MeasurementKind.POLYGON_AREA) close()
        }
        if (measurement.kind == MeasurementKind.POLYGON_AREA) {
            canvas.drawPath(path, Paint(linePaint).apply {
                style = Paint.Style.FILL
                this.color = Color.argb(if (isPreview) 22 else 42, 0, 188, 212)
                pathEffect = null
            })
        }
        canvas.drawPath(path, linePaint)

        val tickHalf = 6f * density * inverseZoom
        listOf(points.first(), points.last()).forEachIndexed { index, point ->
            val neighbour = if (index == 0) points[1] else points[points.lastIndex - 1]
            val dx = neighbour.x - point.x
            val dy = neighbour.y - point.y
            val length = hypot(dx, dy)
            if (length > 0.0001) {
                val nx = (-dy / length * tickHalf).toFloat()
                val ny = (dx / length * tickHalf).toFloat()
                canvas.drawLine(
                    point.x.toFloat() - nx,
                    point.y.toFloat() - ny,
                    point.x.toFloat() + nx,
                    point.y.toFloat() + ny,
                    linePaint
                )
            }
        }

        val result = runCatching {
            MeasurementCalculator.calculate(measurement, urbanScaleConfig)
        }.getOrNull() ?: return
        val valueLabel = when (result) {
            is MeasurementResult.Length -> measurementFormatter.format(result, measurement.displayUnit)
            is MeasurementResult.Area -> {
                val area = measurementFormatter.format(result, measurement.displayUnit)
                val perimeter = measurementFormatter.formatPerimeter(result, measurement.displayUnit)
                "$area  •  محيط $perimeter"
            }
        }
        val label = measurement.label?.takeIf { it.isNotBlank() }?.let { "$it · $valueLabel" }
            ?: valueLabel
        val anchorX = points.map { it.x }.average().toFloat()
        val anchorY = points.map { it.y }.average().toFloat()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f * density * inverseZoom
        }
        val horizontalPad = 9f * density * inverseZoom
        val verticalPad = 6f * density * inverseZoom
        val metrics = textPaint.fontMetrics
        val labelWidth = textPaint.measureText(label) + horizontalPad * 2f
        val labelHeight = metrics.descent - metrics.ascent + verticalPad * 2f
        val rect = RectF(
            anchorX - labelWidth / 2f,
            anchorY - labelHeight / 2f,
            anchorX + labelWidth / 2f,
            anchorY + labelHeight / 2f
        )
        canvas.drawRoundRect(
            rect,
            7f * density * inverseZoom,
            7f * density * inverseZoom,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = Color.argb(if (isPreview) 190 else 230, 28, 28, 31)
            }
        )
        canvas.drawText(label, anchorX, anchorY - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(canvasMatrix)

        val vm = viewModel
        if (vm?.isOpenCanvas == true) {
            CanvasBackground.drawOpenPaper(
                canvas,
                vm.backgroundStyle.value ?: CanvasBackgroundStyle.BLANK,
                visibleCanvasBounds(),
                vm.backgroundPatternPitch(),
                vm.backgroundColor.value ?: Color.WHITE
            )
        }

        // Only hide the real layer / show the floating ghost while Transform is the active tool -
        // if the tool mode changed away without applying (e.g. via another panel), fall back to
        // rendering the real layer so no content is ever left invisible.
        val transformActive = viewModel?.toolMode?.value == ToolMode.TRANSFORM && transformOriginalBitmap != null

        val layers = viewModel?.layers?.value ?: emptyList()
        val skipIndex = if (transformActive) transformLayerIndex else -1
        LayerCompositor.draw(canvas, layers, skipIndex, currentSampling(), previewFilterForLayer)

        // The exact PNG patch arrives just after pen-up. Until then, render the partner's live
        // path above the layers so both artists can see each other while their pens are moving.
        remoteStrokePreviews.values.forEach { preview ->
            canvas.drawPath(preview.path, preview.paint)
            canvas.drawCircle(
                preview.lastX,
                preview.lastY,
                (preview.paint.strokeWidth * 0.55f).coerceAtLeast(4f),
                Paint(preview.paint).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
            )
        }

        if (vm?.isOpenCanvas != true) {
            layers.firstOrNull()?.bitmap?.let { first ->
                canvas.drawRect(0f, 0f, first.width.toFloat(), first.height.toFloat(), canvasBorderPaint)
            }
        }

        if (transformActive) {
            canvas.drawBitmap(transformOriginalBitmap!!, transformTool.getMatrix(), bakePaint)
            drawTransformOverlay(canvas)
        }

        if (!selectionTool.getSelectionPath().isEmpty) {
            drawSelectionOverlay(canvas, selectionTool.getSelectionPath())
        }

        // Urban Ortho Grid
        if (isUrbanMode && urbanScaleConfig.isGridVisible) {
            val firstLayer = layers.firstOrNull()?.bitmap
            val canvasW = firstLayer?.width?.toFloat() ?: width.toFloat()
            val canvasH = firstLayer?.height?.toFloat() ?: height.toFloat()
            UrbanGridRenderer.renderGrid(canvas, canvasW, canvasH, urbanScaleConfig, currentScale())
        }

        // Urban Elements (Axes with trailing dots, hatches, symbols, boundaries)
        for (elem in urbanElements) {
            when (elem) {
                is UrbanElement.ArrowPath -> UrbanArrowRenderer.renderArrow(canvas, elem)
                is UrbanElement.HatchPolygon -> UrbanHatchRenderer.renderHatch(canvas, elem)
                is UrbanElement.PointMarker -> UrbanSymbolRenderer.renderMarker(canvas, elem)
                is UrbanElement.BoundaryPath -> UrbanSymbolRenderer.renderBoundary(canvas, elem)
            }
        }

        val assetViewport = AssetViewport(pixelsPerMeter = urbanScaleConfig.pixelsPerMeter)
        architecturalAssets.sortedBy { it.zIndex }.forEach { asset ->
            architecturalAssetRenderer.render(
                canvas,
                asset,
                assetViewport,
                AssetRenderOptions(isSelected = asset.id == selectedArchitecturalAssetId)
            )
        }
        urbanMeasurements.forEach { renderMeasurementAnnotation(canvas, it) }

        drawSnapIndicator(canvas)

        // Selected Urban element - editable outline + drag handles. Reachable either with no
        // drawing tool focused, or by tapping directly on an already-drawn element while a tool
        // is active but nothing is mid-draw (see routeUrbanTapToSelection) - so this only checks
        // the selection itself, not which toolbar-dock bar is currently showing.
        if (isUrbanMode) {
            selectedUrbanElement?.let { renderUrbanSelectionHandles(canvas, it) }
            if (batchSelectedElements.isNotEmpty()) renderBatchSelectionHighlight(canvas, batchSelectedElements)
        }

        // Scale calibration by picking two vertices of an existing element, instead of a fresh
        // two-point drag
        if (isPickingCalibrationVertices) {
            calibVertexPick?.let { p ->
                val pickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.parseColor("#00E5FF")
                }
                canvas.drawCircle(p.x, p.y, 10f * density, pickPaint)
            }
        }

        // Active Urban In-Progress Nodes & Lines
        if (isUrbanMode && currentUrbanPoints.isNotEmpty()) {
            val draftColor = activeUrbanTool?.defaultColor ?: Color.RED
            val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                color = draftColor
                pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            if (currentUrbanPoints.size >= 2) {
                val draftPath = Path()
                draftPath.moveTo(currentUrbanPoints[0].x, currentUrbanPoints[0].y)
                for (i in 1 until currentUrbanPoints.size) {
                    draftPath.lineTo(currentUrbanPoints[i].x, currentUrbanPoints[i].y)
                }
                canvas.drawPath(draftPath, draftPaint)
            }

            // Draw vertex node badges
            val nodeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                color = draftColor
            }
            val nodeNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 14f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val nodeRadius = 14f
            for (i in currentUrbanPoints.indices) {
                val pt = currentUrbanPoints[i]
                canvas.drawCircle(pt.x, pt.y, nodeRadius, nodeBgPaint)
                canvas.drawCircle(pt.x, pt.y, nodeRadius, nodeStrokePaint)
                val fm = nodeNumPaint.fontMetrics
                val textY = pt.y - (fm.ascent + fm.descent) / 2f
                canvas.drawText((i + 1).toString(), pt.x, textY, nodeNumPaint)
            }

            val measurementKind = when (activeUrbanTool) {
                UrbanToolType.MEASURE_DISTANCE -> MeasurementKind.POLYLINE_LENGTH
                UrbanToolType.MEASURE_AREA -> MeasurementKind.POLYGON_AREA
                else -> null
            }
            if (measurementKind != null && currentUrbanPoints.distinctBy { it.x to it.y }.size >= measurementKind.minimumPointCount) {
                renderMeasurementAnnotation(
                    canvas,
                    MeasurementElement(
                        id = "preview",
                        kind = measurementKind,
                        points = currentUrbanPoints.map { MeasurementPoint(it.x.toDouble(), it.y.toDouble()) },
                        displayUnit = measurementDisplayUnit
                    ),
                    isPreview = true
                )
            }
        }

        // Active Scale Calibration Measurement Line
        if (isCalibratingScale && calibStart != null && calibEnd != null) {
            val p1 = calibStart!!
            val p2 = calibEnd!!
            val calibLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.parseColor("#00E5FF")
            }
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, calibLinePaint)
            val angle = Math.atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
            val perp = angle + Math.PI / 2
            val tickLen = 16f
            canvas.drawLine(
                p1.x - Math.cos(perp).toFloat() * tickLen, p1.y - Math.sin(perp).toFloat() * tickLen,
                p1.x + Math.cos(perp).toFloat() * tickLen, p1.y + Math.sin(perp).toFloat() * tickLen,
                calibLinePaint
            )
            canvas.drawLine(
                p2.x - Math.cos(perp).toFloat() * tickLen, p2.y - Math.sin(perp).toFloat() * tickLen,
                p2.x + Math.cos(perp).toFloat() * tickLen, p2.y + Math.sin(perp).toFloat() * tickLen,
                calibLinePaint
            )
        }

        // ── Canvas-Space Anchored Overlays (zoom/pan with the plan sheet) ──
        val firstLayer = layers.firstOrNull()?.bitmap
        val canvasW = firstLayer?.width?.toFloat() ?: width.toFloat()
        val canvasH = firstLayer?.height?.toFloat() ?: height.toFloat()

        // Symmetry Guidelines
        if (symmetryMode != SymmetryMode.NONE) {
            val invZoom = 1f / currentScale().coerceAtLeast(0.05f)
            val symPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb(125, 0, 188, 212)
                strokeWidth = 2f * density * invZoom
                pathEffect = DashPathEffect(floatArrayOf(12f * density * invZoom, 8f * density * invZoom), 0f)
            }
            val midX = canvasW / 2f
            val midY = canvasH / 2f
            if (symmetryMode == SymmetryMode.VERTICAL || symmetryMode == SymmetryMode.QUAD) {
                canvas.drawLine(midX, 0f, midX, canvasH, symPaint)
            }
            if (symmetryMode == SymmetryMode.HORIZONTAL || symmetryMode == SymmetryMode.QUAD) {
                canvas.drawLine(0f, midY, canvasW, midY, symPaint)
            }
        }

        // QuickShape Live Snapped Preview Overlay
        activeQuickShape?.let { shape ->
            val invZoom = 1f / currentScale().coerceAtLeast(0.05f)
            val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                // The weight the commit will lay, in canvas pixels, so preview and result agree.
                strokeWidth = brushEngine.previewSize(quickShapePressure, quickShapeTilt)
                    .coerceAtLeast(2.5f * invZoom)
                color = Color.argb(220, 0, 229, 255)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = QuickShapeEngine.toPath(shape)
            canvas.drawPath(path, previewPaint)
            // Symmetry commits a reflected copy per branch; preview those too.
            for (i in 0 until symmetryStroke.mirrorCount()) {
                val branch = symmetryStroke.branchAt(i)
                canvas.save()
                canvas.scale(
                    if (branch.mirrorX) -1f else 1f,
                    if (branch.mirrorY) -1f else 1f,
                    symmetryStroke.axisX,
                    symmetryStroke.axisY
                )
                canvas.drawPath(path, previewPaint)
                canvas.restore()
            }
        }

        // Sheet cards share one collision-aware layout.  Scale, legend and tables used to be
        // anchored independently, which made the default scale/table corner overlap.  Cards in
        // the same corner now form a clean vertical stack with one shared margin.
        if (isUrbanMode) {
            val showLeg = showLiveLegend
            val plazas = urbanElements.filterIsInstance<UrbanElement.HatchPolygon>()
                .filter { it.toolType == UrbanToolType.PLAZA_HATCH }
            val otherAreas = otherAreasByType()
            val showTab = showLiveTables && plazas.isNotEmpty()
            val showPathwaysTab = showLiveTables && otherAreas.isNotEmpty()
            val legItems = urbanLegendManager.legendItems.value ?: emptyList()
            val legW = 280f
            val legH = 60f + (legItems.size * 32f).coerceAtLeast(64f)
            val tabBmp = if (showTab) getCachedPlazaTable(plazas) else null
            val pathwaysTabBmp = if (showPathwaysTab) getCachedOtherAreasTable(otherAreas) else null
            val scaleSize = UrbanScaleWidget.measureCardSize(urbanScaleConfig)
            val gap = 14f
            val sheetPad = 40f

            class SheetCard(val width: Float, val height: Float, val draw: (Float, Float) -> Unit)
            val cardsByCorner = linkedMapOf<OverlayPlacement, MutableList<SheetCard>>()
            fun addCard(placement: OverlayPlacement, card: SheetCard) {
                cardsByCorner.getOrPut(placement) { mutableListOf() }.add(card)
            }
            if (urbanScaleConfig.isScaleCardVisible) {
                addCard(scaleCardPlacement, SheetCard(scaleSize.x, scaleSize.y) { x, y ->
                    UrbanScaleWidget.renderCard(canvas, x, y, urbanScaleConfig)
                })
            }
            if (showLeg) addCard(legendPlacement, SheetCard(legW, legH) { x, y ->
                LegendRenderer.renderLegendCard(canvas, x, y, legItems, legW)
            })
            tabBmp?.let { bitmap -> addCard(tablePlacement, SheetCard(bitmap.width.toFloat(), bitmap.height.toFloat()) { x, y ->
                canvas.drawBitmap(bitmap, x, y, null)
            }) }
            pathwaysTabBmp?.let { bitmap -> addCard(tablePlacement, SheetCard(bitmap.width.toFloat(), bitmap.height.toFloat()) { x, y ->
                canvas.drawBitmap(bitmap, x, y, null)
            }) }

            cardsByCorner.forEach { (placement, cards) ->
                val groupW = cards.maxOf { it.width }
                val groupH = cards.sumOf { it.height.toDouble() }.toFloat() + gap * (cards.size - 1)
                val anchor = anchorFor(placement, groupW, groupH, canvasW, canvasH, sheetPad)
                val stackDown = placement == OverlayPlacement.TOP_LEFT || placement == OverlayPlacement.TOP_RIGHT
                var y = if (stackDown) anchor.y else anchor.y + groupH
                cards.forEach { card ->
                    if (!stackDown) y -= card.height
                    val x = if (placement == OverlayPlacement.TOP_RIGHT || placement == OverlayPlacement.BOTTOM_RIGHT) {
                        anchor.x + groupW - card.width
                    } else anchor.x
                    card.draw(x, y)
                    if (stackDown) y += card.height + gap else y -= gap
                }
            }
        }

        canvas.restore()

        drawHud(canvas)
    }

    /** Overlays that belong to the gesture rather than the artwork. */
    private fun drawHud(canvas: Canvas) {

        if (eyedropperActive) {
            val r = 34f * density
            val cy = eyedropperPoint.y - r * 1.6f
            hudFillPaint.color = eyedropperColor
            canvas.drawCircle(eyedropperPoint.x, cy, r, hudFillPaint)
            hudStrokePaint.color = Color.WHITE
            hudStrokePaint.strokeWidth = 3f * density
            canvas.drawCircle(eyedropperPoint.x, cy, r, hudStrokePaint)
        }

        if (brushAdjustActive) {
            val brush = viewModel?.currentBrush?.value ?: return
            val cx = width / 2f
            val cy = height / 2f
            // Uncapped up to the screen's own half-diagonal - large enough that a maxed-out brush
            // size can visibly fill the whole screen from its center, rather than being clamped
            // to a fixed fraction of it regardless of how big the real brush actually is.
            val radius = (brush.size * currentScale() / 2f)
                .coerceIn(3f * density, hypot(width.toDouble(), height.toDouble()).toFloat() / 2f)

            hudFillPaint.color = Color.argb((brush.opacity * 200).toInt(), 0, 0, 0)
            canvas.drawCircle(cx, cy, radius, hudFillPaint)
            hudStrokePaint.color = Color.argb(180, 255, 255, 255)
            hudStrokePaint.strokeWidth = 1.5f * density
            canvas.drawCircle(cx, cy, radius, hudStrokePaint)

            val label = context.getString(
                R.string.gesture_hud_brush,
                brush.size.toInt(), (brush.opacity * 100).toInt()
            )
            val boxWidth = hudTextPaint.measureText(label) + 28f * density
            val boxTop = cy + radius + 18f * density
            hudFillPaint.color = Color.argb(210, 20, 20, 24)
            canvas.drawRoundRect(
                RectF(cx - boxWidth / 2f, boxTop, cx + boxWidth / 2f, boxTop + 34f * density),
                17f * density, 17f * density, hudFillPaint
            )
            canvas.drawText(label, cx, boxTop + 22f * density, hudTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopEdgePan()
        removeCallbacks(eyedropperRunnable)
        removeCallbacks(quickShapeRunnable)
        drawingAudioEngine.release()
    }

    /** Set before the view's first layout pass to request a canvas size other than the screen's
     * own pixel dimensions (e.g. a fixed preset, or an open canvas that grows as it's used). */
    var pendingCanvasSize: Pair<Int, Int>? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) {
            val (cw, ch) = pendingCanvasSize ?: Pair(w, h)
            viewModel?.initialize(cw, ch)
            // A canvas larger than the screen would otherwise open showing only its top-left
            // corner, with nothing to suggest the rest of it exists.
            fitCanvasToScreen()
        }
    }

    private companion object {
        const val MAX_SCALE = 40f
        // Procreate-style smooth momentum: longer glide that tapers gently
        const val FLING_FRICTION = 0.95f
        const val FLING_MIN_SPEED = 0.02f
        const val FRAME_MS = 16f
        const val SWIPE_MIN_DP = 90f
        // Longer settle for a more natural spring-to-rest feel
        const val SETTLE_DURATION_MS = 350f
        /** Cumulative raw angle change (degrees) needed before a two-finger gesture is treated as
         * a deliberate rotation rather than pan-induced noise. Reduced from 4° to 2.5° for faster
         * response while still filtering incidental twist from panning. */
        const val ROTATION_ENGAGE_DEGREES = 2.5f
        // Rotation fling parameters
        const val ROTATION_FLING_FRICTION = 0.92f
        const val ROTATION_FLING_MIN_SPEED = 0.0015f
        // Angle snapping zone (degrees from cardinal angle to trigger snap)
        const val SNAP_ZONE_DEGREES = 3f
        // Quick-pinch: gesture must be shorter than this and scale must shrink below threshold
        const val QUICK_PINCH_MAX_MS = 300L
        const val QUICK_PINCH_THRESHOLD = 0.7f  // scale ratio (new/old) below which = quick pinch
        // How far the gesture's focus point may have drifted and still count as "pinched in
        // place" rather than a pan - keeps a normal fast pan+zoom from being misread as the
        // quick-pinch-to-fit shortcut.
        const val QUICK_PINCH_MAX_PAN_DP = 40f
        // Urban element selection/editing
        const val RESIZE_HANDLE_INDEX = -2
        const val RESIZE_HANDLE_OFFSET_DP = 24f
        const val URBAN_HIT_TOLERANCE_DP = 20f
        /** Deliberately tighter than the selection tolerance: snapping should feel like assistance
         * at the moment of placement, not like the point being yanked from across the drawing. */
        const val URBAN_SNAP_TOLERANCE_DP = 14f
        const val URBAN_VERTEX_TOUCH_RADIUS_DP = 26f
        const val URBAN_VERTEX_VISUAL_RADIUS_DP = 8f
    }
}
