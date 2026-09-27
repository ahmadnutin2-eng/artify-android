package com.procreate.android.brushes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review findings L1 (emoji in set names) and L2 (icons guessed from id fragments), and M1's seed. */
class BrushSetIdentityTest {

    private val sets = BrushLibrary.getDefaultBrushSets()
    private val pictogram = Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]")

    @Test
    fun `every built-in set has a deliberate icon`() {
        val missing = sets.filterNot(BrushSetIdentity::hasExplicitIcon).map { it.id }
        assertTrue("sets without an explicit icon: $missing", missing.isEmpty())
    }

    @Test
    fun `an unknown imported set falls back to the plain brush`() {
        val imported = BrushSet("imported_airy_pack", "Airy pack", emptyList())
        assertEquals(com.procreate.android.R.drawable.ic_brush, BrushSetIdentity.iconFor(imported))
    }

    @Test
    fun `display names carry no emoji or symbols and keep the words`() {
        sets.forEach { set ->
            val shown = BrushSetIdentity.displayName(set)
            assertFalse("${set.id} shows '$shown'", pictogram.containsMatchIn(shown))
            assertTrue("${set.id} lost its name", shown.any(Char::isLetter))
            assertEquals(shown.trim(), shown)
        }
        assertEquals("الخط العربي", BrushSetIdentity.displayName("🖋️ الخط العربي"))
        assertEquals("My Brushes", BrushSetIdentity.displayName("🖼️ My Brushes"))
    }

    @Test
    fun `preview seeds are stable per brush and differ between brushes`() {
        val brushes = sets.flatMap { it.brushes }
        val first = brushes.first()
        assertEquals(BrushSetIdentity.previewSeed(first), BrushSetIdentity.previewSeed(first.copy()))
        assertNotEquals(BrushSetIdentity.previewSeed(brushes[0]), BrushSetIdentity.previewSeed(brushes[1]))
    }
}
