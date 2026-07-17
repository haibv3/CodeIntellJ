package dev.haibachvan.codexintellij.ui

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import dev.haibachvan.codexintellij.session.ConversationReducer
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.session.ThreadId
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptRendererTest {
    @Test
    fun `agent markdown renders headers lists and inline code as html`() {
        val store = ServerStateStore(ConversationReducer())
        val epoch = ProcessEpoch(1)
        store.dispatch(
            SequencedEvent(
                epoch, 1, 1, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "thread/started",
                    JsonObject().apply { addProperty("threadId", "t1") },
                ),
            ),
        )
        store.dispatch(
            SequencedEvent(
                epoch, 2, 2, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "item/started",
                    JsonObject().apply {
                        addProperty("threadId", "t1")
                        addProperty("itemId", "u1")
                        addProperty("type", "userMessage")
                        addProperty("text", "Hỏi về project")
                    },
                ),
            ),
        )
        store.dispatch(
            SequencedEvent(
                epoch, 3, 3, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "item/started",
                    JsonObject().apply {
                        addProperty("threadId", "t1")
                        addProperty("itemId", "a1")
                        addProperty("type", "agentMessage")
                        addProperty(
                            "text",
                            """
                            # Phạm vi MVP
                            - Tool Window
                            - Gửi context từ `README.md`
                            """.trimIndent(),
                        )
                    },
                ),
            ),
        )
        val html = TranscriptRenderer.render(store.snapshot(), ThreadId("t1"))
        assertTrue(html.contains("bubble"))
        assertTrue(html.contains("Hỏi về project"))
        assertTrue(html.contains("codex-copy:") || html.contains("agent"))
        assertTrue(html.contains("<h1>") || html.contains("Phạm vi MVP"))
        assertTrue(html.contains("class=\"icode\"") || html.contains("README.md") || html.contains("file"))
        assertTrue(html.contains("<li>") || html.contains("Tool Window"))
    }

    @Test
    fun `fenced markdown code blocks become native code cards`() {
        val store = ServerStateStore(ConversationReducer())
        val epoch = ProcessEpoch(1)
        store.dispatch(
            SequencedEvent(
                epoch, 1, 1, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "thread/started",
                    JsonObject().apply { addProperty("threadId", "t1") },
                ),
            ),
        )
        store.dispatch(
            SequencedEvent(
                epoch, 2, 2, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "item/started",
                    JsonObject().apply {
                        addProperty("threadId", "t1")
                        addProperty("itemId", "a1")
                        addProperty("type", "agentMessage")
                        addProperty(
                            "text",
                            """
                            Example:
                            ```java
                            CollapsingToolbarLayout toolbarLayout = findViewById(R.id.toolbarLayout);
                            toolbarLayout.setTitle(getTitle());
                            ```
                            """.trimIndent(),
                        )
                    },
                ),
            ),
        )
        val blocks = TranscriptRenderer.renderBlocks(store.snapshot(), ThreadId("t1"))
        val fence = blocks.filterIsInstance<TranscriptBlock.CodeFence>().single()
        assertTrue(fence.language.equals("java", ignoreCase = true))
        assertTrue(fence.code.contains("CollapsingToolbarLayout"))
        // Prose stays HTML; fence is not left inside HTML as nested <pre>.
        val html = blocks.filterIsInstance<TranscriptBlock.Html>().joinToString("") { it.fragment }
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("CollapsingToolbarLayout"), html)
        assertTrue(html.contains("Example"), html)
    }

    @Test
    fun `markdown file anchors become codex-open links`() {
        val store = ServerStateStore(ConversationReducer())
        val epoch = ProcessEpoch(1)
        store.dispatch(
            SequencedEvent(
                epoch, 1, 1, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "thread/started",
                    JsonObject().apply { addProperty("threadId", "t1") },
                ),
            ),
        )
        store.dispatch(
            SequencedEvent(
                epoch, 2, 2, SequencedEventKind.NOTIFICATION, null,
                WireEnvelope.Notification(
                    "item/started",
                    JsonObject().apply {
                        addProperty("threadId", "t1")
                        addProperty("itemId", "a1")
                        addProperty("type", "agentMessage")
                        addProperty(
                            "text",
                            "[ScreenTimeoutSettings.java](/tmp/demo/ScreenTimeoutSettings.java:369)",
                        )
                    },
                ),
            ),
        )
        val html = TranscriptRenderer.render(store.snapshot(), ThreadId("t1"))
        assertTrue(html.contains("codex-open:"), html)
        assertTrue(html.contains("ScreenTimeoutSettings.java"), html)
        assertTrue(html.contains(":369") || html.contains("369"), html)
    }
}
