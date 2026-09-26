package com.procreate.android.export.dxf

import com.procreate.android.urban.measurement.MeasurementCalculator
import com.procreate.android.urban.measurement.MeasurementElement
import com.procreate.android.urban.measurement.MeasurementFormatter
import com.procreate.android.urban.measurement.MeasurementKind
import com.procreate.android.urban.measurement.MeasurementPoint
import com.procreate.android.urban.measurement.MeasurementResult
import com.procreate.android.urban.measurement.MeasurementScale
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

/** CAD appearance for editable measurement annotations. Every length is in model-space metres. */
data class MeasurementDxfStyle(
    val colorArgb: Int = 0xFF1565C0.toInt(),
    val textHeightMeters: Double = 0.30,
    val tickLengthMeters: Double = 0.25,
    val lineWidthMeters: Double = 0.02
) {
    init {
        require(textHeightMeters.isFinite() && textHeightMeters > 0.0) {
            "DXF measurement text height must be finite and positive"
        }
        require(tickLengthMeters.isFinite() && tickLengthMeters > 0.0) {
            "DXF measurement tick length must be finite and positive"
        }
        require(lineWidthMeters.isFinite() && lineWidthMeters >= 0.0) {
            "DXF measurement line width must be finite and non-negative"
        }
    }
}

/**
 * Converts a measurement stored in canvas pixels into editable, 1:1 DXF model-space geometry.
 *
 * The annotation value is always recalculated through [MeasurementCalculator] at export time. A
 * stale cached label can therefore never disagree with the geometry after the project scale is
 * recalibrated. Canvas Y grows down; every emitted coordinate is reflected to CAD's Y-up space.
 */
object MeasurementDxfExporter {
    const val LENGTH_LAYER = "DIMENSIONS_LENGTH"
    const val AREA_LAYER = "DIMENSIONS_AREA"

    val requiredLayers: Set<String> = setOf(LENGTH_LAYER, AREA_LAYER)

    fun export(
        element: MeasurementElement,
        pixelsPerMeter: Double,
        originOffsetXPixels: Double = 0.0,
        originOffsetYPixels: Double = 0.0,
        style: MeasurementDxfStyle = MeasurementDxfStyle()
    ): List<DxfEntity> {
        val scale = MeasurementScale.fromPixelsPerMeter(pixelsPerMeter)
        require(originOffsetXPixels.isFinite() && originOffsetYPixels.isFinite()) {
            "DXF measurement origin offsets must be finite"
        }

        val sourcePoints = when (element.kind) {
            MeasurementKind.POLYLINE_LENGTH -> element.points
            MeasurementKind.POLYGON_AREA -> element.points.withoutRepeatedClosingPoint()
        }
        val points = sourcePoints.map { point ->
            point.toDxf(pixelsPerMeter, originOffsetXPixels, originOffsetYPixels)
        }
        val result = MeasurementCalculator.calculate(element, scale)
        val layer = when (element.kind) {
            MeasurementKind.POLYLINE_LENGTH -> LENGTH_LAYER
            MeasurementKind.POLYGON_AREA -> AREA_LAYER
        }
        val rgb = resolveRgb(style.colorArgb)
        val aci = nearestAci(rgb)
        val entities = mutableListOf<DxfEntity>()

        entities += DxfEntity.LwPolyline(
            layer = layer,
            rgb = rgb,
            aci = aci,
            points = points,
            closed = element.kind == MeasurementKind.POLYGON_AREA,
            constantWidth = style.lineWidthMeters
        )

        when (result) {
            is MeasurementResult.Length -> {
                points.forEachIndexed { index, point ->
                    tickAt(points, index, style.tickLengthMeters)?.let { (start, end) ->
                        entities += DxfEntity.Line(layer, rgb, aci, start, end)
                    }
                }
                val anchor = polylineMidpoint(points)
                entities += DxfEntity.Text(
                    layer = layer,
                    rgb = rgb,
                    aci = aci,
                    position = anchor.point,
                    height = style.textHeightMeters,
                    text = result.annotationText(element),
                    rotationDeg = readableTextAngle(anchor.rotationDegrees)
                )
            }

            is MeasurementResult.Area -> entities += DxfEntity.Text(
                layer = layer,
                rgb = rgb,
                aci = aci,
                position = polygonCentroid(points),
                height = style.textHeightMeters,
                text = result.annotationText(element)
            )
        }

        return entities
    }

    private fun MeasurementResult.annotationText(element: MeasurementElement): String {
        val measuredValue = MeasurementFormatter(Locale.US, maximumFractionDigits = 2)
            .format(this, element.displayUnit)
        return element.label?.trim()?.takeIf(String::isNotEmpty)?.let { "$it - $measuredValue" }
            ?: measuredValue
    }

    private fun MeasurementPoint.toDxf(
        pixelsPerMeter: Double,
        originOffsetXPixels: Double,
        originOffsetYPixels: Double
    ): DxfPoint = DxfPoint(
        x = (x - originOffsetXPixels) / pixelsPerMeter,
        y = -(y - originOffsetYPixels) / pixelsPerMeter
    )

    private fun List<MeasurementPoint>.withoutRepeatedClosingPoint(): List<MeasurementPoint> =
        if (size > 1 && first() == last()) dropLast(1) else this

    private data class TextAnchor(val point: DxfPoint, val rotationDegrees: Double)

    private fun polylineMidpoint(points: List<DxfPoint>): TextAnchor {
        val segments = points.zipWithNext().map { (start, end) ->
            Triple(start, end, hypot(end.x - start.x, end.y - start.y))
        }
        val totalLength = segments.sumOf { it.third }
        if (totalLength <= 0.0) return TextAnchor(points.first(), 0.0)

        val target = totalLength / 2.0
        var traversed = 0.0
        for ((start, end, length) in segments) {
            if (length <= 0.0) continue
            if (traversed + length >= target) {
                val fraction = (target - traversed) / length
                return TextAnchor(
                    point = DxfPoint(
                        start.x + (end.x - start.x) * fraction,
                        start.y + (end.y - start.y) * fraction
                    ),
                    rotationDegrees = Math.toDegrees(atan2(end.y - start.y, end.x - start.x))
                )
            }
            traversed += length
        }
        val last = segments.last()
        return TextAnchor(last.second, Math.toDegrees(atan2(
            last.second.y - last.first.y,
            last.second.x - last.first.x
        )))
    }

    private fun tickAt(
        points: List<DxfPoint>,
        index: Int,
        lengthMeters: Double
    ): Pair<DxfPoint, DxfPoint>? {
        val point = points[index]
        val previous = (index - 1 downTo 0).asSequence()
            .map(points::get)
            .firstOrNull { it != point }
        val next = (index + 1 until points.size).asSequence()
            .map(points::get)
            .firstOrNull { it != point }
        val tangentX: Double
        val tangentY: Double
        when {
            previous != null && next != null -> {
                tangentX = next.x - previous.x
                tangentY = next.y - previous.y
            }
            next != null -> {
                tangentX = next.x - point.x
                tangentY = next.y - point.y
            }
            previous != null -> {
                tangentX = point.x - previous.x
                tangentY = point.y - previous.y
            }
            else -> return null
        }
        val tangentLength = hypot(tangentX, tangentY)
        if (tangentLength <= 0.0) return null
        val half = lengthMeters / 2.0
        val perpendicularX = -tangentY / tangentLength * half
        val perpendicularY = tangentX / tangentLength * half
        return DxfPoint(point.x - perpendicularX, point.y - perpendicularY) to
            DxfPoint(point.x + perpendicularX, point.y + perpendicularY)
    }

    /** Area-weighted centroid with a safe mean fallback for degenerate polygons. */
    private fun polygonCentroid(points: List<DxfPoint>): DxfPoint {
        var twiceArea = 0.0
        var xNumerator = 0.0
        var yNumerator = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val cross = current.x * next.y - next.x * current.y
            twiceArea += cross
            xNumerator += (current.x + next.x) * cross
            yNumerator += (current.y + next.y) * cross
        }
        if (kotlin.math.abs(twiceArea) <= 1e-12) {
            return DxfPoint(points.sumOf(DxfPoint::x) / points.size, points.sumOf(DxfPoint::y) / points.size)
        }
        return DxfPoint(xNumerator / (3.0 * twiceArea), yNumerator / (3.0 * twiceArea))
    }

    private fun readableTextAngle(degrees: Double): Double {
        var angle = ((degrees % 360.0) + 360.0) % 360.0
        if (angle > 180.0) angle -= 360.0
        if (angle > 90.0) angle -= 180.0
        if (angle < -90.0) angle += 180.0
        return angle
    }

    private fun resolveRgb(argb: Int): Int = argb and 0x00FFFFFF

    private fun nearestAci(rgb: Int): Int {
        val red = rgb ushr 16 and 0xFF
        val green = rgb ushr 8 and 0xFF
        val blue = rgb and 0xFF
        val candidates = listOf(
            1 to intArrayOf(255, 0, 0), 2 to intArrayOf(255, 255, 0),
            3 to intArrayOf(0, 255, 0), 4 to intArrayOf(0, 255, 255),
            5 to intArrayOf(0, 0, 255), 6 to intArrayOf(255, 0, 255),
            7 to intArrayOf(255, 255, 255), 8 to intArrayOf(128, 128, 128),
            9 to intArrayOf(192, 192, 192)
        )
        return candidates.minByOrNull { (_, candidate) ->
            val deltaRed = red - candidate[0]
            val deltaGreen = green - candidate[1]
            val deltaBlue = blue - candidate[2]
            deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue
        }?.first ?: 7
    }
}
