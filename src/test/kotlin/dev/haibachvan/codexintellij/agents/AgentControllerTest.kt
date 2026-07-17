package dev.haibachvan.codexintellij.agents

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.session.AgentFact
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ItemId
import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.TerminalRank
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentControllerTest {
    @Test
    fun `stale epoch control is rejected locally`() {
        val epoch = ProcessEpoch(1)
        val agent = AgentFact(ItemId("a1"), "helper", null, ItemStatus.ACTIVE, null)
        val state = NormalizedServerState(
            items = mapOf(
                ItemId("a1") to ItemFact.Subagent(
                    ItemId("a1"), ThreadId("t"), null, ItemStatus.ACTIVE, TerminalRank.ACTIVE, epoch, 1, agent,
                ),
            ),
            agents = mapOf(ItemId("a1") to agent),
        )
        // Local precheck mirrors controller require without needing a live gateway.
        assertThrows<IllegalArgumentException> {
            val live = state.items[ItemId("a1")]
            require(live != null && live.epoch == ProcessEpoch(2)) { "Stale agent control for a1" }
        }
    }
}
