package com.procreate.android.canvas

import android.content.Context

/**
 * How the canvas is displayed once the user zooms in - ibisPaint's "Display when Zoomed" setting.
 *
 * It only changes how the picture is *drawn to the screen*. No pixel of the artwork is touched, so
 * it can be flipped back and forth freely, and exported images are unaffected.
 *
 *  - [SMOOTH]: interpolate, so a zoomed line stays continuous instead of turning into a grid of
 *    squares. The right default for painting and line art.
 *  - [PIXELATED]: show each pixel as a hard square. Right for pixel art, and for checking exactly
 *    what is in the bitmap.
 */
enum class DisplayQuality { SMOOTH, PIXELATED }

/** Persists the display choice across launches. */
object DisplayQualityPrefs {
    private const val PREFS = "procreate_prefs"
    private const val KEY_SMOOTH = "display_smooth_when_zoomed"

    fun get(context: Context): DisplayQuality =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SMOOTH, true))
            DisplayQuality.SMOOTH else DisplayQuality.PIXELATED

    fun set(context: Context, quality: DisplayQuality) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SMOOTH, quality == DisplayQuality.SMOOTH).apply()
    }
}
