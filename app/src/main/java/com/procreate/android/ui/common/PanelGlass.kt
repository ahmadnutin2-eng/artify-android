package com.procreate.android.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.ImageView
import com.procreate.android.adjust.ImageAdjustments

/**
 * Makes a floating panel read as frosted glass over the artwork instead of a slab of black.
 *
 * A translucent colour on its own cannot do this. At the opacity a panel needs to stay legible over
 * a white canvas it is indistinguishable from flat black - which is exactly how the brush library
 * looked next to the reference it was modelled on. What sells glass is that the shapes underneath
 * are still *there*, blurred: the panel has to show its own backdrop.
 *
 * Android does offer window-level background blur, and the panels already ask for it, but the
 * system is free to refuse: battery saver turns it off, the reduced-transparency accessibility
 * setting turns it off, and plenty of devices never enabled cross-window blur at all. A panel whose
 * material depends on that is frosted on one tablet and opaque on the next. Copying the pixels
 * behind the panel once and blurring them in-process makes the material the app's own, identical
 * everywhere, and costs one downscaled snapshot per open.
 */
object PanelGlass {

    /** The snapshot is blurred at a fraction of screen size; upscaling it afterwards softens it further. */
    private const val SAMPLE_DIVISOR = 5

    /** Blur radius in snapshot pixels - about five times this once scaled back up. */
    private const val BLUR_RADIUS = 9

    /**
     * Capture what [hostWindow] is showing behind [panel] and install it, blurred, into [into].
     *
     * Call once the panel has been laid out; the capture needs the panel's real position on screen
     * to know which part of the window is behind it. Failure at any step is silent by design - the
     * caller's tinted background stays visible, which is a duller panel rather than a broken one.
     */
    fun install(hostWindow: Window, panel: View, into: ImageView, tint: Int) {
        into.setBackgroundColor(tint)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        panel.post { capture(hostWindow, panel, into) }
    }

    private fun capture(hostWindow: Window, panel: View, into: ImageView) {
        val width = panel.width
        val height = panel.height
        if (width <= 0 || height <= 0) return

        val location = IntArray(2)
        panel.getLocationInWindow(location)
        val source = Rect(location[0], location[1], location[0] + width, location[1] + height)

        val decor = hostWindow.decorView
        // Asking for a region the window does not contain throws rather than returning an error.
        source.intersect(0, 0, decor.width, decor.height)
        if (source.width() <= 1 || source.height() <= 1) return

        val sampleWidth = (source.width() / SAMPLE_DIVISOR).coerceAtLeast(1)
        val sampleHeight = (source.height() / SAMPLE_DIVISOR).coerceAtLeast(1)
        val snapshot = try {
            Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return
        }

        try {
            PixelCopy.request(
                hostWindow,
                source,
                snapshot,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        into.setImageBitmap(frost(snapshot))
                        into.scaleType = ImageView.ScaleType.FIT_XY
                    } else if (!snapshot.isRecycled) {
                        snapshot.recycle()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (_: IllegalArgumentException) {
            // A window without a surface yet, or one being torn down. The tint alone is the fallback.
            if (!snapshot.isRecycled) snapshot.recycle()
        }
    }

    /**
     * Blur the snapshot and lift it toward the panel's own darkness.
     *
     * Blurring alone leaves the backdrop as bright as the canvas, so white artwork would still
     * swamp the panel's text. Laying a dark wash over the blur restores the contrast the interface
     * needs while the shapes underneath stay legible as shapes - which is the whole effect.
     */
    private fun frost(snapshot: Bitmap): Bitmap {
        val pixels = IntArray(snapshot.width * snapshot.height)
        snapshot.getPixels(pixels, 0, snapshot.width, 0, 0, snapshot.width, snapshot.height)
        ImageAdjustments.gaussianBlur(pixels, snapshot.width, snapshot.height, BLUR_RADIUS)
        snapshot.setPixels(pixels, 0, snapshot.width, 0, 0, snapshot.width, snapshot.height)
        Canvas(snapshot).drawRect(
            0f, 0f, snapshot.width.toFloat(), snapshot.height.toFloat(),
            // Tuned against the worst case, a blank white canvas: any lighter and the panel washes
            // out to pale grey and white labels stop being crisp; any darker and the blurred shapes
            // underneath disappear, which is the one thing the material exists to show.
            Paint().apply { color = Color.argb(198, 12, 13, 17) }
        )
        return snapshot
    }
}
