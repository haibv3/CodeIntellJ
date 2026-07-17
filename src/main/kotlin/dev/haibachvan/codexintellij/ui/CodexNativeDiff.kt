package dev.haibachvan.codexintellij.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/** Opens a native IntelliJ editor tab titled "codex diff" for modified files. */
object CodexNativeDiff {
    private const val TAB_TITLE = "codex diff"

    fun open(project: Project, files: List<ModifiedFilesActions.FileRow>) {
        ApplicationManager.getApplication().invokeLater {
            val requests = files.mapNotNull { row -> buildRequest(project, row) }
            if (requests.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "Không dựng được diff (thiếu unified diff hoặc không đọc được tệp).",
                    "Codex Diff",
                )
                return@invokeLater
            }
            val chain = SimpleDiffRequestChain(requests)
            chain.putUserData(DiffUserDataKeysEx.VCS_DIFF_EDITOR_TAB_TITLE, TAB_TITLE)
            val diffFile = ChainDiffVirtualFile(chain, TAB_TITLE)
            DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true)
        }
    }

    private fun buildRequest(project: Project, row: ModifiedFilesActions.FileRow): SimpleDiffRequest? {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(row.path)
        val factory = DiffContentFactory.getInstance()
        val pair = resolveBeforeAfter(project, row) ?: return null
        val (before, after) = pair
        val shortName = displayName(row.path, project)
        // Both sides from strings — avoids VF/charset mismatch that made reverse-applied
        // "Trước" look wrong next to a live VirtualFile "Sau".
        val beforeContent = factory.create(project, before, fileType)
        val afterContent = factory.create(project, after, fileType)
        val request = SimpleDiffRequest(
            shortName,
            beforeContent,
            afterContent,
            "Trước",
            "Sau",
        )
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        return request
    }

    /**
     * Prefer full-file before/after via reverse-apply on disk content.
     * Never mix a full file with hunk-only reconstruction (that made diffs look wrong).
     */
    internal fun resolveBeforeAfter(
        project: Project,
        row: ModifiedFilesActions.FileRow,
    ): Pair<String, String>? {
        val diff = row.unifiedDiff?.takeIf { it.isNotBlank() }
        val onDisk = readText(project, row.path)
        return when (row.kind.lowercase()) {
            "add" -> {
                val after = onDisk ?: PatchSides.newFile(diff) ?: return null
                "" to after
            }
            "delete" -> {
                val before = PatchSides.oldFile(diff) ?: onDisk ?: return null
                before to ""
            }
            else -> {
                if (diff == null) return null
                if (onDisk != null) {
                    val before = UnifiedDiffReverser.reverse(onDisk, diff)
                    if (before != null) return before to onDisk
                }
                // Same source for both sides: hunk reconstruction (focused change view).
                val oldSide = PatchSides.oldFile(diff) ?: ""
                val newSide = PatchSides.newFile(diff) ?: ""
                if (oldSide.isEmpty() && newSide.isEmpty()) return null
                oldSide to newSide
            }
        }
    }

    fun displayName(path: String, project: Project?): String {
        val short = relativizePath(path, project)
        return short.substringAfterLast('/').substringAfterLast('\\').ifBlank { short }
    }

    fun relativizePath(path: String, project: Project?): String {
        val normalized = path.replace('\\', '/')
        if (project != null) {
            val local = LocalFileSystem.getInstance()
            val absolute = local.findFileByPath(normalized)
            for (root in ProjectRootManager.getInstance(project).contentRoots) {
                val rootPath = root.path.replace('\\', '/')
                if (normalized.startsWith("$rootPath/")) {
                    return normalized.removePrefix("$rootPath/")
                }
                if (absolute != null) {
                    val relative = VfsUtilCore.getRelativePath(absolute, root)
                    if (!relative.isNullOrBlank()) return relative
                }
            }
            project.basePath?.replace('\\', '/')?.trimEnd('/')?.let { base ->
                if (normalized.startsWith("$base/")) return normalized.removePrefix("$base/")
            }
        }
        for (marker in listOf("/packages/", "/android/", "/frameworks/", "/vendor/", "/docs/")) {
            val idx = normalized.indexOf(marker)
            if (idx >= 0) return normalized.substring(idx + 1)
        }
        val parts = normalized.split('/').filter { it.isNotBlank() }
        return if (parts.size <= 3) normalized else parts.takeLast(3).joinToString("/")
    }

    private fun readText(project: Project, path: String): String? {
        val vf = resolveVirtualFile(project, path) ?: return null
        if (!vf.exists() || vf.isDirectory) return null
        return runCatching { String(vf.contentsToByteArray(), vf.charset) }.getOrNull()
    }

    private fun resolveVirtualFile(project: Project, path: String): VirtualFile? {
        val local = LocalFileSystem.getInstance()
        local.findFileByPath(path)?.let { return it }
        val base = project.basePath ?: return null
        val abs = Path.of(base).resolve(path).normalize()
        if (Files.exists(abs)) return local.refreshAndFindFileByNioFile(abs)
        // Search content roots
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            val candidate = Path.of(root.path).resolve(path).normalize()
            if (Files.exists(candidate)) {
                return local.refreshAndFindFileByNioFile(candidate)
            }
        }
        return null
    }
}

/** Extract old/new file text from unified diff hunks (both sides from the same patch). */
object PatchSides {
    fun oldFile(diff: String?): String? = collect(diff, old = true)
    fun newFile(diff: String?): String? = collect(diff, old = false)

    private fun collect(diff: String?, old: Boolean): String? {
        if (diff.isNullOrBlank()) return null
        val out = ArrayList<String>()
        for (line in diff.lineSequence()) {
            when {
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("---") || line.startsWith("+++") ||
                    line.startsWith("@@") || line.startsWith("new file") ||
                    line.startsWith("deleted file") || line.startsWith("similarity") ||
                    line.startsWith("rename") -> Unit
                line.startsWith("\\") -> Unit
                line.startsWith(" ") -> out += line.substring(1)
                line.startsWith("-") -> if (old) out += line.substring(1)
                line.startsWith("+") -> if (!old) out += line.substring(1)
            }
        }
        return out.joinToString("\n").takeIf { it.isNotEmpty() || diff.contains("@@") }
    }
}
