package com.procreate.android.tools

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF

class SelectionTool {

    enum class SelectionMode {
        FREEHAND, RECTANGLE, ELLIPSE, MAGIC_WAND
    }

    var currentMode: SelectionMode = SelectionMode.FREEHAND
    private val selectionPath = Path()
    private val currentPoints = mutableListOf<PointF>()

    fun startSelection(x: Float, y: Float) {
        currentPoints.clear()
        selectionPath.reset()
        selectionPath.moveTo(x, y)
        currentPoints.add(PointF(x, y))
    }

    fun updateSelection(x: Float, y: Float) {
        when (currentMode) {
            SelectionMode.FREEHAND -> {
                selectionPath.lineTo(x, y)
                currentPoints.add(PointF(x, y))
            }
            SelectionMode.RECTANGLE -> {
                val start = currentPoints.first()
                selectionPath.reset()
                selectionPath.addRect(start.x, start.y, x, y, Path.Direction.CW)
            }
            SelectionMode.ELLIPSE -> {
                val start = currentPoints.first()
                selectionPath.reset()
                val rect = RectF(start.x, start.y, x, y)
                selectionPath.addOval(rect, Path.Direction.CW)
            }
            SelectionMode.MAGIC_WAND -> {
                // Implement flood fill selection based on color tolerance
            }
        }
    }

    fun endSelection() {
        if (currentMode == SelectionMode.FREEHAND) {
            selectionPath.close()
        }
    }

    fun invertSelection(width: Float, height: Float) {
        val fullRect = Path()
        fullRect.addRect(0f, 0f, width, height, Path.Direction.CW)
        selectionPath.op(fullRect, selectionPath, Path.Op.REVERSE_DIFFERENCE)
    }

    fun featherSelection(radius: Float) {
        // Implementation for feathering selection path using BlurMaskFilter or similar rendering techniques
    }

    fun getSelectionPath(): Path {
        return selectionPath
    }
}
