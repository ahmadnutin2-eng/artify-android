package com.procreate.android.canvas

import kotlin.math.PI

/**
 * The geometry of drawing with symmetry, kept free of android.graphics so it is testable on the JVM.
 *
 * A branch is one reflection of the artist's stroke. [mirrorX] reflects across the vertical axis
 * (x becomes 2cx - x) and [mirrorY] across the horizontal one; both together is the diagonal branch
 * of four-way symmetry, which is a half turn about the centre.
 */
internal object SymmetryMath {

    data class Branch(val mirrorX: Boolean, val mirrorY: Boolean)

    private val VERTICAL = listOf(Branch(mirrorX = true, mirrorY = false))
    private val HORIZONTAL = listOf(Branch(mirrorX = false, mirrorY = true))
    private val QUAD = listOf(
        Branch(mirrorX = true, mirrorY = false),
        Branch(mirrorX = false, mirrorY = true),
        Branch(mirrorX = true, mirrorY = true)
    )

    /**
     * The reflected branches to paint alongside the artist's own stroke, which is never included.
     * [vertical] is a mirror line running up the canvas, [horizontal] one running across it.
     */
    fun branches(vertical: Boolean, horizontal: Boolean): List<Branch> = when {
        vertical && horizontal -> QUAD
        vertical -> VERTICAL
        horizontal -> HORIZONTAL
        else -> emptyList()
    }

    fun reflectX(x: Float, centerX: Float, branch: Branch): Float =
        if (branch.mirrorX) 2f * centerX - x else x

    fun reflectY(y: Float, centerY: Float, branch: Branch): Float =
        if (branch.mirrorY) 2f * centerY - y else y

    /**
     * Reflect a direction given as atan2(dy, dx) in canvas space, returned in (-pi, pi].
     *
     * Reflection is its own inverse, so the same call maps a mirrored path's direction back to the
     * direction the artist actually drew in.
     */
    fun reflectDirection(radians: Float, mirrorX: Boolean, mirrorY: Boolean): Float {
        val reflected = when {
            mirrorX && mirrorY -> radians + PI.toFloat()
            mirrorX -> PI.toFloat() - radians
            mirrorY -> -radians
            else -> radians
        }
        return wrap(reflected)
    }

    private fun wrap(radians: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var a = radians
        while (a > PI.toFloat()) a -= twoPi
        while (a <= -PI.toFloat()) a += twoPi
        return a
    }
}
