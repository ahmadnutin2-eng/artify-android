package com.procreate.android.canvas

import com.procreate.android.project.ProjectPointDto
import com.procreate.android.project.UrbanCoordinateSpace
import com.procreate.android.project.UrbanElementDto
import com.procreate.android.project.UrbanProjectDto
import com.procreate.android.project.UrbanScaleConfigDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UrbanUndoHistoryTest {

    @Test
    fun urbanEditParticipatesInTheSameChronologicalTimeline() {
        val history = UndoHistory()
        val urban = UrbanEdit(stateAt(10f), stateAt(20f))
        val layerStructure = StackEdit(emptyList(), emptyList())

        history.push(urban)
        history.push(layerStructure)

        assertSame(layerStructure, history.popUndo())
        assertSame(urban, history.popUndo())
        assertSame(urban, history.popRedo())
        assertSame(layerStructure, history.popRedo())
    }

    @Test
    fun canvasExpansionRebasesUrbanHistorySnapshots() {
        val history = UndoHistory()
        val edit = UrbanEdit(stateAt(10f), stateAt(20f))
        history.push(edit)

        history.rebaseAfterExpand(dx = 7, dy = 11, bitmapsById = emptyMap())

        val rebased = history.popUndo() as UrbanEdit
        assertEquals(ProjectPointDto(17f, 21f), markerPoint(rebased.before))
        assertEquals(ProjectPointDto(27f, 31f), markerPoint(rebased.after))
        assertEquals(ProjectPointDto(8f, 13f), rebased.before.scale.scaleBarMapPosition)
    }

    @Test
    fun metreCoordinatesAreNotShiftedByPixelCanvasExpansion() {
        val history = UndoHistory()
        val metres = stateAt(10f).copy(coordinateSpace = UrbanCoordinateSpace.METERS)
        history.push(UrbanEdit(metres, metres))

        history.rebaseAfterExpand(dx = 100, dy = 200, bitmapsById = emptyMap())

        val unchanged = history.popUndo() as UrbanEdit
        assertEquals(ProjectPointDto(10f, 10f), markerPoint(unchanged.before))
        assertEquals(ProjectPointDto(1f, 2f), unchanged.before.scale.scaleBarMapPosition)
    }

    private fun stateAt(value: Float) = UrbanProjectDto(
        scale = UrbanScaleConfigDto(scaleBarMapPosition = ProjectPointDto(1f, 2f)),
        elements = listOf(
            UrbanElementDto.PointMarker(
                id = "marker",
                toolType = "STATION_BADGE",
                color = 0xFF123456.toInt(),
                position = ProjectPointDto(value, value)
            )
        )
    )

    private fun markerPoint(state: UrbanProjectDto): ProjectPointDto =
        (state.elements.single() as UrbanElementDto.PointMarker).position
}
