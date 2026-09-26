package com.procreate.android.urban.measurement

import com.procreate.android.urban.model.UrbanScaleConfig
import kotlin.math.abs
import kotlin.math.hypot

/** A validated, immutable snapshot of the document's real-world scale. */
data class MeasurementScale private constructor(val pixelsPerMeter: Double) {
    fun pixelsToMeters(pixels: Double): Double = pixels / pixelsPerMeter

    fun squarePixelsToSquareMeters(squarePixels: Double): Double =
        squarePixels / (pixelsPerMeter * pixelsPerMeter)

    companion object {
        fun from(config: UrbanScaleConfig): MeasurementScale =
            fromPixelsPerMeter(config.pixelsPerMeter.toDouble())

        fun fromPixelsPerMeter(pixelsPerMeter: Double): MeasurementScale {
            require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0.0) {
                "pixelsPerMeter must be finite and greater than zero"
            }
            return MeasurementScale(pixelsPerMeter)
        }
    }
}

sealed interface MeasurementResult {
    val elementId: String

    data class Length(
        override val elementId: String,
        val totalMeters: Double,
        val segmentMeters: List<Double>
    ) : MeasurementResult

    data class Area(
        override val elementId: String,
        val squareMeters: Double,
        val perimeterMeters: Double
    ) : MeasurementResult
}

/** Pure geometry calculations shared by rendering, properties panels, persistence and export. */
object MeasurementCalculator {

    fun calculate(
        element: MeasurementElement,
        scaleConfig: UrbanScaleConfig
    ): MeasurementResult = calculate(element, MeasurementScale.from(scaleConfig))

    fun calculate(
        element: MeasurementElement,
        scale: MeasurementScale
    ): MeasurementResult = when (element.kind) {
        MeasurementKind.POLYLINE_LENGTH -> {
            val segmentPixels = segmentLengthsPixels(element.points)
            val segmentMeters = segmentPixels.map(scale::pixelsToMeters)
            MeasurementResult.Length(
                elementId = element.id,
                totalMeters = stableSum(segmentMeters),
                segmentMeters = segmentMeters
            )
        }

        MeasurementKind.POLYGON_AREA -> MeasurementResult.Area(
            elementId = element.id,
            squareMeters = scale.squarePixelsToSquareMeters(
                polygonAreaSquarePixels(element.points)
            ),
            perimeterMeters = scale.pixelsToMeters(polygonPerimeterPixels(element.points))
        )
    }

    /** Sum of every consecutive segment; suitable for two-point and multi-point dimensions. */
    fun polylineLengthPixels(points: List<MeasurementPoint>): Double =
        stableSum(segmentLengthsPixels(points))

    fun segmentLengthsPixels(points: List<MeasurementPoint>): List<Double> {
        if (points.size < 2) return emptyList()
        return points.zipWithNext { first, second -> distance(first, second) }
    }

    /**
     * Shoelace area for a simple polygon. Coordinates are translated to the first vertex before
     * cross products are evaluated, preserving precision for plans far from the document origin.
     * A repeated closing vertex is supported but not required.
     */
    fun polygonAreaSquarePixels(points: List<MeasurementPoint>): Double {
        if (points.size < 3) return 0.0
        val origin = points.first()
        var sum = 0.0
        var compensation = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val cross = (current.x - origin.x) * (next.y - origin.y) -
                (next.x - origin.x) * (current.y - origin.y)
            val adjusted = cross - compensation
            val updated = sum + adjusted
            compensation = (updated - sum) - adjusted
            sum = updated
        }
        return requireFinite(abs(sum) * 0.5)
    }

    fun polygonPerimeterPixels(points: List<MeasurementPoint>): Double {
        if (points.size < 2) return 0.0
        val openLength = polylineLengthPixels(points)
        val closingLength = if (points.first() == points.last()) {
            0.0
        } else {
            distance(points.last(), points.first())
        }
        return requireFinite(openLength + closingLength)
    }

    private fun distance(first: MeasurementPoint, second: MeasurementPoint): Double =
        requireFinite(hypot(second.x - first.x, second.y - first.y))

    /** Kahan summation avoids accumulating visible rounding error on long traced routes. */
    private fun stableSum(values: Iterable<Double>): Double {
        var sum = 0.0
        var compensation = 0.0
        values.forEach { value ->
            val adjusted = value - compensation
            val updated = sum + adjusted
            compensation = (updated - sum) - adjusted
            sum = updated
        }
        return requireFinite(sum)
    }

    private fun requireFinite(value: Double): Double {
        require(value.isFinite()) { "Measurement geometry is too large to calculate safely" }
        return value
    }
}
