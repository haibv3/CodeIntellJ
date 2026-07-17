package dev.haibachvan.codexintellij.agents

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.session.NormalizedServerState
import java.util.concurrent.CompletableFuture

class AgentController(
    private val gateway: AppServerGateway,
) {
    fun stopChild(epoch: ProcessEpoch, itemId: String, state: NormalizedServerState): CompletableFuture<Boolean> {
        val agent = state.agents.values.find { it.itemId.value == itemId }
            ?: return CompletableFuture.failedFuture(IllegalStateException("Unknown agent item $itemId"))
        val live = state.items[agent.itemId]
        require(live != null && live.epoch == epoch) { "Stale agent control for $itemId" }
        val params = JsonObject().apply { addProperty("itemId", itemId) }
        return gateway.request("item/stop", params).thenApply { it.error == null }
    }
}
