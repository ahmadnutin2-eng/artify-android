package com.procreate.android.urban.measurement

/** The two geometry types supported by the measurement tool. */
enum class MeasurementKind(val minimumPointCount: Int) {
    POLYLINE_LENGTH(minimumPointCount = 2),
    POLYGON_AREA(minimumPointCount = 3)
}

/**
 * A point in document/canvas pixel coordinates.
 *
 * It deliberately does not depend on [android.graphics.PointF], which keeps measurement
 * calculations deterministic and directly testable on the JVM.
 */
data class MeasurementPoint(
    val x: Double,
    val y: Double
) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "Measurement point coordinates must be finite"
        }
    }
}
