package dev.haibachvan.codexintellij.agents

import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId

object AgentTreeProjection {
    fun from(state: NormalizedServerState, threadId: ThreadId?): List<AgentTreeNode> {
        val agents = state.agents.values.filter { agent ->
            val item = state.items[agent.itemId]
            threadId == null || item?.threadId == threadId
        }
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
        return build(null)
    }
}
