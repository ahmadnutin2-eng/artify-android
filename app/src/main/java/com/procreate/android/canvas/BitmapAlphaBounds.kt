package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.Rect

/** Finds the exact non-transparent content rectangle without allocating a full-canvas IntArray. */
object BitmapAlphaBounds {
    fun find(bitmap: Bitmap, alphaThreshold: Int = 0): Rect? {
        require(alphaThreshold in 0..254)
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val row = IntArray(width)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var rowMin = width
            var rowMax = -1
            for (x in 0 until width) {
                if ((row[x] ushr 24) > alphaThreshold) {
                    if (x < rowMin) rowMin = x
                    rowMax = x
                }
            }
            if (rowMax >= 0) {
                if (rowMin < minX) minX = rowMin
                if (rowMax > maxX) maxX = rowMax
                if (y < minY) minY = y
                maxY = y
            }
        }
        return if (maxX < minX || maxY < minY) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    fun expanded(bounds: Rect, padding: Int, width: Int, height: Int): Rect = Rect(
        (bounds.left - padding).coerceAtLeast(0),
        (bounds.top - padding).coerceAtLeast(0),
        (bounds.right + padding).coerceAtMost(width),
        (bounds.bottom + padding).coerceAtMost(height)
    )
}
