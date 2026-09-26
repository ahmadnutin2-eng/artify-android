package com.procreate.android.brushes

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards the one class of brush bug that compiles perfectly and only shows up on a real device:
 * an asset reference that points at a file which is not there.
 *
 * This is not hypothetical. Renaming the bundled brush assets left `BrushTextures.createCustomTip`
 * and `BrushEngine.refreshGrain` still testing for the *old* blank-texture filename, so 50 brushes
 * silently changed behaviour - nothing failed to compile, and the whole 128-test suite stayed
 * green. Five more brushes had been pointing at thumbnails that had never existed at all.
 *
 * These tests read the actual source and the actual assets directory rather than any in-memory
 * model, because that is the only place the mistake can be seen: [BrushLibrary] cannot be
 * instantiated here (its BrushProperties pull in android.graphics), and a mock would simply
 * reproduce whatever the source says instead of checking it against the files on disk.
 */
class BrushAssetReferenceTest {

    private val moduleDir: File by lazy {
        // Gradle runs unit tests with the module directory as the working directory, but that is a
        // convention rather than a guarantee (IDE runners differ). Walk up until the layout is
        // recognisable so a green run always means the files really were checked.
        var dir = File(".").canonicalFile
        while (!File(dir, "src/main/assets").isDirectory) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val assetsDir: File get() = File(moduleDir, "src/main/assets")

    private val brushLibrarySource: String
        get() = File(moduleDir, "src/main/java/com/procreate/android/brushes/BrushLibrary.kt")
            .readText()

    private fun assetReferences(): List<String> =
        Regex("""asset://([^"]+)""").findAll(brushLibrarySource).map { it.groupValues[1] }.toList()

    @Test
    fun `assets directory is actually found`() {
        // Without this, every other test here could pass by checking nothing at all.
        assertTrue(
            "Could not locate src/main/assets from ${File(".").canonicalPath}",
            assetsDir.isDirectory
        )
        assertTrue("BrushLibrary.kt not readable", brushLibrarySource.isNotEmpty())
    }

    @Test
    fun `every bundled brush asset reference resolves to a real file`() {
        val refs = assetReferences()
        assertTrue("Expected BrushLibrary to reference bundled assets", refs.isNotEmpty())

        val missing = refs.distinct().filterNot { File(assetsDir, it).isFile }
        assertTrue(
            "BrushLibrary points at ${missing.size} asset(s) that do not exist:\n" +
                missing.joinToString("\n") { "  $it" },
            missing.isEmpty()
        )
    }

    @Test
    fun `blank texture sentinel matches the asset the library actually names`() {
        // BrushTextures.BLANK_ASSET and BrushEngine both branch on this filename to mean "no
        // texture". If the asset is renamed and the constant is not, both branches quietly stop
        // firing - which is exactly what happened once already.
        val blank = "tx-blank.png"

        assertTrue(
            "The blank texture asset $blank is missing from the bundle",
            File(assetsDir, "brushes/textures/$blank").isFile
        )
        assertTrue(
            "BrushLibrary no longer references $blank - if the asset was renamed, update " +
                "BrushTextures.BLANK_ASSET (and this test) to match",
            brushLibrarySource.contains(blank)
        )

        val constantSource =
            File(moduleDir, "src/main/java/com/procreate/android/canvas/BrushTextures.kt").readText()
        val declared = Regex("""BLANK_ASSET\s*=\s*"([^"]+)"""")
            .find(constantSource)?.groupValues?.get(1)
        assertEquals(
            "BrushTextures.BLANK_ASSET has drifted from the bundled asset name",
            blank,
            declared
        )
    }

    @Test
    fun `no call site spells the blank texture filename out by hand`() {
        // The constant only helps if every branch goes through it. Three separate call sites test
        // for this asset (two grain paths and the custom tip); the first sweep after the rename
        // fixed two and missed the third, because it searched for the old filename in isolation
        // rather than for the pattern of hardcoding it at all. This asserts the pattern is gone.
        val sources = File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }

        // Only *comparisons* are the smell. A brush in BrushLibrary naming the asset in its
        // customGrainPath is data declaring what it uses, which is exactly where the filename
        // belongs; the bug is code deciding "is this the blank one?" against a literal of its own.
        val predicate = Regex(
            """(endsWith|startsWith|contains|equals)\s*\(\s*"[^"]*[Bb]lank[^"]*\.png"""" +
                """|[=!]=\s*"[^"]*[Bb]lank[^"]*\.png""""
        )

        val offenders = sources.mapNotNull { file ->
            val hardcoded = file.readLines().withIndex().filter { (_, line) ->
                predicate.containsMatchIn(line) && !line.contains("BLANK_ASSET =")
            }
            if (hardcoded.isEmpty()) null
            else "${file.name}: " + hardcoded.joinToString("; ") { "L${it.index + 1}" }
        }.toList()

        assertTrue(
            "The blank-texture filename is hardcoded instead of using BrushTextures.BLANK_ASSET:\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty()
        )
    }

    @Test
    fun `no brush asset filename carries a third-party fingerprint`() {
        // The bundled textures were renamed to neutral tx-/th- names before publishing. A new file
        // dropped in under an old-style name would reintroduce exactly what that pass removed.
        val fingerprints = listOf("artery", "procreate", "brush-pocket", "brush-preset")
        val offenders = assetsDir.walkTopDown()
            .filter { it.isFile }
            .filter { file -> fingerprints.any { file.name.lowercase().contains(it) } }
            .map { it.relativeTo(assetsDir).path }
            .toList()

        assertTrue(
            "Asset filenames carrying a third-party fingerprint:\n" +
                offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty()
        )
    }
}
