package com.procreate.android.urban.model

/**
 * مواضع تموضع البطاقات العائمة لمفتاح الخريطة والجداول المكانية.
 */
enum class OverlayPlacement(val titleAr: String) {
    BOTTOM_RIGHT("أسفل اليمين ↘"),
    BOTTOM_LEFT("أسفل اليسار ↙"),
    TOP_RIGHT("أعلى اليمين ↗"),
    TOP_LEFT("أعلى اليسار ↖"),
    CUSTOM("مخصص / سحب حر ✢")
}
