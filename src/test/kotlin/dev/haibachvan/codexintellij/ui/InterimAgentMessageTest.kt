package dev.haibachvan.codexintellij.ui

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import dev.haibachvan.codexintellij.session.ConversationReducer
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InterimAgentMessageTest {
    @Test
    fun `progress agent messages fold into thinking while turn is active`() {
        val store = ServerStateStore(ConversationReducer())
        val epoch = ProcessEpoch(1)
        var seq = 0L
        fun notify(method: String, body: JsonObject.() -> Unit) {
            seq += 1
            store.dispatch(
                SequencedEvent(
                    epoch, seq, seq, SequencedEventKind.NOTIFICATION, null,
                    WireEnvelope.Notification(method, JsonObject().apply(body)),
                ),
            )
        }
        notify("thread/started") { addProperty("threadId", "t1") }
        notify("turn/started") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
        }
        notify("item/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "a1")
            addProperty("type", "agentMessage")
            addProperty("text", "Tôi sẽ đọc README.md")
        }
        notify("item/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "a2")
            addProperty("type", "agentMessage")
            addProperty("text", "Không có README, dùng AndroidManifest.xml")
        }
        notify("item/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "a3")
            addProperty("type", "agentMessage")
            addProperty("text", "Đây là com.android.settings")
        }

        val items = store.snapshot().items.values
            .filter { it.threadId == ThreadId("t1") }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        val agents = items.filterIsInstance<ItemFact.AgentMessage>()
        assertTrue(agents.size == 3)

        // While turn is active, all progress notes stay inside thinking (no Copy rows).
        agents.forEach {
            assertTrue(TranscriptRenderer.isInterimAgentMessage(it, items, activeTurnId = "turn1"))
        }
        val activeHtml = TranscriptRenderer.render(
            store.snapshot(),
            ThreadId("t1"),
            TranscriptRenderOptions(activeTurnId = "turn1", expandedReasoningIds = setOf("turn-turn1")),
        )
        assertTrue(activeHtml.contains("think-toggle"))
        assertFalse(activeHtml.contains("codex-copy:"))
        assertTrue(activeHtml.contains("Tôi sẽ đọc README.md") || activeHtml.contains("README"))

        // After turn finishes, only the last agent message is the visible result with Copy.
        agents.dropLast(1).forEach {
            assertTrue(TranscriptRenderer.isInterimAgentMessage(it, items, activeTurnId = null))
        }
        assertFalse(TranscriptRenderer.isInterimAgentMessage(agents.last(), items, activeTurnId = null))

        val doneHtml = TranscriptRenderer.render(
            store.snapshot(),
            ThreadId("t1"),
            TranscriptRenderOptions(expandedReasoningIds = setOf("turn-turn1")),
        )
        assertTrue(doneHtml.contains("codex-copy:a3"))
        assertFalse(doneHtml.contains("codex-copy:a1"))
        assertFalse(doneHtml.contains("codex-copy:a2"))
    }

    @Test
    fun `final agent reply stays visible after turn when late reasoning arrives`() {
        val store = ServerStateStore(ConversationReducer())
        val epoch = ProcessEpoch(1)
        var seq = 0L
        fun notify(method: String, body: JsonObject.() -> Unit) {
            seq += 1
            store.dispatch(
                SequencedEvent(
                    epoch, seq, seq, SequencedEventKind.NOTIFICATION, null,
                    WireEnvelope.Notification(method, JsonObject().apply(body)),
                ),
            )
        }
        notify("thread/started") { addProperty("threadId", "t1") }
        notify("turn/started") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
        }
        notify("item/started") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "r1")
            addProperty("type", "reasoning")
            addProperty("text", "Đang đọc cấu trúc project…")
        }
        notify("item/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "a1")
            addProperty("type", "agentMessage")
            addProperty("text", "Project này là plugin Codex cho IntelliJ.")
        }
        // Late reasoning completion after the final answer — common in live streams.
        notify("item/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "r1")
            addProperty("type", "reasoning")
            addProperty("text", "Đã đọc xong README và cấu trúc thư mục.")
        }
        notify("turn/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
        }

        val items = store.snapshot().items.values
            .filter { it.threadId == ThreadId("t1") }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        val agent = items.filterIsInstance<ItemFact.AgentMessage>().single()

        assertFalse(
            TranscriptRenderer.isInterimAgentMessage(agent, items, activeTurnId = null),
            "Final agent reply must remain visible after the turn ends even if reasoning finishes later",
        )

        val doneHtml = TranscriptRenderer.render(
            store.snapshot(),
            ThreadId("t1"),
            TranscriptRenderOptions(),
        )
        assertTrue(doneHtml.contains("codex-copy:a1"), doneHtml)
        assertTrue(
            doneHtml.contains("Project này là plugin Codex") || doneHtml.contains("Codex"),
            doneHtml,
        )
        assertTrue(doneHtml.contains("think-toggle") || doneHtml.contains("Đã hoạt động"), doneHtml)
    }
}
