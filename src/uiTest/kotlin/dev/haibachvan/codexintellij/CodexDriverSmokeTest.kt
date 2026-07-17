package dev.haibachvan.codexintellij

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lightweight uiTest smoke gate. Full Driver automation can extend FakeAppServerFixture.
 */
class CodexDriverSmokeTest {
    @Test
    fun `fake app server fixture and basic project root exist`() {
        assertTrue(Files.isRegularFile(Path.of("src/uiTest/resources/fake-app-server/fake-codex.sh")))
        assertTrue(Files.isRegularFile(Path.of("src/uiTest/resources/projects/basic/.gitkeep")))
        assertTrue(Files.isRegularFile(Path.of("src/test/resources/fixtures/appserver/fake-codex-app-server.py")))
    }
}
