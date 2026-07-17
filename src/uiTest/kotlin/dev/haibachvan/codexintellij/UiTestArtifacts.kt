package dev.haibachvan.codexintellij

import java.nio.file.Files
import java.nio.file.Path

object UiTestArtifacts {
    fun prepare(dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("README.txt"), "uiTest artifacts directory")
    }
}
