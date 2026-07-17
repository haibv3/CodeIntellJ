package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch

enum class ThreadStatus { ACTIVE, ARCHIVED, UNKNOWN }
enum class TurnStatus { ACTIVE, COMPLETED, FAILED, INTERRUPTED, UNKNOWN }
enum class ItemStatus { STARTED, ACTIVE, INTERRUPTED, COMPLETED, FAILED, UNKNOWN }
enum class TerminalRank { NONE, ACTIVE, INTERRUPTED, FAILED, COMPLETED }

data class ThreadId(val value: String)
data class TurnId(val value: String)
data class ItemId(val value: String)

data class PatchFact(
    val itemId: ItemId,
    val path: String,
    val unifiedDiff: String?,
    val status: ItemStatus,
    val changes: List<FileChangeEntry> = emptyList(),
)

data class AgentFact(
    val itemId: ItemId,
    val agentId: String,
    val parentItemId: ItemId?,
    val status: ItemStatus,
    val summary: String?,
)

sealed class ItemFact {
    abstract val id: ItemId
    abstract val threadId: ThreadId
    abstract val turnId: TurnId?
    abstract val status: ItemStatus
    abstract val terminalRank: TerminalRank
    abstract val epoch: ProcessEpoch
    abstract val arrivalSeq: Long

    data class UserMessage(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val text: String,
    ) : ItemFact()

    data class AgentMessage(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val text: String,
    ) : ItemFact()

    data class Reasoning(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val text: String,
    ) : ItemFact()

    data class Command(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val command: String,
        val output: String,
    ) : ItemFact()

    data class Patch(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val fact: PatchFact,
    ) : ItemFact()

    data class Subagent(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val fact: AgentFact,
    ) : ItemFact()

    data class ApprovalReference(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val requestId: String,
    ) : ItemFact()

    data class Unknown(
        override val id: ItemId,
        override val threadId: ThreadId,
        override val turnId: TurnId?,
        override val status: ItemStatus,
        override val terminalRank: TerminalRank,
        override val epoch: ProcessEpoch,
        override val arrivalSeq: Long,
        val type: String,
        val raw: JsonObject,
    ) : ItemFact()
}

data class TurnFact(
    val id: TurnId,
    val threadId: ThreadId,
    val status: TurnStatus,
    val terminalRank: TerminalRank,
    val epoch: ProcessEpoch,
    val arrivalSeq: Long,
    val tokenUsage: TokenUsage? = null,
)

data class ThreadFact(
    val id: ThreadId,
    val status: ThreadStatus,
    val title: String?,
    val epoch: ProcessEpoch,
    val arrivalSeq: Long,
)

data class TokenUsage(
    val input: Long?,
    val output: Long?,
)

/** Latest thread context usage from `thread/tokenUsage/updated`. */
data class ThreadTokenUsage(
    val totalTokens: Long,
    val lastTokens: Long,
    val modelContextWindow: Long?,
    val turnId: TurnId?,
)

data class NormalizedServerState(
    val threads: Map<ThreadId, ThreadFact> = emptyMap(),
    val turns: Map<TurnId, TurnFact> = emptyMap(),
    val items: Map<ItemId, ItemFact> = emptyMap(),
    val patches: Map<ItemId, PatchFact> = emptyMap(),
    val agents: Map<ItemId, AgentFact> = emptyMap(),
    val threadTokenUsage: Map<ThreadId, ThreadTokenUsage> = emptyMap(),
    val lastEventEpoch: ProcessEpoch? = null,
    val lastArrivalSeq: Long = 0L,
    val lastRequestWatermark: Long = 0L,
)

data class SnapshotEnvelope(
    val epoch: ProcessEpoch,
    val requestWatermark: Long,
    val threads: List<ThreadFact> = emptyList(),
    val turns: List<TurnFact> = emptyList(),
    val items: List<ItemFact> = emptyList(),
    val patches: List<PatchFact> = emptyList(),
    val agents: List<AgentFact> = emptyList(),
)
