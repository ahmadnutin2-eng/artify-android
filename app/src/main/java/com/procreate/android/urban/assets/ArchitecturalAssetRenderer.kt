package com.procreate.android.urban.assets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Maps scale-independent, metre-based asset positions to Android Canvas pixels. */
data class AssetViewport(
    val pixelsPerMeter: Float,
    val originXPx: Float = 0f,
    val originYPx: Float = 0f,
    val invertYAxis: Boolean = false
) {
    init {
        require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0f) {
            "pixelsPerMeter must be finite and positive"
        }
        require(originXPx.isFinite() && originYPx.isFinite()) { "Viewport origin must be finite" }
    }

    fun canvasX(xMeters: Double): Float = originXPx + xMeters.toFloat() * pixelsPerMeter

    fun canvasY(yMeters: Double): Float = originYPx +
        yMeters.toFloat() * pixelsPerMeter * if (invertYAxis) -1f else 1f

    fun canvasRotation(worldRotationDegrees: Double): Float =
        worldRotationDegrees.toFloat() * if (invertYAxis) -1f else 1f
}

data class AssetRenderOptions(
    val isSelected: Boolean = false,
    val selectionColorArgb: Int = 0xFF1976D2.toInt(),
    val minimumVisibleSizePx: Float = 3f
) {
    init {
        require(minimumVisibleSizePx.isFinite() && minimumVisibleSizePx > 0f) {
            "minimumVisibleSizePx must be finite and positive"
        }
    }
}

/**
 * Canvas implementation for [ProceduralAssetSymbol]. All geometry is generated here from paths,
 * lines, circles and rounded rectangles, so the starter library has no external asset license.
 */
class ArchitecturalAssetRenderer(
    private val catalog: ArchitecturalAssetCatalog = BuiltInArchitecturalAssets.catalog
) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    /** Returns false for a hidden instance or an id not present in this renderer's catalog. */
    fun render(
        canvas: Canvas,
        instance: AssetInstance,
        viewport: AssetViewport,
        options: AssetRenderOptions = AssetRenderOptions()
    ): Boolean {
        if (!instance.isVisible) return false
        val metadata = catalog[instance.assetId] ?: return false
        render(canvas, metadata, instance, viewport, options)
        return true
    }

    fun render(
        canvas: Canvas,
        metadata: ArchitecturalAssetMetadata,
        instance: AssetInstance,
        viewport: AssetViewport,
        options: AssetRenderOptions = AssetRenderOptions()
    ) {
        require(instance.assetId == metadata.id) {
            "Instance ${instance.id} refers to ${instance.assetId}, not metadata ${metadata.id}"
        }
        if (!instance.isVisible) return

        val dimensions = instance.dimensionsMeters(metadata)
        val widthPx = (dimensions.width * viewport.pixelsPerMeter).toFloat()
            .coerceAtLeast(options.minimumVisibleSizePx)
        val heightPx = (dimensions.height * viewport.pixelsPerMeter).toFloat()
            .coerceAtLeast(options.minimumVisibleSizePx)
        val color = applyOpacity(instance.resolvedColor(metadata), instance.opacity)
        val checkpoint = canvas.save()
        try {
            canvas.translate(viewport.canvasX(instance.xMeters), viewport.canvasY(instance.yMeters))
            canvas.rotate(viewport.canvasRotation(instance.normalizedRotationDegrees))
            configurePaints(color, min(widthPx, heightPx))
            when (metadata.symbol) {
                ProceduralAssetSymbol.DECIDUOUS_TREE -> drawDeciduousTree(canvas, widthPx, heightPx, color)
                ProceduralAssetSymbol.PALM_TREE -> drawPalmTree(canvas, widthPx, heightPx, color)
                ProceduralAssetSymbol.STREET_TREE -> drawStreetTree(canvas, widthPx, heightPx, color)
                ProceduralAssetSymbol.PERSON_STANDING -> drawStandingPerson(canvas, widthPx, heightPx, color)
                ProceduralAssetSymbol.PERSON_WALKING -> drawWalkingPerson(canvas, widthPx, heightPx, color)
                ProceduralAssetSymbol.PERSON_WHEELCHAIR -> drawWheelchairPerson(canvas, widthPx, heightPx, color)
                ProceduralAssetSymbol.CAR_SEDAN -> drawVehicle(canvas, widthPx, heightPx, color, VehicleBody.SEDAN)
                ProceduralAssetSymbol.CAR_SUV -> drawVehicle(canvas, widthPx, heightPx, color, VehicleBody.SUV)
                ProceduralAssetSymbol.CAR_PICKUP -> drawVehicle(canvas, widthPx, heightPx, color, VehicleBody.PICKUP)
            }
            if (options.isSelected) drawSelection(canvas, widthPx, heightPx, options.selectionColorArgb)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun configurePaints(color: Int, shortSidePx: Float) {
        fillPaint.color = color
        strokePaint.color = darken(color, 0.32f)
        strokePaint.strokeWidth = (shortSidePx * 0.045f).coerceIn(1f, 5f)
        detailPaint.color = darken(color, 0.18f)
        detailPaint.strokeWidth = (shortSidePx * 0.026f).coerceIn(1f, 3.5f)
    }

    private fun drawDeciduousTree(canvas: Canvas, width: Float, height: Float, color: Int) {
        val canopy = Path()
        val lobes = 20
        for (index in 0 until lobes) {
            val angle = -Math.PI / 2.0 + index * Math.PI * 2.0 / lobes
            val radius = if (index % 2 == 0) 0.48 else 0.405
            val x = cos(angle).toFloat() * width * radius.toFloat()
            val y = sin(angle).toFloat() * height * radius.toFloat()
            if (index == 0) canopy.moveTo(x, y) else canopy.lineTo(x, y)
        }
        canopy.close()
        fillPaint.color = withAlpha(lighten(color, 0.08f), Color.alpha(color))
        canvas.drawPath(canopy, fillPaint)
        canvas.drawPath(canopy, strokePaint)

        detailPaint.color = withAlpha(darken(color, 0.12f), Color.alpha(color))
        canvas.drawCircle(0f, 0f, min(width, height) * 0.31f, detailPaint)
        fillPaint.color = withAlpha(0xFF76533A.toInt(), Color.alpha(color))
        canvas.drawCircle(0f, 0f, min(width, height) * 0.055f, fillPaint)
    }

    private fun drawPalmTree(canvas: Canvas, width: Float, height: Float, color: Int) {
        val frondPaint = detailPaint.apply {
            this.color = color
            strokeWidth = (min(width, height) * 0.055f).coerceIn(1.2f, 5f)
        }
        repeat(10) { index ->
            val angle = -Math.PI / 2.0 + index * Math.PI * 2.0 / 10.0
            val endX = cos(angle).toFloat() * width * 0.48f
            val endY = sin(angle).toFloat() * height * 0.48f
            val perpendicularX = -sin(angle).toFloat() * width * if (index % 2 == 0) 0.07f else -0.07f
            val perpendicularY = cos(angle).toFloat() * height * if (index % 2 == 0) 0.07f else -0.07f
            val path = Path().apply {
                moveTo(0f, 0f)
                cubicTo(
                    endX * 0.28f + perpendicularX,
                    endY * 0.28f + perpendicularY,
                    endX * 0.72f + perpendicularX,
                    endY * 0.72f + perpendicularY,
                    endX,
                    endY
                )
            }
            canvas.drawPath(path, frondPaint)
        }
        fillPaint.color = withAlpha(0xFF8A633F.toInt(), Color.alpha(color))
        canvas.drawCircle(0f, 0f, min(width, height) * 0.09f, fillPaint)
        strokePaint.color = darken(color, 0.28f)
        canvas.drawCircle(0f, 0f, min(width, height) * 0.12f, strokePaint)
    }

    private fun drawStreetTree(canvas: Canvas, width: Float, height: Float, color: Int) {
        val radius = min(width, height) * 0.46f
        fillPaint.color = withAlpha(lighten(color, 0.14f), (Color.alpha(color) * 0.88f).toInt())
        canvas.drawCircle(0f, 0f, radius, fillPaint)
        canvas.drawCircle(0f, 0f, radius, strokePaint)
        detailPaint.color = withAlpha(darken(color, 0.08f), Color.alpha(color))
        canvas.drawCircle(0f, 0f, radius * 0.66f, detailPaint)
        repeat(8) { index ->
            val angle = index * Math.PI * 2.0 / 8.0
            canvas.drawLine(
                0f,
                0f,
                cos(angle).toFloat() * radius * 0.83f,
                sin(angle).toFloat() * radius * 0.83f,
                detailPaint
            )
        }
        fillPaint.color = withAlpha(0xFF785439.toInt(), Color.alpha(color))
        canvas.drawCircle(0f, 0f, radius * 0.1f, fillPaint)
    }

    private fun drawStandingPerson(canvas: Canvas, width: Float, height: Float, color: Int) {
        val shortSide = min(width, height)
        fillPaint.color = color
        canvas.drawOval(
            RectF(-width * 0.42f, -height * 0.19f, width * 0.42f, height * 0.34f),
            fillPaint
        )
        canvas.drawCircle(0f, -height * 0.27f, shortSide * 0.18f, fillPaint)
        strokePaint.color = darken(color, 0.32f)
        canvas.drawOval(
            RectF(-width * 0.42f, -height * 0.19f, width * 0.42f, height * 0.34f),
            strokePaint
        )
        canvas.drawCircle(0f, -height * 0.27f, shortSide * 0.18f, strokePaint)
    }

    private fun drawWalkingPerson(canvas: Canvas, width: Float, height: Float, color: Int) {
        val unit = min(width, height)
        fillPaint.color = color
        canvas.drawCircle(0f, -height * 0.24f, unit * 0.16f, fillPaint)
        detailPaint.color = color
        detailPaint.strokeWidth = (unit * 0.15f).coerceAtLeast(1.4f)
        canvas.drawLine(0f, -height * 0.06f, 0f, height * 0.18f, detailPaint)
        canvas.drawLine(0f, 0f, -width * 0.34f, height * 0.03f, detailPaint)
        canvas.drawLine(0f, 0f, width * 0.32f, -height * 0.02f, detailPaint)
        canvas.drawLine(0f, height * 0.17f, -width * 0.27f, height * 0.42f, detailPaint)
        canvas.drawLine(0f, height * 0.17f, width * 0.34f, height * 0.36f, detailPaint)
    }

    private fun drawWheelchairPerson(canvas: Canvas, width: Float, height: Float, color: Int) {
        val unit = min(width, height)
        strokePaint.color = color
        strokePaint.strokeWidth = (unit * 0.075f).coerceIn(1.3f, 5f)
        canvas.drawCircle(0f, height * 0.07f, unit * 0.38f, strokePaint)
        fillPaint.color = color
        canvas.drawCircle(-width * 0.08f, -height * 0.23f, unit * 0.11f, fillPaint)
        detailPaint.color = color
        detailPaint.strokeWidth = (unit * 0.09f).coerceIn(1.3f, 5f)
        canvas.drawLine(-width * 0.06f, -height * 0.1f, width * 0.08f, height * 0.09f, detailPaint)
        canvas.drawLine(width * 0.06f, height * 0.08f, width * 0.3f, height * 0.08f, detailPaint)
        canvas.drawLine(width * 0.27f, height * 0.08f, width * 0.39f, height * 0.32f, detailPaint)
    }

    private enum class VehicleBody { SEDAN, SUV, PICKUP }

    private fun drawVehicle(
        canvas: Canvas,
        width: Float,
        height: Float,
        color: Int,
        body: VehicleBody
    ) {
        val bodyRect = RectF(-width * 0.47f, -height * 0.48f, width * 0.47f, height * 0.48f)
        fillPaint.color = color
        canvas.drawRoundRect(bodyRect, width * 0.18f, width * 0.18f, fillPaint)
        canvas.drawRoundRect(bodyRect, width * 0.18f, width * 0.18f, strokePaint)

        val cabinTop = when (body) {
            VehicleBody.SEDAN -> -height * 0.20f
            VehicleBody.SUV -> -height * 0.28f
            VehicleBody.PICKUP -> -height * 0.31f
        }
        val cabinBottom = when (body) {
            VehicleBody.SEDAN -> height * 0.22f
            VehicleBody.SUV -> height * 0.27f
            VehicleBody.PICKUP -> height * 0.03f
        }
        fillPaint.color = withAlpha(lighten(color, 0.45f), Color.alpha(color))
        val cabin = RectF(-width * 0.34f, cabinTop, width * 0.34f, cabinBottom)
        canvas.drawRoundRect(cabin, width * 0.12f, width * 0.12f, fillPaint)
        detailPaint.color = withAlpha(darken(color, 0.25f), Color.alpha(color))
        canvas.drawRoundRect(cabin, width * 0.12f, width * 0.12f, detailPaint)
        canvas.drawLine(-width * 0.34f, 0f, width * 0.34f, 0f, detailPaint)

        if (body == VehicleBody.PICKUP) {
            fillPaint.color = withAlpha(darken(color, 0.12f), Color.alpha(color))
            val bed = RectF(-width * 0.35f, height * 0.1f, width * 0.35f, height * 0.4f)
            canvas.drawRect(bed, fillPaint)
            canvas.drawRect(bed, detailPaint)
        }

        fillPaint.color = withAlpha(Color.rgb(38, 43, 47), Color.alpha(color))
        val wheelWidth = width * 0.1f
        val wheelHeight = height * 0.13f
        listOf(-height * 0.29f, height * 0.29f).forEach { centerY ->
            canvas.drawRoundRect(
                RectF(-width * 0.53f, centerY - wheelHeight / 2f, -width * 0.53f + wheelWidth, centerY + wheelHeight / 2f),
                wheelWidth * 0.25f,
                wheelWidth * 0.25f,
                fillPaint
            )
            canvas.drawRoundRect(
                RectF(width * 0.53f - wheelWidth, centerY - wheelHeight / 2f, width * 0.53f, centerY + wheelHeight / 2f),
                wheelWidth * 0.25f,
                wheelWidth * 0.25f,
                fillPaint
            )
        }

        detailPaint.color = withAlpha(lighten(color, 0.62f), Color.alpha(color))
        canvas.drawLine(-width * 0.29f, -height * 0.43f, width * 0.29f, -height * 0.43f, detailPaint)
        canvas.drawLine(-width * 0.29f, height * 0.43f, width * 0.29f, height * 0.43f, detailPaint)
    }

    private fun drawSelection(canvas: Canvas, width: Float, height: Float, color: Int) {
        selectionPaint.color = color
        canvas.drawRect(
            RectF(-width / 2f - 5f, -height / 2f - 5f, width / 2f + 5f, height / 2f + 5f),
            selectionPaint
        )
        fillPaint.color = color
        val handleRadius = 4.5f
        canvas.drawCircle(-width / 2f - 5f, -height / 2f - 5f, handleRadius, fillPaint)
        canvas.drawCircle(width / 2f + 5f, -height / 2f - 5f, handleRadius, fillPaint)
        canvas.drawCircle(-width / 2f - 5f, height / 2f + 5f, handleRadius, fillPaint)
        canvas.drawCircle(width / 2f + 5f, height / 2f + 5f, handleRadius, fillPaint)
    }

    private fun applyOpacity(color: Int, opacity: Float): Int =
        withAlpha(color, (Color.alpha(color) * opacity).toInt().coerceIn(0, 255))

    private fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        fun channel(start: Int, end: Int): Int = (start + (end - start) * fraction).toInt().coerceIn(0, 255)
        return Color.argb(
            Color.alpha(from),
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to))
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
