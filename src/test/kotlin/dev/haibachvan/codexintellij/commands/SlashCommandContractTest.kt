package dev.haibachvan.codexintellij.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlashCommandContractTest {
    @Test
    fun `exact 22 commands and disabled routes never become raw prompts`() {
        assertEquals(22, SlashCommandRegistry.ALL.size)
        val names = SlashCommandRegistry.ALL.map { it.name }
        assertEquals(names.toSet().size, names.size)
        SlashCommandRegistry.ALL.forEach { assertTrue(it.neverRawPrompt) }

        val dispatcher = SlashCommandDispatcher()
        listOf("/cloud", "/cloud-environment", "/worktree", "/side").forEach { cmd ->
            val result = dispatcher.dispatch(cmd)
            assertTrue(result is DispatchResult.Unavailable, cmd)
        }
        assertTrue(dispatcher.dispatch("/plan") is DispatchResult.Unavailable)
        assertTrue(
            SlashCommandDispatcher(experimentalOptIn = { true }).dispatch("/plan") is DispatchResult.Ok,
        )
    }
}
