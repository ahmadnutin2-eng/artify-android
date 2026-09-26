package com.procreate.android.canvas

/**
 * Pure stylus-axis math, kept independent of Android so tilt and barrel direction can be tested.
 *
 * A pen reports more than where it is and how hard it presses. Laying it over spreads the contact
 * patch; turning it in the hand turns the nib. Until now the engine read position and pressure
 * only, which is enough for a round tip and not enough for a cut one: Arabic calligraphy lives on
 * the angle between a flat-cut nib and the direction of travel, and that angle is exactly what the
 * barrel axes carry. S Pen reports both on this hardware, free of charge.
 */
object StylusResponse {

    /**
     * How far the pen is laid over, as 0 (perpendicular) to 1 (flat enough to count as fully
     * tilted).
     *
     * Android measures tilt up to a right angle, but a pen held at 90 degrees is not being drawn
     * with - past about 60 degrees the hand has run out of wrist. Normalising against the usable
     * range is what makes a tilt-shaded pencil reach its full effect in real use instead of only
     * at an angle nobody draws at.
     */
    fun tiltFactor(tiltRadians: Float): Float {
        if (!tiltRadians.isFinite() || tiltRadians <= 0f) return 0f
        return (tiltRadians / USABLE_TILT_RADIANS).coerceIn(0f, 1f)
    }

    /**
     * Rotation, in degrees, for a stamp whose nib follows the barrel.
     *
     * [azimuthRadians] is MotionEvent's orientation: clockwise from straight up the screen. It is
     * converted to the same convention the engine uses for stroke direction, where zero points
     * along positive X, so that a brush tracking the barrel at full strength and a brush oriented
     * to the stroke agree whenever the pen happens to point the way it is travelling.
     *
     * [tracking] blends between the brush's own fixed cut at 0 and the hand at 1. Partial values
     * are deliberately allowed: a nib that takes most of its angle from a fixed cut and drifts a
     * little with the wrist is closer to a real reed than either extreme.
     */
    fun nibRotationDegrees(azimuthRadians: Float, tracking: Float, fixedAngleDegrees: Float): Float {
        val amount = tracking.coerceIn(0f, 1f)
        if (amount <= 0f || !azimuthRadians.isFinite()) return fixedAngleDegrees
        val barrelDegrees = Math.toDegrees(azimuthRadians.toDouble()).toFloat() - 90f
        return fixedAngleDegrees + barrelDegrees * amount
    }

    /** Shortest signed difference between two angles in radians, for interpolating across the wrap. */
    fun angleDeltaRadians(from: Float, to: Float): Float {
        var delta = to - from
        while (delta > Math.PI.toFloat()) delta -= TWO_PI
        while (delta < -Math.PI.toFloat()) delta += TWO_PI
        return delta
    }

    private const val TWO_PI = (Math.PI * 2).toFloat()

    /** 60 degrees: past this the pen is flatter than anyone actually draws. */
    private val USABLE_TILT_RADIANS = (Math.PI / 3.0).toFloat()
}
