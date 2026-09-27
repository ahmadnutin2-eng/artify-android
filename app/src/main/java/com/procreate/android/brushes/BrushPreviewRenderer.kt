package com.procreate.android.brushes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.procreate.android.canvas.BrushEngine
import com.procreate.android.canvas.BrushTextures
import com.procreate.android.canvas.BrushType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Renders Artify brush previews from the same engine used on the canvas:
 * 1. Tip shape icons (transparent, high-contrast silhouette of the brush head)
 * 2. Live stroke swatches (natural pressure stroke displaying real grain texture and tapering)
 */
object BrushPreviewRenderer {

    // A long session may browse hundreds of brushes at several device densities. Access-ordered
    // caches retain the recently visible rows without pinning every generated bitmap forever.
    // Evicted bitmaps are deliberately not recycled because a recycled row may still display one.
    // Swatches are now rendered at the row's real size rather than a fixed thin strip, so each one
    // holds several times the pixels it used to. The cap came down to match: a hundred of them
    // would be tens of megabytes of native bitmap retained behind a panel the artist has closed.
    private val strokeCache = object : LinkedHashMap<String, Bitmap>(56, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 56
    }
    private val iconCache = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 128
    }

    /**
     * Both caches are read on the UI thread and filled from the preview thread, and an
     * access-ordered map changes even on a read, so every access holds the map's lock. Rendering
     * happens outside it; if two callers race on one key, the first stored bitmap wins.
     */
    private inline fun cached(cache: LinkedHashMap<String, Bitmap>, key: String, render: () -> Bitmap): Bitmap {
        synchronized(cache) { cache[key] }?.let { return it }
        val rendered = render()
        return synchronized(cache) { cache.getOrPut(key) { rendered } }
    }

    private fun previewKey(brush: Brush, widthPx: Int, heightPx: Int) =
        "${brush.id}_${brush.properties.hashCode()}_stroke_${widthPx}x$heightPx"

    fun getPreview(brush: Brush, widthPx: Int, heightPx: Int): Bitmap =
        cached(strokeCache, previewKey(brush, widthPx, heightPx)) { renderStrokePreview(brush, widthPx, heightPx) }

    /** A swatch that is already rendered, or null. Never renders, so it is safe while binding rows. */
    fun cachedPreview(brush: Brush, widthPx: Int, heightPx: Int): Bitmap? =
        synchronized(strokeCache) { strokeCache[previewKey(brush, widthPx, heightPx)] }

    /**
     * One background thread renders swatches in request order, so a cold panel or a fling never
     * runs ~90 textured stamps per row on the UI thread. [stillWanted] is checked just before the
     * work starts, which skips rows that were recycled while waiting; [onReady] runs on the main
     * thread.
     */
    private val previewExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "brush-previews").apply { isDaemon = true }
    }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun renderPreviewAsync(
        brush: Brush,
        widthPx: Int,
        heightPx: Int,
        stillWanted: () -> Boolean,
        onReady: (Bitmap) -> Unit
    ) {
        previewExecutor.execute {
            if (!stillWanted()) return@execute
            val bitmap = runCatching { getPreview(brush, widthPx, heightPx) }.getOrNull() ?: return@execute
            mainHandler.post { onReady(bitmap) }
        }
    }

    fun getTipIcon(brush: Brush, sizePx: Int): Bitmap =
        cached(iconCache, "${brush.id}_${brush.properties.hashCode()}_icon_$sizePx") { renderTipIcon(brush, sizePx) }

    /**
     * A few of a set's own strokes stacked into one strip, for the set's cover card.
     *
     * A cover that showed only a colour and a name would be decoration. Showing what the set
     * actually draws makes it the fastest way to choose one, which is the entire reason the card
     * is there - the strokes are rendered by the real engine, so the strip cannot drift out of
     * step with the brushes it advertises.
     */
    fun getSetMontage(set: BrushSet, widthPx: Int, heightPx: Int): Bitmap {
        val sample = pickRepresentative(set)
        val key = "set_${set.id}_${sample.joinToString("|") { it.id }}_${widthPx}x$heightPx"
        return cached(strokeCache, key) {
            val w = widthPx.coerceAtLeast(10)
            val h = heightPx.coerceAtLeast(10)
            val montage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (sample.isEmpty()) return@cached montage
            val canvas = Canvas(montage)
            val bandHeight = h / sample.size
            sample.forEachIndexed { index, brush ->
                // Ink, because the cover's sample half is a light plate. The pale stroke colour the
                // list swatches use would vanish into it completely.
                val band = renderStrokePreview(brush, w, bandHeight, COVER_INK)
                canvas.drawBitmap(band, 0f, (index * bandHeight).toFloat(), null)
                band.recycle()
            }
            montage
        }
    }

    private val COVER_INK = Color.rgb(26, 27, 32)

    /**
     * Up to three brushes spread across the set rather than its first three, because a set's
     * opening entries are usually variations on one another and would make every cover look alike.
     */
    private fun pickRepresentative(set: BrushSet): List<Brush> {
        val brushes = set.brushes
        return when {
            brushes.size <= 3 -> brushes
            else -> listOf(brushes.first(), brushes[brushes.size / 2], brushes.last())
        }
    }

    fun clearCache() {
        synchronized(strokeCache) {
            strokeCache.values.forEach { it.recycle() }
            strokeCache.clear()
        }
        synchronized(iconCache) {
            iconCache.values.forEach { it.recycle() }
            iconCache.clear()
        }
    }

    private fun renderStrokePreview(
        brush: Brush,
        widthPx: Int,
        heightPx: Int,
        color: Int = if (brush.category.contains("Arabic", ignoreCase = true)) {
            Color.rgb(250, 245, 235)
        } else {
            Color.rgb(247, 249, 255)
        }
    ): Bitmap {
        val w = widthPx.coerceAtLeast(10)
        val h = heightPx.coerceAtLeast(10)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // A grid pen has no stroke to show. Running one through the swatch renderer would draw the
        // smooth curve every other brush draws, which is precisely the thing this tool refuses to
        // do, so the swatch would advertise the opposite of the truth.
        if (brush.properties.gridSnapDivisions > 0) {
            val onLightPlate = Color.red(color) < 128
            renderGridSwatch(canvas, w, h, brush.properties.gridSnapDivisions, color, onLightPlate)
            return bitmap
        }

        // Keep every preset comparable while leaving enough vertical room for scatter, water
        // blooms and coarse charcoal edges. The actual engine still controls pressure response.
        val previewSize = when (brush.properties.type) {
            BrushType.Airbrush -> h * 0.40f
            BrushType.Paint -> h * 0.34f
            BrushType.Pencil -> h * 0.24f
            else -> h * 0.28f
        }.coerceIn(6f, h * 0.42f)

        // Seeded by the brush, so a swatch never changes between renders of the same brush.
        val engine = BrushEngine(seed = BrushSetIdentity.previewSeed(brush))
        engine.properties = brush.properties.copy(
            size = previewSize,
            smoothing = 0f,
            // Preserve separated-dab and spray brushes instead of silently converting every
            // preset into a smooth line for its preview.
            spacing = brush.properties.spacing.coerceIn(0.008f, 0.65f)
        )
        engine.color = color
        engine.renderScale = 1.5f
        // A barrel-led nib draws exactly like a fixed-cut one unless the pen turns, so a swatch
        // stamped with a motionless barrel would render every azimuth brush identical to its
        // classical twin - the one place the difference most needs to be visible is the list where
        // the two sit next to each other. The sweep below stands in for the wrist.
        engine.stylusAzimuthAvailable = brush.properties.azimuthTracking > 0f

        val marginX = w * 0.05f
        val amplitude = when (brush.properties.type) {
            BrushType.Paint, BrushType.Airbrush -> h * 0.17f
            else -> h * 0.23f
        }
        val midY = h * 0.5f
        val steps = 92

        val points = (0..steps).map { i ->
            val t = i / steps.toFloat()
            val px = marginX + t * (w - 2 * marginX)
            // A stroke that sweeps through its directions rather than holding one.
            //
            // The old path was a shallow ripple: near enough to a horizontal line that the angle of
            // travel barely moved across the whole swatch. For a round tip that is harmless, but a
            // cut nib has no other way to speak - its thick and its thin *are* the angle between the
            // cut and the direction of travel - so every reed, every calligraphy pen and every flat
            // brush in the library rendered as the same featureless ribbon. Banking the stroke
            // through roughly a right angle of travel makes each tip show what it is, and it is the
            // same gesture a calligrapher uses to test a fresh pen.
            val phase = t * Math.PI.toFloat()
            val py = midY + amplitude * cos(phase) * 0.88f +
                amplitude * 0.24f * sin(phase * 2f)
            Pair(px, py)
        }

        // One unhurried quarter-turn of the wrist across the swatch, and a pen that starts upright
        // and leans over as the stroke settles - the two barrel axes a real hand supplies.
        fun barrelAt(t: Float) = (-Math.PI.toFloat() / 4f) + t * (Math.PI.toFloat() / 2f)
        fun tiltAt(t: Float) = sin(t * Math.PI.toFloat()).coerceAtLeast(0f) * (Math.PI.toFloat() / 5f)

        val first = points.first()
        engine.startStroke(first.first, first.second, 0.16f, tiltAt(0f), barrelAt(0f))
        engine.stampDot(canvas, bitmap, first.first, first.second, 0.16f, tiltAt(0f), barrelAt(0f))
        for (i in 1 until points.size) {
            val cur = points[i]
            val t = i / steps.toFloat()
            // A soft bell produces believable entry and lift taper while maintaining a long body
            // where paper grain and tip breakup can actually be judged.
            val pressureBell = sin(t * Math.PI.toFloat()).coerceAtLeast(0f).pow(0.58f)
            val pressure = (0.14f + pressureBell * 0.86f).coerceIn(0.1f, 1f)
            val velocity = 0.18f + abs(cos(t * Math.PI.toFloat())) * 0.42f
            engine.strokeTo(
                canvas, bitmap, cur.first, cur.second, pressure, velocity,
                tiltAt(t), barrelAt(t)
            )
        }
        engine.endStroke(canvas, bitmap)

        return bitmap
    }

    /**
     * The swatch for a grid pen: the ruling it writes on, with a short Kufic run built on it.
     *
     * Showing the module *and* a fragment of letterform in the same swatch is what makes the
     * denominators legible at a glance - 1/1 against 1/3 is a difference in the size of the square,
     * and a bare grid alone would not say what the tool is for.
     */
    private fun renderGridSwatch(
        canvas: Canvas,
        w: Int,
        h: Int,
        divisions: Int,
        color: Int,
        onLightPlate: Boolean
    ) {
        // Fit a whole number of modules to the swatch so no half cell is clipped at the edge.
        val targetModule = (h / 4.2f) / divisions.coerceAtLeast(1)
        val module = targetModule.coerceAtLeast(2.5f)
        val columns = (w / module).toInt().coerceAtLeast(4)
        val rows = (h / module).toInt().coerceAtLeast(3)
        val originX = (w - columns * module) / 2f
        val originY = (h - rows * module) / 2f

        val ruling = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // The ruling has to stay visible against whichever plate the swatch is sitting on.
            this.color = if (onLightPlate) Color.argb(48, 0, 0, 0) else Color.argb(58, 255, 255, 255)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        for (c in 0..columns) {
            val x = originX + c * module
            canvas.drawLine(x, originY, x, originY + rows * module, ruling)
        }
        for (r in 0..rows) {
            val y = originY + r * module
            canvas.drawLine(originX, y, originX + columns * module, y, ruling)
        }

        // A meander: down, across, up, across - the elementary move square Kufic is assembled from.
        val ink = Paint().apply {
            this.color = color
            isAntiAlias = false
        }
        fun cell(c: Int, r: Int) {
            if (c !in 0 until columns || r !in 0 until rows) return
            val x = originX + c * module
            val y = originY + r * module
            canvas.drawRect(x, y, x + module, y + module, ink)
        }
        var column = 1
        var goingDown = true
        while (column < columns - 1) {
            for (r in 1 until rows - 1) cell(column, r)
            val turn = if (goingDown) rows - 2 else 1
            cell(column + 1, turn)
            goingDown = !goingDown
            column += 2
        }
    }

    private fun renderTipIcon(brush: Brush, sizePx: Int): Bitmap {
        val s = sizePx.coerceAtLeast(16)
        val thumbPath = brush.properties.thumbnailPath
        if (!thumbPath.isNullOrEmpty()) {
            val thumb = BrushTextures.getThumbnail(thumbPath)
            if (thumb != null) {
                return scaleToSquare(thumb, s)
            }
        }

        // Fallback to brush tip shape
        val tip = BrushTextures.getBrushTip(brush.properties.tipType, s, brush.properties.customTipPath)
        val result = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.WHITE
        }
        val rect = RectF(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f)
        canvas.drawBitmap(tip, null, rect, p)
        return result
    }

    private fun scaleToSquare(source: Bitmap, targetSize: Int): Bitmap {
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val padding = targetSize * 0.08f
        val innerSize = targetSize - padding * 2
        val scale = kotlin.math.min(innerSize / source.width.toFloat(), innerSize / source.height.toFloat())
        val sw = source.width * scale
        val sh = source.height * scale
        val dx = padding + (innerSize - sw) / 2f
        val dy = padding + (innerSize - sh) / 2f

        val destRect = RectF(dx, dy, dx + sw, dy + sh)
        canvas.drawBitmap(source, null, destRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return result
    }
}
