package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import android.graphics.BlendMode as AndroidBlendMode

/**
 * Shared layer-stack compositing used by both the live canvas (DrawingView.onDraw) and
 * flatten/export (CanvasViewModel.flattenToBitmap), so clipping-mask/opacity/blend-mode behavior
 * can never drift between what the user sees while drawing and what actually gets saved.
 *
 * Clipping-mask layers (Layer.isClippingMask) clip to the nearest non-clipping layer below them
 * in the stack (Photoshop/Procreate "clipping group" semantics): a run of one or more clipped
 * layers directly above a base layer is composited into an isolated offscreen group so they're
 * visible only where that base already has content, using BlendMode.SRC_ATOP. A clipping-mask
 * layer with nothing beneath it to clip to is simply hidden, matching Procreate.
 *
 * ## Sampling
 *
 * How a layer's pixels are interpolated when the canvas is zoomed in. This is the same choice
 * ibisPaint exposes as "Display when Zoomed: Smooth / Pixelated" (and offers per-tool as the
 * interpolation method of its Transform tool):
 *
 *  - [Sampling.NEAREST]: the real pixels, hard-edged squares. What pixel artists want.
 *  - [Sampling.BILINEAR]: the platform default. Smooth, but a zoomed line goes soft and blocky.
 *  - [Sampling.BICUBIC]: a Mitchell-Netravali filter over a 4x4 neighbourhood. Edges stay
 *    continuous and crisp at high zoom, so a line reads as a line rather than as a grid of squares.
 *
 * Bicubic needs a runtime shader, which only exists on API 33+ and only in a hardware canvas. Every
 * caller that draws into a software Bitmap (export, flatten, eyedropper) leaves the default, which
 * is also correct there because those draws are 1:1 and interpolate nothing.
 */
object LayerCompositor {

    enum class Sampling { NEAREST, BILINEAR, BICUBIC }

    /** Paints are mutable and drawing is thread-confined, so one set per render thread avoids
     * allocating several Paint objects for every frame while remaining safe for background export. */
    private class PaintSet {
        val layer = Paint()
        val group = Paint()
        val clipped = Paint()
        val clippedGroup = Paint()
        val mask = Paint()
        val maskedComposite = Paint()
        val maskedPixels = Paint()
        /** Hardware-ready pieces of oversized layers. Only visible pieces are retained. */
        val largeBitmapTiles = java.util.IdentityHashMap<Bitmap, BitmapTileSet>()
    }

    private data class TileKey(val x: Int, val y: Int, val sample: Int)

    private class BitmapTileSet(var generation: Int) {
        val tiles = LinkedHashMap<TileKey, Bitmap>(16, 0.75f, true)
        var bytes: Long = 0L

        fun clear() {
            // Do not recycle here: a hardware display list from the preceding frame may still own
            // the bitmap on RenderThread. Removing references lets Android release it safely.
            tiles.clear()
            bytes = 0L
        }
    }

    private val threadPaints = ThreadLocal.withInitial { PaintSet() }

    /** True when [Sampling.BICUBIC] will actually be honoured on this device. */
    val supportsBicubic: Boolean get() = BicubicShader.available

    fun draw(
        canvas: Canvas,
        layers: List<Layer>,
        skipIndex: Int = -1,
        sampling: Sampling = Sampling.BILINEAR,
        colorFilterFor: (Int) -> ColorFilter? = { null }
    ) {
        val effective = if (sampling == Sampling.BICUBIC && !BicubicShader.available) {
            Sampling.BILINEAR
        } else sampling

        val paints = threadPaints.get() ?: PaintSet().also(threadPaints::set)
        val liveBitmaps = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Bitmap, Boolean>()
        ).apply {
            layers.forEach { layer ->
                add(layer.bitmap)
                layer.maskBitmap?.let(::add)
            }
        }
        paints.largeBitmapTiles.keys.removeAll { it !in liveBitmaps }
        val paint = preparePaint(paints.layer, effective)
        var i = 0
        while (i < layers.size) {
            val layer = layers[i]
            if (i == skipIndex || !layer.isVisible || layer.isClippingMask) {
                i++
                continue
            }

            // Gather the run of visible clipping-mask layers stacked directly above this base.
            var j = i + 1
            var hasVisibleClippedLayer = false
            while (j < layers.size && layers[j].isClippingMask) {
                if (layers[j].isVisible && j != skipIndex) hasVisibleClippedLayer = true
                j++
            }

            if (!hasVisibleClippedLayer) {
                drawSingle(canvas, layer, i, paint, effective, colorFilterFor)
            } else {
                // A Porter-Duff blend mode composited against a fresh, fully transparent
                // saveLayer buffer always reduces to a plain normal draw (nothing for it to
                // blend with yet) - so the base's own opacity/blend-mode has to be applied once,
                // as the group's own flattening paint, rather than while drawing into the group.
                val groupPaint = preparePaint(paints.group, effective)
                groupPaint.alpha = (layer.opacity * 255).toInt()
                groupPaint.blendMode = AndroidBlendMode.SRC_OVER
                layer.blendMode.toAndroidBlendMode()?.let { groupPaint.blendMode = it }
                canvas.saveLayer(null, groupPaint)
                val basePaint = preparePaint(paint, effective)
                basePaint.colorFilter = colorFilterFor(i)
                drawLayerPixels(canvas, layer, basePaint, effective, paints)
                for (idx in i + 1 until j) {
                    if (!layers[idx].isVisible || idx == skipIndex) continue
                    val clippedLayer = layers[idx]

                    // Build this clipped layer on transparent pixels, cut it by the *base* alpha,
                    // then composite that result onto the group using the clipped layer's own
                    // blend mode. SRC_ATOP by itself performs the clipping but silently forces
                    // every clipped layer to Normal, which made Multiply/Screen/etc. appear broken.
                    val clippedGroupPaint = preparePaint(paints.clippedGroup, effective)
                    clippedGroupPaint.alpha = (clippedLayer.opacity * 255).toInt().coerceIn(0, 255)
                    clippedGroupPaint.blendMode = AndroidBlendMode.SRC_OVER
                    clippedLayer.blendMode.toAndroidBlendMode()?.let {
                        clippedGroupPaint.blendMode = it
                    }
                    canvas.saveLayer(null, clippedGroupPaint)

                    val clipPaint = preparePaint(paints.clipped, effective)
                    clipPaint.colorFilter = colorFilterFor(idx)
                    drawLayerPixels(canvas, clippedLayer, clipPaint, effective, paints)

                    val maskPaint = preparePaint(paints.mask, effective)
                    maskPaint.blendMode = AndroidBlendMode.DST_IN
                    drawBitmap(canvas, layer.bitmap, maskPaint, effective)
                    applyLayerMask(canvas, layer, effective, paints)
                    canvas.restore()
                }
                canvas.restore()
            }
            i = j
        }
    }

    /**
     * Rasterizes an upper/lower pair for "Merge Down" while keeping the lower layer's external
     * opacity/blend relationship available to the caller. In particular, a clipping-mask upper is
     * cut by the lower alpha before it is merged; drawing its bitmap directly used to spill hidden
     * pixels outside the mask as soon as the two layers were merged.
     */
    fun drawMergedPair(canvas: Canvas, lower: Layer, upper: Layer) {
        val paints = threadPaints.get() ?: PaintSet().also(threadPaints::set)
        val normal = preparePaint(paints.layer, Sampling.BILINEAR)
        drawLayerPixels(canvas, lower, normal, Sampling.BILINEAR, paints)
        if (!upper.isVisible) return

        if (upper.isClippingMask) {
            val composite = preparePaint(paints.clippedGroup, Sampling.BILINEAR)
            composite.alpha = (upper.opacity * 255).toInt().coerceIn(0, 255)
            composite.blendMode = AndroidBlendMode.SRC_OVER
            upper.blendMode.toAndroidBlendMode()?.let { composite.blendMode = it }
            canvas.saveLayer(null, composite)
            drawLayerPixels(
                canvas,
                upper,
                preparePaint(paints.clipped, Sampling.BILINEAR),
                Sampling.BILINEAR,
                paints
            )
            val mask = preparePaint(paints.mask, Sampling.BILINEAR)
            mask.blendMode = AndroidBlendMode.DST_IN
            canvas.drawBitmap(lower.bitmap, 0f, 0f, mask)
            applyLayerMask(canvas, lower, Sampling.BILINEAR, paints)
            canvas.restore()
        } else {
            val paint = preparePaint(paints.clipped, Sampling.BILINEAR)
            paint.alpha = (upper.opacity * 255).toInt().coerceIn(0, 255)
            paint.blendMode = AndroidBlendMode.SRC_OVER
            upper.blendMode.toAndroidBlendMode()?.let { paint.blendMode = it }
            drawLayerPixels(canvas, upper, paint, Sampling.BILINEAR, paints)
        }
    }

    private fun preparePaint(paint: Paint, sampling: Sampling): Paint {
        paint.reset()
        paint.isFilterBitmap = sampling != Sampling.NEAREST
        return paint
    }

    private fun drawSingle(
        canvas: Canvas, layer: Layer, index: Int, paint: Paint,
        sampling: Sampling, colorFilterFor: (Int) -> ColorFilter?
    ) {
        paint.alpha = (layer.opacity * 255).toInt()
        paint.blendMode = AndroidBlendMode.SRC_OVER
        layer.blendMode.toAndroidBlendMode()?.let { paint.blendMode = it }
        paint.colorFilter = colorFilterFor(index)
        drawLayerPixels(canvas, layer, paint, sampling, threadPaints.get() ?: PaintSet())
        paint.colorFilter = null
    }

    /** Draws one layer through its optional grayscale mask without affecting layers below it. */
    private fun drawLayerPixels(
        canvas: Canvas,
        layer: Layer,
        compositePaint: Paint,
        sampling: Sampling,
        paints: PaintSet
    ) {
        if (layer.maskBitmap == null) {
            drawBitmap(canvas, layer.bitmap, compositePaint, sampling)
            return
        }

        val pixelFilter = compositePaint.colorFilter
        val outer = paints.maskedComposite
        outer.set(compositePaint)
        outer.colorFilter = null
        canvas.saveLayer(null, outer)

        val pixels = preparePaint(paints.maskedPixels, sampling)
        pixels.colorFilter = pixelFilter
        drawBitmap(canvas, layer.bitmap, pixels, sampling)
        applyLayerMask(canvas, layer, sampling, paints)
        canvas.restore()
    }

    /** Multiplies the current isolated layer/group by mask luminance (white reveal, black hide). */
    private fun applyLayerMask(
        canvas: Canvas,
        layer: Layer,
        sampling: Sampling,
        paints: PaintSet
    ) {
        val bitmap = layer.maskBitmap ?: return
        val mask = preparePaint(paints.mask, sampling)
        mask.blendMode = AndroidBlendMode.DST_IN
        mask.colorFilter = MASK_LUMINANCE_TO_ALPHA
        drawBitmap(canvas, bitmap, mask, sampling)
        mask.colorFilter = null
    }

    /** Draws [bitmap] at the origin, through the bicubic shader when asked and available. */
    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, paint: Paint, sampling: Sampling) {
        if (canvas.isHardwareAccelerated && bitmap.allocationByteCount.toLong() > MAX_DIRECT_BITMAP_BYTES) {
            drawOversizedBitmapTiled(canvas, bitmap, paint, sampling)
            return
        }
        if (sampling == Sampling.BICUBIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shader = BicubicShader.shaderFor(bitmap)
            if (shader != null) {
                val previous = paint.shader
                paint.shader = shader
                // The shader supplies the colour; the rectangle only says where it is painted. The
                // paint's alpha, blend mode and colour filter still apply exactly as they do to a
                // plain drawBitmap.
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
                paint.shader = previous
                return
            }
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    /**
     * RecordingCanvas refuses direct draws of bitmaps around 100 MiB. Feeding the full bitmap to a
     * shader avoids that exception but uploads the complete open canvas to GPU memory and brought
     * the test tablet back above 700 MiB. This path instead copies only intersecting 1024px pieces.
     * At overview zoom each piece is downsampled to approximately its on-screen resolution, so the
     * cache tracks viewport pixels rather than world-canvas pixels.
     */
    private fun drawOversizedBitmapTiled(
        canvas: Canvas,
        bitmap: Bitmap,
        paint: Paint,
        sampling: Sampling
    ) {
        val clip = Rect()
        if (!canvas.getClipBounds(clip)) return
        val clipLeft = maxOf(0, clip.left)
        val clipTop = maxOf(0, clip.top)
        val clipRight = minOf(bitmap.width, clip.right)
        val clipBottom = minOf(bitmap.height, clip.bottom)
        if (clipLeft >= clipRight || clipTop >= clipBottom) return

        val matrixValues = FloatArray(9)
        canvas.matrix.getValues(matrixValues)
        val scale = kotlin.math.hypot(
            matrixValues[Matrix.MSCALE_X].toDouble(),
            matrixValues[Matrix.MSKEW_Y].toDouble()
        ).toFloat().coerceAtLeast(0.01f)
        val sample = when {
            sampling == Sampling.NEAREST || scale >= 0.75f -> 1
            scale >= 0.375f -> 2
            scale >= 0.1875f -> 4
            else -> 8
        }

        val paints = threadPaints.get() ?: PaintSet().also(threadPaints::set)
        val tileSet = paints.largeBitmapTiles[bitmap] ?: BitmapTileSet(bitmap.generationId).also {
            paints.largeBitmapTiles[bitmap] = it
        }
        if (tileSet.generation != bitmap.generationId) {
            tileSet.clear()
            tileSet.generation = bitmap.generationId
        }

        val startX = Math.floorDiv(clipLeft, RENDER_TILE_SIZE) * RENDER_TILE_SIZE
        val startY = Math.floorDiv(clipTop, RENDER_TILE_SIZE) * RENDER_TILE_SIZE
        var y = startY
        while (y < clipBottom) {
            var x = startX
            while (x < clipRight) {
                val cellRight = minOf(bitmap.width, x + RENDER_TILE_SIZE)
                val cellBottom = minOf(bitmap.height, y + RENDER_TILE_SIZE)
                val key = TileKey(x, y, sample)
                val tile = tileSet.tiles[key] ?: createRenderTile(
                    bitmap, x, y, cellRight, cellBottom, sample, sampling
                ).also { created ->
                    tileSet.tiles[key] = created
                    tileSet.bytes += created.allocationByteCount.toLong()
                    trimTileSet(tileSet)
                }

                // Each cached tile contains one output-pixel gutter. Clip to its exact cell while
                // drawing the expanded source bounds, so filtering sees neighbours without seams
                // or double-compositing alpha along tile boundaries.
                val pad = if (sampling == Sampling.NEAREST) 0 else sample
                val sourceLeft = maxOf(0, x - pad)
                val sourceTop = maxOf(0, y - pad)
                val sourceRight = minOf(bitmap.width, cellRight + pad)
                val sourceBottom = minOf(bitmap.height, cellBottom + pad)
                canvas.save()
                canvas.clipRect(
                    x.toFloat(), y.toFloat(), cellRight.toFloat(), cellBottom.toFloat()
                )
                canvas.drawBitmap(
                    tile,
                    null,
                    RectF(
                        sourceLeft.toFloat(), sourceTop.toFloat(),
                        sourceRight.toFloat(), sourceBottom.toFloat()
                    ),
                    paint
                )
                canvas.restore()
                x += RENDER_TILE_SIZE
            }
            y += RENDER_TILE_SIZE
        }
    }

    private fun createRenderTile(
        source: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sample: Int,
        sampling: Sampling
    ): Bitmap {
        val pad = if (sampling == Sampling.NEAREST) 0 else sample
        val sourceLeft = maxOf(0, left - pad)
        val sourceTop = maxOf(0, top - pad)
        val sourceRight = minOf(source.width, right + pad)
        val sourceBottom = minOf(source.height, bottom + pad)
        val outWidth = kotlin.math.ceil((sourceRight - sourceLeft) / sample.toDouble()).toInt()
            .coerceAtLeast(1)
        val outHeight = kotlin.math.ceil((sourceBottom - sourceTop) / sample.toDouble()).toInt()
            .coerceAtLeast(1)
        return Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { tile ->
            val copy = Paint().apply {
                isFilterBitmap = sampling != Sampling.NEAREST
                blendMode = AndroidBlendMode.SRC
            }
            Canvas(tile).drawBitmap(
                source,
                Rect(sourceLeft, sourceTop, sourceRight, sourceBottom),
                Rect(0, 0, outWidth, outHeight),
                copy
            )
        }
    }

    private fun trimTileSet(tileSet: BitmapTileSet) {
        val iterator = tileSet.tiles.entries.iterator()
        while (tileSet.bytes > MAX_TILE_CACHE_BYTES && iterator.hasNext()) {
            val victim = iterator.next().value
            tileSet.bytes -= victim.allocationByteCount.toLong()
            iterator.remove()
        }
    }

    /** Keep unchanged viewport tiles after a local brush edit instead of rebuilding the overview. */
    fun invalidateBitmapRegion(bitmap: Bitmap, dirty: RectF) {
        val paints = threadPaints.get() ?: return
        val tileSet = paints.largeBitmapTiles[bitmap] ?: return
        val iterator = tileSet.tiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            if (
                key.x < dirty.right && key.x + RENDER_TILE_SIZE > dirty.left &&
                key.y < dirty.bottom && key.y + RENDER_TILE_SIZE > dirty.top
            ) {
                tileSet.bytes -= entry.value.allocationByteCount.toLong()
                iterator.remove()
            }
        }
        tileSet.generation = bitmap.generationId
    }

    /** Leave headroom below RecordingCanvas' platform guard, which is implementation-specific. */
    private const val MAX_DIRECT_BITMAP_BYTES = 90L * 1024L * 1024L
    private const val RENDER_TILE_SIZE = 1024
    private const val MAX_TILE_CACHE_BYTES = 32L * 1024L * 1024L

    private val MASK_LUMINANCE_TO_ALPHA = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0f, 0f, 0f, 0f, 255f,
                0.2126f, 0.7152f, 0.0722f, 0f, 0f
            )
        )
    )

    /**
     * Bicubic (Mitchell-Netravali, B = C = 1/3) resampling as an AGSL runtime shader.
     *
     * Created lazily and once. Building a RuntimeShader compiles its source, which is far too slow
     * to do per layer per frame, so a single instance is shared and only its input image is
     * swapped between draws. If the platform refuses the shader for any reason this switches itself
     * off for good and the compositor quietly falls back to bilinear - a display nicety must never
     * be able to take the canvas down.
     */
    private object BicubicShader {
        private const val SOURCE = """
            uniform shader image;

            float w(float x) {
                x = abs(x);
                if (x < 1.0) {
                    return (7.0 * x * x * x - 12.0 * x * x + 5.3333333) / 6.0;
                } else if (x < 2.0) {
                    return (-2.3333333 * x * x * x + 12.0 * x * x - 20.0 * x + 10.6666667) / 6.0;
                }
                return 0.0;
            }

            half4 main(float2 coord) {
                float2 p = coord - 0.5;
                float2 base = floor(p);
                float2 f = p - base;
                half4 sum = half4(0.0);
                for (int j = -1; j <= 2; j++) {
                    float wy = w(float(j) - f.y);
                    for (int i = -1; i <= 2; i++) {
                        float wx = w(float(i) - f.x);
                        sum += image.eval(base + float2(float(i), float(j)) + 0.5) * half(wx * wy);
                    }
                }
                // The filter has negative lobes, so it can overshoot. Clamp, and keep colour no
                // brighter than alpha so the result is still valid premultiplied colour.
                sum = clamp(sum, half4(0.0), half4(1.0));
                sum.rgb = min(sum.rgb, half3(sum.a));
                return sum;
            }
        """

        private var runtime: RuntimeShader? = null
        private var failed = false

        val available: Boolean get() = Build.VERSION.SDK_INT >= 33 && !failed

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun shaderFor(bitmap: Bitmap): Shader? {
            if (!available) return null
            return try {
                val shader = runtime ?: RuntimeShader(SOURCE).also { runtime = it }
                val image = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                // The child must sample exactly the pixel it is asked for; the shader above does
                // all the interpolation, and a bilinear child underneath would blur it twice.
                image.filterMode = BitmapShader.FILTER_MODE_NEAREST
                shader.setInputShader("image", image)
                shader
            } catch (t: Throwable) {
                failed = true
                Log.w("LayerCompositor", "Bicubic display unavailable, using bilinear", t)
                null
            }
        }
    }
}
