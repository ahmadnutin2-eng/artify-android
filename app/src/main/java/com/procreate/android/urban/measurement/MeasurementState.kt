package com.procreate.android.urban.measurement

import com.procreate.android.urban.model.DistanceUnit

/**
 * Immutable in-progress tool state. Keeping transitions immutable makes one measurement gesture
 * easy to snapshot for undo/redo without leaking mutable point lists into the view layer.
 */
data class MeasurementState(
    val kind: MeasurementKind = MeasurementKind.POLYLINE_LENGTH,
    val displayUnit: DistanceUnit = DistanceUnit.METER,
    val points: List<MeasurementPoint> = emptyList(),
    val previewPoint: MeasurementPoint? = null
) {
    val canFinish: Boolean
        get() = points.distinct().size >= kind.minimumPointCount

    /** Points used for a live preview; the hovering point is never committed implicitly. */
    val previewPoints: List<MeasurementPoint>
        get() = previewPoint?.let { points + it } ?: points

    fun addPoint(point: MeasurementPoint): MeasurementState {
        val newPoints = if (points.lastOrNull() == point) points else points + point
        return copy(points = newPoints, previewPoint = null)
    }

    fun updatePreview(point: MeasurementPoint?): MeasurementState = copy(previewPoint = point)

    fun removeLastPoint(): MeasurementState = copy(
        points = if (points.isEmpty()) points else points.dropLast(1),
        previewPoint = null
    )

    fun changeUnit(unit: DistanceUnit): MeasurementState = copy(displayUnit = unit)

    /** Switching length/area starts a fresh gesture and cannot reinterpret unfinished geometry. */
    fun changeKind(newKind: MeasurementKind): MeasurementState =
        if (newKind == kind) this else MeasurementState(kind = newKind, displayUnit = displayUnit)

    /** Clears the current gesture while retaining the user's selected type and unit. */
    fun reset(): MeasurementState = MeasurementState(kind = kind, displayUnit = displayUnit)

    fun finish(id: String, label: String? = null): MeasurementElement {
        check(canFinish) {
            "${kind.name} requires at least ${kind.minimumPointCount} distinct committed points"
        }
        return MeasurementElement(
            id = id,
            kind = kind,
            points = points.toList(),
            displayUnit = displayUnit,
            label = label
        )
    }
}
