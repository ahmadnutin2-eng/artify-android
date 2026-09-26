package com.procreate.android.export

import android.graphics.Color
import android.graphics.PointF
import com.procreate.android.export.dxf.ArchitecturalAssetDxfExporter
import com.procreate.android.export.dxf.DxfDocument
import com.procreate.android.export.dxf.DxfEntity
import com.procreate.android.export.dxf.DxfHatchPattern
import com.procreate.android.export.dxf.DxfPoint
import com.procreate.android.export.dxf.MeasurementDxfExporter
import com.procreate.android.export.dxf.pixelLengthToDxf
import com.procreate.android.export.dxf.pixelToDxf
import com.procreate.android.urban.assets.AssetInstance
import com.procreate.android.urban.measurement.MeasurementElement
import com.procreate.android.urban.model.ArrowHeadType
import com.procreate.android.urban.model.HatchStyle
import com.procreate.android.urban.model.LegendItem
import com.procreate.android.urban.model.UrbanElement
import com.procreate.android.urban.model.UrbanScaleConfig
import com.procreate.android.urban.model.UrbanToolType
import com.procreate.android.urban.tools.UrbanSymbolRenderer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Walks the app's own Urban Design data model (never a Canvas-recording proxy - see the
 * accompanying plan doc for why) and turns it into a DXF document: every element type becomes
 * real, editable geometry on a layer matching its [UrbanToolType], coloured with the element's own
 * true RGB. General free-hand painting layers are handled separately (see [brushRaster]) since
 * they were explicitly agreed to stay a flattened raster rather than being vectorised.
 */
object UrbanDxfExporter {

    /** A pre-flattened raster of the general (non-Urban) painting layers, already rendered via the
     * same LayerCompositor.draw(...) call the existing PNG/PDF export uses - this class only
     * anchors/scales it into the same DXF world-space, it never touches Canvas/Bitmap itself. */
    data class BrushRasterInfo(
        val relativeFileName: String,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val anchorXPixels: Float,
        val anchorYPixels: Float
    )

    private const val TEXT_HEIGHT_PIXELS = 18f
    private const val NODE_RADIUS_PIXELS = 14f

    fun export(
        urbanElements: List<UrbanElement>,
        scaleConfig: UrbanScaleConfig,
        legendItems: List<LegendItem>,
        brushRaster: BrushRasterInfo? = null,
        measurements: List<MeasurementElement> = emptyList(),
        architecturalAssets: List<AssetInstance> = emptyList()
    ): String {
        val doc = DxfDocument()
        val ppm = scaleConfig.pixelsPerMeter
        doc.addLayer("0", 7)
        doc.addLayer("ANNOTATION", 7)
        doc.addLayer("RASTER_REFERENCE", 7)
        for (toolType in UrbanToolType.entries) doc.addLayer(toolType.name, nearestAci(toolType.defaultColor))
        // Each sub-exporter owns its own layer names, so they're registered from that single
        // source rather than restated here where they could drift apart silently.
        for (layer in MeasurementDxfExporter.requiredLayers) doc.addLayer(layer, 5)
        for (layer in ArchitecturalAssetDxfExporter.requiredLayers) doc.addLayer(layer, 3)

        for (element in urbanElements) {
            when (element) {
                is UrbanElement.ArrowPath -> exportArrow(doc, element, ppm)
                is UrbanElement.HatchPolygon -> exportHatchPolygon(doc, element, ppm)
                is UrbanElement.PointMarker -> exportPointMarker(doc, element, ppm)
                is UrbanElement.BoundaryPath -> exportBoundary(doc, element, ppm)
            }
        }

        // Assets already carry real-world metre coordinates, so they bypass the pixel transform
        // the elements above need; measurements are still stored in canvas pixels and convert
        // through the same pixelsPerMeter ratio everything else uses.
        for (entity in ArchitecturalAssetDxfExporter().export(architecturalAssets)) doc.addEntity(entity)
        for (measurement in measurements) {
            for (entity in MeasurementDxfExporter.export(measurement, ppm.toDouble())) doc.addEntity(entity)
        }

        exportLegend(doc, legendItems, ppm)

        brushRaster?.let {
            val insertion = pixelToDxf(it.anchorXPixels, it.anchorYPixels + it.pixelHeight, ppm, 0f, 0f)
            doc.setImage(
                DxfEntity.Image(
                    layer = "RASTER_REFERENCE", rgb = 0xFFFFFF, aci = 7,
                    insertion = insertion,
                    widthMeters = pixelLengthToDxf(it.pixelWidth.toFloat(), ppm),
                    heightMeters = pixelLengthToDxf(it.pixelHeight.toFloat(), ppm),
                    relativeImagePath = it.relativeFileName,
                    pixelWidth = it.pixelWidth, pixelHeight = it.pixelHeight
                )
            )
        }

        return doc.serialize()
    }

    private fun toDxf(p: PointF, ppm: Float) = pixelToDxf(p.x, p.y, ppm, 0f, 0f)
    private fun len(pixels: Float, ppm: Float) = pixelLengthToDxf(pixels, ppm)

    private fun rgbOf(color: Int) = color and 0x00FFFFFF

    /** Nearest-neighbour fallback among the handful of "pure" ACI colours - true colour (group
     * 420) is the primary signal every entity carries; this is only read by tools that ignore it. */
    private fun nearestAci(color: Int): Int {
        val candidates = listOf(
            1 to Triple(255, 0, 0), 2 to Triple(255, 255, 0), 3 to Triple(0, 255, 0),
            4 to Triple(0, 255, 255), 5 to Triple(0, 0, 255), 6 to Triple(255, 0, 255),
            7 to Triple(255, 255, 255), 8 to Triple(128, 128, 128), 9 to Triple(192, 192, 192)
        )
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return candidates.minByOrNull { (_, rgb) ->
            val (cr, cg, cb) = rgb
            (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
        }?.first ?: 7
    }

    private fun circleBoundary(center: DxfPoint, radius: Double, segments: Int = 24): List<DxfPoint> =
        (0 until segments).map { i ->
            val a = 2.0 * Math.PI * i / segments
            DxfPoint(center.x + radius * cos(a), center.y + radius * sin(a))
        }

    /** Reverses to a consistent (counter-clockwise) winding via the shoelace signed area - user-
     * drawn polygons have no guaranteed winding, and a HATCH boundary is more robust when normalized. */
    private fun normalizeWinding(pts: List<DxfPoint>): List<DxfPoint> {
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            area += a.x * b.y - b.x * a.y
        }
        return if (area < 0) pts.reversed() else pts
    }

    private fun centroidOf(pts: List<PointF>): PointF {
        var cx = 0f; var cy = 0f
        for (p in pts) { cx += p.x; cy += p.y }
        return PointF(cx / pts.size, cy / pts.size)
    }

    // ---------------------------------------------------------------- ArrowPath

    private fun exportArrow(doc: DxfDocument, arrow: UrbanElement.ArrowPath, ppm: Float) {
        val pts = arrow.points
        if (pts.size < 2) return
        val layer = arrow.toolType.name
        val rgb = rgbOf(arrow.color)
        val aci = nearestAci(arrow.color)
        val dxfPts = pts.map { toDxf(it, ppm) }
        // Matches UrbanArrowRenderer.renderArrow's own precedence: isDotted is checked before
        // isDashed, not treated as independent flags.
        val linetype = if (arrow.isDotted) "URBAN_DOT" else if (arrow.isDashed) "URBAN_DASH" else "CONTINUOUS"
        doc.addEntity(
            DxfEntity.LwPolyline(
                layer, rgb, aci, dxfPts, closed = false,
                constantWidth = len(arrow.strokeWidth, ppm), linetype = linetype
            )
        )

        val endP = pts.last(); val prevP = pts[pts.size - 2]
        val angle = atan2((endP.y - prevP.y).toDouble(), (endP.x - prevP.x).toDouble())
        exportArrowHead(doc, layer, rgb, aci, endP, angle, arrow.arrowHeadType, arrow.strokeWidth, ppm)

        if (arrow.arrowHeadType == ArrowHeadType.DOUBLE_HEAD) {
            val startP = pts.first(); val nextP = pts[1]
            val startAngle = atan2((startP.y - nextP.y).toDouble(), (startP.x - nextP.x).toDouble())
            exportArrowHead(doc, layer, rgb, aci, startP, startAngle, ArrowHeadType.LARGE_TRIANGLE, arrow.strokeWidth, ppm)
        }

        for (st in arrow.stations) {
            val center = toDxf(st.point, ppm)
            val r = len(22f, ppm)
            doc.addEntity(DxfEntity.Hatch(layer, rgbOf(Color.WHITE), 7, circleBoundary(center, r), DxfHatchPattern.SOLID))
            doc.addEntity(DxfEntity.Circle(layer, rgb, aci, center, r))
            doc.addEntity(DxfEntity.Text(layer, rgb, aci, center, len(18f, ppm), st.label))
        }

        arrow.endLabel?.let { label ->
            val labelX = endP.x + cos(angle).toFloat() * (arrow.strokeWidth * 3.5f)
            val labelY = endP.y + sin(angle).toFloat() * (arrow.strokeWidth * 3.5f) + 8f
            doc.addEntity(DxfEntity.Text(layer, rgb, aci, toDxf(PointF(labelX, labelY), ppm), len(TEXT_HEIGHT_PIXELS, ppm), label))
        }
    }

    /** Reuses UrbanArrowRenderer.renderArrowHead's exact point math, translated from Path
     * construction into plain DXF points - LARGE_TRIANGLE/CHEVRON_WIDE are actually a 4-point
     * concave chevron (tip, corner, an inward notch, other corner), not a plain triangle, so
     * they're emitted as a HATCH (arbitrary polygon) rather than a DXF SOLID (which only fills
     * correctly for a convex/diagonal-ordered quad). DOUBLE_HEAD's head *is* a plain 3-point
     * triangle in the renderer, so that one alone uses SOLID. */
    private fun exportArrowHead(
        doc: DxfDocument, layer: String, rgb: Int, aci: Int,
        point: PointF, angle: Double, type: ArrowHeadType, strokeWidth: Float, ppm: Float
    ) {
        val headLength = strokeWidth * 3.2f
        val headWidth = strokeWidth * 2.2f
        val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()
        val normX = -sinA; val normY = cosA
        when (type) {
            ArrowHeadType.ROUNDED_DOT -> {
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, circleBoundary(toDxf(point, ppm), len(headWidth, ppm)), DxfHatchPattern.SOLID))
            }
            ArrowHeadType.LARGE_TRIANGLE, ArrowHeadType.CHEVRON_WIDE -> {
                val notchFactor = if (type == ArrowHeadType.LARGE_TRIANGLE) 0.75f else 0.8f
                val p1 = point
                val p2 = PointF(point.x - cosA * headLength + normX * headWidth, point.y - sinA * headLength + normY * headWidth)
                val p3 = PointF(point.x - cosA * (headLength * notchFactor), point.y - sinA * (headLength * notchFactor))
                val p4 = PointF(point.x - cosA * headLength - normX * headWidth, point.y - sinA * headLength - normY * headWidth)
                val quad = listOf(p1, p2, p3, p4).map { toDxf(it, ppm) }
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, quad, DxfHatchPattern.SOLID))
            }
            ArrowHeadType.DOUBLE_HEAD -> {
                val p1 = point
                val p2 = PointF(point.x - cosA * headLength + normX * headWidth, point.y - sinA * headLength + normY * headWidth)
                val p3 = PointF(point.x - cosA * headLength - normX * headWidth, point.y - sinA * headLength - normY * headWidth)
                doc.addEntity(DxfEntity.Solid(layer, rgb, aci, toDxf(p1, ppm), toDxf(p2, ppm), toDxf(p3, ppm)))
            }
        }
    }

    // ---------------------------------------------------------------- HatchPolygon

    private fun exportHatchPolygon(doc: DxfDocument, hatch: UrbanElement.HatchPolygon, ppm: Float) {
        val verts = hatch.vertices
        if (verts.size < 3) return
        val layer = hatch.toolType.name
        val rgb = rgbOf(hatch.color)
        val aci = nearestAci(hatch.color)
        val dxfPts = verts.map { toDxf(it, ppm) }
        doc.addEntity(DxfEntity.LwPolyline(layer, rgb, aci, dxfPts, closed = true))

        val boundary = normalizeWinding(dxfPts)
        val spacing = len(hatch.hatchSpacing, ppm).coerceAtLeast(0.01)
        when (hatch.hatchStyle) {
            HatchStyle.SOLID_FILL ->
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, boundary, DxfHatchPattern.SOLID))
            HatchStyle.DIAGONAL_45 ->
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, boundary, DxfHatchPattern.DIAGONAL, patternAngleDeg = 45.0, patternScale = spacing))
            HatchStyle.CROSS_HATCH ->
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, boundary, DxfHatchPattern.CROSS, patternAngleDeg = 0.0, patternScale = spacing))
            HatchStyle.STIPPLE_DOTS ->
                // Phase 1: outline + solid wash rather than literal dot replication, to keep
                // entity count bounded - see the plan's Phase 2 note for literal stipple fidelity.
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, boundary, DxfHatchPattern.SOLID))
        }

        hatch.plazaNumber?.let { num ->
            val centroid = toDxf(centroidOf(verts), ppm)
            doc.addEntity(DxfEntity.Text(layer, rgb, aci, centroid, len(20f, ppm), num.toString()))
        }
    }

    // ---------------------------------------------------------------- PointMarker

    private fun exportPointMarker(doc: DxfDocument, marker: UrbanElement.PointMarker, ppm: Float) {
        val layer = marker.toolType.name
        val rgb = rgbOf(marker.color)
        val aci = nearestAci(marker.color)
        val pos = toDxf(marker.position, ppm)
        val r = len(marker.radius, ppm)

        when (marker.toolType) {
            UrbanToolType.STATION_BADGE -> {
                // Match the canvas symbol: a legible white badge, coloured outline and a
                // station identifier.  Keeping the label as TEXT makes it editable in CAD.
                doc.addEntity(
                    DxfEntity.Hatch(
                        layer,
                        rgbOf(Color.WHITE),
                        7,
                        circleBoundary(pos, r),
                        DxfHatchPattern.SOLID
                    )
                )
                doc.addEntity(DxfEntity.Circle(layer, rgb, aci, pos, r))
                doc.addEntity(
                    DxfEntity.Text(
                        layer,
                        rgb,
                        aci,
                        pos,
                        len(TEXT_HEIGHT_PIXELS, ppm),
                        marker.label ?: "A"
                    )
                )
            }
            UrbanToolType.INFRA_LIGHT -> {
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, circleBoundary(pos, r * 0.45), DxfHatchPattern.SOLID))
                radiatingLines(doc, layer, rgb, aci, pos, 8, 45.0, r * 0.65, r * 1.25)
            }
            UrbanToolType.INFRA_MANHOLE -> {
                doc.addEntity(DxfEntity.Circle(layer, rgb, aci, pos, r))
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, circleBoundary(pos, r * 0.35), DxfHatchPattern.SOLID))
            }
            UrbanToolType.INFRA_WATER -> {
                doc.addEntity(DxfEntity.Circle(layer, rgb, aci, pos, r))
                doc.addEntity(DxfEntity.Circle(layer, rgb, aci, pos, r * 0.55))
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, circleBoundary(pos, r * 0.25), DxfHatchPattern.SOLID))
            }
            UrbanToolType.INFRA_ELECTRIC -> {
                val rect = squareBoundary(pos, r)
                doc.addEntity(DxfEntity.LwPolyline(layer, rgb, aci, rect, closed = true))
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, rect, DxfHatchPattern.SOLID))
            }
            UrbanToolType.POLLUTION_TRASH ->
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, squareBoundary(pos, r * 0.9), DxfHatchPattern.SOLID))
            UrbanToolType.POLLUTION_SHED ->
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, rectBoundary(pos, r * 1.3, r * 0.8), DxfHatchPattern.SOLID))
            UrbanToolType.POLLUTION_POLE -> {
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, circleBoundary(pos, r * 0.4), DxfHatchPattern.SOLID))
                radiatingLines(doc, layer, rgb, aci, pos, 6, 60.0, r * 0.5, r * 1.3)
            }
            else ->
                doc.addEntity(DxfEntity.Hatch(layer, rgb, aci, circleBoundary(pos, r), DxfHatchPattern.SOLID))
        }

        marker.label?.takeUnless { marker.toolType == UrbanToolType.STATION_BADGE }?.let {
            doc.addEntity(DxfEntity.Text(layer, rgb, aci, DxfPoint(pos.x, pos.y - r * 1.6 - len(8f, ppm)), len(TEXT_HEIGHT_PIXELS, ppm), it))
        }
    }

    private fun radiatingLines(doc: DxfDocument, layer: String, rgb: Int, aci: Int, center: DxfPoint, count: Int, stepDeg: Double, rInner: Double, rOuter: Double) {
        for (i in 0 until count) {
            val a = Math.toRadians(i * stepDeg)
            val x1 = center.x + cos(a) * rInner; val y1 = center.y + sin(a) * rInner
            val x2 = center.x + cos(a) * rOuter; val y2 = center.y + sin(a) * rOuter
            doc.addEntity(DxfEntity.Line(layer, rgb, aci, DxfPoint(x1, y1), DxfPoint(x2, y2)))
        }
    }

    private fun squareBoundary(center: DxfPoint, half: Double) = rectBoundary(center, half, half)
    private fun rectBoundary(center: DxfPoint, halfW: Double, halfH: Double) = listOf(
        DxfPoint(center.x - halfW, center.y - halfH), DxfPoint(center.x + halfW, center.y - halfH),
        DxfPoint(center.x + halfW, center.y + halfH), DxfPoint(center.x - halfW, center.y + halfH)
    )

    // ---------------------------------------------------------------- BoundaryPath

    private fun exportBoundary(doc: DxfDocument, boundary: UrbanElement.BoundaryPath, ppm: Float) {
        val verts = boundary.vertices
        if (verts.size < 2) return
        val layer = boundary.toolType.name
        val rgb = rgbOf(boundary.color)
        val aci = nearestAci(boundary.color)
        val dxfPts = verts.map { toDxf(it, ppm) }
        // Match the actual on-screen semantics: only the site boundary is a closed dashed loop.
        // Contours and overhead wires remain open, continuous paths in editable model space.
        val isSiteBoundary = boundary.toolType == UrbanToolType.SITE_BOUNDARY
        doc.addEntity(
            DxfEntity.LwPolyline(
                layer = layer,
                rgb = rgb,
                aci = aci,
                points = dxfPts,
                closed = isSiteBoundary,
                constantWidth = len(boundary.strokeWidth, ppm),
                linetype = if (isSiteBoundary) "URBAN_DASH" else "CONTINUOUS"
            )
        )

        if (boundary.toolType == UrbanToolType.POLLUTION_WIRES) {
            val nodeRadius = len(NODE_RADIUS_PIXELS * 0.42f, ppm)
            for (point in dxfPts) {
                doc.addEntity(
                    DxfEntity.Hatch(
                        layer,
                        rgbOf(Color.WHITE),
                        7,
                        circleBoundary(point, nodeRadius),
                        DxfHatchPattern.SOLID
                    )
                )
                doc.addEntity(DxfEntity.Circle(layer, rgb, aci, point, nodeRadius))
            }
        }

        if (boundary.showNodeNumbers) {
            val nodePositions = if (boundary.nodeSpacingPx > 1f)
                UrbanSymbolRenderer.resampleForDisplay(verts, boundary.nodeSpacingPx) else verts
            val nodeRadius = len(NODE_RADIUS_PIXELS, ppm)
            for ((i, pt) in nodePositions.withIndex()) {
                val center = toDxf(pt, ppm)
                doc.addEntity(DxfEntity.Hatch(layer, rgbOf(Color.WHITE), 7, circleBoundary(center, nodeRadius), DxfHatchPattern.SOLID))
                doc.addEntity(DxfEntity.Circle(layer, rgb, aci, center, nodeRadius))
                doc.addEntity(DxfEntity.Text(layer, rgb, aci, center, len(14f, ppm), (i + 1).toString()))
            }
        }
    }

    // ---------------------------------------------------------------- Legend (real annotation, not raster)

    private fun exportLegend(doc: DxfDocument, legendItems: List<LegendItem>, ppm: Float) {
        val visible = legendItems.filter { it.isVisibleInLegend }
        if (visible.isEmpty()) return
        val swatchSize = len(20f, ppm)
        val rowHeight = len(30f, ppm)
        var y = 0.0
        for (item in visible) {
            val swatchCenter = DxfPoint(-swatchSize * 2, y)
            doc.addEntity(
                DxfEntity.Hatch(
                    "ANNOTATION", rgbOf(item.color), nearestAci(item.color),
                    listOf(
                        DxfPoint(swatchCenter.x - swatchSize / 2, swatchCenter.y - swatchSize / 2),
                        DxfPoint(swatchCenter.x + swatchSize / 2, swatchCenter.y - swatchSize / 2),
                        DxfPoint(swatchCenter.x + swatchSize / 2, swatchCenter.y + swatchSize / 2),
                        DxfPoint(swatchCenter.x - swatchSize / 2, swatchCenter.y + swatchSize / 2)
                    ),
                    DxfHatchPattern.SOLID
                )
            )
            doc.addEntity(
                DxfEntity.Text(
                    "ANNOTATION", 0x000000, 7,
                    DxfPoint(swatchCenter.x + swatchSize, y - swatchSize / 3), swatchSize * 0.7,
                    item.nameAr
                )
            )
            y -= rowHeight
        }
    }
}
