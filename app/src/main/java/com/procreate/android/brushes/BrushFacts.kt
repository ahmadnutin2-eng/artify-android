package com.procreate.android.brushes

import android.content.Context
import com.procreate.android.R
import com.procreate.android.canvas.BrushProperties
import com.procreate.android.canvas.BrushTipType
import com.procreate.android.canvas.BrushType
import com.procreate.android.canvas.GrainType

/**
 * Says what a brush *is*, in words, rather than what its numbers are.
 *
 * A list of presets whose rows carry only a name and a stroke leaves the two facts that decide
 * whether a brush suits the work - what the tip is cut like, and which of the pen's axes it
 * listens to - discoverable only by drawing with it. A reed that answers to the barrel and a reed
 * that does not are the same word and the same swatch until you have the pen in your hand.
 *
 * The same descriptions serve the library row and the studio's about page, so a brush cannot
 * describe itself one way in the list and another way in its own settings.
 */
object BrushFacts {

    /** Short chips for a library row: at most a handful of words, most distinguishing first. */
    fun chips(context: Context, properties: BrushProperties): List<String> {
        val chips = mutableListOf<String>()
        chips += tipLabel(context, properties.tipType)
        // The barrel axes come before anything else a brush might have, because they are what one
        // preset has and its otherwise identical neighbour does not.
        if (properties.azimuthTracking > 0f) {
            chips += if (properties.azimuthTracking >= 0.99f) {
                context.getString(R.string.brush_fact_barrel_full)
            } else {
                context.getString(R.string.brush_fact_barrel_partial)
            }
        }
        if (properties.tiltSizeScale > 0f || properties.tiltOpacityScale > 0f) {
            chips += context.getString(R.string.brush_fact_tilt)
        }
        if (properties.wetness > 0f) chips += context.getString(R.string.brush_fact_wet)
        if (properties.grainType != GrainType.NONE || !properties.customGrainPath.isNullOrEmpty()) {
            chips += context.getString(R.string.brush_fact_grained)
        }
        return chips
    }

    /** Label/value pairs for the studio's about page. */
    fun describe(context: Context, properties: BrushProperties): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        rows += context.getString(R.string.brush_fact_tip) to tipLabel(context, properties.tipType)
        rows += context.getString(R.string.brush_fact_family) to familyLabel(context, properties.type)
        rows += context.getString(R.string.brush_fact_angle_source) to angleSource(context, properties)
        rows += context.getString(R.string.brush_fact_tilt) to
            if (properties.tiltSizeScale > 0f || properties.tiltOpacityScale > 0f) {
                context.getString(R.string.brush_fact_yes)
            } else {
                context.getString(R.string.brush_fact_no)
            }
        rows += context.getString(R.string.brush_fact_grained) to
            if (properties.grainType != GrainType.NONE || !properties.customGrainPath.isNullOrEmpty()) {
                context.getString(R.string.brush_fact_yes)
            } else {
                context.getString(R.string.brush_fact_no)
            }
        return rows
    }

    /**
     * Where each stamp's rotation comes from - the single setting that separates a classical reed
     * from a barrel-led one, and the hardest to infer from any slider.
     */
    private fun angleSource(context: Context, properties: BrushProperties): String = when {
        properties.azimuthTracking > 0f -> context.getString(R.string.brush_fact_source_barrel)
        properties.orientToStroke -> context.getString(R.string.brush_fact_source_stroke)
        else -> context.getString(R.string.brush_fact_source_fixed)
    }

    private fun tipLabel(context: Context, tip: BrushTipType): String = context.getString(
        when (tip) {
            BrushTipType.REED_PEN -> R.string.brush_tip_reed
            BrushTipType.CALLIGRAPHY -> R.string.brush_tip_calligraphy
            BrushTipType.ROUND_SOFT -> R.string.brush_tip_round_soft
            BrushTipType.ROUND_HARD -> R.string.brush_tip_round_hard
            BrushTipType.PENCIL -> R.string.brush_tip_pencil
            BrushTipType.CHARCOAL -> R.string.brush_tip_charcoal
            BrushTipType.FLAT_BRUSH -> R.string.brush_tip_flat
            BrushTipType.FAN_BRUSH -> R.string.brush_tip_fan
            BrushTipType.SPRAY -> R.string.brush_tip_spray
            BrushTipType.WATERCOLOR -> R.string.brush_tip_watercolor
            BrushTipType.INK_PEN -> R.string.brush_tip_ink
            BrushTipType.MARKER -> R.string.brush_tip_marker
            BrushTipType.CRAYON -> R.string.brush_tip_crayon
            BrushTipType.DRY_BRUSH -> R.string.brush_tip_dry
            BrushTipType.STIPPLE -> R.string.brush_tip_stipple
            BrushTipType.CUSTOM -> R.string.brush_tip_custom
        }
    )

    private fun familyLabel(context: Context, type: BrushType): String = context.getString(
        when (type) {
            BrushType.Pencil -> R.string.brush_family_pencil
            BrushType.Ink -> R.string.brush_family_ink
            BrushType.Paint -> R.string.brush_family_paint
            BrushType.Airbrush -> R.string.brush_family_airbrush
            BrushType.Smudge -> R.string.brush_family_smudge
            BrushType.Eraser -> R.string.brush_family_eraser
        }
    )
}
