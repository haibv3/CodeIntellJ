package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationReducerTest {
    private val reducer = ConversationReducer()
    private val epoch = ProcessEpoch(1)

    @Test
    fun `item lifecycle patch and agent facts stay in one reducer`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1", "title" to "Demo")))
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/started",
                obj("threadId" to "t1", "turnId" to "u1", "itemId" to "i1", "type" to "agentMessage", "text" to "Hi"),
            ),
        )
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 3, 1, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/delta",
                    obj("itemId" to "i1", "text" to " there"),
                ),
            ),
        )
        state = reducer.reduce(
            state,
            notification(
                4,
                "item/completed",
                obj("threadId" to "t1", "turnId" to "u1", "itemId" to "i1", "type" to "agentMessage", "text" to "Hi there"),
            ),
        )
        state = reducer.reduce(
            state,
            notification(
                5,
                "item/started",
                obj("threadId" to "t1", "turnId" to "u1", "itemId" to "p1", "type" to "patch", "path" to "A.kt", "diff" to "+x"),
            ),
        )
        state = reducer.reduce(
            state,
            notification(
                6,
                "item/started",
                obj(
                    "threadId" to "t1", "turnId" to "u1", "itemId" to "a1", "type" to "subagent",
                    "agentId" to "helper", "text" to "working",
                ),
            ),
        )

        val agent = state.items[ItemId("i1")] as ItemFact.AgentMessage
        assertEquals("Hi there", agent.text)
        assertEquals(ItemStatus.COMPLETED, agent.status)
        assertTrue(state.patches.containsKey(ItemId("p1")))
        assertTrue(state.agents.containsKey(ItemId("a1")))
    }

    @Test
    fun `subAgentActivity is normalized as Subagent not Unknown`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/completed",
                obj(
                    "threadId" to "t1",
                    "turnId" to "u1",
                    "itemId" to "sa1",
                    "type" to "subAgentActivity",
                    "agentPath" to "explore",
                    "agentThreadId" to "thr-explore-1",
                    "kind" to "started",
                ),
            ),
        )
        val item = state.items[ItemId("sa1")]
        assertTrue(item is ItemFact.Subagent, "got $item")
        val sub = item as ItemFact.Subagent
        assertEquals("explore", sub.fact.agentId)
        assertTrue(state.agents.containsKey(ItemId("sa1")))
    }

    @Test
    fun `collabAgentToolCall merges agentsStates into agent map`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        val agentsStates = JsonObject().apply {
            add("thr-a", obj("status" to "running", "message" to "scanning"))
            add("thr-b", obj("status" to "completed", "message" to "done"))
        }
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/completed",
                obj(
                    "threadId" to "t1",
                    "itemId" to "c1",
                    "type" to "collabAgentToolCall",
                    "tool" to "spawnAgent",
                    "senderThreadId" to "t1",
                    "status" to "completed",
                ).apply {
                    add("receiverThreadIds", com.google.gson.JsonArray().apply {
                        add("thr-a")
                        add("thr-b")
                    })
                    add("agentsStates", agentsStates)
                },
            ),
        )
        assertTrue(state.items[ItemId("c1")] is ItemFact.Subagent)
        assertTrue(state.agents.containsKey(ItemId("agent:thr-a")))
        assertTrue(state.agents.containsKey(ItemId("agent:thr-b")))
        assertEquals(ItemStatus.ACTIVE, state.agents.getValue(ItemId("agent:thr-a")).status)
        assertEquals(ItemStatus.COMPLETED, state.agents.getValue(ItemId("agent:thr-b")).status)
    }

    @Test
    fun `terminal rank never regresses to active`() {
        var state = NormalizedServerState()
        state = reducer.reduce(
            state,
            notification(
                1,
                "item/completed",
                obj("threadId" to "t1", "itemId" to "i1", "type" to "agentMessage", "text" to "done"),
            ),
        )
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/started",
                obj("threadId" to "t1", "itemId" to "i1", "type" to "agentMessage", "text" to "nope"),
            ),
        )
        val item = state.items.getValue(ItemId("i1"))
        assertEquals(ItemStatus.COMPLETED, item.status)
        assertEquals(TerminalRank.COMPLETED, item.terminalRank)
    }

    @Test
    fun `v2 nested item and agentMessage delta stream into transcript text`() {
        var state = NormalizedServerState()
        val thread = JsonObject().apply {
            addProperty("id", "t-nested")
            addProperty("title", "Nested")
        }
        state = reducer.reduce(
            state,
            notification(1, "thread/started", JsonObject().apply { add("thread", thread) }),
        )
        val item = JsonObject().apply {
            addProperty("id", "i-nested")
            addProperty("type", "agentMessage")
            addProperty("text", "")
        }
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/started",
                JsonObject().apply {
                    addProperty("threadId", "t-nested")
                    addProperty("turnId", "u-nested")
                    add("item", item)
                },
            ),
        )
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 3, 2, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/agentMessage/delta",
                    obj("threadId" to "t-nested", "turnId" to "u-nested", "itemId" to "i-nested", "delta" to "Hello"),
                ),
            ),
        )
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 4, 3, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/agentMessage/delta",
                    obj("itemId" to "i-nested", "delta" to " world"),
                ),
            ),
        )
        val completedItem = JsonObject().apply {
            addProperty("id", "i-nested")
            addProperty("type", "agentMessage")
            addProperty("text", "Hello world")
        }
        state = reducer.reduce(
            state,
            notification(
                5,
                "item/completed",
                JsonObject().apply {
                    addProperty("threadId", "t-nested")
                    addProperty("turnId", "u-nested")
                    add("item", completedItem)
                },
            ),
        )
        val agent = state.items[ItemId("i-nested")] as ItemFact.AgentMessage
        assertEquals("Hello world", agent.text)
        assertEquals(ItemStatus.COMPLETED, agent.status)
        assertTrue(state.threads.containsKey(ThreadId("t-nested")))
    }

    @Test
    fun `blank completed text does not wipe streamed agent deltas`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 2, 1, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/agentMessage/delta",
                    obj("threadId" to "t1", "turnId" to "u1", "itemId" to "i1", "delta" to "Hello"),
                ),
            ),
        )
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 3, 2, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/agentMessage/delta",
                    obj("itemId" to "i1", "delta" to " world"),
                ),
            ),
        )
        // Protocol sometimes completes with an empty text placeholder after deltas.
        val completedItem = JsonObject().apply {
            addProperty("id", "i1")
            addProperty("type", "agentMessage")
            addProperty("text", "")
        }
        state = reducer.reduce(
            state,
            notification(
                4,
                "item/completed",
                JsonObject().apply {
                    addProperty("threadId", "t1")
                    addProperty("turnId", "u1")
                    add("item", completedItem)
                },
            ),
        )
        val agent = state.items[ItemId("i1")] as ItemFact.AgentMessage
        assertEquals("Hello world", agent.text)
        assertEquals(ItemStatus.COMPLETED, agent.status)
    }

    @Test
    fun `deltas after blank completed still grow agent text`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        val completedItem = JsonObject().apply {
            addProperty("id", "i1")
            addProperty("type", "agentMessage")
            addProperty("text", "")
        }
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/completed",
                JsonObject().apply {
                    addProperty("threadId", "t1")
                    addProperty("turnId", "u1")
                    add("item", completedItem)
                },
            ),
        )
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 3, 1, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/agentMessage/delta",
                    obj("threadId" to "t1", "turnId" to "u1", "itemId" to "i1", "delta" to "Project "),
                ),
            ),
        )
        state = reducer.reduce(
            state,
            SequencedEvent(
                epoch, 4, 2, SequencedEventKind.TEXT_DELTA, null,
                WireEnvelope.Notification(
                    "item/agentMessage/delta",
                    obj("itemId" to "i1", "delta" to "là plugin Codex"),
                ),
            ),
        )
        val agent = state.items[ItemId("i1")] as ItemFact.AgentMessage
        assertEquals("Project là plugin Codex", agent.text)
        assertEquals(ItemStatus.COMPLETED, agent.status)
    }

    @Test
    fun `plan items are stored as agent messages`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        state = reducer.reduce(
            state,
            notification(
                2,
                "item/completed",
                obj(
                    "threadId" to "t1",
                    "itemId" to "p1",
                    "type" to "plan",
                    "text" to "1. Đọc README\n2. Tóm tắt",
                ),
            ),
        )
        val agent = state.items[ItemId("p1")] as ItemFact.AgentMessage
        assertEquals("1. Đọc README\n2. Tóm tắt", agent.text)
    }

    @Test
    fun `interleaved threads remain isolated`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        state = reducer.reduce(state, notification(2, "thread/started", obj("threadId" to "t2")))
        state = reducer.reduce(
            state,
            notification(3, "item/started", obj("threadId" to "t1", "itemId" to "i1", "type" to "user", "text" to "A")),
        )
        state = reducer.reduce(
            state,
            notification(4, "item/started", obj("threadId" to "t2", "itemId" to "i2", "type" to "user", "text" to "B")),
        )
        assertEquals(2, state.threads.size)
        assertEquals("t1", state.items.getValue(ItemId("i1")).threadId.value)
        assertEquals("t2", state.items.getValue(ItemId("i2")).threadId.value)
    }

    @Test
    fun `thread token usage updated is stored for status`() {
        var state = NormalizedServerState()
        state = reducer.reduce(state, notification(1, "thread/started", obj("threadId" to "t1")))
        val params = JsonObject().apply {
            addProperty("threadId", "t1")
            addProperty("turnId", "u1")
            add(
                "tokenUsage",
                JsonObject().apply {
                    addProperty("modelContextWindow", 200_000)
                    add(
                        "total",
                        JsonObject().apply {
                            addProperty("totalTokens", 12_345)
                            addProperty("inputTokens", 10_000)
                            addProperty("outputTokens", 2_345)
                            addProperty("cachedInputTokens", 0)
                            addProperty("reasoningOutputTokens", 0)
                        },
                    )
                    add(
                        "last",
                        JsonObject().apply {
                            addProperty("totalTokens", 500)
                            addProperty("inputTokens", 400)
                            addProperty("outputTokens", 100)
                            addProperty("cachedInputTokens", 0)
                            addProperty("reasoningOutputTokens", 0)
                        },
                    )
                },
            )
        }
        state = reducer.reduce(state, notification(2, "thread/tokenUsage/updated", params))
        val usage = state.threadTokenUsage.getValue(ThreadId("t1"))
        assertEquals(12_345, usage.totalTokens)
        assertEquals(500, usage.lastTokens)
        assertEquals(200_000, usage.modelContextWindow)
    }

    private fun notification(seq: Long, method: String, params: JsonObject) =
        SequencedEvent(
            epoch = epoch,
            arrivalSeq = seq,
            requestWatermark = seq,
            kind = SequencedEventKind.NOTIFICATION,
            coalesceKey = null,
            payload = WireEnvelope.Notification(method, params),
        )

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject().apply { pairs.forEach { (k, v) -> addProperty(k, v) } }
}
