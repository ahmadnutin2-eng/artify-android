package com.procreate.android.layers

import com.procreate.android.layers.LayerStackLogic.Flags
import com.procreate.android.layers.LayerStackLogic.MaskPlan
import com.procreate.android.layers.LayerStackLogic.moveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerStackLogicTest {

    private fun plain() = Flags(isClippingMask = false)
    private fun clip() = Flags(isClippingMask = true)
    private fun paper() = Flags(isClippingMask = false, isBackground = true)

    // ---------------------------------------------------------------- drag and drop

    @Test
    fun `display positions map to stack indices from the top`() {
        assertEquals(3, LayerStackLogic.displayToStack(0, 4))
        assertEquals(0, LayerStackLogic.displayToStack(3, 4))
    }

    @Test
    fun `a drop commits exactly the move the user made`() {
        // Stack A,B,C,D shows as D,C,B,A. Dragging the top row (D) two places down gives
        // C,B,D,A on screen - which must be A,D,B,C in the stack.
        val (from, to) = LayerStackLogic.dropToStackMove(fromPos = 0, toPos = 2, size = 4)!!
        val stack = mutableListOf("A", "B", "C", "D")
        stack.add(to, stack.removeAt(from))
        assertEquals(listOf("A", "D", "B", "C"), stack)
    }

    @Test
    fun `dragging upward maps correctly too`() {
        // Display D,C,B,A; drag the bottom row (A) to the top: A,D,C,B on screen = B,C,D,A stack.
        val (from, to) = LayerStackLogic.dropToStackMove(fromPos = 3, toPos = 0, size = 4)!!
        val stack = mutableListOf("A", "B", "C", "D")
        stack.add(to, stack.removeAt(from))
        assertEquals(listOf("B", "C", "D", "A"), stack)
    }

    @Test
    fun `dropping where it started commits nothing`() {
        // The whole point of committing on release: a lift-and-put-back must not push an undo step.
        assertNull(LayerStackLogic.dropToStackMove(2, 2, 5))
    }

    @Test
    fun `out of range drops are ignored rather than crashing`() {
        assertNull(LayerStackLogic.dropToStackMove(-1, 2, 4))
        assertNull(LayerStackLogic.dropToStackMove(0, 4, 4))
    }

    @Test
    fun `the adapter's live reorder matches what the drop commits`() {
        // The panel reorders its display list while dragging and the view model reorders the stack on
        // release; if the two disagreed the list would visibly jump on drop.
        val display = mutableListOf("D", "C", "B", "A")
        display.moveItem(0, 2)
        assertEquals(listOf("C", "B", "D", "A"), display)

        val (from, to) = LayerStackLogic.dropToStackMove(0, 2, 4)!!
        val stack = mutableListOf("A", "B", "C", "D")
        stack.add(to, stack.removeAt(from))
        assertEquals(display.reversed(), stack)
    }

    @Test
    fun `moveItem tolerates bad indices`() {
        val list = mutableListOf(1, 2, 3)
        list.moveItem(0, 9)
        list.moveItem(-1, 1)
        assertEquals(listOf(1, 2, 3), list)
    }

    // ---------------------------------------------------------------- clipping base

    @Test
    fun `a clipped layer reports the layer directly below as its base`() {
        val stack = listOf(paper(), plain(), clip())
        assertEquals(1, LayerStackLogic.clipBaseIndex(stack, 2))
    }

    @Test
    fun `several clipped layers share one base`() {
        // Procreate's clipping group: every consecutive clipped layer clips to the same base.
        val stack = listOf(paper(), plain(), clip(), clip(), clip())
        assertEquals(1, LayerStackLogic.clipBaseIndex(stack, 2))
        assertEquals(1, LayerStackLogic.clipBaseIndex(stack, 3))
        assertEquals(1, LayerStackLogic.clipBaseIndex(stack, 4))
    }

    @Test
    fun `an unclipped layer has no base`() {
        assertEquals(-1, LayerStackLogic.clipBaseIndex(listOf(paper(), plain()), 1))
    }

    @Test
    fun `a clipped layer with nothing beneath it has no base`() {
        // The compositor hides such a layer entirely, which is exactly the "it vanished" symptom, so
        // the panel has to be able to say so.
        assertEquals(-1, LayerStackLogic.clipBaseIndex(listOf(clip()), 0))
        assertEquals(-1, LayerStackLogic.clipBaseIndex(listOf(clip(), clip()), 1))
    }

    // ---------------------------------------------------------------- use as mask

    @Test
    fun `the shape with a picture above it clips the picture`() {
        // paper, shape, picture: "use the shape as a mask" clips the picture to it.
        val stack = listOf(paper(), plain(), plain())
        assertEquals(MaskPlan.CLIP_LAYER_ABOVE, LayerStackLogic.planMask(stack, 1))
    }

    @Test
    fun `the shape with a picture below it lifts the picture over the shape`() {
        // paper, picture, shape (the shape is on top): nothing above to clip, so the picture below
        // is moved above and clipped.
        val stack = listOf(paper(), plain(), plain())
        assertEquals(MaskPlan.PULL_LAYER_BELOW_UP, LayerStackLogic.planMask(stack, 2))
    }

    @Test
    fun `the paper is never lifted over the artwork`() {
        // paper, shape: the only neighbour below is the paper. Lifting it would cover everything.
        val stack = listOf(paper(), plain())
        assertEquals(MaskPlan.NOTHING, LayerStackLogic.planMask(stack, 1))
    }

    @Test
    fun `a layer that is itself clipped cannot be the mask`() {
        // If it were, "clip the layer above" would clip to this layer's own base instead.
        val stack = listOf(paper(), plain(), clip(), plain())
        assertEquals(MaskPlan.SELF_CLIPPED, LayerStackLogic.planMask(stack, 2))
    }

    @Test
    fun `an already clipped layer above is not clipped twice`() {
        // paper, shape, clipped picture: the picture is already doing what was asked. The next
        // candidate is the layer below, which is the paper, so there is nothing to change.
        val stack = listOf(paper(), plain(), clip())
        assertEquals(MaskPlan.NOTHING, LayerStackLogic.planMask(stack, 1))
    }
}
