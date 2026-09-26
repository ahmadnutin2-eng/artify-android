package com.procreate.android.urban.model

import android.graphics.PointF

/**
 * عنصر في مفتاح الخريطة الديناميكي (Dynamic Map Legend)
 */
data class LegendItem(
    val id: String,
    var nameAr: String,
    var nameEn: String,
    val toolType: UrbanToolType,
    var color: Int,
    var count: Int = 1,
    var totalLengthMeters: Float = 0f,
    var totalAreaSqMeters: Float = 0f,
    var isVisibleInLegend: Boolean = true
)

/**
 * عنصر تخطيطي مرسوم على لوحة التحليل الحضري
 */
sealed class UrbanElement(
    open val id: String,
    open val toolType: UrbanToolType,
    open var color: Int
) {
    /** مسار سهم أو محور بصري مع تحكم كامل بالرأس والذيل والنقاط المتسلسلة */
    data class ArrowPath(
        override val id: String,
        override val toolType: UrbanToolType,
        override var color: Int,
        val points: MutableList<PointF> = mutableListOf(),
        var strokeWidth: Float = 14f,
        var isDotted: Boolean = false,
        var isDashed: Boolean = false,
        var hasTrailingDots: Boolean = true,
        var arrowHeadType: ArrowHeadType = ArrowHeadType.LARGE_TRIANGLE,
        var startLabel: String? = null,
        var endLabel: String? = null,
        var stations: MutableList<StationMarker> = mutableListOf(),
        var lengthMeters: Float = 0f
    ) : UrbanElement(id, toolType, color)

    /** مضلع مهشر (ساحة عامة، مزارع، مقبرة، أو مسطح ترابي) */
    data class HatchPolygon(
        override val id: String,
        override val toolType: UrbanToolType,
        override var color: Int,
        val vertices: MutableList<PointF> = mutableListOf(),
        var hatchStyle: HatchStyle = HatchStyle.DIAGONAL_45,
        var hatchSpacing: Float = 24f,
        var plazaNumber: Int? = null,
        var plazaName: String? = null,
        var areaSqMeters: Float = 0f
    ) : UrbanElement(id, toolType, color)

    /** رمز نقطي (مانهول، خزان، عمود إنارة، كابينة، تشوه بصري) */
    data class PointMarker(
        override val id: String,
        override val toolType: UrbanToolType,
        override var color: Int,
        var position: PointF,
        var label: String? = null,
        var radius: Float = 16f
    ) : UrbanElement(id, toolType, color)

    /** خط حدود متقطع مع عقد إحداثيات مرقمة */
    data class BoundaryPath(
        override val id: String,
        override val toolType: UrbanToolType,
        override var color: Int,
        val vertices: MutableList<PointF> = mutableListOf(),
        var strokeWidth: Float = 8f,
        var showNodeNumbers: Boolean = true,
        var lengthMeters: Float = 0f,
        /** 0 (the default) means "a numbered node at every vertex", exactly as before this field
         * existed. A positive value instead spaces the *displayed* numbered nodes evenly along
         * the path at this many pixels apart - the renderer computes those positions separately
         * from [vertices] rather than reusing them, so changing this can never alter the drawn
         * line's actual shape (see UrbanSymbolRenderer.renderBoundary). */
        var nodeSpacingPx: Float = 0f
    ) : UrbanElement(id, toolType, color)
}

enum class ArrowHeadType {
    LARGE_TRIANGLE, // رأس سهم مثلث عريض مصمت (كالمحاور البصرية)
    CHEVRON_WIDE,   // سهم عريض متفرع (للمداخل)
    DOUBLE_HEAD,    // سهم ذو اتجاهين
    ROUNDED_DOT     // نقطة دائرية نهاية المسار
}

enum class HatchStyle {
    DIAGONAL_45,    // تهشير مائل بزاوية 45 درجة (للساحات العامة)
    CROSS_HATCH,    // تهشير متعامد شبكي (للمقابر والمناطق الخاصة)
    STIPPLE_DOTS,   // تهشير نقطي كثيف (للمزارع والنخيل)
    SOLID_FILL      // ملء لوني مصمت مع حدود (للمباني التراثية والحديثة)
}

data class StationMarker(
    val point: PointF,
    val label: String // مثل "A", "B", "C"
)
