package dev.haibachvan.codexintellij.ui

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import dev.haibachvan.codexintellij.session.ConversationReducer
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiPanelChatTest {
    @Test
    fun `panel drafts and queues are isolated while shared thread facts stay consistent`() {
        val store = ServerStateStore(ConversationReducer())
        val panelA = ChatPanelModel("a")
        val panelB = ChatPanelModel("b")
        panelA.updateDraft("from-a")
        panelB.updateDraft("from-b")
        panelA.enqueueDraft()
        panelB.enqueueDraft()
        assertEquals(1, panelA.queue.entries().size)
        assertEquals(1, panelB.queue.entries().size)
        assertNotEquals(panelA.queue.entries()[0].text, panelB.queue.entries()[0].text)

        val epoch = ProcessEpoch(1)
        store.dispatch(
            SequencedEvent(
                epoch, 1, 1, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "thread/started",
                    JsonObject().apply { addProperty("threadId", "shared") },
                ),
            ),
        )
        store.dispatch(
            SequencedEvent(
                epoch, 2, 2, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "item/started",
                    JsonObject().apply {
                        addProperty("threadId", "shared")
                        addProperty("itemId", "i1")
                        addProperty("type", "agentMessage")
                        addProperty("text", "hello")
                    },
                ),
            ),
        )
        panelA.selectThread(ThreadId("shared"))
        panelB.selectThread(ThreadId("shared"))
        val renderedA = TranscriptRenderer.render(store.snapshot(), panelA.selectedThread)
        val renderedB = TranscriptRenderer.render(store.snapshot(), panelB.selectedThread)
        assertEquals(renderedA, renderedB)
        assertTrue(renderedA.contains("hello"))
        assertTrue(renderedA.contains("codex-copy:") || renderedA.contains("agent"))
        assertTrue(TranscriptRenderer.renderPlain(store.snapshot(), panelA.selectedThread).contains("hello"))
        assertTrue(store.snapshot() is NormalizedServerState)
    }
}
