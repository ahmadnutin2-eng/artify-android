package com.procreate.android.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Region

/**
 * Paint bucket / Procreate's ColorDrop.
 *
 * Bitmap plumbing only: every decision about what gets painted lives in [SmartFill], which works on
 * a plain IntArray and is therefore covered by unit tests. Pixels move in and out in bulk through
 * getPixels/setPixels - per-pixel getPixel calls are far too slow at canvas resolution.
 */
object FillTool {

    fun floodFill(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        fillColor: Int,
        options: SmartFill.Options = SmartFill.Options(),
        clip: Region? = null
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (startX !in 0 until w || startY !in 0 until h) return false
        if (clip != null && !clip.contains(startX, startY)) return false

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val allowed: ((Int, Int) -> Boolean)? = clip?.let { region -> { x, y -> region.contains(x, y) } }
        val changed = SmartFill.fill(pixels, w, h, startX, startY, fillColor, options, allowed)
        if (changed) bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return changed
    }
}

/**
 * The bucket's settings, kept across sessions.
 *
 * Exposed because no single value is right for every drawing: tight line art wants a small gap
 * radius so corners stay sharp, while hair or fur needs a large one. The defaults suit line art,
 * which is what this tool is mostly used on.
 */
object FillPrefs {
    private const val PREFS = "procreate_prefs"
    private const val KEY_TOLERANCE = "fill_tolerance"
    private const val KEY_GAP = "fill_gap_closing"
    private const val KEY_EXPAND = "fill_expand"
    private const val KEY_CONTIGUOUS = "fill_contiguous"

    const val MAX_TOLERANCE = 128
    const val MAX_GAP = 24
    const val MAX_EXPAND = 8

    fun load(context: Context): SmartFill.Options {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SmartFill.Options(
            tolerance = p.getInt(KEY_TOLERANCE, 32).coerceIn(0, MAX_TOLERANCE),
            gapClosing = p.getInt(KEY_GAP, 6).coerceIn(0, MAX_GAP),
            expand = p.getInt(KEY_EXPAND, 2).coerceIn(0, MAX_EXPAND),
            contiguous = p.getBoolean(KEY_CONTIGUOUS, true)
        )
    }

    fun save(context: Context, options: SmartFill.Options) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TOLERANCE, options.tolerance)
            .putInt(KEY_GAP, options.gapClosing)
            .putInt(KEY_EXPAND, options.expand)
            .putBoolean(KEY_CONTIGUOUS, options.contiguous)
            .apply()
    }
}
