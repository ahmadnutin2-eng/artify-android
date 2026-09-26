package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sin

/**
 * Phase 2B2: the snap replaces the freehand line and is painted by the brush engine. Mirrors what
 * DrawingView.commitQuickShape does, on the real view model and engine.
 */
@RunWith(AndroidJUnit4::class)
class QuickShapeCommitInstrumentedTest {

    private val size = 600

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun engine(vm: CanvasViewModel, index: Int, configure: BrushProperties.() -> Unit = {}) =
        BrushEngine().apply {
            color = Color.BLACK
            properties = BrushProperties(
                size = 12f, pressureSizeScale = 0f, pressureOpacityScale = 0f, velocitySizeMin = 1f,
                tipType = BrushTipType.ROUND_HARD
            ).apply(configure)
            beforeWrite = { vm.captureBeforeEdit(index, it) }
        }

    private fun wobblyLine(): List<PointF> = (0..60).map { i ->
        PointF(100f + i * 6f, 300f + 18f * sin(i * 0.9f))
    }

    private fun paint(engine: BrushEngine, target: Bitmap, points: FloatArray) {
        val canvas = Canvas(target)
        engine.startStroke(points[0], points[1], 1f)
        var i = 2
        while (i < points.size) { engine.strokeTo(canvas, target, points[i], points[i + 1], 1f); i += 2 }
        engine.endStroke(canvas, target)
    }

    private fun freehandThenSnap(vm: CanvasViewModel, index: Int, target: Bitmap, e: BrushEngine) {
        val freehand = wobblyLine().flatMap { listOf(it.x, it.y) }.toFloatArray()
        vm.prepareEdit(index)
        paint(e, target, freehand)
        vm.restoreCapturedTiles(index)
        val shape = QuickShapeEngine.sample(
            QuickShape.Line(PointF(100f, 300f), PointF(460f, 300f)), e.shapeSampleSpacing(1f)
        )
        paint(e, target, shape)
    }

    @Test
    fun snapLeavesNoFreehandResidueAndIsOneUndoStep() = onMain {
        val vm = CanvasViewModel().apply { initialize(size, size) }
        val index = vm.layers.value!!.indexOfLast { !it.isBackground }
        val target = vm.layers.value!![index].bitmap
        val before = target.copy(Bitmap.Config.ARGB_8888, false)
        val e = engine(vm, index)
        freehandThenSnap(vm, index, target, e)
        vm.commitPixelEdit(index, android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()))

        // Outside the snapped line's band (y 300 +/- 10) nothing may differ from before the gesture.
        var residue = 0
        for (y in 0 until size) {
            if (abs(y - 300) <= 10) continue
            for (x in 0 until size) if (target.getPixel(x, y) != before.getPixel(x, y)) residue++
        }
        assertEquals(0, residue)
        assertTrue(Color.alpha(target.getPixel(280, 300)) > 200)

        vm.undo()
        assertEquals(before.getPixel(280, 300), target.getPixel(280, 300))
        assertEquals(before.getPixel(106, 316), target.getPixel(106, 316))
        vm.redo()
        assertTrue(Color.alpha(target.getPixel(280, 300)) > 200)
    }

    @Test
    fun lineWeightIsTheSameAtEveryZoom() = onMain {
        val widths = listOf(0.25f, 1f, 4f).map { zoom ->
            val vm = CanvasViewModel().apply { initialize(size, size) }
            val index = vm.layers.value!!.indexOfLast { !it.isBackground }
            val target = vm.layers.value!![index].bitmap
            val e = engine(vm, index).apply { renderScale = zoom }
            freehandThenSnap(vm, index, target, e)
            (0 until size).count { y -> Color.alpha(target.getPixel(280, y)) > 127 }
        }
        assertTrue("widths $widths", widths.max() - widths.min() <= 1)
        // And the preview width matches within a pixel.
        val e = BrushEngine().apply { properties = BrushProperties(size = 12f, pressureSizeScale = 0f) }
        assertTrue(abs(e.previewSize(1f) - widths[1]) <= 1.5f)
    }

    @Test
    fun eraserSnapRemovesPaintAndNeverAddsColour() = onMain {
        val vm = CanvasViewModel().apply { initialize(size, size) }
        val index = vm.layers.value!!.indexOfLast { !it.isBackground }
        val target = vm.layers.value!![index].bitmap
        target.eraseColor(Color.RED)
        val e = engine(vm, index) { type = BrushType.Eraser }
        freehandThenSnap(vm, index, target, e)
        assertTrue(Color.alpha(target.getPixel(280, 300)) < 30)
        for (x in 0 until size step 7) for (y in 0 until size step 7) {
            val c = target.getPixel(x, y)
            // Wherever anything is left it is still the red that was there; no brush colour added.
            if (Color.alpha(c) > 8) assertTrue(Color.green(c) < 8 && Color.blue(c) < 8)
        }
    }
}
