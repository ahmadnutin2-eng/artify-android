package com.procreate.android.urban.model

/**
 * أنواع أدوات وعناصر التخطيط الحضري ودراسات الموقع
 * مطابقة للعناصر المستخرجة من مخططات القرية التراثية.
 */
enum class UrbanToolType(
    val titleAr: String,
    val titleEn: String,
    val defaultColor: Int,
    val category: UrbanCategory
) {
    // --- المحاور والمسارات والأسهم ---
    PRIMARY_AXIS(
        titleAr = "المحور الرئيسي",
        titleEn = "Primary Visual Axis",
        defaultColor = 0xFF1565C0.toInt(), // أزرق داكن
        category = UrbanCategory.AXES
    ),
    SECONDARY_AXIS(
        titleAr = "المحور الثانوي",
        titleEn = "Secondary Visual Axis",
        defaultColor = 0xFFD32F2F.toInt(), // أحمر ناري
        category = UrbanCategory.AXES
    ),
    ENTRY_ARROW(
        titleAr = "مداخل القرية",
        titleEn = "Village Entrances",
        defaultColor = 0xFFB71C1C.toInt(), // أحمر عريض داكن
        category = UrbanCategory.AXES
    ),
    REGIONAL_ROAD(
        titleAr = "طرق ومسارات إقليمية",
        titleEn = "Regional Directions",
        defaultColor = 0xFF212121.toInt(), // أسود غامق
        category = UrbanCategory.AXES
    ),
    SITE_BOUNDARY(
        titleAr = "حدود الموقع",
        titleEn = "Site Boundary",
        defaultColor = 0xFFC62828.toInt(), // أحمر متقطع
        category = UrbanCategory.BOUNDARIES
    ),
    CONTOUR_LINE(
        titleAr = "خطوط مناسيب (كونتور)",
        titleEn = "Contour Lines",
        defaultColor = 0xFF9E9E9E.toInt(), // رمادي ناعم
        category = UrbanCategory.BOUNDARIES
    ),

    // --- الساحات والتهشيرات المعمارية ---
    PLAZA_HATCH(
        titleAr = "الساحات العامة والفراغات",
        titleEn = "Public Plazas",
        defaultColor = 0xFFE65100.toInt(), // برتقالي مائل مهشر
        category = UrbanCategory.HATCHING
    ),
    FARM_HATCH(
        titleAr = "مزارع ونخيل",
        titleEn = "Farms & Greenery",
        defaultColor = 0xFF66BB6A.toInt(), // أخضر نقطي
        category = UrbanCategory.HATCHING
    ),
    DIRT_PATH(
        titleAr = "ممرات ترابية (شداخات)",
        titleEn = "Dirt Paths",
        defaultColor = 0xFF8D6E63.toInt(), // بني متقطع
        category = UrbanCategory.HATCHING
    ),
    ASPHALT_ROAD(
        titleAr = "طرق أسفلتية",
        titleEn = "Asphalt Roads",
        defaultColor = 0xFF424242.toInt(), // رمادي داكن
        category = UrbanCategory.HATCHING
    ),
    HERITAGE_BUILDING(
        titleAr = "مباني تراثية",
        titleEn = "Heritage Buildings",
        defaultColor = 0xFFD7CCC8.toInt(), // بيج تراثي
        category = UrbanCategory.BUILDINGS
    ),
    MODERN_BUILDING(
        titleAr = "مباني حديثة",
        titleEn = "Modern Buildings",
        defaultColor = 0xFFFFF59D.toInt(), // أصفر فاتح
        category = UrbanCategory.BUILDINGS
    ),

    // --- عناصر التشوّه البصري ---
    POLLUTION_RUIN(
        titleAr = "مباني متهدمة تمثل تشوه بصري",
        titleEn = "Ruined Buildings (Visual Pollution)",
        defaultColor = 0xFFA0522D.toInt(), // طوبي / بني قرميدي
        category = UrbanCategory.POLLUTION
    ),
    POLLUTION_WIRES(
        titleAr = "خطوط كهرباء هوائية عشوائية",
        titleEn = "Overhead Wires",
        defaultColor = 0xFF0288D1.toInt(), // أزرق سماوي
        category = UrbanCategory.POLLUTION
    ),
    POLLUTION_SHED(
        titleAr = "مظلات سيارات غير مناسبة",
        titleEn = "Improper Vehicle Sheds",
        defaultColor = 0xFF29B6F6.toInt(), // أزرق فاتح
        category = UrbanCategory.POLLUTION
    ),
    POLLUTION_TRASH(
        titleAr = "حاويات قمامة غير مناسبة",
        titleEn = "Inappropriate Waste Bins",
        defaultColor = 0xFF6A1B9A.toInt(), // بنفسجي داكن
        category = UrbanCategory.POLLUTION
    ),
    POLLUTION_POLE(
        titleAr = "أعمدة كهرباء منتشرة غير منتظمة",
        titleEn = "Irregular Utility Poles",
        defaultColor = 0xFFE53935.toInt(), // نجمة حمراء
        category = UrbanCategory.POLLUTION
    ),

    // --- رموز البنية التحتية والمشاهدة ---
    INFRA_LIGHT(
        titleAr = "أعمدة إنارة",
        titleEn = "Street Lighting",
        defaultColor = 0xFF00ACC1.toInt(), // شمس سماوية
        category = UrbanCategory.INFRASTRUCTURE
    ),
    INFRA_MANHOLE(
        titleAr = "مانهول صرف صحي",
        titleEn = "Sewer Manhole",
        defaultColor = 0xFFFB8C00.toInt(), // دائرة برتقالية
        category = UrbanCategory.INFRASTRUCTURE
    ),
    INFRA_WATER(
        titleAr = "خزان مياه / بئر",
        titleEn = "Water Tank / Well",
        defaultColor = 0xFF8E24AA.toInt(), // دائرة بنفسجية
        category = UrbanCategory.INFRASTRUCTURE
    ),
    INFRA_ELECTRIC(
        titleAr = "محول / كابينة كهرباء",
        titleEn = "Transformer / Cabinet",
        defaultColor = 0xFF43A047.toInt(), // مربع أخضر
        category = UrbanCategory.INFRASTRUCTURE
    ),
    STATION_BADGE(
        titleAr = "محطة رؤية بصرية (A, B, C)",
        titleEn = "Sightline Station",
        defaultColor = 0xFF1E88E5.toInt(), // دائرة زرقاء بحرف
        category = UrbanCategory.AXES
    ),

    MEASURE_DISTANCE(
        titleAr = "قياس مسافة",
        titleEn = "Measure Distance",
        defaultColor = 0xFF00BCD4.toInt(),
        category = UrbanCategory.GENERAL
    ),
    MEASURE_AREA(
        titleAr = "قياس مساحة",
        titleEn = "Measure Area",
        defaultColor = 0xFF00BCD4.toInt(),
        category = UrbanCategory.GENERAL
    ),

    // --- أدوات عامة ---
    CALIBRATE_SCALE(
        titleAr = "معايرة المقياس (Scale)",
        titleEn = "Calibrate Scale",
        defaultColor = 0xFFFF9800.toInt(),
        category = UrbanCategory.GENERAL
    );

    /** Geometry, input, measurement and preview contract for this tool. */
    val definition: UrbanToolDefinition
        get() = UrbanToolDefinitions.forTool(this)
}

enum class UrbanCategory(val titleAr: String, val titleEn: String) {
    AXES("المحاور والأسهم", "Axes & Arrows"),
    HATCHING("الساحات والتهشير", "Plazas & Hatching"),
    BUILDINGS("المباني والكتل", "Buildings & Fabric"),
    POLLUTION("التشوّه البصري", "Visual Pollution"),
    INFRASTRUCTURE("البنية التحتية", "Infrastructure"),
    BOUNDARIES("الحدود والكونتور", "Boundaries & Topography"),
    GENERAL("عام والمقياس", "General & Scale")
}
