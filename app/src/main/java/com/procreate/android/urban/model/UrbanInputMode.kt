package com.procreate.android.urban.model

/**
 * نمط إدخال ورسم عناصر التخطيط الحضري.
 */
enum class UrbanInputMode(val titleAr: String, val titleEn: String) {
    /** وضع النقر على الأركان والزوايا (Point-by-Point / Tap Corners) */
    POINT_BY_POINT("نقر الأركان", "Point-by-Point"),

    /** وضع السحب الحر بمسافة متساوية (Continuous Drag with Fixed Step) */
    CONTINUOUS_DRAG("سحب بمسافة ثابتة", "Continuous Drag")
}
