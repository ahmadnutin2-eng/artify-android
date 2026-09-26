package com.procreate.android.project

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses Android's real org.json implementation; local JVM tests only receive android.jar stubs. */
@RunWith(AndroidJUnit4::class)
class ProjectDocumentCodecInstrumentedTest {
    @Test
    fun roundTripPreservesEveryCurrentProjectSection() {
        val document = ProjectDocumentDto(
            projectType = ProjectType.URBAN_DESIGN,
            canvas = ProjectCanvasDto(2048, 1536, "DOT_GRID", isOpenCanvas = true),
            rasterLayers = listOf(
                RasterLayerDto(
                    id = "paper",
                    name = "Paper",
                    bitmapAssetPath = "layers/paper.png",
                    pixelWidth = 2048,
                    pixelHeight = 1536,
                    maskAssetPath = "masks/paper.png",
                    opacity = 0.75f,
                    blendMode = "Multiply",
                    isVisible = false,
                    isLocked = true,
                    isAlphaLocked = true,
                    isClippingMask = true,
                    isBackground = true
                )
            ),
            activeRasterLayerId = "paper",
            urban = UrbanProjectDto(
                scale = UrbanScaleConfigDto(
                    pixelsPerMeter = 2.5f,
                    gridCellSizeMeters = 25f,
                    isGridVisible = false,
                    isScaleCardVisible = false,
                    gridAlpha = 123,
                    gridColor = 0xFF123456.toInt(),
                    isCalibrated = true,
                    standardRatio = 500,
                    nodeDistanceMeters = 12.5f,
                    isScaleBarOnMap = false,
                    scaleBarMapPosition = ProjectPointDto(5.5f, 8.25f),
                    activeToolStrokeWidth = 21f,
                    activeToolColor = 0xFFABCDEF.toInt()
                ),
                settings = UrbanSettingsDto(
                    inputMode = "CONTINUOUS_DRAG",
                    activeTool = "PRIMARY_AXIS",
                    showLiveLegend = false,
                    showLiveTables = false,
                    legendPlacement = "TOP_LEFT",
                    tablePlacement = "TOP_RIGHT",
                    scaleCardPlacement = "BOTTOM_RIGHT"
                ),
                elements = listOf(
                    UrbanElementDto.ArrowPath(
                        id = "arrow",
                        toolType = "PRIMARY_AXIS",
                        color = 1,
                        points = listOf(ProjectPointDto(1f, 2f), ProjectPointDto(3f, 4f)),
                        strokeWidth = 18f,
                        isDotted = true,
                        isDashed = true,
                        hasTrailingDots = false,
                        arrowHeadType = "DOUBLE_HEAD",
                        startLabel = "A",
                        endLabel = "B",
                        stations = listOf(StationMarkerDto(ProjectPointDto(2f, 3f), "S1")),
                        lengthMeters = 88f
                    ),
                    UrbanElementDto.HatchPolygon(
                        id = "hatch",
                        toolType = "PLAZA_HATCH",
                        color = 2,
                        vertices = triangle(),
                        hatchStyle = "CROSS_HATCH",
                        hatchSpacing = 33f,
                        plazaNumber = 7,
                        plazaName = "P7",
                        areaSqMeters = 100f
                    ),
                    UrbanElementDto.PointMarker(
                        id = "marker",
                        toolType = "INFRA_LIGHT",
                        color = 3,
                        position = ProjectPointDto(11f, 12f),
                        label = "L1",
                        radius = 24f
                    ),
                    UrbanElementDto.BoundaryPath(
                        id = "boundary",
                        toolType = "SITE_BOUNDARY",
                        color = 4,
                        vertices = triangle(),
                        strokeWidth = 10f,
                        showNodeNumbers = false,
                        lengthMeters = 42f,
                        nodeSpacingPx = 15f
                    )
                )
            ),
            createdAtEpochMillis = 100L,
            modifiedAtEpochMillis = 200L
        )

        assertEquals(document, ProjectDocumentCodec.decode(ProjectDocumentCodec.encode(document)))
    }

    @Test
    fun projectStoreRoundTripsLayerMaskPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mask-store-${System.nanoTime()}")
        try {
            val store = ProjectDocumentStore.inDirectory(root)
            val layer = RasterLayerDto(
                id = "paint",
                name = "Paint",
                bitmapAssetPath = ProjectDocumentStore.defaultBitmapAssetPath("paint"),
                maskAssetPath = ProjectDocumentStore.defaultMaskAssetPath("paint"),
                pixelWidth = 16,
                pixelHeight = 16
            )
            val document = ProjectDocumentDto(
                projectType = ProjectType.DRAWING,
                canvas = ProjectCanvasDto(16, 16),
                rasterLayers = listOf(layer)
            )
            val content = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
            val mask = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                setPixel(3, 4, Color.BLACK)
            }
            val path = store.save(
                "mask-project",
                document,
                bitmapsByLayerId = mapOf("paint" to content),
                maskBitmapsByLayerId = mapOf("paint" to mask)
            )

            val loadedDocument = store.load(path)
            val loadedMask = store.loadLayerMask(path, loadedDocument.rasterLayers.single())!!
            assertEquals(Color.BLACK, loadedMask.getPixel(3, 4))
            assertEquals(Color.WHITE, loadedMask.getPixel(0, 0))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun triangle() = listOf(
        ProjectPointDto(0f, 0f),
        ProjectPointDto(10f, 0f),
        ProjectPointDto(10f, 10f)
    )
}
