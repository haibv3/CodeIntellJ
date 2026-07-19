package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.appserver.WireEnvelope
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiAgentUiWorkloadTest {
    @Test
    fun `eight-agent workload has exactly 5000 ordered events`() {
        val events = MultiAgentUiWorkload.events()

        assertEquals(8, MultiAgentUiWorkload.spec.agentCount)
        assertEquals(5_000, events.size)
        assertEquals((1L..5_000L).toList(), events.map { it.arrivalSeq })
        val terminal = events.last().payload as WireEnvelope.Notification
        assertEquals("turn/completed", terminal.method)
    }

    @Test
    fun `workload reduces to expected state and transcript shape`() {
        val state = MultiAgentUiWorkload.reduce()
        val blocks = TranscriptRenderer.renderBlocks(state, ThreadId("perf-thread"))

        assertEquals(8, state.agents.size)
        assertEquals(2_000, state.items.size)
        assertEquals(MultiAgentUiWorkload.spec.transcriptBlockCount, blocks.size)
    }

    @Test
    fun `same seed produces identical event payloads`() {
        val first = MultiAgentUiWorkload.events()
        val second = MultiAgentUiWorkload.events()

        assertEquals(first, second)
        assertTrue(first.all { it.payload is WireEnvelope.Notification })
    }
}
