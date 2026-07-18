package dev.haibachvan.codexintellij

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CodexProjectServicePathTest {
    @Test
    fun `runtime trust store override isolates sandbox from user configuration`() {
        val resolved = resolveCodexTrustStorePath(
            userHome = "/home/fixture",
            overridePath = "/tmp/codex-perf/confirmed.store",
        )

        assertEquals(Path.of("/tmp/codex-perf/confirmed.store"), resolved)
    }

    @Test
    fun `runtime trust store defaults under user home`() {
        val resolved = resolveCodexTrustStorePath(
            userHome = "/home/fixture",
            overridePath = null,
        )

        assertEquals(Path.of("/home/fixture/.codex-intellij/confirmed-binary.store"), resolved)
    }
}
