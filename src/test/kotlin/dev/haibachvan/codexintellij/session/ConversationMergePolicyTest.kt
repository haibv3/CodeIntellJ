package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationMergePolicyTest {
    private val reducer = ConversationReducer()
    private val policy = ConversationMergePolicy()

    @Test
    fun `live terminal after snapshot watermark is preserved`() {
        val epoch = ProcessEpoch(1)
        var state = NormalizedServerState()
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 10, 5, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "item/completed",
                    JsonObject().apply {
                        addProperty("threadId", "t1")
                        addProperty("itemId", "i1")
                        addProperty("type", "agentMessage")
                        addProperty("text", "live-done")
                    },
                ),
            ),
        )
        val snapshot = SnapshotEnvelope(
            epoch = epoch,
            requestWatermark = 2,
            items = listOf(
                ItemFact.AgentMessage(
                    ItemId("i1"), ThreadId("t1"), null, ItemStatus.ACTIVE, TerminalRank.ACTIVE,
                    epoch, 1, "stale-active",
                ),
                ItemFact.UserMessage(
                    ItemId("i0"), ThreadId("t1"), null, ItemStatus.COMPLETED, TerminalRank.COMPLETED,
                    epoch, 0, "history",
                ),
            ),
        )
        val merged = policy.mergeSnapshot(state, snapshot)
        val live = merged.items.getValue(ItemId("i1")) as ItemFact.AgentMessage
        assertEquals("live-done", live.text)
        assertEquals(ItemStatus.COMPLETED, live.status)
        assertTrue(merged.items.containsKey(ItemId("i0")))
    }

    @Test
    fun `old epoch snapshot cannot regress current state`() {
        val old = ProcessEpoch(1)
        val current = ProcessEpoch(2)
        var state = NormalizedServerState(lastEventEpoch = current, lastArrivalSeq = 5, lastRequestWatermark = 5)
        state = state.copy(
            items = mapOf(
                ItemId("i1") to ItemFact.AgentMessage(
                    ItemId("i1"), ThreadId("t1"), null, ItemStatus.COMPLETED, TerminalRank.COMPLETED,
                    current, 5, "new-epoch",
                ),
            ),
        )
        assertFalse(policy.canApplyLiveEvent(state, old, 99))
        val merged = policy.mergeSnapshot(
            state,
            SnapshotEnvelope(
                epoch = old,
                requestWatermark = 1,
                items = listOf(
                    ItemFact.AgentMessage(
                        ItemId("i1"), ThreadId("t1"), null, ItemStatus.ACTIVE, TerminalRank.ACTIVE,
                        old, 1, "old",
                    ),
                ),
            ),
        )
        assertEquals(ItemStatus.COMPLETED, merged.items.getValue(ItemId("i1")).status)
    }
}
