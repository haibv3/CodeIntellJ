package dev.haibachvan.codexintellij.platform

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EditorContextCollectorTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `rejects paths outside content roots`() {
        val outside = Files.createTempFile("outside", ".kt")
        val collector = EditorContextCollector(listOf(root))
        val result = collector.capture(ContextKind.SAVED_FILE, outside, text = null)
        assertTrue(result.isFailure)
    }

    @Test
    fun `captures unsaved buffer with hash and relative path`() {
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "old")
        val collector = EditorContextCollector(listOf(root))
        val snap = collector.capture(
            ContextKind.UNSAVED_BUFFER,
            file,
            text = "new-content",
            unsaved = true,
            modificationStamp = 9,
        ).getOrThrow()
        assertEquals("src/A.kt", snap.relativePath)
        assertTrue(snap.unsaved)
        assertEquals(64, snap.contentSha256.length)
        assertEquals("new-content", snap.text)
    }

    @Test
    fun `utf16 selection maps to utf8 byte offsets`() {
        val file = root.resolve("U.kt")
        Files.writeString(file, "café")
        val collector = EditorContextCollector(listOf(root))
        val snap = collector.capture(
            ContextKind.SELECTION,
            file,
            text = "café",
            utf16Start = 0,
            utf16End = 4,
            unsaved = true,
        ).getOrThrow()
        assertEquals(0, snap.utf8Start)
        assertTrue((snap.utf8End ?: 0) >= 4)
    }
}
