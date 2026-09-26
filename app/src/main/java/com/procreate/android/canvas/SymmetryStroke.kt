package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF

/**
 * Runs one stroke through the primary engine and one extra engine per mirrored branch.
 *
 * Each engine keeps its own spline window, spacing accumulator and dirty rectangle. Feeding the
 * reflected samples into the primary engine instead, as the canvas used to, interleaved real and
 * mirrored points in one Catmull-Rom window, so the engine painted spans joining the stroke to its
 * own reflection: straight streaks across the symmetry axis.
 *
 * The axis is captured when the stroke starts and moves only with [offset], so it stays attached
 * to the artwork if an open canvas grows mid-stroke instead of jumping to the new bitmap centre.
 */
internal class SymmetryStroke(private val primary: BrushEngine) {

    /** Engines kept between strokes, so starting a stroke never allocates. */
    private val pool = ArrayList<BrushEngine>(3)
    private val mirrors = ArrayList<BrushEngine>(3)
    private val branches = ArrayList<SymmetryMath.Branch>(3)
    private var centerX = 0f
    private var centerY = 0f

    val hasMirrors: Boolean get() = mirrors.isNotEmpty()

    /**
     * Start a stroke. The primary engine must already carry the stroke's brush, colour and render
     * scale; the mirrors copy them from it here.
     */
    fun begin(
        vertical: Boolean,
        horizontal: Boolean,
        centerX: Float,
        centerY: Float,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        azimuth: Float
    ) {
        this.centerX = centerX
        this.centerY = centerY
        mirrors.clear()
        branches.clear()
        primary.startStroke(x, y, pressure, tilt, azimuth)
        SymmetryMath.branches(vertical, horizontal).forEachIndexed { index, branch ->
            val engine = pool.getOrNull(index) ?: BrushEngine().also { pool.add(it) }
            engine.copyStrokeSetupFrom(primary)
            engine.mirrorX = branch.mirrorX
            engine.mirrorY = branch.mirrorY
            engine.resetDirty()
            engine.startStroke(mirrorX(x, branch), mirrorY(y, branch), pressure, tilt, azimuth)
            mirrors.add(engine)
            branches.add(branch)
        }
    }

    /**
     * Begin a fresh stroke on the same branches and axis as the current one, at ([x], [y]). Used
     * when the gesture replaces what it drew so far, as a QuickShape does at lift.
     */
    fun restart(x: Float, y: Float, pressure: Float, tilt: Float, azimuth: Float) {
        primary.startStroke(x, y, pressure, tilt, azimuth)
        for (i in mirrors.indices) {
            val branch = branches[i]
            mirrors[i].startStroke(mirrorX(x, branch), mirrorY(y, branch), pressure, tilt, azimuth)
        }
    }

    val axisX: Float get() = centerX
    val axisY: Float get() = centerY

    fun strokeTo(
        canvas: Canvas,
        target: Bitmap,
        x: Float,
        y: Float,
        pressure: Float,
        velocity: Float,
        tilt: Float,
        azimuth: Float
    ) {
        primary.strokeTo(canvas, target, x, y, pressure, velocity, tilt, azimuth)
        for (i in mirrors.indices) {
            val engine = mirrors[i]
            val branch = branches[i]
            // Zoom can change during a long stroke (edge pan), and spacing follows it.
            engine.renderScale = primary.renderScale
            // The raw barrel angle, not a reflected one: the engine reflects the finished stamp.
            engine.strokeTo(
                canvas, target, mirrorX(x, branch), mirrorY(y, branch),
                pressure, velocity, tilt, azimuth
            )
        }
    }

    fun stampDot(canvas: Canvas, target: Bitmap, x: Float, y: Float, pressure: Float, tilt: Float, azimuth: Float) {
        primary.stampDot(canvas, target, x, y, pressure, tilt, azimuth)
        for (i in mirrors.indices) {
            val branch = branches[i]
            mirrors[i].stampDot(canvas, target, mirrorX(x, branch), mirrorY(y, branch), pressure, tilt, azimuth)
        }
    }

    fun smudgeSegment(canvas: Canvas, target: Bitmap, fromX: Float, fromY: Float, toX: Float, toY: Float, pressure: Float) {
        primary.drawSmudgeSegment(canvas, target, fromX, fromY, toX, toY, pressure)
        for (i in mirrors.indices) {
            val b = branches[i]
            mirrors[i].drawSmudgeSegment(
                canvas, target,
                mirrorX(fromX, b), mirrorY(fromY, b), mirrorX(toX, b), mirrorY(toY, b), pressure
            )
        }
    }

    fun blurSegment(canvas: Canvas, target: Bitmap, fromX: Float, fromY: Float, toX: Float, toY: Float, pressure: Float) {
        primary.drawBlurSegment(canvas, target, fromX, fromY, toX, toY, pressure)
        for (i in mirrors.indices) {
            val b = branches[i]
            mirrors[i].drawBlurSegment(
                canvas, target,
                mirrorX(fromX, b), mirrorY(fromY, b), mirrorX(toX, b), mirrorY(toY, b), pressure
            )
        }
    }

    /** Calls [action] with every reflection of ([x], [y]); used by tools that do not stamp. */
    inline fun forEachMirroredPoint(x: Float, y: Float, action: (Float, Float) -> Unit) {
        for (i in 0 until mirrorCount()) {
            val b = branchAt(i)
            action(mirrorX(x, b), mirrorY(y, b))
        }
    }

    fun mirrorCount(): Int = branches.size
    fun branchAt(index: Int): SymmetryMath.Branch = branches[index]

    /**
     * Flush every branch's final span. Mirrors stay registered until the next [begin], so the batch
     * that ends the stroke can still collect their dirty rectangles.
     */
    fun end(canvas: Canvas?, target: Bitmap?) {
        primary.endStroke(canvas, target)
        for (engine in mirrors) engine.endStroke(canvas, target)
    }

    fun setDeferStrokeOpacity(defer: Boolean) {
        primary.deferStrokeOpacity = defer
        for (engine in mirrors) engine.deferStrokeOpacity = defer
    }

    fun resetDirty() {
        primary.resetDirty()
        for (engine in mirrors) engine.resetDirty()
    }

    /** Everything any branch painted since [resetDirty], written into [out]. */
    fun collectDirty(out: RectF): RectF {
        out.set(primary.dirtyRect)
        for (engine in mirrors) {
            if (!engine.dirtyRect.isEmpty) out.union(engine.dirtyRect)
        }
        return out
    }

    /**
     * The paper grew and every existing pixel moved by ([dx], [dy]). The axis moves with the art,
     * and so does each mirror's spline: its points are reflections about an axis that shifted by
     * the same amount, so they shift by exactly that amount too.
     */
    fun offset(dx: Float, dy: Float) {
        centerX += dx
        centerY += dy
        primary.offsetActiveStroke(dx, dy)
        for (engine in mirrors) engine.offsetActiveStroke(dx, dy)
    }

    fun mirrorX(x: Float, branch: SymmetryMath.Branch): Float = SymmetryMath.reflectX(x, centerX, branch)
    fun mirrorY(y: Float, branch: SymmetryMath.Branch): Float = SymmetryMath.reflectY(y, centerY, branch)
}
