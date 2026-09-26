package com.procreate.android.urban.model

/**
 * The editable geometry produced by an Urban tool.
 *
 * [COMMAND] is intentionally separate from drawable geometry. A command can collect points
 * (scale calibration currently collects two), but it must not be persisted as an Urban element.
 */
enum class UrbanGeometryKind(val defaultMinimumPoints: Int) {
    POINT(1),
    PATH(2),
    POLYGON(3),
    COMMAND(2)
}

/** The primary quantity shown in the legend/properties panel for an element. */
enum class UrbanMetric {
    COUNT,
    LENGTH,
    AREA,
    NONE
}

/** Line treatment used by both the tool thumbnail and the canvas renderer. */
enum class UrbanStrokePattern {
    NONE,
    SOLID,
    DASHED,
    DOTTED
}

/** Area treatment used by both the tool thumbnail and the canvas renderer. */
enum class UrbanFillPattern {
    NONE,
    SOLID,
    DIAGONAL_45,
    STIPPLE_DOTS,
    RUIN_HATCH
}

/** Optional arrow treatment for path previews. */
enum class UrbanArrowPreview {
    NONE,
    TRIANGLE,
    CHEVRON_WIDE,
    DIRECTION
}

/**
 * Semantic symbol rather than a drawable resource id. This keeps the model platform-neutral and
 * lets phone, tablet, canvas, legend and DXF renderers implement the same visual meaning.
 */
enum class UrbanPreviewSymbol {
    NONE,
    STATION_BADGE,
    STREET_LIGHT,
    MANHOLE,
    WATER_TANK_OR_WELL,
    ELECTRIC_CABINET,
    UTILITY_NODE,
    VEHICLE_SHED,
    WASTE_BIN,
    UTILITY_POLE,
    SCALE_CALIBRATION
}

/** A renderer-neutral contract for how a tool should be represented before placement. */
data class UrbanPreviewSemantics(
    val strokePattern: UrbanStrokePattern = UrbanStrokePattern.NONE,
    val fillPattern: UrbanFillPattern = UrbanFillPattern.NONE,
    val arrow: UrbanArrowPreview = UrbanArrowPreview.NONE,
    val symbol: UrbanPreviewSymbol = UrbanPreviewSymbol.NONE,
    val showVertexNodes: Boolean = false,
    val showLabels: Boolean = false
)

/**
 * Single source of truth for input, measurement and preview behaviour of an Urban tool.
 */
data class UrbanToolDefinition(
    val geometryKind: UrbanGeometryKind,
    val minimumPoints: Int = geometryKind.defaultMinimumPoints,
    val metric: UrbanMetric,
    val preview: UrbanPreviewSemantics
) {
    init {
        require(minimumPoints >= geometryKind.defaultMinimumPoints) {
            "$geometryKind requires at least ${geometryKind.defaultMinimumPoints} point(s)"
        }
        require(geometryKind != UrbanGeometryKind.POINT || minimumPoints == 1) {
            "A point tool must be finalized from exactly one point"
        }
        require(metric != UrbanMetric.COUNT || geometryKind == UrbanGeometryKind.POINT) {
            "COUNT is only valid for point elements"
        }
        require(metric != UrbanMetric.LENGTH || geometryKind == UrbanGeometryKind.PATH) {
            "LENGTH is only valid for path elements"
        }
        require(metric != UrbanMetric.AREA || geometryKind == UrbanGeometryKind.POLYGON) {
            "AREA is only valid for polygon elements"
        }
        require(metric != UrbanMetric.NONE || geometryKind == UrbanGeometryKind.COMMAND) {
            "Only non-persisted commands may omit a metric"
        }
    }

    val createsElement: Boolean
        get() = geometryKind != UrbanGeometryKind.COMMAND

    fun hasEnoughPoints(pointCount: Int): Boolean = pointCount >= minimumPoints
}

/**
 * Exhaustive registry. Adding a new [UrbanToolType] forces this `when` to be updated at compile
 * time, preventing a tool from silently falling back to its category's geometry.
 */
object UrbanToolDefinitions {
    fun forTool(tool: UrbanToolType): UrbanToolDefinition = when (tool) {
        UrbanToolType.PRIMARY_AXIS -> path(
            stroke = UrbanStrokePattern.DOTTED,
            arrow = UrbanArrowPreview.TRIANGLE
        )
        UrbanToolType.SECONDARY_AXIS -> path(
            stroke = UrbanStrokePattern.DASHED,
            arrow = UrbanArrowPreview.TRIANGLE
        )
        UrbanToolType.ENTRY_ARROW -> path(
            arrow = UrbanArrowPreview.CHEVRON_WIDE
        )
        UrbanToolType.REGIONAL_ROAD -> path(
            arrow = UrbanArrowPreview.DIRECTION,
            showLabels = true
        )
        UrbanToolType.STATION_BADGE -> point(
            symbol = UrbanPreviewSymbol.STATION_BADGE,
            showLabels = true
        )

        UrbanToolType.SITE_BOUNDARY -> polygon(
            stroke = UrbanStrokePattern.DASHED,
            showVertexNodes = true,
            showLabels = true
        )
        UrbanToolType.CONTOUR_LINE -> path(
            stroke = UrbanStrokePattern.SOLID,
            showLabels = true
        )

        UrbanToolType.PLAZA_HATCH -> polygon(fill = UrbanFillPattern.DIAGONAL_45, showLabels = true)
        UrbanToolType.FARM_HATCH -> polygon(fill = UrbanFillPattern.STIPPLE_DOTS)
        UrbanToolType.DIRT_PATH -> polygon(fill = UrbanFillPattern.DIAGONAL_45)
        UrbanToolType.ASPHALT_ROAD -> polygon(fill = UrbanFillPattern.SOLID)
        UrbanToolType.HERITAGE_BUILDING -> polygon(fill = UrbanFillPattern.SOLID)
        UrbanToolType.MODERN_BUILDING -> polygon(fill = UrbanFillPattern.SOLID)

        UrbanToolType.POLLUTION_RUIN -> polygon(fill = UrbanFillPattern.RUIN_HATCH)
        UrbanToolType.POLLUTION_WIRES -> path(
            stroke = UrbanStrokePattern.SOLID,
            symbol = UrbanPreviewSymbol.UTILITY_NODE
        )
        UrbanToolType.POLLUTION_SHED -> point(UrbanPreviewSymbol.VEHICLE_SHED)
        UrbanToolType.POLLUTION_TRASH -> point(UrbanPreviewSymbol.WASTE_BIN)
        UrbanToolType.POLLUTION_POLE -> point(UrbanPreviewSymbol.UTILITY_POLE)

        UrbanToolType.INFRA_LIGHT -> point(UrbanPreviewSymbol.STREET_LIGHT)
        UrbanToolType.INFRA_MANHOLE -> point(UrbanPreviewSymbol.MANHOLE)
        UrbanToolType.INFRA_WATER -> point(UrbanPreviewSymbol.WATER_TANK_OR_WELL)
        UrbanToolType.INFRA_ELECTRIC -> point(UrbanPreviewSymbol.ELECTRIC_CABINET)

        UrbanToolType.MEASURE_DISTANCE -> path(
            stroke = UrbanStrokePattern.SOLID,
            showLabels = true
        )
        UrbanToolType.MEASURE_AREA -> polygon(
            stroke = UrbanStrokePattern.SOLID,
            showVertexNodes = true,
            showLabels = true
        )

        UrbanToolType.CALIBRATE_SCALE -> UrbanToolDefinition(
            geometryKind = UrbanGeometryKind.COMMAND,
            metric = UrbanMetric.NONE,
            preview = UrbanPreviewSemantics(
                strokePattern = UrbanStrokePattern.SOLID,
                symbol = UrbanPreviewSymbol.SCALE_CALIBRATION
            )
        )
    }

    private fun point(
        symbol: UrbanPreviewSymbol,
        showLabels: Boolean = false
    ) = UrbanToolDefinition(
        geometryKind = UrbanGeometryKind.POINT,
        metric = UrbanMetric.COUNT,
        preview = UrbanPreviewSemantics(
            strokePattern = UrbanStrokePattern.SOLID,
            symbol = symbol,
            showLabels = showLabels
        )
    )

    private fun path(
        stroke: UrbanStrokePattern = UrbanStrokePattern.SOLID,
        arrow: UrbanArrowPreview = UrbanArrowPreview.NONE,
        symbol: UrbanPreviewSymbol = UrbanPreviewSymbol.NONE,
        showLabels: Boolean = false
    ) = UrbanToolDefinition(
        geometryKind = UrbanGeometryKind.PATH,
        metric = UrbanMetric.LENGTH,
        preview = UrbanPreviewSemantics(
            strokePattern = stroke,
            arrow = arrow,
            symbol = symbol,
            showLabels = showLabels
        )
    )

    private fun polygon(
        stroke: UrbanStrokePattern = UrbanStrokePattern.SOLID,
        fill: UrbanFillPattern = UrbanFillPattern.NONE,
        showVertexNodes: Boolean = false,
        showLabels: Boolean = false
    ) = UrbanToolDefinition(
        geometryKind = UrbanGeometryKind.POLYGON,
        metric = UrbanMetric.AREA,
        preview = UrbanPreviewSemantics(
            strokePattern = stroke,
            fillPattern = fill,
            showVertexNodes = showVertexNodes,
            showLabels = showLabels
        )
    )
}
