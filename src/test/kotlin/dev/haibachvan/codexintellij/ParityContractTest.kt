package dev.haibachvan.codexintellij

import dev.haibachvan.codexintellij.commands.SlashCommandRegistry
import dev.haibachvan.codexintellij.platform.IdeActionIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ParityContractTest {
    @Test
    fun `G0 inventories match exact six actions and twenty two commands`() {
        assertEquals(6, IdeActionIds.ALL.size)
        assertEquals(22, SlashCommandRegistry.ALL.size)
    }

    @Test
    fun `G1 hold-scope disabled routes remain visible-disabled`() {
        val disabled = SlashCommandRegistry.ALL.filter {
            it.name in setOf("/cloud", "/cloud-environment", "/worktree", "/side")
        }
        assertEquals(4, disabled.size)
        disabled.forEach {
            assertTrue(it.route is dev.haibachvan.codexintellij.commands.CommandRoute.Disabled)
        }
    }

    @Test
    fun `G2 schema trees and method map exist`() {
        val root = Path.of("protocol-schema/codex-0.144.5")
        assertTrue(Files.isRegularFile(root.resolve("schema-inventory.txt")))
        assertTrue(Files.isRegularFile(root.resolve("schema-manifest.json")))
        assertTrue(Files.isRegularFile(root.resolve("method-schema-map.json")))
    }
}
