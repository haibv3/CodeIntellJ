package dev.haibachvan.codexintellij.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadSnapshotImportTest {
    @Test
    fun `list data maps titles and sorts by updatedAt`() {
        val data = JsonArray().apply {
            add(
                JsonObject().apply {
                    addProperty("id", "t-old")
                    addProperty("preview", "Old task")
                    addProperty("updatedAt", 100)
                    addProperty("createdAt", 50)
                    addProperty("status", "active")
                },
            )
            add(
                JsonObject().apply {
                    addProperty("id", "t-new")
                    addProperty("name", "Project này làm gì")
                    addProperty("preview", "fallback")
                    addProperty("updatedAt", 200)
                    addProperty("createdAt", 150)
                    addProperty("status", "active")
                },
            )
        }
        val envelope = ThreadSnapshotImport.fromListData(data, ProcessEpoch(1))
        assertEquals(2, envelope.threads.size)
        val byId = envelope.threads.associateBy { it.id.value }
        assertEquals("Project này làm gì", byId["t-new"]?.title)
        assertEquals("Old task", byId["t-old"]?.title)
        assertTrue((byId["t-new"]?.arrivalSeq ?: 0) > (byId["t-old"]?.arrivalSeq ?: 0))
    }

    @Test
    fun `resume thread imports user and agent messages`() {
        val thread = JsonObject().apply {
            addProperty("id", "t1")
            addProperty("preview", "Hello")
            addProperty("status", "active")
            add(
                "turns",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("id", "turn1")
                            addProperty("status", "completed")
                            add(
                                "items",
                                JsonArray().apply {
                                    add(
                                        JsonObject().apply {
                                            addProperty("id", "u1")
                                            addProperty("type", "userMessage")
                                            add(
                                                "content",
                                                JsonArray().apply {
                                                    add(
                                                        JsonObject().apply {
                                                            addProperty("type", "text")
                                                            addProperty("text", "Project này làm gì")
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        JsonObject().apply {
                                            addProperty("id", "a1")
                                            addProperty("type", "agentMessage")
                                            addProperty("text", "Đây là app Settings.")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val envelope = ThreadSnapshotImport.fromThread(thread, ProcessEpoch(2))
        assertEquals(1, envelope.threads.size)
        assertEquals(1, envelope.turns.size)
        assertEquals(2, envelope.items.size)
        val user = envelope.items.filterIsInstance<ItemFact.UserMessage>().single()
        val agent = envelope.items.filterIsInstance<ItemFact.AgentMessage>().single()
        assertEquals("Project này làm gì", user.text)
        assertEquals("Đây là app Settings.", agent.text)
        assertEquals(ThreadId("t1"), user.threadId)
    }

    @Test
    fun `resume result prefers initialTurnsPage when embedded turns lack items`() {
        val result = JsonObject().apply {
            add(
                "thread",
                JsonObject().apply {
                    addProperty("id", "t1")
                    addProperty("preview", "Hello")
                    add(
                        "turns",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("id", "turn1")
                                    addProperty("status", "completed")
                                    addProperty("itemsView", "notLoaded")
                                    add("items", JsonArray())
                                },
                            )
                        },
                    )
                },
            )
            add(
                "initialTurnsPage",
                JsonObject().apply {
                    add(
                        "data",
                        JsonArray().apply {
                            add(
                                JsonObject().apply {
                                    addProperty("id", "turn1")
                                    addProperty("status", "completed")
                                    addProperty("itemsView", "full")
                                    add(
                                        "items",
                                        JsonArray().apply {
                                            add(
                                                JsonObject().apply {
                                                    addProperty("id", "a1")
                                                    addProperty("type", "agentMessage")
                                                    addProperty("text", "Loaded via page")
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val envelope = ThreadSnapshotImport.fromResumeResult(result, ProcessEpoch(3))
        assertEquals(1, envelope.items.size)
        assertEquals(
            "Loaded via page",
            (envelope.items.filterIsInstance<ItemFact.AgentMessage>().single()).text,
        )
    }
}
