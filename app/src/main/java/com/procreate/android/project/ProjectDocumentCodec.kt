package com.procreate.android.project

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets

class ProjectDocumentFormatException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/** JSON codec for project.json. No UI or database state is touched while encoding/decoding. */
object ProjectDocumentCodec {
    fun encode(document: ProjectDocumentDto): String {
        ProjectDocumentValidator.validate(document)
        return JSONObject()
            .put("format", PROJECT_DOCUMENT_FORMAT)
            .put("schemaVersion", document.schemaVersion)
            .put("projectType", document.projectType.name)
            .put("createdAtEpochMillis", document.createdAtEpochMillis)
            .put("modifiedAtEpochMillis", document.modifiedAtEpochMillis)
            .put("canvas", encodeCanvas(document.canvas))
            .putNullable("activeRasterLayerId", document.activeRasterLayerId)
            .put("rasterLayers", JSONArray().apply {
                document.rasterLayers.forEach { put(encodeLayer(it)) }
            })
            .putNullable("urban", document.urban?.let(::encodeUrban))
            .toString()
    }

    fun encodeToBytes(document: ProjectDocumentDto): ByteArray =
        encode(document).toByteArray(StandardCharsets.UTF_8)

    @Throws(ProjectDocumentFormatException::class)
    fun decode(encoded: String): ProjectDocumentDto {
        try {
            val root = JSONObject(encoded)
            val format = root.getString("format")
            if (format != PROJECT_DOCUMENT_FORMAT) {
                throw ProjectDocumentFormatException("Unsupported project format '$format'")
            }
            val schemaVersion = root.getInt("schemaVersion")
            if (schemaVersion !in 1..CURRENT_PROJECT_SCHEMA_VERSION) {
                throw ProjectDocumentFormatException(
                    "Unsupported project schema $schemaVersion; this app supports up to " +
                        CURRENT_PROJECT_SCHEMA_VERSION
                )
            }
            val document = when (schemaVersion) {
                // Later fields are optional, so all earlier schemas migrate through the tolerant
                // decoder. Schema 4 adds Base colour and optional collaboration layer ownership.
                1, 2, 3, 4 -> decodeVersion1(root).copy(schemaVersion = CURRENT_PROJECT_SCHEMA_VERSION)
                else -> throw ProjectDocumentFormatException(
                    "No decoder registered for project schema $schemaVersion"
                )
            }
            ProjectDocumentValidator.validate(document)
            return document
        } catch (error: ProjectDocumentFormatException) {
            throw error
        } catch (error: JSONException) {
            throw ProjectDocumentFormatException("Malformed Artify project document", error)
        } catch (error: IllegalArgumentException) {
            throw ProjectDocumentFormatException("Invalid value in Artify project document", error)
        }
    }

    @Throws(ProjectDocumentFormatException::class)
    fun decode(encoded: ByteArray): ProjectDocumentDto =
        decode(String(encoded, StandardCharsets.UTF_8))

    private fun decodeVersion1(root: JSONObject): ProjectDocumentDto {
        val projectTypeName = root.getString("projectType")
        val projectType = enumValue<ProjectType>(projectTypeName, "projectType")
        return ProjectDocumentDto(
            schemaVersion = CURRENT_PROJECT_SCHEMA_VERSION,
            projectType = projectType,
            canvas = decodeCanvas(root.getJSONObject("canvas")),
            rasterLayers = root.getJSONArray("rasterLayers").mapObjects(::decodeLayer),
            activeRasterLayerId = root.optionalString("activeRasterLayerId"),
            urban = root.optionalObject("urban")?.let(::decodeUrban),
            createdAtEpochMillis = root.optLong("createdAtEpochMillis", 0L),
            modifiedAtEpochMillis = root.optLong("modifiedAtEpochMillis", 0L)
        )
    }

    private fun encodeCanvas(canvas: ProjectCanvasDto) = JSONObject()
        .put("width", canvas.width)
        .put("height", canvas.height)
        .put("backgroundStyle", canvas.backgroundStyle)
        .put("backgroundColor", canvas.backgroundColor)
        .put("isOpenCanvas", canvas.isOpenCanvas)

    private fun decodeCanvas(json: JSONObject) = ProjectCanvasDto(
        width = json.getInt("width"),
        height = json.getInt("height"),
        backgroundStyle = json.optString("backgroundStyle", "BLANK"),
        backgroundColor = json.optInt("backgroundColor", 0xFFFFFFFF.toInt()),
        isOpenCanvas = json.optBoolean("isOpenCanvas", false)
    )

    private fun encodeLayer(layer: RasterLayerDto) = JSONObject()
        .put("id", layer.id)
        .put("name", layer.name)
        .put("bitmapAssetPath", layer.bitmapAssetPath)
        .put("pixelWidth", layer.pixelWidth)
        .put("pixelHeight", layer.pixelHeight)
        .putNullable("maskAssetPath", layer.maskAssetPath)
        .put("opacity", layer.opacity.toDouble())
        .put("blendMode", layer.blendMode)
        .put("isVisible", layer.isVisible)
        .put("isLocked", layer.isLocked)
        .put("isAlphaLocked", layer.isAlphaLocked)
        .put("isClippingMask", layer.isClippingMask)
        .put("isBackground", layer.isBackground)
        .putNullable("collaborationOwnerId", layer.collaborationOwnerId)

    private fun decodeLayer(json: JSONObject) = RasterLayerDto(
        id = json.getString("id"),
        name = json.getString("name"),
        bitmapAssetPath = json.getString("bitmapAssetPath"),
        pixelWidth = json.getInt("pixelWidth"),
        pixelHeight = json.getInt("pixelHeight"),
        maskAssetPath = json.optionalString("maskAssetPath"),
        opacity = json.optDouble("opacity", 1.0).toFloat(),
        blendMode = json.optString("blendMode", "Normal"),
        isVisible = json.optBoolean("isVisible", true),
        isLocked = json.optBoolean("isLocked", false),
        isAlphaLocked = json.optBoolean("isAlphaLocked", false),
        isClippingMask = json.optBoolean("isClippingMask", false),
        isBackground = json.optBoolean("isBackground", false),
        collaborationOwnerId = json.optionalString("collaborationOwnerId")
    )

    private fun encodeUrban(urban: UrbanProjectDto) = JSONObject()
        .put("coordinateSpace", urban.coordinateSpace.name)
        .put("scale", encodeScale(urban.scale))
        .put("settings", encodeUrbanSettings(urban.settings))
        .put("elements", JSONArray().apply {
            urban.elements.forEach { put(encodeUrbanElement(it)) }
        })
        .put("measurements", JSONArray().apply {
            urban.measurements.forEach { put(encodeMeasurement(it)) }
        })
        .put("architecturalAssets", JSONArray().apply {
            urban.architecturalAssets.forEach { put(encodeArchitecturalAsset(it)) }
        })

    private fun decodeUrban(json: JSONObject) = UrbanProjectDto(
        coordinateSpace = enumValue(
            json.optString("coordinateSpace", UrbanCoordinateSpace.CANVAS_PIXELS.name),
            "Urban coordinate space"
        ),
        scale = json.optionalObject("scale")?.let(::decodeScale) ?: UrbanScaleConfigDto(),
        settings = json.optionalObject("settings")?.let(::decodeUrbanSettings)
            ?: UrbanSettingsDto(),
        elements = (json.optJSONArray("elements") ?: JSONArray()).mapObjects(::decodeUrbanElement),
        measurements = (json.optJSONArray("measurements") ?: JSONArray()).mapObjects(::decodeMeasurement),
        architecturalAssets = (json.optJSONArray("architecturalAssets") ?: JSONArray())
            .mapObjects(::decodeArchitecturalAsset)
    )

    private fun encodeMeasurement(measurement: UrbanMeasurementDto) = JSONObject()
        .put("id", measurement.id)
        .put("kind", measurement.kind)
        .put("points", encodePoints(measurement.points))
        .put("displayUnit", measurement.displayUnit)
        .putNullable("label", measurement.label)

    private fun decodeMeasurement(json: JSONObject) = UrbanMeasurementDto(
        id = json.getString("id"),
        kind = json.getString("kind"),
        points = (json.optJSONArray("points") ?: JSONArray()).mapObjects(::decodePoint),
        displayUnit = json.optString("displayUnit", "METER"),
        label = json.optionalString("label")
    )

    private fun encodeArchitecturalAsset(asset: ArchitecturalAssetInstanceDto) = JSONObject()
        .put("id", asset.id)
        .put("assetId", asset.assetId)
        .put("xMeters", asset.xMeters)
        .put("yMeters", asset.yMeters)
        .put("scale", asset.scale)
        .put("rotationDegrees", asset.rotationDegrees)
        .putNullable("colorOverrideArgb", asset.colorOverrideArgb)
        .put("opacity", asset.opacity.toDouble())
        .put("isVisible", asset.isVisible)
        .put("isLocked", asset.isLocked)
        .put("zIndex", asset.zIndex)

    private fun decodeArchitecturalAsset(json: JSONObject) = ArchitecturalAssetInstanceDto(
        id = json.getString("id"),
        assetId = json.getString("assetId"),
        xMeters = json.getDouble("xMeters"),
        yMeters = json.getDouble("yMeters"),
        scale = json.optDouble("scale", 1.0),
        rotationDegrees = json.optDouble("rotationDegrees", 0.0),
        colorOverrideArgb = json.optionalInt("colorOverrideArgb"),
        opacity = json.optDouble("opacity", 1.0).toFloat(),
        isVisible = json.optBoolean("isVisible", true),
        isLocked = json.optBoolean("isLocked", false),
        zIndex = json.optInt("zIndex", 0)
    )

    private fun encodeUrbanSettings(settings: UrbanSettingsDto) = JSONObject()
        .put("isUrbanMode", settings.isUrbanMode)
        .put("inputMode", settings.inputMode)
        .putNullable("activeTool", settings.activeTool)
        .put("showLiveLegend", settings.showLiveLegend)
        .put("showLiveTables", settings.showLiveTables)
        .put("legendPlacement", settings.legendPlacement)
        .put("tablePlacement", settings.tablePlacement)
        .put("scaleCardPlacement", settings.scaleCardPlacement)

    private fun decodeUrbanSettings(json: JSONObject) = UrbanSettingsDto(
        isUrbanMode = json.optBoolean("isUrbanMode", true),
        inputMode = json.optString("inputMode", "POINT_BY_POINT"),
        activeTool = json.optionalString("activeTool"),
        showLiveLegend = json.optBoolean("showLiveLegend", true),
        showLiveTables = json.optBoolean("showLiveTables", true),
        legendPlacement = json.optString("legendPlacement", "BOTTOM_RIGHT"),
        tablePlacement = json.optString("tablePlacement", "BOTTOM_LEFT"),
        scaleCardPlacement = json.optString("scaleCardPlacement", "BOTTOM_LEFT")
    )

    private fun encodeScale(scale: UrbanScaleConfigDto) = JSONObject()
        .put("pixelsPerMeter", scale.pixelsPerMeter.toDouble())
        .put("gridCellSizeMeters", scale.gridCellSizeMeters.toDouble())
        .put("isGridVisible", scale.isGridVisible)
        .put("isScaleCardVisible", scale.isScaleCardVisible)
        .put("gridAlpha", scale.gridAlpha)
        .put("gridColor", scale.gridColor)
        .put("isCalibrated", scale.isCalibrated)
        .put("standardRatio", scale.standardRatio)
        .put("nodeDistanceMeters", scale.nodeDistanceMeters.toDouble())
        .put("isScaleBarOnMap", scale.isScaleBarOnMap)
        .putNullable("scaleBarMapPosition", scale.scaleBarMapPosition?.let(::encodePoint))
        .put("activeToolStrokeWidth", scale.activeToolStrokeWidth.toDouble())
        .put("activeToolColor", scale.activeToolColor)

    private fun decodeScale(json: JSONObject) = UrbanScaleConfigDto(
        pixelsPerMeter = json.optDouble("pixelsPerMeter", 5.0).toFloat(),
        gridCellSizeMeters = json.optDouble("gridCellSizeMeters", 50.0).toFloat(),
        isGridVisible = json.optBoolean("isGridVisible", true),
        isScaleCardVisible = json.optBoolean("isScaleCardVisible", true),
        gridAlpha = json.optInt("gridAlpha", 70),
        gridColor = json.optInt("gridColor", 0xFF555555.toInt()),
        isCalibrated = json.optBoolean("isCalibrated", false),
        standardRatio = json.optInt("standardRatio", 1000),
        nodeDistanceMeters = json.optDouble("nodeDistanceMeters", 20.0).toFloat(),
        isScaleBarOnMap = json.optBoolean("isScaleBarOnMap", true),
        scaleBarMapPosition = json.optionalObject("scaleBarMapPosition")?.let(::decodePoint),
        activeToolStrokeWidth = json.optDouble("activeToolStrokeWidth", 14.0).toFloat(),
        activeToolColor = json.optInt("activeToolColor", 0)
    )

    private fun encodeUrbanElement(element: UrbanElementDto): JSONObject = when (element) {
        is UrbanElementDto.ArrowPath -> encodeElementBase("ARROW_PATH", element)
            .put("points", encodePoints(element.points))
            .put("strokeWidth", element.strokeWidth.toDouble())
            .put("isDotted", element.isDotted)
            .put("isDashed", element.isDashed)
            .put("hasTrailingDots", element.hasTrailingDots)
            .put("arrowHeadType", element.arrowHeadType)
            .putNullable("startLabel", element.startLabel)
            .putNullable("endLabel", element.endLabel)
            .put("stations", JSONArray().apply {
                element.stations.forEach { station ->
                    put(JSONObject().put("point", encodePoint(station.point)).put("label", station.label))
                }
            })
            .put("lengthMeters", element.lengthMeters.toDouble())

        is UrbanElementDto.HatchPolygon -> encodeElementBase("HATCH_POLYGON", element)
            .put("vertices", encodePoints(element.vertices))
            .put("hatchStyle", element.hatchStyle)
            .put("hatchSpacing", element.hatchSpacing.toDouble())
            .putNullable("plazaNumber", element.plazaNumber)
            .putNullable("plazaName", element.plazaName)
            .put("areaSqMeters", element.areaSqMeters.toDouble())

        is UrbanElementDto.PointMarker -> encodeElementBase("POINT_MARKER", element)
            .put("position", encodePoint(element.position))
            .putNullable("label", element.label)
            .put("radius", element.radius.toDouble())

        is UrbanElementDto.BoundaryPath -> encodeElementBase("BOUNDARY_PATH", element)
            .put("vertices", encodePoints(element.vertices))
            .put("strokeWidth", element.strokeWidth.toDouble())
            .put("showNodeNumbers", element.showNodeNumbers)
            .put("lengthMeters", element.lengthMeters.toDouble())
            .put("nodeSpacingPx", element.nodeSpacingPx.toDouble())
    }

    private fun encodeElementBase(kind: String, element: UrbanElementDto) = JSONObject()
        .put("kind", kind)
        .put("id", element.id)
        .put("toolType", element.toolType)
        .put("color", element.color)

    private fun decodeUrbanElement(json: JSONObject): UrbanElementDto {
        val id = json.getString("id")
        val toolType = json.getString("toolType")
        val color = json.getInt("color")
        return when (val kind = json.getString("kind")) {
            "ARROW_PATH" -> UrbanElementDto.ArrowPath(
                id = id,
                toolType = toolType,
                color = color,
                points = (json.optJSONArray("points") ?: JSONArray()).mapObjects(::decodePoint),
                strokeWidth = json.optDouble("strokeWidth", 14.0).toFloat(),
                isDotted = json.optBoolean("isDotted", false),
                isDashed = json.optBoolean("isDashed", false),
                hasTrailingDots = json.optBoolean("hasTrailingDots", true),
                arrowHeadType = json.optString("arrowHeadType", "LARGE_TRIANGLE"),
                startLabel = json.optionalString("startLabel"),
                endLabel = json.optionalString("endLabel"),
                stations = (json.optJSONArray("stations") ?: JSONArray()).mapObjects {
                    StationMarkerDto(decodePoint(it.getJSONObject("point")), it.getString("label"))
                },
                lengthMeters = json.optDouble("lengthMeters", 0.0).toFloat()
            )

            "HATCH_POLYGON" -> UrbanElementDto.HatchPolygon(
                id = id,
                toolType = toolType,
                color = color,
                vertices = (json.optJSONArray("vertices") ?: JSONArray()).mapObjects(::decodePoint),
                hatchStyle = json.optString("hatchStyle", "DIAGONAL_45"),
                hatchSpacing = json.optDouble("hatchSpacing", 24.0).toFloat(),
                plazaNumber = json.optionalInt("plazaNumber"),
                plazaName = json.optionalString("plazaName"),
                areaSqMeters = json.optDouble("areaSqMeters", 0.0).toFloat()
            )

            "POINT_MARKER" -> UrbanElementDto.PointMarker(
                id = id,
                toolType = toolType,
                color = color,
                position = decodePoint(json.getJSONObject("position")),
                label = json.optionalString("label"),
                radius = json.optDouble("radius", 16.0).toFloat()
            )

            "BOUNDARY_PATH" -> UrbanElementDto.BoundaryPath(
                id = id,
                toolType = toolType,
                color = color,
                vertices = (json.optJSONArray("vertices") ?: JSONArray()).mapObjects(::decodePoint),
                strokeWidth = json.optDouble("strokeWidth", 8.0).toFloat(),
                showNodeNumbers = json.optBoolean("showNodeNumbers", true),
                lengthMeters = json.optDouble("lengthMeters", 0.0).toFloat(),
                nodeSpacingPx = json.optDouble("nodeSpacingPx", 0.0).toFloat()
            )

            else -> throw ProjectDocumentFormatException("Unknown Urban element kind '$kind'")
        }
    }

    private fun encodePoints(points: List<ProjectPointDto>) = JSONArray().apply {
        points.forEach { put(encodePoint(it)) }
    }

    private fun encodePoint(point: ProjectPointDto) = JSONObject()
        .put("x", point.x.toDouble())
        .put("y", point.y.toDouble())

    private fun decodePoint(json: JSONObject) = ProjectPointDto(
        x = json.getDouble("x").toFloat(),
        y = json.getDouble("y").toFloat()
    )

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optionalInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private fun JSONObject.optionalObject(name: String): JSONObject? =
        if (!has(name) || isNull(name)) null else getJSONObject(name)

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw ProjectDocumentFormatException("Unknown $field '$value'")
}

/** Central validation used before both disk writes and accepting decoded untrusted content. */
object ProjectDocumentValidator {
    @Throws(ProjectDocumentFormatException::class)
    fun validate(document: ProjectDocumentDto) {
        requireFormat(document.schemaVersion == CURRENT_PROJECT_SCHEMA_VERSION) {
            "Project schema ${document.schemaVersion} is not writable"
        }
        requireFormat(document.canvas.width > 0 && document.canvas.height > 0) {
            "Canvas dimensions must be positive"
        }
        requireFormat(document.canvas.backgroundStyle.isNotBlank()) {
            "Canvas background style is blank"
        }
        requireFormat(document.createdAtEpochMillis >= 0L && document.modifiedAtEpochMillis >= 0L) {
            "Project timestamps cannot be negative"
        }
        requireFormat(document.modifiedAtEpochMillis >= document.createdAtEpochMillis) {
            "Project modification time predates its creation time"
        }
        val layerIds = HashSet<String>()
        val layerPaths = HashSet<String>()
        document.rasterLayers.forEach { layer ->
            requireFormat(layer.id.isNotBlank() && layerIds.add(layer.id)) {
                "Raster layer ids must be non-blank and unique"
            }
            requireFormat(isSafeRelativeAssetPath(layer.bitmapAssetPath)) {
                "Unsafe raster asset path '${layer.bitmapAssetPath}'"
            }
            requireFormat(layerPaths.add(layer.bitmapAssetPath)) {
                "Raster layers cannot share asset path '${layer.bitmapAssetPath}'"
            }
            layer.maskAssetPath?.let { maskPath ->
                requireFormat(isSafeRelativeAssetPath(maskPath)) {
                    "Unsafe raster mask asset path '$maskPath'"
                }
                requireFormat(layerPaths.add(maskPath)) {
                    "Raster bitmap and mask assets cannot share path '$maskPath'"
                }
            }
            requireFormat(layer.pixelWidth > 0 && layer.pixelHeight > 0) {
                "Raster layer '${layer.id}' dimensions must be positive"
            }
            requireFormat(layer.opacity.isFinite() && layer.opacity in 0f..1f) {
                "Raster layer '${layer.id}' opacity must be between 0 and 1"
            }
            requireFormat(layer.blendMode.isNotBlank()) {
                "Raster layer '${layer.id}' blend mode is blank"
            }
        }
        document.activeRasterLayerId?.let { activeId ->
            requireFormat(activeId in layerIds) {
                "Active raster layer '$activeId' does not exist"
            }
        }
        if (document.projectType == ProjectType.URBAN_DESIGN) {
            requireFormat(document.urban != null) {
                "Urban Design projects must include Urban project data"
            }
        }
        document.urban?.let(::validateUrban)
    }

    internal fun isSafeRelativeAssetPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        if (path.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(path)) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun validateUrban(urban: UrbanProjectDto) {
        val scale = urban.scale
        requirePositiveFinite(scale.pixelsPerMeter, "pixelsPerMeter")
        requirePositiveFinite(scale.gridCellSizeMeters, "gridCellSizeMeters")
        requireFormat(scale.gridAlpha in 0..255) { "gridAlpha must be between 0 and 255" }
        requireFormat(scale.standardRatio > 0) { "standardRatio must be positive" }
        requirePositiveFinite(scale.nodeDistanceMeters, "nodeDistanceMeters")
        requirePositiveFinite(scale.activeToolStrokeWidth, "activeToolStrokeWidth")
        scale.scaleBarMapPosition?.let { validatePoint(it, "scaleBarMapPosition") }

        val settings = urban.settings
        requireFormat(settings.inputMode.isNotBlank()) { "Urban input mode is blank" }
        requireFormat(settings.legendPlacement.isNotBlank()) { "Legend placement is blank" }
        requireFormat(settings.tablePlacement.isNotBlank()) { "Table placement is blank" }
        requireFormat(settings.scaleCardPlacement.isNotBlank()) { "Scale-card placement is blank" }

        val ids = HashSet<String>()
        urban.elements.forEach { element ->
            requireFormat(element.id.isNotBlank() && ids.add(element.id)) {
                "Urban element ids must be non-blank and unique"
            }
            requireFormat(element.toolType.isNotBlank()) {
                "Urban element '${element.id}' tool type is blank"
            }
            when (element) {
                is UrbanElementDto.ArrowPath -> {
                    validatePoints(element.points, element.id)
                    requirePositiveFinite(element.strokeWidth, "${element.id}.strokeWidth")
                    requireFormat(element.arrowHeadType.isNotBlank()) {
                        "Urban element '${element.id}' arrow-head type is blank"
                    }
                    requireNonNegativeFinite(element.lengthMeters, "${element.id}.lengthMeters")
                    element.stations.forEach { station ->
                        validatePoint(station.point, "${element.id}.station")
                    }
                }

                is UrbanElementDto.HatchPolygon -> {
                    validatePoints(element.vertices, element.id)
                    requirePositiveFinite(element.hatchSpacing, "${element.id}.hatchSpacing")
                    requireFormat(element.hatchStyle.isNotBlank()) {
                        "Urban element '${element.id}' hatch style is blank"
                    }
                    requireNonNegativeFinite(element.areaSqMeters, "${element.id}.areaSqMeters")
                }

                is UrbanElementDto.PointMarker -> {
                    validatePoint(element.position, element.id)
                    requirePositiveFinite(element.radius, "${element.id}.radius")
                }

                is UrbanElementDto.BoundaryPath -> {
                    validatePoints(element.vertices, element.id)
                    requirePositiveFinite(element.strokeWidth, "${element.id}.strokeWidth")
                    requireNonNegativeFinite(element.lengthMeters, "${element.id}.lengthMeters")
                    requireNonNegativeFinite(element.nodeSpacingPx, "${element.id}.nodeSpacingPx")
                }
            }
        }

        urban.measurements.forEach { measurement ->
            requireFormat(measurement.id.isNotBlank() && ids.add(measurement.id)) {
                "Urban measurement ids must be non-blank and unique"
            }
            requireFormat(measurement.kind == "POLYLINE_LENGTH" || measurement.kind == "POLYGON_AREA") {
                "Unknown measurement kind '${measurement.kind}'"
            }
            requireFormat(measurement.displayUnit in setOf("CENTIMETER", "METER", "KILOMETER")) {
                "Unknown measurement unit '${measurement.displayUnit}'"
            }
            val minimum = if (measurement.kind == "POLYGON_AREA") 3 else 2
            requireFormat(measurement.points.distinct().size >= minimum) {
                "Measurement '${measurement.id}' requires at least $minimum distinct points"
            }
            validatePoints(measurement.points, measurement.id)
        }

        urban.architecturalAssets.forEach { asset ->
            requireFormat(asset.id.isNotBlank() && ids.add(asset.id)) {
                "Architectural asset ids must be non-blank and unique"
            }
            requireFormat(asset.assetId.isNotBlank()) { "Architectural asset catalog id is blank" }
            requireFormat(asset.xMeters.isFinite() && asset.yMeters.isFinite()) {
                "Architectural asset '${asset.id}' position is non-finite"
            }
            requireFormat(asset.scale.isFinite() && asset.scale > 0.0) {
                "Architectural asset '${asset.id}' scale must be positive and finite"
            }
            requireFormat(asset.rotationDegrees.isFinite()) {
                "Architectural asset '${asset.id}' rotation is non-finite"
            }
            requireFormat(asset.opacity.isFinite() && asset.opacity in 0f..1f) {
                "Architectural asset '${asset.id}' opacity must be between 0 and 1"
            }
        }
    }

    private fun validatePoints(points: List<ProjectPointDto>, field: String) {
        points.forEach { validatePoint(it, field) }
    }

    private fun validatePoint(point: ProjectPointDto, field: String) {
        requireFormat(point.x.isFinite() && point.y.isFinite()) {
            "Point in '$field' contains a non-finite coordinate"
        }
    }

    private fun requirePositiveFinite(value: Float, field: String) {
        requireFormat(value.isFinite() && value > 0f) { "$field must be positive and finite" }
    }

    private fun requireNonNegativeFinite(value: Float, field: String) {
        requireFormat(value.isFinite() && value >= 0f) { "$field cannot be negative or non-finite" }
    }

    private inline fun requireFormat(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) throw ProjectDocumentFormatException(lazyMessage())
    }
}
