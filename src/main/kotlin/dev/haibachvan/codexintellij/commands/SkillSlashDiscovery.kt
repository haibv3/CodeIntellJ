package dev.haibachvan.codexintellij.commands

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Discovers user-invocable skills from global/project skill roots
 * (`~/.agents/skills`, `~/.codex/skills`, …) for `/` suggestions.
 */
object SkillSlashDiscovery {
    data class SkillSlash(
        val name: String,
        val description: String,
        val sourcePath: String,
    ) {
        val insertText: String get() = "/$name "
    }

    fun discover(projectBasePath: String? = null): List<SkillSlash> {
        val home = System.getProperty("user.home") ?: return emptyList()
        val roots = buildList {
            add(Path.of(home, ".agents", "skills"))
            add(Path.of(home, ".agent", "skills"))
            add(Path.of(home, ".codex", "skills"))
            add(Path.of(home, ".cursor", "skills-cursor"))
            add(Path.of(home, ".cursor", "skills"))
            if (!projectBasePath.isNullOrBlank()) {
                val base = Path.of(projectBasePath)
                add(base.resolve(".agents").resolve("skills"))
                add(base.resolve(".codex").resolve("skills"))
                add(base.resolve(".cursor").resolve("skills"))
            }
        }
        val out = LinkedHashMap<String, SkillSlash>()
        for (root in roots) {
            if (!root.isDirectory()) continue
            try {
                Files.list(root).use { stream ->
                    stream.filter { it.isDirectory() && !it.name.startsWith(".") }.forEach { dir ->
                        val skillFile = dir.resolve("SKILL.md")
                        if (!skillFile.isRegularFile()) return@forEach
                        parseSkill(skillFile)?.let { skill ->
                            out.putIfAbsent(skill.name.lowercase(), skill)
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore unreadable roots
            }
        }
        return out.values.sortedBy { it.name.lowercase() }
    }

    internal fun parseSkill(path: Path): SkillSlash? {
        return try {
            val lines = Files.readAllLines(path)
            if (lines.isEmpty() || lines.first().trim() != "---") {
                // Fallback: folder name
                val name = path.parent?.fileName?.toString() ?: return null
                return SkillSlash(name, "Skill $name", path.toAbsolutePath().normalize().toString())
            }
            var name: String? = null
            var description: String? = null
            var invocable = true
            var collectingDescription = false
            val descriptionBuf = StringBuilder()
            for (raw in lines.drop(1)) {
                val line = raw.trimEnd()
                val trimmed = line.trim()
                if (trimmed == "---") break
                if (collectingDescription) {
                    // Folded/literal YAML block continues while indented.
                    if (line.startsWith(" ") || line.startsWith("\t")) {
                        descriptionBuf.append(' ').append(trimmed)
                        continue
                    }
                    collectingDescription = false
                    description = unwrap(descriptionBuf.toString())
                }
                when {
                    trimmed.startsWith("name:") ->
                        name = trimmed.removePrefix("name:").trim().trim('"', '\'')
                    trimmed.startsWith("description:") -> {
                        val rest = trimmed.removePrefix("description:").trim()
                        if (rest == ">" || rest == ">-" || rest == "|" || rest == "|-") {
                            collectingDescription = true
                            descriptionBuf.clear()
                        } else {
                            description = unwrap(rest)
                        }
                    }
                    trimmed.startsWith("user-invocable:") ->
                        invocable = !trimmed.substringAfter(':').trim().equals("false", ignoreCase = true)
                }
            }
            if (collectingDescription && descriptionBuf.isNotEmpty()) {
                description = unwrap(descriptionBuf.toString())
            }
            if (!invocable) return null
            val resolvedName = name?.takeIf { it.isNotBlank() }
                ?: path.parent?.fileName?.toString()
                ?: return null
            if (resolvedName.equals("MIGRATION", ignoreCase = true)) return null
            SkillSlash(
                name = resolvedName,
                description = (description ?: "Skill $resolvedName").lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.let { if (it.length <= 110) it else it.take(109).trimEnd() + "…" }
                    ?: "Skill $resolvedName",
                sourcePath = path.toAbsolutePath().normalize().toString(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun unwrap(raw: String): String =
        raw.trim().trim('"', '\'').replace("\\n", " ").replace(Regex("\\s+"), " ")
}
