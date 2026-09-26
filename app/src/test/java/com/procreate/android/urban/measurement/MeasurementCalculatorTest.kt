package com.procreate.android.urban.measurement

import com.procreate.android.urban.model.DistanceUnit
import com.procreate.android.urban.model.UrbanScaleConfig
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementCalculatorTest {

    @Test
    fun `multi-point length sums every segment and applies project scale`() {
        val element = MeasurementElement(
            id = "route-1",
            kind = MeasurementKind.POLYLINE_LENGTH,
            points = listOf(point(0, 0), point(30, 40), point(30, 140))
        )

        val result = MeasurementCalculator.calculate(
            element,
            MeasurementScale.fromPixelsPerMeter(10.0)
        ) as MeasurementResult.Length

        assertEquals(15.0, result.totalMeters, EPSILON)
        assertEquals(listOf(5.0, 10.0), result.segmentMeters)
    }

    @Test
    fun `polygon area and perimeter are converted using pixels per meter`() {
        val element = MeasurementElement(
            id = "plot-1",
            kind = MeasurementKind.POLYGON_AREA,
            points = listOf(point(0, 0), point(200, 0), point(200, 100), point(0, 100))
        )

        val result = MeasurementCalculator.calculate(
            element,
            MeasurementScale.fromPixelsPerMeter(10.0)
        ) as MeasurementResult.Area

        assertEquals(200.0, result.squareMeters, EPSILON)
        assertEquals(60.0, result.perimeterMeters, EPSILON)
    }

    @Test
    fun `calculator reads pixels per meter from Urban scale config`() {
        val element = MeasurementElement(
            id = "line",
            kind = MeasurementKind.POLYLINE_LENGTH,
            points = listOf(point(0, 0), point(100, 0))
        )
        val config = UrbanScaleConfig(pixelsPerMeter = 5f)

        val before = MeasurementCalculator.calculate(element, config) as MeasurementResult.Length
        config.pixelsPerMeter = 10f
        val after = MeasurementCalculator.calculate(element, config) as MeasurementResult.Length

        assertEquals(20.0, before.totalMeters, EPSILON)
        assertEquals(10.0, after.totalMeters, EPSILON)
    }

    @Test
    fun `closed and open polygon point lists produce the same result`() {
        val open = listOf(point(0, 0), point(10, 0), point(10, 10), point(0, 10))
        val closed = open + open.first()

        assertEquals(
            MeasurementCalculator.polygonAreaSquarePixels(open),
            MeasurementCalculator.polygonAreaSquarePixels(closed),
            EPSILON
        )
        assertEquals(
            MeasurementCalculator.polygonPerimeterPixels(open),
            MeasurementCalculator.polygonPerimeterPixels(closed),
            EPSILON
        )
    }

    @Test
    fun `translated shoelace calculation preserves area far from origin`() {
        val offset = 1_000_000_000.0
        val polygon = listOf(
            MeasurementPoint(offset, offset),
            MeasurementPoint(offset + 20.0, offset),
            MeasurementPoint(offset + 20.0, offset + 10.0),
            MeasurementPoint(offset, offset + 10.0)
        )

        assertEquals(200.0, MeasurementCalculator.polygonAreaSquarePixels(polygon), EPSILON)
    }

    @Test
    fun `invalid real-world scale is rejected instead of silently changing results`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementScale.fromPixelsPerMeter(0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementScale.fromPixelsPerMeter(Double.NaN)
        }
    }

    @Test
    fun `draft state enforces minimum committed points and remains immutable`() {
        val initial = MeasurementState(kind = MeasurementKind.POLYGON_AREA)
        val withTwo = initial.addPoint(point(0, 0)).addPoint(point(10, 0))
        val preview = withTwo.updatePreview(point(10, 10))

        assertTrue(initial.points.isEmpty())
        assertFalse(withTwo.canFinish)
        assertFalse(preview.canFinish)
        assertEquals(3, preview.previewPoints.size)

        val complete = preview.addPoint(point(10, 10))
        assertTrue(complete.canFinish)
        assertEquals(3, complete.finish("area").points.size)
    }

    @Test
    fun `formatter supports centimeter meter and kilometer values and squared units`() {
        val formatter = MeasurementFormatter(Locale.US, maximumFractionDigits = 3)

        assertEquals("125 cm", formatter.formatLength(1.25, DistanceUnit.CENTIMETER))
        assertEquals("1.25 m", formatter.formatLength(1.25, DistanceUnit.METER))
        assertEquals("0.001 km", formatter.formatLength(1.25, DistanceUnit.KILOMETER))
        assertEquals("10000 cm\u00B2", formatter.formatArea(1.0, DistanceUnit.CENTIMETER))
        assertEquals("1 km\u00B2", formatter.formatArea(1_000_000.0, DistanceUnit.KILOMETER))
    }

    private fun point(x: Int, y: Int) = MeasurementPoint(x.toDouble(), y.toDouble())

    private companion object {
        const val EPSILON = 1e-9
    }
}
