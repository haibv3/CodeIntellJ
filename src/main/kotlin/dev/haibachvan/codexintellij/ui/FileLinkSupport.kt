package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parses and resolves transcript file links such as `/abs/path/File.java:369`. */
object FileLinkSupport {
    data class Target(
        val path: String,
        val line: Int? = null,
        val column: Int? = null,
    )

    private val LINE_COL = Regex("""^(.*?)(?::(\d+))(?::(\d+))?$""")

    fun parse(raw: String): Target {
        var value = raw.trim()
            .removePrefix("codex-open:")
            .trim()
        value = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

        var line: Int? = null
        var column: Int? = null
        val match = LINE_COL.matchEntire(value)
        if (match != null) {
            val pathPart = match.groupValues[1]
            val linePart = match.groupValues[2].toIntOrNull()
            val colPart = match.groupValues[3].toIntOrNull()
            val looksLikeLine = pathPart.contains('/') || pathPart.contains('\\') ||
                pathPart.contains('.')
            if (looksLikeLine && linePart != null && linePart > 0) {
                value = pathPart
                line = linePart
                column = colPart?.takeIf { it > 0 }
            }
        }

        when {
            value.startsWith("file:") -> {
                value = runCatching { URI(value).path }.getOrNull()
                    ?: value.removePrefix("file://").removePrefix("file:")
            }
        }
        return Target(value, line, column)
    }

    fun resolve(project: Project, target: Target): VirtualFile? {
        val rawPath = target.path.trim()
        if (rawPath.isBlank()) return null
        val lfs = LocalFileSystem.getInstance()
        lfs.findFileByPath(rawPath)?.takeUnless { it.isDirectory }?.let { return it }
        lfs.refreshAndFindFileByPath(rawPath)?.takeUnless { it.isDirectory }?.let { return it }
        val base = project.basePath
        if (!base.isNullOrBlank()) {
            val relative = rawPath.removePrefix("./")
            lfs.findFileByPath("$base/$relative")?.takeUnless { it.isDirectory }?.let { return it }
            lfs.refreshAndFindFileByPath("$base/$relative")?.takeUnless { it.isDirectory }?.let { return it }
        }
        val name = rawPath.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank() || !name.contains('.')) return null
        return runCatching {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
                .firstOrNull { !it.isDirectory }
        }.getOrNull()
    }

    fun open(project: Project, raw: String): Boolean {
        val target = parse(raw)
        if (target.path.startsWith("http://") || target.path.startsWith("https://")) {
            return false
        }
        val vf = resolve(project, target) ?: return false
        val line = ((target.line ?: 1) - 1).coerceAtLeast(0)
        val column = ((target.column ?: 1) - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, vf, line, column).navigate(true)
        return true
    }

    fun looksLikeLocalFileHref(href: String): Boolean {
        val v = href.trim()
        if (v.isBlank() || v.startsWith("#") || v.startsWith("codex-")) return false
        if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("mailto:")) return false
        val parsed = parse(v)
        val p = parsed.path
        if (p.length < 3 || p.length > 500) return false
        return p.startsWith("/") || p.startsWith("file:") ||
            p.contains('/') || p.contains('\\') ||
            Regex("""(?i)\.\w{1,8}$""").containsMatchIn(p)
    }
}
