package dev.haibachvan.codexintellij.appserver

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Locates a Codex CLI executable without user interaction.
 *
 * IntelliJ often has a thinner PATH than the user shell (missing `~/.npm-global/bin`, etc.),
 * so discovery merges process PATH, common install dirs, and a login-shell `command -v`.
 * Symlinks are followed to a regular file for trust pinning.
 */
object CodexBinaryLocator {
    fun resolve(env: Map<String, String> = System.getenv()): Path {
        val explicit = sequenceOf(
            System.getProperty("codex.binary"),
            env["CODEX_BIN"],
        ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .map { Path.of(it) }
            .firstOrNull()

        if (explicit != null) {
            return toRegularExecutable(explicit)
        }

        val errors = ArrayList<String>()
        for (candidate in candidatePaths(env)) {
            try {
                return toRegularExecutable(candidate)
            } catch (ex: Exception) {
                errors += "${candidate}: ${ex.message}"
            }
        }
        val detail = if (errors.isEmpty()) {
            "no candidates under PATH / common bins / login shell"
        } else {
            errors.take(5).joinToString("; ")
        }
        error(
            "Could not find a Codex executable ($detail). " +
                "Install `codex` or set CODEX_BIN / -Dcodex.binary.",
        )
    }

    fun enrichedPath(env: Map<String, String> = System.getenv()): String {
        val parts = ArrayList<String>()
        parts += commonBinDirs().map { it.toString() }
        parts += loginShellPath().orEmpty().split(':')
        parts += (env["PATH"] ?: "").split(':')
        return parts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(":")
    }

    fun toRegularExecutable(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val real = when {
            Files.isSymbolicLink(absolute) -> absolute.toRealPath()
            absolute.exists(LinkOption.NOFOLLOW_LINKS) -> absolute
            else -> absolute.toRealPath()
        }
        require(real.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            "Codex binary must resolve to a regular file: $path -> $real"
        }
        require(real.isExecutable()) { "Codex binary must be executable: $real" }
        return real
    }

    private fun candidatePaths(env: Map<String, String>): List<Path> {
        val out = LinkedHashSet<Path>()
        for (dir in enrichedPath(env).split(':')) {
            out.add(Path.of(dir, "codex"))
        }
        loginShellWhich("codex")?.let { out.add(it) }
        return out.toList()
    }

    private fun commonBinDirs(): List<Path> {
        val home = Path.of(System.getProperty("user.home") ?: ".")
        return listOf(
            home.resolve(".npm-global/bin"),
            home.resolve(".local/bin"),
            home.resolve(".cargo/bin"),
            home.resolve(".bun/bin"),
            home.resolve("bin"),
            Path.of("/usr/local/bin"),
            Path.of("/usr/bin"),
        )
    }

    private fun loginShellPath(): String? = runLoginShell("printf %s \"\$PATH\"")

    private fun loginShellWhich(name: String): Path? {
        val text = runLoginShell("command -v ${shellQuote(name)}") ?: return null
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return Path.of(line)
    }

    private fun runLoginShell(command: String): String? {
        return try {
            val process = ProcessBuilder("bash", "-lc", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0 || output.isEmpty()) null else output
        } catch (_: Exception) {
            null
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
