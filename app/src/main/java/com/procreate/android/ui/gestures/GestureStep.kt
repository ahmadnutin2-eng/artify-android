package com.procreate.android.ui.gestures

import com.procreate.android.R

/**
 * One gesture in the onboarding guide: how it's animated, and what it's called.
 *
 * The order is the order they're taught in - the three that make the canvas usable at all
 * (draw, move, zoom) first, then the shortcuts that make it fast.
 */
enum class GestureStep(
    val titleRes: Int,
    val descriptionRes: Int,
    /** How long one loop of the demonstration runs, in milliseconds. */
    val cycleMs: Long
) {
    DRAW(R.string.gesture_draw_title, R.string.gesture_draw_desc, 2600),
    PAN(R.string.gesture_pan_title, R.string.gesture_pan_desc, 2600),
    ZOOM(R.string.gesture_zoom_title, R.string.gesture_zoom_desc, 2800),
    ROTATE(R.string.gesture_rotate_title, R.string.gesture_rotate_desc, 3000),
    UNDO(R.string.gesture_undo_title, R.string.gesture_undo_desc, 2000),
    // Taught right after the plain tap, because a shortcut nobody is shown is a shortcut nobody
    // uses - and holding is what turns undo from one-at-a-time into something usable.
    UNDO_HOLD(R.string.gesture_undo_hold_title, R.string.gesture_undo_hold_desc, 3000),
    REDO(R.string.gesture_redo_title, R.string.gesture_redo_desc, 2000),
    BRUSH_SIZE(R.string.gesture_brush_title, R.string.gesture_brush_desc, 3400),
    EYEDROPPER(R.string.gesture_eyedropper_title, R.string.gesture_eyedropper_desc, 3000),
    CLEAR_LAYER(R.string.gesture_clear_title, R.string.gesture_clear_desc, 2800),
    TOGGLE_UI(R.string.gesture_ui_title, R.string.gesture_ui_desc, 2400),
    LAYER_SWIPE(R.string.gesture_layer_swipe_title, R.string.gesture_layer_swipe_desc, 3200);

    companion object {
        val ordered: List<GestureStep> = values().toList()
    }
}
