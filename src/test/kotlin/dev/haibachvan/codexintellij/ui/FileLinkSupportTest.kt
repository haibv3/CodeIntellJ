package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileLinkSupportTest {
    @Test
    fun `parse strips line and column from absolute path`() {
        val t = FileLinkSupport.parse(
            "/home/haibachvan/Workspace/Sharp/AQUOS_17/LINUX/android/packages/apps/Settings/" +
                "src/com/android/settings/display/ScreenTimeoutSettings.java:369",
        )
        assertEquals(
            "/home/haibachvan/Workspace/Sharp/AQUOS_17/LINUX/android/packages/apps/Settings/" +
                "src/com/android/settings/display/ScreenTimeoutSettings.java",
            t.path,
        )
        assertEquals(369, t.line)
        assertEquals(null, t.column)
    }

    @Test
    fun `parse handles codex-open prefix and file uri`() {
        val t = FileLinkSupport.parse("codex-open:/tmp/demo.kt:12:4")
        assertEquals("/tmp/demo.kt", t.path)
        assertEquals(12, t.line)
        assertEquals(4, t.column)

        val f = FileLinkSupport.parse("file:///tmp/demo.kt:9")
        assertEquals("/tmp/demo.kt", f.path)
        assertEquals(9, f.line)
    }

    @Test
    fun `looksLikeLocalFileHref accepts markdown style paths`() {
        assertTrue(
            FileLinkSupport.looksLikeLocalFileHref(
                "/home/haibachvan/Workspace/Sharp/foo/ScreenTimeoutSettings.java:369",
            ),
        )
        assertFalse(FileLinkSupport.looksLikeLocalFileHref("https://example.com/a.java"))
        assertFalse(FileLinkSupport.looksLikeLocalFileHref("codex-copy:x"))
    }
}
