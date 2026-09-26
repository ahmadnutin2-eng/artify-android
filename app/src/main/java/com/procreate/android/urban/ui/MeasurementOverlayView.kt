package com.procreate.android.urban.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.procreate.android.R
import com.procreate.android.urban.measurement.MeasurementCalculator
import com.procreate.android.urban.measurement.MeasurementElement
import com.procreate.android.urban.measurement.MeasurementFormatter
import com.procreate.android.urban.measurement.MeasurementKind
import com.procreate.android.urban.measurement.MeasurementPoint
import com.procreate.android.urban.measurement.MeasurementScale
import com.procreate.android.urban.measurement.MeasurementState
import com.procreate.android.urban.model.UrbanScaleConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Transparent presentation layer for completed and in-progress architectural measurements.
 *
 * [MeasurementPoint] coordinates are document pixels. By default they map directly to this
 * view's canvas. A host that pans/zooms the document can supply the same matrix through
 * [updateDocumentTransform]; annotation strokes and labels remain a stable on-screen size.
 * This view is intentionally non-clickable so touch gestures continue to the drawing surface.
 */
class MeasurementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val completedElements = mutableListOf<MeasurementElement>()
    private var activePreview: MeasurementState? = null
    private var currentScale = UrbanScaleConfig()
    private val documentTransform = Matrix()
    private var transformScale = 1f

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val formatter = MeasurementFormatter(currentLocale(), maximumFractionDigits = 2)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 28, 28, 31)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }

    /** Primary annotation colour. Alpha for area fills is derived from this value. */
    var measurementColor: Int = ContextCompat.getColor(context, R.color.procreate_accent_light)
        set(value) {
            field = value
            invalidate()
        }

    /** Replaces completed annotations with an immutable snapshot. */
    fun submitMeasurements(elements: List<MeasurementElement>) {
        completedElements.clear()
        completedElements.addAll(elements)
        updateAccessibilityDescription()
        invalidate()
    }

    /** Shows or updates a live gesture; pass null to hide the preview. */
    fun showPreview(state: MeasurementState?) {
        activePreview = state
        invalidate()
    }

    fun clearPreview() = showPreview(null)

    /** Copies the mutable project config so a partial external mutation cannot affect one frame. */
    fun updateScale(config: UrbanScaleConfig) {
        currentScale = config.copy(
            scaleBarMapPosition = config.scaleBarMapPosition?.let { PointF(it.x, it.y) }
        )
        invalidate()
    }

    /**
     * Applies a document-pixel to overlay-view transformation. Passing null restores identity.
     * The matrix is copied and may therefore be reused or mutated safely by the caller.
     */
    fun updateDocumentTransform(transform: Matrix?) {
        if (transform == null) {
            documentTransform.reset()
        } else {
            documentTransform.set(transform)
        }
        transformScale = calculateDisplayScale(documentTransform)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (completedElements.isEmpty() && activePreview == null) return

        val checkpoint = canvas.save()
        canvas.concat(documentTransform)

        completedElements.forEach { element ->
            drawElement(canvas, element, isPreview = false)
        }
        activePreview?.let { drawPreview(canvas, it) }

        canvas.restoreToCount(checkpoint)
    }

    private fun drawElement(canvas: Canvas, element: MeasurementElement, isPreview: Boolean) {
        val result = runCatching {
            MeasurementCalculator.calculate(element, safeMeasurementScale())
        }.getOrNull() ?: return
        val calculatedLabel = formatter.format(result, element.displayUnit)
        val displayLabel = element.label
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { "$it · $calculatedLabel" }
            ?: calculatedLabel

        when (element.kind) {
            MeasurementKind.POLYLINE_LENGTH -> drawLength(
                canvas = canvas,
                points = element.points,
                label = displayLabel,
                isPreview = isPreview
            )

            MeasurementKind.POLYGON_AREA -> drawArea(
                canvas = canvas,
                points = element.points,
                label = displayLabel,
                isPreview = isPreview
            )
        }
    }

    private fun drawPreview(canvas: Canvas, state: MeasurementState) {
        val points = state.previewPoints
        if (points.isEmpty()) return

        val label = when (state.kind) {
            MeasurementKind.POLYLINE_LENGTH -> if (points.distinct().size >= 2) {
                val meters = safeMeasurementScale().pixelsToMeters(
                    MeasurementCalculator.polylineLengthPixels(points)
                )
                formatter.formatLength(meters, state.displayUnit)
            } else {
                null
            }

            MeasurementKind.POLYGON_AREA -> if (points.distinct().size >= 3) {
                val squareMeters = safeMeasurementScale().squarePixelsToSquareMeters(
                    MeasurementCalculator.polygonAreaSquarePixels(points)
                )
                formatter.formatArea(squareMeters, state.displayUnit)
            } else {
                null
            }
        }

        when (state.kind) {
            MeasurementKind.POLYLINE_LENGTH -> drawLength(canvas, points, label, isPreview = true)
            MeasurementKind.POLYGON_AREA -> drawArea(canvas, points, label, isPreview = true)
        }
    }

    private fun drawLength(
        canvas: Canvas,
        points: List<MeasurementPoint>,
        label: String?,
        isPreview: Boolean
    ) {
        if (points.isEmpty()) return
        configureLinePaint(isPreview)

        if (points.size >= 2) {
            val path = buildPath(points, close = false)
            canvas.drawPath(path, linePaint)
            drawDimensionTicks(canvas, points)
        }
        drawHandles(canvas, points, isPreview)

        if (label != null && points.size >= 2) {
            val anchor = halfLengthPoint(points)
            drawBadge(canvas, anchor.x, anchor.y - screenDp(16f), label)
        }
    }

    private fun drawArea(
        canvas: Canvas,
        points: List<MeasurementPoint>,
        label: String?,
        isPreview: Boolean
    ) {
        if (points.isEmpty()) return
        configureLinePaint(isPreview)

        val canClose = points.distinct().size >= 3
        val path = buildPath(points, close = canClose)
        if (canClose) {
            fillPaint.color = withAlpha(measurementColor, if (isPreview) 28 else 48)
            canvas.drawPath(path, fillPaint)
        }
        if (points.size >= 2) canvas.drawPath(path, linePaint)
        drawHandles(canvas, points, isPreview)

        if (label != null && canClose) {
            val anchor = polygonCentroid(points)
            drawBadge(canvas, anchor.x, anchor.y, label)
        }
    }

    private fun configureLinePaint(isPreview: Boolean) {
        linePaint.color = withAlpha(measurementColor, if (isPreview) 205 else 255)
        linePaint.strokeWidth = screenDp(if (isPreview) 1.75f else 2.25f)
        linePaint.pathEffect = if (isPreview) {
            DashPathEffect(floatArrayOf(screenDp(8f), screenDp(5f)), 0f)
        } else {
            null
        }
    }

    private fun drawDimensionTicks(canvas: Canvas, points: List<MeasurementPoint>) {
        val tickHalf = screenDp(6f)
        points.forEachIndexed { index, point ->
            val neighbour = when (index) {
                0 -> points.getOrNull(1)
                points.lastIndex -> points.getOrNull(points.lastIndex - 1)
                else -> points[index + 1]
            } ?: return@forEachIndexed
            val dx = neighbour.x - point.x
            val dy = neighbour.y - point.y
            val length = hypot(dx, dy)
            if (length <= GEOMETRY_EPSILON) return@forEachIndexed
            val normalX = (-dy / length * tickHalf).toFloat()
            val normalY = (dx / length * tickHalf).toFloat()
            canvas.drawLine(
                point.x.toFloat() - normalX,
                point.y.toFloat() - normalY,
                point.x.toFloat() + normalX,
                point.y.toFloat() + normalY,
                linePaint
            )
        }
    }

    private fun drawHandles(
        canvas: Canvas,
        points: List<MeasurementPoint>,
        isPreview: Boolean
    ) {
        val outerRadius = screenDp(4.5f)
        val innerRadius = screenDp(2.5f)
        linePaint.pathEffect = null
        linePaint.strokeWidth = screenDp(1.5f)
        points.forEachIndexed { index, point ->
            val isHoverPoint = isPreview && index == points.lastIndex && activePreview?.previewPoint != null
            linePaint.color = withAlpha(measurementColor, if (isHoverPoint) 150 else 255)
            handleFillPaint.color = withAlpha(Color.WHITE, if (isHoverPoint) 190 else 255)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), outerRadius, handleFillPaint)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), innerRadius, linePaint)
        }
    }

    private fun drawBadge(canvas: Canvas, centerX: Float, centerY: Float, label: String) {
        badgeTextPaint.textSize = screenSp(12f)
        val horizontalPadding = screenDp(10f)
        val verticalPadding = screenDp(6f)
        val width = badgeTextPaint.measureText(label) + horizontalPadding * 2f
        val metrics = badgeTextPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val height = textHeight + verticalPadding * 2f
        val bounds = RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f
        )
        badgePaint.color = Color.argb(230, 28, 28, 31)
        canvas.drawRoundRect(bounds, screenDp(8f), screenDp(8f), badgePaint)

        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, centerX, baseline, badgeTextPaint)
    }

    private fun buildPath(points: List<MeasurementPoint>, close: Boolean): Path = Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points.first().x.toFloat(), points.first().y.toFloat())
        points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        if (close) close()
    }

    private fun halfLengthPoint(points: List<MeasurementPoint>): PointF {
        val lengths = MeasurementCalculator.segmentLengthsPixels(points)
        val total = lengths.sum()
        if (total <= GEOMETRY_EPSILON) {
            return PointF(points.first().x.toFloat(), points.first().y.toFloat())
        }
        val target = total / 2.0
        var traversed = 0.0
        lengths.forEachIndexed { index, segmentLength ->
            if (traversed + segmentLength >= target && segmentLength > GEOMETRY_EPSILON) {
                val fraction = (target - traversed) / segmentLength
                val start = points[index]
                val end = points[index + 1]
                return PointF(
                    (start.x + (end.x - start.x) * fraction).toFloat(),
                    (start.y + (end.y - start.y) * fraction).toFloat()
                )
            }
            traversed += segmentLength
        }
        return PointF(points.last().x.toFloat(), points.last().y.toFloat())
    }

    /** Area-weighted centroid with an average-point fallback for a degenerate polygon. */
    private fun polygonCentroid(points: List<MeasurementPoint>): PointF {
        var twiceArea = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        points.indices.forEach { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val cross = current.x * next.y - next.x * current.y
            twiceArea += cross
            weightedX += (current.x + next.x) * cross
            weightedY += (current.y + next.y) * cross
        }
        if (abs(twiceArea) > GEOMETRY_EPSILON) {
            return PointF(
                (weightedX / (3.0 * twiceArea)).toFloat(),
                (weightedY / (3.0 * twiceArea)).toFloat()
            )
        }
        return PointF(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        )
    }

    private fun safeMeasurementScale(): MeasurementScale {
        val ppm = currentScale.pixelsPerMeter.toDouble()
        return MeasurementScale.fromPixelsPerMeter(
            if (ppm.isFinite() && ppm > 0.0) ppm else 1.0
        )
    }

    private fun screenDp(value: Float): Float = value * density / transformScale

    private fun screenSp(value: Float): Float = value * scaledDensity / transformScale

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun calculateDisplayScale(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        val determinant = values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y] -
            values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y]
        val scale = sqrt(abs(determinant))
        return if (scale.isFinite() && scale > 0.0001f) scale else 1f
    }

    private fun updateAccessibilityDescription() {
        contentDescription = when (completedElements.size) {
            0 -> "طبقة القياسات، لا توجد قياسات"
            1 -> "طبقة القياسات، قياس واحد"
            2 -> "طبقة القياسات، قياسان"
            in 3..10 -> "طبقة القياسات، ${completedElements.size} قياسات"
            else -> "طبقة القياسات، ${completedElements.size} قياسًا"
        }
    }

    private fun currentLocale(): Locale = resources.configuration.locales[0]

    companion object {
        private const val GEOMETRY_EPSILON = 1e-9
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }
}
