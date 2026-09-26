package com.procreate.android.tools

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paint-bucket fill that does not leak through the small gaps line art is full of.
 *
 * ## The problem
 *
 * Hair, fur, grass and hatching are drawn as many separate strokes, and between them sit gaps a few
 * pixels wide that the eye reads as closed. A plain flood fill does not: it finds one gap, escapes,
 * and floods the whole canvas. Raising the colour tolerance does not help - the gap is not a colour
 * problem, it is a topology problem.
 *
 * ## The approach
 *
 * Seal the gaps temporarily, fill inside the sealed shape, then grow the result back:
 *
 *  1. **Barrier**: every pixel that differs from the clicked colour by more than [Options.tolerance].
 *     This is the line art.
 *  2. **Seal**: thicken the barrier by [Options.gapClosing] using a distance transform. Two barrier
 *     pixels closer than twice that distance now touch, so any gap up to `2 * gapClosing` wide is
 *     closed - while the real, thick lines only gain a temporary skin.
 *  3. **Fill** inside the sealed barrier. The fill cannot escape, but it now stops `gapClosing`
 *     pixels short of every real line, leaving a visible halo.
 *  4. **Grow back**, at most [Options.gapClosing] steps, blocked by the *original* barrier. Against
 *     a real line the region returns exactly to the line. At a sealed gap it advances at most
 *     `gapClosing` into a gap at least that deep from each side, so it still cannot get through.
 *  5. **Expand** a further [Options.expand] pixels *into* the barrier, to slide under the
 *     semi-transparent pixels along an antialiased line - otherwise every fill is ringed by a thin
 *     unpainted outline, which is the other classic bucket-fill complaint.
 *
 * Steps 2, 4 and 5 are all linear in the number of pixels regardless of radius: the seal uses a
 * two-pass chamfer distance transform, and the two growth steps are breadth-first, visiting each
 * pixel once. A naive "dilate N times" would be N passes over the whole canvas.
 *
 * Deliberately free of `android.graphics` - including `Color`, whose methods are unimplemented
 * stubs in unit tests - so the arithmetic that decides what gets painted is actually covered by
 * tests rather than judged by eye.
 */
object SmartFill {

    /**
     * @param tolerance how different a colour may be and still count as the same region, 0..255.
     * @param gapClosing half the width of the widest gap to treat as closed, in pixels. 0 disables
     *        gap closing and makes this a plain flood fill.
     * @param expand extra pixels to grow into the line art, hiding the antialiased seam.
     * @param contiguous false fills every matching pixel on the layer, not just the connected one.
     */
    data class Options(
        val tolerance: Int = 28,
        val gapClosing: Int = 4,
        val expand: Int = 2,
        val contiguous: Boolean = true
    ) {
        companion object {
            /** Line art with obvious gaps - hair, fur, hatching. */
            val LINE_ART = Options(tolerance = 32, gapClosing = 8, expand = 2)

            /** Closed shapes, where sealing would only round off sharp corners. */
            val EXACT = Options(tolerance = 16, gapClosing = 0, expand = 1)
        }
    }

    /** Sentinel for "not reached" in the distance transform; large enough never to be a real value. */
    private const val FAR = 1 shl 20

    /**
     * Which pixels the fill covers, or null when the start point is outside the image.
     *
     * Separated from painting so the same decision can be previewed, tested, or reused as a
     * selection without anything being written.
     */
    fun computeMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        options: Options = Options(),
        allowed: ((Int, Int) -> Boolean)? = null
    ): BooleanArray? {
        if (width <= 0 || height <= 0) return null
        if (startX !in 0 until width || startY !in 0 until height) return null
        if (pixels.size < width * height) return null
        if (allowed != null && !allowed(startX, startY)) return null

        val n = width * height
        val target = pixels[startY * width + startX]
        val tolerance = options.tolerance.coerceIn(0, 255)

        // 1. Barrier: everything that is not part of the clicked region.
        val barrier = BooleanArray(n)
        for (i in 0 until n) barrier[i] = !matches(pixels[i], target, tolerance)

        if (!options.contiguous) {
            // Every matching pixel, connectivity ignored. Gap closing is meaningless here.
            val mask = BooleanArray(n)
            for (i in 0 until n) {
                mask[i] = !barrier[i] && (allowed == null || allowed(i % width, i / width))
            }
            return mask
        }

        val requested = options.gapClosing.coerceIn(0, 64)
        val startIdx = startY * width + startX

        // 2. Seal: thicken the barrier so gaps narrower than 2 * gap close up.
        //
        // The radius is a maximum, not a promise. A seal wide enough to close a gap can also be
        // wide enough to swallow a *small* area whole - click inside an 8px box with the radius at
        // 6 and every pixel of that box is sealed, the flood below starts on a blocked pixel, and
        // the click does nothing at all. So the radius backs off until the clicked pixel survives:
        // big regions get the full gap closing, small ones degrade to an exact fill, and a tap
        // always does something.
        val dist = if (requested > 0) distanceToBarrier(barrier, width, height) else null
        var gap = requested
        if (dist != null) {
            while (gap > 0 && dist[startIdx] <= gap) gap--
        }
        val sealed = if (gap == 0 || dist == null) barrier else BooleanArray(n) { dist[it] <= gap }

        // 3. Fill inside the sealed barrier.
        var region = floodFrom(sealed, width, height, startX, startY, allowed)
            ?: return BooleanArray(n)

        if (gap > 0) {
            // 4. Give back what the seal took, without crossing any real line.
            region = growWithin(region, barrier, width, height, gap, allowed)
        }

        // 5. Slide under the antialiased edge of the line.
        val expand = options.expand.coerceIn(0, 32)
        if (expand > 0) {
            region = growWithin(region, null, width, height, expand, allowed)
        }
        return region
    }

    /** Paints [fillColor] into [pixels] wherever the mask covers. @return true if anything changed. */
    fun fill(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        fillColor: Int,
        options: Options = Options(),
        allowed: ((Int, Int) -> Boolean)? = null
    ): Boolean {
        val mask = computeMask(pixels, width, height, startX, startY, options, allowed) ?: return false
        var changed = false
        for (i in mask.indices) {
            if (mask[i] && pixels[i] != fillColor) {
                pixels[i] = fillColor
                changed = true
            }
        }
        return changed
    }

    // ------------------------------------------------------------------ internals

    /**
     * Chebyshev distance from every pixel to the nearest barrier pixel, by two chamfer passes.
     *
     * Chebyshev (a 3x3 neighbourhood counting diagonals as one step) rather than Euclidean because
     * it seals diagonal gaps at the same radius as straight ones - a diagonal hair stroke should not
     * need a larger setting than a vertical one. Two passes, so the cost does not grow with radius.
     */
    private fun distanceToBarrier(barrier: BooleanArray, width: Int, height: Int): IntArray {
        val dist = IntArray(barrier.size) { if (barrier[it]) 0 else FAR }

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (dist[i] == 0) continue
                var best = dist[i]
                if (y > 0) {
                    best = min(best, dist[i - width] + 1)
                    if (x > 0) best = min(best, dist[i - width - 1] + 1)
                    if (x < width - 1) best = min(best, dist[i - width + 1] + 1)
                }
                if (x > 0) best = min(best, dist[i - 1] + 1)
                dist[i] = best
            }
        }
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val i = row + x
                if (dist[i] == 0) continue
                var best = dist[i]
                if (y < height - 1) {
                    best = min(best, dist[i + width] + 1)
                    if (x > 0) best = min(best, dist[i + width - 1] + 1)
                    if (x < width - 1) best = min(best, dist[i + width + 1] + 1)
                }
                if (x < width - 1) best = min(best, dist[i + 1] + 1)
                dist[i] = best
            }
        }
        return dist
    }

    /** Four-connected flood over pixels where [blocked] is false. Null when the seed is blocked. */
    private fun floodFrom(
        blocked: BooleanArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        allowed: ((Int, Int) -> Boolean)?
    ): BooleanArray? {
        val start = startY * width + startX
        if (blocked[start]) return null

        val region = BooleanArray(blocked.size)
        // An IntArray used as a stack: ArrayDeque<Int> boxes every index, and a full-canvas fill
        // pushes millions of them. Marking a pixel as it is pushed (never as it is popped) is what
        // bounds the stack to the pixel count, so it can be sized once up front.
        val stack = IntArray(blocked.size)
        var top = 0
        region[start] = true
        stack[top++] = start

        while (top > 0) {
            val i = stack[--top]
            val x = i % width
            val y = i / width
            if (x > 0 && claim(i - 1, x - 1, y, blocked, region, allowed)) stack[top++] = i - 1
            if (x < width - 1 && claim(i + 1, x + 1, y, blocked, region, allowed)) stack[top++] = i + 1
            if (y > 0 && claim(i - width, x, y - 1, blocked, region, allowed)) stack[top++] = i - width
            if (y < height - 1 && claim(i + width, x, y + 1, blocked, region, allowed)) stack[top++] = i + width
        }
        return region
    }

    /** Marks [idx] as part of the region if it is eligible and not already taken. */
    private fun claim(
        idx: Int, x: Int, y: Int,
        blocked: BooleanArray, region: BooleanArray,
        allowed: ((Int, Int) -> Boolean)?
    ): Boolean {
        if (region[idx] || blocked[idx]) return false
        if (allowed != null && !allowed(x, y)) return false
        region[idx] = true
        return true
    }

    /**
     * Grows [region] outward by at most [steps] pixels, never entering [blocked].
     *
     * Breadth-first from the whole current region at once, so every pixel is visited once and the
     * step count is exact - a pixel is only reached on the wave that genuinely reaches it. Passing
     * null for [blocked] lets the growth run into the line art, which is what hides the
     * antialiased seam.
     */
    private fun growWithin(
        region: BooleanArray,
        blocked: BooleanArray?,
        width: Int,
        height: Int,
        steps: Int,
        allowed: ((Int, Int) -> Boolean)?
    ): BooleanArray {
        if (steps <= 0) return region
        val out = region.copyOf()
        var frontier = IntArray(out.size)
        var frontierSize = 0
        for (i in out.indices) if (out[i]) frontier[frontierSize++] = i

        var next = IntArray(out.size)
        repeat(steps) {
            var nextSize = 0
            for (f in 0 until frontierSize) {
                val i = frontier[f]
                val x = i % width
                val y = i / width
                if (x > 0) nextSize = visit(i - 1, x - 1, y, out, blocked, next, nextSize, allowed)
                if (x < width - 1) nextSize = visit(i + 1, x + 1, y, out, blocked, next, nextSize, allowed)
                if (y > 0) nextSize = visit(i - width, x, y - 1, out, blocked, next, nextSize, allowed)
                if (y < height - 1) nextSize = visit(i + width, x, y + 1, out, blocked, next, nextSize, allowed)
            }
            if (nextSize == 0) return out
            val swap = frontier
            frontier = next
            next = swap
            frontierSize = nextSize
        }
        return out
    }

    private fun visit(
        idx: Int, x: Int, y: Int,
        out: BooleanArray, blocked: BooleanArray?,
        next: IntArray, nextSize: Int,
        allowed: ((Int, Int) -> Boolean)?
    ): Int {
        if (out[idx]) return nextSize
        if (blocked != null && blocked[idx]) return nextSize
        if (allowed != null && !allowed(x, y)) return nextSize
        out[idx] = true
        next[nextSize] = idx
        return nextSize + 1
    }

    /**
     * Whether two ARGB pixels count as the same region.
     *
     * Transparent pixels are compared on alpha alone: two fully transparent pixels carry whatever
     * RGB happens to be underneath, and comparing that would split one empty area into several for
     * reasons nothing on screen explains.
     */
    private fun matches(a: Int, b: Int, tolerance: Int): Boolean {
        val aa = a ushr 24 and 0xFF
        val ab = b ushr 24 and 0xFF
        if (abs(aa - ab) > tolerance) return false
        if (aa == 0 && ab == 0) return true

        val dr = abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF))
        val dg = abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db)) <= tolerance
    }
}
