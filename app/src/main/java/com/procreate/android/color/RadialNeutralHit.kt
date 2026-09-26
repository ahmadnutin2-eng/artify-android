package com.procreate.android.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Pure geometry for the fixed black/white ring; kept separate so every cell is unit-testable. */
object RadialNeutralHit {
    fun indexAt(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
        thickness: Float,
        count: Int,
        touchSlop: Float,
        startDegrees: Float = -126f
    ): Int? {
        if (count <= 0 || radius <= 0f || thickness <= 0f) return null
        val distance = hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
        val inner = radius - thickness / 2f
        val outer = radius + thickness / 2f
        if (distance < inner - touchSlop || distance > outer + touchSlop) return null
        val angle = Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())).toFloat()
        val pitch = 360f / count
        return (0 until count).firstOrNull { index ->
            val start = startDegrees + index * pitch + 0.08f
            val sweep = pitch - 0.16f
            abs(signedAngleDifference(angle, start + sweep / 2f)) <= sweep / 2f
        }
    }

    private fun signedAngleDifference(first: Float, second: Float): Float =
        ((first - second + 540f) % 360f) - 180f
}
