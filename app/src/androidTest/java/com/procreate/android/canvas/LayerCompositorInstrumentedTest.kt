package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayerCompositorInstrumentedTest {

    private fun bitmap(width: Int = 8, height: Int = 8, color: Int = Color.TRANSPARENT) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun clippingMaskNeverPaintsOutsideBaseAlpha() {
        val baseBitmap = bitmap()
        baseBitmap.setPixel(4, 4, Color.rgb(200, 200, 200))
        val clippedBitmap = bitmap(color = Color.RED)
        val output = bitmap()

        LayerCompositor.draw(
            Canvas(output),
            listOf(
                Layer("base", "Base", baseBitmap),
                Layer("clip", "Clip", clippedBitmap, isClippingMask = true)
            )
        )

        assertEquals(0, Color.alpha(output.getPixel(0, 0)))
        assertEquals(Color.RED, output.getPixel(4, 4))
    }

    @Test
    fun clippedLayerRetainsItsMultiplyBlendMode() {
        val baseBitmap = bitmap(color = Color.rgb(200, 200, 200))
        val clippedBitmap = bitmap(color = Color.RED)
        val output = bitmap()

        LayerCompositor.draw(
            Canvas(output),
            listOf(
                Layer("base", "Base", baseBitmap),
                Layer(
                    "clip", "Clip", clippedBitmap,
                    blendMode = BlendMode.Multiply,
                    isClippingMask = true
                )
            )
        )

        val pixel = output.getPixel(4, 4)
        assertTrue("multiply must preserve the darker base red", Color.red(pixel) in 195..205)
        assertEquals(0, Color.green(pixel))
        assertEquals(0, Color.blue(pixel))
    }

    @Test
    fun mergeDownBakesClippingWithoutRevealingHiddenPixels() {
        val baseBitmap = bitmap()
        baseBitmap.setPixel(4, 4, Color.WHITE)
        val clippedBitmap = bitmap(color = Color.BLUE)
        val output = bitmap()

        LayerCompositor.drawMergedPair(
            Canvas(output),
            Layer("base", "Base", baseBitmap),
            Layer("clip", "Clip", clippedBitmap, isClippingMask = true)
        )

        assertEquals(0, Color.alpha(output.getPixel(0, 0)))
        assertEquals(Color.BLUE, output.getPixel(4, 4))
    }

    @Test
    fun grayscaleLayerMaskRevealsTheLayerBelowWithoutDeletingUpperPixels() {
        val lower = bitmap(color = Color.BLUE)
        val upper = bitmap(color = Color.RED)
        val mask = bitmap(color = Color.WHITE).apply {
            setPixel(2, 3, Color.BLACK)
            setPixel(3, 3, Color.rgb(128, 128, 128))
        }
        val output = bitmap()

        LayerCompositor.draw(
            Canvas(output),
            listOf(
                Layer("lower", "Lower", lower),
                Layer("upper", "Upper", upper, maskBitmap = mask)
            )
        )

        assertEquals(Color.RED, output.getPixel(4, 4))
        assertEquals(Color.BLUE, output.getPixel(2, 3))
        val half = output.getPixel(3, 3)
        assertTrue("gray mask must partially reveal both layers", Color.red(half) in 120..136)
        assertTrue("gray mask must partially reveal both layers", Color.blue(half) in 119..136)
        // A mask is non-destructive: the hidden source pixel remains untouched.
        assertEquals(Color.RED, upper.getPixel(2, 3))
    }

    @Test
    fun maskBrushEditHasIndependentUndoAndRedo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = CanvasViewModel().apply { initialize(512, 512) }
            val paintLayerIndex = viewModel.layers.value!!.indexOfLast { !it.isBackground }
            assertTrue(viewModel.addOrToggleLayerMask(paintLayerIndex))
            val layer = viewModel.layers.value!![paintLayerIndex]
            val mask = layer.maskBitmap!!

            viewModel.prepareEdit(paintLayerIndex)
            viewModel.captureBeforeEdit(paintLayerIndex, RectF(8f, 8f, 20f, 20f))
            mask.setPixel(12, 12, Color.BLACK)
            viewModel.commitPixelEdit(paintLayerIndex, RectF(8f, 8f, 20f, 20f))
            assertEquals(Color.BLACK, mask.getPixel(12, 12))

            viewModel.undo()
            assertEquals(Color.WHITE, mask.getPixel(12, 12))
            viewModel.redo()
            assertEquals(Color.BLACK, mask.getPixel(12, 12))
        }
    }

    @Test
    fun distantStrokeSamplesUndoAsSparseTiles() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = CanvasViewModel().apply { initialize(2048, 2048) }
            val bitmap = viewModel.layers.value!!.last().bitmap
            viewModel.prepareEdit(1)
            viewModel.captureBeforeEdit(1, RectF(8f, 8f, 20f, 20f))
            bitmap.setPixel(12, 12, Color.RED)
            viewModel.captureBeforeEdit(1, RectF(1800f, 1800f, 1815f, 1815f))
            bitmap.setPixel(1808, 1808, Color.BLUE)
            viewModel.commitPixelEdit(1, RectF(8f, 8f, 1815f, 1815f))

            viewModel.undo()
            assertEquals(0, Color.alpha(bitmap.getPixel(12, 12)))
            assertEquals(0, Color.alpha(bitmap.getPixel(1808, 1808)))
        }
    }

    @Test
    fun blurBrushSoftensOnlyItsCoveredRegionWithoutDarkHalo() {
        val target = bitmap(96, 64)
        val canvas = Canvas(target)
        canvas.drawRect(0f, 0f, 48f, 64f, android.graphics.Paint().apply { color = Color.WHITE })
        canvas.drawRect(48f, 0f, 96f, 64f, android.graphics.Paint().apply { color = Color.BLACK })
        val engine = BrushEngine().apply {
            properties = BrushProperties(size = 28f, opacity = 1f, spacing = 0.08f)
        }

        engine.drawBlurSegment(canvas, target, 48f, 18f, 48f, 46f, 1f)

        val softenedWhite = Color.red(target.getPixel(46, 32))
        val softenedBlack = Color.red(target.getPixel(50, 32))
        assertTrue("white side should mix toward black", softenedWhite in 1..254)
        assertTrue("black side should mix toward white", softenedBlack in 1..254)
        assertEquals("outside the brush remains unchanged", Color.WHITE, target.getPixel(8, 8))
        assertEquals("outside the brush remains unchanged", Color.BLACK, target.getPixel(88, 8))
    }

    @Test
    fun alphaBoundsFindsOnlyVisibleImageContent() {
        val source = bitmap(80, 60)
        Canvas(source).drawRect(17f, 11f, 63f, 49f, android.graphics.Paint().apply {
            color = Color.RED
        })

        assertEquals(android.graphics.Rect(17, 11, 63, 49), BitmapAlphaBounds.find(source))
    }

    @Test
    fun boundedLayerFilterCommitUndoesAndRedoesOnlyItsRegion() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = CanvasViewModel().apply { initialize(96, 96) }
            val index = viewModel.layers.value!!.indexOfLast { !it.isBackground }
            val layer = viewModel.layers.value!![index]
            val before = bitmap(12, 10)
            Canvas(layer.bitmap).drawRect(21f, 18f, 33f, 28f, android.graphics.Paint().apply {
                color = Color.MAGENTA
            })

            assertTrue(viewModel.commitLayerRegionEdit(index, 21, 18, before))
            assertEquals(Color.MAGENTA, layer.bitmap.getPixel(25, 22))
            viewModel.undo()
            assertEquals(0, Color.alpha(layer.bitmap.getPixel(25, 22)))
            viewModel.redo()
            assertEquals(Color.MAGENTA, layer.bitmap.getPixel(25, 22))
            // A sparse filter undo must not touch pixels outside its snapshot.
            assertEquals(0, Color.alpha(layer.bitmap.getPixel(70, 70)))
        }
    }

    @Test
    fun transformAxisScalingUsesTheRotatedArtworkAxis() {
        val tool = com.procreate.android.tools.TransformTool()
        tool.scaleAlongAxes(2f, 1f, 0f, 0f, 90f)
        val points = floatArrayOf(0f, 10f, 10f, 0f)
        tool.getMatrix().mapPoints(points)

        assertTrue("rotated x axis should scale vertically", kotlin.math.abs(points[1] - 20f) < 0.01f)
        assertTrue("perpendicular axis should stay unchanged", kotlin.math.abs(points[2] - 10f) < 0.01f)
    }

    @Test
    fun expandingMidStrokePreservesUndoBeforeImage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = CanvasViewModel().apply {
                isOpenCanvas = true
                initialize(2048, 1536)
            }
            val layer = viewModel.layers.value!!.single()
            viewModel.prepareEdit(0)
            viewModel.captureBeforeEdit(0, RectF(4f, 696f, 36f, 706f))
            layer.bitmap.setPixel(8, 700, Color.RED)

            val offset = viewModel.expandIfNeeded(RectF(0f, 680f, 20f, 720f))
            assertNotNull(offset)
            val shiftedX = 8 + offset!!.x.toInt()
            viewModel.captureBeforeEdit(0, RectF(
                shiftedX + 16f, 696f, shiftedX + 32f, 706f
            ))
            layer.bitmap.setPixel(shiftedX + 20, 700, Color.BLUE)
            viewModel.commitPixelEdit(
                0,
                RectF(shiftedX - 2f, 695f, shiftedX + 26f, 705f)
            )

            viewModel.undo()

            assertEquals(0, Color.alpha(layer.bitmap.getPixel(shiftedX, 700)))
            assertEquals(0, Color.alpha(layer.bitmap.getPixel(shiftedX + 20, 700)))
        }
    }

    @Test
    fun clippingAndBlendChangesParticipateInUndo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = CanvasViewModel().apply { initialize(256, 256) }
            viewModel.addLayer(256, 256)
            val upper = viewModel.layers.value!!.lastIndex

            viewModel.setLayerClipping(upper, true)
            assertTrue(viewModel.layers.value!![upper].isClippingMask)
            viewModel.undo()
            assertTrue(!viewModel.layers.value!![upper].isClippingMask)
            viewModel.redo()
            assertTrue(viewModel.layers.value!![upper].isClippingMask)

            viewModel.setLayerBlendMode(upper, BlendMode.Multiply)
            assertEquals(BlendMode.Multiply, viewModel.layers.value!![upper].blendMode)
            viewModel.undo()
            assertEquals(BlendMode.Normal, viewModel.layers.value!![upper].blendMode)
        }
    }
}
