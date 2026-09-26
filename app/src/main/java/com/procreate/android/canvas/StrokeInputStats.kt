package com.procreate.android.canvas

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pen input of the stroke under the pen, summarised so a QuickShape that replaces the stroke
 * is painted with the weight the artist was drawing with rather than a guess.
 *
 * Medians rather than means, so the light touch-down and lift at either end of a stroke do not pull
 * the whole shape thin. The barrel direction is averaged as a unit vector, because an arithmetic
 * mean of angles either side of the +/-pi wrap would point the opposite way.
 */
internal class StrokeInputStats {
    private var pressures = FloatArray(256)
    private var tilts = FloatArray(256)
    private var count = 0
    private var azimuthX = 0.0
    private var azimuthY = 0.0

    val size: Int get() = count

    fun reset() {
        count = 0
        azimuthX = 0.0
        azimuthY = 0.0
    }

    fun add(pressure: Float, tilt: Float, azimuth: Float) {
        if (count == pressures.size) {
            pressures = pressures.copyOf(count * 2)
            tilts = tilts.copyOf(count * 2)
        }
        pressures[count] = pressure
        tilts[count] = tilt
        count++
        if (azimuth.isFinite()) {
            azimuthX += cos(azimuth.toDouble())
            azimuthY += sin(azimuth.toDouble())
        }
    }

    /** Median pressure, or [fallback] before any sample has arrived. */
    fun medianPressure(fallback: Float = 1f): Float = median(pressures, fallback)

    fun medianTilt(fallback: Float = 0f): Float = median(tilts, fallback)

    /** Mean barrel direction in radians, or [fallback] when no direction was reported. */
    fun meanAzimuth(fallback: Float = 0f): Float =
        if (azimuthX == 0.0 && azimuthY == 0.0) fallback else atan2(azimuthY, azimuthX).toFloat()

    private fun median(values: FloatArray, fallback: Float): Float {
        if (count == 0) return fallback
        val sorted = values.copyOf(count)
        sorted.sort()
        val mid = count / 2
        return if (count % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
