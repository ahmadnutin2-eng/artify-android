package com.procreate.android.vectorize

/**
 * Walks an [EdgeDetector.EdgeMask] into ordered polylines.
 *
 * The approach is a simple 8-connected walk rather than a full Suzuki-Abe border follow: this
 * pipeline feeds a human review step, so a contour that is broken into two pieces costs the user
 * one extra tap, whereas the complexity of full border following costs correctness everywhere.
 *
 * Each pixel is consumed exactly once, so a traced path never doubles back over itself and the
 * total work stays linear in the number of edge pixels.
 */
object ContourTracer {

    private val NEIGHBOURS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to 0, 1 to 0,
        -1 to 1, 0 to 1, 1 to 1
    )

    /**
     * @param minPathPixels contours shorter than this are scanner speckle, not drawing. Filtering
     * here rather than after simplification keeps the output list small enough to review.
     */
    fun trace(mask: EdgeDetector.EdgeMask, minPathPixels: Int = 24): List<List<Vec2>> {
        val width = mask.width
        val height = mask.height
        val visited = BooleanArray(mask.edges.size)
        val paths = mutableListOf<List<Vec2>>()

        // Endpoints first. Starting a walk in the middle of a line splits it into two half-paths
        // running in opposite directions; starting at a free end yields the whole line as one
        // path, which is both what the drawing actually contains and far easier to review.
        val order = orderedSeeds(mask, width, height)

        for (startIndex in order) {
            if (!mask.edges[startIndex] || visited[startIndex]) continue

            val path = mutableListOf<Vec2>()
            var current = startIndex
            var headingX = 0f
            var headingY = 0f
            visited[current] = true
            path += Vec2((current % width).toFloat(), (current / width).toFloat())

            while (true) {
                val next = bestNeighbour(current, width, height, mask.edges, visited, headingX, headingY) ?: break
                headingX = ((next % width) - (current % width)).toFloat()
                headingY = ((next / width) - (current / width)).toFloat()
                visited[next] = true
                path += Vec2((next % width).toFloat(), (next / width).toFloat())
                current = next
            }

            if (path.size >= minPathPixels) paths += path
        }
        return paths
    }

    /** Edge pixels with a single neighbour are line ends; everything else follows after them. */
    private fun orderedSeeds(mask: EdgeDetector.EdgeMask, width: Int, height: Int): List<Int> {
        val endpoints = mutableListOf<Int>()
        val rest = mutableListOf<Int>()
        for (i in mask.edges.indices) {
            if (!mask.edges[i]) continue
            if (neighbourCount(i, width, height, mask.edges) == 1) endpoints += i else rest += i
        }
        return endpoints + rest
    }

    private fun neighbourCount(index: Int, width: Int, height: Int, edges: BooleanArray): Int {
        val x = index % width
        val y = index / width
        var count = 0
        for ((dx, dy) in NEIGHBOURS) {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            if (edges[ny * width + nx]) count++
        }
        return count
    }

    /**
     * Picks the neighbour that best continues the current heading rather than the first one in a
     * fixed scan order.
     *
     * Scan order alone makes the walk zigzag along any diagonal and take an arbitrary branch at
     * every junction, so a straight drawn line comes back as a staircase that no amount of
     * simplification can straighten. Scoring by how well a step continues the previous one keeps
     * the path travelling the way the line actually runs.
     */
    private fun bestNeighbour(
        index: Int,
        width: Int,
        height: Int,
        edges: BooleanArray,
        visited: BooleanArray,
        headingX: Float,
        headingY: Float
    ): Int? {
        val x = index % width
        val y = index / width
        var best: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((dx, dy) in NEIGHBOURS) {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            val n = ny * width + nx
            if (!edges[n] || visited[n]) continue

            val length = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
            val ux = dx / length
            val uy = dy / length
            // With no heading yet (first step) every direction is equally valid, so fall back to
            // preferring straight steps over diagonal ones to avoid an arbitrary diagonal start.
            val score = if (headingX == 0f && headingY == 0f) {
                -length
            } else {
                val hLength = kotlin.math.sqrt(headingX * headingX + headingY * headingY)
                (ux * (headingX / hLength) + uy * (headingY / hLength))
            }
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }
}
