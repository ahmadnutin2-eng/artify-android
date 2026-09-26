package com.procreate.android.project

import android.graphics.Bitmap
import android.graphics.PointF
import com.procreate.android.canvas.BlendMode
import com.procreate.android.canvas.Layer
import com.procreate.android.urban.model.ArrowHeadType
import com.procreate.android.urban.model.HatchStyle
import com.procreate.android.urban.model.OverlayPlacement
import com.procreate.android.urban.model.StationMarker
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanInputMode
import com.procreate.android.urban.model.UrbanScaleConfig
import com.procreate.android.urban.model.UrbanToolType
import com.procreate.android.urban.assets.AssetInstance
import com.procreate.android.urban.measurement.MeasurementElement
import com.procreate.android.urban.measurement.MeasurementKind
import com.procreate.android.urban.measurement.MeasurementPoint

class ProjectDocumentMappingException(message: String) : IllegalArgumentException(message)

/**
 * The integration boundary between mutable canvas models and immutable persistence DTOs.
 * Keeping these copies here means neither DrawingView nor CanvasViewModel needs serialization
 * knowledge, and loading never shares mutable PointF/list instances with the decoded document.
 */
object ProjectDocumentMapper {
    fun fromMeasurement(element: MeasurementElement): UrbanMeasurementDto = UrbanMeasurementDto(
        id = element.id,
        kind = element.kind.name,
        points = element.points.map { ProjectPointDto(it.x.toFloat(), it.y.toFloat()) },
        displayUnit = element.displayUnit.name,
        label = element.label
    )

    fun toMeasurement(dto: UrbanMeasurementDto): MeasurementElement = MeasurementElement(
        id = dto.id,
        kind = enumValue(dto.kind, "measurement kind"),
        points = dto.points.map { MeasurementPoint(it.x.toDouble(), it.y.toDouble()) },
        displayUnit = enumValue(dto.displayUnit, "measurement display unit"),
        label = dto.label
    )

    fun fromArchitecturalAsset(instance: AssetInstance): ArchitecturalAssetInstanceDto =
        ArchitecturalAssetInstanceDto(
            id = instance.id,
            assetId = instance.assetId,
            xMeters = instance.xMeters,
            yMeters = instance.yMeters,
            scale = instance.scale,
            rotationDegrees = instance.rotationDegrees,
            colorOverrideArgb = instance.colorOverrideArgb,
            opacity = instance.opacity,
            isVisible = instance.isVisible,
            isLocked = instance.isLocked,
            zIndex = instance.zIndex
        )

    fun toArchitecturalAsset(dto: ArchitecturalAssetInstanceDto): AssetInstance = AssetInstance(
        id = dto.id,
        assetId = dto.assetId,
        xMeters = dto.xMeters,
        yMeters = dto.yMeters,
        scale = dto.scale,
        rotationDegrees = dto.rotationDegrees,
        colorOverrideArgb = dto.colorOverrideArgb,
        opacity = dto.opacity,
        isVisible = dto.isVisible,
        isLocked = dto.isLocked,
        zIndex = dto.zIndex
    )

    fun fromLayer(
        layer: Layer,
        bitmapAssetPath: String = ProjectDocumentStore.defaultBitmapAssetPath(layer.id)
    ): RasterLayerDto = RasterLayerDto(
        id = layer.id,
        name = layer.name,
        bitmapAssetPath = bitmapAssetPath,
        pixelWidth = layer.bitmap.width,
        pixelHeight = layer.bitmap.height,
        maskAssetPath = layer.maskBitmap?.let { ProjectDocumentStore.defaultMaskAssetPath(layer.id) },
        opacity = layer.opacity,
        blendMode = layer.blendMode.name,
        isVisible = layer.isVisible,
        isLocked = layer.isLocked,
        isAlphaLocked = layer.isAlphaLocked,
        isClippingMask = layer.isClippingMask,
        isBackground = layer.isBackground,
        collaborationOwnerId = layer.collaborationOwnerId
    )

    fun toLayer(dto: RasterLayerDto, bitmap: Bitmap, maskBitmap: Bitmap? = null): Layer {
        if (bitmap.width != dto.pixelWidth || bitmap.height != dto.pixelHeight) {
            throw ProjectDocumentMappingException(
                "Layer '${dto.id}' bitmap is ${bitmap.width}x${bitmap.height}, " +
                    "expected ${dto.pixelWidth}x${dto.pixelHeight}"
            )
        }
        if (maskBitmap != null &&
            (maskBitmap.width != dto.pixelWidth || maskBitmap.height != dto.pixelHeight)
        ) {
            throw ProjectDocumentMappingException(
                "Layer '${dto.id}' mask is ${maskBitmap.width}x${maskBitmap.height}, " +
                    "expected ${dto.pixelWidth}x${dto.pixelHeight}"
            )
        }
        return Layer(
            id = dto.id,
            name = dto.name,
            bitmap = bitmap,
            opacity = dto.opacity,
            blendMode = enumValue(dto.blendMode, "blend mode"),
            isVisible = dto.isVisible,
            isLocked = dto.isLocked,
            isAlphaLocked = dto.isAlphaLocked,
            isClippingMask = dto.isClippingMask,
            maskBitmap = maskBitmap,
            isBackground = dto.isBackground,
            collaborationOwnerId = dto.collaborationOwnerId
        )
    }

    fun fromScaleConfig(config: UrbanScaleConfig): UrbanScaleConfigDto = UrbanScaleConfigDto(
        pixelsPerMeter = config.pixelsPerMeter,
        gridCellSizeMeters = config.gridCellSizeMeters,
        isGridVisible = config.isGridVisible,
        isScaleCardVisible = config.isScaleCardVisible,
        gridAlpha = config.gridAlpha,
        gridColor = config.gridColor,
        isCalibrated = config.isCalibrated,
        standardRatio = config.standardRatio,
        nodeDistanceMeters = config.nodeDistanceMeters,
        isScaleBarOnMap = config.isScaleBarOnMap,
        scaleBarMapPosition = config.scaleBarMapPosition?.toDto(),
        activeToolStrokeWidth = config.activeToolStrokeWidth,
        activeToolColor = config.activeToolColor
    )

    fun toScaleConfig(dto: UrbanScaleConfigDto): UrbanScaleConfig = UrbanScaleConfig().also {
        applyScaleConfig(dto, it)
    }

    /** Applies decoded values to DrawingView's existing config instance. */
    fun applyScaleConfig(dto: UrbanScaleConfigDto, target: UrbanScaleConfig) {
        target.pixelsPerMeter = dto.pixelsPerMeter
        target.gridCellSizeMeters = dto.gridCellSizeMeters
        target.isGridVisible = dto.isGridVisible
        target.isScaleCardVisible = dto.isScaleCardVisible
        target.gridAlpha = dto.gridAlpha
        target.gridColor = dto.gridColor
        target.isCalibrated = dto.isCalibrated
        target.standardRatio = dto.standardRatio
        target.nodeDistanceMeters = dto.nodeDistanceMeters
        target.isScaleBarOnMap = dto.isScaleBarOnMap
        target.scaleBarMapPosition = dto.scaleBarMapPosition?.toPointF()
        target.activeToolStrokeWidth = dto.activeToolStrokeWidth
        target.activeToolColor = dto.activeToolColor
    }

    fun fromUrbanSettings(
        isUrbanMode: Boolean,
        inputMode: UrbanInputMode,
        activeTool: UrbanToolType?,
        showLiveLegend: Boolean,
        showLiveTables: Boolean,
        legendPlacement: OverlayPlacement,
        tablePlacement: OverlayPlacement,
        scaleCardPlacement: OverlayPlacement
    ): UrbanSettingsDto = UrbanSettingsDto(
        isUrbanMode = isUrbanMode,
        inputMode = inputMode.name,
        activeTool = activeTool?.name,
        showLiveLegend = showLiveLegend,
        showLiveTables = showLiveTables,
        legendPlacement = legendPlacement.name,
        tablePlacement = tablePlacement.name,
        scaleCardPlacement = scaleCardPlacement.name
    )

    fun decodeInputMode(settings: UrbanSettingsDto): UrbanInputMode =
        enumValue(settings.inputMode, "Urban input mode")

    fun decodeActiveTool(settings: UrbanSettingsDto): UrbanToolType? =
        settings.activeTool?.let { enumValue<UrbanToolType>(it, "active Urban tool") }

    fun decodeLegendPlacement(settings: UrbanSettingsDto): OverlayPlacement =
        enumValue(settings.legendPlacement, "legend placement")

    fun decodeTablePlacement(settings: UrbanSettingsDto): OverlayPlacement =
        enumValue(settings.tablePlacement, "table placement")

    fun decodeScaleCardPlacement(settings: UrbanSettingsDto): OverlayPlacement =
        enumValue(settings.scaleCardPlacement, "scale-card placement")

    fun fromUrbanElement(element: UrbanElement): UrbanElementDto = when (element) {
        is UrbanElement.ArrowPath -> UrbanElementDto.ArrowPath(
            id = element.id,
            toolType = element.toolType.name,
            color = element.color,
            points = element.points.map { it.toDto() },
            strokeWidth = element.strokeWidth,
            isDotted = element.isDotted,
            isDashed = element.isDashed,
            hasTrailingDots = element.hasTrailingDots,
            arrowHeadType = element.arrowHeadType.name,
            startLabel = element.startLabel,
            endLabel = element.endLabel,
            stations = element.stations.map { StationMarkerDto(it.point.toDto(), it.label) },
            lengthMeters = element.lengthMeters
        )

        is UrbanElement.HatchPolygon -> UrbanElementDto.HatchPolygon(
            id = element.id,
            toolType = element.toolType.name,
            color = element.color,
            vertices = element.vertices.map { it.toDto() },
            hatchStyle = element.hatchStyle.name,
            hatchSpacing = element.hatchSpacing,
            plazaNumber = element.plazaNumber,
            plazaName = element.plazaName,
            areaSqMeters = element.areaSqMeters
        )

        is UrbanElement.PointMarker -> UrbanElementDto.PointMarker(
            id = element.id,
            toolType = element.toolType.name,
            color = element.color,
            position = element.position.toDto(),
            label = element.label,
            radius = element.radius
        )

        is UrbanElement.BoundaryPath -> UrbanElementDto.BoundaryPath(
            id = element.id,
            toolType = element.toolType.name,
            color = element.color,
            vertices = element.vertices.map { it.toDto() },
            strokeWidth = element.strokeWidth,
            showNodeNumbers = element.showNodeNumbers,
            lengthMeters = element.lengthMeters,
            nodeSpacingPx = element.nodeSpacingPx
        )
    }

    fun toUrbanElement(dto: UrbanElementDto): UrbanElement = when (dto) {
        is UrbanElementDto.ArrowPath -> UrbanElement.ArrowPath(
            id = dto.id,
            toolType = enumValue(dto.toolType, "Urban tool type"),
            color = dto.color,
            points = dto.points.mapTo(mutableListOf()) { it.toPointF() },
            strokeWidth = dto.strokeWidth,
            isDotted = dto.isDotted,
            isDashed = dto.isDashed,
            hasTrailingDots = dto.hasTrailingDots,
            arrowHeadType = enumValue(dto.arrowHeadType, "arrow-head type"),
            startLabel = dto.startLabel,
            endLabel = dto.endLabel,
            stations = dto.stations.mapTo(mutableListOf()) {
                StationMarker(it.point.toPointF(), it.label)
            },
            lengthMeters = dto.lengthMeters
        )

        is UrbanElementDto.HatchPolygon -> UrbanElement.HatchPolygon(
            id = dto.id,
            toolType = enumValue(dto.toolType, "Urban tool type"),
            color = dto.color,
            vertices = dto.vertices.mapTo(mutableListOf()) { it.toPointF() },
            hatchStyle = enumValue(dto.hatchStyle, "hatch style"),
            hatchSpacing = dto.hatchSpacing,
            plazaNumber = dto.plazaNumber,
            plazaName = dto.plazaName,
            areaSqMeters = dto.areaSqMeters
        )

        is UrbanElementDto.PointMarker -> UrbanElement.PointMarker(
            id = dto.id,
            toolType = enumValue(dto.toolType, "Urban tool type"),
            color = dto.color,
            position = dto.position.toPointF(),
            label = dto.label,
            radius = dto.radius
        )

        is UrbanElementDto.BoundaryPath -> UrbanElement.BoundaryPath(
            id = dto.id,
            toolType = enumValue(dto.toolType, "Urban tool type"),
            color = dto.color,
            vertices = dto.vertices.mapTo(mutableListOf()) { it.toPointF() },
            strokeWidth = dto.strokeWidth,
            showNodeNumbers = dto.showNodeNumbers,
            lengthMeters = dto.lengthMeters,
            nodeSpacingPx = dto.nodeSpacingPx
        )
    }

    private fun PointF.toDto() = ProjectPointDto(x, y)

    private fun ProjectPointDto.toPointF() = PointF(x, y)

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw ProjectDocumentMappingException("Unknown $field '$value'")
}
