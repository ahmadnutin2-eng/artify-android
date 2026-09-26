package com.procreate.android.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RadialNeutralHitTest {
    @Test
    fun `every neutral swatch resolves to its own index`() {
        val count = 47
        val pitch = 360f / count
        repeat(count) { expected ->
            val degrees = -126f + expected * pitch + pitch / 2f
            val radians = Math.toRadians(degrees.toDouble())
            val x = 100f + cos(radians).toFloat() * 150f
            val y = 200f + sin(radians).toFloat() * 150f
            assertEquals(
                expected,
                RadialNeutralHit.indexAt(x, y, 100f, 200f, 150f, 38f, count, 7f)
            )
        }
    }

    @Test
    fun `touch outside neutral band does not steal hue-wheel gesture`() {
        assertNull(RadialNeutralHit.indexAt(400f, 200f, 100f, 200f, 150f, 38f, 47, 7f))
    }
}
