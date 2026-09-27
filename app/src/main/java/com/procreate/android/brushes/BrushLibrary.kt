package com.procreate.android.brushes

import com.procreate.android.canvas.BrushProperties
import com.procreate.android.canvas.BrushTipType
import com.procreate.android.canvas.BrushType
import com.procreate.android.canvas.GrainType

data class Brush(
    val id: String,
    val name: String,
    val category: String,
    val properties: BrushProperties
)

data class BrushSet(
    val id: String,
    val name: String,
    val brushes: List<Brush>,
    /**
     * The colour this set is introduced with on its cover card and tagged with in the rail.
     *
     * Zero means "choose one" - [BrushSetIdentity] derives a stable colour from the id, so an
     * imported pack or a user's own collection gets a cover as considered as a built-in one
     * without anybody having to pick a swatch for it.
     */
    val accentColor: Int = 0,
    /** One line under the title, saying what the set is for rather than repeating its name. */
    val tagline: String? = null
)

/**
 * Cover identity for a brush set.
 *
 * A list of sets that all look the same reads as a filing cabinet. Giving each one a card - a
 * colour, a name set large, a line about what it is for, and a sample of what it actually draws -
 * is what turns a collection of brushes into something a calligrapher recognises as *their* set.
 */
object BrushSetIdentity {

    /**
     * Deliberately a small, curated palette rather than a hue spun out of the hash: eight colours
     * that sit together make a library that looks designed, where an unrestricted spectrum makes
     * one that looks random.
     */
    private val ACCENTS = intArrayOf(
        0xFF2FBF71.toInt(), // green
        0xFF2F86D6.toInt(), // blue
        0xFF2AA9C9.toInt(), // cyan
        0xFF5B5BD6.toInt(), // indigo
        0xFFD98324.toInt(), // amber
        0xFFC7405E.toInt(), // rose
        0xFF8E56C4.toInt(), // purple
        0xFF3E8E7E.toInt()  // teal
    )

    fun accentFor(set: BrushSet): Int {
        if (set.accentColor != 0) return set.accentColor
        val index = (set.id.hashCode().toLong() and 0x7FFFFFFFL) % ACCENTS.size
        return ACCENTS[index.toInt()]
    }

    /**
     * The first real letter of the set's name, blown up as a watermark on its cover.
     *
     * Taken from the name rather than stored separately so it can never disagree with it, and
     * after stripping the emoji many sets are prefixed with - a pictogram enlarged to seventy
     * points is a sticker, where a letterform is a monogram.
     */
    fun glyphFor(set: BrushSet): String {
        val firstWord = set.name
            .split(' ', ' ', '-')
            .map { word -> word.filter { it.isLetter() } }
            .firstOrNull { it.isNotEmpty() }
            ?: return "•"
        // Nearly every Arabic set name opens with the definite article, so taking the very first
        // letter would stamp the same bare vertical stroke on half the library's covers.
        val meaningful = if (firstWord.length > 2 && firstWord.startsWith("ال")) {
            firstWord.drop(2)
        } else {
            firstWord
        }
        return meaningful.first().toString()
    }

    private val PICTOGRAMS = Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]")

    /**
     * The set's name as every surface shows it: without the emoji and symbol prefixes the names
     * carry. The rail stripped them and the cover did not, so the two disagreed (review L1), and
     * emoji ignore the app's tint and change look from device to device.
     */
    fun displayName(set: BrushSet): String = displayName(set.name)

    fun displayName(name: String): String =
        name.replace(PICTOGRAMS, "").replace(Regex("\\s+"), " ").trim().ifEmpty { name.trim() }

    /**
     * The rail icon for each set, chosen per set rather than guessed from fragments of its id
     * (review L2: "air" matched "airbrushing" but also any future id containing it). A set not
     * listed here - an imported pack - gets the plain brush.
     */
    private val ICONS: Map<String, Int> = mapOf(
        "my_brushes" to com.procreate.android.R.drawable.ic_plus,
        "artify_originals" to com.procreate.android.R.drawable.ic_brush,
        "color_flow" to com.procreate.android.R.drawable.ic_palette,
        "square_kufic" to com.procreate.android.R.drawable.ic_urban_grid,
        "arabic_calligraphy" to com.procreate.android.R.drawable.ic_brush,
        "featured_signature" to com.procreate.android.R.drawable.ic_library,
        "sketching" to com.procreate.android.R.drawable.ic_brush,
        "inking" to com.procreate.android.R.drawable.ic_brush,
        "drawing" to com.procreate.android.R.drawable.ic_brush,
        "painting" to com.procreate.android.R.drawable.ic_palette,
        "artistic" to com.procreate.android.R.drawable.ic_filters,
        "calligraphy" to com.procreate.android.R.drawable.ic_brush,
        "airbrushing" to com.procreate.android.R.drawable.ic_smudge,
        "textures" to com.procreate.android.R.drawable.ic_image,
        "abstract" to com.procreate.android.R.drawable.ic_filters,
        "charcoals" to com.procreate.android.R.drawable.ic_image,
        "elements" to com.procreate.android.R.drawable.ic_blur,
        "spraypaints" to com.procreate.android.R.drawable.ic_smudge,
        "touchups" to com.procreate.android.R.drawable.ic_adjustments,
        "retro" to com.procreate.android.R.drawable.ic_image,
        "luminance" to com.procreate.android.R.drawable.ic_colors,
        "industrial" to com.procreate.android.R.drawable.ic_urban_grid,
        "organic" to com.procreate.android.R.drawable.ic_image,
        "water" to com.procreate.android.R.drawable.ic_blur,
        "earth" to com.procreate.android.R.drawable.ic_image,
        "special_brushes" to com.procreate.android.R.drawable.ic_library
    )

    fun iconFor(set: BrushSet): Int = ICONS[set.id] ?: com.procreate.android.R.drawable.ic_brush

    /** For tests: whether [iconFor] has a deliberate choice for this set. */
    internal fun hasExplicitIcon(set: BrushSet): Boolean = set.id in ICONS

    /**
     * Stable seed for a brush preview. Kotlin's String hash is deterministic, but widening a single
     * 32-bit hash to Long makes collisions needlessly likely in a large imported library. FNV-1a
     * keeps the renderer deterministic while mixing the brush identity into the full seed width.
     */
    fun previewSeed(brush: Brush): Long {
        var hash = -0x340d631b7bdddcdbL // 64-bit FNV offset basis as a signed Long.
        val identity = "${brush.id}\u0000${brush.category}\u0000${brush.name}"
        identity.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    fun lighten(color: Int): Int = blend(color, 0xFFFFFFFF.toInt(), 0.22f)

    fun darken(color: Int): Int = blend(color, 0xFF000000.toInt(), 0.24f)

    private fun blend(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/**
 * The built-in brush library grouped by discipline (sketching, inking, painting, Arabic
 * calligraphy, and so on), including a small Artify-original signature collection.
 *
 * Brush ids here are not persisted anywhere - [BrushPanel] tracks the active set by list index -
 * so an id can be renamed freely without invalidating a user's saved work.
 */
object BrushLibrary {

    fun getDefaultBrushSets(): List<BrushSet> {
        return listOf(
            createArabicCalligraphySet(),
            createSquareKuficSet(),
            createColorFlowSet(),
            createArtifyOriginalsSet(),
            createFeaturedSet(),
            createSketchingSet(),
            createInkingSet(),
            createDrawingSet(),
            createPaintingSet(),
            createArtisticSet(),
            createCalligraphySet(),
            createAirbrushingSet(),
            createTexturesSet(),
            createAbstractSet(),
            createCharcoalsSet(),
            createElementsSet(),
            createSpraypaintsSet(),
            createTouchupsSet(),
            createRetroSet(),
            createLuminanceSet(),
            createIndustrialSet(),
            createOrganicSet(),
            createWaterSet(),
            createEarthSet(),
            createSpecialBrushesSet(),
        )
    }

    /**
     * A compact house collection designed around Artify's engine rather than copied preset
     * names. Each brush owns a distinct job and deliberately exercises a different combination
     * of pressure, velocity, grain, wet mixing and stamp dynamics.
     */
    private fun createArtifyOriginalsSet(): BrushSet = BrushSet(
        "artify_originals", "✦ فرش Artify الأصلية", listOf(
            Brush("artify_pulse_ink", "نبض الحبر", "Artify Originals", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, flow = 0.94f,
                spacing = 0.012f, smoothing = 0.58f, velocitySizeMin = 0.58f,
                pressureSizeScale = 0.68f, pressureOpacityScale = 0.16f, wetness = 0.08f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard-ink.png",
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                grainScale = 0.28f
            )),
            Brush("artify_architect_marker", "ماركر معماري", "Artify Originals", BrushProperties(
                type = BrushType.Ink, size = 28f, opacity = 0.94f, flow = 0.82f,
                spacing = 0.016f, smoothing = 0.44f, angle = 8f,
                pressureSizeScale = 0.24f, pressureOpacityScale = 0.46f,
                velocitySizeMin = 0.74f, tipType = BrushTipType.MARKER,
                grainType = GrainType.PAPER, grainScale = 0.18f
            )),
            Brush("artify_velvet_gouache", "غواش مخملي", "Artify Originals", BrushProperties(
                type = BrushType.Paint, size = 42f, opacity = 0.88f, flow = 0.76f,
                spacing = 0.032f, smoothing = 0.32f, wetness = 0.30f,
                pressureSizeScale = 0.58f, pressureOpacityScale = 0.48f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-raggedy-gouache.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                grainScale = 0.66f
            )),
            Brush("artify_mist_wash", "غيمة مائية", "Artify Originals", BrushProperties(
                type = BrushType.Paint, size = 58f, opacity = 0.60f, flow = 0.58f,
                spacing = 0.045f, smoothing = 0.38f, wetness = 0.84f,
                pressureSizeScale = 0.64f, pressureOpacityScale = 0.54f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-cotton-paper.jpg",
                grainScale = 0.72f
            )),
            Brush("artify_night_charcoal", "فحم ليلي", "Artify Originals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, flow = 0.82f,
                spacing = 0.052f, smoothing = 0.20f, sizeJitter = 0.10f,
                angleJitter = 12f, pressureSizeScale = 0.70f,
                pressureOpacityScale = 0.58f, tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-block.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-rough.jpg",
                grainScale = 0.86f
            )),
            Brush("artify_light_thread", "خيط الضوء", "Artify Originals", BrushProperties(
                type = BrushType.Paint, size = 22f, opacity = 0.78f, flow = 0.68f,
                spacing = 0.018f, smoothing = 0.70f, velocitySizeMin = 0.48f,
                pressureSizeScale = 0.82f, pressureOpacityScale = 0.22f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-pen-glow.jpg",
                grainType = GrainType.NONE, grainScale = 0f
            )),
            Brush("artify_stone_grain", "حبيبات الحجر", "Artify Originals", BrushProperties(
                type = BrushType.Paint, size = 46f, opacity = 0.82f, flow = 0.72f,
                spacing = 0.060f, smoothing = 0.24f, scatter = 0.10f,
                angleJitter = 24f, pressureSizeScale = 0.56f,
                pressureOpacityScale = 0.44f, tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-limestone-shape.png",
                customGrainPath = "asset://brushes/textures/tx-limestone-grain.png",
                grainScale = 0.78f
            )),
            Brush("artify_orbit_spray", "رذاذ مداري", "Artify Originals", BrushProperties(
                type = BrushType.Airbrush, size = 44f, opacity = 0.60f, flow = 0.48f,
                spacing = 0.18f, smoothing = 0.18f, scatter = 0.76f,
                angleJitter = 180f, sizeJitter = 0.52f, opacityJitter = 0.38f,
                pressureSizeScale = 0.60f, pressureOpacityScale = 0.52f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-fine-spray.jpg",
                grainType = GrainType.NONE, grainScale = 0f
            ))
        )
    )

    /**
     * Square Kufic - the built, orthogonal script that is assembled inside a grid rather than
     * written along a line.
     *
     * These are not ordinary brushes with a stiff setting. A Kufic pen fills whole modules of the
     * paper's own grid: bars one module wide, gaps one module wide, corners exactly square. The
     * denominator is the module size relative to the ruling, so a 1/2 pen writes at twice the
     * resolution of the paper's squares for finer interior detail.
     */
    /**
     * Brushes whose colour moves while the stroke is being made.
     *
     * Every one of these is an ordinary brush with the colour-flow settings turned up; the point of
     * gathering them is that the effect is invisible in a still list and has to be met somewhere.
     * The drift figures are per thousand pixels, so a short mark stays close to the chosen colour
     * and a long wash travels - which is how a loaded brush actually behaves.
     */
    private fun createColorFlowSet(): BrushSet = BrushSet(
        "color_flow", "🌊 تدرّج اللون",
        accentColor = 0xFF5B5BD6.toInt(),
        tagline = "فرش تتحرّك ألوانها أثناء الضربة الواحدة",
        brushes = listOf(
            Brush("flow_watercolor_wash", "غسلة مائية متدرّجة", "Colour Flow", BrushProperties(
                type = BrushType.Paint, size = 48f, opacity = 0.55f, flow = 0.7f,
                spacing = 0.04f, wetness = 0.35f, smoothing = 0.4f,
                pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f,
                colorFlowEnabled = true, colorFlowHue = 110f, colorJitterHue = 0.05f,
                colorJitterSaturation = 0.18f, colorJitterBrightness = 0.12f,
                tipType = BrushTipType.WATERCOLOR, grainType = GrainType.INK_BLEED, grainScale = 0.5f
            )),
            Brush("flow_bloom", "تفتّح الصبغة", "Colour Flow", BrushProperties(
                type = BrushType.Paint, size = 64f, opacity = 0.42f, flow = 0.6f,
                spacing = 0.06f, wetness = 0.5f, smoothing = 0.45f, scatter = 0.08f,
                pressureSizeScale = 0.6f, pressureOpacityScale = 0.5f,
                colorFlowEnabled = true, colorFlowHue = 200f, colorJitterHue = 0.09f,
                colorJitterSaturation = 0.22f, colorJitterBrightness = 0.16f,
                tipType = BrushTipType.WATERCOLOR, grainType = GrainType.SPECKLE, grainScale = 0.45f
            )),
            // A small drift only: enough to keep a line alive without it announcing itself.
            Brush("flow_ink_subtle", "حبر حيّ", "Colour Flow", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 0.95f,
                spacing = 0.02f, smoothing = 0.45f,
                pressureSizeScale = 0.4f, pressureOpacityScale = 0.15f,
                colorFlowEnabled = true, colorFlowHue = 28f, colorJitterSaturation = 0.08f,
                tipType = BrushTipType.INK_PEN, grainType = GrainType.NONE
            )),
            Brush("flow_spray_prism", "رذاذ منشوري", "Colour Flow", BrushProperties(
                type = BrushType.Airbrush, size = 56f, opacity = 0.5f, flow = 0.5f,
                spacing = 0.22f, scatter = 0.55f, sizeJitter = 0.4f, smoothing = 0.25f,
                pressureSizeScale = 0.5f, pressureOpacityScale = 0.4f,
                colorFlowEnabled = true, colorFlowHue = 300f, colorJitterHue = 0.22f,
                colorJitterSaturation = 0.25f, colorJitterBrightness = 0.2f,
                tipType = BrushTipType.SPRAY, grainType = GrainType.NOISE, grainScale = 0.3f
            ))
        )
    )

    private fun createSquareKuficSet(): BrushSet = BrushSet(
        "square_kufic", "▦ كوفي تربيعي",
        accentColor = 0xFF2FBF71.toInt(),
        tagline = "خط بنائي — يُركَّب داخل الشبكة لا يُكتب على سطر",
        brushes = listOf(
            Brush("kufic_grid_1_1", "شبكة 1/1", "Square Kufic", BrushProperties(
                type = BrushType.Ink, size = 24f, opacity = 1f, flow = 1f,
                gridSnapDivisions = 1,
                smoothing = 0.3f, pressureSizeScale = 0f, pressureOpacityScale = 0f,
                tipType = BrushTipType.FLAT_BRUSH
            )),
            Brush("kufic_grid_1_2", "شبكة 1/2", "Square Kufic", BrushProperties(
                type = BrushType.Ink, size = 12f, opacity = 1f, flow = 1f,
                gridSnapDivisions = 2,
                smoothing = 0.3f, pressureSizeScale = 0f, pressureOpacityScale = 0f,
                tipType = BrushTipType.FLAT_BRUSH
            )),
            Brush("kufic_grid_1_3", "شبكة 1/3", "Square Kufic", BrushProperties(
                type = BrushType.Ink, size = 8f, opacity = 1f, flow = 1f,
                gridSnapDivisions = 3,
                smoothing = 0.25f, pressureSizeScale = 0f, pressureOpacityScale = 0f,
                tipType = BrushTipType.FLAT_BRUSH
            )),
            // A softer module for the coloured accents a Kufic panel is punctuated with - the one
            // orange square in an otherwise black composition.
            Brush("kufic_accent", "مربّع لوني", "Square Kufic", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 1f, flow = 1f,
                gridSnapDivisions = 1,
                smoothing = 0.1f, pressureSizeScale = 0f, pressureOpacityScale = 0f,
                tipType = BrushTipType.FLAT_BRUSH
            ))
        ).map { brush ->
            // Modules are either filled or they are not; a cell entered twice must look identical
            // to one entered once, or the grid stops being a grid.
            brush.copy(properties = brush.properties.copy(buildUp = false))
        }
    )

    private fun createArabicCalligraphySet(): BrushSet = BrushSet(
        "arabic_calligraphy", "🖋️ الخط العربي",
        accentColor = 0xFF2FBF71.toInt(),
        tagline = "قصبة مقطوعة الطرف — ثلث ونسخ ورقعة وديواني",
        brushes = listOf(
            Brush("artify_khat_diwani", "خط الديواني الأصلي", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, flow = 1f,
                spacing = 0.012f, angle = 45f, orientToStroke = false,
                pressureSizeScale = 0.35f, pressureOpacityScale = 0.15f,
                velocitySizeMin = 0.6f, smoothing = 0.45f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-stained-paper-1.jpg",
                grainScale = 0.38f
            )),
            Brush("artify_khat_ruqah", "خط الرقعة الأصلي", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 16f, opacity = 1f, flow = 1f,
                spacing = 0.015f, angle = 30f, orientToStroke = false,
                pressureSizeScale = 0.35f, pressureOpacityScale = 0.15f,
                velocitySizeMin = 0.7f, smoothing = 0.35f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                grainScale = 0.34f
            )),
            Brush("qalam_reed_pen", "قلم القصب (ثلث)", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 16f, opacity = 1f,
                spacing = 0.015f, angle = 45f, orientToStroke = false,
                pressureSizeScale = 0.45f, pressureOpacityScale = 0.15f,
                velocitySizeMin = 0.6f, smoothing = 0.35f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-textured-ink.png",
                grainScale = 0.44f
            )),
            Brush("khat_thuluth", "خط الثلث الجلي", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f,
                spacing = 0.012f, angle = 45f, orientToStroke = false,
                pressureSizeScale = 0.55f, pressureOpacityScale = 0.1f,
                velocitySizeMin = 0.6f, smoothing = 0.45f, wetness = 0.1f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-european-paper.jpg",
                grainScale = 0.38f
            )),
            Brush("khat_ruqah", "خط الرقعة السريع", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 12f, opacity = 1f,
                spacing = 0.018f, angle = 30f, orientToStroke = false,
                pressureSizeScale = 0.3f, pressureOpacityScale = 0.05f,
                velocitySizeMin = 0.8f, smoothing = 0.25f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED, grainScale = 0.25f
            )),
            Brush("khat_diwani", "خط الديواني الجلي", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 16f, opacity = 1f,
                spacing = 0.015f, angle = 45f, orientToStroke = false,
                pressureSizeScale = 0.5f, pressureOpacityScale = 0.1f,
                velocitySizeMin = 0.55f, smoothing = 0.45f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED, grainScale = 0.3f
            )),
            Brush("khat_naskh", "خط النسخ القرآني", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 12f, opacity = 1f,
                spacing = 0.015f, angle = 40f, orientToStroke = false,
                pressureSizeScale = 0.4f, pressureOpacityScale = 0.08f,
                velocitySizeMin = 0.7f, smoothing = 0.35f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-cotton-paper.jpg",
                grainScale = 0.28f
            )),
            Brush("khat_kufi", "خط كوفي هندسي", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f,
                spacing = 0.02f, angle = 0f, orientToStroke = false,
                pressureSizeScale = 0.1f, pressureOpacityScale = 0f,
                velocitySizeMin = 0.9f, smoothing = 0.2f,
                tipType = BrushTipType.FLAT_BRUSH, grainType = GrainType.PAPER,
                customGrainPath = "asset://brushes/textures/tx-limestone-grain.png",
                grainScale = 0.34f
            )),
            Brush("artify_kufi_engraved", "كوفي منقوش", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 24f, opacity = 0.96f, flow = 0.88f,
                spacing = 0.028f, angle = 0f, orientToStroke = false, smoothing = 0.28f,
                pressureSizeScale = 0.16f, pressureOpacityScale = 0.22f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-limestone-shape.png",
                customGrainPath = "asset://brushes/textures/tx-limestone-grain.png",
                grainScale = 0.72f
            )),
            Brush("artify_manuscript_ink", "حبر المخطوط", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 19f, opacity = 0.92f, flow = 0.84f,
                spacing = 0.022f, angle = 38f, orientToStroke = false, smoothing = 0.42f,
                pressureSizeScale = 0.48f, pressureOpacityScale = 0.40f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard-ink.png",
                customGrainPath = "asset://brushes/textures/tx-dirty-paper.jpg",
                grainScale = 0.58f
            )),
            Brush("artify_arabesque_stamp", "زخرفة الأرابيسك", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Paint, size = 34f, opacity = 0.94f, flow = 0.90f,
                spacing = 0.42f, smoothing = 0.18f, scatter = 0.05f,
                angleJitter = 10f, sizeJitter = 0.06f, pressureSizeScale = 0.24f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-simple-leaf.png",
                customGrainPath = "asset://brushes/textures/tx-stained-paper-2.jpg",
                grainScale = 0.52f
            )),

            // Barrel-led companions to the fixed-cut reeds above.
            //
            // A classical reed is held at one angle and the thick and thin come from the direction
            // of travel, which is what every brush above does. But a calligrapher still rolls the
            // wrist through particular letterforms - the tail of a ya, the bowl of an ayn - and no
            // amount of pressure or speed can express that, because it is the nib turning, not the
            // hand pressing. These take their angle from where the S Pen's barrel actually points,
            // so the pen in the hand and the nib on the paper are the same object. The fixed angle
            // survives as the cut's own offset, and tilt spreads the contact patch the way laying a
            // real reed over does.
            Brush("khat_thuluth_azimuth", "خط الثلث ( اتجاه القلم )", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f,
                spacing = 0.012f, angle = 45f, orientToStroke = false,
                azimuthTracking = 1f, tiltSizeScale = 0.35f,
                pressureSizeScale = 0.35f, pressureOpacityScale = 0.08f,
                velocitySizeMin = 0.65f, smoothing = 0.45f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-european-paper.jpg",
                grainScale = 0.38f
            )),
            Brush("khat_diwani_azimuth", "خط الديواني ( اتجاه القلم )", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 16f, opacity = 1f,
                spacing = 0.015f, angle = 45f, orientToStroke = false,
                azimuthTracking = 1f, tiltSizeScale = 0.3f,
                pressureSizeScale = 0.4f, pressureOpacityScale = 0.1f,
                velocitySizeMin = 0.6f, smoothing = 0.45f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED, grainScale = 0.3f
            )),
            Brush("khat_naskh_azimuth", "خط النسخ ( اتجاه القلم )", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 12f, opacity = 1f,
                spacing = 0.015f, angle = 40f, orientToStroke = false,
                azimuthTracking = 1f, tiltSizeScale = 0.25f,
                pressureSizeScale = 0.3f, pressureOpacityScale = 0.06f,
                velocitySizeMin = 0.72f, smoothing = 0.35f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-cotton-paper.jpg",
                grainScale = 0.28f
            )),
            // Half tracking: most of the cut stays fixed and the nib only drifts with the wrist.
            // Closer to a real reed than either extreme, and the gentlest place to start.
            Brush("khat_ruqah_azimuth", "خط الرقعة ( اتجاه القلم )", "Arabic Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 16f, opacity = 1f,
                spacing = 0.015f, angle = 30f, orientToStroke = false,
                azimuthTracking = 0.5f, tiltSizeScale = 0.2f,
                pressureSizeScale = 0.3f, pressureOpacityScale = 0.08f,
                velocitySizeMin = 0.72f, smoothing = 0.35f,
                tipType = BrushTipType.REED_PEN, grainType = GrainType.INK_BLEED,
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                grainScale = 0.34f
            ))
        ).sortedBy { brush ->
            when (brush.id) {
                "artify_kufi_engraved" -> 0
                "artify_manuscript_ink" -> 1
                "artify_arabesque_stamp" -> 2
                else -> 10
            }
        }.map { brush ->
            // Ink on paper does not get darker because the pen passed twice. A letter whose bowl
            // closes over its own stem showed a bruise at the join, which is the single most
            // obvious way a digital hand gives itself away in calligraphy.
            brush.copy(properties = brush.properties.copy(buildUp = false))
        }
    )

    private fun createFeaturedSet(): BrushSet = BrushSet(
        "featured_signature", "⭐️ مختارات", listOf(
            Brush("proc_6b_pencil_feat", "6B Pencil", "Featured", BrushProperties(
                type = BrushType.Pencil, size = 22f, opacity = 0.95f, spacing = 0.04f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-brick.png",
                customGrainPath = "asset://brushes/textures/tx-charcoal-corse.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-brick.png"
            )),
            Brush("proc_studio_pen_feat", "Precision Pen", "Featured", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.02f, smoothing = 0.4f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blank.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-blank.png"
            )),
            Brush("proc_technical_pen_feat", "Technical Pen", "Featured", BrushProperties(
                type = BrushType.Ink, size = 12f, opacity = 1f, spacing = 0.02f, smoothing = 0.2f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-small-point.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                // No bundled thumbnail for this one. Left null so BrushPreviewRenderer takes its
                // designed fallback and draws the brush's own tip shape - an icon that actually
                // describes this brush - instead of re-attempting an asset that does not exist on
                // every single render (getThumbnail caches nothing when the load fails).
                thumbnailPath = null
            )),
            Brush("proc_syrup_feat", "Thick Ink", "Featured", BrushProperties(
                type = BrushType.Ink, size = 20f, opacity = 1f, spacing = 0.02f, smoothing = 0.5f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-simple-leaf.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-simple-leaf.jpg"
            )),
            Brush("br_ember", "Ember", "Featured", BrushProperties(
                type = BrushType.Ink, size = 24f, opacity = 0.9f, spacing = 0.05f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-square.jpg",
                customGrainPath = "asset://brushes/textures/tx-wax-crayon-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-square.jpg"
            )),
            Brush("br_cobblestone", "Cobblestone", "Featured", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.85f, spacing = 0.06f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-5.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-5.jpg"
            )),
            Brush("br_chalk_stick", "Chalk Stick", "Featured", BrushProperties(
                type = BrushType.Pencil, size = 28f, opacity = 0.88f, spacing = 0.05f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-chalk-stick.png",
                // No bundled thumbnail - see the note on Technical Pen above.
                thumbnailPath = null
            )),
            Brush("proc_charcoal_block_feat", "Charcoal Block", "Featured", BrushProperties(
                type = BrushType.Pencil, size = 35f, opacity = 0.95f, spacing = 0.06f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-block.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-rough.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-block.png"
            )),
            Brush("proc_vine_charcoal_feat", "Vine Charcoal", "Featured", BrushProperties(
                type = BrushType.Pencil, size = 26f, opacity = 0.85f, spacing = 0.05f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-macro.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-vine.jpg",
                // No bundled thumbnail - see the note on Technical Pen above.
                thumbnailPath = null
            )),
            Brush("proc_dry_ink_feat", "Dry Ink", "Featured", BrushProperties(
                type = BrushType.Ink, size = 20f, opacity = 0.95f, spacing = 0.04f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blotch.jpg",
                customGrainPath = "asset://brushes/textures/tx-splatters.png",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-dry.png"
            )),
            Brush("proc_limestone_feat", "Limestone", "Featured", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.88f, spacing = 0.07f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-limestone-shape.png",
                customGrainPath = "asset://brushes/textures/tx-limestone-grain.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hard.png"
            )),
            Brush("proc_palm_husk_feat", "Palm Husk", "Featured", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.85f, spacing = 0.08f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-palm-shape.png",
                customGrainPath = "asset://brushes/textures/tx-palm-grain.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_volcanic_ash_feat", "Volcanic Ash", "Featured", BrushProperties(
                type = BrushType.Paint, size = 28f, opacity = 0.85f, spacing = 0.08f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-volcanic-shape.png",
                customGrainPath = "asset://brushes/textures/tx-volcanic-grain.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_acrylic_feat", "Acrylic", "Featured", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.9f, spacing = 0.05f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-refined-round-dots.png",
                customGrainPath = "asset://brushes/textures/tx-badspray.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("proc_gouache_feat", "Gouache", "Featured", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.9f, spacing = 0.06f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                // No bundled thumbnail - see the note on Technical Pen above.
                thumbnailPath = null
            )),
            Brush("proc_clouds_feat", "Clouds", "Featured", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.75f, spacing = 0.12f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-cloud-shape.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-clouds.png"
            )),
            Brush("proc_clay_feat", "Clay", "Featured", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.85f, spacing = 0.07f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-wet-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-clay.png"
            )),
            Brush("br_deep_ink", "Deep Ink", "Featured", BrushProperties(
                type = BrushType.Ink, size = 26f, opacity = 0.9f, spacing = 0.05f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink-dry.png",
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-dry.png"
            )),
            Brush("proc_aurora_feat", "Polar Glow", "Featured", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.8f, spacing = 0.08f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-simple-blotch.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-simple-blotch.jpg"
            )),
            Brush("proc_spectra_feat", "Prism", "Featured", BrushProperties(
                type = BrushType.Paint, size = 34f, opacity = 0.85f, spacing = 0.06f,
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-rough.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-rough-2.jpg"
            ))
        )
    )

    private fun createSketchingSet(): BrushSet = BrushSet(
        "sketching", "✏️ الرسم الأولي", listOf(
            Brush("proc_peppermint", "Mint Leaf", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-distressed-stamp.jpg",
                customGrainPath = "asset://brushes/textures/tx-procedural-noise.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-distressed-stamp.jpg"
            )),
            Brush("br_river_wash", "River Wash", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-distressed-stamp.jpg",
                customGrainPath = "asset://brushes/textures/tx-european-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-distressed-stamp.jpg"
            )),
            Brush("proc_artify_pencil", "Artify Pencil", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink.jpg",
                customGrainPath = "asset://brushes/textures/tx-dirty-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink.png"
            )),
            Brush("proc_technical_pencil", "Technical Pencil", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 6f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.15f, grainScale = 0.35f, pressureSizeScale = 0.25f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink-sponge.png",
                customGrainPath = "asset://brushes/textures/tx-sketch-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-sponge.png"
            )),
            Brush("proc_hb_pencil", "HB Pencil", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-scribble.jpg",
                customGrainPath = "asset://brushes/textures/tx-munched-paper.png",
                thumbnailPath = "asset://brushes/thumbnails/th-scribble.png"
            )),
            Brush("proc_6b_pencil", "6B Pencil", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 24f, opacity = 0.92f, spacing = 0.035f,
                smoothing = 0.15f, grainScale = 0.90f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.70f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-brick.png",
                customGrainPath = "asset://brushes/textures/tx-charcoal-corse.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-brick.png"
            )),
            Brush("br_sketch_pencil", "Sketch Pencil", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-small-point.png",
                customGrainPath = "asset://brushes/textures/tx-recycled-paper.png",
                thumbnailPath = "asset://brushes/thumbnails/th-recycled-paper.png"
            )),
            Brush("proc_soft_pastel", "Soft Pastel", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blotch.jpg",
                customGrainPath = "asset://brushes/textures/tx-pastel-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-blotch.png"
            )),
            Brush("proc_oil_pastel", "Oil Pastel", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-oil-pastel.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-oil-pastel.png"
            )),
            Brush("proc_artist_crayon", "Artist Crayon", "Sketching", BrushProperties(
                type = BrushType.Pencil, size = 16f, opacity = 0.90f, spacing = 0.030f,
                smoothing = 0.15f, grainScale = 0.75f, pressureSizeScale = 0.45f, pressureOpacityScale = 0.55f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-crayon.jpg",
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-crayon.png"
            ))
        )
    )

    private fun createInkingSet(): BrushSet = BrushSet(
        "inking", "✒️ التحبير", listOf(
            Brush("proc_mercury", "Liquid Metal", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp.png",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-2.jpg"
            )),
            Brush("br_ink_nib", "Ink Nib", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-simple-leaf.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-simple-leaf.jpg"
            )),
            Brush("proc_inka", "Ink Drop", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-5.png",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-5.jpg"
            )),
            Brush("br_frond", "Frond", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-4.jpg",
                customGrainPath = "asset://brushes/textures/tx-undercoat.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-4.jpg"
            )),
            Brush("br_ember_2", "Ember", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-square.jpg",
                customGrainPath = "asset://brushes/textures/tx-wax-crayon-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-square.jpg"
            )),
            Brush("proc_syrup", "Thick Ink", "Inking", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.65f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-simple-leaf.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-simple-leaf.jpg"
            )),
            Brush("br_striped_grain", "Striped Grain", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-pastel-board-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-pastel-board-wash.jpg"
            )),
            Brush("proc_fine_tip", "Fine Tip", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ultra-soft-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-ultra-soft-2.png"
            )),
            Brush("proc_technical_pen", "Technical Pen", "Inking", BrushProperties(
                type = BrushType.Ink, size = 8f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.25f, grainScale = 0f, pressureSizeScale = 0.20f, pressureOpacityScale = 0f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-small-point.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-blank.png"
            )),
            Brush("proc_gel_pen", "Gel Pen", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_ink_bleed", "Ink Bleed", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink2.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-ink2.png"
            )),
            Brush("proc_studio_pen", "Precision Pen", "Inking", BrushProperties(
                type = BrushType.Ink, size = 16f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.75f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_dry_ink", "Dry Ink", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blotch.jpg",
                customGrainPath = "asset://brushes/textures/tx-splatters.png",
                thumbnailPath = "asset://brushes/thumbnails/th-blotch.png"
            )),
            Brush("br_studio_ink", "Studio Ink", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-slim-oval.png",
                customGrainPath = "asset://brushes/textures/tx-textured-ink.png",
                thumbnailPath = "asset://brushes/thumbnails/th-slim-oval.png"
            )),
            Brush("proc_marker", "Marker", "Inking", BrushProperties(
                type = BrushType.Ink, size = 18f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink.jpg",
                customGrainPath = "asset://brushes/textures/tx-munched-paper.png",
                thumbnailPath = "asset://brushes/thumbnails/th-ink.png"
            ))
        )
    )

    private fun createDrawingSet(): BrushSet = BrushSet(
        "drawing", "🎨 الرسم", listOf(
            Brush("proc_little_pine", "Pine Needle", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-pastel-board-coarse.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-2.jpg"
            )),
            Brush("proc_gloaming", "Dusk Glow", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-3.png",
                customGrainPath = "asset://brushes/textures/tx-cotton-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-3.jpg"
            )),
            Brush("br_granite_wash", "Granite Wash", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-splash.jpg",
                customGrainPath = "asset://brushes/textures/tx-pastel-board-fine.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-splash.jpg"
            )),
            Brush("br_deep_ink_2", "Deep Ink", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink-dry.png",
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-dry.png"
            )),
            Brush("proc_evolve", "Morph", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab.jpg",
                customGrainPath = "asset://brushes/textures/tx-light-pastel-board.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("br_talon_ink", "Talon Ink", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-2.jpg"
            )),
            Brush("proc_oberon", "Night Wash", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-6.jpg",
                customGrainPath = "asset://brushes/textures/tx-fine-thread.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-6.jpg"
            )),
            Brush("proc_styx", "Deep Current", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-orbs.png",
                customGrainPath = "asset://brushes/textures/tx-square-dots.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-orbs.png"
            )),
            Brush("br_vineyard_wash", "Vineyard Wash", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-4.png",
                customGrainPath = "asset://brushes/textures/tx-raggedy-gouache.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-4.jpg"
            )),
            Brush("br_copper_line", "Copper Line", "Drawing", BrushProperties(
                type = BrushType.Paint, size = 24f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.20f, grainScale = 0.70f, pressureSizeScale = 0.55f, pressureOpacityScale = 0.45f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-weary-dots.jpg",
                customGrainPath = "asset://brushes/textures/tx-pastel-board.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-weary-dots.jpg"
            ))
        )
    )

    private fun createPaintingSet(): BrushSet = BrushSet(
        "painting", "🖌️ التلوين", listOf(
            Brush("proc_round_brush", "Round Brush", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hard.png"
            )),
            Brush("proc_flat_brush", "Flat Brush", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-brick.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-flat-brush.png"
            )),
            Brush("proc_acrylic", "Acrylic", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-refined-round-dots.png",
                customGrainPath = "asset://brushes/textures/tx-badspray.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("proc_wet_acrylic", "Wet Acrylic", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.35f, wetness = 0.65f, grainScale = 0.60f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-burnt.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-mess-2.png"
            )),
            Brush("proc_jagged_brush", "Jagged Brush", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flat-brush.jpg",
                customGrainPath = "asset://brushes/textures/tx-army.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-flat-brush.png"
            )),
            Brush("br_cobblestone_2", "Cobblestone", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-5.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-5.jpg"
            )),
            Brush("br_roller_ink", "Roller Ink", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-brick.png",
                customGrainPath = "asset://brushes/textures/tx-grunge.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-brick.png"
            )),
            Brush("proc_spectra", "Prism", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-infrared-oil.jpg",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-infrared-oil.jpg"
            )),
            Brush("proc_tamar", "Estuary", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-impress.png",
                customGrainPath = "asset://brushes/textures/tx-oil-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-impress.jpg"
            )),
            Brush("proc_old_brush", "Old Brush", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-block.jpg",
                customGrainPath = "asset://brushes/textures/tx-hard-ink.png",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-block.png"
            )),
            Brush("proc_dry_brush", "Dry Brush", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-block.jpg",
                customGrainPath = "asset://brushes/textures/tx-bark.png",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-block.png"
            )),
            Brush("proc_stucco", "Stucco", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-square-dots.jpg",
                customGrainPath = "asset://brushes/textures/tx-brick.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-square-dots.png"
            )),
            Brush("proc_oil_paint", "Oil Paint", "Painting", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.30f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink-sponge.png",
                customGrainPath = "asset://brushes/textures/tx-rust-3.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-sponge.png"
            )),
            Brush("proc_turpentine", "Turpentine", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flat-brush.jpg",
                customGrainPath = "asset://brushes/textures/tx-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-flat-brush.png"
            )),
            Brush("proc_gouache", "Gouache", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-grain.png",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-drop-2.jpg"
            )),
            Brush("proc_fresco", "Fresco", "Painting", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-bleed.png",
                customGrainPath = "asset://brushes/textures/tx-grunge.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-water-bleed-2.png"
            )),
            Brush("proc_watercolor", "Watercolor", "Painting", BrushProperties(
                type = BrushType.Paint, size = 42f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.30f, wetness = 0.80f, grainScale = 0.80f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink2.jpg",
                customGrainPath = "asset://brushes/textures/tx-cardboard.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink2.png"
            ))
        )
    )

    private fun createArtisticSet(): BrushSet = BrushSet(
        "artistic", "🎭 فنية", listOf(
            Brush("proc_wild_light", "Flare Burst", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-stained-paper-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-water-blotch-1.png"
            )),
            Brush("proc_aurora", "Polar Glow", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-simple-blotch.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-simple-blotch.jpg"
            )),
            Brush("br_leather_grain", "Leather Grain", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-haggard-oval.png",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-haggard-oval.jpg"
            )),
            Brush("br_alpine_grain", "Alpine Grain", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-oil-dash.png",
                customGrainPath = "asset://brushes/textures/tx-wax-crayon-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-oil-dash-2.jpg"
            )),
            Brush("br_cascade", "Cascade", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-oil-dash-3.png",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-oil-dash-3.jpg"
            )),
            Brush("br_chalk_line", "Chalk Line", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab.jpg",
                customGrainPath = "asset://brushes/textures/tx-raw-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("proc_old_beach", "Sand Drift", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-3.jpg",
                customGrainPath = "asset://brushes/textures/tx-wax-crayon-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-3.jpg"
            )),
            Brush("br_shoreline", "Shoreline", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-2.png",
                customGrainPath = "asset://brushes/textures/tx-distressed-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("br_bark_grain", "Bark Grain", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-liquid-lines.png",
                customGrainPath = "asset://brushes/textures/tx-distressed-canvas-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-liquid-lines.jpg"
            )),
            Brush("br_spotted_grain", "Spotted Grain", "Artistic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, wetness = 0.45f, grainScale = 0.80f, scatter = 0.08f, angleJitter = 15f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-oil-dab.png",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-oil-dab.jpg"
            ))
        )
    )

    private fun createCalligraphySet(): BrushSet = BrushSet(
        "calligraphy", "🔤 الخطوط", listOf(
            Brush("br_summit_grain", "Summit Grain", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-rough.jpg",
                customGrainPath = "asset://brushes/textures/tx-pastel-board-coarse.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-rough-2.jpg"
            )),
            Brush("proc_odeon", "Stage Glow", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-blank.png"
            )),
            Brush("proc_monoline", "Monoline", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 14f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.50f, grainScale = 0f, pressureSizeScale = 0f, pressureOpacityScale = 0f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hard.png"
            )),
            Brush("proc_chalk", "Chalk", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-paint-dab-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-6b.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-paint-dab-1.png"
            )),
            Brush("proc_blotchy", "Blotchy", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-intense-water.jpg",
                customGrainPath = "asset://brushes/textures/tx-stained-paper-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-intense-water.png"
            )),
            Brush("proc_streaks", "Streaks", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-streaky.jpg",
                customGrainPath = "asset://brushes/textures/tx-stained-paper-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-streaky.png"
            )),
            Brush("proc_water_pen", "Water Pen", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-rough.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-mess.png"
            )),
            Brush("proc_shale_brush", "Shale Brush", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-paint-dab-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-dirty-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-paint-dab-1.png"
            )),
            Brush("proc_brush_pen", "Brush Pen", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-oval.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-oval.png"
            )),
            Brush("proc_script", "Script", "Calligraphy", BrushProperties(
                type = BrushType.Ink, size = 22f, opacity = 1f, spacing = 0.015f,
                smoothing = 0.55f, grainScale = 0f, pressureSizeScale = 0.80f, pressureOpacityScale = 0.05f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            ))
        )
    )

    private fun createAirbrushingSet(): BrushSet = BrushSet(
        "airbrushing", "💨 البخاخ", listOf(
            Brush("proc_soft_brush", "Soft Brush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_medium_brush", "Medium Brush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_medium_hard_brush", "Medium Hard Brush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_hard_brush", "Hard Brush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hard.png"
            )),
            Brush("proc_soft_blend", "Soft Blend", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ultra-soft-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-ultra-soft-2.png"
            )),
            Brush("proc_medium_blend", "Medium Blend", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_medium_hard_blend", "Medium Hard Blend", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_hard_blend", "Hard Blend", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hard.png"
            )),
            Brush("proc_soft_airbrush", "Soft Airbrush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 60f, opacity = 0.50f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.85f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_medium_airbrush", "Medium Airbrush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_medium_hard_airbrush", "Medium Hard Airbrush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.65f, spacing = 0.020f,
                smoothing = 0.35f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.75f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_hard_airbrush", "Hard Airbrush", "Airbrushing", BrushProperties(
                type = BrushType.Airbrush, size = 38f, opacity = 0.90f, spacing = 0.020f,
                smoothing = 0.30f, grainScale = 0f, pressureSizeScale = 0.30f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hard.png"
            ))
        )
    )

    private fun createTexturesSet(): BrushSet = BrushSet(
        "textures", "🧱 الخامات", listOf(
            Brush("proc_tessellated", "Tessellated", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-drop.jpg",
                customGrainPath = "asset://brushes/textures/tx-tessellated.png",
                thumbnailPath = "asset://brushes/thumbnails/th-tessellated.jpg"
            )),
            Brush("proc_tarkine", "Old Growth", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-drop.jpg",
                customGrainPath = "asset://brushes/textures/tx-acrylic-undercoat.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-drop-2.jpg"
            )),
            Brush("br_feather_ink", "Feather Ink", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-drop.jpg",
                customGrainPath = "asset://brushes/textures/tx-acrylic-grit.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-drop-2.jpg"
            )),
            Brush("br_fine_feather", "Fine Feather", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-impress.png",
                customGrainPath = "asset://brushes/textures/tx-crunchy-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-impress.jpg"
            )),
            Brush("br_paperbark", "Paperbark", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-rough-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-warby-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-rough-2.jpg"
            )),
            Brush("br_still_water", "Still Water", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-drop-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-kingston-beach.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-drop-2.jpg"
            )),
            Brush("br_rect_grid", "Rect Grid", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gouache-streak.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-streak.jpg"
            )),
            Brush("proc_grid", "Grid", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-grid.png",
                thumbnailPath = "asset://brushes/thumbnails/th-grid.png"
            )),
            Brush("proc_decimals", "Dot Matrix", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-hard.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_diagonal", "Diagonal", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-diagonal.png",
                thumbnailPath = "asset://brushes/thumbnails/th-diagonal.png"
            )),
            Brush("proc_victorian", "Ornate", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-floral.png",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_wood", "Wood", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-wood.png",
                thumbnailPath = "asset://brushes/thumbnails/th-wood.png"
            )),
            Brush("proc_cubes", "Cubes", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hexagon.png",
                customGrainPath = "asset://brushes/textures/tx-cubes.png",
                thumbnailPath = "asset://brushes/thumbnails/th-cubes.png"
            )),
            Brush("proc_rosette", "Rosette", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-dof-dots-light.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("proc_grunge", "Grunge", "Textures", BrushProperties(
                type = BrushType.Paint, size = 55f, opacity = 0.85f, spacing = 0.050f,
                smoothing = 0.25f, grainScale = 0.95f, angleJitter = 25f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flat-brush2.jpg",
                customGrainPath = "asset://brushes/textures/tx-grunge.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-grunge.png"
            ))
        )
    )

    private fun createAbstractSet(): BrushSet = BrushSet(
        "abstract", "🌀 تجريدية", listOf(
            Brush("proc_stickman", "Figure", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-solid-rectangles.png",
                thumbnailPath = "asset://brushes/thumbnails/th-solid-rectangles.jpg"
            )),
            Brush("proc_storm_bay", "Storm Wash", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-seashell.jpg",
                customGrainPath = "asset://brushes/textures/tx-kingston-beach-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-seashell.jpg"
            )),
            Brush("proc_waveform", "Waveform", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-waveform.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-waveform.png"
            )),
            Brush("proc_membrane", "Cell Wall", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-synthetic-round.jpg",
                customGrainPath = "asset://brushes/textures/tx-membrane.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-membrane.png"
            )),
            Brush("br_tri_grid", "Tri Grid", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-tri-grid.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-tri-grid.png"
            )),
            Brush("br_hex_grid", "Hex Grid", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hex.png",
                customGrainPath = "asset://brushes/textures/tx-hexagon.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hex.png"
            )),
            Brush("proc_marble", "Marble", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-scribble-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-bark.png",
                thumbnailPath = "asset://brushes/thumbnails/th-scribble-2.png"
            )),
            Brush("proc_opticon", "Optic Flare", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-reticles.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-reticles.png"
            )),
            Brush("br_needle_dot", "Needle Dot", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-spike.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-spike.png"
            )),
            Brush("proc_polygons", "Polygons", "Abstract", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-cube.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-cube.png"
            ))
        )
    )

    private fun createCharcoalsSet(): BrushSet = BrushSet(
        "charcoals", "🪵 الفحم", listOf(
            Brush("proc_2b_compressed", "2B Compressed", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-soft.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-vine.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-soft.png"
            )),
            Brush("proc_4b_compressed", "4B Compressed", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-macro.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-corse.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-macro.png"
            )),
            Brush("proc_6b_compressed", "6B Compressed", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-soft.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-6b.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-soft.png"
            )),
            Brush("proc_vine_charcoal", "Vine Charcoal", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-macro.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-vine.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-macro.png"
            )),
            Brush("proc_willow_charcoal", "Willow Charcoal", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-willow.jpg",
                customGrainPath = "asset://brushes/textures/tx-willow-grain.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-willow.png"
            )),
            Brush("proc_carbon_stick", "Carbon Stick", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-whisp.jpg",
                customGrainPath = "asset://brushes/textures/tx-graphite.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-whisp.png"
            )),
            Brush("proc_charcoal_block", "Charcoal Block", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-block.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-rough.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-block.png"
            )),
            Brush("proc_burnt_tree", "Charred Branch", "Charcoals", BrushProperties(
                type = BrushType.Pencil, size = 34f, opacity = 0.90f, spacing = 0.045f,
                smoothing = 0.10f, grainScale = 0.90f, angleJitter = 20f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.65f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-shards.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-burnt.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-shards.png"
            ))
        )
    )

    private fun createElementsSet(): BrushSet = BrushSet(
        "elements", "🌊 العناصر", listOf(
            Brush("proc_smoke", "Smoke", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-tentacle.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-tentacle.png"
            )),
            Brush("proc_flames", "Flames", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-tendril.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-tendril.png"
            )),
            Brush("proc_water", "Water", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-refract.png",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_oceans", "Oceans", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-ocean.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_driven_snow", "Snow Drift", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-flakes.png",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_hard_rain", "Hard Rain", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-directional-rain.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-directional-rain.png"
            )),
            Brush("proc_crystals", "Crystals", "Elements", BrushProperties(
                type = BrushType.Paint, size = 65f, opacity = 0.50f, spacing = 0.050f,
                smoothing = 0.20f, grainScale = 0.75f, scatter = 0.35f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-crystal-shape.jpg",
                customGrainPath = "asset://brushes/textures/tx-aggate.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-crystal-shape.png"
            )),
            Brush("proc_clouds", "Clouds", "Elements", BrushProperties(
                type = BrushType.Paint, size = 70f, opacity = 0.45f, spacing = 0.060f,
                smoothing = 0.20f, grainScale = 0.80f, scatter = 0.40f, angleJitter = 180f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.60f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-cloud-shape.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-clouds.png"
            ))
        )
    )

    private fun createSpraypaintsSet(): BrushSet = BrushSet(
        "spraypaints", "🥫 الرذاذ", listOf(
            Brush("proc_ultrafine_nozzle", "Ultrafine Nozzle", "Spraypaints", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ultra-soft.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ultra-soft.png"
            )),
            Brush("proc_fine_nozzle", "Fine Nozzle", "Spraypaints", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-burst.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-burst.png"
            )),
            Brush("proc_medium_nozzle", "Medium Nozzle", "Spraypaints", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-fine-spray.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-fine-spray.png"
            )),
            Brush("proc_fat_nozzle", "Fat Nozzle", "Spraypaints", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-fat.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-fat.png"
            )),
            Brush("proc_splatter", "Splatter", "Spraypaints", BrushProperties(
                type = BrushType.Airbrush, size = 65f, opacity = 0.90f, spacing = 0.220f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.30f, scatter = 0.90f, angleJitter = 180f, sizeJitter = 0.50f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-splash-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-aggate.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gouache-splatter-2.jpg"
            )),
            Brush("proc_flicks", "Flicks", "Spraypaints", BrushProperties(
                type = BrushType.Airbrush, size = 50f, opacity = 0.85f, spacing = 0.280f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.30f, scatter = 1.20f, angleJitter = 180f, sizeJitter = 0.60f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flicks.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-flicks.png"
            )),
            Brush("proc_burst", "Burst", "Spraypaints", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-burst.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-burst.png"
            )),
            Brush("proc_drip", "Drip", "Spraypaints", BrushProperties(
                type = BrushType.Paint, size = 36f, opacity = 0.88f, spacing = 0.035f,
                smoothing = 0.30f, wetness = 0.55f, grainScale = 0.70f, pressureSizeScale = 0.65f, pressureOpacityScale = 0.35f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-drip.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-drip.png"
            ))
        )
    )

    private fun createTouchupsSet(): BrushSet = BrushSet(
        "touchups", "✨ الرتوش", listOf(
            Brush("br_soft_wash", "Soft Wash", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("br_wattle_dot", "Wattle Dot", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium-hard.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium-hard.png"
            )),
            Brush("br_fern_texture", "Fern Texture", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-square.jpg",
                customGrainPath = "asset://brushes/textures/tx-bark.png",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-square.jpg"
            )),
            Brush("proc_sawtooth", "Sawtooth", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-5.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-5.jpg"
            )),
            Brush("br_dark_timber", "Dark Timber", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("proc_tawny", "Amber Grain", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-charcoal-whisp.jpg",
                customGrainPath = "asset://brushes/textures/tx-crackle.png",
                thumbnailPath = "asset://brushes/thumbnails/th-charcoal-whisp.png"
            )),
            Brush("br_coastal_wash", "Coastal Wash", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab.jpg",
                customGrainPath = "asset://brushes/textures/tx-procedural-noise.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("br_landscape_wash", "Landscape Wash", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab.jpg",
                customGrainPath = "asset://brushes/textures/tx-stone-wall-2.png",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-2.jpg"
            )),
            Brush("br_island_grain", "Island Grain", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-leopard-print.png",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_stubble", "Stubble", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-stubble.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-stubble.png"
            )),
            Brush("proc_noise_brush", "Noise Brush", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-noise.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_old_skin", "Aged Skin", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-leather.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_rough_skin", "Rough Skin", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-orange-peal.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_zombie_skin", "Cracked Skin", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-zombie.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_fine_hair", "Fine Hair", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink2.jpg",
                customGrainPath = "asset://brushes/textures/tx-fine-hair.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-fine-hair.png"
            )),
            Brush("proc_flowing_hair", "Flowing Hair", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flowing.jpg",
                customGrainPath = "asset://brushes/textures/tx-bark.png",
                thumbnailPath = "asset://brushes/thumbnails/th-flowing.png"
            )),
            Brush("proc_short_hair", "Short Hair", "Touchups", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-short-hair.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-short-hair.png"
            ))
        )
    )

    private fun createRetroSet(): BrushSet = BrushSet(
        "retro", "📻 كلاسيكية", listOf(
            Brush("proc_myrtle", "Leaf Grain", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp.png",
                customGrainPath = "asset://brushes/textures/tx-bacteria.png",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-2.jpg"
            )),
            Brush("br_nectar_dot", "Nectar Dot", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp-4.png",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-4.jpg"
            )),
            Brush("br_star_field", "Star Field", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp.png",
                customGrainPath = "asset://brushes/textures/tx-memories.png",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-2.jpg"
            )),
            Brush("br_rough_wash", "Rough Wash", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-stamp.png",
                customGrainPath = "asset://brushes/textures/tx-dof-dots.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-stamp-2.jpg"
            )),
            Brush("proc_wedge_tail", "Wing Sweep", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-rough.jpg",
                customGrainPath = "asset://brushes/textures/tx-glitch-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-rough-2.jpg"
            )),
            Brush("proc_groovy", "Retro Wave", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-gloop.jpg",
                customGrainPath = "asset://brushes/textures/tx-sprial.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-gloop.png"
            )),
            Brush("proc_bolt", "Bolt", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-lightning.jpg",
                customGrainPath = "asset://brushes/textures/tx-oil-canvas.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-lightning.png"
            )),
            Brush("proc_fever", "Heat Haze", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-70s-fever.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_newsprint", "Newsprint", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-worn-dots.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_buzzz", "Buzz Line", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-lattice.jpg",
                customGrainPath = "asset://brushes/textures/tx-badspray.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-lattice.png"
            )),
            Brush("proc_disco", "Mirror Ball", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-disco-ball.png",
                customGrainPath = "asset://brushes/textures/tx-badspray.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-disco-ball.png"
            )),
            Brush("proc_rad", "Radiant", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-torn.jpg",
                customGrainPath = "asset://brushes/textures/tx-army.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-torn.png"
            )),
            Brush("proc_flower_power", "Bloom", "Retro", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flower.jpg",
                customGrainPath = "asset://brushes/textures/tx-badspray.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-flower.png"
            ))
        )
    )

    private fun createLuminanceSet(): BrushSet = BrushSet(
        "luminance", "💡 الإضاءة", listOf(
            Brush("proc_flare", "Flare", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-flare.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-flare.png"
            )),
            Brush("proc_lightpen", "Lightpen", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-pen-glow.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-pen-glow.png"
            )),
            Brush("proc_lightbrush", "Lightbrush", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-brush-glow.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-brush-glow.png"
            )),
            Brush("proc_pulse", "Pulse", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-blinds-glow.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_bokeh_lights", "Bokeh Lights", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-bokeh.png",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-bokeh.png"
            )),
            Brush("proc_lightleak", "Lightleak", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-lightpeaks.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_glimmer", "Shimmer", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-star.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-star.png"
            )),
            Brush("proc_nebula", "Star Cloud", "Luminance", BrushProperties(
                type = BrushType.Paint, size = 32f, opacity = 0.95f, spacing = 0.020f,
                smoothing = 0.40f, grainScale = 0.20f, pressureSizeScale = 0.70f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-neblua.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-neblua.png"
            ))
        )
    )

    private fun createIndustrialSet(): BrushSet = BrushSet(
        "industrial", "⚙️ صناعية", listOf(
            Brush("proc_caged", "Cage Grid", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-cage.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            )),
            Brush("proc_heavy_metal", "Steel Edge", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-rust-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-rust-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-rust-2.png"
            )),
            Brush("proc_wasteland", "Barren", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-clay.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-mess-2.png"
            )),
            Brush("proc_twisted_tree", "Gnarled Branch", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-bark.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-mess-2.png"
            )),
            Brush("proc_stone_wall", "Stone Wall", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-stone-wall.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-stone-wall.png"
            )),
            Brush("proc_rusted_decay", "Rust", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.jpg",
                customGrainPath = "asset://brushes/textures/tx-rust-3.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-mess-2.png"
            )),
            Brush("proc_concrete_block", "Concrete Block", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-concreate.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_corrugated_iron", "Corrugated Iron", "Industrial", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-soft.png",
                customGrainPath = "asset://brushes/textures/tx-corrugated.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-soft.png"
            ))
        )
    )

    private fun createOrganicSet(): BrushSet = BrushSet(
        "organic", "🌿 عضوية", listOf(
            Brush("proc_spires", "Ridge Line", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-garden-rocks.jpg",
                customGrainPath = "asset://brushes/textures/tx-cliff-face.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-garden-rocks.jpg"
            )),
            Brush("proc_rainforest", "Canopy", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-garden-bush.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-garden-bush.jpg"
            )),
            Brush("proc_snow_gum", "Pale Bark", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-garden-leaf.png",
                customGrainPath = "asset://brushes/textures/tx-clay.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-garden-leaf.jpg"
            )),
            Brush("proc_mountain_ash", "Tall Timber", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-rough.jpg",
                customGrainPath = "asset://brushes/textures/tx-cicero-tree.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-rough-2.jpg"
            )),
            Brush("proc_paper_daisy", "Petal Dot", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-garden-gum.png",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-garden-gum.jpg"
            )),
            Brush("proc_swordgrass", "Blade Grass", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-garden-grass.png",
                customGrainPath = "asset://brushes/textures/tx-oil-board.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-garden-grass.jpg"
            )),
            Brush("proc_wildgrass", "Wildgrass", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-wildgrass.jpg",
                customGrainPath = "asset://brushes/textures/tx-oil-board.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-wildgrass.jpg"
            )),
            Brush("proc_twig", "Twig", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-orbs.png",
                customGrainPath = "asset://brushes/textures/tx-paper-mush.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-orbs.png"
            )),
            Brush("proc_reed", "Reed", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blotch-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-charcoal-burnt.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-reed.png"
            )),
            Brush("proc_bamboo", "Bamboo", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-synthetic-round.jpg",
                customGrainPath = "asset://brushes/textures/tx-bark.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-synthetic-round.png"
            )),
            Brush("proc_sable", "Sable", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-chalk-stick.png",
                thumbnailPath = "asset://brushes/thumbnails/th-water-blotch-2.png"
            )),
            Brush("proc_hemp", "Hemp", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-mess.png",
                customGrainPath = "asset://brushes/textures/tx-canvas-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-mess.png"
            )),
            Brush("proc_clay", "Clay", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-wet-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-clay.png"
            )),
            Brush("proc_cotton", "Cotton", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-crusty.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-cotton-paper.jpg"
            )),
            Brush("proc_hessian", "Hessian", "Organic", BrushProperties(
                type = BrushType.Paint, size = 38f, opacity = 0.85f, spacing = 0.040f,
                smoothing = 0.25f, grainScale = 0.80f, scatter = 0.15f, angleJitter = 30f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-hessian.png",
                thumbnailPath = "asset://brushes/thumbnails/th-hessian.png"
            ))
        )
    )

    private fun createWaterSet(): BrushSet = BrushSet(
        "water", "💧 مائية", listOf(
            Brush("proc_water_bleed", "Water Bleed", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-wet-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-water-bleed.png"
            )),
            Brush("proc_wet_sponge", "Wet Sponge", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-blotch-2.jpg",
                customGrainPath = "asset://brushes/textures/tx-clouds-2.png",
                thumbnailPath = "asset://brushes/thumbnails/th-blotch-2.png"
            )),
            Brush("proc_wet_glaze", "Wet Glaze", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-water-blotch-1.png"
            )),
            Brush("proc_wash", "Wash", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-bleed.jpg",
                customGrainPath = "asset://brushes/textures/tx-blank.png",
                thumbnailPath = "asset://brushes/thumbnails/th-dry-acrylic-wash.jpg"
            )),
            Brush("proc_mad_splashes", "Wild Splash", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-splash-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-splash-1.png"
            )),
            Brush("proc_water_flicks", "Water Flicks", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-flicks.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-water-flicks.png"
            )),
            Brush("proc_blotch", "Blotch", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-splash-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-blotch.png"
            )),
            Brush("proc_water_drip", "Water Drip", "Water", BrushProperties(
                type = BrushType.Paint, size = 45f, opacity = 0.65f, spacing = 0.040f,
                smoothing = 0.35f, wetness = 0.75f, grainScale = 0.70f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-drip-2.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-drip-2.png"
            ))
        )
    )

    private fun createEarthSet(): BrushSet = BrushSet(
        "earth", "🌍 أرضية", listOf(
            Brush("proc_wheatgrass", "Wheat Blade", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-summer-shape.png",
                customGrainPath = "asset://brushes/textures/tx-summer-grain.png",
            )),
            Brush("proc_limestone", "Limestone", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-limestone-shape.png",
                customGrainPath = "asset://brushes/textures/tx-limestone-grain.png",
            )),
            Brush("proc_smoky_quartz", "Smoky Quartz", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-sediment-shape.png",
                customGrainPath = "asset://brushes/textures/tx-sediment-grain.png",
            )),
            Brush("proc_palm_husk", "Palm Husk", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-palm-shape.png",
                customGrainPath = "asset://brushes/textures/tx-palm-grain.png",
            )),
            Brush("proc_flourish", "Flourish", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-sable-shape.png",
                customGrainPath = "asset://brushes/textures/tx-sable-grain.png",
            )),
            Brush("proc_sandstone", "Sandstone", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-earth-shape.png",
                customGrainPath = "asset://brushes/textures/tx-earth-grain.png",
            )),
            Brush("proc_river_silt", "Silt Wash", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-marble-shape.png",
                customGrainPath = "asset://brushes/textures/tx-marble-grain.png",
            )),
            Brush("proc_volcanic_ash", "Volcanic Ash", "Earth", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-volcanic-shape.png",
                customGrainPath = "asset://brushes/textures/tx-volcanic-grain.png",
            ))
        )
    )

    private fun createSpecialBrushesSet(): BrushSet = BrushSet(
        "special_brushes", "🌟 فرش خاصة", listOf(
            Brush("br_chalk_stick_2", "Chalk Stick", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-chalk-stick.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_damp_brush", "Damp Brush", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink-sponge.png",
                customGrainPath = "asset://brushes/textures/tx-brick.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink-sponge.png"
            )),
            Brush("proc_metallic_beach", "Metallic Sand", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-acrylic-dab-3.jpg",
                customGrainPath = "asset://brushes/textures/tx-wax-crayon-paper.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-acrylic-dab-3.jpg"
            )),
            Brush("br_metallic_cascade", "Metallic Cascade", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-oil-dash-3.png",
                customGrainPath = "asset://brushes/textures/tx-gouache-wash.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-oil-dash-3.jpg"
            )),
            Brush("proc_oriental_brush", "East Asian Brush", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-ink.jpg",
                customGrainPath = "asset://brushes/textures/tx-aggate.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-ink.png"
            )),
            Brush("proc_signature", "Signature", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-medium.png",
                customGrainPath = "asset://brushes/textures/tx-textured-ink.png",
                thumbnailPath = "asset://brushes/thumbnails/th-medium.png"
            )),
            Brush("proc_water_brush", "Water Brush", "Special Brushes", BrushProperties(
                type = BrushType.Paint, size = 30f, opacity = 0.90f, spacing = 0.035f,
                smoothing = 0.25f, grainScale = 0.75f, pressureSizeScale = 0.60f, pressureOpacityScale = 0.40f, 
                tipType = BrushTipType.CUSTOM,
                customTipPath = "asset://brushes/textures/tx-water-blotch-1.jpg",
                customGrainPath = "asset://brushes/textures/tx-stained-paper-1.jpg",
                thumbnailPath = "asset://brushes/thumbnails/th-water-blotch-1.png"
            ))
        )
    )

}
