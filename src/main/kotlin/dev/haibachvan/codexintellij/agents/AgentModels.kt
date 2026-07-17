package dev.haibachvan.codexintellij.agents

data class AgentDescriptor(
    val id: String,
    val name: String,
    val sourcePath: String,
)

data class AgentTreeNode(
    val itemId: String,
    val agentId: String,
    val parentItemId: String?,
    val status: String,
    val summary: String?,
    val children: List<AgentTreeNode> = emptyList(),
)
