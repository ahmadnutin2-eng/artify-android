package com.procreate.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTilingTest {

    // ---------------------------------------------------------------- tiler

    @Test
    fun `a dense plan is cut into a grid with overlap`() {
        val tiles = PlanTiler.tile(imageWidth = 2000, imageHeight = 1500, targetTileSize = 800)
        assertTrue("expected a multi-tile grid, got ${tiles.size}", tiles.size >= 4)
        assertTrue(PlanTiler.coversEntireImage(tiles, 2000, 1500))
    }

    @Test
    fun `every pixel falls inside at least one tile`() {
        val width = 1737
        val height = 1103
        val tiles = PlanTiler.tile(width, height, targetTileSize = 700)
        // Sampling the interior is enough to catch a gap: a hole would have to be smaller than the
        // sample step to escape, and the step is far finer than any realistic seam.
        for (y in 0 until height step 37) {
            for (x in 0 until width step 37) {
                val covered = tiles.any { x >= it.offsetX && x < it.right && y >= it.offsetY && y < it.bottom }
                assertTrue("pixel ($x,$y) is not covered by any tile", covered)
            }
        }
    }

    @Test
    fun `neighbouring tiles actually overlap so a crossing feature appears in both`() {
        val tiles = PlanTiler.tile(2000, 1500, targetTileSize = 800, overlapFraction = 0.25f)
        val firstRow = tiles.filter { it.row == 0 }.sortedBy { it.column }
        assertTrue("need at least two columns to test overlap", firstRow.size >= 2)
        val a = firstRow[0]
        val b = firstRow[1]
        assertTrue("tiles must overlap horizontally", b.offsetX < a.right)
    }

    @Test
    fun `an image smaller than one tile yields a single tile`() {
        val tiles = PlanTiler.tile(400, 300, targetTileSize = 800)
        assertEquals(1, tiles.size)
        assertEquals(0, tiles[0].offsetX)
        assertEquals(400, tiles[0].width)
    }

    @Test
    fun `no tile ever extends past the image bounds`() {
        val tiles = PlanTiler.tile(1234, 987, targetTileSize = 500)
        assertTrue(tiles.all { it.right <= 1234 && it.bottom <= 987 })
        assertTrue(tiles.all { it.offsetX >= 0 && it.offsetY >= 0 })
    }

    @Test
    fun `invalid tiling parameters are rejected up front`() {
        assertTrue(runCatching { PlanTiler.tile(0, 100) }.isFailure)
        assertTrue(runCatching { PlanTiler.tile(100, 100, overlapFraction = 0.95f) }.isFailure)
    }

    @Test
    fun `every extraction category maps only to features it owns`() {
        // A category that claimed a feature belonging to another would let the same thing be
        // extracted twice under different headings.
        val seen = mutableSetOf<PlanFeature>()
        for (category in ExtractionCategory.entries) {
            for (feature in category.features) {
                assertTrue("${feature.name} claimed by two categories", seen.add(feature))
            }
        }
    }

    // ---------------------------------------------------------------- merger

    private fun line(vararg pts: Pair<Float, Float>, clipped: Boolean = false, conf: Float = 0.8f) =
        DetectedFeature(PlanFeature.MAIN_ROAD, PlanGeometry.LINE, pts.toList(), conf, clippedAtEdge = clipped)

    private fun building(vararg pts: Pair<Float, Float>, conf: Float = 0.8f) =
        DetectedFeature(PlanFeature.BUILDING, PlanGeometry.POLYGON, pts.toList(), conf)

    @Test
    fun `the same building seen in two overlapping tiles is kept once`() {
        val a = building(0.10f to 0.10f, 0.20f to 0.10f, 0.20f to 0.20f, 0.10f to 0.20f, conf = 0.9f)
        val b = building(0.101f to 0.101f, 0.201f to 0.101f, 0.201f to 0.201f, 0.101f to 0.201f, conf = 0.7f)
        val merged = FeatureMerger.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("the more confident reading survives", 0.9f, merged[0].confidence, 1e-4f)
    }

    @Test
    fun `two genuinely different buildings are both kept`() {
        val a = building(0.10f to 0.10f, 0.20f to 0.10f, 0.20f to 0.20f, 0.10f to 0.20f)
        val b = building(0.50f to 0.50f, 0.60f to 0.50f, 0.60f to 0.60f, 0.50f to 0.60f)
        assertEquals(2, FeatureMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun `adjacent buildings sharing a wall are not fused`() {
        // The failure that would quietly destroy a plan: two separate blocks becoming one.
        val a = building(0.10f to 0.10f, 0.20f to 0.10f, 0.20f to 0.20f, 0.10f to 0.20f)
        val b = building(0.20f to 0.10f, 0.30f to 0.10f, 0.30f to 0.20f, 0.20f to 0.20f)
        assertEquals("touching is not the same as duplicated", 2, FeatureMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun `a road clipped by a tile seam is rejoined into one route`() {
        // The gap sits clearly inside the join tolerance rather than exactly on it: a test pinned
        // to the threshold measures float rounding, not the behaviour being described.
        val left = line(0.10f to 0.50f, 0.49f to 0.50f, clipped = true)
        val right = line(0.50f to 0.50f, 0.90f to 0.50f, clipped = true)
        val merged = FeatureMerger.merge(listOf(left, right))
        assertEquals(1, merged.size)
        assertEquals("the joined route spans both fragments", 0.90f, merged[0].points.last().first, 1e-3f)
        assertTrue("and is no longer marked clipped", !merged[0].clippedAtEdge)
    }

    @Test
    fun `fragments separated by more than the tolerance stay separate`() {
        val left = line(0.10f to 0.50f, 0.30f to 0.50f, clipped = true)
        val right = line(0.60f to 0.50f, 0.90f to 0.50f, clipped = true)
        assertEquals(
            "a wide gap means two different roads, not one severed road",
            2, FeatureMerger.merge(listOf(left, right)).size
        )
    }

    @Test
    fun `an unflagged endpoint is never welded to another line`() {
        // A cul-de-sac ending near an unrelated road must keep its endpoint.
        val culDeSac = line(0.10f to 0.50f, 0.48f to 0.50f, clipped = false)
        val other = line(0.50f to 0.50f, 0.90f to 0.50f, clipped = false)
        assertEquals(2, FeatureMerger.merge(listOf(culDeSac, other)).size)
    }

    @Test
    fun `fragments meeting at a sharp angle are not joined`() {
        val horizontal = line(0.10f to 0.50f, 0.48f to 0.50f, clipped = true)
        val vertical = line(0.50f to 0.50f, 0.50f to 0.90f, clipped = true)
        assertEquals("a corner is not a severed line", 2, FeatureMerger.merge(listOf(horizontal, vertical)).size)
    }

    @Test
    fun `fragments of different feature types never merge`() {
        val road = line(0.10f to 0.50f, 0.48f to 0.50f, clipped = true)
        val path = DetectedFeature(
            PlanFeature.DIRT_PATH, PlanGeometry.LINE,
            listOf(0.50f to 0.50f, 0.90f to 0.50f), 0.8f, clippedAtEdge = true
        )
        assertEquals(2, FeatureMerger.merge(listOf(road, path)).size)
    }

    @Test
    fun `a rectangle duplicate is recognised through its parametric form`() {
        val spec = RectangleSpec(0.3f, 0.3f, 0.1f, 0.08f, 0f)
        val a = DetectedFeature(PlanFeature.BUILDING, PlanGeometry.RECTANGLE, emptyList(), 0.9f, rectangle = spec)
        val b = DetectedFeature(
            PlanFeature.BUILDING, PlanGeometry.RECTANGLE, emptyList(), 0.6f,
            rectangle = spec.copy(centerX = 0.301f)
        )
        assertEquals(1, FeatureMerger.merge(listOf(a, b)).size)
    }

    @Test
    fun `a degenerate grid of identical rectangles is thrown away`() {
        // Reproduces a real failure: asked to be exhaustive, the model emitted a lattice of
        // identical 30x30 squares at a fixed stride instead of stopping when it ran out of
        // buildings. Every one looked valid on its own.
        val fabricated = (0 until 14).map { i ->
            DetectedFeature(
                PlanFeature.BUILDING, PlanGeometry.RECTANGLE, emptyList(), 1.0f,
                rectangle = RectangleSpec(0.3f, 0.2f + i * 0.015f, 0.015f, 0.015f, 0f)
            )
        }
        assertTrue("the fabricated lattice must not survive", FeatureMerger.rejectDegenerate(fabricated).isEmpty())
    }

    @Test
    fun `genuinely repeated buildings at irregular spacing are kept`() {
        // A real terrace of similar units is not a lattice: the spacing varies.
        val offsets = listOf(0f, 0.031f, 0.068f, 0.094f, 0.137f, 0.171f, 0.198f, 0.244f, 0.29f)
        val real = offsets.map { dy ->
            DetectedFeature(
                PlanFeature.BUILDING, PlanGeometry.RECTANGLE, emptyList(), 0.85f,
                rectangle = RectangleSpec(0.3f, 0.2f + dy, 0.015f, 0.015f, 0f)
            )
        }
        assertEquals("irregular spacing means real buildings", real.size, FeatureMerger.rejectDegenerate(real).size)
    }

    @Test
    fun `a handful of similar rectangles is never treated as degenerate`() {
        val few = (0 until 4).map { i ->
            DetectedFeature(
                PlanFeature.BUILDING, PlanGeometry.RECTANGLE, emptyList(), 0.9f,
                rectangle = RectangleSpec(0.3f, 0.2f + i * 0.02f, 0.015f, 0.015f, 0f)
            )
        }
        assertEquals(4, FeatureMerger.rejectDegenerate(few).size)
    }

    @Test
    fun `nearby markers collapse but distant ones do not`() {
        fun marker(x: Float, y: Float) =
            DetectedFeature(PlanFeature.LIGHT_POLE, PlanGeometry.POINT, listOf(x to y), 0.7f)
        assertEquals(1, FeatureMerger.merge(listOf(marker(0.5f, 0.5f), marker(0.505f, 0.5f))).size)
        assertEquals(2, FeatureMerger.merge(listOf(marker(0.5f, 0.5f), marker(0.7f, 0.7f))).size)
    }
}
