package com.procreate.android.adjust

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Creative looks, as distinct from the corrective sliders in [ImageAdjustments].
 *
 * The split is deliberate: adjustments answer "is this image right?" and filters answer "what if it
 * looked like this?". Keeping them apart means the adjustments panel stays a precise instrument
 * while filters can be one-tap and opinionated.
 *
 * Same discipline as [ImageAdjustments]: no `android.graphics` import anywhere, so every function
 * here is covered by real JVM tests instead of being verified by squinting at a tablet.
 */
object ImageFilters {

    /** Every filter the panel offers, in the order it shows them. */
    enum class Filter {
        SOFT_BLUR, MOTION_BLUR, ZOOM_BLUR, FROSTED_GLASS,
        SEPIA, VINTAGE, NOIR, POSTERIZE, VIGNETTE, GRAIN, BLOOM, COOL, WARM, SKETCH,
        CHROMATIC, HALFTONE, COLOR_BALANCE, OIL_GLAZE, NEON_GLOW
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Applies [filter] at [strength] (0..1), mixing against the original so any filter can be
     * dialled back. A filter that is all-or-nothing is a filter people use once.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        filter: Filter,
        strength: Float = 1f
    ) {
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return
        val original = if (amount < 1f) pixels.copyOf() else null

        when (filter) {
            Filter.SOFT_BLUR -> softBlur(pixels, width, height)
            Filter.MOTION_BLUR -> motionBlur(pixels, width, height)
            Filter.ZOOM_BLUR -> zoomBlur(pixels, width, height)
            Filter.FROSTED_GLASS -> frostedGlass(pixels, width, height)
            Filter.SEPIA -> sepia(pixels)
            Filter.VINTAGE -> vintage(pixels, width, height)
            Filter.NOIR -> noir(pixels)
            Filter.POSTERIZE -> posterize(pixels, levels = 5)
            Filter.VIGNETTE -> vignette(pixels, width, height, 0.85f)
            Filter.GRAIN -> grain(pixels, 22)
            Filter.BLOOM -> bloom(pixels, width, height)
            Filter.COOL -> temperature(pixels, -28)
            Filter.WARM -> temperature(pixels, 28)
            Filter.SKETCH -> sketch(pixels, width, height)
            Filter.CHROMATIC -> chromaticAberration(pixels, width, height)
            Filter.HALFTONE -> halftone(pixels, width, height)
            Filter.COLOR_BALANCE -> colorBalance(pixels)
            Filter.OIL_GLAZE -> oilGlaze(pixels, width, height)
            Filter.NEON_GLOW -> neonGlow(pixels, width, height)
        }

        if (original != null) blend(pixels, original, amount)
    }

    /** Smooth defocus, useful for backgrounds and depth-of-field cleanup. */
    private fun softBlur(pixels: IntArray, width: Int, height: Int) {
        val radius = (minOf(width, height) / 70).coerceIn(2, 28)
        ImageAdjustments.gaussianBlur(pixels, width, height, radius)
    }

    /** Directional horizontal blur with a sliding window, O(width * height) rather than O(radius). */
    private fun motionBlur(pixels: IntArray, width: Int, height: Int) {
        if (width <= 1 || height <= 0) return
        val source = pixels.copyOf()
        val radius = (minOf(width, height) / 60).coerceIn(2, 30)
        for (y in 0 until height) {
            val row = y * width
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            val initialLast = radius.coerceAtMost(width - 1)
            for (sx in 0..initialLast) {
                val p = source[row + sx]
                sumA += p ushr 24 and 0xFF
                sumR += p shr 16 and 0xFF
                sumG += p shr 8 and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until width) {
                val first = (x - radius).coerceAtLeast(0)
                val last = (x + radius).coerceAtMost(width - 1)
                val count = last - first + 1
                pixels[row + x] = ((sumA / count) shl 24) or
                    ((sumR / count) shl 16) or ((sumG / count) shl 8) or (sumB / count)

                val removeX = x - radius
                if (removeX >= 0) {
                    val p = source[row + removeX]
                    sumA -= p ushr 24 and 0xFF
                    sumR -= p shr 16 and 0xFF
                    sumG -= p shr 8 and 0xFF
                    sumB -= p and 0xFF
                }
                val addX = x + radius + 1
                if (addX < width) {
                    val p = source[row + addX]
                    sumA += p ushr 24 and 0xFF
                    sumR += p shr 16 and 0xFF
                    sumG += p shr 8 and 0xFF
                    sumB += p and 0xFF
                }
            }
        }
    }

    /** Radial zoom blur: samples a short trail from every pixel toward the optical centre. */
    private fun zoomBlur(pixels: IntArray, width: Int, height: Int) {
        if (width <= 1 || height <= 1) return
        val source = pixels.copyOf()
        val cx = (width - 1) * 0.5f
        val cy = (height - 1) * 0.5f
        val samples = 7
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumA = 0
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (sample in 0 until samples) {
                    val t = sample / (samples - 1f) * 0.12f
                    val sx = (x + (cx - x) * t).roundToInt().coerceIn(0, width - 1)
                    val sy = (y + (cy - y) * t).roundToInt().coerceIn(0, height - 1)
                    val p = source[sy * width + sx]
                    sumA += p ushr 24 and 0xFF
                    sumR += p shr 16 and 0xFF
                    sumG += p shr 8 and 0xFF
                    sumB += p and 0xFF
                }
                pixels[y * width + x] = ((sumA / samples) shl 24) or
                    ((sumR / samples) shl 16) or ((sumG / samples) shl 8) or (sumB / samples)
            }
        }
    }

    /** Deterministic frosted-glass diffusion; repeat previews therefore match the applied result. */
    private fun frostedGlass(pixels: IntArray, width: Int, height: Int) {
        if (width <= 1 || height <= 1) return
        val source = pixels.copyOf()
        val radius = (minOf(width, height) / 110).coerceIn(2, 12)
        var state = 0x46A51
        for (y in 0 until height) {
            for (x in 0 until width) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                val dx = ((state and 0xFF) % (radius * 2 + 1)) - radius
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                val dy = ((state ushr 8 and 0xFF) % (radius * 2 + 1)) - radius
                val sx = (x + dx).coerceIn(0, width - 1)
                val sy = (y + dy).coerceIn(0, height - 1)
                pixels[y * width + x] = source[sy * width + sx]
            }
        }
    }

    /** Mixes [pixels] toward [original] so only [amount] of the effect remains. */
    private fun blend(pixels: IntArray, original: IntArray, amount: Float) {
        val inv = 1f - amount
        for (i in pixels.indices) {
            val a = pixels[i]
            val b = original[i]
            pixels[i] = ((a ushr 24 and 0xFF) shl 24) or
                (clamp255(((a shr 16 and 0xFF) * amount + (b shr 16 and 0xFF) * inv).roundToInt()) shl 16) or
                (clamp255(((a shr 8 and 0xFF) * amount + (b shr 8 and 0xFF) * inv).roundToInt()) shl 8) or
                clamp255(((a and 0xFF) * amount + (b and 0xFF) * inv).roundToInt())
        }
    }

    private fun sepia(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            pixels[i] = (alpha shl 24) or
                (clamp255((r * 0.393f + g * 0.769f + b * 0.189f).roundToInt()) shl 16) or
                (clamp255((r * 0.349f + g * 0.686f + b * 0.168f).roundToInt()) shl 8) or
                clamp255((r * 0.272f + g * 0.534f + b * 0.131f).roundToInt())
        }
    }

    /** Faded film: sepia-ish warmth, lifted blacks, and a soft corner falloff. */
    private fun vintage(pixels: IntArray, width: Int, height: Int) {
        sepia(pixels)
        // Lifting the blacks is what actually reads as "old photo" - aged prints lose their
        // deepest tone long before they lose colour.
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            pixels[i] = (alpha shl 24) or
                (clamp255(((p shr 16 and 0xFF) * 0.88f + 28f).roundToInt()) shl 16) or
                (clamp255(((p shr 8 and 0xFF) * 0.88f + 24f).roundToInt()) shl 8) or
                clamp255(((p and 0xFF) * 0.88f + 18f).roundToInt())
        }
        vignette(pixels, width, height, 0.7f)
    }

    /** High-contrast black and white. */
    private fun noir(pixels: IntArray) {
        ImageAdjustments.grayscale(pixels)
        ImageAdjustments.brightnessContrast(pixels, -0.04f, 0.42f)
    }

    /** Collapses each channel to [levels] steps - a poster/screen-print look. */
    fun posterize(pixels: IntArray, levels: Int) {
        val n = levels.coerceIn(2, 32)
        val step = 255f / (n - 1)
        val lut = IntArray(256) { i -> clamp255(((i / step).roundToInt() * step).roundToInt()) }
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            pixels[i] = (alpha shl 24) or
                (lut[p shr 16 and 0xFF] shl 16) or
                (lut[p shr 8 and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }

    /** Darkens toward the corners. [power] is how deep the corners go, 0..1. */
    fun vignette(pixels: IntArray, width: Int, height: Int, power: Float) {
        if (width < 2 || height < 2) return
        val cx = width / 2f
        val cy = height / 2f
        val maxDist = hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val depth = power.coerceIn(0f, 1f)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val p = pixels[i]
                val alpha = p ushr 24 and 0xFF
                if (alpha == 0) continue
                val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() / maxDist
                // Squared falloff, and only past the halfway mark, so the centre stays untouched
                // and the darkening arrives gradually rather than as a visible ring.
                val t = ((d - 0.45f) / 0.55f).coerceIn(0f, 1f)
                val f = 1f - depth * t * t
                pixels[i] = (alpha shl 24) or
                    (clamp255(((p shr 16 and 0xFF) * f).roundToInt()) shl 16) or
                    (clamp255(((p shr 8 and 0xFF) * f).roundToInt()) shl 8) or
                    clamp255(((p and 0xFF) * f).roundToInt())
            }
        }
    }

    /**
     * Film grain. Uses a fixed seed so the same image filtered twice looks identical - a filter
     * that changes every time it is applied cannot be previewed honestly.
     */
    fun grain(pixels: IntArray, intensity: Int, seed: Int = 0x5EED) {
        var state = seed
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            // xorshift: cheap, deterministic, and good enough for visual noise.
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val n = (state and 0xFF) * intensity / 255 - intensity / 2
            pixels[i] = (alpha shl 24) or
                (clamp255((p shr 16 and 0xFF) + n) shl 16) or
                (clamp255((p shr 8 and 0xFF) + n) shl 8) or
                clamp255((p and 0xFF) + n)
        }
    }

    /** Glow: highlights blurred and screened back over the image. */
    private fun bloom(pixels: IntArray, width: Int, height: Int) {
        val glow = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            // Only the brightest fifth blooms; letting mid-tones glow just fogs the whole image.
            glow[i] = if (luma > 200) p else (p ushr 24 and 0xFF) shl 24
        }
        ImageAdjustments.gaussianBlur(glow, width, height, maxOf(2, minOf(width, height) / 90))

        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            val g = glow[i]
            val ga = g ushr 24 and 0xFF
            if (ga == 0) continue
            pixels[i] = (alpha shl 24) or
                (screen(p shr 16 and 0xFF, (g shr 16 and 0xFF) * ga / 255) shl 16) or
                (screen(p shr 8 and 0xFF, (g shr 8 and 0xFF) * ga / 255) shl 8) or
                screen(p and 0xFF, (g and 0xFF) * ga / 255)
        }
    }

    private fun screen(a: Int, b: Int): Int = 255 - (255 - a) * (255 - b) / 255

    /** Shifts white balance. Positive [amount] warms, negative cools. */
    fun temperature(pixels: IntArray, amount: Int) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            pixels[i] = (alpha shl 24) or
                (clamp255((p shr 16 and 0xFF) + amount) shl 16) or
                (clamp255(p shr 8 and 0xFF) shl 8) or
                clamp255((p and 0xFF) - amount)
        }
    }

    /**
     * Pencil-sketch look: the classic colour-dodge of a greyscale image against its blurred,
     * inverted self, which leaves bright paper with dark lines wherever detail changes fast.
     */
    private fun sketch(pixels: IntArray, width: Int, height: Int) {
        ImageAdjustments.grayscale(pixels)
        val inverted = pixels.copyOf()
        ImageAdjustments.invert(inverted)
        ImageAdjustments.gaussianBlur(inverted, width, height, maxOf(2, minOf(width, height) / 120))

        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            val base = p shr 16 and 0xFF
            val top = inverted[i] shr 16 and 0xFF
            val v = colorDodge(base, top)
            pixels[i] = (alpha shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun colorDodge(base: Int, top: Int): Int =
        if (top >= 255) 255 else clamp255(base * 255 / (255 - top))

    /**
     * انحراف لوني سينمائي (Chromatic Aberration): إزاحة أفقية طفيفة لقنوات الأحمر والأزرق.
     */
    private fun chromaticAberration(pixels: IntArray, width: Int, height: Int) {
        val copy = pixels.copyOf()
        val shift = maxOf(2, minOf(width, height) / 140)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val p = copy[row + x]
                val alpha = p ushr 24 and 0xFF
                if (alpha == 0) continue
                val rX = (x - shift).coerceIn(0, width - 1)
                val bX = (x + shift).coerceIn(0, width - 1)
                val r = copy[row + rX] shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = copy[row + bX] and 0xFF
                pixels[row + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * شاشة النقاط والكوميكس (Halftone): تحويل التدرج إلى مصفوفة نقطية بوب آرت.
     */
    private fun halftone(pixels: IntArray, width: Int, height: Int) {
        val cellSize = maxOf(4, minOf(width, height) / 80)
        val copy = pixels.copyOf()
        for (cy in 0 until height step cellSize) {
            for (cx in 0 until width step cellSize) {
                var sumLuma = 0f
                var count = 0
                val endY = minOf(cy + cellSize, height)
                val endX = minOf(cx + cellSize, width)
                for (y in cy until endY) {
                    for (x in cx until endX) {
                        val p = copy[y * width + x]
                        val r = p shr 16 and 0xFF
                        val g = p shr 8 and 0xFF
                        val b = p and 0xFF
                        sumLuma += 0.299f * r + 0.587f * g + 0.114f * b
                        count++
                    }
                }
                val avgLuma = if (count > 0) sumLuma / count else 255f
                val dotRadius = (cellSize * 0.5f) * (1f - (avgLuma / 255f))
                val midX = cx + cellSize / 2f
                val midY = cy + cellSize / 2f
                val maxR2 = dotRadius * dotRadius

                for (y in cy until endY) {
                    for (x in cx until endX) {
                        val idx = y * width + x
                        val p = copy[idx]
                        val alpha = p ushr 24 and 0xFF
                        if (alpha == 0) continue
                        val dx = x - midX
                        val dy = y - midY
                        val d2 = dx * dx + dy * dy
                        if (d2 <= maxR2) {
                            val r = (p shr 16 and 0xFF) * 2 / 5
                            val g = (p shr 8 and 0xFF) * 2 / 5
                            val b = (p and 0xFF) * 2 / 5
                            pixels[idx] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                        } else {
                            val r = clamp255((p shr 16 and 0xFF) + 40)
                            val g = clamp255((p shr 8 and 0xFF) + 40)
                            val b = clamp255((p and 0xFF) + 40)
                            pixels[idx] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
            }
        }
    }

    /**
     * موازنة الألوان (Color Balance): نغمات دافئة للإضاءة العالية وباردة للظلال.
     */
    private fun colorBalance(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            if (alpha == 0) continue
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val rShift: Float
            val bShift: Float
            if (luma < 128f) {
                val t = (128f - luma) / 128f
                rShift = -22f * t
                bShift = 26f * t
            } else {
                val t = (luma - 128f) / 127f
                rShift = 28f * t
                bShift = -16f * t
            }
            pixels[i] = (alpha shl 24) or
                (clamp255((r + rShift).roundToInt()) shl 16) or
                (clamp255(g) shl 8) or
                clamp255((b + bShift).roundToInt())
        }
    }

    /**
     * طلاء زيتي وتنعيم (Oil Paint Glaze): تنعيم الأسطح مع إبقاء الحواف بارزة كالطلاء الزيتي.
     */
    private fun oilGlaze(pixels: IntArray, width: Int, height: Int) {
        val copy = pixels.copyOf()
        val r = 2
        for (y in r until height - r) {
            val row = y * width
            for (x in r until width - r) {
                val p = copy[row + x]
                val alpha = p ushr 24 and 0xFF
                if (alpha == 0) continue

                var bestVar = Float.MAX_VALUE
                var bestR = p shr 16 and 0xFF
                var bestG = p shr 8 and 0xFF
                var bestB = p and 0xFF

                val qRanges = arrayOf(
                    -r..0 to -r..0,
                    0..r to -r..0,
                    -r..0 to 0..r,
                    0..r to 0..r
                )
                for ((dyR, dxR) in qRanges) {
                    var sumR = 0; var sumG = 0; var sumB = 0
                    var sumSq = 0.0
                    var count = 0
                    for (dy in dyR) {
                        val qRow = (y + dy) * width
                        for (dx in dxR) {
                            val qp = copy[qRow + (x + dx)]
                            val qr = qp shr 16 and 0xFF
                            val qg = qp shr 8 and 0xFF
                            val qb = qp and 0xFF
                            sumR += qr; sumG += qg; sumB += qb
                            val l = 0.299 * qr + 0.587 * qg + 0.114 * qb
                            sumSq += l * l
                            count++
                        }
                    }
                    if (count > 0) {
                        val meanL = sumSq / count
                        val variance = (sumSq - (meanL * meanL)).toFloat()
                        if (variance < bestVar) {
                            bestVar = variance
                            bestR = sumR / count
                            bestG = sumG / count
                            bestB = sumB / count
                        }
                    }
                }
                pixels[row + x] = (alpha shl 24) or (bestR shl 16) or (bestG shl 8) or bestB
            }
        }
    }

    /**
     * توهج النيون (Neon Glow): إبراز الحواف بتوهج فوسفوري عصري.
     */
    private fun neonGlow(pixels: IntArray, width: Int, height: Int) {
        val copy = pixels.copyOf()
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val p = copy[row + x]
                val alpha = p ushr 24 and 0xFF
                if (alpha == 0) continue

                fun luma(c: Int): Float {
                    return 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
                }

                val gx = -luma(copy[(y - 1) * width + (x - 1)]) + luma(copy[(y - 1) * width + (x + 1)]) +
                        -2f * luma(copy[row + (x - 1)]) + 2f * luma(copy[row + (x + 1)]) +
                        -luma(copy[(y + 1) * width + (x - 1)]) + luma(copy[(y + 1) * width + (x + 1)])

                val gy = -luma(copy[(y - 1) * width + (x - 1)]) - 2f * luma(copy[(y - 1) * width + x]) - luma(copy[(y - 1) * width + (x + 1)]) +
                        luma(copy[(y + 1) * width + (x - 1)]) + 2f * luma(copy[(y + 1) * width + x]) + luma(copy[(y + 1) * width + (x + 1)])

                val edge = hypot(gx.toDouble(), gy.toDouble()).toFloat().coerceIn(0f, 255f)
                val r = clamp255((edge * 1.1f + (p shr 16 and 0xFF) * 0.35f).roundToInt())
                val g = clamp255((edge * 0.7f + (p shr 8 and 0xFF) * 0.3f).roundToInt())
                val b = clamp255((edge * 1.5f + (p and 0xFF) * 0.5f).roundToInt())
                pixels[row + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
