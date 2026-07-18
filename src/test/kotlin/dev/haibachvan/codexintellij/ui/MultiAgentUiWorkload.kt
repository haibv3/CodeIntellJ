package dev.haibachvan.codexintellij.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import dev.haibachvan.codexintellij.session.ConversationReducer
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ServerStateStore
import java.nio.file.Files
import java.nio.file.Path

data class MultiAgentUiWorkloadSpec(
    val agentCount: Int,
    val eventCount: Int,
    val messageCount: Int,
    val transcriptBlockCount: Int,
    val seed: Int,
)

object MultiAgentUiWorkload {
    val spec: MultiAgentUiWorkloadSpec by lazy {
        val path = Path.of("src/test/resources/fixtures/appserver/multi-agent-performance-spec.json")
        Gson().fromJson(Files.readString(path), MultiAgentUiWorkloadSpec::class.java)
    }

    fun events(): List<SequencedEvent> {
        val config = spec
        val epoch = ProcessEpoch(1)
        var sequence = 0L
        val events = ArrayList<SequencedEvent>(config.eventCount)

        fun notify(method: String, body: JsonObject.() -> Unit) {
            sequence += 1
            events += SequencedEvent(
                epoch = epoch,
                arrivalSeq = sequence,
                requestWatermark = sequence,
                kind = SequencedEventKind.NOTIFICATION,
                coalesceKey = null,
                payload = WireEnvelope.Notification(method, JsonObject().apply(body)),
            )
        }

        notify("thread/started") {
            addProperty("threadId", "perf-thread")
            addProperty("title", "Multi-agent performance")
        }
        notify("turn/started") {
            addProperty("threadId", "perf-thread")
            addProperty("turnId", "perf-turn")
        }
        notify("item/completed") {
            addProperty("threadId", "perf-thread")
            addProperty("turnId", "perf-turn")
            addProperty("itemId", "perf-user")
            addProperty("type", "userMessage")
            addProperty("text", "Run deterministic multi-agent workload")
        }
        repeat(config.agentCount) { index ->
            notify("item/started") {
                addProperty("threadId", "perf-thread")
                addProperty("turnId", "perf-agents")
                addProperty("itemId", "perf-agent-$index")
                addProperty("type", "subagent")
                addProperty("agentId", "worker-$index")
                addProperty("status", "active")
                addProperty("text", "Worker $index")
            }
        }
        notify("item/completed") {
            addProperty("threadId", "perf-thread")
            addProperty("turnId", "perf-agents")
            addProperty("itemId", "perf-progress")
            addProperty("type", "agentMessage")
            addProperty("text", "Agents are collecting results")
        }
        repeat(config.messageCount - 1) { index ->
            notify("item/completed") {
                addProperty("threadId", "perf-thread")
                addProperty("turnId", "perf-result-$index")
                addProperty("itemId", "perf-result-$index")
                addProperty("type", "agentMessage")
                addProperty("text", "Result $index revision 0 seed ${config.seed}")
            }
        }

        val updateCount = config.eventCount - events.size - 1
        require(updateCount >= 0) { "eventCount is smaller than the workload core" }
        repeat(updateCount) { revision ->
            val resultCount = config.messageCount - 1
            val index = revision % resultCount
            notify("item/updated") {
                addProperty("threadId", "perf-thread")
                addProperty("turnId", "perf-result-$index")
                addProperty("itemId", "perf-result-$index")
                addProperty("type", "agentMessage")
                addProperty("text", "Result $index revision ${revision + 1} seed ${config.seed}")
            }
        }
        notify("turn/completed") {
            addProperty("threadId", "perf-thread")
            addProperty("turnId", "perf-turn")
        }

        check(events.size == config.eventCount)
        return events
    }

    fun reduce(): NormalizedServerState =
        ServerStateStore(ConversationReducer()).also { store ->
            events().forEach(store::dispatch)
        }.snapshot()
}
