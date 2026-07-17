package dev.haibachvan.codexintellij.targets

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ExecutionTargetServiceTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `rejects cwd outside content roots and accepts rooted cwd`() {
        val service = ExecutionTargetService(listOf(root))
        val inside = root.resolve("module")
        Files.createDirectories(inside)
        assertTrue(service.resolve(inside).isSuccess)
        val outside = Files.createTempDirectory("outside-cwd")
        assertTrue(service.resolve(outside).isFailure)
    }
}
