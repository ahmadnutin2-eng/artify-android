package com.procreate.android.export.dxf

import com.procreate.android.urban.assets.ArchitecturalAssetCatalog
import com.procreate.android.urban.assets.ArchitecturalAssetCategory
import com.procreate.android.urban.assets.ArchitecturalAssetMetadata
import com.procreate.android.urban.assets.AssetInstance
import com.procreate.android.urban.assets.BuiltInArchitecturalAssets
import com.procreate.android.urban.assets.ProceduralAssetSymbol
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Emits the original procedural resource catalog as simple, editable CAD primitives.
 *
 * [AssetInstance] coordinates use the app's metre-based, Y-down document convention. This
 * exporter reflects Y and rotation once at the local-to-CAD transform, producing 1:1 metres in
 * DXF's Y-up model space. The current [DxfEntity] contract has no transparency group, so partial
 * opacity is approximated by compositing true colour over white; fully transparent resources are
 * omitted rather than leaving surprising selectable geometry in the drawing.
 */
class ArchitecturalAssetDxfExporter(
    private val catalog: ArchitecturalAssetCatalog = BuiltInArchitecturalAssets.catalog
) {
    fun export(instance: AssetInstance): List<DxfEntity> {
        if (!instance.isVisible || instance.opacity <= 0f) return emptyList()
        val metadata = catalog[instance.assetId] ?: return emptyList()
        val color = compositeOverWhite(instance.resolvedColor(metadata), instance.opacity)
        val context = SymbolContext(metadata, instance, layerFor(metadata.category), color)

        return buildList {
            when (metadata.symbol) {
                ProceduralAssetSymbol.DECIDUOUS_TREE -> addDeciduousTree(context)
                ProceduralAssetSymbol.PALM_TREE -> addPalmTree(context)
                ProceduralAssetSymbol.STREET_TREE -> addStreetTree(context)
                ProceduralAssetSymbol.PERSON_STANDING -> addStandingPerson(context)
                ProceduralAssetSymbol.PERSON_WALKING -> addWalkingPerson(context)
                ProceduralAssetSymbol.PERSON_WHEELCHAIR -> addWheelchairPerson(context)
                ProceduralAssetSymbol.CAR_SEDAN -> addVehicle(context, VehicleKind.SEDAN)
                ProceduralAssetSymbol.CAR_SUV -> addVehicle(context, VehicleKind.SUV)
                ProceduralAssetSymbol.CAR_PICKUP -> addVehicle(context, VehicleKind.PICKUP)
            }
        }
    }

    /** Preserves explicit z order while keeping equal-z input order stable. */
    fun export(instances: Iterable<AssetInstance>): List<DxfEntity> = instances
        .withIndex()
        .sortedWith(compareBy<IndexedValue<AssetInstance>> { it.value.zIndex }.thenBy { it.index })
        .flatMap { export(it.value) }

    private fun MutableList<DxfEntity>.addDeciduousTree(context: SymbolContext) {
        val radiusX = context.width * 0.48
        val radiusY = context.height * 0.48
        val canopy = (0 until 20).map { index ->
            val angle = -Math.PI / 2.0 + index * 2.0 * Math.PI / 20.0
            val lobe = if (index % 2 == 0) 1.0 else 0.84
            context.point(cos(angle) * radiusX * lobe, sin(angle) * radiusY * lobe)
        }
        add(context.polyline(canopy, closed = true))
        add(context.circle(0.0, 0.0, min(context.width, context.height) * 0.31))
        add(context.circle(0.0, 0.0, min(context.width, context.height) * 0.055, trunkRgb))
    }

    private fun MutableList<DxfEntity>.addPalmTree(context: SymbolContext) {
        repeat(10) { index ->
            val angle = -Math.PI / 2.0 + index * 2.0 * Math.PI / 10.0
            val startRadius = min(context.width, context.height) * 0.08
            add(
                context.line(
                    cos(angle) * startRadius,
                    sin(angle) * startRadius,
                    cos(angle) * context.width * 0.48,
                    sin(angle) * context.height * 0.48
                )
            )
        }
        add(context.circle(0.0, 0.0, min(context.width, context.height) * 0.12))
        add(context.circle(0.0, 0.0, min(context.width, context.height) * 0.07, trunkRgb))
    }

    private fun MutableList<DxfEntity>.addStreetTree(context: SymbolContext) {
        val radius = min(context.width, context.height) * 0.46
        add(context.circle(0.0, 0.0, radius))
        add(context.circle(0.0, 0.0, radius * 0.66))
        repeat(8) { index ->
            val angle = index * Math.PI * 2.0 / 8.0
            add(context.line(0.0, 0.0, cos(angle) * radius * 0.83, sin(angle) * radius * 0.83))
        }
        add(context.circle(0.0, 0.0, radius * 0.10, trunkRgb))
    }

    private fun MutableList<DxfEntity>.addStandingPerson(context: SymbolContext) {
        val unit = min(context.width, context.height)
        add(context.circle(0.0, -context.height * 0.28, unit * 0.15))
        add(context.line(0.0, -context.height * 0.11, 0.0, context.height * 0.20))
        add(context.line(-context.width * 0.34, 0.0, context.width * 0.34, 0.0))
        add(context.line(0.0, context.height * 0.20, -context.width * 0.23, context.height * 0.43))
        add(context.line(0.0, context.height * 0.20, context.width * 0.23, context.height * 0.43))
    }

    private fun MutableList<DxfEntity>.addWalkingPerson(context: SymbolContext) {
        val unit = min(context.width, context.height)
        add(context.circle(0.0, -context.height * 0.27, unit * 0.15))
        add(context.line(0.0, -context.height * 0.10, 0.0, context.height * 0.17))
        add(context.line(0.0, -context.height * 0.01, -context.width * 0.36, context.height * 0.08))
        add(context.line(0.0, -context.height * 0.01, context.width * 0.31, -context.height * 0.09))
        add(context.line(0.0, context.height * 0.17, -context.width * 0.31, context.height * 0.43))
        add(context.line(0.0, context.height * 0.17, context.width * 0.39, context.height * 0.34))
    }

    private fun MutableList<DxfEntity>.addWheelchairPerson(context: SymbolContext) {
        val unit = min(context.width, context.height)
        add(context.circle(0.0, context.height * 0.08, unit * 0.36))
        add(context.circle(-context.width * 0.09, -context.height * 0.26, unit * 0.10))
        add(context.line(-context.width * 0.07, -context.height * 0.13, context.width * 0.07, context.height * 0.08))
        add(context.line(context.width * 0.04, context.height * 0.08, context.width * 0.31, context.height * 0.08))
        add(context.line(context.width * 0.29, context.height * 0.08, context.width * 0.41, context.height * 0.34))
    }

    private enum class VehicleKind { SEDAN, SUV, PICKUP }

    private fun MutableList<DxfEntity>.addVehicle(context: SymbolContext, kind: VehicleKind) {
        val width = context.width
        val height = context.height
        val body = listOf(
            -width * 0.36 to -height * 0.48,
            width * 0.36 to -height * 0.48,
            width * 0.47 to -height * 0.38,
            width * 0.47 to height * 0.38,
            width * 0.36 to height * 0.48,
            -width * 0.36 to height * 0.48,
            -width * 0.47 to height * 0.38,
            -width * 0.47 to -height * 0.38
        ).map { (x, y) -> context.point(x, y) }
        add(context.polyline(body, closed = true))

        val cabinTop = when (kind) {
            VehicleKind.SEDAN -> -height * 0.20
            VehicleKind.SUV -> -height * 0.28
            VehicleKind.PICKUP -> -height * 0.31
        }
        val cabinBottom = when (kind) {
            VehicleKind.SEDAN -> height * 0.22
            VehicleKind.SUV -> height * 0.27
            VehicleKind.PICKUP -> height * 0.03
        }
        add(context.rectangle(-width * 0.34, cabinTop, width * 0.34, cabinBottom))
        add(context.line(-width * 0.34, 0.0, width * 0.34, 0.0))

        if (kind == VehicleKind.PICKUP) {
            add(context.rectangle(-width * 0.35, height * 0.10, width * 0.35, height * 0.40))
        }

        val wheelRgb = darken(context.rgb, 0.62)
        listOf(-height * 0.29, height * 0.29).forEach { wheelY ->
            add(context.rectangle(-width * 0.53, wheelY - height * 0.065, -width * 0.43, wheelY + height * 0.065, wheelRgb))
            add(context.rectangle(width * 0.43, wheelY - height * 0.065, width * 0.53, wheelY + height * 0.065, wheelRgb))
        }
        add(context.line(-width * 0.28, -height * 0.43, width * 0.28, -height * 0.43))
        add(context.line(-width * 0.28, height * 0.43, width * 0.28, height * 0.43))
    }

    private data class SymbolContext(
        val metadata: ArchitecturalAssetMetadata,
        val instance: AssetInstance,
        val layer: String,
        val rgb: Int
    ) {
        val width: Double = instance.dimensionsMeters(metadata).width
        val height: Double = instance.dimensionsMeters(metadata).height
        private val radians = Math.toRadians(-instance.normalizedRotationDegrees)
        private val cosine = cos(radians)
        private val sine = sin(radians)

        /** [localY] follows Canvas (positive down); returned Y follows CAD (positive up). */
        fun point(localX: Double, localY: Double): DxfPoint {
            val localCadY = -localY
            return DxfPoint(
                x = instance.xMeters + localX * cosine - localCadY * sine,
                y = -instance.yMeters + localX * sine + localCadY * cosine
            )
        }

        fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: Int = rgb): DxfEntity.Line =
            DxfEntity.Line(layer, color, nearestAci(color), point(x1, y1), point(x2, y2))

        fun circle(x: Double, y: Double, radius: Double, color: Int = rgb): DxfEntity.Circle =
            DxfEntity.Circle(layer, color, nearestAci(color), point(x, y), radius)

        fun polyline(
            points: List<DxfPoint>,
            closed: Boolean,
            color: Int = rgb
        ): DxfEntity.LwPolyline = DxfEntity.LwPolyline(
            layer = layer,
            rgb = color,
            aci = nearestAci(color),
            points = points,
            closed = closed
        )

        fun rectangle(
            left: Double,
            top: Double,
            right: Double,
            bottom: Double,
            color: Int = rgb
        ): DxfEntity.LwPolyline = polyline(
            listOf(point(left, top), point(right, top), point(right, bottom), point(left, bottom)),
            closed = true,
            color = color
        )
    }

    companion object {
        const val TREES_LAYER = "ASSETS_TREES"
        const val PEOPLE_LAYER = "ASSETS_PEOPLE"
        const val VEHICLES_LAYER = "ASSETS_VEHICLES"

        val requiredLayers: Set<String> = setOf(TREES_LAYER, PEOPLE_LAYER, VEHICLES_LAYER)

        fun layerFor(category: ArchitecturalAssetCategory): String = when (category) {
            ArchitecturalAssetCategory.TREES -> TREES_LAYER
            ArchitecturalAssetCategory.PEOPLE -> PEOPLE_LAYER
            ArchitecturalAssetCategory.VEHICLES -> VEHICLES_LAYER
        }

        private const val trunkRgb = 0x76533A

        private fun compositeOverWhite(argb: Int, opacity: Float): Int {
            val sourceAlpha = (argb ushr 24 and 0xFF) / 255.0
            val alpha = (sourceAlpha * opacity).coerceIn(0.0, 1.0)
            fun channel(shift: Int): Int {
                val source = argb ushr shift and 0xFF
                return (source * alpha + 255.0 * (1.0 - alpha)).roundToInt().coerceIn(0, 255)
            }
            return channel(16) shl 16 or (channel(8) shl 8) or channel(0)
        }

        private fun darken(rgb: Int, amount: Double): Int {
            val retained = 1.0 - amount.coerceIn(0.0, 1.0)
            fun channel(shift: Int): Int = ((rgb ushr shift and 0xFF) * retained)
                .roundToInt()
                .coerceIn(0, 255)
            return channel(16) shl 16 or (channel(8) shl 8) or channel(0)
        }

        private fun nearestAci(rgb: Int): Int {
            val red = rgb ushr 16 and 0xFF
            val green = rgb ushr 8 and 0xFF
            val blue = rgb and 0xFF
            val candidates = listOf(
                1 to intArrayOf(255, 0, 0), 2 to intArrayOf(255, 255, 0),
                3 to intArrayOf(0, 255, 0), 4 to intArrayOf(0, 255, 255),
                5 to intArrayOf(0, 0, 255), 6 to intArrayOf(255, 0, 255),
                7 to intArrayOf(255, 255, 255), 8 to intArrayOf(128, 128, 128),
                9 to intArrayOf(192, 192, 192)
            )
            return candidates.minByOrNull { (_, candidate) ->
                val deltaRed = red - candidate[0]
                val deltaGreen = green - candidate[1]
                val deltaBlue = blue - candidate[2]
                deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue
            }?.first ?: 7
        }
    }
}
