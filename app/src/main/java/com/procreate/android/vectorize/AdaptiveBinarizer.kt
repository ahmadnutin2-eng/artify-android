package com.procreate.android.vectorize

import kotlin.math.sqrt

/**
 * Separates ink from paper using a threshold computed per pixel from its own neighbourhood.
 *
 * A single global threshold fails on exactly the images people actually have: a plan photographed
 * on a desk is brighter on one side than the other, so any one cut-off either loses the lines in
 * the shaded half or floods the lit half with paper texture. Sauvola's local rule adapts to that
 * gradient, and its standard-deviation term additionally suppresses flat regions - meaning blank
 * paper stays blank even where it is slightly grubby.
 *
 * Implemented over integral images so each pixel's local mean and variance are four array lookups
 * regardless of window size: the naive version is O(width * height * window^2) and is far too slow
 * for a full-page scan.
 */
object AdaptiveBinarizer {

    /**
     * @param windowRadius half-width of the neighbourhood, in pixels.
     * @param k Sauvola's sensitivity. Higher removes more faint material; 0.34 is a good default
     * for pencil and print alike.
     * @return true where the pixel is ink (darker than its local threshold).
     */
    fun binarize(
        luminance: FloatArray,
        width: Int,
        height: Int,
        windowRadius: Int = 12,
        k: Float = 0.34f
    ): BooleanArray {
        require(luminance.size == width * height) { "Luminance buffer does not match the dimensions" }
        require(windowRadius > 0) { "windowRadius must be positive" }

        val sum = DoubleArray((width + 1) * (height + 1))
        val sumSquares = DoubleArray((width + 1) * (height + 1))
        val stride = width + 1

        for (y in 0 until height) {
            var rowSum = 0.0
            var rowSumSquares = 0.0
            for (x in 0 until width) {
                val v = luminance[y * width + x].toDouble()
                rowSum += v
                rowSumSquares += v * v
                sum[(y + 1) * stride + (x + 1)] = sum[y * stride + (x + 1)] + rowSum
                sumSquares[(y + 1) * stride + (x + 1)] = sumSquares[y * stride + (x + 1)] + rowSumSquares
            }
        }

        fun areaSum(buffer: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int): Double =
            buffer[(y1 + 1) * stride + (x1 + 1)] -
                buffer[y0 * stride + (x1 + 1)] -
                buffer[(y1 + 1) * stride + x0] +
                buffer[y0 * stride + x0]

        val ink = BooleanArray(luminance.size)
        val dynamicRange = 128.0
        for (y in 0 until height) {
            val y0 = (y - windowRadius).coerceAtLeast(0)
            val y1 = (y + windowRadius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - windowRadius).coerceAtLeast(0)
                val x1 = (x + windowRadius).coerceAtMost(width - 1)
                val count = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()

                val mean = areaSum(sum, x0, y0, x1, y1) / count
                val meanSquares = areaSum(sumSquares, x0, y0, x1, y1) / count
                // Clamped because floating-point cancellation can push a flat region very slightly
                // negative, and sqrt of that is NaN - which would silently mark the area as ink.
                val variance = (meanSquares - mean * mean).coerceAtLeast(0.0)
                val stdDev = sqrt(variance)

                val threshold = mean * (1.0 + k * (stdDev / dynamicRange - 1.0))
                ink[y * width + x] = luminance[y * width + x] < threshold
            }
        }
        return ink
    }
}
