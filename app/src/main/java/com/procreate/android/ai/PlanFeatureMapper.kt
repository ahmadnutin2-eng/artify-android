package com.procreate.android.ai

import android.graphics.PointF
import com.procreate.android.urban.model.HatchStyle
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanToolType
import java.util.UUID

/**
 * Turns a model's reading of an image into real, editable Urban elements.
 *
 * Normalised image coordinates become canvas pixels here, using a fit that preserves aspect ratio
 * so an imported plan lands centred and undistorted regardless of how the photo was framed.
 *
 * Every element produced is an ordinary one: the same type the user could have drawn by hand, fully
 * selectable, snappable, undoable and exported to DXF like any other. Nothing is marked as
 * "AI-generated" in the document, because once the user has accepted it, it is simply their
 * drawing - and anything they did not accept never gets here at all.
 */
object PlanFeatureMapper {

    data class Placement(val canvasWidth: Int, val canvasHeight: Int) {
        fun scaleFor(sourceWidth: Int, sourceHeight: Int): Triple<Float, Float, Float> {
            val scale = minOf(
                canvasWidth.toFloat() / sourceWidth,
                canvasHeight.toFloat() / sourceHeight
            )
            val offsetX = (canvasWidth - sourceWidth * scale) / 2f
            val offsetY = (canvasHeight - sourceHeight * scale) / 2f
            return Triple(scale, offsetX, offsetY)
        }
    }

    fun toElements(
        features: List<DetectedFeature>,
        sourceWidth: Int,
        sourceHeight: Int,
        placement: Placement
    ): List<UrbanElement> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyList()
        val (scale, offsetX, offsetY) = placement.scaleFor(sourceWidth, sourceHeight)

        fun toCanvas(point: Pair<Float, Float>) = PointF(
            offsetX + point.first * sourceWidth * scale,
            offsetY + point.second * sourceHeight * scale
        )

        return features.filter { it.isDrawable }.mapNotNull { detected ->
            val tool = detected.feature.toolType
            // Rectangles and circles arrive as parameters, not point lists, so their exact corners
            // are computed here. This is the point of asking for them that way: the orthogonality
            // of a building and the roundness of a fountain come out of arithmetic rather than out
            // of four separately-estimated corners that never quite line up.
            val sourcePoints = when (detected.geometry) {
                PlanGeometry.RECTANGLE -> detected.rectangle?.let(::rectangleCorners).orEmpty()
                PlanGeometry.CIRCLE -> detected.circle?.let(::circlePoints).orEmpty()
                else -> detected.points
            }
            val points = sourcePoints.map(::toCanvas)
            if (points.isEmpty()) return@mapNotNull null

            when (detected.geometry) {
                // Both resolve to closed areas once their corners exist.
                PlanGeometry.RECTANGLE, PlanGeometry.CIRCLE -> UrbanElement.HatchPolygon(
                    id = UUID.randomUUID().toString(),
                    toolType = tool,
                    color = tool.defaultColor,
                    vertices = points.toMutableList(),
                    hatchStyle = hatchFor(tool)
                )
                PlanGeometry.POINT -> UrbanElement.PointMarker(
                    id = UUID.randomUUID().toString(),
                    toolType = tool,
                    color = tool.defaultColor,
                    position = points.first(),
                    label = detected.note
                )

                PlanGeometry.POLYGON -> UrbanElement.HatchPolygon(
                    id = UUID.randomUUID().toString(),
                    toolType = tool,
                    color = tool.defaultColor,
                    vertices = points.toMutableList(),
                    hatchStyle = hatchFor(tool)
                )

                PlanGeometry.LINE -> when (tool) {
                    // Axes and entries read as direction, so they become arrows; everything else
                    // linear is a path. Using the wrong one here would draw an arrowhead on a
                    // contour line, which is meaningless on a plan.
                    UrbanToolType.PRIMARY_AXIS,
                    UrbanToolType.SECONDARY_AXIS,
                    UrbanToolType.ENTRY_ARROW,
                    UrbanToolType.REGIONAL_ROAD -> UrbanElement.ArrowPath(
                        id = UUID.randomUUID().toString(),
                        toolType = tool,
                        color = tool.defaultColor,
                        points = points.toMutableList(),
                        isDotted = tool == UrbanToolType.PRIMARY_AXIS,
                        isDashed = tool == UrbanToolType.SECONDARY_AXIS,
                        endLabel = detected.note
                    )

                    else -> UrbanElement.BoundaryPath(
                        id = UUID.randomUUID().toString(),
                        toolType = tool,
                        color = tool.defaultColor,
                        vertices = points.toMutableList(),
                        // Node numbers belong on a surveyed boundary the user is measuring, not on
                        // every auto-detected road.
                        showNodeNumbers = tool == UrbanToolType.SITE_BOUNDARY
                    )
                }
            }
        }
    }

    /** Exact corners of a rotated rectangle, in the same normalised space as every other point. */
    internal fun rectangleCorners(spec: RectangleSpec): List<Pair<Float, Float>> {
        val halfW = spec.width / 2f
        val halfH = spec.height / 2f
        val radians = Math.toRadians(spec.rotationDegrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        return listOf(
            -halfW to -halfH, halfW to -halfH, halfW to halfH, -halfW to halfH
        ).map { (dx, dy) ->
            (spec.centerX + dx * cos - dy * sin) to (spec.centerY + dx * sin + dy * cos)
        }
    }

    /** A circle as a polygon. 32 segments is smooth at any realistic plan scale while staying
     * light enough to edit by hand afterwards. */
    internal fun circlePoints(spec: CircleSpec, segments: Int = 32): List<Pair<Float, Float>> =
        (0 until segments).map { i ->
            val angle = 2.0 * Math.PI * i / segments
            (spec.centerX + (kotlin.math.cos(angle) * spec.radius).toFloat()) to
                (spec.centerY + (kotlin.math.sin(angle) * spec.radius).toFloat())
        }

    private fun hatchFor(tool: UrbanToolType): HatchStyle = when (tool) {
        UrbanToolType.FARM_HATCH -> HatchStyle.STIPPLE_DOTS
        UrbanToolType.PLAZA_HATCH -> HatchStyle.DIAGONAL_45
        else -> HatchStyle.SOLID_FILL
    }
}
