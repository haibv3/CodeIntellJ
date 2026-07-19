package dev.haibachvan.codexintellij.ui

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import dev.haibachvan.codexintellij.session.ConversationReducer
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Feedback loop for streaming display glitches.
 *
 * While an answer streams, block identity/type must stay stable. Plain↔Html↔CodeFence
 * remounts dispose Swing hosts mid-flight and show blank/flickering bands in the chat.
 */
class StreamingTranscriptDisplayTest {
    @Test
    fun `streaming agent keeps a single html block across markdown and fences`() {
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
        val chunks = listOf(
            "Project",
            " này là plugin.\n\n",
            "```kotlin\n",
            "fun main() {\n",
            "  println(\"hi\")\n",
            "}\n```\n",
            "\nXong.",
        )
        val shapes = ArrayList<List<String>>()
        for (chunk in chunks) {
            notify("item/agentMessage/delta") {
                addProperty("threadId", "t1")
                addProperty("turnId", "turn1")
                addProperty("itemId", "a1")
                addProperty("delta", chunk)
            }
            val blocks = TranscriptRenderer.renderBlocks(
                store.snapshot(),
                ThreadId("t1"),
                TranscriptRenderOptions(activeTurnId = "turn1", lightweightStreaming = true),
            ).filter { it.id.startsWith("item:t1:a1:") }
            shapes += blocks.map { "${it::class.simpleName}:${it.id}" }
        }

        val expected = listOf("Html:item:t1:a1:prose:0")
        shapes.forEachIndexed { index, shape ->
            assertEquals(
                expected,
                shape,
                "stream step $index remounted transcript hosts: $shapes",
            )
        }
        val finalText = store.snapshot().items.values
            .filterIsInstance<dev.haibachvan.codexintellij.session.ItemFact.AgentMessage>()
            .single { it.id.value == "a1" }
            .text
        assertTrue(finalText.contains("println(\"hi\")"), finalText)
        assertTrue(finalText.contains("Xong."), finalText)
    }

    @Test
    fun `completed agent may split fences after streaming ends`() {
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
        val text = "Intro\n\n```kotlin\nfun x() = 1\n```\n\nOutro"
        notify("item/completed") {
            addProperty("threadId", "t1")
            addProperty("turnId", "turn1")
            addProperty("itemId", "a1")
            addProperty("type", "agentMessage")
            addProperty("text", text)
        }
        val live = TranscriptRenderer.renderBlocks(
            store.snapshot(),
            ThreadId("t1"),
            TranscriptRenderOptions(activeTurnId = "turn1", lightweightStreaming = true),
        ).filter { it.id.startsWith("item:t1:a1:") }
        assertEquals(listOf("Html"), live.map { it::class.simpleName })

        val done = TranscriptRenderer.renderBlocks(
            store.snapshot(),
            ThreadId("t1"),
            TranscriptRenderOptions(lightweightStreaming = false),
        ).filter { it.id.startsWith("item:t1:a1:") }
        assertTrue(
            done.any { it is TranscriptBlock.CodeFence },
            "after stream, native fence cards are allowed: ${done.map { it::class.simpleName }}",
        )
    }

    @Test
    fun `streaming updates reuse the same block id via reconciler UPDATE`() {
        val previous = listOf(
            TranscriptBlock.Html(
                fragment = "<div>partial</div>",
                id = "item:t1:a1:prose:0",
                revision = TranscriptBlockRevision(viewVersion = "partial\u0000light=true"),
            ),
        )
        val next = listOf(
            TranscriptBlock.Html(
                fragment = "<div>partial answer with **markdown**</div>",
                id = "item:t1:a1:prose:0",
                revision = TranscriptBlockRevision(viewVersion = "partial answer with **markdown**\u0000light=true"),
            ),
        )
        val plan = TranscriptBlockReconciler.plan(previous, next)
        assertEquals(1, plan.entries.size)
        assertEquals(TranscriptBlockReconciler.Change.UPDATE, plan.entries.single().change)
        assertTrue(plan.removals.isEmpty())
    }

    @Test
    fun `activity section revision changes when reasoning text streams`() {
        val options = TranscriptRenderOptions(
            expandedReasoningIds = setOf("t1/turn-turn1"),
            activeTurnId = "turn1",
        )
        val blocks1 = renderWithReasoningDeltas(listOf("partial"), options)
        val blocks2 = renderWithReasoningDeltas(listOf("partial", " and more text"), options)
        val r1 = blocks1.single { it.id.startsWith("activity:") }.revision
        val r2 = blocks2.single { it.id.startsWith("activity:") }.revision
        assertTrue(r1 != r2, "reasoning text stream must bump revision (r1=$r1 r2=$r2)")
        assertTrue(
            r1.viewVersion != r2.viewVersion || r1.sourceVersion != r2.sourceVersion,
        )
    }

    private fun renderWithReasoningDeltas(
        deltas: List<String>,
        options: TranscriptRenderOptions,
    ): List<TranscriptBlock> {
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
            addProperty("text", "")
        }
        for (delta in deltas) {
            notify("item/reasoning/delta") {
                addProperty("itemId", "r1")
                addProperty("delta", delta)
            }
        }
        return TranscriptRenderer.renderBlocks(store.snapshot(), ThreadId("t1"), options)
    }
}
