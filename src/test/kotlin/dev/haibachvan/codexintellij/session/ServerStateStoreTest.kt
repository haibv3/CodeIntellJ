package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerStateStoreTest {
    @Test
    fun `replaceThreadHistory overwrites stale items for reopen`() {
        val store = ServerStateStore()
        val epoch = ProcessEpoch(1)
        val thread = ThreadId("t1")
        store.applySnapshot(
            SnapshotEnvelope(
                epoch = epoch,
                requestWatermark = 50L,
                threads = listOf(ThreadFact(thread, ThreadStatus.ACTIVE, "Old", epoch, 1)),
                items = listOf(
                    ItemFact.UserMessage(
                        ItemId("old"),
                        thread,
                        TurnId("u0"),
                        ItemStatus.COMPLETED,
                        TerminalRank.COMPLETED,
                        epoch,
                        1,
                        "stale",
                    ),
                ),
            ),
        )
        // Simulate live watermark advancing so a watermark=0 resume would be "stale".
        store.replace(store.snapshot().copy(lastEventEpoch = epoch, lastRequestWatermark = 99L))

        store.replaceThreadHistory(
            thread,
            SnapshotEnvelope(
                epoch = epoch,
                requestWatermark = 0L,
                threads = listOf(ThreadFact(thread, ThreadStatus.ACTIVE, "Fresh", epoch, 2)),
                items = listOf(
                    ItemFact.UserMessage(
                        ItemId("new"),
                        thread,
                        TurnId("u1"),
                        ItemStatus.COMPLETED,
                        TerminalRank.COMPLETED,
                        epoch,
                        2,
                        "hello",
                    ),
                    ItemFact.AgentMessage(
                        ItemId("a1"),
                        thread,
                        TurnId("u1"),
                        ItemStatus.COMPLETED,
                        TerminalRank.COMPLETED,
                        epoch,
                        3,
                        "world",
                    ),
                ),
            ),
        )

        val snap = store.snapshot()
        assertEquals("Fresh", snap.threads[thread]?.title)
        assertEquals(2, snap.items.values.count { it.threadId == thread })
        assertTrue(snap.items.keys.none { it.value == "old" })
        assertEquals("hello", (snap.items[ItemId("new")] as ItemFact.UserMessage).text)
    }

    @Test
    fun `replaceThreadHistory keeps existing items when envelope is empty`() {
        val store = ServerStateStore()
        val epoch = ProcessEpoch(1)
        val thread = ThreadId("t1")
        store.replaceThreadHistory(
            thread,
            SnapshotEnvelope(
                epoch = epoch,
                requestWatermark = 0L,
                threads = listOf(ThreadFact(thread, ThreadStatus.ACTIVE, "Keep", epoch, 1)),
                items = listOf(
                    ItemFact.UserMessage(
                        ItemId("u1"),
                        thread,
                        TurnId("turn1"),
                        ItemStatus.COMPLETED,
                        TerminalRank.COMPLETED,
                        epoch,
                        1,
                        "cached",
                    ),
                ),
            ),
        )
        store.replaceThreadHistory(
            thread,
            SnapshotEnvelope(
                epoch = epoch,
                requestWatermark = 0L,
                threads = listOf(ThreadFact(thread, ThreadStatus.ACTIVE, "Meta only", epoch, 2)),
            ),
        )
        val snap = store.snapshot()
        assertEquals("Meta only", snap.threads[thread]?.title)
        assertEquals(1, snap.items.size)
        assertEquals("cached", (snap.items[ItemId("u1")] as ItemFact.UserMessage).text)
    }

    @Test
    fun `replaceThreadHistory keeps items when turns arrive without items`() {
        val store = ServerStateStore()
        val epoch = ProcessEpoch(1)
        val thread = ThreadId("t1")
        store.replaceThreadHistory(
            thread,
            SnapshotEnvelope(
                epoch = epoch,
                requestWatermark = 0L,
                threads = listOf(ThreadFact(thread, ThreadStatus.ACTIVE, "Keep", epoch, 1)),
                items = listOf(
                    ItemFact.UserMessage(
                        ItemId("u1"),
                        thread,
                        TurnId("turn1"),
                        ItemStatus.COMPLETED,
                        TerminalRank.COMPLETED,
                        epoch,
                        1,
                        "cached",
                    ),
                ),
            ),
        )
        // Simulate resume with turns but itemsView=notLoaded (empty items).
        store.replaceThreadHistory(
            thread,
            SnapshotEnvelope(
                epoch = epoch,
                requestWatermark = 0L,
                threads = listOf(ThreadFact(thread, ThreadStatus.ACTIVE, "Resumed", epoch, 2)),
                turns = listOf(
                    TurnFact(
                        TurnId("turn1"),
                        thread,
                        TurnStatus.COMPLETED,
                        TerminalRank.COMPLETED,
                        epoch,
                        2,
                    ),
                ),
            ),
        )
        val snap = store.snapshot()
        assertEquals("Resumed", snap.threads[thread]?.title)
        assertEquals(1, snap.items.size)
        assertEquals("cached", (snap.items[ItemId("u1")] as ItemFact.UserMessage).text)
    }
}
