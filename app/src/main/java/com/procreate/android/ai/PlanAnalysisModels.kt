package com.procreate.android.ai

import com.procreate.android.urban.model.UrbanToolType

/**
 * The vocabulary the vision model is allowed to answer in.
 *
 * Deliberately a closed list rather than free text: an open-ended label ("kind of a driveway")
 * cannot be turned into a drawing tool, and asking the model to emit our exact enum names invites
 * silent mismatches when it improvises. Each case maps to one real tool, so anything the model
 * returns either becomes a specific element or is rejected - never guessed at.
 */
enum class PlanFeature(val toolType: UrbanToolType, val arabicLabel: String) {
    MAIN_ROAD(UrbanToolType.ASPHALT_ROAD, "طريق رئيسي"),
    SECONDARY_ROAD(UrbanToolType.ASPHALT_ROAD, "طريق ثانوي"),
    DIRT_PATH(UrbanToolType.DIRT_PATH, "ممر ترابي"),
    SITE_BOUNDARY(UrbanToolType.SITE_BOUNDARY, "حدود الموقع"),
    BUILDING(UrbanToolType.MODERN_BUILDING, "مبنى"),
    HERITAGE_BUILDING(UrbanToolType.HERITAGE_BUILDING, "مبنى تراثي"),
    RUINED_BUILDING(UrbanToolType.POLLUTION_RUIN, "مبنى متهدم"),
    PLAZA(UrbanToolType.PLAZA_HATCH, "ساحة عامة"),
    GREEN_AREA(UrbanToolType.FARM_HATCH, "مساحة خضراء / مزرعة"),
    CONTOUR(UrbanToolType.CONTOUR_LINE, "خط كونتور"),
    MAIN_AXIS(UrbanToolType.PRIMARY_AXIS, "محور رئيسي"),
    SECONDARY_AXIS(UrbanToolType.SECONDARY_AXIS, "محور ثانوي"),
    ENTRY(UrbanToolType.ENTRY_ARROW, "مدخل"),
    LIGHT_POLE(UrbanToolType.INFRA_LIGHT, "عمود إنارة"),
    WATER(UrbanToolType.INFRA_WATER, "خزان / بئر مياه");

    companion object {
        /** Case- and separator-insensitive, because models are inconsistent about both. */
        fun parse(raw: String?): PlanFeature? {
            val key = raw?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_') ?: return null
            return entries.firstOrNull { it.name == key }
        }
    }
}

/**
 * How the feature is drawn.
 *
 * [RECTANGLE] and [CIRCLE] exist because describing an obviously rectangular building as a generic
 * polygon makes the model emit four corners that are each slightly off, so the building arrives
 * visibly skewed. Asking for centre/size/angle instead lets it state the shape it actually means,
 * and the exact corners are then computed here rather than estimated by the model.
 */
enum class PlanGeometry { LINE, POLYGON, POINT, RECTANGLE, CIRCLE;

    companion object {
        fun parse(raw: String?): PlanGeometry? {
            val key = raw?.trim()?.uppercase() ?: return null
            return when (key) {
                "POLYLINE" -> LINE
                "MARKER" -> POINT
                else -> entries.firstOrNull { it.name == key }
            }
        }
    }
}

/** Centre/size/angle form for a rectangle, converted to corners by the mapper. */
data class RectangleSpec(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float
)

/** Centre/radius form for a circle, converted to a polygon by the mapper. */
data class CircleSpec(val centerX: Float, val centerY: Float, val radius: Float)

/**
 * One element the model believes it found.
 *
 * Coordinates are normalised to 0..1 of the image, never pixels: the image is downscaled before
 * upload, so any absolute coordinate the model returned would refer to a size the app no longer
 * has. Normalised values survive that and map onto any canvas.
 */
data class DetectedFeature(
    val feature: PlanFeature,
    val geometry: PlanGeometry,
    val points: List<Pair<Float, Float>>,
    val confidence: Float,
    val note: String? = null,
    val rectangle: RectangleSpec? = null,
    val circle: CircleSpec? = null,
    /** Set by the model when the shape runs off the edge of its tile, marking it as a candidate
     * for joining with a fragment from the neighbouring tile. A route that genuinely ends inside
     * the plan must not carry this, or real endpoints get welded to unrelated geometry. */
    val clippedAtEdge: Boolean = false
) {
    /** True when the shape has enough points to be drawable as the kind of thing it claims to be. */
    val isDrawable: Boolean
        get() = when (geometry) {
            PlanGeometry.POINT -> points.size == 1
            PlanGeometry.LINE -> points.size >= 2
            PlanGeometry.POLYGON -> points.size >= 3
            PlanGeometry.RECTANGLE -> rectangle != null
            PlanGeometry.CIRCLE -> circle != null
        }
}

/**
 * Result of one analysis run. [warnings] carries anything that was rejected during parsing so the
 * review screen can say what was discarded rather than quietly dropping it - a model that returned
 * ten features of which six were unusable is important information, not noise.
 */
data class PlanAnalysis(
    val features: List<DetectedFeature>,
    val warnings: List<String> = emptyList(),
    val rawModelText: String? = null
)

/** Where the analysis came from, so the UI can be explicit about what left the device. */
enum class AnalysisProvider(val displayName: String, val privacyNote: String) {
    GEMINI(
        "Google Gemini",
        "تُرفع الصورة إلى خوادم Google. الطبقة المجانية تعني أن Google قد تستخدم البيانات لتحسين منتجاتها."
    ),
    NVIDIA(
        "NVIDIA NIM",
        "تُرفع الصورة إلى خوادم NVIDIA لمعالجتها."
    )
}
