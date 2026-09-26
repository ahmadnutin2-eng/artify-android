package com.procreate.android.ai

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Runs the full multi-pass extraction: context, then every tile against every category, then
 * stitching.
 *
 * The single whole-image call this replaces returned a few dozen features for a plan holding
 * hundreds, because the model spreads its attention over everything at once and reports the
 * largest things it sees. Splitting the work is what buys detail - each call looks at one area
 * and one kind of feature, and can afford to describe it properly.
 *
 * That detail is paid for in requests. A 3x3 grid across four categories is 36 calls plus one for
 * context, against a free tier of roughly ten per minute and 250 per day. So this is written to be
 * interruptible, to report progress honestly, and to keep whatever it has already gathered when
 * something fails part-way - losing thirty successful calls because the thirty-first timed out
 * would be indefensible.
 */
class PlanAnalysisOrchestrator(
    private val client: PlanVisionClient = PlanVisionClient()
) {

    data class Progress(
        val completedCalls: Int,
        val totalCalls: Int,
        val stage: String,
        val featuresSoFar: Int
    )

    data class Outcome(
        val features: List<DetectedFeature>,
        val warnings: List<String>,
        val completedCalls: Int,
        val failedCalls: Int,
        val legend: List<String>
    )

    /**
     * @param detail how finely to cut the plan. More tiles means more detail and more requests.
     */
    enum class Detail(val arabicLabel: String, val targetTileSize: Int, val categories: List<ExtractionCategory>) {
        FAST("سريع - نداء واحد", Int.MAX_VALUE, listOf()),
        BALANCED("متوازن - تقسيم 2x2", 1100, ExtractionCategory.entries.toList()),
        THOROUGH("تفصيلي - تقسيم 3x3", 750, ExtractionCategory.entries.toList()),
        SURVEY("مسح كامل - تقسيم 4x4", 560, ExtractionCategory.entries.toList());

        fun estimateCalls(imageWidth: Int, imageHeight: Int): Int {
            if (this == FAST) return 1
            val tiles = PlanTiler.tile(imageWidth, imageHeight, targetTileSize).size
            return 1 + tiles * categories.size
        }
    }

    suspend fun analyze(
        bitmap: Bitmap,
        detail: Detail = Detail.THOROUGH,
        onFeaturesFound: (List<DetectedFeature>) -> Unit = {},
        onProgress: (Progress) -> Unit = {}
    ): Outcome {
        if (detail == Detail.FAST) return singlePass(bitmap, onProgress, onFeaturesFound)

        val warnings = mutableListOf<String>()
        val collected = mutableListOf<DetectedFeature>()
        var completed = 0
        var failed = 0

        val tiles = PlanTiler.tile(bitmap.width, bitmap.height, detail.targetTileSize)
        val total = 1 + tiles.size * detail.categories.size

        onProgress(Progress(0, total, "قراءة مفتاح الخريطة…", 0))
        val context = client.readContext(bitmap)
        completed++
        val legend = context?.legend.orEmpty()
        if (context == null) {
            // Not fatal: the legend only sharpens classification, it is not required to extract
            // geometry, so the run continues without it rather than failing outright.
            warnings += "تعذّرت قراءة مفتاح الخريطة؛ سيستمر التحليل بدونه."
        }

        val overview = downscale(bitmap, OVERVIEW_MAX_EDGE)

        for (tile in tiles) {
            for (category in detail.categories) {
                if (!currentCoroutineContext().isActive) {
                    warnings += "أُلغيت العملية بعد $completed نداءً."
                    return Outcome(FeatureMerger.merge(collected), warnings, completed, failed, legend)
                }

                onProgress(
                    Progress(
                        completedCalls = completed,
                        totalCalls = total,
                        stage = "البلاطة ${tile.column + 1},${tile.row + 1} · ${category.arabicLabel}",
                        featuresSoFar = collected.size
                    )
                )

                val crop = cropTile(bitmap, tile)
                when (val result = client.analyzeTile(crop, overview, tile, bitmap.width, bitmap.height, category, legend)) {
                    is PlanVisionClient.Result.Success -> {
                        collected += result.analysis.features
                        warnings += result.analysis.warnings
                        if (result.analysis.features.isNotEmpty()) {
                            onFeaturesFound(result.analysis.features)
                        }
                    }
                    is PlanVisionClient.Result.Failure -> {
                        failed++
                        warnings += "بلاطة ${tile.column + 1},${tile.row + 1} (${category.arabicLabel}): ${result.message}"
                    }
                }
                if (crop != bitmap) crop.recycle()
                completed++

                // The free tier allows roughly ten requests a minute; pacing here keeps a long run
                // from being rejected halfway through for exceeding it.
                delay(REQUEST_SPACING_MS)
            }
        }

        onProgress(Progress(completed, total, "دمج النتائج…", collected.size))
        val merged = FeatureMerger.merge(collected)
        return Outcome(merged, warnings, completed, failed, legend)
    }

    private suspend fun singlePass(
        bitmap: Bitmap,
        onProgress: (Progress) -> Unit,
        onFeaturesFound: (List<DetectedFeature>) -> Unit
    ): Outcome {
        onProgress(Progress(0, 1, "تحليل الصورة كاملة…", 0))
        return when (val result = client.analyze(bitmap)) {
            is PlanVisionClient.Result.Success -> Outcome(
                features = result.analysis.features.also(onFeaturesFound),
                warnings = result.analysis.warnings,
                completedCalls = 1,
                failedCalls = 0,
                legend = emptyList()
            )
            is PlanVisionClient.Result.Failure -> Outcome(
                features = emptyList(),
                warnings = listOf(result.message),
                completedCalls = 1,
                failedCalls = 1,
                legend = emptyList()
            )
        }
    }

    private fun cropTile(source: Bitmap, tile: PlanTile): Bitmap {
        val width = tile.width.coerceAtMost(source.width - tile.offsetX)
        val height = tile.height.coerceAtMost(source.height - tile.offsetY)
        if (width <= 0 || height <= 0) return source
        if (width == source.width && height == source.height) return source
        return Bitmap.createBitmap(source, tile.offsetX, tile.offsetY, width, height)
    }

    private fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val factor = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        /** Sent alongside every tile so the model can see where the tile sits in the whole plan.
         * Small on purpose - it is context, not the subject of the call. */
        private const val OVERVIEW_MAX_EDGE = 640
        private const val REQUEST_SPACING_MS = 6_500L
    }
}
