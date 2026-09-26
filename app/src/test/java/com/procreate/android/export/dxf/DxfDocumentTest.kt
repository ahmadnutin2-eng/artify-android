package com.procreate.android.export.dxf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Structural sanity tests for the hand-rolled DXF writer. There is no real AutoCAD available in
 * this environment to generate a true golden-file fixture from, so these check the specific
 * group-code ordering rules that are documented gotchas (see DxfDocument's own comments) rather
 * than diffing a full serialized file - an accidental reordering of, say, LWPOLYLINE's vertex
 * count (90) relative to its vertices would fail these tests loudly.
 */
class DxfDocumentTest {

    private data class Pair(val code: Int, val value: String)

    private fun parse(text: String): List<Pair> {
        val lines = text.removeSuffix("\n").split('\n')
        assertEquals("A DXF group code must always have a matching value", 0, lines.size % 2)
        return lines.chunked(2).map { (code, value) -> Pair(code.trim().toInt(), value) }
    }

    private fun records(text: String, type: String): List<List<Pair>> {
        val pairs = parse(text)
        val starts = pairs.indices.filter { pairs[it] == Pair(0, type) }
        return starts.map { start ->
            val end = ((start + 1) until pairs.size).firstOrNull { pairs[it].code == 0 } ?: pairs.size
            pairs.subList(start, end)
        }
    }

    private fun record(text: String, type: String): List<Pair> =
        records(text, type).single()

    private fun List<Pair>.singleValue(code: Int): String =
        filter { it.code == code }.map { it.value }.single()

    private fun List<Pair>.values(code: Int): List<String> =
        filter { it.code == code }.map { it.value }

    private fun List<Pair>.recordHandle(): String =
        filter { it.code == 5 || it.code == 105 }.map { it.value }.single()

    @Test
    fun `serializes required sections in order and terminates with EOF`() {
        val doc = DxfDocument()
        doc.addLayer("0", 7)
        doc.addEntity(DxfEntity.Line("0", 0xFF0000, 1, DxfPoint(0.0, 0.0), DxfPoint(1.0, 1.0)))
        val text = doc.serialize()

        val header = text.indexOf("HEADER")
        val classes = text.indexOf("CLASSES")
        val tables = text.indexOf("TABLES")
        val blocks = text.indexOf("BLOCKS")
        val entities = text.indexOf("ENTITIES")
        val eof = text.indexOf("EOF")

        assertTrue("HEADER must come before CLASSES", header in 0 until classes)
        assertTrue("CLASSES must come before TABLES", classes in 0 until tables)
        assertTrue("TABLES must come before BLOCKS", tables in 0 until blocks)
        assertTrue("BLOCKS must come before ENTITIES", blocks in 0 until entities)
        assertTrue("ENTITIES must come before EOF", entities in 0 until eof)
        assertTrue(text.trim().endsWith("EOF"))
        assertTrue("DIMSTYLE handles must use group 105", record(text, "DIMSTYLE").any { it.code == 105 })
    }

    @Test
    fun `LWPOLYLINE writes vertex count (90) before any vertex coordinate`() {
        val doc = DxfDocument()
        doc.addEntity(
            DxfEntity.LwPolyline(
                "0", 0x00FF00, 3,
                listOf(DxfPoint(0.0, 0.0), DxfPoint(1.0, 0.0), DxfPoint(1.0, 1.0)),
                closed = true
            )
        )
        val lines = doc.serialize().lines()
        val entitiesStart = lines.indexOf("ENTITIES")
        val ninetyIndex = lines.withIndex().first { it.index >= entitiesStart && it.value == "90" }.index
        val firstTenIndex = lines.withIndex().first { it.index >= entitiesStart && it.value == "10" }.index
        assertTrue("group 90 (vertex count) must appear before the first vertex's group 10", ninetyIndex in 0 until firstTenIndex)
        // The vertex count value itself must match the actual number of points supplied.
        assertEquals("3", lines[ninetyIndex + 1])
    }

    @Test
    fun `group 420 writes the entity's rgb field verbatim - masking is the caller's job`() {
        // DxfDocument itself does no ARGB-to-RGB masking - that's UrbanDxfExporter.rgbOf's
        // contract (it can't be unit-tested here without Robolectric, since it calls
        // android.graphics.Color). This test just pins that DxfDocument never silently transforms
        // whatever rgb value it's handed, so a caller that forgets to mask fails loudly in its own
        // output rather than being masked "by accident" somewhere downstream.
        val doc = DxfDocument()
        val alreadyMasked = 0x00FF00FF
        doc.addEntity(DxfEntity.Circle("0", alreadyMasked, 6, DxfPoint(0.0, 0.0), 1.0))
        val lines = doc.serialize().lines()
        val idx420 = lines.indexOf("420")
        assertEquals(alreadyMasked.toString(), lines[idx420 + 1])
    }

    @Test
    fun `an empty document still serializes without throwing and without Infinity bounds`() {
        val doc = DxfDocument()
        val text = doc.serialize()
        assertTrue(!text.contains("Infinity"))
        assertTrue(text.contains("EXTMIN"))
    }

    @Test
    fun `raster is emitted on the first serialization with a complete handle graph`() {
        val doc = DxfDocument()
        doc.setImage(
            DxfEntity.Image(
                layer = "RASTER",
                rgb = 0xFFFFFF,
                aci = 7,
                insertion = DxfPoint(12.0, -8.0),
                widthMeters = 50.0,
                heightMeters = 25.0,
                relativeImagePath = "brush-canvas.png",
                pixelWidth = 1000,
                pixelHeight = 500
            )
        )

        val text = doc.serialize()
        val image = record(text, "IMAGE")
        val imageDef = record(text, "IMAGEDEF")
        val reactor = record(text, "IMAGEDEF_REACTOR")
        val dictionaries = records(text, "DICTIONARY")

        assertEquals(
            setOf("IMAGE", "IMAGEDEF", "IMAGEDEF_REACTOR"),
            records(text, "CLASS").map { it.singleValue(1) }.toSet()
        )

        assertEquals("IMAGE must reference the emitted IMAGEDEF", imageDef.singleValue(5), image.singleValue(340))
        assertEquals("IMAGE must reference a real reactor", reactor.singleValue(5), image.singleValue(360))
        assertTrue(
            "IMAGEDEF must list its reactor in the persistent-reactor group",
            reactor.singleValue(5) in imageDef.values(330)
        )
        assertEquals("The reactor must point back to IMAGE", image.singleValue(5), reactor.values(330).last())

        val root = dictionaries.single { "ACAD_IMAGE_DICT" in it.values(3) }
        val imageDictionary = dictionaries.single { "IMAGE_1" in it.values(3) }
        assertEquals(imageDictionary.singleValue(5), root.singleValue(350))
        assertEquals(imageDef.singleValue(5), imageDictionary.singleValue(350))
        assertEquals(imageDictionary.singleValue(5), imageDef.values(330).last())

        // Every non-zero reference emitted by this file must resolve to exactly one object/entity
        // handle. This catches dangling IMAGEDEF handles as well as accidental handle reuse.
        val pairs = parse(text)
        val handleBearingRecordTypes = setOf(
            "VPORT", "LTYPE", "LAYER", "STYLE", "APPID", "DIMSTYLE", "BLOCK_RECORD",
            "BLOCK", "ENDBLK", "LINE", "LWPOLYLINE", "CIRCLE", "SOLID", "TEXT", "HATCH",
            "IMAGE", "DICTIONARY", "IMAGEDEF", "IMAGEDEF_REACTOR"
        )
        val allRecords = handleBearingRecordTypes.flatMap { records(text, it) }
        val definedHandles = allRecords.map { it.recordHandle() }
        assertEquals("Every handle must be unique", definedHandles.size, definedHandles.toSet().size)

        val references = pairs.filter { it.code in setOf(330, 340, 350, 360) }
            .map { it.value }
            .filter { it != "0" }
        assertTrue(
            "All handle references must resolve: ${references.filterNot { it in definedHandles }}",
            references.all { it in definedHandles }
        )

        val handSeedIndex = pairs.indexOf(Pair(9, "\$HANDSEED"))
        val handSeed = pairs[handSeedIndex + 1].value.toInt(16)
        val largestHandle = definedHandles.maxOf { it.toInt(16) }
        assertTrue("HANDSEED must be greater than every assigned handle", handSeed > largestHandle)
    }

    @Test
    fun `serialization is repeatable and does not carry raster handles between calls`() {
        val doc = DxfDocument()
        doc.addEntity(DxfEntity.Line("ROAD", 0x444444, 8, DxfPoint(0.0, 0.0), DxfPoint(100.0, 0.0)))
        doc.setImage(
            DxfEntity.Image(
                "RASTER", 0xFFFFFF, 7, DxfPoint(0.0, -50.0),
                widthMeters = 100.0, heightMeters = 50.0,
                relativeImagePath = "site.png", pixelWidth = 1000, pixelHeight = 500
            )
        )

        val first = doc.serialize()
        val second = doc.serialize()

        assertEquals("serialize() must be deterministic for an unchanged document", first, second)
        assertEquals(1, records(first, "IMAGE").size)
        assertEquals(1, records(second, "IMAGE").size)
    }

    @Test
    fun `raster underlay is emitted before editable vectors`() {
        val doc = DxfDocument()
        doc.addEntity(
            DxfEntity.Line(
                "ROAD",
                0x444444,
                8,
                DxfPoint(0.0, 0.0),
                DxfPoint(10.0, 0.0)
            )
        )
        doc.setImage(
            DxfEntity.Image(
                "RASTER_REFERENCE",
                0xFFFFFF,
                7,
                DxfPoint(0.0, 0.0),
                10.0,
                10.0,
                "reference.png",
                100,
                100
            )
        )

        val text = doc.serialize()
        assertTrue(text.indexOf("\nIMAGE\n") < text.indexOf("\nLINE\n"))
    }

    @Test
    fun `IMAGE U and V vectors describe one pixel and recover the requested world size`() {
        val doc = DxfDocument()
        doc.setImage(
            DxfEntity.Image(
                "RASTER", 0xFFFFFF, 7, DxfPoint(3.0, 4.0),
                widthMeters = 60.0, heightMeters = 20.0,
                relativeImagePath = "scaled.png", pixelWidth = 1200, pixelHeight = 500
            )
        )

        val text = doc.serialize()
        val image = record(text, "IMAGE")
        val imageDef = record(text, "IMAGEDEF")

        val uPixelLength = hypot(image.singleValue(11).toDouble(), image.singleValue(21).toDouble())
        val vPixelLength = hypot(image.singleValue(12).toDouble(), image.singleValue(22).toDouble())
        val recoveredWidth = uPixelLength * image.singleValue(13).toDouble()
        val recoveredHeight = vPixelLength * image.singleValue(23).toDouble()

        assertEquals(0.05, uPixelLength, 1e-9)
        assertEquals(0.04, vPixelLength, 1e-9)
        assertEquals(60.0, recoveredWidth, 1e-6)
        assertEquals(20.0, recoveredHeight, 1e-6)
        assertEquals(uPixelLength, imageDef.singleValue(11).toDouble(), 1e-9)
        assertEquals(vPixelLength, imageDef.singleValue(21).toDouble(), 1e-9)
        assertEquals("11", image.singleValue(70)) // visible + transparency, no clipping bit
        assertEquals(0, image.singleValue(70).toInt() and 4)
        assertEquals("0", image.singleValue(280))
        assertNotEquals("0", image.singleValue(360))
    }

    @Test
    fun `representative urban drawing preserves a known 100 meter scale and editable entity kinds`() {
        val pixelsPerMeter = 4f
        fun point(x: Float, y: Float) = pixelToDxf(
            x, y, pixelsPerMeter,
            originOffsetXPixels = 80f,
            originOffsetYPixels = 40f
        )

        val siteBoundary = listOf(
            point(80f, 40f), point(480f, 40f), point(480f, 240f), point(80f, 240f)
        )
        val doc = DxfDocument()
        doc.addEntity(DxfEntity.Line("ROAD", 0x444444, 8, point(80f, 40f), point(480f, 40f)))
        doc.addEntity(DxfEntity.LwPolyline("SITE_BOUNDARY", 0xFF0000, 1, siteBoundary, closed = true))
        doc.addEntity(DxfEntity.Hatch("PLAZA", 0xFFAA00, 2, siteBoundary, DxfHatchPattern.DIAGONAL, 45.0, 2.0))
        doc.addEntity(DxfEntity.Circle("TREE", 0x008800, 3, point(280f, 140f), pixelLengthToDxf(20f, pixelsPerMeter)))
        doc.addEntity(DxfEntity.Solid("ENTRANCE", 0x0000FF, 5, point(480f, 40f), point(460f, 32f), point(460f, 48f)))
        doc.addEntity(DxfEntity.Text("ANNOTATION", 0x000000, 7, point(280f, 140f), 2.5, "100 m"))
        doc.setImage(
            DxfEntity.Image(
                "RASTER", 0xFFFFFF, 7, point(80f, 440f),
                widthMeters = pixelLengthToDxf(800f, pixelsPerMeter),
                heightMeters = pixelLengthToDxf(400f, pixelsPerMeter),
                relativeImagePath = "urban-base.png", pixelWidth = 800, pixelHeight = 400
            )
        )

        val text = doc.serialize()
        val line = record(text, "LINE")
        val lineLength = hypot(
            line.singleValue(11).toDouble() - line.singleValue(10).toDouble(),
            line.singleValue(21).toDouble() - line.singleValue(20).toDouble()
        )
        assertEquals("The 400 px reference line at 4 px/m must be exactly 100 m", 100.0, lineLength, 1e-6)
        assertEquals(5.0, record(text, "CIRCLE").singleValue(40).toDouble(), 1e-6)

        val boundary = record(text, "LWPOLYLINE")
        assertEquals("4", boundary.singleValue(90))
        assertEquals("1", boundary.singleValue(70))
        val xs = boundary.values(10).map(String::toDouble)
        val ys = boundary.values(20).map(String::toDouble)
        assertEquals(100.0, xs.maxOrNull()!! - xs.minOrNull()!!, 1e-6)
        assertEquals(50.0, ys.maxOrNull()!! - ys.minOrNull()!!, 1e-6)

        assertEquals(1, records(text, "HATCH").size)
        assertEquals(1, records(text, "SOLID").size)
        assertEquals(1, records(text, "TEXT").size)
        assertEquals(1, records(text, "IMAGE").size)

        val pairs = parse(text)
        val unitsIndex = pairs.indexOf(Pair(9, "\$INSUNITS"))
        assertEquals("70", pairs[unitsIndex + 1].code.toString())
        assertEquals("6", pairs[unitsIndex + 1].value) // meters, model space 1:1
    }
}
