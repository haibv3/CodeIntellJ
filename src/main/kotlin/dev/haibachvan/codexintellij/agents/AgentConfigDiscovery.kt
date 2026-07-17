package dev.haibachvan.codexintellij.agents

import java.nio.file.Files
import java.nio.file.Path

class AgentConfigDiscovery {
    fun discover(searchRoots: List<Path>): List<AgentDescriptor> {
        val out = ArrayList<AgentDescriptor>()
        for (root in searchRoots) {
            val dirs = listOf(
                root.resolve(".codex/agents"),
                root.resolve("agents"),
                root.resolve(".agents/agents"),
            )
            for (agentsDir in dirs) {
                if (!Files.isDirectory(agentsDir)) continue
                Files.list(agentsDir).use { stream ->
                    stream.filter {
                        val name = it.fileName.toString()
                        name.endsWith(".toml") || name.endsWith(".md")
                    }.forEach { path ->
                        val id = path.fileName.toString().substringBeforeLast('.')
                        if (id.equals("MIGRATION", ignoreCase = true) || id.equals("README", ignoreCase = true)) {
                            return@forEach
                        }
                        out += AgentDescriptor(
                            id = id,
                            name = id,
                            sourcePath = path.toAbsolutePath().normalize().toString(),
                        )
                    }
                }
            }
        }
        return out.distinctBy { it.id.lowercase() }.sortedBy { it.id }
    }
}
