package com.procreate.android.urban.assets

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** High-level sections shown by the architectural resource browser. */
enum class ArchitecturalAssetCategory {
    TREES,
    PEOPLE,
    VEHICLES
}

/** The projection in which the procedural symbol was designed. */
enum class ArchitecturalAssetView {
    PLAN,
    ELEVATION
}

/**
 * Stable renderer keys for the original, code-generated symbols bundled with the app.
 *
 * These are semantic keys rather than Android drawable ids. Project files can therefore retain
 * the key across app updates, while Canvas, DXF and future SVG renderers can each implement the
 * same symbol independently.
 */
enum class ProceduralAssetSymbol {
    DECIDUOUS_TREE,
    PALM_TREE,
    STREET_TREE,
    PERSON_STANDING,
    PERSON_WALKING,
    PERSON_WHEELCHAIR,
    CAR_SEDAN,
    CAR_SUV,
    CAR_PICKUP
}

enum class AssetLanguage {
    ARABIC,
    ENGLISH
}

/** Real-world footprint used for placement, selection and scale-correct rendering. */
data class AssetDimensionsMeters(
    val width: Double,
    val height: Double
) {
    init {
        require(width.isFinite() && width > 0.0) { "Asset width must be finite and positive" }
        require(height.isFinite() && height > 0.0) { "Asset height must be finite and positive" }
    }
}

/** Catalog record. No bitmap or third-party file is referenced by this model. */
data class ArchitecturalAssetMetadata(
    val id: String,
    val category: ArchitecturalAssetCategory,
    val nameAr: String,
    val nameEn: String,
    val symbol: ProceduralAssetSymbol,
    val defaultDimensionsMeters: AssetDimensionsMeters,
    val defaultColorArgb: Int,
    val tags: Set<String> = emptySet(),
    val view: ArchitecturalAssetView = ArchitecturalAssetView.PLAN,
    val schemaVersion: Int = 1
) {
    init {
        require(STABLE_ID.matches(id)) {
            "Asset id must contain only lowercase letters, digits, dots, underscores or hyphens"
        }
        require(nameAr.isNotBlank()) { "Arabic asset name cannot be blank" }
        require(nameEn.isNotBlank()) { "English asset name cannot be blank" }
        require(schemaVersion > 0) { "Asset schema version must be positive" }
        require(tags.none(String::isBlank)) { "Asset tags cannot be blank" }
    }

    fun displayName(language: AssetLanguage): String = when (language) {
        AssetLanguage.ARABIC -> nameAr
        AssetLanguage.ENGLISH -> nameEn
    }

    internal fun searchableText(): String = buildString {
        append(id)
        append(' ')
        append(nameAr)
        append(' ')
        append(nameEn)
        append(' ')
        append(category.name)
        tags.forEach {
            append(' ')
            append(it)
        }
    }.lowercase(Locale.ROOT)

    private companion object {
        val STABLE_ID = Regex("[a-z0-9][a-z0-9._-]*")
    }
}

/** Axis-aligned real-world bounds, expressed in metres. */
data class AssetWorldBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    init {
        require(listOf(minX, minY, maxX, maxY).all(Double::isFinite)) {
            "Asset bounds must be finite"
        }
        require(maxX >= minX && maxY >= minY) { "Asset bounds are inverted" }
    }

    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    fun contains(xMeters: Double, yMeters: Double, toleranceMeters: Double = 0.0): Boolean {
        require(toleranceMeters.isFinite() && toleranceMeters >= 0.0) {
            "Hit-test tolerance must be finite and non-negative"
        }
        return xMeters >= minX - toleranceMeters && xMeters <= maxX + toleranceMeters &&
            yMeters >= minY - toleranceMeters && yMeters <= maxY + toleranceMeters
    }
}

/**
 * Editable placement of a catalog item. Coordinates and dimensions stay in metres; the viewport
 * decides how many pixels represent a metre. The center of the resource is its anchor.
 */
data class AssetInstance(
    val id: String,
    val assetId: String,
    val xMeters: Double,
    val yMeters: Double,
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val colorOverrideArgb: Int? = null,
    val opacity: Float = 1f,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val zIndex: Int = 0
) {
    init {
        require(id.isNotBlank()) { "Asset instance id cannot be blank" }
        require(assetId.isNotBlank()) { "Catalog asset id cannot be blank" }
        require(xMeters.isFinite() && yMeters.isFinite()) { "Asset position must be finite" }
        require(scale.isFinite() && scale > 0.0) { "Asset scale must be finite and positive" }
        require(rotationDegrees.isFinite()) { "Asset rotation must be finite" }
        require(opacity.isFinite() && opacity in 0f..1f) { "Asset opacity must be between 0 and 1" }
    }

    /** Canonical angle useful for inspectors and stable project diffs. */
    val normalizedRotationDegrees: Double
        get() = ((rotationDegrees % 360.0) + 360.0) % 360.0

    fun moveTo(xMeters: Double, yMeters: Double): AssetInstance =
        copy(xMeters = xMeters, yMeters = yMeters)

    fun translateBy(deltaXMeters: Double, deltaYMeters: Double): AssetInstance =
        copy(xMeters = xMeters + deltaXMeters, yMeters = yMeters + deltaYMeters)

    fun resizeTo(scale: Double): AssetInstance = copy(scale = scale)

    fun scaleBy(factor: Double): AssetInstance = resizeTo(scale * factor)

    fun rotateTo(degrees: Double): AssetInstance = copy(rotationDegrees = normalizeDegrees(degrees))

    fun rotateBy(deltaDegrees: Double): AssetInstance = rotateTo(rotationDegrees + deltaDegrees)

    fun recolor(argb: Int?): AssetInstance = copy(colorOverrideArgb = argb)

    fun resolvedColor(metadata: ArchitecturalAssetMetadata): Int =
        colorOverrideArgb ?: metadata.defaultColorArgb

    fun dimensionsMeters(metadata: ArchitecturalAssetMetadata): AssetDimensionsMeters =
        AssetDimensionsMeters(
            width = metadata.defaultDimensionsMeters.width * scale,
            height = metadata.defaultDimensionsMeters.height * scale
        )

    /** Rotated, axis-aligned bounds for viewport culling and selection handles. */
    fun worldBounds(metadata: ArchitecturalAssetMetadata): AssetWorldBounds {
        val dimensions = dimensionsMeters(metadata)
        val halfWidth = dimensions.width / 2.0
        val halfHeight = dimensions.height / 2.0
        val radians = Math.toRadians(normalizedRotationDegrees)
        val extentX = abs(cos(radians)) * halfWidth + abs(sin(radians)) * halfHeight
        val extentY = abs(sin(radians)) * halfWidth + abs(cos(radians)) * halfHeight
        return AssetWorldBounds(
            minX = xMeters - extentX,
            minY = yMeters - extentY,
            maxX = xMeters + extentX,
            maxY = yMeters + extentY
        )
    }

    /** Precise hit test against the rotated rectangular footprint rather than its larger AABB. */
    fun containsWorldPoint(
        metadata: ArchitecturalAssetMetadata,
        xMeters: Double,
        yMeters: Double,
        toleranceMeters: Double = 0.0
    ): Boolean {
        require(xMeters.isFinite() && yMeters.isFinite()) { "Hit-test point must be finite" }
        require(toleranceMeters.isFinite() && toleranceMeters >= 0.0) {
            "Hit-test tolerance must be finite and non-negative"
        }

        val dimensions = dimensionsMeters(metadata)
        val radians = Math.toRadians(normalizedRotationDegrees)
        val cosTheta = cos(radians)
        val sinTheta = sin(radians)
        val deltaX = xMeters - this.xMeters
        val deltaY = yMeters - this.yMeters
        val localX = deltaX * cosTheta + deltaY * sinTheta
        val localY = -deltaX * sinTheta + deltaY * cosTheta
        return abs(localX) <= dimensions.width / 2.0 + toleranceMeters &&
            abs(localY) <= dimensions.height / 2.0 + toleranceMeters
    }

    private companion object {
        fun normalizeDegrees(degrees: Double): Double {
            require(degrees.isFinite()) { "Asset rotation must be finite" }
            return ((degrees % 360.0) + 360.0) % 360.0
        }
    }
}
