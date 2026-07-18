package dev.haibachvan.codexintellij.ui

import com.google.gson.JsonArray
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

class ModifiedFilesCardTest {
    @Test
    fun `patch items produce modified files transcript block`() {
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
                    "item/completed",
                    JsonObject().apply {
                        addProperty("threadId", "t1")
                        addProperty("turnId", "turn1")
                        addProperty("itemId", "p1")
                        addProperty("type", "fileChange")
                        add(
                            "changes",
                            JsonArray().apply {
                                add(
                                    JsonObject().apply {
                                        addProperty("path", "docs/a.md")
                                        addProperty(
                                            "diff",
                                            "--- a/docs/a.md\n+++ b/docs/a.md\n@@ -0,0 +1,2 @@\n+one\n+two\n",
                                        )
                                        add("kind", JsonObject().apply { addProperty("type", "add") })
                                    },
                                )
                                add(
                                    JsonObject().apply {
                                        addProperty("path", "docs/b.md")
                                        addProperty(
                                            "diff",
                                            "--- a/docs/b.md\n+++ b/docs/b.md\n@@ -0,0 +1,1 @@\n+x\n",
                                        )
                                        add("kind", JsonObject().apply { addProperty("type", "add") })
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
        )
        val blocks = TranscriptRenderer.renderBlocks(
            store.snapshot(),
            ThreadId("t1"),
            TranscriptRenderOptions(
                localNotices = listOf(ChatPanelModel.LocalNotice("files:turn-turn1", "Notice", "Body")),
            ),
        )
        val card = blocks.filterIsInstance<TranscriptBlock.ModifiedFiles>().single()
        assertTrue(card.payload.files.size == 2)
        assertTrue(card.payload.files.any { it.path.endsWith("docs/a.md") })
        assertTrue(card.payload.total.added >= 3)
        assertTrue(blocks.map { it.id }.toSet().size == blocks.size)
        assertTrue(card.id.startsWith("files:"))
        assertTrue(blocks.any { it.id.startsWith("notice:") })
    }

    @Test
    fun `modified files card appears after agent reply`() {
        val store = ServerStateStore(ConversationReducer())
        val epoch = ProcessEpoch(1)
        fun notif(seq: Long, method: String, params: JsonObject) {
            store.dispatch(
                SequencedEvent(
                    epoch, seq, seq, SequencedEventKind.NOTIFICATION, null,
                    WireEnvelope.Notification(method, params),
                ),
            )
        }
        notif(1, "thread/started", JsonObject().apply { addProperty("threadId", "t1") })
        notif(
            2,
            "item/completed",
            JsonObject().apply {
                addProperty("threadId", "t1")
                addProperty("turnId", "turn1")
                addProperty("itemId", "p1")
                addProperty("type", "fileChange")
                add(
                    "changes",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("path", "docs/a.md")
                                addProperty(
                                    "diff",
                                    "--- a/docs/a.md\n+++ b/docs/a.md\n@@ -0,0 +1,1 @@\n+one\n",
                                )
                                add("kind", JsonObject().apply { addProperty("type", "add") })
                            },
                        )
                    },
                )
            },
        )
        notif(
            3,
            "item/completed",
            JsonObject().apply {
                addProperty("threadId", "t1")
                addProperty("turnId", "turn1")
                addProperty("itemId", "a1")
                addProperty("type", "agentMessage")
                addProperty("text", "Đã sửa xong.")
            },
        )
        val blocks = TranscriptRenderer.renderBlocks(store.snapshot(), ThreadId("t1"))
        val cardIdx = blocks.indexOfFirst { it is TranscriptBlock.ModifiedFiles }
        val agentIdx = blocks.indexOfFirst {
            when (it) {
                is TranscriptBlock.Html -> it.fragment.contains("Đã sửa xong")
                is TranscriptBlock.PlainAgentMessage -> it.text.contains("Đã sửa xong")
                else -> false
            }
        }
        assertTrue(agentIdx >= 0, "expected agent transcript block")
        assertTrue(cardIdx >= 0, "expected modified-files card")
        assertTrue(cardIdx > agentIdx, "card should follow agent reply, got order=$blocks")
    }
}
