package com.procreate.android.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns whatever the model actually returned into validated features.
 *
 * Written defensively on purpose. A vision model asked for JSON routinely wraps it in a markdown
 * fence, adds a sentence before it, invents a key, returns a coordinate outside the image, or emits
 * a label that is not in the vocabulary. None of that should crash the feature or - worse - become
 * a wrong element silently drawn onto someone's site plan, so every departure is either repaired
 * where that is unambiguous or rejected with a recorded reason.
 */
object PlanAnalysisParser {

    /**
     * @param pixelWidth/[pixelHeight] when non-null, the model was asked for full-plan pixel
     * coordinates rather than the 0-2000 grid, and values are normalised against these instead.
     * Tiled extraction needs this: a tile reports where things sit in the whole drawing, and the
     * whole drawing's size is the only thing those numbers can be measured against.
     */
    fun parse(modelText: String, pixelWidth: Int? = null, pixelHeight: Int? = null): PlanAnalysis {
        val warnings = mutableListOf<String>()
        val pixelSpace = if (pixelWidth != null && pixelHeight != null && pixelWidth > 0 && pixelHeight > 0) {
            pixelWidth.toDouble() to pixelHeight.toDouble()
        } else null
        var truncated = false
        val json = extractJsonObject(modelText)
            ?: repairTruncated(modelText)?.also { truncated = true }
            ?: return PlanAnalysis(
                features = emptyList(),
                warnings = listOf("لم يُرجع النموذج بنية JSON صالحة."),
                rawModelText = modelText
            )
        if (truncated) warnings += "انقطع رد النموذج؛ أُنقذت العناصر المكتملة فقط."

        val array = json.optJSONArray("elements")
            ?: json.optJSONArray("features")
            ?: return PlanAnalysis(
                features = emptyList(),
                warnings = listOf("لا يحتوي رد النموذج على قائمة عناصر."),
                rawModelText = modelText
            )

        val features = mutableListOf<DetectedFeature>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            when (val parsed = parseFeature(item, i, pixelSpace)) {
                is ParseOutcome.Success -> features += parsed.feature
                is ParseOutcome.Rejected -> warnings += parsed.reason
            }
        }
        return PlanAnalysis(features, warnings, modelText)
    }

    private sealed class ParseOutcome {
        data class Success(val feature: DetectedFeature) : ParseOutcome()
        data class Rejected(val reason: String) : ParseOutcome()
    }

    private fun parseFeature(item: JSONObject, index: Int, pixelSpace: Pair<Double, Double>?): ParseOutcome {
        val rawType = item.optString("type").ifBlank { item.optString("feature") }
        val feature = PlanFeature.parse(rawType)
            ?: return ParseOutcome.Rejected("العنصر ${index + 1}: نوع غير معروف «$rawType»")

        val geometry = PlanGeometry.parse(item.optString("geometry"))
            ?: defaultGeometryFor(feature)

        val confidenceValue = item.optDouble("confidence", 0.6).toFloat().coerceIn(0f, 1f)
        val noteValue = item.optString("label").ifBlank { null }

        // Rectangle and circle carry their shape in dedicated fields rather than a point list, so
        // they are resolved before any attempt to read points.
        if (geometry == PlanGeometry.RECTANGLE) {
            val spec = readRectangle(item, pixelSpace)
                ?: return ParseOutcome.Rejected(
                    "العنصر ${index + 1} (${feature.arabicLabel}): مستطيل بأبعاد غير صالحة"
                )
            return ParseOutcome.Success(
                DetectedFeature(feature, geometry, emptyList(), confidenceValue, noteValue, rectangle = spec)
            )
        }
        if (geometry == PlanGeometry.CIRCLE) {
            val spec = readCircle(item, pixelSpace)
                ?: return ParseOutcome.Rejected(
                    "العنصر ${index + 1} (${feature.arabicLabel}): دائرة بنصف قطر غير صالح"
                )
            return ParseOutcome.Success(
                DetectedFeature(feature, geometry, emptyList(), confidenceValue, noteValue, circle = spec)
            )
        }

        val pointsArray = item.optJSONArray("points")
            ?: item.optJSONArray("polygon")
            ?: item.optJSONArray("path")
            ?: return ParseOutcome.Rejected("العنصر ${index + 1} (${feature.arabicLabel}): بلا إحداثيات")

        val raw = mutableListOf<Pair<Double, Double>>()
        for (p in 0 until pointsArray.length()) {
            raw += readPoint(pointsArray, p) ?: continue
        }
        if (raw.isEmpty()) {
            return ParseOutcome.Rejected("العنصر ${index + 1} (${feature.arabicLabel}): بلا إحداثيات صالحة")
        }

        // Pixel-space answers are divided by the real image size; grid/percent answers by their own
        // detected scale. Getting this wrong is silent and total: leaving a pixel value such as 550
        // unscaled makes every point fail the 0..1 bounds check below, so every path is discarded
        // and the import produces nothing at all, with only a per-element warning to show for it.
        val scaleX = pixelSpace?.let { 1.0 / it.first } ?: detectScale(raw)
        val scaleY = pixelSpace?.let { 1.0 / it.second } ?: scaleX
        val points = mutableListOf<Pair<Float, Float>>()
        for ((rawX, rawY) in raw) {
            val x = (rawX * scaleX).toFloat()
            val y = (rawY * scaleY).toFloat()
            // A near-miss just outside the edge is a rounding artefact and is nudged back. Anything
            // well outside is a hallucinated coordinate: the element is not trustworthy and is
            // rejected whole rather than silently repaired into a plausible-looking wrong shape.
            if (x < -0.15f || x > 1.15f || y < -0.15f || y > 1.15f) {
                return ParseOutcome.Rejected(
                    "العنصر ${index + 1} (${feature.arabicLabel}): إحداثيات خارج حدود الصورة"
                )
            }
            points += x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
        }

        val candidate = DetectedFeature(feature, geometry, points, confidenceValue, noteValue)
        if (!candidate.isDrawable) {
            return ParseOutcome.Rejected(
                "العنصر ${index + 1} (${feature.arabicLabel}): نقاط غير كافية لرسم ${geometry.name}"
            )
        }
        return ParseOutcome.Success(candidate)
    }

    /** Accepts both `[x, y]` and `{"x":.., "y":..}`, which different models favour. */
    private fun readPoint(array: JSONArray, index: Int): Pair<Double, Double>? {
        array.optJSONArray(index)?.let { pair ->
            if (pair.length() < 2) return null
            return pair.optDouble(0) to pair.optDouble(1)
        }
        array.optJSONObject(index)?.let { obj ->
            if (!obj.has("x") || !obj.has("y")) return null
            return obj.optDouble("x") to obj.optDouble("y")
        }
        return null
    }

    /**
     * Decides whether a feature's coordinates are normalised (0..1) or percent (0..100), judged
     * across the whole shape rather than per point.
     *
     * A single value cannot distinguish the two: 4.5 is a perfectly ordinary percent coordinate and
     * also exactly what a hallucination looks like among normalised ones. Deciding per point meant
     * a stray 4.5 was quietly rescaled to 0.045 and drawn, instead of being recognised as the
     * nonsense it was. A genuine percent answer has most of its values above 1; a normalised answer
     * with one bad number does not.
     */
    internal fun detectScale(points: List<Pair<Double, Double>>): Double {
        val values = points.flatMap { listOf(it.first, it.second) }.filter { it.isFinite() }
        if (values.isEmpty()) return 1.0
        val largest = values.maxOf { kotlin.math.abs(it) }
        val aboveUnit = values.count { kotlin.math.abs(it) > 1.15 }
        // Most values above 1 means the answer is not normalised. Which grid it is follows from
        // how large the numbers get: the contract asks for 0-2000, but a model that reverted to
        // percent should still be understood rather than rejected wholesale.
        if (aboveUnit * 2 < values.size) return 1.0
        return when {
            largest > 110.0 -> 1.0 / GRID
            else -> 0.01
        }
    }

    /** The integer grid the extraction contract asks the model to work in. */
    internal const val GRID = 2000.0

    private fun readRectangle(item: JSONObject, pixelSpace: Pair<Double, Double>?): RectangleSpec? {
        val cx = item.optDouble("geometry_center_x", Double.NaN)
        val cy = item.optDouble("geometry_center_y", Double.NaN)
        val w = item.optDouble("geometry_width", Double.NaN)
        val h = item.optDouble("geometry_height", Double.NaN)
        if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite()) return null
        if (w <= 0.0 || h <= 0.0) return null
        val rotation = item.optDouble("geometry_rotation_degrees", 0.0).let { if (it.isFinite()) it else 0.0 }
        // Same division the point path needs: pixel answers divide by the image, grid answers by
        // the 0-2000 grid. Using GRID for a pixel answer silently misplaces and misscales the shape.
        val divX = pixelSpace?.first ?: GRID
        val divY = pixelSpace?.second ?: GRID
        return RectangleSpec(
            centerX = (cx / divX).toFloat(),
            centerY = (cy / divY).toFloat(),
            width = (w / divX).toFloat(),
            height = (h / divY).toFloat(),
            rotationDegrees = rotation.toFloat()
        )
    }

    private fun readCircle(item: JSONObject, pixelSpace: Pair<Double, Double>?): CircleSpec? {
        val cx = item.optDouble("geometry_center_x", Double.NaN)
        val cy = item.optDouble("geometry_center_y", Double.NaN)
        val r = item.optDouble("geometry_radius", Double.NaN)
        if (!cx.isFinite() || !cy.isFinite() || !r.isFinite() || r <= 0.0) return null
        val divX = pixelSpace?.first ?: GRID
        val divY = pixelSpace?.second ?: GRID
        return CircleSpec(
            centerX = (cx / divX).toFloat(),
            centerY = (cy / divY).toFloat(),
            // A radius has one length; the shorter axis keeps a circle from becoming an ellipse
            // on a non-square image.
            radius = (r / minOf(divX, divY)).toFloat()
        )
    }

    private fun defaultGeometryFor(feature: PlanFeature): PlanGeometry = when (feature) {
        PlanFeature.BUILDING, PlanFeature.HERITAGE_BUILDING, PlanFeature.RUINED_BUILDING,
        PlanFeature.PLAZA, PlanFeature.GREEN_AREA, PlanFeature.SITE_BOUNDARY -> PlanGeometry.POLYGON
        PlanFeature.LIGHT_POLE, PlanFeature.WATER -> PlanGeometry.POINT
        else -> PlanGeometry.LINE
    }

    /**
     * Salvages the complete elements from a reply that ran out of output tokens mid-array.
     *
     * A dense plan can genuinely exceed the model's reply budget, and the cut always lands inside
     * whichever element was being written. Discarding the whole answer would throw away a dozen
     * perfectly good features because the thirteenth was half-written, so the array is closed after
     * the last element that actually finished. The caller is told this happened - a silently
     * shortened result would leave the user believing the plan contained less than it does.
     */
    internal fun repairTruncated(text: String): JSONObject? {
        val elementsStart = text.indexOf("\"elements\"")
        if (elementsStart < 0) return null
        val arrayStart = text.indexOf('[', elementsStart)
        if (arrayStart < 0) return null

        // Walk the array tracking nesting, remembering where each top-level element closed.
        var depth = 0
        var lastCompleteEnd = -1
        var inString = false
        var escaped = false
        for (i in arrayStart until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    // depth 1 means we just closed an element and are back inside the array.
                    if (depth == 1 && c == '}') lastCompleteEnd = i
                    if (depth == 0) return null // the array was actually complete; not truncated
                }
            }
        }
        if (lastCompleteEnd < 0) return null

        val repaired = text.substring(0, lastCompleteEnd + 1) + "]}"
        return runCatching { JSONObject(repaired) }.getOrNull()
    }

    /**
     * Pulls the JSON object out of a reply that may be wrapped in prose or a markdown fence.
     * Scanning for balanced braces rather than taking the first '{' means an opening brace inside
     * an explanatory sentence does not truncate the real payload.
     */
    internal fun extractJsonObject(text: String): JSONObject? {
        val direct = runCatching { JSONObject(text) }.getOrNull()
        if (direct != null) return direct

        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val candidate = text.substring(start, i + 1)
                        runCatching { JSONObject(candidate) }.getOrNull()?.let { return it }
                        start = -1
                    }
                }
            }
        }
        return null
    }
}
