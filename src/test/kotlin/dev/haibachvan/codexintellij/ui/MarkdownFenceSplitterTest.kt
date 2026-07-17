package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownFenceSplitterTest {
    @Test
    fun `splits prose and fence`() {
        val parts = MarkdownFenceSplitter.split(
            """
            Before
            ```xml
            <bool name="x">false</bool>
            ```
            After
            """.trimIndent(),
        )
        assertEquals(3, parts.size)
        assertTrue(parts[0] is MarkdownFenceSplitter.Part.Text)
        val fence = parts[1] as MarkdownFenceSplitter.Part.Fence
        assertEquals("xml", fence.language)
        assertTrue(fence.code.contains("<bool name=\"x\">false</bool>"))
        assertTrue(parts[2] is MarkdownFenceSplitter.Part.Text)
    }
}
