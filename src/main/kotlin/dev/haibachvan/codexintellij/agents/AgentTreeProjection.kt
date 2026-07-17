package dev.haibachvan.codexintellij.agents

import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId

object AgentTreeProjection {
    fun from(state: NormalizedServerState, threadId: ThreadId?): List<AgentTreeNode> {
        val agents = state.agents.values.filter { agent ->
            val item = state.items[agent.itemId]
            // Synthetic collab agents (agent:<threadId>) are not in items — still show them.
            item == null || threadId == null || item.threadId == threadId
        }
        if (agents.isEmpty()) return emptyList()
        val ids = agents.map { it.itemId.value }.toSet()
        val byParent = agents.groupBy { it.parentItemId?.value }
        fun build(parentId: String?): List<AgentTreeNode> =
            (byParent[parentId] ?: emptyList()).map { agent ->
                AgentTreeNode(
                    itemId = agent.itemId.value,
                    agentId = agent.agentId,
                    parentItemId = agent.parentItemId?.value,
                    status = agent.status.name,
                    summary = agent.summary,
                    children = build(agent.itemId.value),
                )
            }
        // Roots: no parent, or parent not present in this agent set (orphan under missing parent).
        val roots = agents.filter { agent ->
            val parent = agent.parentItemId?.value
            parent == null || parent !in ids
        }
        return roots.map { agent ->
            AgentTreeNode(
                itemId = agent.itemId.value,
                agentId = agent.agentId,
                parentItemId = agent.parentItemId?.value,
                status = agent.status.name,
                summary = agent.summary,
                children = build(agent.itemId.value),
            )
        }
    }
}
