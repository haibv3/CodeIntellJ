package dev.haibachvan.codexintellij.agents

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.session.AgentFact
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ItemId
import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.TerminalRank
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentTreeProjectionTest {
    @Test
    fun `builds parent child tree from normalized agent facts`() {
        val epoch = ProcessEpoch(1)
        val parent = AgentFact(ItemId("p"), "parent", null, ItemStatus.ACTIVE, "root")
        val child = AgentFact(ItemId("c"), "child", ItemId("p"), ItemStatus.ACTIVE, "leaf")
        val state = NormalizedServerState(
            items = mapOf(
                ItemId("p") to ItemFact.Subagent(ItemId("p"), ThreadId("t"), null, ItemStatus.ACTIVE, TerminalRank.ACTIVE, epoch, 1, parent),
                ItemId("c") to ItemFact.Subagent(ItemId("c"), ThreadId("t"), null, ItemStatus.ACTIVE, TerminalRank.ACTIVE, epoch, 2, child),
            ),
            agents = mapOf(ItemId("p") to parent, ItemId("c") to child),
        )
        val tree = AgentTreeProjection.from(state, ThreadId("t"))
        assertEquals(1, tree.size)
        assertEquals("parent", tree[0].agentId)
        assertEquals(1, tree[0].children.size)
        assertEquals("child", tree[0].children[0].agentId)
    }
}
