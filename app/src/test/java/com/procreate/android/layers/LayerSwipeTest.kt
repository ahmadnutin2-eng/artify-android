package com.procreate.android.layers

import com.procreate.android.layers.LayerSwipe.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LayerSwipeTest {

    private val density = 2f
    private val past = LayerSwipe.THRESHOLD_DP * density + 10f

    @Test
    fun `a short drag is not a swipe`() {
        // Otherwise a tap with a little drift in it would silently toggle Alpha Lock.
        assertEquals(Action.NONE, LayerSwipe.classify(10f, 0f, density, isRtl = true))
        assertEquals(Action.NONE, LayerSwipe.classify(-10f, 0f, density, isRtl = true))
    }

    @Test
    fun `a mostly vertical drag is a scroll`() {
        assertEquals(Action.NONE, LayerSwipe.classify(past, past * 2, density, isRtl = true))
        assertEquals(Action.NONE, LayerSwipe.classify(-past, past * 2, density, isRtl = false))
    }

    @Test
    fun `direction follows the layout, not the screen`() {
        // The same physical swipe has to mean the same thing to an Arabic and an English reader,
        // which means opposite screen directions.
        assertEquals(Action.ALPHA_LOCK, LayerSwipe.classify(past, 0f, density, isRtl = true))
        assertEquals(Action.ALPHA_LOCK, LayerSwipe.classify(-past, 0f, density, isRtl = false))

        assertEquals(Action.QUICK_ACTIONS, LayerSwipe.classify(-past, 0f, density, isRtl = true))
        assertEquals(Action.QUICK_ACTIONS, LayerSwipe.classify(past, 0f, density, isRtl = false))
    }

    @Test
    fun `a diagonal swipe still counts while it stays mostly horizontal`() {
        assertEquals(Action.ALPHA_LOCK, LayerSwipe.classify(past, past * 0.5f, density, isRtl = true))
    }

    @Test
    fun `the row follows the finger one to one up to the limit`() {
        assertEquals(40f, LayerSwipe.followOffset(40f, density), 0.01f)
        assertEquals(-40f, LayerSwipe.followOffset(-40f, density), 0.01f)
    }

    @Test
    fun `past the limit the row resists`() {
        val limit = LayerSwipe.COMMIT_DP * density
        val far = limit + 400f
        val offset = LayerSwipe.followOffset(far, density)
        assertTrue("must keep moving", offset > limit)
        assertTrue("but far less than the finger", offset < far * 0.6f)
    }

    @Test
    fun `resistance is symmetric`() {
        val far = LayerSwipe.COMMIT_DP * density + 300f
        assertEquals(
            LayerSwipe.followOffset(far, density),
            abs(LayerSwipe.followOffset(-far, density)),
            0.01f
        )
    }

    @Test
    fun `the threshold is comfortably larger than a touch slop`() {
        // Android's touch slop is around 8dp. A swipe threshold near it would fire on any tap with
        // a little drag in it.
        assertTrue(LayerSwipe.THRESHOLD_DP >= 40f)
        assertTrue("and the commit distance must be further still", LayerSwipe.COMMIT_DP > LayerSwipe.THRESHOLD_DP)
    }
}
