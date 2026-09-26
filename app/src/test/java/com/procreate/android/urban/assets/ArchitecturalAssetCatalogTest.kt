package com.procreate.android.urban.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitecturalAssetCatalogTest {
    private val catalog = BuiltInArchitecturalAssets.catalog

    @Test
    fun `starter catalog covers every architectural category with unique stable ids`() {
        assertEquals(9, catalog.assets.size)
        assertEquals(catalog.assets.size, catalog.assets.map { it.id }.toSet().size)
        ArchitecturalAssetCategory.entries.forEach { category ->
            assertTrue("Missing starter resources for $category", catalog.inCategory(category).isNotEmpty())
        }
        assertTrue(catalog.assets.all { it.view == ArchitecturalAssetView.PLAN })
        assertTrue(catalog.assets.all { it.defaultDimensionsMeters.width > 0.0 })
        assertTrue(catalog.assets.all { it.defaultDimensionsMeters.height > 0.0 })
    }

    @Test
    fun `catalog supports Arabic English and tag search with category filter`() {
        assertEquals(BuiltInArchitecturalAssets.PALM_TREE_ID, catalog.search("نخلة").single().id)
        assertEquals(BuiltInArchitecturalAssets.CAR_SEDAN_ID, catalog.search("sedan").single().id)
        assertTrue(catalog.search("parking").all { it.category == ArchitecturalAssetCategory.VEHICLES })
        assertTrue(catalog.search("person", ArchitecturalAssetCategory.VEHICLES).isEmpty())
        assertEquals(3, catalog.search("", ArchitecturalAssetCategory.TREES).size)
    }

    @Test
    fun `instance edits are immutable and keep catalog reference`() {
        val original = AssetInstance(
            id = "instance-1",
            assetId = BuiltInArchitecturalAssets.CAR_SEDAN_ID,
            xMeters = 4.0,
            yMeters = 8.0
        )
        val edited = original
            .translateBy(2.5, -1.0)
            .scaleBy(1.5)
            .rotateBy(-90.0)
            .recolor(0xFFAA3300.toInt())

        assertNotSame(original, edited)
        assertEquals(4.0, original.xMeters, 0.0)
        assertEquals(6.5, edited.xMeters, 0.0)
        assertEquals(7.0, edited.yMeters, 0.0)
        assertEquals(1.5, edited.scale, 0.0)
        assertEquals(270.0, edited.rotationDegrees, 0.0)
        assertEquals(original.assetId, edited.assetId)
        assertEquals(0xFFAA3300.toInt(), edited.colorOverrideArgb)
    }

    @Test
    fun `rotated world bounds swap footprint dimensions at ninety degrees`() {
        val metadata = catalog.requireAsset(BuiltInArchitecturalAssets.CAR_SEDAN_ID)
        val instance = AssetInstance(
            id = "car-1",
            assetId = metadata.id,
            xMeters = 10.0,
            yMeters = 20.0,
            rotationDegrees = 90.0
        )

        val bounds = instance.worldBounds(metadata)
        assertEquals(metadata.defaultDimensionsMeters.height, bounds.width, 1e-9)
        assertEquals(metadata.defaultDimensionsMeters.width, bounds.height, 1e-9)
        assertEquals(10.0, (bounds.minX + bounds.maxX) / 2.0, 1e-9)
        assertEquals(20.0, (bounds.minY + bounds.maxY) / 2.0, 1e-9)
    }

    @Test
    fun `precise hit test respects rotation scale and tolerance`() {
        val metadata = catalog.requireAsset(BuiltInArchitecturalAssets.CAR_SEDAN_ID)
        val instance = AssetInstance(
            id = "car-2",
            assetId = metadata.id,
            xMeters = 0.0,
            yMeters = 0.0,
            scale = 2.0,
            rotationDegrees = 90.0
        )

        assertTrue(instance.containsWorldPoint(metadata, xMeters = 4.4, yMeters = 0.0))
        assertFalse(instance.containsWorldPoint(metadata, xMeters = 0.0, yMeters = 2.0))
        assertTrue(
            instance.containsWorldPoint(
                metadata,
                xMeters = 0.0,
                yMeters = 2.0,
                toleranceMeters = 0.25
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `instance rejects non-positive scale`() {
        AssetInstance(
            id = "invalid",
            assetId = BuiltInArchitecturalAssets.STREET_TREE_ID,
            xMeters = 0.0,
            yMeters = 0.0,
            scale = 0.0
        )
    }
}
