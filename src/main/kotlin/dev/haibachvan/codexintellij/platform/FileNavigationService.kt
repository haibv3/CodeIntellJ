package dev.haibachvan.codexintellij.platform

import java.nio.file.Path

data class NavigationTarget(val path: Path, val line: Int?)

class FileNavigationService(
    private val contentRoots: List<Path>,
) {
    fun resolve(path: String, line: Int? = null): Result<NavigationTarget> = runCatching {
        val candidate = Path.of(path).toAbsolutePath().normalize()
        val rooted = contentRoots.map { it.toAbsolutePath().normalize() }
            .any { candidate.startsWith(it) || it.resolve(path).normalize() == candidate }
        require(rooted || contentRoots.any { it.resolve(path).toAbsolutePath().normalize().startsWith(it.toAbsolutePath().normalize()) }) {
            "Navigation path outside content roots"
        }
        val resolved = contentRoots.firstNotNullOfOrNull { root ->
            val rel = root.resolve(path).normalize()
            if (rel.startsWith(root.toAbsolutePath().normalize())) rel else null
        } ?: candidate
        NavigationTarget(resolved, line)
    }
}
