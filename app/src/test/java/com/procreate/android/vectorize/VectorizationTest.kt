package com.procreate.android.vectorize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorizationTest {

    // ---------------------------------------------------------------- simplifier

    @Test
    fun `a straight staircase collapses to its two endpoints`() {
        // What a traced pixel contour of a straight line actually looks like.
        val staircase = (0..20).map { Vec2(it.toFloat(), 0f) }
        val simplified = PathSimplifier.simplify(staircase, epsilon = 1f)
        assertEquals(2, simplified.size)
        assertEquals(0f, simplified.first().x, 1e-4f)
        assertEquals(20f, simplified.last().x, 1e-4f)
    }

    @Test
    fun `a real corner survives simplification`() {
        val corner = listOf(
            Vec2(0f, 0f), Vec2(5f, 0f), Vec2(10f, 0f),
            Vec2(10f, 5f), Vec2(10f, 10f)
        )
        val simplified = PathSimplifier.simplify(corner, epsilon = 1f)
        assertEquals(3, simplified.size)
        assertEquals(10f, simplified[1].x, 1e-4f)
        assertEquals(0f, simplified[1].y, 1e-4f)
    }

    @Test
    fun `simplification never drops the endpoints`() {
        val noisy = (0..50).map { Vec2(it.toFloat(), if (it % 2 == 0) 0f else 0.4f) }
        val simplified = PathSimplifier.simplify(noisy, epsilon = 2f)
        assertEquals(noisy.first(), simplified.first())
        assertEquals(noisy.last(), simplified.last())
    }

    @Test
    fun `a degenerate segment falls back to plain distance`() {
        val d = PathSimplifier.perpendicularDistance(Vec2(3f, 4f), Vec2(0f, 0f), Vec2(0f, 0f))
        assertEquals(5f, d, 1e-4f)
    }

    @Test
    fun `an almost closed ring is closed and a clearly open path is left alone`() {
        val ring = listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 10f), Vec2(0f, 10f), Vec2(1f, 1f))
        val (closedPoints, wasClosed) = PathSimplifier.closeIfNearlyClosed(ring, tolerancePx = 3f)
        assertTrue(wasClosed)
        assertEquals(4, closedPoints.size)

        val open = listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 10f), Vec2(0f, 40f))
        val (openPoints, stillOpen) = PathSimplifier.closeIfNearlyClosed(open, tolerancePx = 3f)
        assertTrue(!stillOpen)
        assertEquals(4, openPoints.size)
    }

    // ---------------------------------------------------------------- edge detection

    @Test
    fun `luminance weights green most`() {
        val lum = EdgeDetector.toLuminance(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertTrue("green must read brighter than red", lum[1] > lum[0])
        assertTrue("red must read brighter than blue", lum[0] > lum[2])
    }

    @Test
    fun `a hard vertical boundary produces edges along that boundary`() {
        val w = 20; val h = 20
        val pixels = IntArray(w * h) { i ->
            if ((i % w) < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val mask = EdgeDetector.detect(pixels, w, h, blurRadius = 0, keepFraction = 0.2f)
        assertTrue("a sharp black/white split must yield edges", mask.edgeCount() > 0)

        // Every detected edge should sit near the split, not scattered across the flat regions.
        val split = w / 2
        val strays = mask.edges.indices.filter { mask.edges[it] }.count { kotlin.math.abs((it % w) - split) > 2 }
        assertEquals("no edges should appear in the flat areas", 0, strays)
    }

    @Test
    fun `a flat image yields no edges at all`() {
        val pixels = IntArray(16 * 16) { 0xFF808080.toInt() }
        val mask = EdgeDetector.detect(pixels, 16, 16, blurRadius = 0, keepFraction = 0.2f)
        assertEquals(0, mask.edgeCount())
    }

    @Test
    fun `blur with zero radius is a pass through`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f)
        val out = EdgeDetector.boxBlur(src, 2, 2, radius = 0)
        assertTrue(src.contentEquals(out))
    }

    @Test
    fun `mismatched pixel buffer and dimensions are rejected`() {
        val failed = runCatching { EdgeDetector.detect(IntArray(10), 5, 5) }.isFailure
        assertTrue("a buffer that doesn't match w*h must not be silently accepted", failed)
    }

    // ---------------------------------------------------------------- pipeline

    @Test
    fun `the pipeline recovers a rectangle drawn on a blank field`() {
        val w = 64; val h = 64
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        fun set(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) pixels[y * w + x] = 0xFF000000.toInt() }
        for (x in 10..50) { set(x, 10); set(x, 50) }
        for (y in 10..50) { set(10, y); set(50, y) }

        val result = VectorizationPipeline.run(pixels, w, h, VectorizationOptions(detail = 0.15f, blurRadius = 0))
        assertTrue("the rectangle outline should be traced", result.paths.isNotEmpty())
        assertTrue("traced geometry should be substantial", result.paths.first().lengthPx > 50f)
    }

    @Test
    fun `a blank image produces no paths rather than failing`() {
        val pixels = IntArray(32 * 32) { 0xFFFFFFFF.toInt() }
        val result = VectorizationPipeline.run(pixels, 32, 32)
        assertEquals(0, result.paths.size)
        assertEquals(0, result.totalFound)
    }

    @Test
    fun `confidence rewards length and closure`() {
        val shortOpen = VectorizationPipeline.confidenceOf(listOf(Vec2(0f, 0f), Vec2(10f, 0f)), closed = false)
        val longOpen = VectorizationPipeline.confidenceOf(listOf(Vec2(0f, 0f), Vec2(700f, 0f)), closed = false)
        val longClosed = VectorizationPipeline.confidenceOf(listOf(Vec2(0f, 0f), Vec2(700f, 0f)), closed = true)
        assertTrue(longOpen > shortOpen)
        assertTrue(longClosed > longOpen)
        assertTrue(longClosed <= 1f)
    }

    @Test
    fun `the threshold keeps roughly the requested fraction of edge pixels`() {
        // A ramp gives a known, evenly spread gradient distribution to measure against.
        val magnitude = FloatArray(1000) { (it + 1).toFloat() }
        val threshold = EdgeDetector.adaptiveThreshold(magnitude, keepFraction = 0.1f)
        val kept = magnitude.count { it >= threshold }
        assertTrue("expected roughly 100 kept, got $kept", kept in 80..130)
    }

    @Test
    fun `an all zero gradient field yields an unreachable threshold instead of dividing by zero`() {
        val threshold = EdgeDetector.adaptiveThreshold(FloatArray(500), keepFraction = 0.1f)
        assertEquals(Float.MAX_VALUE, threshold, 0f)
    }

    @Test
    fun `thresholding a large buffer stays allocation-light enough to finish quickly`() {
        // Guards the histogram implementation: the previous boxed filter-and-sort on a buffer this
        // size allocated hundreds of megabytes and is what would OOM on a real camera photo.
        val magnitude = FloatArray(4_000_000) { (it % 255).toFloat() }
        val started = System.currentTimeMillis()
        val threshold = EdgeDetector.adaptiveThreshold(magnitude, keepFraction = 0.08f)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("threshold should be a real value", threshold > 0f && threshold < Float.MAX_VALUE)
        assertTrue("a linear pass over 4M floats should be fast, took ${elapsed}ms", elapsed < 4000)
    }

    @Test
    fun `suppression thins a wide gradient band down to a single pixel ridge`() {
        // A soft ramp produces a gradient several pixels wide - exactly the case that made traced
        // output wander across a line's width instead of along its length.
        val w = 21; val h = 5
        val lum = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            lum[y * w + x] = when {
                x < 8 -> 0f
                x > 12 -> 255f
                else -> (x - 8) * 51f
            }
        }
        val gradient = EdgeDetector.sobel(lum, w, h)
        val thinned = EdgeDetector.nonMaximumSuppression(gradient, w, h)

        val middleRow = 2
        val thickBefore = (1 until w - 1).count { gradient.magnitude[middleRow * w + it] > 0f }
        val thinAfter = (1 until w - 1).count { thinned[middleRow * w + it] > 0f }
        assertTrue("the raw gradient should be a wide band, was $thickBefore", thickBefore >= 3)
        assertTrue("suppression must narrow it, got $thinAfter from $thickBefore", thinAfter < thickBefore)
    }

    @Test
    fun `hysteresis keeps a faint line connected to a strong one but drops isolated speckle`() {
        val w = 10; val h = 3
        val thinned = FloatArray(w * h)
        // A strong segment that fades into a weak tail along the middle row.
        for (x in 1..4) thinned[1 * w + x] = 100f
        for (x in 5..7) thinned[1 * w + x] = 50f
        // Unconnected weak speckle elsewhere.
        thinned[0 * w + 9] = 50f

        val edges = EdgeDetector.hysteresis(thinned, w, h, lowThreshold = 40f, highThreshold = 90f)
        assertTrue("the strong part must survive", edges[1 * w + 2])
        assertTrue("the weak tail joined to it must survive", edges[1 * w + 7])
        assertTrue("isolated weak speckle must be dropped", !edges[0 * w + 9])
    }

    @Test
    fun `tracing a straight line yields a straight path rather than a staircase`() {
        val w = 40; val h = 12
        val edges = BooleanArray(w * h)
        for (x in 5..34) edges[6 * w + x] = true
        val mask = EdgeDetector.EdgeMask(w, h, edges)

        val traced = ContourTracer.trace(mask, minPathPixels = 5)
        assertEquals("a single line should trace as one path", 1, traced.size)
        // Every point must sit on the same row: any deviation means the walk wandered.
        assertTrue("the path must stay on one row", traced[0].all { it.y == 6f })

        val simplified = PathSimplifier.simplify(traced[0], epsilon = 1f)
        assertEquals("a straight line reduces to two endpoints", 2, simplified.size)
    }

    @Test
    fun `tracing starts at a line end so the line comes back in one piece`() {
        val w = 30; val h = 8
        val edges = BooleanArray(w * h)
        for (x in 4..24) edges[4 * w + x] = true
        val mask = EdgeDetector.EdgeMask(w, h, edges)

        val traced = ContourTracer.trace(mask, minPathPixels = 5)
        assertEquals(1, traced.size)
        assertEquals("all 21 pixels belong to the one path", 21, traced[0].size)
        // Starting mid-line would leave the walk stranded and split the line in two.
        val startX = traced[0].first().x
        assertTrue("should begin at an end of the line, began at $startX", startX == 4f || startX == 24f)
    }

    // ---------------------------------------------------------------- centreline mode

    @Test
    fun `thinning reduces a thick stroke to a single pixel wide centreline`() {
        val w = 30; val h = 15
        val ink = BooleanArray(w * h)
        // A horizontal bar 5 pixels thick - what a real pen line looks like once binarised.
        for (y in 5..9) for (x in 4..25) ink[y * w + x] = true

        val thinned = Skeletonizer.thin(ink, w, h)

        for (x in 8..21) {
            val column = (0 until h).count { thinned[it * w + x] }
            assertTrue("column $x should be one pixel thick, was $column", column <= 1)
        }
        assertTrue("the line must survive thinning", thinned.count { it } > 5)
    }

    @Test
    fun `thinning preserves connectivity rather than breaking the line into dots`() {
        val w = 40; val h = 12
        val ink = BooleanArray(w * h)
        for (y in 4..7) for (x in 3..35) ink[y * w + x] = true

        val thinned = Skeletonizer.thin(ink, w, h)
        val mask = EdgeDetector.EdgeMask(w, h, thinned)
        val traced = ContourTracer.trace(mask, minPathPixels = 5)

        assertEquals("a single stroke must trace as exactly one path", 1, traced.size)
    }

    @Test
    fun `centreline mode returns one path for a stroke where edge mode would return two`() {
        val w = 60; val h = 30
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        // A thick horizontal stroke: two edges, but only one line.
        for (y in 12..16) for (x in 8..50) pixels[y * w + x] = 0xFF000000.toInt()

        val centreline = VectorizationPipeline.run(
            pixels, w, h,
            VectorizationOptions(mode = TraceMode.CENTERLINE, blurRadius = 0, minPathPixels = 6, binarizeWindowRadius = 8)
        )
        assertEquals("a single stroke should yield a single centreline path", 1, centreline.paths.size)
    }

    @Test
    fun `binarisation survives a strong lighting gradient across the page`() {
        val w = 80; val h = 20
        val luminance = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // Page brightness ramps from near-black to near-white left to right.
            luminance[y * w + x] = 40f + (x / w.toFloat()) * 200f
        }
        // One dark line on each side - a global threshold can only ever catch one of them.
        for (y in 6..8) {
            for (x in 10..14) luminance[y * w + x] = 10f
            for (x in 64..68) luminance[y * w + x] = 120f
        }

        val ink = AdaptiveBinarizer.binarize(luminance, w, h, windowRadius = 6)
        assertTrue("the line in the dark half must be found", ink[7 * w + 12])
        assertTrue("the line in the bright half must also be found", ink[7 * w + 66])
        assertTrue("blank paper in the bright half must stay blank", !ink[2 * w + 75])
    }

    @Test
    fun `isolated specks are dropped but line pixels are kept`() {
        val w = 12; val h = 6
        val mask = BooleanArray(w * h)
        for (x in 2..8) mask[3 * w + x] = true
        mask[0 * w + 11] = true // lone speck

        val cleaned = Skeletonizer.removeIsolated(mask, w, h)
        assertTrue("line pixels stay", cleaned[3 * w + 5])
        assertTrue("the lone speck goes", !cleaned[0 * w + 11])
    }

    // ---------------------------------------------------------------- geometric judgement

    private fun path(vararg pts: Pair<Float, Float>, closed: Boolean = false) =
        TracedPath(pts.map { Vec2(it.first, it.second) }, closed, 0.8f)

    @Test
    fun `two collinear fragments of one line become a single path`() {
        val a = path(0f to 0f, 100f to 0f)
        val b = path(108f to 0f, 200f to 0f)
        val merged = GeometryRefiner.mergeFragments(listOf(a, b), RefineOptions())
        assertEquals("the gap was small and the headings agree, so this is one line", 1, merged.size)
        assertEquals(200f, merged[0].points.last().x, 1f)
    }

    @Test
    fun `fragments that meet at a sharp angle are not merged`() {
        val a = path(0f to 0f, 100f to 0f)
        val b = path(104f to 0f, 104f to 90f)
        val merged = GeometryRefiner.mergeFragments(listOf(a, b), RefineOptions())
        assertEquals("a corner is not a broken line", 2, merged.size)
    }

    @Test
    fun `fragments too far apart are left alone`() {
        val a = path(0f to 0f, 100f to 0f)
        val b = path(400f to 0f, 500f to 0f)
        val merged = GeometryRefiner.mergeFragments(listOf(a, b), RefineOptions())
        assertEquals(2, merged.size)
    }

    @Test
    fun `a near right angle is squared up`() {
        // 87 degrees: clearly meant to be vertical.
        val drifted = path(0f to 0f, 100f to 0f, 105f to 100f)
        val fixed = GeometryRefiner.regularize(drifted, RefineOptions())
        val dx = fixed.points[2].x - fixed.points[1].x
        assertEquals("the second segment should become exactly vertical", 0f, dx, 0.01f)
    }

    @Test
    fun `a deliberately oblique line is not forced onto an axis`() {
        // 30 degrees is far from any 45-degree axis and must be preserved exactly.
        val oblique = path(0f to 0f, 100f to 57.7f)
        val fixed = GeometryRefiner.regularize(oblique, RefineOptions())
        assertEquals(100f, fixed.points[1].x, 0.5f)
        assertEquals(57.7f, fixed.points[1].y, 0.5f)
    }

    @Test
    fun `regularisation keeps segment lengths`() {
        val original = path(0f to 0f, 100f to 4f)
        val fixed = GeometryRefiner.regularize(original, RefineOptions())
        assertEquals("length must be preserved, not projected away", original.lengthPx, fixed.lengthPx, 0.5f)
    }

    @Test
    fun `a duplicate running alongside a longer line is dropped`() {
        val main = path(0f to 0f, 300f to 0f)
        val shadow = path(10f to 3f, 280f to 3f)
        val kept = GeometryRefiner.dropDuplicates(listOf(main, shadow), RefineOptions())
        assertEquals(1, kept.size)
        assertEquals("the longer of the pair survives", 300f, kept[0].points.last().x, 1f)
    }

    @Test
    fun `a line that merely crosses another is kept`() {
        val horizontal = path(0f to 50f, 300f to 50f)
        val vertical = path(150f to 0f, 150f to 200f)
        val kept = GeometryRefiner.dropDuplicates(listOf(horizontal, vertical), RefineOptions())
        assertEquals("a junction must not delete a branch", 2, kept.size)
    }

    @Test
    fun `undirected heading comparison treats opposite directions as the same line`() {
        assertEquals(0.0, GeometryRefiner.angleBetweenLines(179.0, -179.0), 2.5)
        assertEquals(90.0, GeometryRefiner.angleBetweenLines(0.0, 90.0), 0.001)
    }

    @Test
    fun `strokes that meet but never close are left as separate lines`() {
        // An L: two strokes sharing a corner. They touch, but the chain never returns to its
        // start, so fusing them into one object would be an invention.
        val a = path(0f to 0f, 100f to 0f)
        val b = path(100f to 0f, 100f to 100f)
        val chained = GeometryRefiner.chainIntoShapes(listOf(a, b), RefineOptions())
        assertEquals("an open corner is not a shape", 2, chained.size)
        assertTrue("and nothing should be marked closed", chained.none { it.closed })
    }

    @Test
    fun `an already closed shape passes through chaining untouched`() {
        val ring = path(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f, closed = true)
        val chained = GeometryRefiner.chainIntoShapes(listOf(ring), RefineOptions())
        assertEquals(1, chained.size)
        assertTrue(chained[0].closed)
        assertEquals(4, chained[0].points.size)
    }

    @Test
    fun `refinement turns four drifting fragments into one closed square`() {
        val fragments = listOf(
            path(0f to 0f, 200f to 3f),
            path(204f to 6f, 200f to 200f),
            path(198f to 206f, 2f to 202f),
            path(0f to 198f, 1f to 6f)
        )
        val refined = GeometryRefiner.refine(fragments, RefineOptions())
        assertEquals("a square drawn as four strokes is one shape", 1, refined.size)
        assertTrue("and it should come back closed", refined[0].closed)
    }

    @Test
    fun `an out of range detail setting is rejected up front`() {
        assertTrue(runCatching { VectorizationOptions(detail = 0f) }.isFailure)
        assertTrue(runCatching { VectorizationOptions(detail = 1f) }.isFailure)
    }
}
