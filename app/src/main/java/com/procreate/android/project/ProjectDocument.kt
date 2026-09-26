package com.procreate.android.project

/** Current on-disk schema. It is intentionally independent from the Room database version. */
const val CURRENT_PROJECT_SCHEMA_VERSION = 4

/** Stable identifier used to reject unrelated JSON files before trying to decode their fields. */
const val PROJECT_DOCUMENT_FORMAT = "artify.project"

/**
 * Complete editable project metadata. Raster pixels live in the layer assets referenced by
 * [RasterLayerDto.bitmapAssetPath]; everything needed to rebuild the editable structure lives in
 * this document rather than in the flattened gallery preview.
 */
data class ProjectDocumentDto(
    val schemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    val projectType: ProjectType,
    val canvas: ProjectCanvasDto,
    val rasterLayers: List<RasterLayerDto> = emptyList(),
    val activeRasterLayerId: String? = null,
    val urban: UrbanProjectDto? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val modifiedAtEpochMillis: Long = createdAtEpochMillis
)

data class ProjectCanvasDto(
    val width: Int,
    val height: Int,
    /** Enum name from CanvasBackgroundStyle. Kept as a string for schema compatibility. */
    val backgroundStyle: String = "BLANK",
    /** ARGB colour of the editable Base layer. */
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val isOpenCanvas: Boolean = false
)

/** Metadata for one editable raster layer. The list order is the canvas stacking order. */
data class RasterLayerDto(
    val id: String,
    val name: String,
    /** Forward-slash-separated path relative to the directory containing project.json. */
    val bitmapAssetPath: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** Optional non-destructive grayscale layer-mask asset. */
    val maskAssetPath: String? = null,
    val opacity: Float = 1f,
    /** Enum name from com.procreate.android.canvas.BlendMode. */
    val blendMode: String = "Normal",
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val isAlphaLocked: Boolean = false,
    val isClippingMask: Boolean = false,
    val isBackground: Boolean = false,
    /** Present for layers created in a two-person online room. */
    val collaborationOwnerId: String? = null
)

/** Makes it explicit whether element coordinates are legacy canvas pixels or model-space metres. */
enum class UrbanCoordinateSpace {
    CANVAS_PIXELS,
    METERS
}

data class UrbanProjectDto(
    val coordinateSpace: UrbanCoordinateSpace = UrbanCoordinateSpace.CANVAS_PIXELS,
    val scale: UrbanScaleConfigDto = UrbanScaleConfigDto(),
    val settings: UrbanSettingsDto = UrbanSettingsDto(),
    val elements: List<UrbanElementDto> = emptyList(),
    /** Dimension annotations use canvas pixels and are recalculated from [scale] on demand. */
    val measurements: List<UrbanMeasurementDto> = emptyList(),
    /** Architectural resources keep real-world metre coordinates and physical dimensions. */
    val architecturalAssets: List<ArchitecturalAssetInstanceDto> = emptyList()
)

data class UrbanMeasurementDto(
    val id: String,
    /** Enum name from MeasurementKind. */
    val kind: String,
    val points: List<ProjectPointDto>,
    /** Enum name from DistanceUnit. */
    val displayUnit: String = "METER",
    val label: String? = null
)

data class ArchitecturalAssetInstanceDto(
    val id: String,
    val assetId: String,
    val xMeters: Double,
    val yMeters: Double,
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val colorOverrideArgb: Int? = null,
    val opacity: Float = 1f,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val zIndex: Int = 0
)

/** All non-element Urban workspace state that changes the user's working view or overlays. */
data class UrbanSettingsDto(
    val isUrbanMode: Boolean = true,
    /** Enum name from UrbanInputMode. */
    val inputMode: String = "POINT_BY_POINT",
    /** Nullable enum name from UrbanToolType. */
    val activeTool: String? = null,
    val showLiveLegend: Boolean = true,
    val showLiveTables: Boolean = true,
    /** Enum names from OverlayPlacement. */
    val legendPlacement: String = "BOTTOM_RIGHT",
    val tablePlacement: String = "BOTTOM_LEFT",
    val scaleCardPlacement: String = "BOTTOM_LEFT"
)

/** Serializable counterpart of UrbanScaleConfig, including its optional on-map anchor. */
data class UrbanScaleConfigDto(
    val pixelsPerMeter: Float = 5f,
    val gridCellSizeMeters: Float = 50f,
    val isGridVisible: Boolean = true,
    val isScaleCardVisible: Boolean = true,
    val gridAlpha: Int = 70,
    val gridColor: Int = 0xFF555555.toInt(),
    val isCalibrated: Boolean = false,
    val standardRatio: Int = 1000,
    val nodeDistanceMeters: Float = 20f,
    val isScaleBarOnMap: Boolean = true,
    val scaleBarMapPosition: ProjectPointDto? = null,
    val activeToolStrokeWidth: Float = 14f,
    val activeToolColor: Int = 0
)

data class ProjectPointDto(val x: Float, val y: Float)

data class StationMarkerDto(val point: ProjectPointDto, val label: String)

/** Lossless serializable representation of every currently supported UrbanElement subtype. */
sealed class UrbanElementDto {
    abstract val id: String
    /** Enum name from UrbanToolType. */
    abstract val toolType: String
    abstract val color: Int

    data class ArrowPath(
        override val id: String,
        override val toolType: String,
        override val color: Int,
        val points: List<ProjectPointDto> = emptyList(),
        val strokeWidth: Float = 14f,
        val isDotted: Boolean = false,
        val isDashed: Boolean = false,
        val hasTrailingDots: Boolean = true,
        /** Enum name from ArrowHeadType. */
        val arrowHeadType: String = "LARGE_TRIANGLE",
        val startLabel: String? = null,
        val endLabel: String? = null,
        val stations: List<StationMarkerDto> = emptyList(),
        val lengthMeters: Float = 0f
    ) : UrbanElementDto()

    data class HatchPolygon(
        override val id: String,
        override val toolType: String,
        override val color: Int,
        val vertices: List<ProjectPointDto> = emptyList(),
        /** Enum name from HatchStyle. */
        val hatchStyle: String = "DIAGONAL_45",
        val hatchSpacing: Float = 24f,
        val plazaNumber: Int? = null,
        val plazaName: String? = null,
        val areaSqMeters: Float = 0f
    ) : UrbanElementDto()

    data class PointMarker(
        override val id: String,
        override val toolType: String,
        override val color: Int,
        val position: ProjectPointDto,
        val label: String? = null,
        val radius: Float = 16f
    ) : UrbanElementDto()

    data class BoundaryPath(
        override val id: String,
        override val toolType: String,
        override val color: Int,
        val vertices: List<ProjectPointDto> = emptyList(),
        val strokeWidth: Float = 8f,
        val showNodeNumbers: Boolean = true,
        val lengthMeters: Float = 0f,
        val nodeSpacingPx: Float = 0f
    ) : UrbanElementDto()
}
