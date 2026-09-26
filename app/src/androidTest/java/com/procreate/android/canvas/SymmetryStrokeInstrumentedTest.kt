package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sin

/**
 * Phase 2B1: every symmetry branch is its own stroke. Runs the real engine on real bitmaps.
 */
@RunWith(AndroidJUnit4::class)
class SymmetryStrokeInstrumentedTest {

    private val width = 1000
    private val height = 800
    private val centerX = width / 2f
    private val centerY = height / 2f

    private fun blank() = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.TRANSPARENT)
    }

    /** Deterministic brush: no jitter or scatter, so a mirror has to be an exact reflection. */
    private fun engine(configure: BrushProperties.() -> Unit = {}): BrushEngine = BrushEngine().apply {
        color = Color.BLACK
        renderScale = 1f
        properties = BrushProperties(
            size = 24f,
            spacing = 0.05f,
            pressureSizeScale = 0f,
            pressureOpacityScale = 0f,
            velocitySizeMin = 1f,
            tipType = BrushTipType.ROUND_HARD
        ).apply(configure)
    }

    /** A slow curve kept at least 150px from both axes, in the upper-left quadrant. */
    private fun curve(): List<Pair<Float, Float>> = (0..80).map { i ->
        val t = i / 80f
        (150f + 200f * t) to (120f + 110f * sin(t * Math.PI.toFloat() * 1.5f))
    }

    private fun draw(
        target: Bitmap,
        primary: BrushEngine,
        vertical: Boolean,
        horizontal: Boolean,
        azimuth: Float = 0f
    ): SymmetryStroke {
        val canvas = Canvas(target)
        val stroke = SymmetryStroke(primary)
        val points = curve()
        stroke.begin(vertical, horizontal, centerX, centerY, points[0].first, points[0].second, 1f, 0f, azimuth)
        stroke.resetDirty()
        for ((x, y) in points.drop(1)) stroke.strokeTo(canvas, target, x, y, 1f, 0f, 0f, azimuth)
        stroke.end(canvas, target)
        return stroke
    }

    private fun alpha(b: Bitmap, x: Int, y: Int) = Color.alpha(b.getPixel(x, y))

    private fun paintedInColumns(b: Bitmap, from: Int, to: Int): Int {
        var count = 0
        for (x in from until to) for (y in 0 until height) if (alpha(b, x, y) > 0) count++
        return count
    }

    private fun paintedInRows(b: Bitmap, from: Int, to: Int): Int {
        var count = 0
        for (y in from until to) for (x in 0 until width) if (alpha(b, x, y) > 0) count++
        return count
    }

    /** Fraction of pixels whose alpha differs from their reflection by more than [tolerance]. */
    private fun mirrorMismatch(b: Bitmap, mirrorX: Boolean, mirrorY: Boolean, tolerance: Int = 48): Double {
        var bad = 0
        var inked = 0
        for (y in 0 until height) for (x in 0 until width) {
            val a = alpha(b, x, y)
            if (a == 0) continue
            inked++
            val rx = if (mirrorX) width - 1 - x else x
            val ry = if (mirrorY) height - 1 - y else y
            if (abs(a - alpha(b, rx, ry)) > tolerance) bad++
        }
        assertTrue("stroke painted nothing", inked > 0)
        return bad.toDouble() / inked
    }

    @Test
    fun verticalSymmetryNeverPaintsAcrossTheAxis() {
        val target = blank()
        draw(target, engine(), vertical = true, horizontal = false)
        assertEquals(0, paintedInColumns(target, 480, 520))
        assertTrue(paintedInColumns(target, 600, 900) > 0) // the mirror exists
    }

    @Test
    fun horizontalSymmetryNeverPaintsAcrossTheAxis() {
        val target = blank()
        draw(target, engine(), vertical = false, horizontal = true)
        assertEquals(0, paintedInRows(target, 380, 420))
        assertTrue(paintedInRows(target, 500, 800) > 0)
    }

    @Test
    fun quadSymmetryPaintsFourCopiesAndNothingOnEitherAxis() {
        val target = blank()
        draw(target, engine(), vertical = true, horizontal = true)
        assertEquals(0, paintedInColumns(target, 480, 520))
        assertEquals(0, paintedInRows(target, 380, 420))
        assertTrue(alphaSum(target, 0, 0) > 0)
        assertTrue(alphaSum(target, 1, 0) > 0)
        assertTrue(alphaSum(target, 0, 1) > 0)
        assertTrue(alphaSum(target, 1, 1) > 0)
    }

    private fun alphaSum(b: Bitmap, qx: Int, qy: Int): Long {
        var sum = 0L
        for (y in qy * height / 2 until (qy + 1) * height / 2)
            for (x in qx * width / 2 until (qx + 1) * width / 2) sum += alpha(b, x, y)
        return sum
    }

    @Test
    fun verticalMirrorIsAnExactReflectionForAStrokeOrientedBrush() {
        val target = blank()
        draw(target, engine { tipType = BrushTipType.CALLIGRAPHY; orientToStroke = true; angle = 20f },
            vertical = true, horizontal = false)
        assertTrue(mirrorMismatch(target, mirrorX = true, mirrorY = false) < 0.01)
    }

    @Test
    fun mirrorsAFixedCutReedNibAsItsReflection() {
        val target = blank()
        draw(target, engine { tipType = BrushTipType.REED_PEN; orientToStroke = false; angle = 35f },
            vertical = true, horizontal = true)
        assertTrue(mirrorMismatch(target, mirrorX = true, mirrorY = false) < 0.01)
        assertTrue(mirrorMismatch(target, mirrorX = false, mirrorY = true) < 0.01)
    }

    @Test
    fun mirrorsABarrelFollowingNibAsItsReflection() {
        val target = blank()
        val primary = engine { tipType = BrushTipType.REED_PEN; azimuthTracking = 1f }
        primary.stylusAzimuthAvailable = true
        draw(target, primary, vertical = true, horizontal = false, azimuth = 0.6f)
        assertTrue(mirrorMismatch(target, mirrorX = true, mirrorY = false) < 0.01)
    }

    @Test
    fun aTapLaysOneDotPerBranch() {
        val target = blank()
        val canvas = Canvas(target)
        val stroke = SymmetryStroke(engine())
        stroke.begin(true, true, centerX, centerY, 200f, 150f, 1f, 0f, 0f)
        stroke.stampDot(canvas, target, 200f, 150f, 1f, 0f, 0f)
        for ((x, y) in listOf(200 to 150, 799 to 150, 200 to 649, 799 to 649)) {
            assertTrue("no dot at $x,$y", alpha(target, x, y) > 0)
        }
    }

    @Test
    fun liftingFlushesTheFinalSpanOnEveryBranch() {
        val target = blank()
        draw(target, engine(), vertical = true, horizontal = false)
        // The stroke ends at x = 350; its reflection must reach x = 650.
        val (endX, endY) = curve().last()
        assertTrue(alpha(target, endX.toInt(), endY.toInt()) > 0)
        assertTrue(alpha(target, (2 * centerX - endX).toInt(), endY.toInt()) > 0)
    }

    @Test
    fun dirtyRectangleCoversEveryBranch() {
        val target = blank()
        val stroke = draw(target, engine(), vertical = true, horizontal = true)
        val dirty = stroke.collectDirty(RectF())
        assertTrue(dirty.left <= 150f && dirty.right >= 850f)
        assertTrue(dirty.top <= 120f && dirty.bottom >= 680f)
    }

    @Test
    fun beforeWriteSeesMirroredWritesSoUndoCapturesThem() {
        val target = blank()
        val touched = RectF()
        val primary = engine().apply { beforeWrite = { touched.union(it) } }
        draw(target, primary, vertical = true, horizontal = false)
        assertTrue(touched.right >= 640f)
    }

    @Test
    fun axisFollowsTheArtworkWhenTheCanvasGrowsMidStroke() {
        val target = blank()
        val canvas = Canvas(target)
        val stroke = SymmetryStroke(engine())
        stroke.begin(true, false, centerX, centerY, 150f, 200f, 1f, 0f, 0f)
        stroke.strokeTo(canvas, target, 160f, 200f, 1f, 0f, 0f, 0f)
        stroke.offset(40f, 0f) // paper grew 40px on the left
        val branch = stroke.branchAt(0)
        // A pen now at 200 (the old 160) reflects about the shifted axis at 540.
        assertEquals(880f, stroke.mirrorX(200f, branch), 0.001f)
    }

    @Test
    fun noSymmetryRunsOnlyThePrimaryEngine() {
        val target = blank()
        val stroke = draw(target, engine(), vertical = false, horizontal = false)
        assertEquals(0, stroke.mirrorCount())
        assertEquals(0, paintedInColumns(target, 500, width))
    }
}
