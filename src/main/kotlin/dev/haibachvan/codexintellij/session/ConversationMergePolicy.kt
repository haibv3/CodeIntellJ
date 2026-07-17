package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.appserver.ProcessEpoch

/**
 * Snapshot/live merge rules: post-watermark live facts win; stale snapshots fill missing history only;
 * terminal rank never regresses.
 */
class ConversationMergePolicy {
    fun mergeSnapshot(state: NormalizedServerState, snapshot: SnapshotEnvelope): NormalizedServerState {
        var next = state
        for (thread in snapshot.threads) {
            next = mergeThread(next, thread, snapshot)
        }
        for (turn in snapshot.turns) {
            next = mergeTurn(next, turn, snapshot)
        }
        for (item in snapshot.items) {
            next = mergeItem(next, item, snapshot)
        }
        for (patch in snapshot.patches) {
            if (next.patches[patch.itemId] == null) {
                next = next.copy(patches = next.patches + (patch.itemId to patch))
            }
        }
        for (agent in snapshot.agents) {
            if (next.agents[agent.itemId] == null) {
                next = next.copy(agents = next.agents + (agent.itemId to agent))
            }
        }
        return next
    }

    fun canApplyLiveEvent(
        state: NormalizedServerState,
        epoch: ProcessEpoch,
        arrivalSeq: Long,
    ): Boolean {
        val lastEpoch = state.lastEventEpoch ?: return true
        if (epoch.value < lastEpoch.value) {
            return false
        }
        if (epoch == lastEpoch && arrivalSeq < state.lastArrivalSeq) {
            return false
        }
        return true
    }

    fun terminalRankOf(status: ItemStatus): TerminalRank =
        when (status) {
            ItemStatus.STARTED, ItemStatus.ACTIVE -> TerminalRank.ACTIVE
            ItemStatus.INTERRUPTED -> TerminalRank.INTERRUPTED
            ItemStatus.FAILED -> TerminalRank.FAILED
            ItemStatus.COMPLETED -> TerminalRank.COMPLETED
            ItemStatus.UNKNOWN -> TerminalRank.NONE
        }

    fun maxRank(a: TerminalRank, b: TerminalRank): TerminalRank =
        if (a.ordinal >= b.ordinal) a else b

    private fun mergeThread(
        state: NormalizedServerState,
        incoming: ThreadFact,
        snapshot: SnapshotEnvelope,
    ): NormalizedServerState {
        val existing = state.threads[incoming.id]
        if (existing == null) {
            return state.copy(threads = state.threads + (incoming.id to incoming))
        }
        if (isStaleSnapshot(state, snapshot)) {
            return state
        }
        return state.copy(threads = state.threads + (incoming.id to incoming))
    }

    private fun mergeTurn(
        state: NormalizedServerState,
        incoming: TurnFact,
        snapshot: SnapshotEnvelope,
    ): NormalizedServerState {
        val existing = state.turns[incoming.id]
            ?: return state.copy(turns = state.turns + (incoming.id to incoming))
        if (isStaleSnapshot(state, snapshot)) {
            return state
        }
        val rank = maxRank(existing.terminalRank, incoming.terminalRank)
        val merged = if (existing.terminalRank >= TerminalRank.INTERRUPTED &&
            incoming.terminalRank < existing.terminalRank
        ) {
            existing
        } else {
            incoming.copy(terminalRank = rank)
        }
        return state.copy(turns = state.turns + (incoming.id to merged))
    }

    private fun mergeItem(
        state: NormalizedServerState,
        incoming: ItemFact,
        snapshot: SnapshotEnvelope,
    ): NormalizedServerState {
        val existing = state.items[incoming.id]
        if (existing == null) {
            return state.copy(items = state.items + (incoming.id to incoming))
        }
        if (isStaleSnapshot(state, snapshot)) {
            return state
        }
        if (existing.terminalRank >= TerminalRank.INTERRUPTED &&
            incoming.terminalRank < existing.terminalRank
        ) {
            return state
        }
        return state.copy(items = state.items + (incoming.id to incoming))
    }

    private fun isStaleSnapshot(state: NormalizedServerState, snapshot: SnapshotEnvelope): Boolean {
        val lastEpoch = state.lastEventEpoch ?: return false
        if (snapshot.epoch.value < lastEpoch.value) {
            return true
        }
        return snapshot.epoch == lastEpoch && snapshot.requestWatermark < state.lastRequestWatermark
    }
}
