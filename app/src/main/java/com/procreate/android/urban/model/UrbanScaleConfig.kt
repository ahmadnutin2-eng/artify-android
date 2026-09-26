package com.procreate.android.urban.model

/**
 * إعدادات مقياس الرسم والشبكة المساحية الحضرية.
 * تتيح التحويل الدقيق بين بكسلات الشاشة والأمتار الحقيقية في الموقع.
 */
data class UrbanScaleConfig(
    /** عدد البكسلات لكل متر حقيقي على اللوحة (Pixels Per Meter) */
    var pixelsPerMeter: Float = 5.0f,

    /** طول ضلع مربع الشبكة بالمتر (مثلاً 50 متراً) */
    var gridCellSizeMeters: Float = 50.0f,

    /** هل شبكة المربعات مفعلة ومرئية */
    var isGridVisible: Boolean = true,

    /** هل بطاقة معلومات المقياس والمساحة ظاهرة بجانب المشروع */
    var isScaleCardVisible: Boolean = true,

    /** شفافية خطوط الشبكة (0 - 255) */
    var gridAlpha: Int = 70,

    /** لون خطوط الشبكة */
    var gridColor: Int = 0xFF555555.toInt(),

    /** هل تم معايرة المقياس من قبل المستخدم */
    var isCalibrated: Boolean = false,

    /** نسبة الطباعة/الـ Layout فقط (مثل 1000 تعني 1:1000).
     * هندسة النموذج وDXF تبقى بوحدات المتر الحقيقية 1:1 عبر [pixelsPerMeter]. */
    var standardRatio: Int = 1000,

    /** المسافة الثابتة بالمتر بين كل عقدة والأخرى عند رسم الحدود (Equidistant Step) */
    var nodeDistanceMeters: Float = 20.0f,

    /** هل شريط المقياس يظهر كعنصر حقيقي على الخريطة واللوحة */
    var isScaleBarOnMap: Boolean = true,

    /** موضع شريط المقياس في إحداثيات الخريطة */
    var scaleBarMapPosition: android.graphics.PointF? = null,

    /** سماكة خط الأداة الحالية بالبكسل */
    var activeToolStrokeWidth: Float = 14.0f,

    /** اللون المختار للأداة الحالية (0 يعني استخدام اللون الافتراضي) */
    var activeToolColor: Int = 0
) {
    /** مساحة المربع الواحد بالمتر المربع (م²) */
    fun cellAreaSqMeters(): Float = gridCellSizeMeters * gridCellSizeMeters

    /** مساحة المربع الواحد بالهكتار */
    fun cellAreaHectares(): Float = cellAreaSqMeters() / 10_000.0f

    /** حجم المربع الواحد بالبكسل على الكانفاس */
    fun gridCellSizePixels(): Float = gridCellSizeMeters * pixelsPerMeter

    /** تحويل مسافة بالبكسل إلى أمتار */
    fun pixelsToMeters(pixels: Float): Float {
        requireValidPixelsPerMeter()
        require(pixels.isFinite()) { "Pixel distance must be finite" }
        return pixels / pixelsPerMeter
    }

    /** تحويل أمتار إلى بكسلات */
    fun metersToPixels(meters: Float): Float {
        requireValidPixelsPerMeter()
        require(meters.isFinite()) { "Distance in metres must be finite" }
        return meters * pixelsPerMeter
    }

    /** تحويل مساحة بالبكسل المربع إلى متر مربع */
    fun pixelAreaToSqMeters(pixelArea: Float): Float {
        requireValidPixelsPerMeter()
        require(pixelArea.isFinite()) { "Pixel area must be finite" }
        return pixelArea / (pixelsPerMeter * pixelsPerMeter)
    }

    /** معايرة المقياس بناءً على نقطتين معلومتي المسافة */
    fun calibrate(pixelDistance: Float, realMeters: Float) {
        if (pixelDistance > 1f && realMeters > 0.01f) {
            pixelsPerMeter = pixelDistance / realMeters
            isCalibrated = true
        }
    }

    /** معايرة المقياس باستخدام أي وحدة قياس (cm, m, km) */
    fun calibrateWithUnit(pixelDistance: Float, realValue: Float, unit: DistanceUnit) {
        val realMeters = realValue * unit.toMetersMultiplier
        calibrate(pixelDistance, realMeters)
    }

    private fun requireValidPixelsPerMeter() {
        require(pixelsPerMeter.isFinite() && pixelsPerMeter > 0f) {
            "pixelsPerMeter must be finite and greater than zero"
        }
    }
}

/** وحدات قياس المسافات المتاحة للمعايرة */
enum class DistanceUnit(val symbol: String, val titleAr: String, val toMetersMultiplier: Float) {
    CENTIMETER("cm", "سنتيمتر (cm)", 0.01f),
    METER("m", "متر (m)", 1.0f),
    KILOMETER("km", "كيلومتر (km)", 1000.0f)
}
