package com.procreate.android.brushes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import com.procreate.android.database.CustomBrushEntity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports .brush/.brushset files at the file-format level: both are just ZIP archives - Apple
 * never documented this, but it's the same technique every brush tool uses (Photoshop's .abr,
 * Krita's .kpp are the same idea) - a container bundling a plain PNG shape image together with a
 * settings file. We extract the shape image directly since it's an ordinary PNG needing no
 * proprietary decoding, and save it as one of our own custom brushes - the exact same
 * storage/pipeline the plain-image importer uses.
 *
 * A brush bundle is identified by *any* of its three well-known member files - Shape.png,
 * Grain.png, or Brush.archive - being present, not by Shape.png specifically. A large share of
 * real Procreate brushes (most pencils, charcoals, anything meant to look hand-drawn) ship with
 * no Shape.png at all and rely on Procreate's own implicit round default; requiring Shape.png to
 * even recognise a bundle was silently skipping every one of those, which is what "the app has
 * trouble receiving the brushes" actually was - not a decoding failure, a recognition failure.
 * A bundle with no Shape.png of its own gets a plain soft round shape here instead of being
 * dropped, so importing a set no longer quietly keeps only the outlier brushes that happened to
 * ship a custom silhouette.
 *
 * What this deliberately still does NOT attempt: parsing Brush.archive's actual content, an
 * undocumented Apple NSKeyedArchiver binary property list. Its presence is only ever used as a
 * marker that a folder is a real brush bundle - guessing at its unconfirmed internal schema would
 * risk silently producing wrong values that *look* authoritative but aren't. Imported brushes get
 * sensible defaults instead - the shape imports faithfully, and size/opacity/spacing/etc. are
 * meant to be tuned afterward in Brush Studio, same as any custom brush.
 */
object BrushsetImporter {

    data class ImportedBrush(
        val name: String,
        val shapeBitmap: Bitmap,
        /** The brush's paper texture, when it shipped one. */
        val grainBitmap: Bitmap? = null,
        val size: Float = 32f,
        val opacity: Float = 1f,
        val spacing: Float = 0.12f
    )

    private val BUNDLE_MARKERS = setOf("shape.png", "grain.png", "brush.archive")

    /** True if the picked file's first bytes match the ZIP magic number - the cheap way to tell
     * a .brush/.brushset archive apart from a plain image before deciding how to import it. */
    fun looksLikeZip(context: Context, uri: Uri): Boolean {
        val stream = context.contentResolver.openInputStream(uri) ?: return false
        return stream.use {
            val header = ByteArray(4)
            val read = it.read(header)
            read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        }
    }

    /** Finds every brush bundle inside a .brush or .brushset zip (one bundle per .brush it
     * contains) and returns each one's shape image, generating a default round shape for a
     * bundle that didn't ship its own. */
    fun extractShapes(context: Context, uri: Uri): List<ImportedBrush> {
        val entries = readRelevantEntries(context, uri)
        if (entries.isEmpty()) return emptyList()

        // A bundle is the folder directly containing any marker file - collected as a set so a
        // bundle with e.g. both Shape.png and Brush.archive isn't counted twice.
        val bundleFolders = linkedSetOf<String>()
        for (path in entries.keys) {
            bundleFolders.add(path.substringBeforeLast('/', ""))
        }
        if (bundleFolders.isEmpty()) return emptyList()

        return bundleFolders.mapIndexed { index, folder ->
            val prefix = if (folder.isEmpty()) "" else "$folder/"
            fun member(leaf: String): ByteArray? = entries.entries.firstOrNull {
                it.key.startsWith(prefix) &&
                    it.key.substring(prefix.length).indexOf('/') == -1 &&
                    it.key.substringAfterLast('/').equals(leaf, ignoreCase = true)
            }?.value

            val shapeBitmap = member("Shape.png")
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: createDefaultRoundShape()

            // The paper texture is most of what a grain-led brush is - a watercolour or a charcoal
            // without it is a flat silhouette. It was already being read out of the archive and
            // then dropped on the floor.
            val grainBitmap = member("Grain.png")
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

            val archive = member("Brush.archive")?.let { BinaryPlist.parse(it) }
            val folderName = folder.substringAfterLast('/')
            val name = archive?.findString("name")
                ?: folderName.takeIf { it.isNotBlank() && !looksLikeIdentifier(it) }
                ?: "فرشاة مستوردة ${index + 1}"

            ImportedBrush(
                name = name,
                shapeBitmap = shapeBitmap,
                grainBitmap = grainBitmap,
                // Procreate stores size as a fraction of its own maximum, so it is rescaled rather
                // than copied; the others are already the same 0..1 quantities this engine uses.
                size = archive?.findFloat("maxSize")?.let { (it * 120f).coerceIn(2f, 200f) } ?: 32f,
                opacity = archive?.findFloat("paintOpacity")?.coerceIn(0.05f, 1f) ?: 1f,
                spacing = archive?.findFloat("plotSpacing")?.coerceIn(0.01f, 1f) ?: 0.12f
            )
        }
    }

    /**
     * The name the pack gives itself, from the plain-XML manifest at the root of a .brushset.
     *
     * Unlike the per-brush archives this one is ordinary text, so there is nothing to decode - and
     * without it an imported pack loses the one piece of identity its author actually wrote down.
     */
    fun readSetName(context: Context, uri: Uri): String? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        val manifest = stream.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                var found: String? = null
                while (entry != null && found == null) {
                    if (!entry.isDirectory &&
                        entry.name.substringAfterLast('/').equals("brushset.plist", ignoreCase = true)
                    ) {
                        found = String(zip.readBytes(), Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                found
            }
        } ?: return null
        // <key>name</key> followed by the set's own <string>.
        val match = Regex(
            "<key>name</key>\\s*<string>(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL
        ).find(manifest)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Reads just the three marker files out of the zip (by exact leaf filename, case-insensitive)
     * rather than buffering everything - QuickLook previews and the rest of a real brush pack can
     * run to tens of megabytes that nothing here ever needs. */
    private fun readRelevantEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val stream = context.contentResolver.openInputStream(uri) ?: return result
        stream.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val leaf = entry.name.substringAfterLast('/').lowercase()
                        if (leaf in BUNDLE_MARKERS) {
                            result[entry.name] = zip.readBytes()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return result
    }

    /** A plain soft round tip, for a bundle that has no Shape.png of its own - matching
     * Procreate's own implicit default rather than dropping the brush entirely. */
    private fun createDefaultRoundShape(size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val center = size / 2f
        Canvas(bitmap).drawCircle(center, center, center, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                center, center, center,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
        })
        return bitmap
    }

    /** Procreate frequently names a brush's internal folder with a UUID/hash rather than its
     * display name, so a folder name that's just hex/dashes isn't worth showing to the user. */
    private fun looksLikeIdentifier(name: String): Boolean =
        name.matches(Regex("^[0-9A-Fa-f-]{8,}$"))

    /** Saves extracted shapes as custom brushes, in the same storage the manual image importer
     * uses, and returns the rows ready to insert into the custom-brush database. */
    fun saveAsCustomBrushes(context: Context, imported: List<ImportedBrush>): List<CustomBrushEntity> {
        val dir = File(context.filesDir, "custom_brushes").apply { mkdirs() }
        return imported.map { brush ->
            val id = UUID.randomUUID().toString()
            val file = File(dir, "$id.png")
            FileOutputStream(file).use { brush.shapeBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val grainFile = brush.grainBitmap?.let { grain ->
                File(dir, "$id-grain.png").also { target ->
                    FileOutputStream(target).use { grain.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            CustomBrushEntity(
                id = id,
                name = brush.name,
                tipImagePath = file.absolutePath,
                grainImagePath = grainFile?.absolutePath,
                size = brush.size,
                opacity = brush.opacity,
                hardness = 1f,
                spacing = brush.spacing,
                createdAt = System.currentTimeMillis()
            )
        }
    }
}
