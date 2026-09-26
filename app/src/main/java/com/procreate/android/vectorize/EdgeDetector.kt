package com.procreate.android.vectorize

import kotlin.math.sqrt

/**
 * Classical edge extraction over a plain ARGB pixel buffer.
 *
 * No `android.*` import and no model: this runs entirely on device, needs no network, no API key
 * and no privacy policy, and is fully deterministic - the same scan always produces the same
 * paths, which is what makes the result reviewable and the bugs reproducible.
 *
 * Pipeline: luminance -> box blur (kills scanner grain that would otherwise become thousands of
 * spurious one-pixel edges) -> Sobel gradient magnitude -> threshold chosen from the image's own
 * gradient distribution rather than a fixed constant, because a faint pencil sketch and a crisp
 * printed plan have completely different contrast ranges.
 */
object EdgeDetector {

    data class EdgeMask(val width: Int, val height: Int, val edges: BooleanArray) {
        fun edgeCount(): Int = edges.count { it }

        // Data classes don't compare array contents; these make equality mean what callers expect.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EdgeMask) return false
            return width == other.width && height == other.height && edges.contentEquals(other.edges)
        }

        override fun hashCode(): Int =
            (width * 31 + height) * 31 + edges.contentHashCode()
    }

    /** Rec. 709 luminance - matches how the eye weights the channels, so coloured ink on paper
     * survives the conversion instead of vanishing into the background. */
    fun toLuminance(argb: IntArray): FloatArray {
        val out = FloatArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        return out
    }

    /** Separable box blur, [radius] pixels each way. Two 1-D passes instead of one 2-D kernel, so
     * cost grows with radius rather than radius squared - it matters on a multi-megapixel scan. */
    fun boxBlur(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return source.copyOf()
        val horizontal = FloatArray(source.size)
        val output = FloatArray(source.size)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (k in -radius..radius) {
                    val sx = x + k
                    if (sx in 0 until width) {
                        sum += source[row + sx]
                        count++
                    }
                }
                horizontal[row + x] = sum / count
            }
        }

        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0f
                var count = 0
                for (k in -radius..radius) {
                    val sy = y + k
                    if (sy in 0 until height) {
                        sum += horizontal[sy * width + x]
                        count++
                    }
                }
                output[y * width + x] = sum / count
            }
        }
        return output
    }

    /** Gradient magnitude plus the direction each edge runs, which non-maximum suppression needs. */
    class Gradient(val magnitude: FloatArray, val directionBucket: ByteArray)

    /** Sobel gradient magnitude per pixel. Border pixels stay zero - a one-pixel frame around the
     * image is never a real feature, and treating it as one puts a rectangle around every scan. */
    fun sobelMagnitude(source: FloatArray, width: Int, height: Int): FloatArray =
        sobel(source, width, height).magnitude

    /**
     * Sobel, keeping the gradient direction quantised to the four axes a pixel grid actually has
     * (0, 45, 90, 135 degrees). The direction is what lets [nonMaximumSuppression] know which two
     * neighbours to compare against, so it cannot be discarded the way plain magnitude-only edge
     * detection does.
     */
    fun sobel(source: FloatArray, width: Int, height: Int): Gradient {
        val magnitude = FloatArray(source.size)
        val direction = ByteArray(source.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val tl = source[i - width - 1]; val tc = source[i - width]; val tr = source[i - width + 1]
                val ml = source[i - 1];                                     val mr = source[i + 1]
                val bl = source[i + width - 1]; val bc = source[i + width]; val br = source[i + width + 1]

                val gx = (tr + 2f * mr + br) - (tl + 2f * ml + bl)
                val gy = (bl + 2f * bc + br) - (tl + 2f * tc + tr)
                magnitude[i] = sqrt((gx * gx + gy * gy).toDouble()).toFloat()

                var angle = Math.toDegrees(kotlin.math.atan2(gy.toDouble(), gx.toDouble()))
                if (angle < 0) angle += 180.0
                direction[i] = when {
                    angle < 22.5 || angle >= 157.5 -> 0
                    angle < 67.5 -> 1
                    angle < 112.5 -> 2
                    else -> 3
                }
            }
        }
        return Gradient(magnitude, direction)
    }

    /**
     * Thins each gradient ridge to a single pixel by keeping only the local maximum along the
     * gradient direction.
     *
     * Without this a drawn line comes back as a band three to five pixels wide, and any tracer
     * walking that band wanders across its width instead of along its length - which is exactly
     * what makes the output look like a random scribble rather than an analysis of the drawing.
     */
    fun nonMaximumSuppression(gradient: Gradient, width: Int, height: Int): FloatArray {
        val magnitude = gradient.magnitude
        val out = FloatArray(magnitude.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val m = magnitude[i]
                if (m <= 0f) continue
                // The two neighbours lying along this pixel's gradient direction.
                val (a, b) = when (gradient.directionBucket[i].toInt()) {
                    0 -> magnitude[i - 1] to magnitude[i + 1]
                    1 -> magnitude[i - width + 1] to magnitude[i + width - 1]
                    2 -> magnitude[i - width] to magnitude[i + width]
                    else -> magnitude[i - width - 1] to magnitude[i + width + 1]
                }
                if (m >= a && m >= b) out[i] = m
            }
        }
        return out
    }

    /**
     * Double-threshold with connectivity, the second half of Canny.
     *
     * A single threshold forces an impossible choice: high enough to reject noise also breaks
     * every faint or slightly-varying line into disconnected fragments. Keeping anything above
     * [highThreshold] outright, plus anything above [lowThreshold] that can be reached from a
     * strong pixel, preserves whole lines while still discarding isolated speckle.
     */
    fun hysteresis(
        thinned: FloatArray,
        width: Int,
        height: Int,
        lowThreshold: Float,
        highThreshold: Float
    ): BooleanArray {
        val edges = BooleanArray(thinned.size)
        val stack = ArrayDeque<Int>()

        for (i in thinned.indices) {
            if (thinned[i] >= highThreshold) {
                edges[i] = true
                stack.addLast(i)
            }
        }

        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % width
            val y = i / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val n = ny * width + nx
                    if (!edges[n] && thinned[n] >= lowThreshold) {
                        edges[n] = true
                        stack.addLast(n)
                    }
                }
            }
        }
        return edges
    }

    /**
     * Picks the threshold from the image's own gradient histogram: everything above the
     * [keepFraction] strongest gradients becomes an edge. A fixed constant either floods a faint
     * sketch with noise or erases a light one entirely, depending only on how the scan was lit.
     */
    fun adaptiveThreshold(magnitude: FloatArray, keepFraction: Float): Float {
        require(keepFraction > 0f && keepFraction < 1f) { "keepFraction must be between 0 and 1" }

        // A histogram rather than a sort. The obvious `magnitude.filter { it > 0f }.sorted()`
        // boxes every value into a List<Float> - on a 12-megapixel phone photo that is tens of
        // millions of boxed objects and hundreds of megabytes, which is an OutOfMemoryError on a
        // real device rather than a slow path. This is O(n) with one fixed 4KB array.
        var maxMagnitude = 0f
        var nonZeroCount = 0
        for (m in magnitude) {
            if (m > 0f) {
                nonZeroCount++
                if (m > maxMagnitude) maxMagnitude = m
            }
        }
        if (nonZeroCount == 0 || maxMagnitude <= 0f) return Float.MAX_VALUE

        val buckets = 1024
        val histogram = IntArray(buckets)
        val scale = (buckets - 1) / maxMagnitude
        for (m in magnitude) {
            if (m > 0f) histogram[(m * scale).toInt().coerceIn(0, buckets - 1)]++
        }

        // Walk down from the strongest bucket until the strongest [keepFraction] of edge pixels
        // has been covered; that bucket's lower bound is the threshold.
        val target = (nonZeroCount * keepFraction).toInt().coerceAtLeast(1)
        var running = 0
        for (bucket in buckets - 1 downTo 0) {
            running += histogram[bucket]
            if (running >= target) return bucket / scale
        }
        return 0f
    }

    fun detect(
        argb: IntArray,
        width: Int,
        height: Int,
        blurRadius: Int = 1,
        keepFraction: Float = 0.08f
    ): EdgeMask {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(argb.size == width * height) { "Pixel buffer does not match the given dimensions" }

        val luminance = toLuminance(argb)
        val blurred = boxBlur(luminance, width, height, blurRadius)
        val gradient = sobel(blurred, width, height)
        // Thin first, then threshold. Thresholding a thick gradient band keeps the whole band,
        // which is what produced multi-pixel-wide "edges" that no tracer can follow cleanly.
        val thinned = nonMaximumSuppression(gradient, width, height)
        val high = adaptiveThreshold(thinned, keepFraction)
        // The conventional Canny ratio: the weak threshold at 40% of the strong one is low enough
        // to bridge gaps in a faint line without dragging in unrelated texture.
        val low = high * 0.4f
        val edges = hysteresis(thinned, width, height, low, high)
        return EdgeMask(width, height, edges)
    }
}
