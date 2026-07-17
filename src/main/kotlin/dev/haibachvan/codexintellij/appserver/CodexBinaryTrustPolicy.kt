package dev.haibachvan.codexintellij.appserver

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Gates schema generation and (later) process launch on a reviewed, revalidated executable.
 * The committed schema manifest is evidence only and never confers runtime trust.
 */
class CodexBinaryTrustPolicy(
    private val storePath: Path,
    private val versionRunner: (Path) -> String = Companion::defaultVersionRunner,
    private val environmentReader: () -> Map<String, String> = { System.getenv() },
) {
    data class BinaryIdentity(
        val canonicalPath: String,
        val sha256: String,
        val size: Long,
        val fileKey: String,
        val versionText: String,
    )

    sealed class TrustDecision {
        data class Trusted(val identity: BinaryIdentity) : TrustDecision()
        data class NeedsConfirmation(val current: BinaryIdentity, val expected: BinaryIdentity?) : TrustDecision()
        data class Rejected(val reason: String) : TrustDecision()
    }

    data class PreviewedEnvironment(
        val inherited: Map<String, String>,
        val optedIn: Map<String, String>,
        val previewKeys: List<String>,
    )

    fun inspect(path: Path): BinaryIdentity {
        require(path.exists(LinkOption.NOFOLLOW_LINKS)) { "Path does not exist: $path" }
        require(path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            "Codex binary must be a regular file (symlinks rejected): $path"
        }
        require(path.isExecutable()) { "Codex binary must be executable: $path" }

        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile) { "Codex binary must be a regular file: $path" }

        val bytes = path.readBytes()
        val sha256 = sha256Hex(bytes)
        val fileKey = fileKeyOf(attrs)
        val versionText = versionRunner(path).trim()
        require(versionText.isNotEmpty()) { "codex --version produced empty output" }

        return BinaryIdentity(
            canonicalPath = path.toAbsolutePath().normalize().toString(),
            sha256 = sha256,
            size = attrs.size(),
            fileKey = fileKey,
            versionText = versionText,
        )
    }

    fun confirm(identity: BinaryIdentity) {
        // Re-read immediately so confirmation cannot race a swapped inode.
        val current = inspect(Path.of(identity.canonicalPath))
        require(current == identity) {
            "Binary identity changed between inspect and confirm"
        }
        Files.createDirectories(storePath.parent)
        storePath.writeText(encodeStore(current))
    }

    fun loadConfirmed(): BinaryIdentity? {
        if (!Files.isRegularFile(storePath)) {
            return null
        }
        return decodeStore(storePath.readText())
    }

    fun revalidate(expected: BinaryIdentity? = loadConfirmed()): TrustDecision {
        val baseline = expected
            ?: return TrustDecision.NeedsConfirmation(
                current = BinaryIdentity("", "", 0, "", ""),
                expected = null,
            )
        return try {
            val current = inspect(Path.of(baseline.canonicalPath))
            when {
                current == baseline -> TrustDecision.Trusted(current)
                else -> TrustDecision.NeedsConfirmation(current = current, expected = baseline)
            }
        } catch (ex: Exception) {
            TrustDecision.Rejected(ex.message ?: ex::class.java.simpleName)
        }
    }

    /**
     * Auto-discovers `codex` (PATH / CODEX_BIN) and pins it without a user confirmation dialog.
     * Re-links automatically when the previously stored identity is missing or changed.
     */
    fun ensureTrustedForLaunch(
        resolver: () -> Path = { CodexBinaryLocator.resolve() },
    ): BinaryIdentity {
        when (val decision = revalidate()) {
            is TrustDecision.Trusted -> return decision.identity
            is TrustDecision.NeedsConfirmation, is TrustDecision.Rejected -> {
                val discovered = CodexBinaryLocator.toRegularExecutable(resolver())
                val identity = inspect(discovered)
                confirm(identity)
                return identity
            }
        }
    }

    /**
     * Builds a child-process environment from an explicit allowlist.
     * Extra keys require per-key opt-in; values are never included in [PreviewedEnvironment] logs
     * beyond key names in [PreviewedEnvironment.previewKeys].
     */
    fun environment(extraKeys: Set<String> = emptySet()): PreviewedEnvironment {
        val source = environmentReader()
        val inherited = LinkedHashMap<String, String>()
        for (key in DEFAULT_ALLOWLIST) {
            if (key.endsWith("*")) {
                val prefix = key.dropLast(1)
                source.filterKeys { it.startsWith(prefix) }
                    .toList()
                    .sortedBy { it.first }
                    .forEach { (k, v) -> inherited[k] = v }
            } else if (key == "PATH") {
                // Desktop-launched IDEs often miss npm/cargo user bins; enrich for node shebang wrappers.
                inherited["PATH"] = CodexBinaryLocator.enrichedPath(source)
            } else {
                val value = source[key] ?: continue
                inherited[key] = value
            }
        }

        val optedIn = LinkedHashMap<String, String>()
        val previewKeys = ArrayList<String>()
        for (key in extraKeys.sorted()) {
            require(key.isNotBlank()) { "Extra environment key must not be blank" }
            require(!isForbiddenKey(key)) {
                "Environment key '$key' cannot be opted in (token/proxy secret class)"
            }
            val value = source[key]
            require(value != null) { "Opt-in environment key '$key' is not present" }
            optedIn[key] = value
            previewKeys += key
        }

        return PreviewedEnvironment(
            inherited = inherited.toMap(),
            optedIn = optedIn.toMap(),
            previewKeys = previewKeys,
        )
    }

    companion object {
        val DEFAULT_ALLOWLIST: Set<String> = setOf(
            "HOME",
            "PATH",
            "CODEX_HOME",
            "XDG_CONFIG_HOME",
            "XDG_CACHE_HOME",
            "XDG_DATA_HOME",
            "LANG",
            "LC_*",
            "TMPDIR",
        )

        private val FORBIDDEN_SUBSTRINGS = listOf(
            "TOKEN",
            "SECRET",
            "PASSWORD",
            "API_KEY",
            "AUTHORIZATION",
            "PROXY",
        )

        fun isForbiddenKey(key: String): Boolean {
            val upper = key.uppercase(Locale.ROOT)
            return FORBIDDEN_SUBSTRINGS.any { upper.contains(it) }
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }

        private fun fileKeyOf(attrs: BasicFileAttributes): String {
            val key = attrs.fileKey()
            return key?.toString() ?: "mtime=${attrs.lastModifiedTime().toMillis()}:size=${attrs.size()}"
        }

        private fun defaultVersionRunner(path: Path): String {
            val builder = ProcessBuilder(path.toAbsolutePath().toString(), "--version")
                .redirectErrorStream(true)
            builder.environment()["PATH"] = CodexBinaryLocator.enrichedPath()
            val process = builder.start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!finished) {
                process.destroyForcibly()
                throw IOException("Timed out running codex --version")
            }
            if (process.exitValue() != 0) {
                throw IOException("codex --version failed (exit=${process.exitValue()}): $output")
            }
            return output
        }

        private fun encodeStore(identity: BinaryIdentity): String =
            buildString {
                appendLine("canonicalPath=${escape(identity.canonicalPath)}")
                appendLine("sha256=${identity.sha256}")
                appendLine("size=${identity.size}")
                appendLine("fileKey=${escape(identity.fileKey)}")
                appendLine("versionText=${escape(identity.versionText)}")
            }

        private fun decodeStore(text: String): BinaryIdentity {
            val map = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && "=" in it }
                .associate {
                    val idx = it.indexOf('=')
                    it.substring(0, idx) to unescape(it.substring(idx + 1))
                }
            return BinaryIdentity(
                canonicalPath = map.getValue("canonicalPath"),
                sha256 = map.getValue("sha256"),
                size = map.getValue("size").toLong(),
                fileKey = map.getValue("fileKey"),
                versionText = map.getValue("versionText"),
            )
        }

        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\n", "\\n")

        private fun unescape(value: String): String =
            buildString(value.length) {
                var i = 0
                while (i < value.length) {
                    val c = value[i]
                    if (c == '\\' && i + 1 < value.length) {
                        when (value[i + 1]) {
                            'n' -> append('\n')
                            '\\' -> append('\\')
                            else -> append(value[i + 1])
                        }
                        i += 2
                    } else {
                        append(c)
                        i += 1
                    }
                }
            }

        private fun Path.writeText(text: String) {
            Files.writeString(this, text)
        }

        private fun Path.readText(): String = Files.readString(this)
    }
}
