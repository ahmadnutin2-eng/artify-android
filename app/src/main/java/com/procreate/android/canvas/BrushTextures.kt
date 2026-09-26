package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates brush tip shapes and grain textures programmatically.
 * No external files needed - everything is created via code.
 */
object BrushTextures {

    /**
     * The bundled "nothing here" image. A brush naming it for its grain or its tip means *there is
     * no texture to apply*, not *apply this picture*: the file is a flat blank, so sampling it
     * costs work to produce nothing, and as a tip it yields a featureless square instead of a
     * proper round stamp.
     *
     * It is a constant because two separate call sites test for it - [createCustomTip] here and
     * BrushEngine.refreshGrain. Both used to spell the filename out inline, and when the asset was
     * renamed both tests silently stopped matching: nothing failed to compile, no test covered it,
     * and 50 brushes quietly changed behaviour. One shared name makes the next rename a
     * compile-time problem instead of an invisible one.
     */
    const val BLANK_ASSET = "tx-blank.png"

    var appContext: android.content.Context? = null

    /** Tips are rendered at one of a few power-of-two resolutions and scaled at stamp time,
     * rather than at the brush's exact pixel size. Generating a size-10 brush as a literal
     * 10x10 bitmap is what made strokes look like a chain of blocky squares once the canvas was
     * zoomed in - there simply weren't enough source pixels to magnify. It also keeps the cache
     * to a couple of dozen entries instead of one per integer brush size the user drags through. */
    private const val MIN_TIP_PX = 64
    private const val MAX_TIP_PX = 512

    /** Grain is a canvas-anchored tile (see BrushEngine's grain shader), so it has one fixed
     * resolution rather than following the brush size. */
    const val GRAIN_TILE_PX = 256

    /** Access-ordered so the least recently used tip is the one dropped once the cap is hit.
     * Evicted bitmaps are dropped, never recycled - a stroke in flight may still be stamping one. */
    private val textureCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 48
    }

    /** The bitmap resolution used to render a brush of [size] canvas pixels. */
    fun tipResolutionFor(size: Int): Int {
        var res = MIN_TIP_PX
        while (res < size && res < MAX_TIP_PX) res *= 2
        return res
    }

    /**
     * Get or create a brush tip bitmap for a brush of the given size. The returned bitmap is
     * *not* [size] pixels wide - it's rendered at [tipResolutionFor] and meant to be scaled by
     * the caller (BrushEngine divides by the bitmap's own width, so this stays transparent).
     * [customPath] supplies the source image file for [BrushTipType.CUSTOM] (user-imported
     * brushes) brushes - ignored for every other type.
     */
    fun getBrushTip(type: BrushTipType, size: Int, customPath: String? = null): Bitmap {
        val res = tipResolutionFor(size)
        val key = if (type == BrushTipType.CUSTOM) "custom_${customPath}_$res" else "${type.name}_tip_$res"
        return textureCache.getOrPut(key) {
            // Tips are almost always drawn smaller than they're generated, so mip levels are what
            // keep a noisy tip (pencil grain, spray dots, bristles) from aliasing into shimmer as
            // the brush size changes - plain bilinear minification would just drop pixels.
            createBrushTip(type, res, customPath).apply { setHasMipMap(true) }
        }
    }

    /**
     * Get or create a grain texture tile (tiled pattern, always [GRAIN_TILE_PX] square).
     */
    fun getGrainTexture(type: GrainType): Bitmap {
        val key = "${type.name}_grain"
        return textureCache.getOrPut(key) { createGrainTexture(type, GRAIN_TILE_PX) }
    }

    /**
     * Loads a custom grain texture from assets or local files, scaled to [GRAIN_TILE_PX],
     * converted to an authentic [Bitmap.Config.ALPHA_8] heightmap with contrast enhancement.
     */
    fun getGrainTextureCustom(path: String?): Bitmap {
        if (path.isNullOrEmpty() || path.endsWith(BLANK_ASSET)) return getGrainTexture(GrainType.NONE)
        val key = "grain_custom_$path"
        return textureCache.getOrPut(key) {
            val source = try {
                when {
                    path.startsWith("asset://") -> {
                        val assetPath = path.removePrefix("asset://")
                        appContext?.assets?.open(assetPath)?.use { BitmapFactory.decodeStream(it) }
                    }
                    else -> BitmapFactory.decodeFile(path)
                }
            } catch (e: Exception) {
                null
            } ?: return@getOrPut createPaperGrain(GRAIN_TILE_PX)

            val scaled = if (source.width == GRAIN_TILE_PX && source.height == GRAIN_TILE_PX) source
            else Bitmap.createScaledBitmap(source, GRAIN_TILE_PX, GRAIN_TILE_PX, true)

            val rawPixels = IntArray(GRAIN_TILE_PX * GRAIN_TILE_PX)
            scaled.getPixels(rawPixels, 0, GRAIN_TILE_PX, 0, 0, GRAIN_TILE_PX, GRAIN_TILE_PX)
            if (scaled !== source) scaled.recycle()
            source.recycle()

            var minLum = 255
            var maxLum = 0
            val lumArray = IntArray(rawPixels.size)
            for (i in rawPixels.indices) {
                val p = rawPixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
                lumArray[i] = lum
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }

            val range = max(1, maxLum - minLum)
            val alphaPixels = IntArray(rawPixels.size)
            for (i in lumArray.indices) {
                // Contrast stretch so the surface tooth is rich and tangible
                val stretched = ((lumArray[i] - minLum).toFloat() / range * 255f).toInt().coerceIn(0, 255)
                alphaPixels[i] = stretched shl 24
            }

            val grainBmp = Bitmap.createBitmap(GRAIN_TILE_PX, GRAIN_TILE_PX, Bitmap.Config.ALPHA_8)
            grainBmp.setPixels(alphaPixels, 0, GRAIN_TILE_PX, 0, 0, GRAIN_TILE_PX, GRAIN_TILE_PX)
            grainBmp
        }
    }

    /**
     * Loads an authentic Procreate preview thumbnail from assets or local storage.
     * Converts the black background into a crisp, transparent foreground silhouette.
     */
    fun getThumbnail(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val key = "thumb_clean_$path"
        return textureCache.getOrPut(key) {
            val source = try {
                when {
                    path.startsWith("asset://") -> {
                        val assetPath = path.removePrefix("asset://")
                        appContext?.assets?.open(assetPath)?.use { BitmapFactory.decodeStream(it) }
                    }
                    else -> BitmapFactory.decodeFile(path)
                }
            } catch (e: Exception) {
                null
            } ?: return null

            val w = source.width
            val h = source.height
            val raw = IntArray(w * h)
            source.getPixels(raw, 0, w, 0, 0, w, h)
            source.recycle()

            val clean = IntArray(raw.size)
            for (i in raw.indices) {
                val p = raw[i]
                val a = (p ushr 24)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val srcAlpha = a / 255f
                val finalAlpha = (lum * srcAlpha * 255f).toInt().coerceIn(0, 255)
                clean[i] = Color.argb(finalAlpha, 255, 255, 255)
            }

            val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(clean, 0, w, 0, 0, w, h)
            outBmp
        }
    }

    // ==================== BRUSH TIP SHAPES ====================

    private fun createBrushTip(type: BrushTipType, size: Int, customPath: String? = null): Bitmap {
        val s = max(size, 4)
        return when (type) {
            BrushTipType.ROUND_SOFT -> createRoundSoftTip(s)
            BrushTipType.ROUND_HARD -> createRoundHardTip(s)
            BrushTipType.PENCIL -> createPencilTip(s)
            BrushTipType.CHARCOAL -> createCharcoalTip(s)
            BrushTipType.FLAT_BRUSH -> createFlatBrushTip(s)
            BrushTipType.FAN_BRUSH -> createFanBrushTip(s)
            BrushTipType.SPRAY -> createSprayTip(s)
            BrushTipType.WATERCOLOR -> createWatercolorTip(s)
            BrushTipType.CALLIGRAPHY -> createCalligraphyTip(s)
            BrushTipType.INK_PEN -> createInkPenTip(s)
            BrushTipType.MARKER -> createMarkerTip(s)
            BrushTipType.CRAYON -> createCrayonTip(s)
            BrushTipType.DRY_BRUSH -> createDryBrushTip(s)
            BrushTipType.STIPPLE -> createStippleTip(s)
            BrushTipType.REED_PEN -> createReedPenTip(s)
            BrushTipType.CUSTOM -> createCustomTip(customPath, s)
        }
    }

    /** Converts a user-imported image or bundled asset into a stamp shape. */
    private fun createCustomTip(path: String?, size: Int): Bitmap {
        if (path != null && path.endsWith(BLANK_ASSET)) {
            return createRoundHardTip(size)
        }

        val source = try {
            when {
                path == null -> null
                path.startsWith("asset://") -> {
                    val assetPath = path.removePrefix("asset://")
                    appContext?.assets?.open(assetPath)?.use { BitmapFactory.decodeStream(it) }
                }
                else -> BitmapFactory.decodeFile(path)
            }
        } catch (e: Exception) {
            null
        } ?: return createRoundHardTip(size)

        val scaled = if (source.width == size && source.height == size) source
            else Bitmap.createScaledBitmap(source, size, size, true)

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== source) scaled.recycle()
        source.recycle()

        val isBundledAsset = path?.startsWith("asset://") == true
        val corners = intArrayOf(pixels[0], pixels[size - 1], pixels[(size - 1) * size], pixels[size * size - 1])
        val avgCornerAlpha = corners.map { Color.alpha(it) / 255f }.average().toFloat()
        val avgCornerLuminance = corners.map { p ->
            (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
        }.average().toFloat()

        // Only invert if it's an external user paper scan (not bundled Procreate assets)
        val invertLuminance = !isBundledAsset && avgCornerAlpha > 0.8f && avgCornerLuminance > 0.8f

        val alphaPixels = IntArray(size * size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = Color.alpha(p) / 255f
            val luminance = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            val rawInk = if (invertLuminance) (1f - luminance) else luminance
            val ink = (rawInk * alpha * 255f).toInt().coerceIn(0, 255)
            alphaPixels[i] = ink shl 24
        }

        val alphaTip = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        alphaTip.setPixels(alphaPixels, 0, size, 0, 0, size, size)
        return alphaTip
    }

    /** Soft radial gradient circle - ideal for airbrush */
    private fun createRoundSoftTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                center, center, center,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.3f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(center, center, center, paint)
        return bitmap
    }

    /** Hard circle with slight edge softness - ideal for ink/pen */
    private fun createRoundHardTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                center, center, center,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(center, center, center, paint)
        return bitmap
    }

    /** Textured pencil tip with noise and grain */
    private fun createPencilTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center
        val rng = Random(42)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = sqrt(dx * dx + dy * dy)
                val falloff = (1f - (dist / radius)).coerceIn(0f, 1f)

                // Add grainy noise for pencil texture
                val noise = rng.nextFloat()
                val alpha = (falloff * falloff * noise * 255).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Chunky charcoal texture - rough and irregular */
    private fun createCharcoalTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center
        val rng = Random(123)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val dist = sqrt(dx * dx + dy * dy)
                val falloff = (1f - (dist / radius)).coerceIn(0f, 1f)

                // Chunky noise for charcoal effect
                val blockX = x / 3
                val blockY = y / 3
                val blockNoise = ((blockX * 7 + blockY * 13 + 37) % 256) / 256f
                val fineNoise = rng.nextFloat()
                val combined = (blockNoise * 0.6f + fineNoise * 0.4f)
                val alpha = (falloff * combined * 255).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Flat rectangular brush shape */
    private fun createFlatBrushTip(size: Int): Bitmap {
        val w = size
        val h = (size * 0.35f).toInt().coerceAtLeast(4)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(w * h)
        val cx = w / 2f
        val cy = h / 2f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = abs(x - cx) / cx
                val ny = abs(y - cy) / cy
                val edgeFade = (1f - max(nx, ny)).coerceIn(0f, 1f)
                val softEdge = if (edgeFade < 0.15f) edgeFade / 0.15f else 1f
                val alpha = (softEdge * 230).toInt().coerceIn(0, 255)
                pixels[y * w + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    /** Fan brush shape - multiple bristle lines */
    private fun createFanBrushTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val center = size / 2f
        val bristleCount = 12
        for (i in 0 until bristleCount) {
            val angle = Math.toRadians((-30.0 + 60.0 * i / bristleCount))
            val endX = center + (center * 0.9f * cos(angle)).toFloat()
            val endY = (size * 0.1f + center * 0.8f * sin(angle).toFloat())
            canvas.drawLine(center, size.toFloat(), endX, endY.toFloat(), paint)
        }
        return bitmap
    }

    /** Random dots for spray paint effect */
    private fun createSprayTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val center = size / 2f
        val radius = center
        val rng = Random(77)

        val dotCount = (size * size / 8).coerceAtMost(4000)
        for (i in 0 until dotCount) {
            val angle = rng.nextFloat() * Math.PI.toFloat() * 2
            val dist = rng.nextFloat() * radius
            val x = center + cos(angle.toDouble()).toFloat() * dist
            val y = center + sin(angle.toDouble()).toFloat() * dist
            val dotSize = 1f + rng.nextFloat() * 2f
            paint.alpha = (100 + rng.nextInt(155))
            canvas.drawCircle(x, y, dotSize, paint)
        }
        return bitmap
    }

    /** Watercolor blob - irregular soft edges */
    private fun createWatercolorTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center
        val rng = Random(999)

        // Generate a few random offsets for organic shape
        val blobCount = 5
        val blobOffsets = Array(blobCount) {
            Pair(
                center + (rng.nextFloat() - 0.5f) * radius * 0.5f,
                center + (rng.nextFloat() - 0.5f) * radius * 0.5f
            )
        }
        val blobRadii = FloatArray(blobCount) { radius * (0.5f + rng.nextFloat() * 0.5f) }

        for (y in 0 until size) {
            for (x in 0 until size) {
                var maxInfluence = 0f
                for (b in 0 until blobCount) {
                    val dx = x - blobOffsets[b].first
                    val dy = y - blobOffsets[b].second
                    val dist = sqrt(dx * dx + dy * dy)
                    val influence = (1f - dist / blobRadii[b]).coerceIn(0f, 1f)
                    maxInfluence = max(maxInfluence, influence)
                }
                val noise = 0.7f + rng.nextFloat() * 0.3f
                val alpha = (maxInfluence * noise * 180).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Angled oval for calligraphy */
    private fun createCalligraphyTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val cx = size / 2f
        val cy = size / 2f
        val angle = Math.toRadians(45.0)
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - cx
                val dy = y - cy
                // Rotate and stretch
                val rx = (dx * cosA + dy * sinA) / (cx * 0.3f)
                val ry = (-dx * sinA + dy * cosA) / (cy * 0.9f)
                val dist = sqrt(rx * rx + ry * ry)
                val alpha = if (dist <= 1f) {
                    ((1f - dist * 0.2f) * 255).toInt().coerceIn(0, 255)
                } else 0
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /**
     * Flat-cut reed pen (qalam) nib for Arabic calligraphy - a straight-edged parallelogram
     * held at a shallow angle, with a slightly irregular hand-cut edge (unlike the smooth
     * elliptical Latin calligraphy nib above). Produces the characteristic thick/thin
     * transitions of Naskh/Thuluth-style strokes depending on stroke direction.
     */
    private fun createReedPenTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val cx = size / 2f
        val cy = size / 2f
        val angle = Math.toRadians(20.0) // shallow qalam cut angle
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val halfWidth = size * 0.44f
        val halfThickness = size * 0.1f
        val rng = Random(707)
        // Per-edge random offsets simulate a hand-cut nib rather than a machine-perfect one.
        // Interpolated smoothly between control points instead of jumping between fixed buckets.
        val edgeControlPoints = 7
        val edgeNoise = FloatArray(edgeControlPoints) { (rng.nextFloat() - 0.5f) * size * 0.035f }

        fun smoothEdgeOffset(t01: Float): Float {
            val pos = t01.coerceIn(0f, 1f) * (edgeControlPoints - 1)
            val i0 = pos.toInt().coerceIn(0, edgeControlPoints - 2)
            val frac = pos - i0
            return edgeNoise[i0] + (edgeNoise[i0 + 1] - edgeNoise[i0]) * frac
        }

        // How many pixels the length-axis edge fades over, instead of cutting off in one hard
        // step - a binary edge there is what made rotated stamps look like crisp, blocky
        // rectangles/diamonds instead of blending into a smooth stroke.
        val lengthFeather = max(1f, size * 0.02f)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - cx
                val dy = y - cy
                // Rotate into nib space: rx runs along the nib's long edge, ry across its thickness
                val rx = dx * cosA + dy * sinA
                val ry = -dx * sinA + dy * cosA

                val lengthAlpha = ((halfWidth - abs(rx)) / lengthFeather).coerceIn(0f, 1f)
                if (lengthAlpha <= 0f) {
                    pixels[y * size + x] = 0
                    continue
                }
                val t = (rx / halfWidth + 1f) / 2f
                val localThickness = halfThickness + smoothEdgeOffset(t)
                val edgeDist = (localThickness - abs(ry)) / localThickness
                var shapeAlpha = if (edgeDist > 0f) (edgeDist.coerceAtMost(1f).let { if (it > 0.55f) 1f else it / 0.55f }) else 0f
                shapeAlpha *= lengthAlpha

                // Taper the two ends slightly, like a worn/rounded pen point rather than a hard cut.
                val endTaper = (1f - (abs(t - 0.5f) * 2f).let { if (it > 0.92f) ((it - 0.92f) / 0.08f).coerceIn(0f, 1f) else 0f })
                shapeAlpha *= endTaper

                // Central ink channel: a faint softer line down the nib's spine, evoking the slit
                // that carries ink in a real cut reed pen.
                val channelDist = abs(ry) / halfThickness
                if (channelDist < 0.22f) {
                    shapeAlpha *= 0.75f + 0.25f * (channelDist / 0.22f)
                }

                pixels[y * size + x] = (shapeAlpha * 255).toInt().coerceIn(0, 255) shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Precise circle for ink pen */
    private fun createInkPenTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                center, center, center,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.92f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(center, center, center, paint)
        return bitmap
    }

    /** Marker tip - slightly rectangular with medium softness */
    private fun createMarkerTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val cx = size / 2f
        val cy = size / 2f

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = abs(x - cx) / (cx * 0.8f)
                val dy = abs(y - cy) / (cy * 1.0f)
                val dist = sqrt(dx * dx + dy * dy)
                val alpha = if (dist <= 1f) {
                    val fade = if (dist > 0.7f) (1f - (dist - 0.7f) / 0.3f) else 1f
                    (fade * 200).toInt().coerceIn(0, 255)
                } else 0
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Crayon - very rough and textured */
    private fun createCrayonTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val cx = size / 2f
        val cy = size / 2f
        val radius = cx
        val rng = Random(321)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                val falloff = (1f - dist / radius).coerceIn(0f, 1f)
                val waxNoise = if (rng.nextFloat() > 0.3f) 1f else 0.2f
                val alpha = (falloff * waxNoise * 220).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Dry brush - radial streaks with random gaps, like a bristle dragged near-dry across paper */
    private fun createDryBrushTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val cx = size / 2f
        val cy = size / 2f
        val radius = cx
        val rng = Random(654)

        // A fixed set of radial streak angles with per-streak random gaps/thickness
        val streakCount = 14
        val streakAngle = FloatArray(streakCount) { (rng.nextFloat() * 360f) }
        val streakWidth = FloatArray(streakCount) { 0.15f + rng.nextFloat() * 0.25f }

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                val falloff = (1f - dist / radius).coerceIn(0f, 1f)
                if (falloff <= 0f) {
                    pixels[y * size + x] = 0
                    continue
                }
                val angle = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()) + 360f) % 360f

                var streakInfluence = 0f
                for (i in 0 until streakCount) {
                    var diff = abs(angle - streakAngle[i])
                    if (diff > 180f) diff = 360f - diff
                    val width = streakWidth[i] * 40f
                    if (diff < width) {
                        streakInfluence = max(streakInfluence, 1f - diff / width)
                    }
                }
                val gapNoise = if (rng.nextFloat() > 0.25f) 1f else 0f
                val alpha = (falloff * streakInfluence * gapNoise * 255).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Stipple - dense, fairly even scattering of small dots (pointillism-style tip) */
    private fun createStippleTip(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val center = size / 2f
        val radius = center
        val rng = Random(210)

        val dotCount = (size * size / 4).coerceAtMost(9000)
        for (i in 0 until dotCount) {
            val angle = rng.nextFloat() * Math.PI.toFloat() * 2
            // sqrt bias keeps density roughly even across the disc instead of clustering at the center
            val dist = sqrt(rng.nextFloat()) * radius
            val x = center + cos(angle.toDouble()).toFloat() * dist
            val y = center + sin(angle.toDouble()).toFloat() * dist
            val dotSize = 0.6f + rng.nextFloat() * 1.2f
            paint.alpha = 160 + rng.nextInt(95)
            canvas.drawCircle(x, y, dotSize, paint)
        }
        return bitmap
    }

    // ==================== GRAIN TEXTURES ====================

    private fun createGrainTexture(type: GrainType, size: Int): Bitmap {
        val s = max(size, 16)
        return when (type) {
            GrainType.NONE -> createSolidGrain(s)
            GrainType.PAPER -> createPaperGrain(s)
            GrainType.CANVAS_FABRIC -> createCanvasGrain(s)
            GrainType.NOISE -> createNoiseGrain(s)
            GrainType.LINEN -> createLinenGrain(s)
            GrainType.ROUGH -> createRoughGrain(s)
            GrainType.SPECKLE -> createSpeckleGrain(s)
            GrainType.INK_BLEED -> createInkBleedGrain(s)
        }
    }

    private fun createSolidGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        bitmap.eraseColor(Color.WHITE)
        return bitmap
    }

    /** Paper grain - subtle fiber pattern */
    private fun createPaperGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val rng = Random(555)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val base = 180 + rng.nextInt(75)
                val fiber = if ((x + y) % 7 < 2) -20 else 0
                val alpha = (base + fiber).coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Canvas fabric - cross-hatch weave pattern */
    private fun createCanvasGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val xMod = x % 6
                val yMod = y % 6
                val warpThread = if (xMod < 3) 1f else 0.6f
                val weftThread = if (yMod < 3) 1f else 0.6f
                val weave = min(warpThread, weftThread)
                val alpha = (weave * 230).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Pure random noise */
    private fun createNoiseGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val rng = Random(888)

        for (i in pixels.indices) {
            val alpha = 128 + rng.nextInt(127)
            pixels[i] = alpha shl 24
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Linen texture - fine parallel lines */
    private fun createLinenGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val rng = Random(222)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val line = if (y % 4 == 0 || x % 4 == 0) 0.5f else 1f
                val noise = 0.8f + rng.nextFloat() * 0.2f
                val alpha = (line * noise * 240).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Rough sandpaper-like texture */
    private fun createRoughGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size)
        val rng = Random(333)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val blockNoise = ((x / 2 * 13 + y / 2 * 7) % 255) / 255f
                val fineNoise = rng.nextFloat()
                val combined = blockNoise * 0.5f + fineNoise * 0.5f
                val alpha = (combined * 255).toInt().coerceIn(0, 255)
                pixels[y * size + x] = alpha shl 24
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Speckle - irregular blotch clusters of varying size, distinct from uniform NOISE/ROUGH */
    private fun createSpeckleGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size) { (200 shl 24) }
        val rng = Random(444)

        val blotchCount = (size * size / 30).coerceAtMost(150)
        repeat(blotchCount) {
            val bx = rng.nextFloat() * size
            val by = rng.nextFloat() * size
            val r = 0.5f + rng.nextFloat() * (size * 0.04f)
            val minX = max(0, (bx - r).toInt())
            val maxX = min(size - 1, (bx + r).toInt())
            val minY = max(0, (by - r).toInt())
            val maxY = min(size - 1, (by + r).toInt())
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dist = sqrt((x - bx) * (x - bx) + (y - by) * (y - by))
                    if (dist <= r) {
                        pixels[y * size + x] = (50 shl 24)
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Ink bleed - soft organic capillary blotches at two scales, like ink soaking into absorbent paper */
    private fun createInkBleedGrain(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(size * size) { (235 shl 24) }
        val rng = Random(919)

        fun applyBlotches(count: Int, minRadiusFrac: Float, maxRadiusFrac: Float, strength: Float) {
            repeat(count) {
                val bx = rng.nextFloat() * size
                val by = rng.nextFloat() * size
                val r = size * (minRadiusFrac + rng.nextFloat() * (maxRadiusFrac - minRadiusFrac))
                val minX = max(0, (bx - r).toInt())
                val maxX = min(size - 1, (bx + r).toInt())
                val minY = max(0, (by - r).toInt())
                val maxY = min(size - 1, (by + r).toInt())
                for (y in minY..maxY) {
                    for (x in minX..maxX) {
                        val dist = sqrt((x - bx) * (x - bx) + (y - by) * (y - by))
                        val falloff = (1f - dist / r).coerceIn(0f, 1f)
                        if (falloff > 0f) {
                            val idx = y * size + x
                            val existing = (pixels[idx] ushr 24) and 0xFF
                            val darkened = (existing - falloff * strength).toInt().coerceIn(20, 255)
                            pixels[idx] = darkened shl 24
                        }
                    }
                }
            }
        }

        // Large, soft capillary blooms first, then finer speckle on top for depth.
        applyBlotches((size * size / 150).coerceAtMost(35), 0.05f, 0.13f, 90f)
        applyBlotches((size * size / 60).coerceAtMost(90), 0.015f, 0.045f, 130f)

        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Drop every cached texture. Entries are released to the GC rather than recycled, since a
     * stroke may still be stamping one of them on another thread/frame. */
    fun clearCache() {
        textureCache.clear()
    }
}

/** Types of brush tip shapes */
enum class BrushTipType {
    ROUND_SOFT,    // Airbrush-style soft circle
    ROUND_HARD,    // Pen-style hard circle
    PENCIL,        // Grainy pencil texture
    CHARCOAL,      // Rough charcoal chunks
    FLAT_BRUSH,    // Wide flat bristle
    FAN_BRUSH,     // Fan-shaped bristles
    SPRAY,         // Random dots (spray paint)
    WATERCOLOR,    // Organic blob shape
    CALLIGRAPHY,   // Angled nib
    INK_PEN,       // Sharp precise circle
    MARKER,        // Slightly rectangular
    CRAYON,        // Waxy rough texture
    DRY_BRUSH,     // Streaky, near-dry bristle drag
    STIPPLE,       // Dense even dot scatter
    REED_PEN,      // Flat-cut angled nib for Arabic calligraphy (qalam)
    CUSTOM         // User-imported image, converted to a grayscale stamp shape
}

/** Types of grain/texture overlays */
enum class GrainType {
    NONE,          // No grain (solid)
    PAPER,         // Subtle paper fibers
    CANVAS_FABRIC, // Woven canvas
    NOISE,         // Random noise
    LINEN,         // Fine linen threads
    ROUGH,         // Sandpaper-like rough
    SPECKLE,       // Irregular blotch clusters
    INK_BLEED      // Soft capillary ink-soak blotches
}
