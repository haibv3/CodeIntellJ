package dev.haibachvan.codexintellij.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope

/**
 * Sole writer of normalized app-server facts. Pure function over [NormalizedServerState].
 * Accepts both flat fixture shapes and real 0.144.5 v2 nested `item` / `turn` / `thread` payloads.
 */
class ConversationReducer(
    private val mergePolicy: ConversationMergePolicy = ConversationMergePolicy(),
) {
    fun reduce(state: NormalizedServerState, event: SequencedEvent): NormalizedServerState {
        if (!mergePolicy.canApplyLiveEvent(state, event.epoch, event.arrivalSeq)) {
            return state
        }
        val stamped = state.copy(
            lastEventEpoch = event.epoch,
            lastArrivalSeq = event.arrivalSeq,
            lastRequestWatermark = maxOf(state.lastRequestWatermark, event.requestWatermark),
        )
        return when (val payload = event.payload) {
            is WireEnvelope.Notification -> reduceNotification(stamped, event, payload)
            is WireEnvelope.Response -> stamped
            is WireEnvelope.ServerRequest -> reduceServerRequest(stamped, event, payload)
            is WireEnvelope.Unknown -> stamped
            is WireEnvelope.Request -> stamped
        }
    }

    fun applySnapshot(state: NormalizedServerState, snapshot: SnapshotEnvelope): NormalizedServerState =
        mergePolicy.mergeSnapshot(state, snapshot)

    private fun reduceNotification(
        state: NormalizedServerState,
        event: SequencedEvent,
        notification: WireEnvelope.Notification,
    ): NormalizedServerState {
        val params = notification.params ?: JsonObject()
        val method = notification.method
        return when {
            method == "thread/started" || method == "thread/updated" -> {
                val id = ThreadId(threadIdOf(params) ?: return state)
                val existing = state.threads[id]
                val incomingTitle = params.string("title")
                    ?: params.getAsJsonObject("thread")?.string("title")
                val title = incomingTitle?.takeIf { it.isNotBlank() } ?: existing?.title
                val fact = ThreadFact(
                    id = id,
                    status = ThreadStatus.ACTIVE,
                    title = title,
                    epoch = event.epoch,
                    arrivalSeq = event.arrivalSeq,
                )
                state.copy(threads = state.threads + (id to fact))
            }
            method == "thread/archived" -> {
                val id = ThreadId(threadIdOf(params) ?: return state)
                val existing = state.threads[id]
                val fact = (existing ?: ThreadFact(id, ThreadStatus.ARCHIVED, null, event.epoch, event.arrivalSeq))
                    .copy(status = ThreadStatus.ARCHIVED, epoch = event.epoch, arrivalSeq = event.arrivalSeq)
                state.copy(threads = state.threads + (id to fact))
            }
            method == "thread/deleted" || method == "thread/closed" -> {
                val id = ThreadId(threadIdOf(params) ?: return state)
                purgeThread(state, id)
            }
            method == "thread/tokenUsage/updated" -> reduceTokenUsage(state, event, params)
            method.startsWith("turn/") -> reduceTurn(state, event, method, params)
            // Deltas must win over generic item/* routing (e.g. item/agentMessage/delta).
            method.contains("delta", ignoreCase = true) ||
                event.kind == SequencedEventKind.TEXT_DELTA ||
                event.kind == SequencedEventKind.OUTPUT_DELTA ->
                appendDelta(state, event, params)
            method.contains("item") -> reduceItem(state, event, method, params)
            else -> state
        }
    }

    private fun reduceTokenUsage(
        state: NormalizedServerState,
        event: SequencedEvent,
        params: JsonObject,
    ): NormalizedServerState {
        val threadId = ThreadId(threadIdOf(params) ?: return state)
        val usageObj = params.getAsJsonObject("tokenUsage") ?: return state
        val total = usageObj.getAsJsonObject("total") ?: return state
        val last = usageObj.getAsJsonObject("last")
        val totalTokens = total.get("totalTokens")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: return state
        val lastTokens = last?.get("totalTokens")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val window = usageObj.get("modelContextWindow")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong
        val turnId = turnIdOf(params)?.let { TurnId(it) }
        val fact = ThreadTokenUsage(
            totalTokens = totalTokens,
            lastTokens = lastTokens,
            modelContextWindow = window,
            turnId = turnId,
        )
        val turnUpdate = turnId?.let { tid ->
            val existing = state.turns[tid]
            if (existing != null) {
                existing.copy(
                    tokenUsage = TokenUsage(
                        input = total.get("inputTokens")?.takeIf { it.isJsonPrimitive }?.asLong,
                        output = total.get("outputTokens")?.takeIf { it.isJsonPrimitive }?.asLong,
                    ),
                    epoch = event.epoch,
                    arrivalSeq = event.arrivalSeq,
                ).let { tid to it }
            } else {
                null
            }
        }
        return state.copy(
            threadTokenUsage = state.threadTokenUsage + (threadId to fact),
            turns = if (turnUpdate != null) state.turns + turnUpdate else state.turns,
        )
    }

    private fun reduceTurn(
        state: NormalizedServerState,
        event: SequencedEvent,
        method: String,
        params: JsonObject,
    ): NormalizedServerState {
        val turnId = TurnId(turnIdOf(params) ?: return state)
        val threadId = ThreadId(threadIdOf(params) ?: return state)
        val nestedStatus = params.getAsJsonObject("turn")?.string("status")
        val status = when {
            method.endsWith("/completed") ||
                nestedStatus.equals("completed", ignoreCase = true) -> TurnStatus.COMPLETED
            method.endsWith("/failed") ||
                nestedStatus.equals("failed", ignoreCase = true) ||
                nestedStatus.equals("error", ignoreCase = true) -> TurnStatus.FAILED
            method.endsWith("/interrupted") ||
                method.endsWith("/cancelled") ||
                nestedStatus.equals("interrupted", ignoreCase = true) ||
                nestedStatus.equals("cancelled", ignoreCase = true) -> TurnStatus.INTERRUPTED
            else -> TurnStatus.ACTIVE
        }
        val rank = when (status) {
            TurnStatus.ACTIVE -> TerminalRank.ACTIVE
            TurnStatus.INTERRUPTED -> TerminalRank.INTERRUPTED
            TurnStatus.FAILED -> TerminalRank.FAILED
            TurnStatus.COMPLETED -> TerminalRank.COMPLETED
            TurnStatus.UNKNOWN -> TerminalRank.NONE
        }
        val existing = state.turns[turnId]
        if (existing != null && existing.terminalRank > rank) {
            return state
        }
        val fact = TurnFact(
            id = turnId,
            threadId = threadId,
            status = status,
            terminalRank = rank,
            epoch = event.epoch,
            arrivalSeq = event.arrivalSeq,
        )
        return state.copy(turns = state.turns + (turnId to fact))
    }

    private fun reduceItem(
        state: NormalizedServerState,
        event: SequencedEvent,
        method: String,
        params: JsonObject,
    ): NormalizedServerState {
        val nested = params.getAsJsonObject("item")
        val itemId = ItemId(itemIdOf(params, nested) ?: return state)
        val threadId = ThreadId(threadIdOf(params) ?: "unknown")
        val turnId = turnIdOf(params)?.let(::TurnId)
        val status = when {
            method.endsWith("/completed") || method.endsWith("/done") -> ItemStatus.COMPLETED
            method.endsWith("/failed") -> ItemStatus.FAILED
            method.endsWith("/interrupted") -> ItemStatus.INTERRUPTED
            method.endsWith("/started") -> ItemStatus.STARTED
            else -> ItemStatus.ACTIVE
        }
        val rank = mergePolicy.terminalRankOf(status)
        val existing = state.items[itemId]
        if (existing != null && existing.terminalRank > rank) {
            return state
        }
        val type = params.string("type")
            ?: nested?.string("type")
            ?: typeHintFromMethod(method)
            ?: "unknown"
        val text = params.string("text")
            ?: nested?.string("text")
            ?: extractUserMessageText(nested)
            ?: existingText(existing)
            ?: ""
        val item: ItemFact = when (type) {
            "user", "userMessage" -> ItemFact.UserMessage(itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq, text)
            "agent", "agentMessage", "assistant" -> ItemFact.AgentMessage(itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq, text)
            "reasoning" -> ItemFact.Reasoning(
                itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq,
                text.ifBlank { nested?.string("summary") ?: "" },
            )
            "command", "commandExecution" -> ItemFact.Command(
                itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq,
                command = params.string("command")
                    ?: nested?.string("command")
                    ?: nested?.getAsJsonArray("commandActions")?.let { "" }
                    ?: "",
                output = text,
            )
            "patch", "fileChange" -> {
                val changes = parseFileChangeEntries(params, nested)
                val path = changes.firstOrNull()?.path
                    ?: params.string("path")
                    ?: nested?.string("path")
                    ?: ""
                val diff = when {
                    changes.size == 1 -> changes[0].unifiedDiff
                    else -> params.string("diff") ?: nested?.string("diff")
                }
                val patch = PatchFact(itemId, path, diff, status, changes)
                ItemFact.Patch(itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq, patch)
            }
            "subagent", "subAgentActivity", "collaboration", "collabAgentToolCall" -> {
                val agent = parseAgentFact(itemId, status, text, params, nested)
                ItemFact.Subagent(itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq, agent)
            }
            "approval" -> ItemFact.ApprovalReference(
                itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq,
                requestId = params.string("requestId") ?: itemId.value,
            )
            else -> ItemFact.Unknown(itemId, threadId, turnId, status, rank, event.epoch, event.arrivalSeq, type, params)
        }
        var next = state.copy(items = state.items + (itemId to item))
        when (item) {
            is ItemFact.Patch -> next = next.copy(patches = next.patches + (itemId to item.fact))
            is ItemFact.Subagent -> {
                var agents = next.agents + (itemId to item.fact)
                agents = mergeCollabAgentStates(agents, itemId, params, nested, item.status)
                next = next.copy(agents = agents)
            }
            else -> Unit
        }
        return next
    }

    private fun parseAgentFact(
        itemId: ItemId,
        status: ItemStatus,
        text: String,
        params: JsonObject,
        nested: JsonObject?,
    ): AgentFact {
        val type = params.string("type") ?: nested?.string("type").orEmpty()
        if (type == "subAgentActivity") {
            val agentPath = params.string("agentPath") ?: nested?.string("agentPath").orEmpty()
            val agentThreadId = params.string("agentThreadId")
                ?: nested?.string("agentThreadId")
                ?: itemId.value
            val kind = params.string("kind") ?: nested?.string("kind").orEmpty()
            val name = shortAgentLabel(agentPath).ifBlank { shortAgentLabel(agentThreadId) }
            val kindStatus = when (kind) {
                "started" -> ItemStatus.STARTED
                "interrupted" -> ItemStatus.INTERRUPTED
                "interacted" -> ItemStatus.ACTIVE
                else -> status
            }
            return AgentFact(
                itemId = itemId,
                agentId = name.ifBlank { itemId.value },
                parentItemId = (params.string("parentItemId") ?: nested?.string("parentItemId"))?.let(::ItemId),
                status = if (status == ItemStatus.COMPLETED || status == ItemStatus.FAILED) status else kindStatus,
                summary = buildString {
                    if (kind.isNotBlank()) append(kind)
                    if (agentPath.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(agentPath)
                    }
                    if (isEmpty() && text.isNotBlank()) append(text)
                }.ifBlank { null },
            )
        }
        val tool = params.string("tool") ?: nested?.string("tool")
        val prompt = params.string("prompt") ?: nested?.string("prompt")
        val receivers = (nested?.getAsJsonArray("receiverThreadIds") ?: params.getAsJsonArray("receiverThreadIds"))
            ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
            .orEmpty()
        val agentId = params.string("agentId")
            ?: nested?.string("agentId")
            ?: tool
            ?: receivers.firstOrNull()?.let { shortAgentLabel(it) }
            ?: itemId.value
        val summary = prompt?.take(160)
            ?: text.takeIf { it.isNotBlank() }
            ?: tool?.let { "Collab · $it" }
        return AgentFact(
            itemId = itemId,
            agentId = agentId,
            parentItemId = (params.string("parentItemId") ?: nested?.string("parentItemId"))?.let(::ItemId),
            status = status,
            summary = summary,
        )
    }

    private fun mergeCollabAgentStates(
        agents: Map<ItemId, AgentFact>,
        parentItemId: ItemId,
        params: JsonObject,
        nested: JsonObject?,
        fallbackStatus: ItemStatus,
    ): Map<ItemId, AgentFact> {
        var result = agents
        val states = nested?.getAsJsonObject("agentsStates")
            ?: params.getAsJsonObject("agentsStates")
        if (states != null) {
            for ((threadId, el) in states.entrySet()) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = ItemId("agent:$threadId")
                result = result + (id to AgentFact(
                    itemId = id,
                    agentId = shortAgentLabel(threadId),
                    parentItemId = parentItemId,
                    status = mapCollabAgentStatus(obj.string("status")) ?: fallbackStatus,
                    summary = obj.string("message"),
                ))
            }
        }
        val receivers = nested?.getAsJsonArray("receiverThreadIds")
            ?: params.getAsJsonArray("receiverThreadIds")
        receivers?.forEach { el ->
            if (!el.isJsonPrimitive) return@forEach
            val threadId = el.asString
            val id = ItemId("agent:$threadId")
            if (id !in result) {
                result = result + (id to AgentFact(
                    itemId = id,
                    agentId = shortAgentLabel(threadId),
                    parentItemId = parentItemId,
                    status = fallbackStatus,
                    summary = null,
                ))
            }
        }
        return result
    }

    private fun mapCollabAgentStatus(raw: String?): ItemStatus? =
        when (raw?.lowercase()) {
            "pendinginit", "running" -> ItemStatus.ACTIVE
            "completed", "shutdown" -> ItemStatus.COMPLETED
            "interrupted" -> ItemStatus.INTERRUPTED
            "errored", "notfound" -> ItemStatus.FAILED
            else -> null
        }

    private fun shortAgentLabel(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val leaf = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return leaf.ifBlank { trimmed.take(16) }
    }

    private fun appendDelta(
        state: NormalizedServerState,
        event: SequencedEvent,
        params: JsonObject,
    ): NormalizedServerState {
        val itemId = ItemId(itemIdOf(params, params.getAsJsonObject("item")) ?: return state)
        val delta = params.string("delta")
            ?: params.string("text")
            ?: params.getAsJsonObject("delta")?.string("text")
            ?: return state
        val existing = state.items[itemId]
        if (existing == null) {
            // Synthesize an agent message so streaming works before item/started arrives/out-of-order.
            val threadId = ThreadId(threadIdOf(params) ?: "unknown")
            val turnId = turnIdOf(params)?.let(::TurnId)
            val created = ItemFact.AgentMessage(
                id = itemId,
                threadId = threadId,
                turnId = turnId,
                status = ItemStatus.ACTIVE,
                terminalRank = TerminalRank.ACTIVE,
                epoch = event.epoch,
                arrivalSeq = event.arrivalSeq,
                text = delta,
            )
            return state.copy(items = state.items + (itemId to created))
        }
        if (existing.terminalRank >= TerminalRank.COMPLETED) {
            return state
        }
        val updated = when (existing) {
            is ItemFact.AgentMessage -> existing.copy(
                text = existing.text + delta,
                arrivalSeq = event.arrivalSeq,
                epoch = event.epoch,
                status = ItemStatus.ACTIVE,
                terminalRank = TerminalRank.ACTIVE,
            )
            is ItemFact.Reasoning -> existing.copy(
                text = existing.text + delta,
                arrivalSeq = event.arrivalSeq,
                epoch = event.epoch,
            )
            is ItemFact.Command -> existing.copy(
                output = existing.output + delta,
                arrivalSeq = event.arrivalSeq,
                epoch = event.epoch,
            )
            is ItemFact.Unknown -> ItemFact.AgentMessage(
                id = existing.id,
                threadId = existing.threadId,
                turnId = existing.turnId,
                status = ItemStatus.ACTIVE,
                terminalRank = TerminalRank.ACTIVE,
                epoch = event.epoch,
                arrivalSeq = event.arrivalSeq,
                text = delta,
            )
            else -> existing
        }
        return state.copy(items = state.items + (itemId to updated))
    }

    private fun reduceServerRequest(
        state: NormalizedServerState,
        event: SequencedEvent,
        request: WireEnvelope.ServerRequest,
    ): NormalizedServerState {
        val itemId = ItemId("approval-${request.id}")
        val threadId = ThreadId(request.params?.let { threadIdOf(it) } ?: "unknown")
        val item = ItemFact.ApprovalReference(
            id = itemId,
            threadId = threadId,
            turnId = request.params?.let { turnIdOf(it) }?.let(::TurnId),
            status = ItemStatus.ACTIVE,
            terminalRank = TerminalRank.ACTIVE,
            epoch = event.epoch,
            arrivalSeq = event.arrivalSeq,
            requestId = request.id,
        )
        return state.copy(items = state.items + (itemId to item))
    }

    private fun existingText(item: ItemFact?): String? =
        when (item) {
            is ItemFact.AgentMessage -> item.text
            is ItemFact.UserMessage -> item.text
            is ItemFact.Reasoning -> item.text
            is ItemFact.Command -> item.output
            else -> null
        }

    companion object {
        fun purgeThread(state: NormalizedServerState, threadId: ThreadId): NormalizedServerState {
            val itemIds = state.items.filterValues { it.threadId == threadId }.keys
            return state.copy(
                threads = state.threads - threadId,
                turns = state.turns.filterValues { it.threadId != threadId },
                items = state.items.filterKeys { it !in itemIds },
                patches = state.patches.filterKeys { it !in itemIds },
                agents = state.agents.filterKeys { it !in itemIds },
                threadTokenUsage = state.threadTokenUsage - threadId,
            )
        }
    }
}

private fun threadIdOf(params: JsonObject): String? =
    params.string("threadId")
        ?: params.getAsJsonObject("thread")?.string("id")

private fun turnIdOf(params: JsonObject): String? =
    params.string("turnId")
        ?: params.getAsJsonObject("turn")?.string("id")

private fun itemIdOf(params: JsonObject, nested: JsonObject?): String? =
    params.string("itemId")
        ?: nested?.string("id")
        ?: params.getAsJsonObject("item")?.string("id")

private fun typeHintFromMethod(method: String): String? =
    when {
        method.contains("agentMessage", ignoreCase = true) -> "agentMessage"
        method.contains("reasoning", ignoreCase = true) -> "reasoning"
        method.contains("commandExecution", ignoreCase = true) -> "commandExecution"
        method.contains("fileChange", ignoreCase = true) -> "fileChange"
        method.contains("userMessage", ignoreCase = true) -> "userMessage"
        else -> null
    }

private fun extractUserMessageText(item: JsonObject?): String? {
    if (item == null) return null
    val content = item.get("content")
    if (content !is JsonArray) return null
    return buildString {
        content.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val obj = el.asJsonObject
            when (obj.string("type")) {
                "text" -> obj.string("text")?.let { append(it) }
                else -> obj.string("text")?.let { append(it) }
            }
        }
    }.takeIf { it.isNotBlank() }
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
