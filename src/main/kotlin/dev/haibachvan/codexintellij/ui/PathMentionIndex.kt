package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Path
import kotlin.io.path.relativeToOrNull

/** Project file/dir candidates for `@` mentions. */
object PathMentionIndex {
    data class Hit(
        val file: VirtualFile,
        val relativePath: String,
        val isDirectory: Boolean,
    )

    fun search(project: Project, rawQuery: String, limit: Int = 40): List<Hit> {
        val query = rawQuery.trim().removePrefix("@").trim().replace('\\', '/')
        return ReadAction.compute<List<Hit>, RuntimeException> {
            if (query.contains('/')) {
                resolvePathQuery(project, query, limit)
            } else {
                searchByName(project, query, limit)
            }
        }
    }

    private fun searchByName(project: Project, query: String, limit: Int): List<Hit> {
        val basePath = project.basePath ?: return emptyList()
        val base = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        val index = ProjectFileIndex.getInstance(project)
        val q = query.lowercase()
        val hits = LinkedHashMap<String, Hit>()

        // Always seed with top-level project entries so `@` is never empty of files.
        base.children
            ?.asSequence()
            ?.filter { !it.name.startsWith(".") }
            ?.filter { index.isInContent(it) || it.isDirectory }
            ?.forEach { vf ->
                val rel = relativePath(project, vf) ?: return@forEach
                if (q.isEmpty() || rel.lowercase().contains(q) || vf.name.lowercase().contains(q)) {
                    hits.putIfAbsent(rel, Hit(vf, rel, vf.isDirectory))
                }
            }

        try {
            FilenameIndex.processAllFileNames({ name ->
                if (hits.size >= limit * 4) return@processAllFileNames false
                val lower = name.lowercase()
                val match = q.isEmpty() || lower.startsWith(q) || lower.contains(q)
                if (match) {
                    FilenameIndex.getVirtualFilesByName(name, scope).forEach { vf ->
                        if (!index.isInContent(vf)) return@forEach
                        if (vf.path.contains("/.") || vf.path.contains("\\.")) return@forEach
                        val rel = relativePath(project, vf) ?: return@forEach
                        hits.putIfAbsent(rel, Hit(vf, rel, vf.isDirectory))
                    }
                }
                true
            }, scope, null)
        } catch (_: Throwable) {
            // FilenameIndex can fail before indices are ready; VFS seed above still works.
        }

        if (q.isNotEmpty()) {
            collectDirs(project, q, limit).forEach { hit ->
                hits.putIfAbsent(hit.relativePath, hit)
            }
        }

        return hits.values
            .sortedWith(
                compareBy<Hit>(
                    { if (it.relativePath.lowercase().startsWith(q)) 0 else 1 },
                    { if (it.file.name.lowercase().startsWith(q)) 0 else 1 },
                    { it.relativePath.length },
                    { it.relativePath.lowercase() },
                ),
            )
            .take(limit)
    }

    private fun resolvePathQuery(project: Project, query: String, limit: Int): List<Hit> {
        val basePath = project.basePath ?: return emptyList()
        val base = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val index = ProjectFileIndex.getInstance(project)
        val q = query.lowercase().trimEnd('/')
        val hits = mutableListOf<Hit>()

        fun walk(dir: VirtualFile, depth: Int) {
            if (hits.size >= limit || depth > 8) return
            dir.children?.forEach { child ->
                if (!index.isInContent(child)) return@forEach
                if (child.name.startsWith(".")) return@forEach
                val rel = relativePath(project, child) ?: return@forEach
                val lower = rel.lowercase()
                if (lower.startsWith(q) || lower.contains(q)) {
                    hits += Hit(child, rel, child.isDirectory)
                }
                if (child.isDirectory && (lower.startsWith(q.substringBeforeLast('/')) || q.count { it == '/' } >= depth)) {
                    walk(child, depth + 1)
                }
            }
        }
        walk(base, 0)
        return hits
            .distinctBy { it.relativePath }
            .sortedWith(compareBy({ !it.relativePath.lowercase().startsWith(q) }, { it.relativePath.length }))
            .take(limit)
    }

    private fun collectDirs(project: Project, query: String, limit: Int): List<Hit> {
        val basePath = project.basePath ?: return emptyList()
        val base = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val index = ProjectFileIndex.getInstance(project)
        val q = query.lowercase()
        val out = mutableListOf<Hit>()
        fun walk(dir: VirtualFile, depth: Int) {
            if (out.size >= limit || depth > 6) return
            dir.children?.forEach { child ->
                if (!child.isDirectory || !index.isInContent(child) || child.name.startsWith(".")) return@forEach
                val rel = relativePath(project, child) ?: return@forEach
                if (child.name.lowercase().contains(q) || rel.lowercase().contains(q)) {
                    out += Hit(child, rel, true)
                }
                walk(child, depth + 1)
            }
        }
        walk(base, 0)
        return out
    }

    fun relativePath(project: Project, file: VirtualFile): String? {
        val base = project.basePath?.let { Path.of(it) } ?: return file.name
        val absolute = try {
            file.toNioPath()
        } catch (_: Exception) {
            try {
                file.fileSystem.getNioPath(file)
            } catch (_: Exception) {
                return file.path
                    .removePrefix(project.basePath + "/")
                    .removePrefix(project.basePath + "\\")
            }
        } ?: return file.name
        return absolute.relativeToOrNull(base)?.normalize()?.toString()?.replace('\\', '/')
            ?: file.name
    }
}
