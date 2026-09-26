package com.procreate.android.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode as AndroidBlendMode

enum class BlendMode {
    Normal, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity, Add, Subtract;

    fun toAndroidBlendMode(): AndroidBlendMode? {
        return when (this) {
            Normal -> AndroidBlendMode.SRC_OVER
            Multiply -> AndroidBlendMode.MULTIPLY
            Screen -> AndroidBlendMode.SCREEN
            Overlay -> AndroidBlendMode.OVERLAY
            Darken -> AndroidBlendMode.DARKEN
            Lighten -> AndroidBlendMode.LIGHTEN
            ColorDodge -> AndroidBlendMode.COLOR_DODGE
            ColorBurn -> AndroidBlendMode.COLOR_BURN
            HardLight -> AndroidBlendMode.HARD_LIGHT
            SoftLight -> AndroidBlendMode.SOFT_LIGHT
            Difference -> AndroidBlendMode.DIFFERENCE
            Exclusion -> AndroidBlendMode.EXCLUSION
            Hue -> AndroidBlendMode.HUE
            Saturation -> AndroidBlendMode.SATURATION
            Color -> AndroidBlendMode.COLOR
            Luminosity -> AndroidBlendMode.LUMINOSITY
            Add -> AndroidBlendMode.PLUS
            Subtract -> null // Requires custom shader or PorterDuff in standard Android
        }
    }
}

data class Layer(
    val id: String,
    var name: String,
    var bitmap: Bitmap,
    var opacity: Float = 1f,
    var blendMode: BlendMode = BlendMode.Normal,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    /** Constrains new strokes to only paint over this layer's already-opaque pixels. */
    var isAlphaLocked: Boolean = false,
    /** Clips this layer to the shape (alpha) of the nearest non-clipping layer below it. */
    var isClippingMask: Boolean = false,
    /** Optional non-destructive grayscale mask: white reveals this layer, black hides it. */
    var maskBitmap: Bitmap? = null,
    /** Routes brush strokes to [maskBitmap] instead of the layer's colour pixels. */
    var isEditingMask: Boolean = false,
    /** The paper this canvas sits on. Only a background layer is ever repainted by a change of
     * canvas background style, so switching from plain to dotted paper can't touch artwork. */
    var isBackground: Boolean = false,
    /** Stable owner for an online room. Null for ordinary offline layers. */
    var collaborationOwnerId: String? = null
)

class LayerManager(width: Int, height: Int) {
    val layers = mutableListOf<Layer>()
    var activeLayerIndex: Int = -1
    
    val activeLayer: Layer?
        get() = if (activeLayerIndex in layers.indices) layers[activeLayerIndex] else null

    fun addLayer(layer: Layer) {
        layers.add(layer)
        activeLayerIndex = layers.size - 1
    }

    fun removeLayer(index: Int) {
        if (index in layers.indices) {
            layers.removeAt(index)
            if (activeLayerIndex >= layers.size) {
                activeLayerIndex = layers.size - 1
            }
        }
    }
    
    fun moveLayer(fromIndex: Int, toIndex: Int) {
        if (fromIndex in layers.indices && toIndex in layers.indices) {
            val layer = layers.removeAt(fromIndex)
            layers.add(toIndex, layer)
            activeLayerIndex = toIndex
        }
    }
}
