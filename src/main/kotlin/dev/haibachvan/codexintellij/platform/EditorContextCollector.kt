package dev.haibachvan.codexintellij.platform

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Captures immutable context snapshots limited to canonical content roots. */
class EditorContextCollector(
    private val contentRoots: List<Path>,
    private val projectId: String = "project",
) {
    fun capture(
        kind: ContextKind,
        path: Path,
        text: String?,
        utf16Start: Int? = null,
        utf16End: Int? = null,
        modificationStamp: Long = 0L,
        languageHint: String? = null,
        unsaved: Boolean = false,
        maxChars: Int = 200_000,
    ): Result<ContextSnapshot> = runCatching {
        val canonical = path.toAbsolutePath().normalize()
        require(Files.exists(canonical) || unsaved || kind == ContextKind.SELECTION) {
            "Path does not exist: $canonical"
        }
        val root = contentRoots.map { it.toAbsolutePath().normalize() }
            .firstOrNull { canonical.startsWith(it) }
            ?: error("Path outside content roots: $canonical")
        val relative = root.relativize(canonical).toString().ifBlank { canonical.fileName.toString() }
        val body = text ?: if (Files.isRegularFile(canonical)) Files.readString(canonical) else ""
        val truncated = body.length > maxChars
        val clipped = if (truncated) body.take(maxChars) else body
        val bytes = clipped.toByteArray(StandardCharsets.UTF_8)
        val utf8Start = utf16Start?.let { clipped.take(it).toByteArray(StandardCharsets.UTF_8).size }
        val utf8End = utf16End?.let { clipped.take(it).toByteArray(StandardCharsets.UTF_8).size }
        ContextSnapshot(
            kind = kind,
            canonicalPath = canonical.toString(),
            relativePath = relative,
            displayName = canonical.fileName.toString(),
            text = if (kind == ContextKind.SAVED_FILE && !unsaved) null else clipped,
            utf16Start = utf16Start,
            utf16End = utf16End,
            lineStart = null,
            lineEnd = null,
            utf8Start = utf8Start,
            utf8End = utf8End,
            modificationStamp = modificationStamp,
            languageHint = languageHint,
            truncated = truncated,
            unsaved = unsaved,
            contentSha256 = sha256(bytes),
            projectId = projectId,
            contentRootId = root.toString(),
        )
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
