package com.procreate.android.urban.assets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Lightweight, scale-to-fit preview for a catalog resource.
 *
 * The preview deliberately uses [ArchitecturalAssetRenderer], which keeps the browser thumbnail
 * identical to the symbol that will be placed on the drawing instead of maintaining a second set
 * of bitmap thumbnails.
 */
class ArchitecturalAssetPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderer = ArchitecturalAssetRenderer()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PREVIEW_BACKGROUND
    }
    private val backgroundBounds = RectF()

    var asset: ArchitecturalAssetMetadata? = null
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value?.nameAr
            invalidate()
        }

    /** Draws the renderer's selection frame when the host marks this resource as active. */
    var isAssetSelected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        // The surrounding card owns the click and accessibility node; this view is visual only.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        backgroundBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(backgroundBounds, dp(14f), dp(14f), backgroundPaint)

        val metadata = asset ?: return
        val horizontalRoom = (width - paddingLeft - paddingRight - dp(18f)).coerceAtLeast(1f)
        val verticalRoom = (height - paddingTop - paddingBottom - dp(18f)).coerceAtLeast(1f)
        val pixelsPerMeter = min(
            horizontalRoom / metadata.defaultDimensionsMeters.width.toFloat(),
            verticalRoom / metadata.defaultDimensionsMeters.height.toFloat()
        ).coerceAtLeast(0.01f)

        val previewInstance = AssetInstance(
            id = "catalog-preview-${metadata.id}",
            assetId = metadata.id,
            xMeters = 0.0,
            yMeters = 0.0
        )
        renderer.render(
            canvas = canvas,
            metadata = metadata,
            instance = previewInstance,
            viewport = AssetViewport(
                pixelsPerMeter = pixelsPerMeter,
                originXPx = width / 2f,
                originYPx = height / 2f
            ),
            options = AssetRenderOptions(
                isSelected = isAssetSelected,
                selectionColorArgb = SELECTION_COLOR,
                minimumVisibleSizePx = dp(4f)
            )
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val PREVIEW_BACKGROUND = 0xFF18181B.toInt()
        const val SELECTION_COLOR = 0xFFB39DDB.toInt()
    }
}
