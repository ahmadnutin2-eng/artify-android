package com.procreate.android.urban.measurement

import com.procreate.android.urban.model.DistanceUnit

/**
 * A completed measurement annotation stored in document coordinates.
 *
 * Real-world values are intentionally not cached here. They are derived from the project's
 * current scale, so recalibrating [com.procreate.android.urban.model.UrbanScaleConfig] updates
 * every measurement consistently.
 */
data class MeasurementElement(
    val id: String,
    val kind: MeasurementKind,
    val points: List<MeasurementPoint>,
    val displayUnit: DistanceUnit = DistanceUnit.METER,
    val label: String? = null
) {
    init {
        require(id.isNotBlank()) { "Measurement id must not be blank" }
        require(points.distinct().size >= kind.minimumPointCount) {
            "${kind.name} requires at least ${kind.minimumPointCount} distinct points"
        }
    }
}
