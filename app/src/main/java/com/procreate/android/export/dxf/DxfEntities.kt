package com.procreate.android.export.dxf

/** Plain (x, y) in DXF world-space (meters, Y already flipped to CAD's Y-up convention) - never
 * android.graphics.PointF, so this whole module stays free of Android imports and is plain-JVM
 * unit-testable. */
data class DxfPoint(val x: Double, val y: Double)

enum class DxfHatchPattern { SOLID, DIAGONAL, CROSS }

/** Every entity carries its own layer + colour (true RGB, 0x00RRGGBB - no alpha channel - plus an
 * ACI fallback index for readers that ignore true colour) rather than inheriting a shared "current
 * colour" state, since every UrbanElement already stores its own explicit colour. */
sealed class DxfEntity {
    abstract val layer: String
    abstract val rgb: Int
    abstract val aci: Int

    data class Line(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val a: DxfPoint, val b: DxfPoint
    ) : DxfEntity()

    data class LwPolyline(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val points: List<DxfPoint>, val closed: Boolean,
        val constantWidth: Double = 0.0,
        val linetype: String = "CONTINUOUS", val linetypeScale: Double = 1.0
    ) : DxfEntity()

    data class Circle(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val center: DxfPoint, val radius: Double
    ) : DxfEntity()

    /** A 3-point filled triangle (DXF SOLID always has 4 corners - the 3rd is repeated as the
     * 4th, which is the standard way to represent a triangle with this entity type). */
    data class Solid(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val p1: DxfPoint, val p2: DxfPoint, val p3: DxfPoint
    ) : DxfEntity()

    data class Text(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val position: DxfPoint, val height: Double, val text: String, val rotationDeg: Double = 0.0
    ) : DxfEntity()

    /** A filled region - solid or one of the two predefined AutoCAD hatch patterns referenced by
     * name (ANSI31/ANSI37), never a hand-authored custom pattern definition (see DxfDocument for
     * why: referencing a built-in pattern is far less fragile than re-deriving its dash geometry
     * by hand). The boundary is always emitted via the "polyline path" shortcut since every
     * UrbanHatchPolygon here is a straight-sided loop - never the generic multi-edge-type
     * boundary format. */
    data class Hatch(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val boundary: List<DxfPoint>, val pattern: DxfHatchPattern,
        val patternAngleDeg: Double = 0.0, val patternScale: Double = 1.0
    ) : DxfEntity()

    /** References a sidecar raster file by relative filename - a DXF IMAGE entity can never embed
     * pixel data inline in the ASCII text, only point at an external file (see IMAGEDEF in
     * DxfDocument). */
    data class Image(
        override val layer: String, override val rgb: Int, override val aci: Int,
        val insertion: DxfPoint, val widthMeters: Double, val heightMeters: Double,
        val relativeImagePath: String, val pixelWidth: Int, val pixelHeight: Int
    ) : DxfEntity()
}

/** One CAD layer: a natural DXF LAYER per UrbanToolType, so each tool's elements can be toggled/
 * selected/coloured independently in AutoCAD exactly like the app's own tool categories. */
data class DxfLayerDef(val name: String, val aci: Int)
