package com.procreate.android.layers

/**
 * The layer-stack rules the Layers panel depends on, kept free of `android.*` so they can be tested
 * on the JVM. Each of these was previously an inline expression in the panel, and each was a place
 * where a mistake is invisible: an off-by-one in an index mapping reorders the wrong layer and
 * nothing crashes.
 *
 * Vocabulary: the **stack** is bottom-first (index 0 is the paper). The **display list** in the panel
 * is top-first, the way every painting app shows layers, so display position 0 is stack index
 * `size - 1`.
 */
object LayerStackLogic {

    /** The only facts about a layer these rules need. */
    data class Flags(
        val isClippingMask: Boolean,
        val isVisible: Boolean = true,
        val isBackground: Boolean = false
    )

    /** Maps a position in the top-first display list to an index in the bottom-first stack. */
    fun displayToStack(displayPos: Int, size: Int): Int = size - 1 - displayPos

    /**
     * The stack indices to hand to `moveLayer` for a drop, or null when nothing moved.
     *
     * The panel reorders its own list live while the finger is down and commits to the view model
     * exactly once, on release. Committing on every intermediate step is what made the drag fall
     * apart: each commit republished the whole layer list, the adapter rebound every row, and
     * RecyclerView dropped the item being dragged a fraction of a second after it was lifted.
     */
    fun dropToStackMove(fromPos: Int, toPos: Int, size: Int): Pair<Int, Int>? {
        if (fromPos == toPos) return null
        if (fromPos !in 0 until size || toPos !in 0 until size) return null
        return displayToStack(fromPos, size) to displayToStack(toPos, size)
    }

    /**
     * Index of the layer that [index] is clipped to: the nearest layer below it that is not itself a
     * clipping layer. This mirrors the rule LayerCompositor applies when drawing, so the panel can
     * show exactly what the canvas is doing. Returns -1 when [index] is not a clipping layer or has
     * nothing beneath it to clip to (in which case the compositor hides it).
     */
    fun clipBaseIndex(flags: List<Flags>, index: Int): Int {
        if (index !in flags.indices || !flags[index].isClippingMask) return -1
        var i = index - 1
        while (i >= 0) {
            if (!flags[i].isClippingMask) return i
            i--
        }
        return -1
    }

    enum class MaskPlan {
        /** The layer above becomes a clipping mask on this one - the Procreate arrangement. */
        CLIP_LAYER_ABOVE,

        /** The layer below is lifted above this one and clipped to it. */
        PULL_LAYER_BELOW_UP,

        /** This layer is itself clipped, so "the layer above clips to it" would clip to its base. */
        SELF_CLIPPED,

        NOTHING
    }

    /**
     * What "use this layer as a mask" should do, given the stack.
     *
     * Users describe a clipping mask as "the drawing is the mask and the picture shows through it",
     * without caring which of the two sits on top. The compositor's rule is strictly "a clipping
     * layer shows inside the layer below it", so the panel translates the intent into that rule:
     * clip the layer above if there is one, otherwise lift the layer below over this one first.
     */
    fun planMask(flags: List<Flags>, index: Int): MaskPlan {
        if (index !in flags.indices) return MaskPlan.NOTHING
        if (flags[index].isClippingMask) return MaskPlan.SELF_CLIPPED

        val above = index + 1
        if (above < flags.size && !flags[above].isClippingMask) return MaskPlan.CLIP_LAYER_ABOVE

        val below = index - 1
        // The paper is never lifted over the artwork - it would cover everything.
        if (below >= 0 && !flags[below].isBackground && !flags[below].isClippingMask) {
            return MaskPlan.PULL_LAYER_BELOW_UP
        }
        return MaskPlan.NOTHING
    }

    /** RecyclerView's `notifyItemMoved(from, to)` semantics: the item leaves [from] and lands on [to]. */
    fun <T> MutableList<T>.moveItem(from: Int, to: Int) {
        if (from == to || from !in indices || to !in indices) return
        add(to, removeAt(from))
    }
}
