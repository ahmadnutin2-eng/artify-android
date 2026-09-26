package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Brush type enumeration matching Procreate categories.
 */
enum class BrushType {
    Pencil, Ink, Paint, Airbrush, Smudge, Eraser
}

/**
 * Properties defining a brush's behavior and appearance.
 */
data class BrushProperties(
    var type: BrushType = BrushType.Pencil,
    var size: Float = 10f,
    var opacity: Float = 1f,
    var flow: Float = 1f,
    var hardness: Float = 1f,
    // Fraction of brush size between stamps. Kept low by default: at 0.25 a stroke is a visible
    // chain of separate dabs rather than a line. Brushes that *want* separated dabs (spray,
    // scatter) raise it deliberately in BrushLibrary.
    var spacing: Float = 0.05f,
    var angle: Float = 0f,            // Fixed rotation angle
    var orientToStroke: Boolean = true, // Whether tip rotates with stroke direction (calligraphy = false)
    var scatter: Float = 0f,          // Perpendicular offset randomness
    var angleJitter: Float = 0f,      // Random rotation per stamp
    var sizeJitter: Float = 0f,       // Random size variation per stamp
    var opacityJitter: Float = 0f,    // Random opacity variation per stamp
    var velocitySizeMin: Float = 0.45f,// Min size at max velocity
    var pressureSizeScale: Float = 0.7f,// How much pressure affects size
    var pressureOpacityScale: Float = 0.4f, // How much pressure affects opacity
    /**
     * How far the nib turns with the stylus barrel, 0 (the fixed [angle] alone) to 1 (fully led by
     * the hand). This is the axis a cut nib lives on: an Arabic reed keeps a constant cut and lets
     * direction of travel make the thick and thin, but a calligrapher still rolls the wrist through
     * certain letterforms, and only the barrel direction can express that. Takes precedence over
     * [orientToStroke] when non-zero, because the two are competing sources for the same angle.
     */
    var azimuthTracking: Float = 0f,
    /** How much laying the pen over widens the stamp, as a real reed or marker spreads. */
    var tiltSizeScale: Float = 0f,
    /** How much laying the pen over lightens the stamp, as a pencil shades on its side. */
    var tiltOpacityScale: Float = 0f,
    var tipType: BrushTipType = BrushTipType.ROUND_HARD,
    var customTipPath: String? = null, // Source image for BrushTipType.CUSTOM (user-imported brushes or authentic Procreate textures)
    var customGrainPath: String? = null, // Custom grain texture from authentic assets
    var thumbnailPath: String? = null, // Authentic Procreate preview thumbnail path
    var grainType: GrainType = GrainType.NONE,
    var grainScale: Float = 1f,       // How much grain shows through
    var buildUp: Boolean = true,      // Whether strokes build up opacity
    /**
     * Turns this brush into a square-Kufic pen: strokes fill whole cells of the canvas grid
     * instead of laying down dabs. Zero leaves the brush ordinary; 1 makes one module the full
     * grid cell, 2 makes it a half cell, and so on - the "1/1" and "1/2" of a Kufic grid pen.
     *
     * Square Kufic is built, not written. Every stroke is an orthogonal bar one module wide, every
     * gap is one module, and the letters are assembled inside the grid rather than drawn over it.
     * No amount of stabilization or taper gets a freehand stroke there; the geometry has to be the
     * thing the tool produces.
     */
    var gridSnapDivisions: Int = 0,
    /**
     * Whether this brush's colour moves at all. Off for every brush that does not ask for it, and
     * off by default, because a colour that wanders is the exception - most work wants the colour
     * the artist picked and no other.
     *
     * Kept separate from the amounts below so switching the effect off preserves the settings
     * underneath it; zeroing the sliders to disable it would throw a tuned brush away.
     */
    var colorFlowEnabled: Boolean = false,
    /** Degrees the hue travels along every thousand canvas pixels of stroke. */
    var colorFlowHue: Float = 0f,
    /** Per-stamp scatter around wherever the drift currently is - the grain of pigment itself. */
    var colorJitterHue: Float = 0f,
    var colorJitterSaturation: Float = 0f,
    var colorJitterBrightness: Float = 0f,
    var smoothing: Float = 0.2f,      // Stroke stabilization strength (0-1)
    var wetness: Float = 0f           // Wet-mix: blend brush color with canvas color underneath (0-1)
)

/**
 * Advanced Brush Engine using Bitmap Stamping technique.
 *
 * Instead of drawing lines, this engine stamps a brush tip bitmap repeatedly along the touch
 * path. Two things keep that from reading as a row of discrete blobs:
 *
 *  - **Spline path.** [strokeTo] fits a Catmull-Rom curve through the incoming touch samples and
 *    stamps along the curve, so the stroke bends between samples instead of turning the polyline
 *    corners a straight-segment walk would produce.
 *  - **Zoom-aware spacing.** The minimum step between stamps is expressed in *screen* pixels via
 *    [renderScale], not canvas pixels. A fixed canvas-space floor is what made stamps visibly
 *    separate once the user zoomed in - the gap was magnified along with everything else.
 */
class BrushEngine {
    /** Called immediately before pixels in this canvas-space rectangle are mutated. */
    var beforeWrite: ((RectF) -> Unit)? = null
    var properties = BrushProperties()
    var color: Int = Color.BLACK

    /**
     * The canvas' current on-screen zoom, set by DrawingView. Only affects how tightly stamps are
     * packed: the engine targets sub-pixel spacing *as displayed*, so a stroke is equally smooth
     * whether it's drawn zoomed out or at 10x.
     */
    var renderScale: Float = 1f
        set(value) { field = value.coerceIn(0.05f, 40f) }

    /** Canvas-space bounds touched since [resetDirty] - lets DrawingView repaint only what changed. */
    val dirtyRect = RectF()

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stampMatrix = Matrix()
    private val grainLocalMatrix = Matrix()
    private val rng = Random(System.nanoTime())

    private var distanceAccumulator: Float = 0f
    private var currentTipBitmap: Bitmap? = null

    // Rolling window of the last four stroke samples. Catmull-Rom needs a point on either side of
    // the span it draws, so ink trails the finger by exactly one sample - the same small,
    // predictable lag a stabilized brush has, and far less than any smoothing filter costs.
    private val ptX = FloatArray(4)
    private val ptY = FloatArray(4)
    private val ptPressure = FloatArray(4)
    private val ptVelocity = FloatArray(4)
    private val ptTilt = FloatArray(4)
    /**
     * Barrel direction is carried as a unit vector rather than an angle. Interpolating radians
     * directly would sweep the long way round whenever a stroke crosses the +/-pi wrap, spinning a
     * cut nib through a half turn in the middle of a letter.
     */
    private val ptAzimuthX = FloatArray(4)
    private val ptAzimuthY = FloatArray(4)

    /**
     * Whether the current input actually carries a barrel direction. A finger and a mouse report
     * orientation zero, which is a real angle rather than "unknown", so an azimuth-driven brush
     * would silently pin its nib straight up instead of falling back to its own cut.
     */
    var stylusAzimuthAvailable: Boolean = false

    /** Canvas pixels laid down since the stroke began; what the colour drift is measured against. */
    private var strokeDistance = 0f

    /**
     * Set while the stroke is accumulating in a scratch buffer that will be folded down at the
     * brush's opacity afterwards. The stamps then go in at full strength, because applying the
     * master opacity here as well would apply it twice and halve every uniform brush.
     */
    var deferStrokeOpacity: Boolean = false

    /**
     * Recolours the grain without rebuilding its tile.
     *
     * The grain tile bakes the brush colour into its pixels, so a drifting colour would need the
     * tile rebuilt for every stamp - hundreds of allocations a second. Source-in keeps the tile's
     * alpha, which is the texture, and replaces only its colour, which is the part that moves.
     */
    private var grainTintFilter: android.graphics.PorterDuffColorFilter? = null
    private var grainTintColor: Int = 0
    private var strokeActive = false

    // Grain is baked into a canvas-anchored tile rather than into each stamp. Baking it per stamp
    // made every dab carry an identical texture patch, which is what turned a textured stroke into
    // a regular, obviously-repeating pattern instead of continuous paper tooth.
    private var grainShader: BitmapShader? = null
    private var grainTile: Bitmap? = null
    private var grainColor: Int = 0
    private var grainCachedType: GrainType? = null
    private var grainCachedScale: Float = -1f

    // Separate scratch buffer for the smudge tool, sized in canvas pixels (unlike brush tips,
    // which are generated at a fixed high resolution and scaled at stamp time).
    private var smudgeTipType: BrushTipType? = null
    private var smudgeStampSize: Int = 0
    private var smudgeScratch: Bitmap? = null
    private var smudgeMask: Bitmap? = null
    private val copyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Interactive blur used to allocate five channel arrays and two bitmaps for every motion
    // sample. Retaining bounded scratch storage turns that GC-heavy path into steady-state work.
    private val blurWorkspace = com.procreate.android.adjust.ImageAdjustments.BlurWorkspace()
    private var blurPixels = IntArray(0)
    private var blurOriginalPixels = IntArray(0)
    private var blurCoveragePixels = IntArray(0)
    private var blurBitmap: Bitmap? = null
    private var blurCoverage: Bitmap? = null
    private val blurCoveragePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // An ALPHA_8 tip takes its colour from the paint; only the resulting alpha is read back.
        color = Color.BLACK
    }
    /** The mixed patch is the finished pixel, so it replaces the rectangle instead of blending. */
    private val blurReplacePaint = Paint().apply {
        blendMode = android.graphics.BlendMode.SRC
    }

    fun resetDirty() {
        dirtyRect.setEmpty()
    }

    fun startStroke(
        x: Float,
        y: Float,
        pressure: Float = 1f,
        tilt: Float = 0f,
        azimuth: Float = 0f
    ) {
        distanceAccumulator = 0f
        strokeDistance = 0f
        strokeActive = true

        val azX = cos(azimuth.toDouble()).toFloat()
        val azY = sin(azimuth.toDouble()).toFloat()
        for (i in 0 until 4) {
            ptX[i] = x; ptY[i] = y; ptPressure[i] = pressure; ptVelocity[i] = 0f
            ptTilt[i] = tilt; ptAzimuthX[i] = azX; ptAzimuthY[i] = azY
        }

        currentTipBitmap = BrushTextures.getBrushTip(properties.tipType, brushPixelSize(), properties.customTipPath)
        refreshGrain()
    }

    /**
     * Rebase the in-flight spline after an open canvas grows on its left or top side.
     *
     * Canvas growth moves every existing pixel by the same amount. The four-point spline window
     * must move with those pixels; otherwise its next sample joins a point in the new coordinate
     * system to controls left behind in the old one, producing the visible diagonal jump/cut that
     * used to occur exactly at an expanding edge.
     */
    fun offsetActiveStroke(dx: Float, dy: Float) {
        if (!strokeActive || (dx == 0f && dy == 0f)) return
        for (i in ptX.indices) {
            ptX[i] += dx
            ptY[i] += dy
        }
        if (!dirtyRect.isEmpty) dirtyRect.offset(dx, dy)
    }

    /**
     * Feed one touch sample into the stroke and paint everything the curve has resolved so far.
     * Call this once per sample - including the batched historical samples on a MotionEvent, which
     * are where most of a fast stroke's shape actually lives.
     */
    fun strokeTo(
        canvas: Canvas,
        destBitmap: Bitmap?,
        x: Float, y: Float,
        pressure: Float = 1f,
        velocity: Float = 0f,
        tilt: Float = 0f,
        azimuth: Float = 0f
    ) {
        if (!strokeActive) {
            startStroke(x, y, pressure, tilt, azimuth)
            return
        }
        pushPoint(x, y, pressure, velocity, tilt, azimuth)
        drawSpline(canvas, destBitmap)
    }

    /**
     * Finish the stroke, flushing the span the one-sample lookahead was still holding back so the
     * ink actually reaches where the finger lifted.
     */
    fun endStroke(canvas: Canvas? = null, destBitmap: Bitmap? = null) {
        if (strokeActive && canvas != null) {
            // Two extra repeats of the final point walk the window forward past the last real span.
            repeat(2) {
                pushPoint(ptX[3], ptY[3], ptPressure[3], ptVelocity[3])
                drawSpline(canvas, destBitmap)
            }
        }
        strokeActive = false
        distanceAccumulator = 0f
    }

    /**
     * Lay a single dab, for a tap that never turns into a stroke. The spline needs two samples
     * before it can resolve any curve, so without this a quick tap would leave no mark at all.
     */
    fun stampDot(
        canvas: Canvas,
        destBitmap: Bitmap?,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        tilt: Float = 0f,
        azimuth: Float = 0f
    ) {
        val tip = currentTipBitmap
            ?: BrushTextures.getBrushTip(properties.tipType, brushPixelSize(), properties.customTipPath)
                .also { currentTipBitmap = it }
        drawSingleStamp(canvas, destBitmap, tip, x, y, pressure, 0f, 0f, tilt, azimuth)
        distanceAccumulator = stampSpacing(calculateDynamicSize(pressure, 0f, tilt))
    }

    private fun pushPoint(
        x: Float,
        y: Float,
        pressure: Float,
        velocity: Float,
        tilt: Float = ptTilt[3],
        azimuth: Float = Float.NaN
    ) {
        for (i in 0 until 3) {
            ptX[i] = ptX[i + 1]; ptY[i] = ptY[i + 1]
            ptPressure[i] = ptPressure[i + 1]; ptVelocity[i] = ptVelocity[i + 1]
            ptTilt[i] = ptTilt[i + 1]
            ptAzimuthX[i] = ptAzimuthX[i + 1]; ptAzimuthY[i] = ptAzimuthY[i + 1]
        }
        ptX[3] = x; ptY[3] = y; ptPressure[3] = pressure; ptVelocity[3] = velocity
        ptTilt[3] = tilt
        if (azimuth.isFinite()) {
            ptAzimuthX[3] = cos(azimuth.toDouble()).toFloat()
            ptAzimuthY[3] = sin(azimuth.toDouble()).toFloat()
        }
    }

    /**
     * Stamp the Catmull-Rom span between samples 1 and 2, using 0 and 3 as the tangent controls.
     * The curve is flattened into short chords and handed to the same spacing walk a straight
     * segment uses, so stamp spacing stays uniform right through the curvature.
     */
    private fun drawSpline(canvas: Canvas, destBitmap: Bitmap?) {
        val chord = hypot((ptX[2] - ptX[1]).toDouble(), (ptY[2] - ptY[1]).toDouble()).toFloat()
        if (chord < 0.01f) return

        val step = stampSpacing(calculateDynamicSize(ptPressure[2], ptVelocity[2], ptTilt[2]))
        // One flattening chord per stamp keeps the polyline finer than the stamps riding on it;
        // capped so a fast flick across a zoomed-in canvas can't explode into thousands of chords.
        val subdivisions = ceil(chord / max(step, 0.5f)).toInt().coerceIn(1, 96)

        var prevX = ptX[1]
        var prevY = ptY[1]
        var prevPressure = ptPressure[1]
        var prevVelocity = ptVelocity[1]
        var prevTilt = ptTilt[1]
        var prevAzimuth = atan2(ptAzimuthY[1].toDouble(), ptAzimuthX[1].toDouble()).toFloat()

        // The barrel turns smoothly through a span, so interpolate its direction as a vector and
        // read the angle back out; that carries a nib across the +/-pi wrap without spinning it.
        val azimuthStart = prevAzimuth
        val azimuthSweep = StylusResponse.angleDeltaRadians(
            azimuthStart,
            atan2(ptAzimuthY[2].toDouble(), ptAzimuthX[2].toDouble()).toFloat()
        )

        for (i in 1..subdivisions) {
            val t = i / subdivisions.toFloat()
            val cx = catmullRom(ptX[0], ptX[1], ptX[2], ptX[3], t)
            val cy = catmullRom(ptY[0], ptY[1], ptY[2], ptY[3], t)
            val cp = ptPressure[1] + (ptPressure[2] - ptPressure[1]) * t
            val cv = ptVelocity[1] + (ptVelocity[2] - ptVelocity[1]) * t
            val ct = ptTilt[1] + (ptTilt[2] - ptTilt[1]) * t
            val ca = azimuthStart + azimuthSweep * t
            walkSegment(
                canvas, destBitmap,
                prevX, prevY, prevPressure, prevVelocity, prevTilt, prevAzimuth,
                cx, cy, cp, cv, ct, ca
            )
            prevX = cx; prevY = cy; prevPressure = cp; prevVelocity = cv
            prevTilt = ct; prevAzimuth = ca
        }
    }

    private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    /**
     * Distance between consecutive stamps, in canvas pixels.
     *
     * The floor is derived from [renderScale] so it lands just under one *displayed* pixel: at 1x
     * that is the old sub-pixel behaviour, and at 8x zoom the step shrinks to an eighth of a canvas
     * pixel so the stroke stays solid instead of resolving into the separate dabs it's built from.
     */
    private fun stampSpacing(dynamicSize: Float): Float {
        val floor = max(0.08f, 0.6f / renderScale)
        return max(dynamicSize * properties.spacing.coerceAtLeast(0.005f), floor)
    }

    /**
     * The shared spacing walk: lay stamps every [stampSpacing] canvas pixels between two points,
     * carrying the leftover distance into the next call so spacing never resets at a sample
     * boundary (which would bunch stamps up at every touch event).
     */
    private fun walkSegment(
        canvas: Canvas,
        destBitmap: Bitmap?,
        startX: Float, startY: Float, startPressure: Float, startVelocity: Float,
        startTilt: Float, startAzimuth: Float,
        endX: Float, endY: Float, endPressure: Float, endVelocity: Float,
        endTilt: Float, endAzimuth: Float
    ) {
        val tip = currentTipBitmap ?: return

        val dx = endX - startX
        val dy = endY - startY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // A zero-length chord carries no direction to orient or space stamps along; a genuine
        // tap goes through stampDot instead.
        if (dist < 0.001f) return

        var spacingPx = stampSpacing(calculateDynamicSize(endPressure, endVelocity, endTilt))
        // Hard ceiling on stamps per chord. Without it a single huge jump (a dropped frame, a
        // pen teleporting across the canvas) at high zoom could queue tens of thousands of stamps
        // and stall the UI thread; widening the step degrades smoothness instead of freezing.
        val maxStamps = 512
        if (dist / spacingPx > maxStamps) spacingPx = dist / maxStamps

        val dirAngle = atan2(dy.toDouble(), dx.toDouble()).toFloat()

        var d = distanceAccumulator
        while (d < dist) {
            val t = d / dist
            drawSingleStamp(
                canvas, destBitmap, tip,
                startX + dx * t, startY + dy * t,
                startPressure + (endPressure - startPressure) * t,
                startVelocity + (endVelocity - startVelocity) * t,
                dirAngle,
                startTilt + (endTilt - startTilt) * t,
                startAzimuth + StylusResponse.angleDeltaRadians(startAzimuth, endAzimuth) * t
            )
            strokeDistance += spacingPx
            d += spacingPx
        }
        distanceAccumulator = d - dist
    }

    /**
     * Softens existing pixels under the brush along a segment (blur tool).
     *
     * Distinct from smudge, which drags colour along the stroke: this one leaves colour where it is
     * and only removes detail, which is what a painter reaches for to push something into the
     * background or to soften an edge that came out too crisp.
     *
     * The blur is computed once for the whole affected rectangle and then stamped back through the
     * brush tip's alpha, so the effect fades at the edge of the brush instead of leaving a hard
     * circular cut. [pressure] scales both the radius and how much of the blurred result is mixed
     * in, so a light pass softens gently and a firm one melts the area.
     */
    fun drawBlurSegment(
        canvas: Canvas,
        destBitmap: Bitmap,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        pressure: Float
    ) {
        val footprint = brushPixelSize()
        val half = footprint / 2 + 1

        // One rectangle covering the whole segment plus the brush radius. Blurring per stamp would
        // re-blur the overlap between consecutive stamps and smear far more than asked.
        val left = (kotlin.math.min(startX, endX) - half).toInt().coerceIn(0, destBitmap.width - 1)
        val top = (kotlin.math.min(startY, endY) - half).toInt().coerceIn(0, destBitmap.height - 1)
        val right = (max(startX, endX) + half).toInt().coerceIn(left + 1, destBitmap.width)
        val bottom = (max(startY, endY) + half).toInt().coerceIn(top + 1, destBitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 2 || h < 2) return

        val pixelCount = w * h
        if (blurPixels.size < pixelCount) blurPixels = IntArray(pixelCount)
        if (blurOriginalPixels.size < pixelCount) blurOriginalPixels = IntArray(pixelCount)
        if (blurCoveragePixels.size < pixelCount) blurCoveragePixels = IntArray(pixelCount)
        val pixels = blurPixels
        val original = blurOriginalPixels
        destBitmap.getPixels(pixels, 0, w, left, top, w, h)
        System.arraycopy(pixels, 0, original, 0, pixelCount)

        val radius = (footprint * 0.12f * (0.35f + pressure * 0.65f)).toInt().coerceIn(1, 24)
        com.procreate.android.adjust.ImageAdjustments.gaussianBlur(
            pixels, w, h, radius, blurWorkspace
        )

        val tip = BrushTextures.getBrushTip(properties.tipType, footprint, properties.customTipPath)
        val dx = endX - startX
        val dy = endY - startY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val spacing = max(footprint * properties.spacing, 1f)
        val steps = max(1, (dist / spacing).toInt())
        // A first pass clears the mask to transparent, then each stamp adds coverage; drawing the
        // tips straight over the blurred copy with DST_IN would let the last stamp erase the rest.
        val coverage = ensureBlurCoverage(w, h)
        coverage.eraseColor(Color.TRANSPARENT)
        val coverageCanvas = Canvas(coverage)
        for (i in 0..steps) {
            val t = if (steps == 0) 0f else i / steps.toFloat()
            val cx = startX + dx * t - left
            val cy = startY + dy * t - top
            val dst = android.graphics.RectF(
                cx - footprint / 2f, cy - footprint / 2f,
                cx + footprint / 2f, cy + footprint / 2f
            )
            coverageCanvas.drawBitmap(tip, null, dst, blurCoveragePaint)
        }
        val coverageAlpha = blurCoveragePixels
        coverage.getPixels(coverageAlpha, 0, w, 0, 0, w, h)

        // Opacity is blur strength: repeated light passes stay controllable, while full opacity
        // produces the pronounced defocus artists expect. Pressure continues to influence radius.
        val strength = (properties.opacity * (0.25f + pressure * 0.75f)).coerceIn(0.05f, 1f)

        // Mix each pixel between what was there and its blurred value, weighted by how much of the
        // brush tip covered it.
        //
        // The blurred patch used to be masked by the tip and then drawn back on with SRC_OVER, and
        // source-over can only ever add: it cannot lower a pixel's alpha. Blurring an area that
        // borders transparency - which is most of a detailed layer - therefore left every hard
        // silhouette exactly as crisp as it was and painted a spreading halo of blurred colour
        // around it instead. Softening an edge is precisely the job, so the result has to be able
        // to move alpha in both directions. Mixing here and replacing the rectangle outright does
        // that, and blending in premultiplied space keeps colour from bleeding out of the
        // transparent side of the edge.
        blend(pixels, original, coverageAlpha, pixelCount, strength)

        val blurred = ensureBlurBitmap(w, h)
        blurred.setPixels(pixels, 0, w, 0, 0, w, h)
        val src = Rect(0, 0, w, h)
        val dst = Rect(left, top, right, bottom)
        beforeWrite?.invoke(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
        canvas.drawBitmap(blurred, src, dst, blurReplacePaint)
        // The whole rectangle was rewritten, so union it directly rather than going through the
        // point-and-size markDirty used by the stamping path.
        dirtyRect.union(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    }

    /**
     * In place, replace [blurredPixels] with its mix toward [originalPixels], weighted per pixel by
     * the tip coverage in [coveragePixels] and globally by [strength].
     */
    private fun blend(
        blurredPixels: IntArray,
        originalPixels: IntArray,
        coveragePixels: IntArray,
        count: Int,
        strength: Float
    ) {
        for (i in 0 until count) {
            val cover = (coveragePixels[i] ushr 24 and 0xFF)
            val o = originalPixels[i]
            if (cover == 0) {
                blurredPixels[i] = o
                continue
            }
            val weight = cover / 255f * strength
            val b = blurredPixels[i]
            val oa = (o ushr 24 and 0xFF) / 255f
            val ba = (b ushr 24 and 0xFF) / 255f
            val opr = (o shr 16 and 0xFF) / 255f * oa
            val opg = (o shr 8 and 0xFF) / 255f * oa
            val opb = (o and 0xFF) / 255f * oa
            val mixedA = oa + (ba - oa) * weight
            if (mixedA <= 0f) {
                blurredPixels[i] = 0
                continue
            }
            val mr = opr + ((b shr 16 and 0xFF) / 255f * ba - opr) * weight
            val mg = opg + ((b shr 8 and 0xFF) / 255f * ba - opg) * weight
            val mb = opb + ((b and 0xFF) / 255f * ba - opb) * weight
            blurredPixels[i] = channel(mixedA) shl 24 or
                (channel(mr / mixedA) shl 16) or
                (channel(mg / mixedA) shl 8) or
                channel(mb / mixedA)
        }
    }

    private fun channel(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

    private fun ensureBlurBitmap(width: Int, height: Int): Bitmap {
        val current = blurBitmap
        if (current != null && current.width >= width && current.height >= height) return current
        val grownWidth = max(width, current?.width ?: 0)
        val grownHeight = max(height, current?.height ?: 0)
        current?.recycle()
        return Bitmap.createBitmap(grownWidth, grownHeight, Bitmap.Config.ARGB_8888).also {
            blurBitmap = it
        }
    }

    private fun ensureBlurCoverage(width: Int, height: Int): Bitmap {
        val current = blurCoverage
        if (current != null && current.width >= width && current.height >= height) return current
        val grownWidth = max(width, current?.width ?: 0)
        val grownHeight = max(height, current?.height ?: 0)
        current?.recycle()
        // ARGB_8888 rather than ALPHA_8: the coverage is now read back with getPixels, which
        // Android does not support for alpha-only bitmaps.
        return Bitmap.createBitmap(grownWidth, grownHeight, Bitmap.Config.ARGB_8888).also {
            blurCoverage = it
        }
    }

    /**
     * Drag/smear existing pixels from destBitmap along a line segment (smudge tool).
     * Does not add new color - it redistributes what's already on the layer.
     */
    fun drawSmudgeSegment(canvas: Canvas, destBitmap: Bitmap, startX: Float, startY: Float, endX: Float, endY: Float, pressure: Float) {
        val footprint = brushPixelSize()
        val tip = BrushTextures.getBrushTip(properties.tipType, footprint, properties.customTipPath)
        // Brush tips are generated at a fixed high resolution, so the smudge footprint has to be
        // derived from the brush size and the tip's own aspect ratio rather than its pixel size.
        val tw = footprint
        val th = max(1, (footprint * tip.height / tip.width.toFloat()).toInt())

        if (smudgeTipType != properties.tipType || smudgeStampSize != footprint || smudgeScratch == null) {
            smudgeScratch = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            // Pre-scale the tip to the smudge footprint once per size change, so the per-stamp
            // masking pass is a plain 1:1 blit instead of a scaled one.
            smudgeMask = Bitmap.createBitmap(tw, th, Bitmap.Config.ALPHA_8).also { mask ->
                val m = Matrix().apply { setScale(tw / tip.width.toFloat(), th / tip.height.toFloat()) }
                Canvas(mask).drawBitmap(tip, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            smudgeTipType = properties.tipType
            smudgeStampSize = footprint
        }
        val scratch = smudgeScratch ?: return
        val mask = smudgeMask ?: return
        val scratchCanvas = Canvas(scratch)

        val dx = endX - startX
        val dy = endY - startY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 0.1f) return

        var spacingPx = max(tw * properties.spacing, max(0.6f, 0.6f / renderScale))
        if (dist / spacingPx > 256) spacingPx = dist / 256
        val halfW = tw / 2
        val halfH = th / 2
        val dragBack = spacingPx / max(dist, 0.001f)

        var d = distanceAccumulator
        while (d < dist) {
            val t = (d / dist).coerceIn(0f, 1f)
            // Sample from slightly behind the current stamp position (where paint is being dragged from)
            val sampleX = (startX + dx * t - dx * dragBack).toInt()
            val sampleY = (startY + dy * t - dy * dragBack).toInt()
            val targetX = startX + dx * t
            val targetY = startY + dy * t

            val srcLeft = (sampleX - halfW).coerceIn(0, destBitmap.width - 1)
            val srcTop = (sampleY - halfH).coerceIn(0, destBitmap.height - 1)
            val srcRight = (sampleX + halfW).coerceIn(srcLeft + 1, destBitmap.width)
            val srcBottom = (sampleY + halfH).coerceIn(srcTop + 1, destBitmap.height)
            val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight, srcBottom)
            val fullRect = android.graphics.Rect(0, 0, tw, th)

            // Copy the sampled area into the scratch buffer, then mask it with the brush tip's
            // soft-edge alpha so the smudge doesn't leave hard rectangular edges.
            copyPaint.blendMode = android.graphics.BlendMode.SRC
            scratchCanvas.drawBitmap(destBitmap, srcRect, fullRect, copyPaint)
            maskPaint.blendMode = android.graphics.BlendMode.DST_IN
            scratchCanvas.drawBitmap(mask, 0f, 0f, maskPaint)

            // Pressure controls how strongly the sampled paint is pushed (like a real smudge tool)
            val smudgeStrength = (properties.opacity * (0.3f + 0.7f * pressure)).coerceIn(0f, 1f)
            stampPaint.shader = null
            stampPaint.colorFilter = null
            stampPaint.color = Color.BLACK
            stampPaint.alpha = (smudgeStrength * 255).toInt()
            stampPaint.blendMode = android.graphics.BlendMode.SRC_OVER
            beforeWrite?.invoke(RectF(
                targetX - tw / 2f - 2f,
                targetY - th / 2f - 2f,
                targetX + tw / 2f + 2f,
                targetY + th / 2f + 2f
            ))
            canvas.drawBitmap(scratch, targetX - tw / 2f, targetY - th / 2f, stampPaint)
            markDirty(targetX, targetY, max(tw, th).toFloat())

            d += spacingPx
        }
        distanceAccumulator = d - dist
    }

    /**
     * Draw a single stamp at the given position.
     *
     * [tip] is the alpha-only shape; its colour comes from the paint (a flat colour, or the
     * canvas-anchored grain shader) rather than from a pre-tinted copy of the bitmap. Skipping
     * that per-colour copy is both faster and what allows grain to stay fixed to the canvas.
     */
    private fun drawSingleStamp(
        canvas: Canvas,
        destBitmap: Bitmap?,
        tip: Bitmap,
        x: Float, y: Float,
        pressure: Float,
        velocity: Float,
        dirAngle: Float,
        tilt: Float = 0f,
        azimuth: Float = 0f
    ) {
        val dynamicSize = calculateDynamicSize(pressure, velocity, tilt)
        val tiltFactor = StylusResponse.tiltFactor(tilt)

        var dynamicOpacity = (if (deferStrokeOpacity) 1f else properties.opacity) * properties.flow
        dynamicOpacity *= (1f - properties.pressureOpacityScale + properties.pressureOpacityScale * pressure)
        // Laying the pen over puts less of the tip's weight on the paper, the way a pencil held
        // low shades pale even when pressed.
        dynamicOpacity *= (1f - properties.tiltOpacityScale.coerceIn(0f, 1f) * tiltFactor)
        dynamicOpacity += (rng.nextFloat() - 0.5f) * properties.opacityJitter * 2f
        dynamicOpacity = dynamicOpacity.coerceIn(0.02f, 1f)

        val tracksBarrel = properties.azimuthTracking > 0f && stylusAzimuthAvailable
        var rotation = when {
            tracksBarrel ->
                StylusResponse.nibRotationDegrees(azimuth, properties.azimuthTracking, properties.angle)
            properties.orientToStroke ->
                Math.toDegrees(dirAngle.toDouble()).toFloat() + properties.angle
            else -> properties.angle
        }
        rotation += (rng.nextFloat() - 0.5f) * properties.angleJitter * 2f

        val scatterOffset = if (properties.scatter > 0f) {
            val perpAngle = dirAngle + Math.PI.toFloat() / 2f
            val offset = (rng.nextFloat() - 0.5f) * properties.scatter * dynamicSize
            PointF(
                cos(perpAngle.toDouble()).toFloat() * offset,
                sin(perpAngle.toDouble()).toFloat() * offset
            )
        } else {
            PointF(0f, 0f)
        }

        val finalX = x + scatterOffset.x
        val finalY = y + scatterOffset.y

        val scale = dynamicSize / tip.width.toFloat()

        stampMatrix.reset()
        stampMatrix.postScale(scale, scale)
        stampMatrix.postRotate(rotation, tip.width * scale / 2f, tip.height * scale / 2f)
        stampMatrix.postTranslate(
            finalX - tip.width * scale / 2f,
            finalY - tip.height * scale / 2f
        )

        val isEraser = properties.type == BrushType.Eraser
        val wetMix = properties.wetness > 0f && destBitmap != null && !isEraser

        // Where this particular stamp's colour has got to. An eraser removes paint rather than
        // laying it, so it has no colour to move.
        val flowingColor = if (isEraser || !properties.colorFlowEnabled) color else ColorFlow.shift(
            color,
            ColorFlow.hueDrift(strokeDistance, properties.colorFlowHue),
            properties.colorJitterHue,
            properties.colorJitterSaturation,
            properties.colorJitterBrightness
        ) { rng.nextFloat() }

        stampPaint.colorFilter = null
        when {
            isEraser -> {
                // DST_OUT subtracts the stamp's alpha from what's underneath. The previous CLEAR
                // mode ignores source alpha entirely, so every dab punched out its full square
                // bounding box - the eraser left a trail of hard rectangles instead of a soft line.
                stampPaint.shader = null
                stampPaint.color = Color.BLACK
                stampPaint.blendMode = android.graphics.BlendMode.DST_OUT
            }
            wetMix -> {
                val sampled = samplePixel(destBitmap!!, finalX.toInt(), finalY.toInt())
                val sampledAlpha = Color.alpha(sampled) / 255f
                val mixT = (properties.wetness * sampledAlpha).coerceIn(0f, 1f)
                stampPaint.shader = null
                stampPaint.color = lerpColor(flowingColor, sampled, mixT)
                stampPaint.blendMode = android.graphics.BlendMode.SRC_OVER
            }
            else -> {
                val grain = grainShader
                if (grain != null) {
                    // drawBitmap(bitmap, matrix, paint) concatenates the matrix onto the canvas,
                    // which would drag the shader along with the stamp and reintroduce the per-dab
                    // texture repeat. Feeding the shader the inverse cancels that out, pinning the
                    // grain to the canvas. The local matrix is set before the shader is attached,
                    // so the paint always picks up the current one.
                    if (stampMatrix.invert(grainLocalMatrix)) grain.setLocalMatrix(grainLocalMatrix)
                    stampPaint.shader = grain
                    if (flowingColor != color) {
                        // Retint the baked tile rather than rebuilding it; the filter is cached so
                        // a drifting colour does not allocate one per stamp.
                        if (grainTintFilter == null || grainTintColor != flowingColor) {
                            grainTintColor = flowingColor
                            grainTintFilter = android.graphics.PorterDuffColorFilter(
                                flowingColor, android.graphics.PorterDuff.Mode.SRC_IN
                            )
                        }
                        stampPaint.colorFilter = grainTintFilter
                    }
                } else {
                    stampPaint.shader = null
                    stampPaint.color = flowingColor
                }
                stampPaint.blendMode = android.graphics.BlendMode.SRC_OVER
            }
        }
        stampPaint.alpha = (dynamicOpacity * 255).toInt().coerceIn(0, 255)

        val dirtySize = dynamicSize * max(1f, tip.height / tip.width.toFloat())
        val dirtyRadius = dirtySize * 0.75f + 2f
        beforeWrite?.invoke(RectF(
            finalX - dirtyRadius,
            finalY - dirtyRadius,
            finalX + dirtyRadius,
            finalY + dirtyRadius
        ))
        canvas.drawBitmap(tip, stampMatrix, stampPaint)
        markDirty(finalX, finalY, dirtySize)
    }

    private fun markDirty(x: Float, y: Float, size: Float) {
        val r = size * 0.75f + 2f
        dirtyRect.union(x - r, y - r, x + r, y + r)
    }

    private fun brushPixelSize(): Int = max(properties.size.toInt(), 4)

    /**
     * Rebuild the canvas-anchored grain tile whenever the colour, grain type or grain depth
     * changes. The tile is the brush colour with the grain pattern in its alpha channel, tiled
     * with REPEAT - so the texture belongs to the canvas, and successive stamps reveal different
     * parts of it exactly as paint dragged over real paper does.
     */
    private fun refreshGrain() {
        val customGrain = properties.customGrainPath
        val type = properties.grainType
        val isBlankGrain = customGrain?.endsWith(BrushTextures.BLANK_ASSET) == true
        if (properties.grainScale <= 0.02f || ((customGrain.isNullOrEmpty() || isBlankGrain) && type == GrainType.NONE)) {
            grainShader = null
            grainCachedType = null
            return
        }

        val source = if (!customGrain.isNullOrEmpty()) {
            BrushTextures.getGrainTextureCustom(customGrain)
        } else {
            BrushTextures.getGrainTexture(type)
        }
        val tile = grainTile?.takeIf { it.width == source.width && it.height == source.height }
            ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { grainTile = it }

        val c = Canvas(tile)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawRect(
            0f, 0f, tile.width.toFloat(), tile.height.toFloat(),
            Paint().apply { this.color = this@BrushEngine.color }
        )

        val depth = properties.grainScale.coerceIn(0f, 1f)
        val mask = if (depth >= 0.999f) source else {
            // grainScale is how deep the tooth cuts. Flooring the grain's alpha at (1 - depth)
            // fades the pattern toward solid paint, rather than scaling it down uniformly - which
            // would just make the whole stroke fainter instead of less textured.
            val softened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ALPHA_8)
            val bc = Canvas(softened)
            bc.drawBitmap(source, 0f, 0f, null)
            bc.drawColor(Color.argb(((1f - depth) * 255).toInt().coerceIn(0, 255), 0, 0, 0), PorterDuff.Mode.DST_OVER)
            softened
        }
        c.drawBitmap(mask, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = android.graphics.BlendMode.DST_IN
        })
        if (mask !== source) mask.recycle()

        grainShader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grainColor = color
        grainCachedType = type
        grainCachedScale = properties.grainScale
    }

    /** Called by DrawingView when the brush or colour changes between strokes. */
    fun invalidateCaches() {
        currentTipBitmap = BrushTextures.getBrushTip(properties.tipType, brushPixelSize(), properties.customTipPath)
        refreshGrain()
    }

    private fun samplePixel(bitmap: Bitmap, x: Int, y: Int): Int {
        val px = x.coerceIn(0, bitmap.width - 1)
        val py = y.coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(px, py)
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }

    /**
     * Calculate dynamic brush size based on pressure and velocity.
     */
    private fun calculateDynamicSize(pressure: Float, velocity: Float, tilt: Float = 0f): Float {
        var size = properties.size

        size *= (1f - properties.pressureSizeScale + properties.pressureSizeScale * pressure)

        // Tilting spreads the contact patch. A reed laid over covers more paper than one held
        // upright at identical pressure, and so does a marker or a pencil on its side.
        if (properties.tiltSizeScale != 0f) {
            size *= (1f + properties.tiltSizeScale * StylusResponse.tiltFactor(tilt))
        }

        // Velocity shrinks the brush toward velocitySizeMin as the stroke speeds up
        // (velocity is in px/ms; ~2.5 px/ms is treated as "fast")
        val velocityFactor = (velocity / 2.5f).coerceIn(0f, 1f)
        size *= (1f - (1f - properties.velocitySizeMin) * velocityFactor)

        if (properties.sizeJitter > 0f) {
            size *= (1f + (rng.nextFloat() - 0.5f) * properties.sizeJitter)
        }

        return size.coerceAtLeast(0.5f)
    }
}
