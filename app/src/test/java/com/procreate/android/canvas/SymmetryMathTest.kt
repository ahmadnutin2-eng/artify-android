package com.procreate.android.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SymmetryMathTest {

    private val pi = PI.toFloat()

    @Test
    fun `each mode paints the right number of mirrored branches`() {
        assertEquals(0, SymmetryMath.branches(vertical = false, horizontal = false).size)
        assertEquals(1, SymmetryMath.branches(vertical = true, horizontal = false).size)
        assertEquals(1, SymmetryMath.branches(vertical = false, horizontal = true).size)
        assertEquals(3, SymmetryMath.branches(vertical = true, horizontal = true).size)
    }

    @Test
    fun `the artist's own stroke is never one of the mirrored branches`() {
        for (v in listOf(false, true)) for (h in listOf(false, true)) {
            assertTrue(SymmetryMath.branches(v, h).none { !it.mirrorX && !it.mirrorY })
        }
    }

    @Test
    fun `a vertical mirror reflects x about the centre and leaves y alone`() {
        val branch = SymmetryMath.branches(vertical = true, horizontal = false).single()
        assertEquals(700f, SymmetryMath.reflectX(300f, 500f, branch), 0f)
        assertEquals(123f, SymmetryMath.reflectY(123f, 400f, branch), 0f)
    }

    @Test
    fun `a horizontal mirror reflects y about the centre and leaves x alone`() {
        val branch = SymmetryMath.branches(vertical = false, horizontal = true).single()
        assertEquals(300f, SymmetryMath.reflectX(300f, 500f, branch), 0f)
        assertEquals(650f, SymmetryMath.reflectY(150f, 400f, branch), 0f)
    }

    @Test
    fun `quad symmetry lands one copy in every quadrant`() {
        val cx = 500f
        val cy = 400f
        val points = SymmetryMath.branches(vertical = true, horizontal = true).map {
            SymmetryMath.reflectX(200f, cx, it) to SymmetryMath.reflectY(100f, cy, it)
        }.toSet() + (200f to 100f)
        assertEquals(
            setOf(200f to 100f, 800f to 100f, 200f to 700f, 800f to 700f),
            points
        )
    }

    @Test
    fun `a point on the axis stays on the axis`() {
        val branch = SymmetryMath.branches(vertical = true, horizontal = false).single()
        assertEquals(500f, SymmetryMath.reflectX(500f, 500f, branch), 0f)
    }

    @Test
    fun `reflected directions match the reflected travel vector`() {
        val angles = listOf(0f, 0.3f, 1.2f, pi / 2, 2.8f, -0.7f, -pi / 2, -2.9f)
        for (mx in listOf(false, true)) for (my in listOf(false, true)) {
            for (a in angles) {
                // Reflect the unit vector directly and compare with the angle formula.
                val dx = cos(a) * (if (mx) -1f else 1f)
                val dy = sin(a) * (if (my) -1f else 1f)
                val expected = atan2(dy, dx)
                val actual = SymmetryMath.reflectDirection(a, mx, my)
                assertEquals("mx=$mx my=$my a=$a", 0f, StylusResponse.angleDeltaRadians(expected, actual), 1e-5f)
            }
        }
    }

    @Test
    fun `reflecting a direction twice gives it back, which is how a mirror branch recovers the drawn direction`() {
        for (mx in listOf(false, true)) for (my in listOf(false, true)) {
            for (a in listOf(-3f, -1f, 0f, 0.5f, 2f, 3.1f)) {
                val back = SymmetryMath.reflectDirection(SymmetryMath.reflectDirection(a, mx, my), mx, my)
                assertEquals(0f, StylusResponse.angleDeltaRadians(a, back), 1e-5f)
            }
        }
    }

    @Test
    fun `reflected directions stay inside the half-open range`() {
        for (a in listOf(-pi, -pi + 1e-4f, 0f, pi - 1e-4f, pi)) {
            for (mx in listOf(false, true)) for (my in listOf(false, true)) {
                val r = SymmetryMath.reflectDirection(a, mx, my)
                assertTrue("$r", r > -pi - 1e-6f && r <= pi + 1e-6f)
            }
        }
    }

    @Test
    fun `the diagonal branch of quad symmetry is a half turn`() {
        assertEquals(0f, StylusResponse.angleDeltaRadians(0.4f + pi, SymmetryMath.reflectDirection(0.4f, true, true)), 1e-5f)
    }
}
