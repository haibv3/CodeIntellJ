package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reverts agent file changes using the stored unified diffs (reverse apply).
 * Best-effort — complex renames / binary patches are skipped.
 */
object PatchFileUndo {
    data class Result(
        val reverted: List<String>,
        val skipped: List<String>,
        val errors: List<String>,
    )

    fun undo(project: Project, files: List<ModifiedFilesActions.FileRow>): Result {
        val reverted = ArrayList<String>()
        val skipped = ArrayList<String>()
        val errors = ArrayList<String>()
        for (file in files) {
            try {
                when (file.kind.lowercase()) {
                    "add" -> {
                        if (deleteFile(project, file.path)) reverted += file.path
                        else skipped += file.path
                    }
                    else -> {
                        val diff = file.unifiedDiff
                        if (diff.isNullOrBlank()) {
                            skipped += file.path
                            continue
                        }
                        val vf = resolveVirtualFile(project, file.path)
                        if (vf == null || !vf.exists() || vf.isDirectory) {
                            skipped += file.path
                            continue
                        }
                        val current = String(vf.contentsToByteArray(), vf.charset)
                        val previous = UnifiedDiffReverser.reverse(current, diff)
                        if (previous == null) {
                            skipped += file.path
                            continue
                        }
                        writeFile(project, vf, previous)
                        reverted += file.path
                    }
                }
            } catch (ex: Exception) {
                errors += "${file.path}: ${ex.message ?: ex.javaClass.simpleName}"
            }
        }
        return Result(reverted = reverted, skipped = skipped, errors = errors)
    }

    private fun deleteFile(project: Project, path: String): Boolean {
        val vf = resolveVirtualFile(project, path) ?: return false
        var ok = false
        WriteCommandAction.runWriteCommandAction(project, "Codex Undo Add", "Codex", Runnable {
            ok = runCatching { vf.delete(this) }.isSuccess
        })
        return ok
    }

    private fun writeFile(project: Project, vf: VirtualFile, content: String) {
        WriteCommandAction.runWriteCommandAction(project, "Codex Undo Patch", "Codex", Runnable {
            VfsUtil.saveText(vf, content)
        })
    }

    private fun resolveVirtualFile(project: Project, path: String): VirtualFile? {
        val local = LocalFileSystem.getInstance()
        local.findFileByPath(path)?.let { return it }
        val base = project.basePath ?: return null
        val abs = Path.of(base).resolve(path).normalize()
        if (!Files.exists(abs)) {
            ApplicationManager.getApplication().invokeAndWait {
                local.refreshAndFindFileByNioFile(abs)
            }
        }
        return local.findFileByNioFile(abs) ?: local.refreshAndFindFileByNioFile(abs)
    }
}

/** Minimal unified-diff reverse apply for text files. */
object UnifiedDiffReverser {
    fun reverse(current: String, unifiedDiff: String): String? {
        return try {
            val lines = splitLines(current)
            val hunks = parseHunks(unifiedDiff)
            if (hunks.isEmpty()) return null
            // Apply from bottom to top so line numbers stay valid.
            for (hunk in hunks.asReversed()) {
                if (!applyReverseHunk(lines, hunk)) return null
            }
            joinLines(lines, current)
        } catch (_: Throwable) {
            null
        }
    }

    private fun splitLines(text: String): MutableList<String> {
        val normalized = text.replace("\r\n", "\n")
        if (normalized.isEmpty()) return mutableListOf()
        val parts = normalized.split('\n')
        return if (normalized.endsWith('\n')) {
            parts.dropLast(1).toMutableList()
        } else {
            parts.toMutableList()
        }
    }

    private data class Hunk(val oldStart: Int, val newStart: Int, val lines: List<String>)

    private fun parseHunks(diff: String): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var oldStart = 1
        var newStart = 1
        var body = ArrayList<String>()
        fun flush() {
            if (body.isNotEmpty()) {
                hunks += Hunk(oldStart, newStart, body)
                body = ArrayList()
            }
        }
        val header = Regex("""^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@""")
        for (line in diff.lineSequence()) {
            val m = header.find(line)
            if (m != null) {
                flush()
                oldStart = m.groupValues[1].toInt().coerceAtLeast(1)
                newStart = m.groupValues[2].toInt().coerceAtLeast(1)
                continue
            }
            if (line.startsWith("diff ") ||
                line.startsWith("index ") ||
                line.startsWith("---") ||
                line.startsWith("+++") ||
                line.startsWith("new file") ||
                line.startsWith("deleted file")
            ) {
                continue
            }
            if (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ") || line == "\\ No newline at end of file") {
                body += line
            }
        }
        flush()
        return hunks
    }

    private fun applyReverseHunk(lines: MutableList<String>, hunk: Hunk): Boolean {
        // After forward apply, file matches "new" side. Reverse: remove '+' lines, restore '-' lines.
        val expected = ArrayList<String>()
        val replacement = ArrayList<String>()
        for (line in hunk.lines) {
            when {
                line.startsWith("+") -> expected += line.substring(1)
                line.startsWith("-") -> replacement += line.substring(1)
                line.startsWith(" ") -> {
                    val text = line.substring(1)
                    expected += text
                    replacement += text
                }
                line.startsWith("\\") -> Unit
                else -> return false
            }
        }
        if (expected.isEmpty() && replacement.isEmpty()) return true
        // Locate using NEW-file line number — we're matching against current (after) content.
        val start = findSequence(lines, expected, (hunk.newStart - 1).coerceAtLeast(0))
            ?: findSequence(lines, expected, 0)
            ?: return false
        repeat(expected.size) {
            if (start < lines.size) lines.removeAt(start)
        }
        replacement.asReversed().forEach { lines.add(start, it) }
        return true
    }

    private fun findSequence(lines: List<String>, needle: List<String>, from: Int): Int? {
        if (needle.isEmpty()) return from.coerceIn(0, lines.size)
        val max = lines.size - needle.size
        if (max < 0) return null
        fun matchesAt(i: Int): Boolean {
            for (j in needle.indices) {
                if (!linesEqualSoft(lines[i + j], needle[j])) return false
            }
            return true
        }
        val start = from.coerceIn(0, max)
        for (i in start..max) if (matchesAt(i)) return i
        for (i in 0 until start) if (matchesAt(i)) return i
        return null
    }

    private fun linesEqualSoft(a: String, b: String): Boolean =
        a == b || a.trimEnd() == b.trimEnd()


    private fun joinLines(lines: List<String>, original: String): String {
        val nl = if (original.contains("\r\n")) "\r\n" else "\n"
        val body = lines.joinToString(nl)
        return if (original.endsWith("\n") || original.endsWith("\r\n")) body + nl else body
    }
}
