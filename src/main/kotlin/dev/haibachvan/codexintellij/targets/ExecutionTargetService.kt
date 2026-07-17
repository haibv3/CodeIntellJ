package dev.haibachvan.codexintellij.targets

import java.nio.file.Path

data class ExecutionTarget(
    val contentRoot: Path,
    val cwd: Path,
)

class ExecutionTargetService(
    private val contentRoots: List<Path>,
) {
    fun resolve(cwdCandidate: Path): Result<ExecutionTarget> = runCatching {
        val cwd = cwdCandidate.toAbsolutePath().normalize()
        val root = contentRoots.map { it.toAbsolutePath().normalize() }
            .firstOrNull { cwd.startsWith(it) }
            ?: error("cwd outside canonical content roots: $cwd")
        ExecutionTarget(contentRoot = root, cwd = cwd)
    }

    fun defaultTarget(): ExecutionTarget? {
        val root = contentRoots.firstOrNull()?.toAbsolutePath()?.normalize() ?: return null
        return ExecutionTarget(root, root)
    }
}
