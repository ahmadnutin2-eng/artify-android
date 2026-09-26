package com.procreate.android.ai

import android.graphics.Bitmap
import android.util.Base64
import com.procreate.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a plan image to a hosted vision model and returns the structured reading.
 *
 * Two providers because their failure modes differ: Gemini gives far better plan comprehension but
 * a tight free quota (roughly ten calls a minute), while NVIDIA's endpoint - already wired into
 * this app for crash reports - allows a much higher rate at lower accuracy. If the preferred one
 * is unavailable or rate-limited the other is tried, so a quota reset is not a dead feature.
 *
 * Keys come from [AiKeyStore], which prefers the BuildConfig key (local.properties, developer
 * build) and otherwise uses one the user entered on this device. No key is ever compiled into a
 * shipped APK either way.
 */
class PlanVisionClient {

    sealed class Result {
        data class Success(val analysis: PlanAnalysis, val provider: AnalysisProvider) : Result()
        data class Failure(val message: String, val retryable: Boolean) : Result()
    }

    fun availableProviders(): List<AnalysisProvider> = buildList {
        if (AiKeyStore.geminiKey.isNotBlank()) add(AnalysisProvider.GEMINI)
        if (AiKeyStore.nvidiaKey.isNotBlank()) add(AnalysisProvider.NVIDIA)
    }

    /** Blocking; callers must already be off the main thread. */
    fun analyze(bitmap: Bitmap, preferred: AnalysisProvider? = null): Result {
        val providers = availableProviders().sortedByDescending { it == preferred }
        if (providers.isEmpty()) {
            return Result.Failure(
                "لم يتم إعداد مفتاح الذكاء الاصطناعي بعد. افتح إعدادات الذكاء الاصطناعي وأدخل " +
                    "مفتاح Google Gemini الخاص بك (مجاني) لتفعيل تحليل المخططات.",
                retryable = false
            )
        }

        val imageBase64 = encode(bitmap)
        var lastFailure: Result.Failure? = null
        for (provider in providers) {
            when (val outcome = request(provider, imageBase64)) {
                is Result.Success -> return outcome
                is Result.Failure -> {
                    lastFailure = outcome
                    // Only fall through to the next provider when the problem is this provider's
                    // (quota, outage). A malformed request would fail identically everywhere.
                    if (!outcome.retryable) return outcome
                }
            }
        }
        return lastFailure ?: Result.Failure("تعذّر تحليل الصورة.", retryable = true)
    }

    data class PlanContext(val legend: List<String>, val description: String)

    /**
     * First pass over the whole plan, reading the legend.
     *
     * The legend is the only place the drawing states what its numbers mean, and a tile showing
     * "13." with no legend cannot be classified as retail by any amount of looking. Reading it once
     * and passing the text into every tile call is what lets those tiles be classified correctly.
     */
    fun readContext(bitmap: Bitmap): PlanContext? {
        val provider = availableProviders().firstOrNull() ?: return null
        val encoded = encode(bitmap)
        val body = when (provider) {
            AnalysisProvider.GEMINI -> geminiBody(CONTEXT_PROMPT, listOf(encoded), json = true)
            AnalysisProvider.NVIDIA -> nvidiaBody(CONTEXT_PROMPT, encoded)
        }
        val (url, headers) = endpointFor(provider)
        val response = post(url, body, headers) ?: return null
        val text = when (provider) {
            AnalysisProvider.GEMINI -> extractGeminiText(response)
            AnalysisProvider.NVIDIA -> extractOpenAiStyleText(response)
        } ?: return null

        val json = PlanAnalysisParser.extractJsonObject(text) ?: return null
        val legendArray = json.optJSONArray("legend_text") ?: json.optJSONArray("legend")
        val legend = buildList {
            if (legendArray != null) {
                for (i in 0 until legendArray.length()) {
                    legendArray.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        return PlanContext(legend, json.optString("overall_description"))
    }

    /**
     * One tile, one category.
     *
     * The tile's offset is stated in the prompt and the model answers in whole-image coordinates,
     * so nothing here has to translate results afterwards. Translation would be easy to write and
     * easy to get subtly wrong - an offset applied to the wrong tile produces a plan that looks
     * plausible and is entirely misplaced.
     */
    fun analyzeTile(
        tileBitmap: Bitmap,
        overviewBitmap: Bitmap,
        tile: PlanTile,
        fullWidth: Int,
        fullHeight: Int,
        category: ExtractionCategory,
        legend: List<String>
    ): Result {
        val providers = availableProviders()
        if (providers.isEmpty()) {
            return Result.Failure(
                "لم يتم إعداد مفتاح الذكاء الاصطناعي بعد.",
                retryable = false
            )
        }
        val prompt = tilePrompt(tile, fullWidth, fullHeight, category, legend)
        // The tile carries the detail being traced, so it goes at native resolution and high
        // quality. The overview is only there for orientation and stays small.
        val tileEncoded = encode(tileBitmap, TILE_MAX_EDGE, quality = 94)
        val overviewEncoded = encode(overviewBitmap, 640, quality = 80)

        var lastFailure: Result.Failure? = null
        for (provider in providers) {
            val body = when (provider) {
                // The overview rides along as a second image so the model can place the tile in the
                // whole plan; without it a tile of green is just green, not "the park".
                AnalysisProvider.GEMINI -> geminiBody(prompt, listOf(tileEncoded, overviewEncoded), json = true)
                AnalysisProvider.NVIDIA -> nvidiaBody(prompt, tileEncoded)
            }
            val (url, headers) = endpointFor(provider)
            val response = post(url, body, headers)
            if (response == null) {
                lastFailure = Result.Failure("${provider.displayName}: تعذّر الاتصال.", retryable = true)
                continue
            }
            val text = when (provider) {
                AnalysisProvider.GEMINI -> extractGeminiText(response)
                AnalysisProvider.NVIDIA -> extractOpenAiStyleText(response)
            }
            if (text.isNullOrBlank()) {
                lastFailure = Result.Failure(
                    "${provider.displayName}: رد فارغ (${extractFinishReason(response) ?: "بلا سبب"}).",
                    retryable = true
                )
                continue
            }
            return Result.Success(PlanAnalysisParser.parse(text, fullWidth, fullHeight), provider)
        }
        return lastFailure ?: Result.Failure("تعذّر تحليل البلاطة.", retryable = true)
    }

    /**
     * Asks the model to review its own reconstruction against the original.
     *
     * Returns plain prose, not geometry - this feeds the developer log rather than the drawing, so
     * a null here is simply a missing note and never affects what the user already has.
     */
    fun reviewReconstruction(original: Bitmap, reconstruction: Bitmap): String? {
        val provider = availableProviders().firstOrNull() ?: return null
        val body = when (provider) {
            AnalysisProvider.GEMINI -> geminiBody(
                PlanFeedbackRecorder.REVIEW_PROMPT,
                listOf(encode(original, 1024, 85), encode(reconstruction, 1024, 85)),
                json = false
            )
            // The OpenAI-shaped request carries one image, so the reconstruction alone is sent -
            // the comparison is weaker, but a partial note still beats none.
            AnalysisProvider.NVIDIA -> nvidiaBody(
                PlanFeedbackRecorder.REVIEW_PROMPT,
                encode(reconstruction, 1024, 85)
            )
        }
        val (url, headers) = endpointFor(provider)
        val response = post(url, body, headers) ?: return null
        return when (provider) {
            AnalysisProvider.GEMINI -> extractGeminiText(response)
            AnalysisProvider.NVIDIA -> extractOpenAiStyleText(response)
        }
    }

    private fun request(provider: AnalysisProvider, imageBase64: String): Result {
        return try {
            val (url, body, headers) = when (provider) {
                AnalysisProvider.GEMINI -> geminiRequest(imageBase64)
                AnalysisProvider.NVIDIA -> nvidiaRequest(imageBase64)
            }

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 90_000
            }
            connection.outputStream.use { it.write(body.toByteArray()) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                return Result.Failure(
                    "${provider.displayName}: فشل الطلب ($code) ${error.take(180)}",
                    // 429 is quota, 5xx is an outage: both are worth trying elsewhere.
                    retryable = code == 429 || code >= 500
                )
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val text = when (provider) {
                AnalysisProvider.GEMINI -> extractGeminiText(response)
                AnalysisProvider.NVIDIA -> extractOpenAiStyleText(response)
            }

            // Logged, not just shown: when this fails on a device it fails once, in the user's
            // hands, and a generic "invalid response" tells nobody anything. The actual reply is
            // the only thing that identifies whether the model refused, hit its token ceiling, or
            // answered in prose.
            // Debug builds only. The excerpt is the model's reading of the plan - geometry derived
            // from a picture the user chose - and logcat is the wrong place for anything derived
            // from user content on a device that is not the developer's own.
            if (BuildConfig.DEBUG) {
                android.util.Log.i(
                    LOG_TAG,
                    "${provider.name} http=$code textLen=${text?.length ?: -1} " +
                        "finish=${extractFinishReason(response)} " +
                        "head=${text?.take(300)?.replace('\n', ' ') ?: response.take(300)}"
                )
            }

            if (text.isNullOrBlank()) {
                return Result.Failure(
                    "${provider.displayName}: رد فارغ (${extractFinishReason(response) ?: "بلا سبب"}). " +
                        "قد يكون النموذج استهلك حد الرموز في التفكير.",
                    retryable = true
                )
            }

            Result.Success(PlanAnalysisParser.parse(text), provider)
        } catch (e: Exception) {
            Result.Failure("${provider.displayName}: ${e.message ?: "خطأ في الاتصال"}", retryable = true)
        }
    }

    // ---------------------------------------------------------------- providers

    private fun geminiRequest(imageBase64: String): Triple<String, String, Map<String, String>> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", PROMPT))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", imageBase64)
                    }))
                })
            }))
            put("generationConfig", JSONObject().apply {
                // Near-zero temperature: this is a reading task, and creative variation here means
                // the same plan analysed twice returns different geometry.
                put("temperature", 0.1)
                // A dense plan legitimately produces a lot of geometry. At 8192 the reply was
                // being cut off mid-array on a real site plan, which is silent data loss dressed
                // up as a parse error.
                put("maxOutputTokens", 32768)
                put("responseMimeType", "application/json")
                // Measured against a real site plan, this model spent 12,028 tokens "thinking" for
                // 3,346 tokens of answer - and on 2.5 both draw on the same budget, so a busier
                // image can burn the whole allowance before any JSON is written. Reading a plan
                // into a fixed schema is extraction, not open reasoning, so the budget is capped.
                put("thinkingConfig", JSONObject().put("thinkingBudget", 2048))
            })
        }.toString()
        return Triple(url, body, mapOf("x-goog-api-key" to AiKeyStore.geminiKey))
    }

    private fun nvidiaRequest(imageBase64: String): Triple<String, String, Map<String, String>> {
        val url = "https://integrate.api.nvidia.com/v1/chat/completions"
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", PROMPT))
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
            })
        }
        val body = JSONObject().apply {
            put("model", NVIDIA_MODEL)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            put("temperature", 0.1)
            put("max_tokens", 4096)
        }.toString()
        return Triple(url, body, mapOf("Authorization" to "Bearer ${AiKeyStore.nvidiaKey}"))
    }

    // ---------------------------------------------------------------- shared plumbing

    private fun endpointFor(provider: AnalysisProvider): Pair<String, Map<String, String>> =
        when (provider) {
            AnalysisProvider.GEMINI -> Pair(
                "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                mapOf("x-goog-api-key" to AiKeyStore.geminiKey)
            )
            AnalysisProvider.NVIDIA -> Pair(
                "https://integrate.api.nvidia.com/v1/chat/completions",
                mapOf("Authorization" to "Bearer ${AiKeyStore.nvidiaKey}")
            )
        }

    private fun geminiBody(prompt: String, images: List<String>, json: Boolean): String =
        JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    images.forEach { encoded ->
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", encoded)
                        }))
                    }
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 32768)
                if (json) put("responseMimeType", "application/json")
                put("thinkingConfig", JSONObject().put("thinkingBudget", 2048))
            })
        }.toString()

    private fun nvidiaBody(prompt: String, imageBase64: String): String = JSONObject().apply {
        put("model", NVIDIA_MODEL)
        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", prompt))
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
                })
            }
        )))
        put("temperature", 0.1)
        put("max_tokens", 4096)
    }.toString()

    private fun post(url: String, body: String, headers: Map<String, String>): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 120_000
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // Diagnostics belong in the developer's build. A provider's error body is not ours to
            // decide is harmless - it is whatever that service chose to echo back.
            if (BuildConfig.DEBUG) android.util.Log.w(LOG_TAG, "http=$code ${error.take(300)}")
            connection.disconnect()
            null
        } else {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            text
        }
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) android.util.Log.w(LOG_TAG, "request failed: ${e.message}")
        null
    }

    private fun tilePrompt(
        tile: PlanTile,
        fullWidth: Int,
        fullHeight: Int,
        category: ExtractionCategory,
        legend: List<String>
    ): String {
        val allowed = category.features.joinToString(", ") { it.name }
        val legendBlock = if (legend.isEmpty()) "" else
            "\nLEGEND FROM THE FULL PLAN (use it to classify correctly):\n" + legend.joinToString("\n")

        return """
            You are an expert CAD technician extracting survey-quality vector geometry.

            You are given TWO images:
            1. A TILE cropped from a larger site plan - this is what you must extract from.
            2. The FULL plan downscaled, for context only - do NOT extract from this one.

            TILE POSITION IN THE FULL PLAN
            The tile's top-left corner sits at X=${tile.offsetX}, Y=${tile.offsetY} in the full
            plan. The full plan is ${fullWidth} x ${fullHeight} pixels.

            COORDINATE SYSTEM - READ CAREFULLY
            Report every coordinate in FULL-PLAN pixel space, not tile space. A feature at the very
            top-left corner of this tile has coordinates (${tile.offsetX}, ${tile.offsetY}), NOT
            (0,0). All values are integers.
            $legendBlock

            EXTRACT ONLY THIS CATEGORY: ${category.name}
            ${category.instruction}

            Use only these "type" values: $allowed

            Report each distinct instance separately rather than grouping neighbours into one shape.

            STOP WHEN YOU RUN OUT OF REAL FEATURES.
            List only what you can actually see in this tile. When you have described every one,
            stop. Never continue producing entries to fill a quota, and never emit a run of
            identical shapes spaced on a regular grid - that is not a plan, and it is worse than
            returning nothing. An empty answer for an empty tile is a correct answer.

            DO NOT EXTRACT: the legend box, title block, scale bar, north arrow, plain text labels,
            or anything outside the drawn site plan.

            GEOMETRY FORMS
            - "RECTANGLE" for rectangular footprints: geometry_center_x, geometry_center_y,
              geometry_width, geometry_height, geometry_rotation_degrees.
            - "CIRCLE" for circular features: geometry_center_x, geometry_center_y, geometry_radius.
            - "POLYGON" for irregular areas: "points", up to 50, first point not repeated.
            - "POLYLINE" for routes: "points" along the CENTRELINE, up to 50.
            - "MARKER" for single points: exactly one point.

            CLIPPING
            Set "clipped_at_edge": true on any shape that runs off the edge of this tile, so it can
            be joined to its continuation in the neighbouring tile. Leave it false for a feature
            that genuinely ends inside the tile.

            OUTPUT - JSON only, no prose:
            {"elements":[{"type":"...","geometry":"...","points":[[x,y]],"geometry_center_x":0,
            "geometry_center_y":0,"geometry_width":0,"geometry_height":0,
            "geometry_rotation_degrees":0,"geometry_radius":0,"clipped_at_edge":false,
            "confidence":0.0,"label":"وصف عربي مختصر"}]}
            If this tile contains nothing of this category, return {"elements":[]}.
        """.trimIndent()
    }

    private fun extractGeminiText(response: String): String? = runCatching {
        JSONObject(response)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
    }.getOrNull()

    /** MAX_TOKENS, SAFETY, RECITATION and friends - the single most useful field when a reply
     * comes back empty, and invisible to the user without it. */
    private fun extractFinishReason(response: String): String? = runCatching {
        JSONObject(response).getJSONArray("candidates").getJSONObject(0).optString("finishReason")
            .ifBlank { null }
    }.getOrNull()

    private fun extractOpenAiStyleText(response: String): String? = runCatching {
        JSONObject(response)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }.getOrNull()

    /**
     * JPEG-encodes for upload, capping the long edge at [maxEdge].
     *
     * The cap here was the single largest cause of poor extraction. Every image, tiles included,
     * was being squeezed to 1024px - so a tile cropped out of a large plan arrived blurred, and the
     * model was being asked for survey-grade tracing from a picture in which the lines it had to
     * trace were no longer distinguishable. Asked directly, it named the resolution as "the single
     * biggest limiting factor" and said fixing it mattered more than any refinement loop, because
     * no amount of critique can recover detail that was never in the input.
     *
     * Tiles now go at [TILE_MAX_EDGE], which is large enough to keep a tile's native pixels in all
     * realistic cases. Quality is also raised, since JPEG artefacts around thin CAD linework are
     * exactly the kind of damage that turns two nearby lines into one.
     */
    private fun encode(bitmap: Bitmap, maxEdge: Int = MAX_UPLOAD_DIMENSION, quality: Int = 88): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > maxEdge) {
            val factor = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * factor).toInt().coerceAtLeast(1),
                (bitmap.height * factor).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled != bitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        const val LOG_TAG = "PlanVision"

        private val CONTEXT_PROMPT = """
            You are analysing an architectural site plan.
            Transcribe the text of the legend/key box exactly as printed, one entry per line, and
            describe the overall layout in one sentence.
            Respond with JSON only:
            {"legend_text":["1. ...","2. ..."],"overall_description":"..."}
            If the plan has no legend, return {"legend_text":[],"overall_description":"..."}.
        """.trimIndent()
        private const val GEMINI_MODEL = "gemini-2.5-flash"
        private const val NVIDIA_MODEL = "meta/llama-3.2-11b-vision-instruct"
        private const val MAX_UPLOAD_DIMENSION = 1600
        /** Tiles go at native resolution in every realistic case; this is only a runaway guard. */
        private const val TILE_MAX_EDGE = 3000

        /**
         * Extraction contract, rewritten from the model's own account of what it is and is not
         * accurate at. Three changes carry nearly all of the quality improvement:
         *
         *  - **Integer 0..2000 grid instead of 0..1 floats.** A normalised float answer clusters
         *    on coarse values, and that quantisation is what showed up as positional drift.
         *  - **A 50-point budget instead of 10.** The old cap was mine, not the model's, and it
         *    was flattening every curve and irregular boundary into a crude outline.
         *  - **Dedicated rectangle and circle forms.** Asked for a rectangular building as a
         *    polygon, the model returns four independently-estimated corners and the building
         *    arrives skewed; centre/size/angle lets it state the shape it means exactly.
         *
         * The role framing, the explicit prohibitions and the self-check list are also its own
         * recommendations - it reports being materially more accurate when asked to validate
         * closure and coordinate range before answering.
         */
        private val PROMPT = """
            You are an expert CAD technician specializing in urban planning. Meticulously extract
            precise vector geometry from this site plan image so it can be redrawn with high
            fidelity in a CAD application.

            COORDINATE SYSTEM
            All coordinates are INTEGERS in the range 0-2000 on both axes. (0,0) is the top-left
            corner of the image and (2000,2000) the bottom-right. Do NOT use normalized 0..1 floats.

            FEATURE TYPES - use these exact values for "type":
            MAIN_ROAD, SECONDARY_ROAD, DIRT_PATH, SITE_BOUNDARY, BUILDING, HERITAGE_BUILDING,
            RUINED_BUILDING, PLAZA, GREEN_AREA, CONTOUR, MAIN_AXIS, SECONDARY_AXIS, ENTRY,
            LIGHT_POLE, WATER

            GEOMETRY FORMS - choose the one that matches the feature, and set "geometry" to it:
            1. "RECTANGLE" - any clearly rectangular building or feature, even when rotated.
               Give geometry_center_x, geometry_center_y, geometry_width, geometry_height,
               geometry_rotation_degrees (counter-clockwise from the X axis).
            2. "CIRCLE" - any circular feature. Give geometry_center_x, geometry_center_y,
               geometry_radius.
            3. "POLYGON" - irregular closed areas. Give "points"; do not repeat the first point.
            4. "POLYLINE" - open paths, roads, boundaries, contours. Give "points" following the
               CENTRELINE of the feature.
            5. "MARKER" - a single point of interest. Give exactly one point.

            PRECISION REQUIREMENTS
            - Use up to 50 points for POLYGON and POLYLINE. Do NOT cap at 10 points. Use enough
              points to follow curves and irregular boundaries faithfully.
            - Do NOT approximate a rectangle or a circle with a generic polygon. Use the dedicated
              form, which preserves exact orthogonality and circularity.
            - Aim for pixel-level accuracy. Preserve apparent orthogonality, parallelism and smooth
              curves exactly as they appear in the image.
            - Where two features share a boundary in the plan, make the shared edges align.

            SELF-CHECK BEFORE ANSWERING
            - Every coordinate is an integer within 0-2000.
            - Every POLYGON has at least 3 points and does not repeat its first point.
            - Every POLYLINE follows the centreline, not one edge.
            - Rectangles and circles use their dedicated fields, not point lists.
            - No shape exceeds 50 points.

            OUTPUT
            Respond with ONLY this JSON object, no prose and no markdown fence:
            {"elements":[{"type":"<TYPE>","geometry":"<RECTANGLE|CIRCLE|POLYGON|POLYLINE|MARKER>",
            "points":[[x,y],...],"geometry_center_x":0,"geometry_center_y":0,"geometry_width":0,
            "geometry_height":0,"geometry_rotation_degrees":0,"geometry_radius":0,
            "confidence":0.0,"label":"short arabic label"}]}
            Include only the geometry fields relevant to that element's form.
            Return at most 40 elements, preferring the largest and most significant.
            Only report a feature you can actually see; if unsure, leave it out.
            If the image is not a plan or map, return {"elements":[]}.
        """.trimIndent()
    }
}
