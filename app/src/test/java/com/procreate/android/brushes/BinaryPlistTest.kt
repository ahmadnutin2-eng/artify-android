package com.procreate.android.brushes

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the reader against a real `Brush.archive` taken from a shipping Procreate pack, because the
 * only thing worth knowing about a format reader is whether it survives the files it will actually
 * meet. A synthetic fixture would have agreed with whatever the reader happened to do.
 */
class BinaryPlistTest {

    private fun sample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("sample-brush.archive")!!.use { it.readBytes() }

    @Test
    fun `a real brush archive parses`() {
        assertNotNull(BinaryPlist.parse(sample()))
    }

    @Test
    fun `the brush's own name comes out, not the archive's bookkeeping`() {
        val name = BinaryPlist.parse(sample())?.findString("name")
        assertNotNull("no name found in the archive", name)
        // The keyed archiver's own keys all begin with a dollar; reading one of those back would
        // mean the reference was resolved against the wrong table.
        assertTrue("resolved to archiver metadata: $name", !name!!.startsWith("$"))
        assertTrue(name.isNotBlank())
        // A Foundation class name means the reference landed on the object's type rather than its
        // contents - the archive would have parsed and still told us nothing.
        assertTrue(
            "resolved to a class name rather than the brush's name: $name",
            !Regex("^(NS|CF)[A-Za-z]+$").matches(name)
        )
    }

    @Test
    fun `settings come back as numbers in their documented ranges`() {
        val document = BinaryPlist.parse(sample())
        assertNotNull(document)
        val spacing = document!!.findFloat("plotSpacing")
        val opacity = document.findFloat("paintOpacity")
        assertNotNull("plotSpacing missing", spacing)
        assertNotNull("paintOpacity missing", opacity)
        assertTrue("spacing out of range: $spacing", spacing!! in 0f..1f)
        assertTrue("opacity out of range: $opacity", opacity!! in 0f..1f)
    }

    @Test
    fun `anything that is not a binary plist is refused rather than guessed at`() {
        assertNotNull(BinaryPlist.parse(sample()))
        org.junit.Assert.assertNull(BinaryPlist.parse(ByteArray(64)))
        org.junit.Assert.assertNull(BinaryPlist.parse("not a plist at all".toByteArray()))
    }
}
