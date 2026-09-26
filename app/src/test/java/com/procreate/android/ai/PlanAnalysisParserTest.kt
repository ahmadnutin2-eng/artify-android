package com.procreate.android.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are all real vision-model behaviours, not hypotheticals: fenced output, prose before
 * the JSON, percent coordinates, invented labels, and coordinates outside the image. Each one would
 * otherwise either crash the feature or, far worse, draw a fabricated element onto a site plan.
 */
class PlanAnalysisParserTest {

    @Test
    fun `a clean response parses into features`() {
        val json = """
            {"elements":[
              {"type":"MAIN_ROAD","geometry":"LINE","points":[[0.1,0.5],[0.9,0.52]],"confidence":0.9},
              {"type":"BUILDING","geometry":"POLYGON","points":[[0.2,0.2],[0.3,0.2],[0.3,0.3]],"confidence":0.7}
            ]}
        """.trimIndent()
        val result = PlanAnalysisParser.parse(json)
        assertEquals(2, result.features.size)
        assertEquals(PlanFeature.MAIN_ROAD, result.features[0].feature)
        assertEquals(PlanGeometry.POLYGON, result.features[1].geometry)
    }

    @Test
    fun `json wrapped in a markdown fence with prose is still read`() {
        val reply = """
            Here is my analysis of the plan:
            ```json
            {"elements":[{"type":"SITE_BOUNDARY","geometry":"POLYGON","points":[[0,0],[1,0],[1,1]],"confidence":0.8}]}
            ```
            Let me know if you need more detail.
        """.trimIndent()
        val result = PlanAnalysisParser.parse(reply)
        assertEquals(1, result.features.size)
        assertEquals(PlanFeature.SITE_BOUNDARY, result.features[0].feature)
    }

    @Test
    fun `an unknown type is rejected with a reason rather than guessed`() {
        val json = """{"elements":[{"type":"SWIMMING_POOL","geometry":"POLYGON","points":[[0,0],[1,0],[1,1]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertTrue("an unrecognised label must not become an element", result.features.isEmpty())
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("SWIMMING_POOL"))
    }

    @Test
    fun `coordinates far outside the image are rejected as hallucinated`() {
        val json = """{"elements":[{"type":"MAIN_ROAD","geometry":"LINE","points":[[0.1,0.1],[4.5,0.2]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertTrue(result.features.isEmpty())
        assertTrue(result.warnings[0].contains("خارج حدود"))
    }

    @Test
    fun `a slight overshoot is clamped rather than thrown away`() {
        val json = """{"elements":[{"type":"MAIN_ROAD","geometry":"LINE","points":[[-0.02,0.5],[1.03,0.5]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertEquals(1, result.features.size)
        assertEquals(0f, result.features[0].points.first().first, 1e-4f)
        assertEquals(1f, result.features[0].points.last().first, 1e-4f)
    }

    @Test
    fun `percent style coordinates are converted to the normalised range`() {
        val json = """{"elements":[{"type":"MAIN_ROAD","geometry":"LINE","points":[[10,50],[90,52]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertEquals(1, result.features.size)
        assertEquals(0.1f, result.features[0].points[0].first, 1e-3f)
        assertEquals(0.9f, result.features[0].points[1].first, 1e-3f)
    }

    @Test
    fun `scale is judged across the whole shape not per point`() {
        // One stray large value among normalised ones is a hallucination, not a percent answer.
        assertEquals(1.0, PlanAnalysisParser.detectScale(listOf(0.1 to 0.1, 4.5 to 0.2)), 1e-9)
        // A genuine percent answer has most of its values above 1.
        assertEquals(0.01, PlanAnalysisParser.detectScale(listOf(10.0 to 50.0, 90.0 to 52.0)), 1e-9)
        // Plain normalised input is left alone.
        assertEquals(1.0, PlanAnalysisParser.detectScale(listOf(0.2 to 0.3, 0.8 to 0.9)), 1e-9)
    }

    @Test
    fun `object style points are accepted alongside array style`() {
        val json = """{"elements":[{"type":"DIRT_PATH","geometry":"LINE","points":[{"x":0.2,"y":0.3},{"x":0.6,"y":0.7}]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertEquals(1, result.features.size)
        assertEquals(0.6f, result.features[0].points[1].first, 1e-4f)
    }

    @Test
    fun `a polygon with too few points is rejected`() {
        val json = """{"elements":[{"type":"BUILDING","geometry":"POLYGON","points":[[0.1,0.1],[0.2,0.2]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertTrue(result.features.isEmpty())
        assertTrue(result.warnings[0].contains("نقاط غير كافية"))
    }

    @Test
    fun `a missing geometry falls back to the sensible default for that feature`() {
        val json = """{"elements":[{"type":"BUILDING","points":[[0.1,0.1],[0.3,0.1],[0.3,0.4]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertEquals(1, result.features.size)
        assertEquals("a building is an area, not a line", PlanGeometry.POLYGON, result.features[0].geometry)
    }

    @Test
    fun `an empty result is reported honestly instead of as a failure`() {
        val result = PlanAnalysisParser.parse("""{"elements":[]}""")
        assertTrue(result.features.isEmpty())
        assertTrue("an empty plan is a valid answer", result.warnings.isEmpty())
    }

    @Test
    fun `a reply with no json at all yields a warning not a crash`() {
        val result = PlanAnalysisParser.parse("I'm sorry, I can't help with that image.")
        assertTrue(result.features.isEmpty())
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `good and bad elements in one reply are separated`() {
        val json = """
            {"elements":[
              {"type":"MAIN_ROAD","geometry":"LINE","points":[[0.1,0.5],[0.9,0.5]],"confidence":0.9},
              {"type":"TELEPORTER","geometry":"POINT","points":[[0.5,0.5]]},
              {"type":"LIGHT_POLE","geometry":"POINT","points":[[0.3,0.3]],"confidence":0.6}
            ]}
        """.trimIndent()
        val result = PlanAnalysisParser.parse(json)
        assertEquals("the two valid elements survive", 2, result.features.size)
        assertEquals("and the invalid one is reported", 1, result.warnings.size)
    }

    @Test
    fun `a reply cut off mid element keeps the elements that completed`() {
        // Exactly what a real dense site plan produced against the live API: the reply ran out of
        // output tokens partway through writing a polygon.
        val truncated = """
            {"elements":[
              {"type":"MAIN_ROAD","geometry":"LINE","points":[[0.1,0.5],[0.9,0.5]],"confidence":0.9},
              {"type":"BUILDING","geometry":"POLYGON","points":[[0.2,0.2],[0.4,0.2],[0.4,0.4]],"confidence":0.8},
              {"type":"SITE_BOUNDARY","geometry":"POLYGON","points":[[0.099,0.998],[0.099,0.815],[0.625,
        """.trimIndent()
        val result = PlanAnalysisParser.parse(truncated)
        assertEquals("the two finished elements must survive", 2, result.features.size)
        assertTrue(
            "and the user must be told the reply was cut short",
            result.warnings.any { it.contains("انقطع") }
        )
    }

    @Test
    fun `a complete reply is never mistaken for a truncated one`() {
        val complete = """{"elements":[{"type":"PLAZA","geometry":"POLYGON","points":[[0.1,0.1],[0.5,0.1],[0.5,0.5]]}]}"""
        val result = PlanAnalysisParser.parse(complete)
        assertEquals(1, result.features.size)
        assertTrue("no truncation warning on a whole reply", result.warnings.isEmpty())
    }

    // ---------------------------------------------------------------- pixel-space (tiled) answers

    @Test
    fun `pixel coordinates are divided by the real image size`() {
        // The tiled path asks for full-plan pixels. Leaving them unscaled made every point fail the
        // 0..1 bounds check, so the whole import silently produced nothing.
        val json = """{"elements":[{"type":"MAIN_ROAD","geometry":"POLYLINE","points":[[500,300],[1000,600]]}]}"""
        val result = PlanAnalysisParser.parse(json, pixelWidth = 2000, pixelHeight = 1200)
        assertEquals("a pixel-space path must survive", 1, result.features.size)
        assertEquals(0.25f, result.features[0].points[0].first, 1e-3f)
        assertEquals(0.25f, result.features[0].points[0].second, 1e-3f)
        assertEquals(0.5f, result.features[0].points[1].first, 1e-3f)
        assertEquals(0.5f, result.features[0].points[1].second, 1e-3f)
    }

    @Test
    fun `a pixel-space rectangle is placed by image size not by the grid`() {
        val json = """{"elements":[{"type":"BUILDING","geometry":"RECTANGLE","geometry_center_x":1000,
            "geometry_center_y":600,"geometry_width":200,"geometry_height":120}]}"""
        val result = PlanAnalysisParser.parse(json, pixelWidth = 2000, pixelHeight = 1200)
        assertEquals(1, result.features.size)
        val rect = result.features[0].rectangle!!
        assertEquals("centre should be mid-image", 0.5f, rect.centerX, 1e-3f)
        assertEquals(0.5f, rect.centerY, 1e-3f)
        assertEquals(0.1f, rect.width, 1e-3f)
        assertEquals(0.1f, rect.height, 1e-3f)
    }

    @Test
    fun `a pixel-space circle stays circular on a non-square image`() {
        val json = """{"elements":[{"type":"WATER","geometry":"CIRCLE","geometry_center_x":1000,
            "geometry_center_y":600,"geometry_radius":120}]}"""
        val result = PlanAnalysisParser.parse(json, pixelWidth = 2000, pixelHeight = 1200)
        val circle = result.features[0].circle!!
        assertEquals(0.5f, circle.centerX, 1e-3f)
        assertEquals(0.5f, circle.centerY, 1e-3f)
        assertEquals("radius uses the shorter axis", 0.1f, circle.radius, 1e-3f)
    }

    @Test
    fun `grid coordinates still work when no image size is given`() {
        val json = """{"elements":[{"type":"MAIN_ROAD","geometry":"POLYLINE","points":[[1000,1000],[2000,2000]]}]}"""
        val result = PlanAnalysisParser.parse(json)
        assertEquals(1, result.features.size)
        assertEquals("0-2000 grid maps to 0..1", 0.5f, result.features[0].points[0].first, 1e-3f)
    }

    @Test
    fun `a pixel point beyond the image is still rejected`() {
        val json = """{"elements":[{"type":"MAIN_ROAD","geometry":"POLYLINE","points":[[500,300],[9000,600]]}]}"""
        val result = PlanAnalysisParser.parse(json, pixelWidth = 2000, pixelHeight = 1200)
        assertTrue("scaling must not disable the sanity check", result.features.isEmpty())
    }

    @Test
    fun `a brace inside prose does not truncate the real payload`() {
        val reply = """Some models write { like this } before the answer.
            {"elements":[{"type":"PLAZA","geometry":"POLYGON","points":[[0.1,0.1],[0.5,0.1],[0.5,0.5]],"confidence":0.7}]}"""
        val result = PlanAnalysisParser.parse(reply)
        assertEquals(1, result.features.size)
        assertEquals(PlanFeature.PLAZA, result.features[0].feature)
    }
}
