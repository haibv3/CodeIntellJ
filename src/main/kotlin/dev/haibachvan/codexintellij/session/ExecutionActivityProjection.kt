package dev.haibachvan.codexintellij.session

data class ActivityRow(
    val itemId: String,
    val kind: String,
    val summary: String,
    val status: String,
)

object ExecutionActivityProjection {
    fun from(state: NormalizedServerState, threadId: ThreadId?): List<ActivityRow> =
        state.items.values
            .filter { threadId == null || it.threadId == threadId }
            .map { item ->
                when (item) {
                    is ItemFact.Command -> ActivityRow(item.id.value, "command", item.command, item.status.name)
                    is ItemFact.Patch -> ActivityRow(item.id.value, "patch", item.fact.path, item.status.name)
                    is ItemFact.ApprovalReference -> ActivityRow(item.id.value, "approval", item.requestId, item.status.name)
                    is ItemFact.Subagent -> ActivityRow(item.id.value, "agent", item.fact.agentId, item.status.name)
                    else -> ActivityRow(item.id.value, item::class.simpleName ?: "item", item.status.name, item.status.name)
                }
            }
}
