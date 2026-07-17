package dev.haibachvan.codexintellij

import java.nio.file.Path

object FakeAppServerFixture {
    fun scriptPath(): Path = Path.of("src/uiTest/resources/fake-app-server/fake-codex.sh").toAbsolutePath().normalize()
    fun projectRoot(): Path = Path.of("src/uiTest/resources/projects/basic").toAbsolutePath().normalize()
}
