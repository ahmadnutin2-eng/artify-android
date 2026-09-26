package com.procreate.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every bottom sheet in this app must open expanded.
 *
 * The app is locked to landscape, and a BottomSheetDialog in landscape opens COLLAPSED at a small
 * peek height. A panel in that state shows its title and roughly one row at the very bottom of the
 * screen, and dragging it - the obvious reaction - is read as a dismissal and closes it. It is
 * indistinguishable from a feature that was never built, and that is precisely how it was reported:
 * "the filters menu does not appear". The menu was there. It was a sliver.
 *
 * Four panels handled this inline and seven did not, because the fix was copied per panel instead
 * of shared. This test is the thing that makes the eighth panel impossible to forget.
 */
class BottomSheetExpansionTest {

    private val moduleDir: File by lazy {
        var dir = File(".").canonicalFile
        while (!File(dir, "src/main/java").isDirectory) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun sheetFragments(): List<File> =
        File(moduleDir, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(": BottomSheetDialogFragment(") }
            .toList()

    @Test
    fun `the sheet fragments are actually found`() {
        // Without this, the test below would pass triumphantly over an empty list.
        assertTrue(
            "No BottomSheetDialogFragment found - the scan is broken, not the app",
            sheetFragments().size >= 8
        )
    }

    @Test
    fun `every bottom sheet opens expanded`() {
        val offenders = sheetFragments().filter { file ->
            val text = file.readText()
            // Either the shared helper, or an inline BottomSheetBehavior that expands. Both are
            // acceptable; silently inheriting the collapsed default is not.
            val usesHelper = text.contains("PanelUi.expandSheet") || text.contains("PanelUi.dockSheet")
            val inlineExpand = text.contains("STATE_EXPANDED")
            !usesHelper && !inlineExpand
        }.map { it.name }

        assertTrue(
            "These bottom sheets will open as a collapsed sliver in landscape:\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\nCall PanelUi.expandSheet(...) or PanelUi.dockSheet(...) from onStart().",
            offenders.isEmpty()
        )
    }
}
