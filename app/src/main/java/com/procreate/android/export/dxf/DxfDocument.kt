package com.procreate.android.export.dxf

/**
 * Two-pass DXF builder targeting AC1021 (AutoCAD 2007) - the version floor for true-colour
 * entities (group 420, introduced AC1018/2004), and universally readable by every AutoCAD release
 * since plus every third-party CAD tool (LibreCAD, QCAD, BricsCAD).
 *
 * Pass 1 (while the caller is calling [addLayer]/[addEntity]/[setImage]): accumulate entities and
 * register every layer actually used. Pass 2 ([serialize]): derive current bounds, then emit HEADER
 * (using those bounds for $EXTMIN/$EXTMAX so "Zoom Extents" works on open) -> CLASSES -> TABLES ->
 * BLOCKS -> ENTITIES -> OBJECTS (only if a raster IMAGE was included) -> EOF.
 *
 * The original Phase-1 pass here skipped the BLOCK_RECORD table and every entity's group-330
 * owner reference, on the theory that AutoCAD's own reader is lenient enough to auto-assign both
 * on open. That held for AutoCAD itself, but broke on the very first non-AutoCAD test: Rhino's
 * ODA-based importer refused the file outright with "Opendesign error: Missing Symbol Table" -
 * the ODA library (used by many third-party CAD tools, not just Rhino) validates that the full
 * standard table set (VPORT/LTYPE/LAYER/STYLE/VIEW/UCS/APPID/DIMSTYLE/BLOCK_RECORD) is present
 * before it will even attempt to load, unlike AutoCAD's more forgiving recovery pass. Every one
 * of those tables is written now (some, like VIEW/UCS/DIMSTYLE, as valid-but-empty placeholders
 * since nothing in this export actually needs entries in them), BLOCK_RECORD carries real
 * "Model_Space" and "Paper_Space" entries wired to the matching BLOCK definitions, and every
 * entity carries a genuine group-330 owner pointing at the model-space BLOCK_RECORD's handle
 * instead of omitting it.
 */
class DxfDocument {

    private val entities = mutableListOf<DxfEntity>()
    private val layers = linkedMapOf<String, DxfLayerDef>()
    private var image: DxfEntity.Image? = null

    private var usesDashOrDot = false

    /**
     * Handles are deliberately planned afresh for every [serialize] call. Keeping a mutable
     * counter (and the IMAGEDEF handle) on the document made serialization stateful: the first
     * call omitted IMAGE because IMAGEDEF had not been allocated yet, while later calls produced
     * different handles and could point IMAGE at an IMAGEDEF from the previous call.
     */
    private data class CoreHandles(
        val modelSpaceBlockRecord: String,
        val paperSpaceBlockRecord: String,
        val modelSpaceBlock: String,
        val modelSpaceEndBlk: String,
        val paperSpaceBlock: String,
        val paperSpaceEndBlk: String
    )

    /** All five raster handles are reserved before any section is emitted so forward references
     * are stable and IMAGE/IMAGEDEF/IMAGEDEF_REACTOR can be linked in both directions. */
    private data class RasterHandles(
        val imageEntity: String,
        val rootDictionary: String,
        val imageDictionary: String,
        val imageDefinition: String,
        val imageDefinitionReactor: String
    )

    private data class SerializationContext(
        val counter: DxfHandleCounter,
        val core: CoreHandles,
        val raster: RasterHandles?
    )

    private data class DrawingBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    )

    fun addLayer(name: String, aci: Int) {
        layers.putIfAbsent(name, DxfLayerDef(name, aci))
    }

    fun addEntity(entity: DxfEntity) {
        layers.putIfAbsent(entity.layer, DxfLayerDef(entity.layer, entity.aci))
        if (entity is DxfEntity.LwPolyline && entity.linetype != "CONTINUOUS") usesDashOrDot = true
        entities.add(entity)
    }

    /** At most one raster sidecar per document (the whole flattened brush/paint canvas) - see
     * UrbanDxfExporter for why this can only ever reference an external file, never embed pixels
     * inline. */
    fun setImage(image: DxfEntity.Image) {
        require(image.pixelWidth > 0 && image.pixelHeight > 0) {
            "Raster pixel dimensions must be positive"
        }
        require(image.widthMeters.isFinite() && image.widthMeters > 0.0 &&
            image.heightMeters.isFinite() && image.heightMeters > 0.0) {
            "Raster world dimensions must be finite and positive"
        }
        require(image.relativeImagePath.isNotBlank() &&
            '\n' !in image.relativeImagePath && '\r' !in image.relativeImagePath) {
            "Raster path must be a non-empty single-line relative path"
        }
        layers.putIfAbsent(image.layer, DxfLayerDef(image.layer, image.aci))
        this.image = image
    }

    private fun pointsOf(e: DxfEntity): List<DxfPoint> = when (e) {
        is DxfEntity.Line -> listOf(e.a, e.b)
        is DxfEntity.LwPolyline -> e.points
        is DxfEntity.Circle -> listOf(
            DxfPoint(e.center.x - e.radius, e.center.y - e.radius),
            DxfPoint(e.center.x + e.radius, e.center.y + e.radius)
        )
        is DxfEntity.Solid -> listOf(e.p1, e.p2, e.p3)
        is DxfEntity.Text -> listOf(e.position)
        is DxfEntity.Hatch -> e.boundary
        is DxfEntity.Image -> listOf(e.insertion, DxfPoint(e.insertion.x + e.widthMeters, e.insertion.y + e.heightMeters))
    }

    fun serialize(): String {
        val context = createSerializationContext()
        val bounds = calculateBounds()

        // TABLES/BLOCKS/ENTITIES/OBJECTS are what actually consume most handles (every table
        // entry, every entity) - they're built into their own buffer FIRST so that by the time
        // $HANDSEED is computed for HEADER (which has to appear first in the finished file), it
        // reflects every handle really used. Computing it before these sections run would
        // under-count the seed, which can make a later editor reassign a NEW handle that
        // collides with one already used here.
        val body = DxfWriter()
        writeClasses(body)
        writeTables(body, context)
        writeBlocks(body, context)
        writeEntitiesSection(body, context)
        image?.let { writeObjectsSection(body, it, requireNotNull(context.raster)) }
        body.pair(0, "EOF")

        val header = DxfWriter()
        writeHeader(header, bounds, context.counter.seed())

        return header.result() + body.result()
    }

    // ---------------------------------------------------------------- CLASSES

    /** IMAGE, IMAGEDEF and IMAGEDEF_REACTOR are registered ObjectDBX/ISM classes rather than the
     * primitive entities understood by every R12 reader. AC1021 files containing them need class
     * declarations so a strict reader can bind their ENTITIES/OBJECTS records without recovery. */
    private fun writeClasses(w: DxfWriter) {
        w.pair(0, "SECTION").pair(2, "CLASSES")
        if (image != null) {
            writeClass(
                w, "IMAGE", "AcDbRasterImage",
                proxyCapabilities = 127, instanceCount = 1, isEntity = true
            )
            writeClass(
                w, "IMAGEDEF", "AcDbRasterImageDef",
                proxyCapabilities = 0, instanceCount = 1, isEntity = false
            )
            writeClass(
                w, "IMAGEDEF_REACTOR", "AcDbRasterImageDefReactor",
                proxyCapabilities = 1, instanceCount = 1, isEntity = false
            )
        }
        w.pair(0, "ENDSEC")
    }

    private fun writeClass(
        w: DxfWriter,
        recordName: String,
        cppClassName: String,
        proxyCapabilities: Int,
        instanceCount: Int,
        isEntity: Boolean
    ) {
        w.pair(0, "CLASS")
        w.pair(1, recordName).pair(2, cppClassName).pair(3, "ISM")
        w.pair(90, proxyCapabilities).pair(91, instanceCount)
        w.pair(280, 0).pair(281, if (isEntity) 1 else 0)
    }

    private fun createSerializationContext(): SerializationContext {
        val counter = DxfHandleCounter()
        // Allocated first so every graphical entity can point at a real model-space BLOCK_RECORD.
        val core = CoreHandles(
            modelSpaceBlockRecord = counter.nextHandle(),
            paperSpaceBlockRecord = counter.nextHandle(),
            modelSpaceBlock = counter.nextHandle(),
            modelSpaceEndBlk = counter.nextHandle(),
            paperSpaceBlock = counter.nextHandle(),
            paperSpaceEndBlk = counter.nextHandle()
        )
        val raster = image?.let {
            RasterHandles(
                imageEntity = counter.nextHandle(),
                rootDictionary = counter.nextHandle(),
                imageDictionary = counter.nextHandle(),
                imageDefinition = counter.nextHandle(),
                imageDefinitionReactor = counter.nextHandle()
            )
        }
        return SerializationContext(counter, core, raster)
    }

    /** Bounds are derived from the current model on each serialization. Besides avoiding
     * serialization side effects, this also means replacing the single raster cannot leave the
     * previous image's extents behind. */
    private fun calculateBounds(): DrawingBounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        fun include(point: DxfPoint) {
            if (point.x < minX) minX = point.x
            if (point.y < minY) minY = point.y
            if (point.x > maxX) maxX = point.x
            if (point.y > maxY) maxY = point.y
        }

        for (entity in entities) pointsOf(entity).forEach(::include)
        image?.let { pointsOf(it).forEach(::include) }

        // An empty drawing has no meaningful bounds; never emit Infinity into the header.
        return if (minX.isInfinite()) DrawingBounds(0.0, 0.0, 1.0, 1.0)
        else DrawingBounds(minX, minY, maxX, maxY)
    }

    // ---------------------------------------------------------------- HEADER

    private fun writeHeader(w: DxfWriter, bounds: DrawingBounds, handSeed: String) {
        w.pair(0, "SECTION").pair(2, "HEADER")
        w.pair(9, "\$ACADVER").pair(1, "AC1021")
        w.pair(9, "\$INSUNITS").pair(70, 6) // 6 = meters
        w.pair(9, "\$MEASUREMENT").pair(70, 1) // 1 = metric
        w.pair(9, "\$EXTMIN").pair(10, bounds.minX).pair(20, bounds.minY).pair(30, 0.0)
        w.pair(9, "\$EXTMAX").pair(10, bounds.maxX).pair(20, bounds.maxY).pair(30, 0.0)
        w.pair(9, "\$LIMMIN").pair(10, bounds.minX).pair(20, bounds.minY)
        w.pair(9, "\$LIMMAX").pair(10, bounds.maxX).pair(20, bounds.maxY)
        w.pair(9, "\$LTSCALE").pair(40, 1.0)
        w.pair(9, "\$CLAYER").pair(8, "0")
        w.pair(9, "\$TEXTSTYLE").pair(7, "ARABIC")
        w.pair(9, "\$HANDSEED").pair(5, handSeed)
        w.pair(0, "ENDSEC")
    }

    // ---------------------------------------------------------------- TABLES

    private fun writeTables(w: DxfWriter, context: SerializationContext) {
        w.pair(0, "SECTION").pair(2, "TABLES")
        writeVportTable(w, context.counter)
        writeLtypeTable(w, context.counter)
        writeLayerTable(w, context.counter)
        writeStyleTable(w, context.counter)
        writeEmptyTable(w, "VIEW")
        writeEmptyTable(w, "UCS")
        writeAppidTable(w, context.counter)
        writeDimstyleTable(w, context.counter)
        writeBlockRecordTable(w, context.core)
        w.pair(0, "ENDSEC")
    }

    /** A syntactically valid but zero-entry table - some tables (VIEW, UCS) need to exist for a
     * strict reader to consider the TABLES section complete, but nothing here actually needs a
     * named view/UCS, so there's nothing to put inside one. */
    private fun writeEmptyTable(w: DxfWriter, name: String) {
        w.pair(0, "TABLE").pair(2, name).pair(70, 0)
        w.pair(0, "ENDTAB")
    }

    private fun writeVportTable(w: DxfWriter, handles: DxfHandleCounter) {
        w.pair(0, "TABLE").pair(2, "VPORT").pair(70, 1)
        w.pair(0, "VPORT").pair(5, handles.nextHandle())
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbViewportTableRecord")
        w.pair(2, "*Active").pair(70, 0)
        w.pair(10, 0.0).pair(20, 0.0).pair(11, 1.0).pair(21, 1.0)
        w.pair(12, 0.0).pair(22, 0.0).pair(13, 0.0).pair(23, 0.0)
        w.pair(14, 0.5).pair(24, 0.5).pair(15, 0.5).pair(25, 0.5)
        w.pair(16, 0.0).pair(26, 0.0).pair(36, 1.0)
        w.pair(17, 0.0).pair(27, 0.0).pair(37, 0.0)
        w.pair(40, 1.0).pair(41, 1.0).pair(42, 50.0).pair(43, 0.0).pair(44, 0.0)
        w.pair(50, 0.0).pair(51, 0.0)
        w.pair(71, 0).pair(72, 1000).pair(73, 1).pair(74, 3)
        w.pair(75, 0).pair(76, 0).pair(77, 0).pair(78, 0)
        w.pair(0, "ENDTAB")
    }

    private fun writeDimstyleTable(w: DxfWriter, handles: DxfHandleCounter) {
        // A single "Standard" entry - nothing in this export uses dimension entities, but a
        // strict reader still expects the table to be present with at least the default style.
        w.pair(0, "TABLE").pair(2, "DIMSTYLE").pair(70, 1)
        // DIMSTYLE is the sole symbol-table record whose handle uses group 105, not group 5.
        w.pair(0, "DIMSTYLE").pair(105, handles.nextHandle())
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbDimStyleTableRecord")
        w.pair(2, "Standard").pair(70, 0)
        w.pair(0, "ENDTAB")
    }

    private fun writeBlockRecordTable(w: DxfWriter, handles: CoreHandles) {
        w.pair(0, "TABLE").pair(2, "BLOCK_RECORD").pair(70, 2)
        w.pair(0, "BLOCK_RECORD").pair(5, handles.modelSpaceBlockRecord)
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbBlockTableRecord")
        w.pair(2, "*Model_Space")
        w.pair(0, "BLOCK_RECORD").pair(5, handles.paperSpaceBlockRecord)
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbBlockTableRecord")
        w.pair(2, "*Paper_Space")
        w.pair(0, "ENDTAB")
    }

    private fun writeLtypeTable(w: DxfWriter, handles: DxfHandleCounter) {
        w.pair(0, "TABLE").pair(2, "LTYPE").pair(70, if (usesDashOrDot) 3 else 1)
        writeLtypeEntry(w, "CONTINUOUS", "Solid line", emptyList(), handles)
        if (usesDashOrDot) {
            // Dash-gap pattern: a positive number is a dash of that length, negative is a gap.
            writeLtypeEntry(w, "URBAN_DASH", "Dashed", listOf(0.5, -0.25), handles)
            writeLtypeEntry(w, "URBAN_DOT", "Dotted", listOf(0.05, -0.2), handles)
        }
        w.pair(0, "ENDTAB")
    }

    private fun writeLtypeEntry(
        w: DxfWriter,
        name: String,
        description: String,
        dashes: List<Double>,
        handles: DxfHandleCounter
    ) {
        w.pair(0, "LTYPE").pair(5, handles.nextHandle())
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbLinetypeTableRecord")
        w.pair(2, name).pair(70, 0).pair(3, description).pair(72, 65).pair(73, dashes.size)
        w.pair(40, dashes.sumOf { kotlin.math.abs(it) })
        for (d in dashes) w.pair(49, d).pair(74, 0)
    }

    private fun writeLayerTable(w: DxfWriter, handles: DxfHandleCounter) {
        w.pair(0, "TABLE").pair(2, "LAYER").pair(70, layers.size)
        for (layer in layers.values) {
            w.pair(0, "LAYER").pair(5, handles.nextHandle())
            w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbLayerTableRecord")
            w.pair(2, layer.name).pair(70, 0).pair(62, layer.aci).pair(6, "CONTINUOUS")
        }
        w.pair(0, "ENDTAB")
    }

    private fun writeStyleTable(w: DxfWriter, handles: DxfHandleCounter) {
        w.pair(0, "TABLE").pair(2, "STYLE").pair(70, 1)
        w.pair(0, "STYLE").pair(5, handles.nextHandle())
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbTextStyleTableRecord")
        // Arabic-capable TrueType style - the default SHX stroke font has no Arabic glyphs at all,
        // and startLabel/endLabel/plazaName/legend text in this app are routinely Arabic strings.
        w.pair(2, "ARABIC").pair(70, 0).pair(40, 0.0).pair(41, 1.0).pair(50, 0.0).pair(71, 0).pair(42, 2.5)
        w.pair(3, "Tahoma.ttf").pair(4, "")
        w.pair(0, "ENDTAB")
    }

    private fun writeAppidTable(w: DxfWriter, handles: DxfHandleCounter) {
        w.pair(0, "TABLE").pair(2, "APPID").pair(70, 1)
        w.pair(0, "APPID").pair(5, handles.nextHandle())
        w.pair(100, "AcDbSymbolTableRecord").pair(100, "AcDbRegAppTableRecord")
        w.pair(2, "ACAD").pair(70, 0)
        w.pair(0, "ENDTAB")
    }

    // ---------------------------------------------------------------- BLOCKS

    private fun writeBlocks(w: DxfWriter, context: SerializationContext) {
        w.pair(0, "SECTION").pair(2, "BLOCKS")
        writeBlockStub(w, "*Model_Space", context.core.modelSpaceBlockRecord, context.core.modelSpaceBlock, context.core.modelSpaceEndBlk)
        writeBlockStub(w, "*Paper_Space", context.core.paperSpaceBlockRecord, context.core.paperSpaceBlock, context.core.paperSpaceEndBlk)
        w.pair(0, "ENDSEC")
    }

    private fun writeBlockStub(w: DxfWriter, name: String, blockRecordHandle: String, blockHandle: String, endBlkHandle: String) {
        w.pair(0, "BLOCK").pair(5, blockHandle).pair(330, blockRecordHandle)
        w.pair(100, "AcDbEntity").pair(8, "0").pair(100, "AcDbBlockBegin")
        w.pair(2, name).pair(70, 0).pair(10, 0.0).pair(20, 0.0).pair(30, 0.0).pair(3, name).pair(1, "")
        w.pair(0, "ENDBLK").pair(5, endBlkHandle).pair(330, blockRecordHandle)
        w.pair(100, "AcDbEntity").pair(8, "0").pair(100, "AcDbBlockEnd")
    }

    // ---------------------------------------------------------------- ENTITIES

    private fun writeEntitiesSection(w: DxfWriter, context: SerializationContext) {
        w.pair(0, "SECTION").pair(2, "ENTITIES")
        // File order is the most widely honoured fallback draw order among CAD readers.  Put the
        // raster underlay first so every editable vector is painted after (and therefore above)
        // it even in readers that ignore AutoCAD's optional sort-entities table.
        image?.let { writeImageEntity(w, it, requireNotNull(context.raster), context.core) }
        for (e in entities) writeEntity(w, e, context)
        w.pair(0, "ENDSEC")
    }

    /** Follows AutoCAD's own documented "common group codes for all entities" order exactly:
     * handle -> owner -> AcDbEntity marker -> layer/colour -> the entity's OWN subclass marker
     * (not both 100 markers bundled together right before the data, which is where an earlier
     * version of this writer diverged from real AutoCAD output). */
    private fun writePreamble(w: DxfWriter, type: String, subclass: String, e: DxfEntity, context: SerializationContext) {
        w.pair(0, type).pair(5, context.counter.nextHandle()).pair(330, context.core.modelSpaceBlockRecord)
        w.pair(100, "AcDbEntity")
        w.pair(8, e.layer).pair(62, e.aci).pair(420, e.rgb)
        w.pair(100, subclass)
    }

    private fun writeEntity(w: DxfWriter, e: DxfEntity, context: SerializationContext) {
        when (e) {
            is DxfEntity.Line -> {
                writePreamble(w, "LINE", "AcDbLine", e, context)
                w.pair(10, e.a.x).pair(20, e.a.y).pair(30, 0.0)
                w.pair(11, e.b.x).pair(21, e.b.y).pair(31, 0.0)
            }
            is DxfEntity.LwPolyline -> {
                w.pair(0, "LWPOLYLINE").pair(5, context.counter.nextHandle()).pair(330, context.core.modelSpaceBlockRecord)
                w.pair(100, "AcDbEntity")
                w.pair(8, e.layer)
                if (e.linetype != "CONTINUOUS") w.pair(6, e.linetype)
                w.pair(62, e.aci).pair(420, e.rgb)
                if (e.linetype != "CONTINUOUS") w.pair(48, e.linetypeScale)
                w.pair(100, "AcDbPolyline")
                // Group 90 (vertex count) MUST precede every vertex's 10/20 pair, and 70 (closed
                // flag) must not be interleaved between vertices - AutoCAD silently refuses to
                // close the polyline otherwise even when the flag is set.
                w.pair(90, e.points.size)
                w.pair(70, if (e.closed) 1 else 0)
                if (e.constantWidth > 0.0) w.pair(43, e.constantWidth)
                for (p in e.points) { w.pair(10, p.x).pair(20, p.y) }
            }
            is DxfEntity.Circle -> {
                writePreamble(w, "CIRCLE", "AcDbCircle", e, context)
                w.pair(10, e.center.x).pair(20, e.center.y).pair(30, 0.0)
                w.pair(40, e.radius)
            }
            is DxfEntity.Solid -> {
                writePreamble(w, "SOLID", "AcDbTrace", e, context)
                w.pair(10, e.p1.x).pair(20, e.p1.y).pair(30, 0.0)
                w.pair(11, e.p2.x).pair(21, e.p2.y).pair(31, 0.0)
                w.pair(12, e.p3.x).pair(22, e.p3.y).pair(32, 0.0)
                w.pair(13, e.p3.x).pair(23, e.p3.y).pair(33, 0.0) // 4th corner repeats the 3rd -> a triangle
            }
            is DxfEntity.Text -> {
                writePreamble(w, "TEXT", "AcDbText", e, context)
                w.pair(10, e.position.x).pair(20, e.position.y).pair(30, 0.0)
                w.pair(40, e.height)
                w.pair(1, escapeDxfText(e.text))
                if (e.rotationDeg != 0.0) w.pair(50, e.rotationDeg)
                w.pair(7, "ARABIC")
            }
            is DxfEntity.Hatch -> writeHatch(w, e, context)
            is DxfEntity.Image -> Unit // handled once, after the main loop, via writeImageEntity
        }
    }

    private fun writeHatch(w: DxfWriter, e: DxfEntity.Hatch, context: SerializationContext) {
        writePreamble(w, "HATCH", "AcDbHatch", e, context)
        w.pair(10, 0.0).pair(20, 0.0).pair(30, 0.0)
        w.pair(210, 0.0).pair(220, 0.0).pair(230, 1.0)
        val patternName = when (e.pattern) {
            DxfHatchPattern.SOLID -> "SOLID"
            DxfHatchPattern.DIAGONAL -> "ANSI31"
            DxfHatchPattern.CROSS -> "ANSI37"
        }
        w.pair(2, patternName)
        w.pair(70, if (e.pattern == DxfHatchPattern.SOLID) 1 else 0)
        w.pair(71, 0)
        w.pair(91, 1) // one boundary path
        // Boundary path type 7 = default(1) + external(2) + polyline(4): every UrbanHatchPolygon
        // here is a straight-sided loop, so the simpler "polyline path" boundary shortcut is used
        // (73/93/flat vertex pairs) rather than the far more error-prone generic multi-edge-type
        // boundary definition.
        w.pair(92, 7)
        w.pair(73, 1) // boundary is closed
        w.pair(93, e.boundary.size)
        for (p in e.boundary) { w.pair(10, p.x).pair(20, p.y) }
        w.pair(97, 0) // no associated source boundary objects (non-associative hatch)
        w.pair(75, 1) // hatch style: outer
        w.pair(76, 1) // pattern type: predefined/named (SOLID or ANSI31/ANSI37) - never hand-authored inline
        if (e.pattern != DxfHatchPattern.SOLID) {
            w.pair(52, e.patternAngleDeg)
            w.pair(41, e.patternScale)
        }
        w.pair(98, 0) // no seed points
    }

    // ---------------------------------------------------------------- OBJECTS (raster sidecar)

    private fun writeObjectsSection(w: DxfWriter, img: DxfEntity.Image, handles: RasterHandles) {
        val pixelWidthMeters = img.widthMeters / img.pixelWidth
        val pixelHeightMeters = img.heightMeters / img.pixelHeight

        w.pair(0, "SECTION").pair(2, "OBJECTS")

        // Named Object Dictionary (root) -> ACAD_IMAGE_DICT -> IMAGEDEF. IMAGEDEF cannot be
        // placed directly in an anonymous one-level dictionary: strict readers use this ownership
        // chain to discover raster definitions.
        w.pair(0, "DICTIONARY").pair(5, handles.rootDictionary).pair(330, "0")
        w.pair(100, "AcDbDictionary").pair(281, 1)
        w.pair(3, "ACAD_IMAGE_DICT").pair(350, handles.imageDictionary)

        w.pair(0, "DICTIONARY").pair(5, handles.imageDictionary).pair(330, handles.rootDictionary)
        w.pair(100, "AcDbDictionary").pair(281, 1)
        w.pair(3, "IMAGE_1").pair(350, handles.imageDefinition)

        w.pair(0, "IMAGEDEF").pair(5, handles.imageDefinition)
        w.pair(102, "{ACAD_REACTORS").pair(330, handles.imageDefinitionReactor).pair(102, "}")
        w.pair(330, handles.imageDictionary)
        w.pair(100, "AcDbRasterImageDef")
        w.pair(90, 0)
        w.pair(1, img.relativeImagePath)
        w.pair(10, img.pixelWidth.toDouble()).pair(20, img.pixelHeight.toDouble())
        // IMAGEDEF stores the default physical size of one pixel, not the reciprocal number of
        // pixels. Keep it consistent with IMAGE's U/V vectors below.
        w.pair(11, pixelWidthMeters).pair(21, pixelHeightMeters)
        w.pair(280, 1).pair(281, 0)

        // One reactor per IMAGE instance. It points back to the graphical IMAGE entity, while the
        // IMAGE entity's group 360 and IMAGEDEF's persistent-reactor list point here.
        w.pair(0, "IMAGEDEF_REACTOR").pair(5, handles.imageDefinitionReactor).pair(330, "0")
        w.pair(100, "AcDbRasterImageDefReactor").pair(90, 2).pair(330, handles.imageEntity)

        w.pair(0, "ENDSEC")
    }

    private fun writeImageEntity(
        w: DxfWriter,
        img: DxfEntity.Image,
        handles: RasterHandles,
        coreHandles: CoreHandles
    ) {
        val pixelWidthMeters = img.widthMeters / img.pixelWidth
        val pixelHeightMeters = img.heightMeters / img.pixelHeight

        w.pair(0, "IMAGE").pair(5, handles.imageEntity).pair(330, coreHandles.modelSpaceBlockRecord)
        w.pair(100, "AcDbEntity")
        w.pair(8, img.layer)
        w.pair(100, "AcDbRasterImage")
        w.pair(90, 0)
        w.pair(10, img.insertion.x).pair(20, img.insertion.y).pair(30, 0.0)
        // Autodesk defines groups 11/12 as the U/V vectors of ONE pixel. Multiplying these by
        // groups 13/23 (pixel dimensions) must recover widthMeters/heightMeters exactly.
        w.pair(11, pixelWidthMeters).pair(21, 0.0).pair(31, 0.0)
        w.pair(12, 0.0).pair(22, pixelHeightMeters).pair(32, 0.0)
        w.pair(13, img.pixelWidth.toDouble()).pair(23, img.pixelHeight.toDouble())
        w.pair(340, handles.imageDefinition)
        // Bits 1+2 enable/show the image and bit 8 enables transparency for the alpha-bearing
        // PNG sidecar. No custom clipping boundary exists, so bit 4 remains clear and group 280
        // stays disabled. Brightness and contrast use AutoCAD's neutral value of 50.
        w.pair(70, 11).pair(280, 0).pair(281, 50).pair(282, 50).pair(283, 0)
        w.pair(360, handles.imageDefinitionReactor)
    }

    /** Autodesk's documented convention for non-ASCII characters in DXF text strings - safer than
     * gambling on raw UTF-8 byte fidelity across every DXF-reading tool, and this app's own
     * startLabel/endLabel/plazaName/legend strings are routinely Arabic. */
    private fun escapeDxfText(text: String): String = buildString {
        for (c in text) {
            if (c.code in 0x20..0x7E) append(c) else append("\\U+").append(String.format("%04X", c.code))
        }
    }
}
