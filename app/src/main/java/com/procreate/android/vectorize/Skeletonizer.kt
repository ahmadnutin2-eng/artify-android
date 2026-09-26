package com.procreate.android.vectorize

/**
 * Zhang-Suen thinning: erodes a filled shape down to its one-pixel-wide centreline while keeping
 * the shape connected.
 *
 * This is what removes the doubled-line problem. Edge detection finds the *boundaries* of a drawn
 * stroke, and a stroke has two of them, so a single pen line comes back as two parallel paths a
 * stroke-width apart. Thinning the ink itself instead yields one path down the middle of the line,
 * which is what the drawing actually means and what a CAD file should contain.
 *
 * The algorithm alternates two sub-passes, each deleting only pixels whose removal cannot break
 * connectivity or shorten a line end. Deletions within a pass are collected first and applied
 * together, because deleting during the scan would let an already-thinned pixel influence its
 * neighbour's test and eat through the line.
 */
object Skeletonizer {

    /** @return a new mask thinned to single-pixel width; the input is not modified. */
    fun thin(ink: BooleanArray, width: Int, height: Int, maxIterations: Int = 60): BooleanArray {
        require(ink.size == width * height) { "Mask does not match the given dimensions" }
        val image = ink.copyOf()
        val toRemove = ArrayList<Int>()

        repeat(maxIterations) {
            var changed = false
            for (step in 0..1) {
                toRemove.clear()
                for (y in 1 until height - 1) {
                    for (x in 1 until width - 1) {
                        val i = y * width + x
                        if (!image[i]) continue

                        // Neighbours clockwise from north, as the algorithm numbers them.
                        val p2 = image[i - width]
                        val p3 = image[i - width + 1]
                        val p4 = image[i + 1]
                        val p5 = image[i + width + 1]
                        val p6 = image[i + width]
                        val p7 = image[i + width - 1]
                        val p8 = image[i - 1]
                        val p9 = image[i - width - 1]

                        val neighbours = listOf(p2, p3, p4, p5, p6, p7, p8, p9)
                        val filled = neighbours.count { it }
                        // A pixel with fewer than two filled neighbours is a line end (removing it
                        // would shorten the line); more than six means it is interior, not border.
                        if (filled < 2 || filled > 6) continue

                        // Number of 0->1 transitions going round the ring. Exactly one means the
                        // neighbourhood is a single connected arc, so deleting this pixel cannot
                        // split the shape in two.
                        var transitions = 0
                        for (n in neighbours.indices) {
                            val current = neighbours[n]
                            val next = neighbours[(n + 1) % neighbours.size]
                            if (!current && next) transitions++
                        }
                        if (transitions != 1) continue

                        val keep = if (step == 0) {
                            (p2 && p4 && p6) || (p4 && p6 && p8)
                        } else {
                            (p2 && p4 && p8) || (p2 && p6 && p8)
                        }
                        if (keep) continue

                        toRemove.add(i)
                    }
                }
                if (toRemove.isNotEmpty()) {
                    for (i in toRemove) image[i] = false
                    changed = true
                }
            }
            if (!changed) return image
        }
        return image
    }

    /** Drops isolated specks that survive thinning - a single lit pixel with no neighbour is dust
     * on the scanner glass, never a line. */
    fun removeIsolated(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = mask.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!mask[i]) continue
                var neighbours = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    if (mask[ny * width + nx]) neighbours++
                }
                if (neighbours == 0) out[i] = false
            }
        }
        return out
    }
}
