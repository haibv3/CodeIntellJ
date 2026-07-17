package dev.haibachvan.codexintellij.platform

import com.google.gson.JsonParser
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ContextWireMapperTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `saved mention encodes exact type name path and warns deferred disk read`() {
        val file = root.resolve("Main.kt")
        Files.writeString(file, "fun main() {}\n")
        val collector = EditorContextCollector(listOf(root))
        val snapshot = collector.capture(ContextKind.SAVED_FILE, file, text = null, unsaved = false).getOrThrow()
        val encoded = ContextWireMapper().encode(snapshot)
        val json = JsonParser.parseString(encoded.jsonBytes.toString(Charsets.UTF_8)).asJsonObject
        assertEquals("mention", json.get("type").asString)
        assertEquals("Main.kt", json.get("name").asString)
        assertEquals("Main.kt", json.get("path").asString)
        assertTrue(encoded.preview.diskReadDeferredWarning)
        assertEquals(encoded.sha256, encoded.preview.hash)
        assertTrue(encoded.jsonBytes.contentEquals(encoded.preview.wireBytes))
        assertFalse(encoded.preview.uiOnlyMetadata.containsKey("text"))
    }

    @Test
    fun `selection preview bytes equal assembled input bytes`() {
        val file = root.resolve("Sel.kt")
        Files.writeString(file, "abcdef")
        val collector = EditorContextCollector(listOf(root))
        val snapshot = collector.capture(
            ContextKind.SELECTION,
            file,
            text = "cd",
            utf16Start = 2,
            utf16End = 4,
            unsaved = true,
        ).getOrThrow()
        val encoded = ContextWireMapper().encode(snapshot)
        val assembled = TurnInputAssembler().assemble("t1", "go", listOf(encoded))
        val rootJson = JsonParser.parseString(assembled.jsonBytes.toString(Charsets.UTF_8)).asJsonObject
        val first = rootJson.getAsJsonArray("input")[0].asJsonObject
        assertEquals(
            JsonParser.parseString(encoded.jsonBytes.toString(Charsets.UTF_8)),
            first,
        )
        assertEquals(encoded.sha256, ContextWireMapper().encode(snapshot).sha256)
    }
}
