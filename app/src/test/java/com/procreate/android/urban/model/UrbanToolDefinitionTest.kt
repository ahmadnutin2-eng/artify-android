package com.procreate.android.urban.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrbanToolDefinitionTest {

    @Test
    fun `sightline station is a labelled point and counts instances`() {
        val definition = UrbanToolType.STATION_BADGE.definition

        assertEquals(UrbanGeometryKind.POINT, definition.geometryKind)
        assertEquals(1, definition.minimumPoints)
        assertEquals(UrbanMetric.COUNT, definition.metric)
        assertEquals(UrbanPreviewSymbol.STATION_BADGE, definition.preview.symbol)
        assertTrue(definition.preview.showLabels)
    }

    @Test
    fun `overhead wires are a continuous measurable path`() {
        val definition = UrbanToolType.POLLUTION_WIRES.definition

        assertEquals(UrbanGeometryKind.PATH, definition.geometryKind)
        assertEquals(2, definition.minimumPoints)
        assertEquals(UrbanMetric.LENGTH, definition.metric)
        assertEquals(UrbanStrokePattern.SOLID, definition.preview.strokePattern)
        assertEquals(UrbanPreviewSymbol.UTILITY_NODE, definition.preview.symbol)
    }

    @Test
    fun `ruined building is an area polygon with a distinct preview`() {
        val definition = UrbanToolType.POLLUTION_RUIN.definition

        assertEquals(UrbanGeometryKind.POLYGON, definition.geometryKind)
        assertEquals(3, definition.minimumPoints)
        assertEquals(UrbanMetric.AREA, definition.metric)
        assertEquals(UrbanFillPattern.RUIN_HATCH, definition.preview.fillPattern)
    }

    @Test
    fun `contour is a solid continuous path without boundary nodes`() {
        val definition = UrbanToolType.CONTOUR_LINE.definition

        assertEquals(UrbanGeometryKind.PATH, definition.geometryKind)
        assertEquals(2, definition.minimumPoints)
        assertEquals(UrbanMetric.LENGTH, definition.metric)
        assertEquals(UrbanStrokePattern.SOLID, definition.preview.strokePattern)
        assertFalse(definition.preview.showVertexNodes)
    }

    @Test
    fun `every declared tool has a valid explicit definition`() {
        UrbanToolType.entries.forEach { tool ->
            val definition = tool.definition
            assertNotNull("Missing definition for $tool", definition)
            assertFalse(definition.hasEnoughPoints(definition.minimumPoints - 1))
            assertTrue(definition.hasEnoughPoints(definition.minimumPoints))
            assertEquals(
                tool == UrbanToolType.CALIBRATE_SCALE,
                !definition.createsElement
            )
        }
    }
}
