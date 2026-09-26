package com.procreate.android.adjust

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Per-pixel image adjustments, written against a plain `IntArray` of ARGB pixels.
 *
 * Deliberately free of every `android.graphics` import, including [android.graphics.Color]. Those
 * classes are signature-only stubs on the unit-test classpath and throw "not mocked" the moment
 * they are touched, which would leave the arithmetic here - the part that is actually easy to get
 * wrong - untestable. Channels are unpacked with shifts instead, so the whole file runs on the JVM
 * and every operation below is covered by real tests.
 *
 * All functions mutate [pixels] in place: a layer bitmap on a large canvas is tens of megabytes,
 * and allocating a second copy per adjustment is what turns a slider into a stutter.
 */
object ImageAdjustments {

    /**
     * Reusable storage for interactive blur callers.
     *
     * A brush can request dozens of small blurs every second. Allocating the four premultiplied
     * channels plus a pass buffer for every request creates enough short-lived arrays to make the
     * runtime pause for garbage collection while the pen is still moving. Layer-wide adjustments
     * can keep using the simple overload below; latency-sensitive tools retain one workspace.
     */
    class BlurWorkspace {
        internal var alpha = IntArray(0)
        internal var red = IntArray(0)
        internal var green = IntArray(0)
        internal var blue = IntArray(0)
        internal var pass = IntArray(0)

        internal fun ensureCapacity(size: Int) {
            if (alpha.size >= size) return
            alpha = IntArray(size)
            red = IntArray(size)
            green = IntArray(size)
            blue = IntArray(size)
            pass = IntArray(size)
        }
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * @param brightness -1..1, added as a fraction of full scale.
     * @param contrast -1..1, where 0 leaves the image alone.
     */
    fun brightnessContrast(pixels: IntArray, brightness: Float, contrast: Float) {
        // Standard contrast factor, pivoting around mid-grey so raising contrast does not also
        // brighten or darken the image overall.
        val c = contrast.coerceIn(-1f, 1f) * 255f
        val factor = (259f * (c + 255f)) / (255f * (259f - c))
        val offset = brightness.coerceIn(-1f, 1f) * 255f

        // A 256-entry lookup instead of the same arithmetic per channel per pixel: identical
        // results, and it turns three multiplies per pixel into three array reads.
        val lut = IntArray(256) { i ->
            clamp255((factor * (i - 128f) + 128f + offset).roundToInt())
        }

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            if (a == 0) continue
            pixels[i] = (a shl 24) or
                (lut[p shr 16 and 0xFF] shl 16) or
                (lut[p shr 8 and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }

    /** @param amount 0 = greyscale, 1 = unchanged, >1 = more saturated. */
    fun saturation(pixels: IntArray, amount: Float) {
        val s = amount.coerceIn(0f, 3f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            if (a == 0) continue
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            // Rec. 601 luma. Averaging the channels instead would make a saturated blue and a
            // saturated yellow desaturate to the same grey, which is visibly wrong.
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            pixels[i] = (a shl 24) or
                (clamp255((luma + (r - luma) * s).roundToInt()) shl 16) or
                (clamp255((luma + (g - luma) * s).roundToInt()) shl 8) or
                clamp255((luma + (b - luma) * s).roundToInt())
        }
    }

    /** Rotates hue by [degrees] while preserving luminance. */
    fun hueRotate(pixels: IntArray, degrees: Float) {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        // The standard luminance-preserving hue rotation matrix.
        val m00 = 0.213f + cosA * 0.787f - sinA * 0.213f
        val m01 = 0.715f - cosA * 0.715f - sinA * 0.715f
        val m02 = 0.072f - cosA * 0.072f + sinA * 0.928f
        val m10 = 0.213f - cosA * 0.213f + sinA * 0.143f
        val m11 = 0.715f + cosA * 0.285f + sinA * 0.140f
        val m12 = 0.072f - cosA * 0.072f - sinA * 0.283f
        val m20 = 0.213f - cosA * 0.213f - sinA * 0.787f
        val m21 = 0.715f - cosA * 0.715f + sinA * 0.715f
        val m22 = 0.072f + cosA * 0.928f + sinA * 0.072f

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            if (a == 0) continue
            val r = (p shr 16 and 0xFF).toFloat()
            val g = (p shr 8 and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            pixels[i] = (a shl 24) or
                (clamp255((r * m00 + g * m01 + b * m02).roundToInt()) shl 16) or
                (clamp255((r * m10 + g * m11 + b * m12).roundToInt()) shl 8) or
                clamp255((r * m20 + g * m21 + b * m22).roundToInt())
        }
    }

    /** Inverts colour, leaving alpha untouched so transparent areas stay transparent. */
    fun invert(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            if (a == 0) continue
            pixels[i] = (a shl 24) or
                ((255 - (p shr 16 and 0xFF)) shl 16) or
                ((255 - (p shr 8 and 0xFF)) shl 8) or
                (255 - (p and 0xFF))
        }
    }

    fun grayscale(pixels: IntArray) = saturation(pixels, 0f)

    /**
     * Three box-blur passes, which converge on a Gaussian closely enough to be indistinguishable
     * while staying O(pixels) per pass regardless of radius - a true Gaussian kernel at radius 40
     * would be roughly 80x more work per pixel.
     *
     * Colour is premultiplied by alpha before blurring and unpremultiplied afterwards. Without
     * that, the fully transparent pixels around a stroke contribute their (arbitrary, usually
     * black) colour with full weight, and every blurred edge picks up a dark halo.
     */
    fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        gaussianBlur(pixels, width, height, radius, BlurWorkspace())
    }

    fun gaussianBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        workspace: BlurWorkspace
    ) {
        if (radius < 1 || width < 1 || height < 1) return
        val pixelCount = width * height
        require(pixels.size >= pixelCount) { "Pixel buffer is smaller than the requested image" }
        val r = radius.coerceAtMost(minOf(width, height).coerceAtLeast(1))
        workspace.ensureCapacity(pixelCount)
        val a = workspace.alpha
        val rp = workspace.red
        val gp = workspace.green
        val bp = workspace.blue
        for (i in 0 until pixelCount) {
            val p = pixels[i]
            val alpha = p ushr 24 and 0xFF
            a[i] = alpha
            rp[i] = (p shr 16 and 0xFF) * alpha / 255
            gp[i] = (p shr 8 and 0xFF) * alpha / 255
            bp[i] = (p and 0xFF) * alpha / 255
        }

        repeat(3) {
            boxBlur(a, workspace.pass, width, height, r)
            boxBlur(rp, workspace.pass, width, height, r)
            boxBlur(gp, workspace.pass, width, height, r)
            boxBlur(bp, workspace.pass, width, height, r)
        }

        for (i in 0 until pixelCount) {
            val alpha = a[i].coerceIn(0, 255)
            if (alpha == 0) { pixels[i] = 0; continue }
            pixels[i] = (alpha shl 24) or
                (clamp255(rp[i] * 255 / alpha) shl 16) or
                (clamp255(gp[i] * 255 / alpha) shl 8) or
                clamp255(bp[i] * 255 / alpha)
        }
    }

    /** One horizontal then one vertical box pass, each using a running sum so cost is independent
     * of radius. */
    private fun boxBlur(channel: IntArray, tmp: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1

        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (i in -radius..radius) sum += channel[row + i.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                tmp[row + x] = sum / window
                val outIdx = (x - radius).coerceIn(0, width - 1)
                val inIdx = (x + radius + 1).coerceIn(0, width - 1)
                sum += channel[row + inIdx] - channel[row + outIdx]
            }
        }

        for (x in 0 until width) {
            var sum = 0
            for (i in -radius..radius) sum += tmp[i.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                channel[y * width + x] = sum / window
                val outIdx = (y - radius).coerceIn(0, height - 1)
                val inIdx = (y + radius + 1).coerceIn(0, height - 1)
                sum += tmp[inIdx * width + x] - tmp[outIdx * width + x]
            }
        }
    }

    /**
     * Unsharp mask: subtract a blurred copy to recover edge contrast.
     * @param amount 0 = unchanged, 1 = strong.
     */
    fun sharpen(pixels: IntArray, width: Int, height: Int, amount: Float) {
        if (amount <= 0f || width < 3 || height < 3) return
        val k = amount.coerceIn(0f, 2f)
        val blurred = pixels.copyOf()
        gaussianBlur(blurred, width, height, 2)

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24 and 0xFF
            if (a == 0) continue
            val b = blurred[i]
            pixels[i] = (a shl 24) or
                (sharpenChannel(p shr 16 and 0xFF, b shr 16 and 0xFF, k) shl 16) or
                (sharpenChannel(p shr 8 and 0xFF, b shr 8 and 0xFF, k) shl 8) or
                sharpenChannel(p and 0xFF, b and 0xFF, k)
        }
    }

    private fun sharpenChannel(original: Int, blurred: Int, amount: Float): Int =
        clamp255((original + (original - blurred) * amount).roundToInt())

    /** True when [a] and [b] differ by more than [tolerance] on any channel - used by tests and by
     * the preview to skip work when a slider has not meaningfully moved. */
    fun differs(a: IntArray, b: IntArray, tolerance: Int = 0): Boolean {
        if (a.size != b.size) return true
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (abs((x shr 16 and 0xFF) - (y shr 16 and 0xFF)) > tolerance) return true
            if (abs((x shr 8 and 0xFF) - (y shr 8 and 0xFF)) > tolerance) return true
            if (abs((x and 0xFF) - (y and 0xFF)) > tolerance) return true
            if (abs((x ushr 24 and 0xFF) - (y ushr 24 and 0xFF)) > tolerance) return true
        }
        return false
    }
}
