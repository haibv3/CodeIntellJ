package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.session.AgentFact
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ItemId
import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.session.TerminalRank
import dev.haibachvan.codexintellij.session.ThreadFact
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.ThreadStatus
import java.util.ArrayDeque
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiStateBridgeTest {
    @Test
    fun `5000 offers keep at most one scheduled EDT delivery and drain latest state`() {
        val fixture = fixture()

        repeat(5_000) { index ->
            fixture.bridge.offer(state(index + 1L))
        }

        assertEquals(1, fixture.scheduler.pendingCount)
        assertEquals(1, fixture.bridge.metrics().pendingHighWater)
        fixture.scheduler.runUntilIdle()
        assertEquals(5_000L, fixture.deliveries.last().state.lastArrivalSeq)
        assertEquals(5_000L, fixture.bridge.metrics().offered)
        assertEquals(4_999L, fixture.bridge.metrics().merged)
        assertEquals(1L, fixture.bridge.metrics().delivered)
    }

    @Test
    fun `item delta invalidates transcript but not task projection`() {
        val fixture = fixture()
        fixture.bridge.offer(state(1))
        fixture.scheduler.runUntilIdle()
        fixture.deliveries.clear()

        val item = ItemFact.AgentMessage(
            id = ItemId("message"),
            threadId = THREAD_A,
            turnId = null,
            status = ItemStatus.COMPLETED,
            terminalRank = TerminalRank.COMPLETED,
            epoch = ProcessEpoch(1),
            arrivalSeq = 2,
            text = "result",
        )
        fixture.bridge.offer(state(2).copy(items = mapOf(item.id to item)))
        fixture.scheduler.runUntilIdle()

        assertTrue(UiSurface.TRANSCRIPT in fixture.deliveries.single().surfaces)
        assertTrue(UiSurface.TASKS !in fixture.deliveries.single().surfaces)
    }

    @Test
    fun `sequence only delta does not invalidate transcript projection`() {
        val fixture = fixture()
        fixture.bridge.offer(state(1))
        fixture.scheduler.runUntilIdle()
        fixture.deliveries.clear()

        fixture.bridge.offer(state(2))
        fixture.scheduler.runUntilIdle()

        assertTrue(fixture.deliveries.isEmpty())
    }

    @Test
    fun `agent delta invalidates agent and transcript surfaces only`() {
        val fixture = fixture()
        fixture.bridge.offer(state(1))
        fixture.scheduler.runUntilIdle()
        fixture.deliveries.clear()

        val itemId = ItemId("agent")
        val agent = AgentFact(itemId, "worker", null, ItemStatus.ACTIVE, "running")
        fixture.bridge.offer(state(2).copy(agents = mapOf(itemId to agent)))
        fixture.scheduler.runUntilIdle()

        assertEquals(
            setOf(UiSurface.TRANSCRIPT, UiSurface.AGENTS),
            fixture.deliveries.flatMapTo(mutableSetOf()) { it.surfaces },
        )
    }

    @Test
    fun `selected thread change can force title agent and transcript refresh`() {
        var selected = THREAD_A
        val fixture = fixture(selectedThread = { selected })
        val snapshot = state(1).copy(
            threads = mapOf(
                THREAD_A to thread(THREAD_A, 1),
                THREAD_B to thread(THREAD_B, 1),
            ),
        )
        fixture.bridge.offer(snapshot)
        fixture.scheduler.runUntilIdle()
        fixture.deliveries.clear()

        selected = THREAD_B
        val forced = setOf(UiSurface.TITLE, UiSurface.AGENTS, UiSurface.TRANSCRIPT)
        fixture.bridge.offer(snapshot, force = forced)
        fixture.scheduler.runUntilIdle()

        assertEquals(forced, fixture.deliveries.flatMapTo(mutableSetOf()) { it.surfaces })
    }

    @Test
    fun `dispose removes store listener and suppresses late callback`() {
        val fixture = fixture()
        fixture.bridge.offer(state(1))

        fixture.bridge.dispose()
        fixture.scheduler.runUntilIdle()
        fixture.store.replace(state(2))

        assertTrue(fixture.deliveries.isEmpty())
        assertEquals(0, fixture.scheduler.pendingCount)
    }

    private fun fixture(selectedThread: () -> ThreadId? = { THREAD_A }): Fixture {
        val store = ServerStateStore()
        val scheduler = FakeScheduler()
        val deliveries = mutableListOf<UiStateDelivery>()
        val bridge = UiStateBridge(
            store = store,
            selectedThread = selectedThread,
            activeTurnId = { null },
            scheduler = scheduler,
            clock = scheduler,
            onDelivery = deliveries::add,
        )
        return Fixture(store, scheduler, bridge, deliveries)
    }

    private fun state(seq: Long): NormalizedServerState = NormalizedServerState(
        threads = mapOf(THREAD_A to thread(THREAD_A, 1)),
        lastArrivalSeq = seq,
    )

    private fun thread(id: ThreadId, seq: Long) = ThreadFact(
        id = id,
        status = ThreadStatus.ACTIVE,
        title = id.value,
        epoch = ProcessEpoch(1),
        arrivalSeq = seq,
    )

    private data class Fixture(
        val store: ServerStateStore,
        val scheduler: FakeScheduler,
        val bridge: UiStateBridge,
        val deliveries: MutableList<UiStateDelivery>,
    )

    private class FakeScheduler : UiStateScheduler, UiStateClock {
        private data class Scheduled(val dueMs: Long, val task: () -> Unit)

        private val tasks = ArrayDeque<Scheduled>()
        private var now = 0L
        val pendingCount: Int get() = tasks.size

        override fun schedule(delayMs: Int, task: () -> Unit) {
            tasks += Scheduled(now + delayMs, task)
        }

        override fun nowMs(): Long = now

        fun runUntilIdle() {
            while (tasks.isNotEmpty()) {
                val next = tasks.removeFirst()
                now = next.dueMs
                next.task()
            }
        }
    }

    private companion object {
        val THREAD_A = ThreadId("thread-a")
        val THREAD_B = ThreadId("thread-b")
    }
}
