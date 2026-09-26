package com.procreate.android.urban.measurement

import com.procreate.android.urban.model.DistanceUnit
import java.text.NumberFormat
import java.util.Locale

/** Locale-aware labels for canvas badges and the measurement properties panel. */
class MeasurementFormatter(
    private val locale: Locale = Locale.getDefault(),
    private val maximumFractionDigits: Int = 2
) {
    init {
        require(maximumFractionDigits in 0..8) {
            "maximumFractionDigits must be between 0 and 8"
        }
    }

    fun format(result: MeasurementResult, unit: DistanceUnit): String = when (result) {
        is MeasurementResult.Length -> formatLength(result.totalMeters, unit)
        is MeasurementResult.Area -> formatArea(result.squareMeters, unit)
    }

    fun formatLength(meters: Double, unit: DistanceUnit): String {
        requireNonNegativeFinite(meters)
        val value = meters / unit.toMetersMultiplier.toDouble()
        return "${formatNumber(value)} ${unit.symbol}"
    }

    fun formatArea(squareMeters: Double, unit: DistanceUnit): String {
        requireNonNegativeFinite(squareMeters)
        val metersPerUnit = unit.toMetersMultiplier.toDouble()
        val value = squareMeters / (metersPerUnit * metersPerUnit)
        return "${formatNumber(value)} ${unit.symbol}\u00B2"
    }

    fun formatPerimeter(result: MeasurementResult.Area, unit: DistanceUnit): String =
        formatLength(result.perimeterMeters, unit)

    private fun formatNumber(value: Double): String {
        val normalized = if (value == -0.0) 0.0 else value
        return NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = 0
            maximumFractionDigits = this@MeasurementFormatter.maximumFractionDigits
        }.format(normalized)
    }

    private fun requireNonNegativeFinite(value: Double) {
        require(value.isFinite() && value >= 0.0) {
            "Measurement value must be finite and non-negative"
        }
    }
}
