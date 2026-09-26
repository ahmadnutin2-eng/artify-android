package com.procreate.android.tools

import android.graphics.Matrix
import android.graphics.PointF

class TransformTool {
    
    private val transformMatrix = Matrix()
    private val pivotPoint = PointF(0f, 0f)

    fun move(dx: Float, dy: Float) {
        transformMatrix.postTranslate(dx, dy)
    }

    fun scale(scaleFactor: Float, pivotX: Float, pivotY: Float) {
        transformMatrix.postScale(scaleFactor, scaleFactor, pivotX, pivotY)
    }

    fun scaleNonUniform(scaleX: Float, scaleY: Float, pivotX: Float, pivotY: Float) {
        transformMatrix.postScale(scaleX, scaleY, pivotX, pivotY)
    }

    /** Scales in the transformed artwork's own axes rather than the screen axes. */
    fun scaleAlongAxes(
        scaleX: Float,
        scaleY: Float,
        pivotX: Float,
        pivotY: Float,
        axisDegrees: Float
    ) {
        transformMatrix.postRotate(-axisDegrees, pivotX, pivotY)
        transformMatrix.postScale(scaleX, scaleY, pivotX, pivotY)
        transformMatrix.postRotate(axisDegrees, pivotX, pivotY)
    }

    fun rotate(degrees: Float, pivotX: Float, pivotY: Float) {
        transformMatrix.postRotate(degrees, pivotX, pivotY)
    }

    fun flipHorizontal(centerX: Float) {
        transformMatrix.postScale(-1f, 1f, centerX, 0f)
    }

    fun flipVertical(centerY: Float) {
        transformMatrix.postScale(1f, -1f, 0f, centerY)
    }

    fun distort(srcPts: FloatArray, dstPts: FloatArray) {
        // Uses 8 points (4 corners x, y)
        transformMatrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
    }

    fun warp(mesh: FloatArray) {
        // Warp transformation logic via drawBitmapMesh
    }

    fun getMatrix(): Matrix {
        return transformMatrix
    }
    
    fun reset() {
        transformMatrix.reset()
    }
}
