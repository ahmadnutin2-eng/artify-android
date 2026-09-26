package com.procreate.android.project

import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectDocumentValidatorTest {
    @Test
    fun `accepts an empty but complete Urban project`() {
        val document = ProjectDocumentDto(
            projectType = ProjectType.URBAN_DESIGN,
            canvas = ProjectCanvasDto(2048, 1536),
            urban = UrbanProjectDto(),
            createdAtEpochMillis = 1L,
            modifiedAtEpochMillis = 2L
        )

        ProjectDocumentValidator.validate(document)
    }

    @Test
    fun `rejects an asset path that escapes the project directory`() {
        val document = ProjectDocumentDto(
            projectType = ProjectType.DRAWING,
            canvas = ProjectCanvasDto(100, 100),
            rasterLayers = listOf(
                RasterLayerDto("layer", "Layer", "../outside.png", 100, 100)
            ),
            createdAtEpochMillis = 1L,
            modifiedAtEpochMillis = 1L
        )

        assertThrows(ProjectDocumentFormatException::class.java) {
            ProjectDocumentValidator.validate(document)
        }
    }

    @Test
    fun `rejects an Urban project without an Urban snapshot`() {
        val document = ProjectDocumentDto(
            projectType = ProjectType.URBAN_DESIGN,
            canvas = ProjectCanvasDto(100, 100),
            createdAtEpochMillis = 1L,
            modifiedAtEpochMillis = 1L
        )

        assertThrows(ProjectDocumentFormatException::class.java) {
            ProjectDocumentValidator.validate(document)
        }
    }
}

