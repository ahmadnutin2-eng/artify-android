package com.procreate.android.urban.assets

import java.util.Locale

/** Immutable, searchable catalog that can later be backed by built-in or user asset packs. */
class ArchitecturalAssetCatalog(definitions: List<ArchitecturalAssetMetadata>) {
    val assets: List<ArchitecturalAssetMetadata> = definitions.toList()

    private val assetsById: Map<String, ArchitecturalAssetMetadata>

    init {
        require(assets.isNotEmpty()) { "An architectural asset catalog cannot be empty" }
        val duplicateIds = assets.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate architectural asset ids: $duplicateIds" }
        assetsById = assets.associateBy { it.id }
    }

    operator fun get(assetId: String): ArchitecturalAssetMetadata? = assetsById[assetId]

    fun requireAsset(assetId: String): ArchitecturalAssetMetadata =
        assetsById[assetId] ?: error("Unknown architectural asset: $assetId")

    fun inCategory(category: ArchitecturalAssetCategory): List<ArchitecturalAssetMetadata> =
        assets.filter { it.category == category }

    /** Bilingual search over stable id, Arabic/English names, category and tags. */
    fun search(query: String, category: ArchitecturalAssetCategory? = null): List<ArchitecturalAssetMetadata> {
        val terms = query.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)

        return assets.filter { asset ->
            (category == null || asset.category == category) &&
                (terms.isEmpty() || terms.all(asset.searchableText()::contains))
        }
    }
}

/**
 * Original procedural starter library. Every preview and placed symbol is drawn from Canvas
 * primitives at runtime; the catalog contains no downloaded imagery or copied CAD blocks.
 */
object BuiltInArchitecturalAssets {
    const val DECIDUOUS_TREE_ID = "tree.deciduous-plan"
    const val PALM_TREE_ID = "tree.palm-plan"
    const val STREET_TREE_ID = "tree.street-plan"
    const val PERSON_STANDING_ID = "person.standing-plan"
    const val PERSON_WALKING_ID = "person.walking-plan"
    const val PERSON_WHEELCHAIR_ID = "person.wheelchair-plan"
    const val CAR_SEDAN_ID = "vehicle.sedan-plan"
    const val CAR_SUV_ID = "vehicle.suv-plan"
    const val CAR_PICKUP_ID = "vehicle.pickup-plan"

    val catalog = ArchitecturalAssetCatalog(
        listOf(
            ArchitecturalAssetMetadata(
                id = DECIDUOUS_TREE_ID,
                category = ArchitecturalAssetCategory.TREES,
                nameAr = "شجرة ظل",
                nameEn = "Deciduous shade tree",
                symbol = ProceduralAssetSymbol.DECIDUOUS_TREE,
                defaultDimensionsMeters = AssetDimensionsMeters(6.0, 6.0),
                defaultColorArgb = 0xFF4E8B57.toInt(),
                tags = setOf("tree", "canopy", "landscape", "شجرة", "ظل")
            ),
            ArchitecturalAssetMetadata(
                id = PALM_TREE_ID,
                category = ArchitecturalAssetCategory.TREES,
                nameAr = "نخلة",
                nameEn = "Palm tree",
                symbol = ProceduralAssetSymbol.PALM_TREE,
                defaultDimensionsMeters = AssetDimensionsMeters(5.0, 5.0),
                defaultColorArgb = 0xFF2F7D4A.toInt(),
                tags = setOf("palm", "landscape", "نخلة", "تنسيق")
            ),
            ArchitecturalAssetMetadata(
                id = STREET_TREE_ID,
                category = ArchitecturalAssetCategory.TREES,
                nameAr = "شجرة شارع",
                nameEn = "Street tree",
                symbol = ProceduralAssetSymbol.STREET_TREE,
                defaultDimensionsMeters = AssetDimensionsMeters(3.5, 3.5),
                defaultColorArgb = 0xFF619B52.toInt(),
                tags = setOf("street", "tree", "boulevard", "شارع", "تشجير")
            ),
            ArchitecturalAssetMetadata(
                id = PERSON_STANDING_ID,
                category = ArchitecturalAssetCategory.PEOPLE,
                nameAr = "شخص واقف",
                nameEn = "Standing person",
                symbol = ProceduralAssetSymbol.PERSON_STANDING,
                defaultDimensionsMeters = AssetDimensionsMeters(0.65, 0.65),
                defaultColorArgb = 0xFF4B5563.toInt(),
                tags = setOf("person", "human", "standing", "شخص", "واقف")
            ),
            ArchitecturalAssetMetadata(
                id = PERSON_WALKING_ID,
                category = ArchitecturalAssetCategory.PEOPLE,
                nameAr = "شخص يمشي",
                nameEn = "Walking person",
                symbol = ProceduralAssetSymbol.PERSON_WALKING,
                defaultDimensionsMeters = AssetDimensionsMeters(0.8, 0.6),
                defaultColorArgb = 0xFF54657A.toInt(),
                tags = setOf("person", "pedestrian", "walking", "مشاة", "شخص")
            ),
            ArchitecturalAssetMetadata(
                id = PERSON_WHEELCHAIR_ID,
                category = ArchitecturalAssetCategory.PEOPLE,
                nameAr = "مستخدم كرسي متحرك",
                nameEn = "Wheelchair user",
                symbol = ProceduralAssetSymbol.PERSON_WHEELCHAIR,
                defaultDimensionsMeters = AssetDimensionsMeters(1.1, 1.1),
                defaultColorArgb = 0xFF3F628B.toInt(),
                tags = setOf("person", "accessibility", "wheelchair", "إتاحة", "كرسي")
            ),
            ArchitecturalAssetMetadata(
                id = CAR_SEDAN_ID,
                category = ArchitecturalAssetCategory.VEHICLES,
                nameAr = "سيارة سيدان",
                nameEn = "Sedan car",
                symbol = ProceduralAssetSymbol.CAR_SEDAN,
                defaultDimensionsMeters = AssetDimensionsMeters(1.8, 4.5),
                defaultColorArgb = 0xFF607D8B.toInt(),
                tags = setOf("car", "sedan", "parking", "سيارة", "مواقف")
            ),
            ArchitecturalAssetMetadata(
                id = CAR_SUV_ID,
                category = ArchitecturalAssetCategory.VEHICLES,
                nameAr = "سيارة دفع رباعي",
                nameEn = "SUV",
                symbol = ProceduralAssetSymbol.CAR_SUV,
                defaultDimensionsMeters = AssetDimensionsMeters(2.0, 4.8),
                defaultColorArgb = 0xFF536B78.toInt(),
                tags = setOf("car", "suv", "parking", "سيارة", "دفع")
            ),
            ArchitecturalAssetMetadata(
                id = CAR_PICKUP_ID,
                category = ArchitecturalAssetCategory.VEHICLES,
                nameAr = "سيارة بيك أب",
                nameEn = "Pickup truck",
                symbol = ProceduralAssetSymbol.CAR_PICKUP,
                defaultDimensionsMeters = AssetDimensionsMeters(2.0, 5.2),
                defaultColorArgb = 0xFF6B7280.toInt(),
                tags = setOf("car", "pickup", "truck", "سيارة", "شاحنة")
            )
        )
    )
}
