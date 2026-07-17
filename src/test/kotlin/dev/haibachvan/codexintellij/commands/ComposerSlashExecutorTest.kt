package dev.haibachvan.codexintellij.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposerSlashExecutorTest {
    @Test
    fun `parseBuiltin only matches registry commands`() {
        val status = ComposerSlashExecutor.parseBuiltin("/status")
        assertEquals("/status", status?.name)
        assertEquals("", status?.args)

        val goal = ComposerSlashExecutor.parseBuiltin("/goal ship it")
        assertEquals("/goal", goal?.name)
        assertEquals("ship it", goal?.args)

        assertNull(ComposerSlashExecutor.parseBuiltin("/ck-ask something"))
        assertNull(ComposerSlashExecutor.parseBuiltin("hello"))
        assertNull(ComposerSlashExecutor.parseBuiltin("not /status"))
    }

    @Test
    fun `status handler formats missing thread honestly`() {
        val md = StatusCommandHandler(null).render(
            StatusCommandHandler.Context(
                threadId = null,
                model = "gpt-test",
                effort = "medium",
                cwd = "/tmp/proj",
                state = dev.haibachvan.codexintellij.session.NormalizedServerState(),
                lifecycleState = "chưa kết nối",
            ),
        )
        assertTrue(md.contains("Trạng thái"))
        assertTrue(md.contains("(chưa có)"))
        assertTrue(md.contains("gpt-test"))
        assertTrue(md.contains("chưa kết nối"))
        assertTrue(md.contains("Ngữ cảnh"))
    }

    @Test
    fun `init returns start turn prompt`() {
        assertTrue(ComposerSlashExecutor.INIT_PROMPT.contains("AGENTS.md"))
    }

    @Test
    fun `all twenty two registry names are parseable builtins`() {
        SlashCommandRegistry.ALL.forEach { spec ->
            assertEquals(spec.name, ComposerSlashExecutor.parseBuiltin(spec.name)?.name)
        }
    }
}
