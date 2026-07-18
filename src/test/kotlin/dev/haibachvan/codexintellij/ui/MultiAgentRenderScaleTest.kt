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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards multi-agent ANR fixes: O(n) projection index + single embedded store owner.
 */
class MultiAgentRenderScaleTest {
    @Test
    fun `projection index classification stays near-linear under multi-agent load`() {
        val smallItems = threadItems(buildMultiAgentState(agentCount = 40))
        val largeItems = threadItems(buildMultiAgentState(agentCount = 160)) // 4×

        fun classifyOnce(items: List<ItemFact>) {
            TranscriptRenderer.projectionIndex(items, activeTurnId = "turn1")
        }

        repeat(3) { classifyOnce(smallItems) }

        val smallNs = medianNs(runs = 7) { classifyOnce(smallItems) }
        val largeNs = medianNs(runs = 7) { classifyOnce(largeItems) }
        val ratio = largeNs.toDouble() / smallNs.coerceAtLeast(1L)

        TranscriptRenderer.projectionIndex(largeItems, activeTurnId = "turn1")
        val visits = TranscriptRenderer.lastProjectionItemVisits
        val n = largeItems.size

        val report = buildString {
            appendLine("multi-agent projection-index scale")
            appendLine("small_agents=40 items=${smallItems.size} ns=$smallNs")
            appendLine("large_agents=160 items=${largeItems.size} ns=$largeNs")
            appendLine("ratio=$ratio (4× input; linear≈4, quadratic≈16)")
            appendLine("large_visits=$visits items=$n")
        }
        Files.writeString(Path.of("/tmp/multi-agent-render-scale.txt"), report)
        System.err.println(report)

        assertTrue(
            ratio < 10.0,
            "Expected near-linear classification; got ${"%.2f".format(ratio)}× for 4× multi-agent items. $report",
        )
        // Sort once + a few linear passes — reject nested O(n²) rescans.
        assertTrue(
            visits <= n * 6,
            "Expected O(n) projection visits (<=6n), got visits=$visits for n=$n",
        )
    }

    @Test
    fun `embedded chat does not self-subscribe when parent delivers state`() {
        val chatSrc = Files.readString(
            Path.of("src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexChatPanel.kt"),
        )
        val workspaceSrc = Files.readString(
            Path.of("src/main/kotlin/dev/haibachvan/codexintellij/ui/CodexWorkspacePanel.kt"),
        )
        assertTrue(workspaceSrc.contains("embedded = true"))
        assertTrue(workspaceSrc.contains("chat.renderExternal(state)"))
        assertTrue(workspaceSrc.contains("serverStateStore().addListener(stateListener)"))

        val initBlock = chatSrc.substringAfter("init {").substringBefore("fun renderExternal")
        assertTrue(
            initBlock.contains("if (!embedded)"),
            "Standalone/embedded ownership must be gated in init",
        )
        // addListener lives only inside the !embedded branch (with composer setup).
        val afterGate = initBlock.substringAfter("if (!embedded)")
        assertTrue(afterGate.contains("addListener(stateListener)"))
        // No unconditional addListener after the gated block closes.
        val afterEmbeddedBlock = afterGate.substringAfter("addListener(stateListener)")
        assertFalse(
            afterEmbeddedBlock.contains("addListener(stateListener)"),
            "Embedded CodexChatPanel must not also self-subscribe",
        )
    }

    @Test
    fun `projection index matches public interim classification`() {
        val items = threadItems(buildMultiAgentState(agentCount = 12))
        val index = TranscriptRenderer.projectionIndex(items, activeTurnId = "turn1")
        for (agent in items.filterIsInstance<ItemFact.AgentMessage>()) {
            val expected = TranscriptRenderer.isInterimAgentMessage(agent, items, "turn1")
            assertTrue(
                index.isInterim(agent) == expected,
                "Mismatch for ${agent.id.value}: index=${index.isInterim(agent)} api=$expected",
            )
        }
    }

    private fun threadItems(state: dev.haibachvan.codexintellij.session.NormalizedServerState) =
        state.items.values
            .filter { it.threadId == ThreadId("t1") }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))

    private fun medianNs(runs: Int, block: () -> Unit): Long {
        val samples = LongArray(runs) { measureNanoTime(block) }
        samples.sort()
        return samples[runs / 2]
    }

    private fun buildMultiAgentState(agentCount: Int) =
        ServerStateStore(ConversationReducer()).also { store ->
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
                addProperty("itemId", "user1")
                addProperty("type", "userMessage")
                addProperty("text", "Chạy nhiều agent song song")
            }
            repeat(agentCount) { i ->
                notify("item/completed") {
                    addProperty("threadId", "t1")
                    addProperty("turnId", "turn1")
                    addProperty("itemId", "prog$i")
                    addProperty("type", "agentMessage")
                    addProperty("text", "Tiến độ agent #$i: đang khảo sát module")
                }
                notify("item/started") {
                    addProperty("threadId", "t1")
                    addProperty("turnId", "turn1")
                    addProperty("itemId", "cmd$i")
                    addProperty("type", "commandExecution")
                    addProperty("command", "echo agent-$i")
                }
                notify("item/started") {
                    addProperty("threadId", "t1")
                    addProperty("turnId", "turn1")
                    addProperty("itemId", "agent$i")
                    addProperty("type", "agent")
                    addProperty("agentId", "worker-$i")
                    addProperty("status", "active")
                    addProperty("summary", "Worker $i đang chạy")
                }
            }
            notify("item/started") {
                addProperty("threadId", "t1")
                addProperty("turnId", "turn1")
                addProperty("itemId", "final")
                addProperty("type", "agentMessage")
                addProperty("text", "Tổng hợp kết quả từ các agent…")
            }
        }.snapshot()
}
