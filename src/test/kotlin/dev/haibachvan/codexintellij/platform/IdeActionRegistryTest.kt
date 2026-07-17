package dev.haibachvan.codexintellij.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeActionRegistryTest {
    @Test
    fun `exact six IDE actions are registered`() {
        assertEquals(
            listOf(
                "codex.addToThread",
                "codex.addFileToThread",
                "codex.newChat",
                "codex.newCodexPanel",
                "codex.openCommandMenu",
                "codex.openSidebar",
            ),
            IdeActionIds.ALL,
        )
        val pluginXml = java.nio.file.Path.of("src/main/resources/META-INF/plugin.xml").toFile().readText()
        IdeActionIds.ALL.forEach { id ->
            assertEquals(true, pluginXml.contains("id=\"$id\""), "missing action $id")
        }
    }
}
