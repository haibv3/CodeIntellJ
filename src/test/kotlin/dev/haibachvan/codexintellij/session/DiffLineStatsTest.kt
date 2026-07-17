package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.ui.PatchSides
import dev.haibachvan.codexintellij.ui.UnifiedDiffReverser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiffLineStatsTest {
    @Test
    fun `counts added and removed lines`() {
        val diff = """
            --- a/x.kt
            +++ b/x.kt
            @@ -1,3 +1,4 @@
             keep
            -old
            +new
            +more
        """.trimIndent()
        val counts = DiffLineStats.of(diff)
        assertEquals(2, counts.added)
        assertEquals(1, counts.removed)
    }
}

class UnifiedDiffReverserTest {
    @Test
    fun `reverse restores previous content`() {
        val before = "keep\nold\n"
        val after = "keep\nnew\nmore\n"
        val diff = """
            --- a/x
            +++ b/x
            @@ -1,2 +1,3 @@
             keep
            -old
            +new
            +more
        """.trimIndent()
        val restored = UnifiedDiffReverser.reverse(after, diff)
        assertEquals(before, restored)
    }

    @Test
    fun `patch sides stay paired from same hunks`() {
        val diff = """
            --- a/x
            +++ b/x
            @@ -1,2 +1,3 @@
             keep
            -old
            +new
            +more
        """.trimIndent()
        assertEquals("keep\nold", PatchSides.oldFile(diff))
        assertEquals("keep\nnew\nmore", PatchSides.newFile(diff))
    }
}

