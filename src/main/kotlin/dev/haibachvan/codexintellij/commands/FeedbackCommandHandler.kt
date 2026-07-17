package dev.haibachvan.codexintellij.commands

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.RedactedBundle
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import java.util.concurrent.CompletableFuture

fun interface FeedbackRpc {
    fun request(method: String, params: JsonObject?): CompletableFuture<WireEnvelope.Response>
}

class FeedbackCommandHandler(
    private val rpc: FeedbackRpc,
) {
    constructor(gateway: AppServerGateway) : this(FeedbackRpc { method, params -> gateway.request(method, params) })

    fun upload(bundle: RedactedBundle, consentGranted: Boolean): CompletableFuture<Boolean> {
        require(consentGranted) { "Feedback upload requires explicit consent" }
        val params = JsonObject().apply {
            addProperty("sha256", bundle.sha256)
            addProperty("eventCount", bundle.eventCount)
            addProperty("bytesLength", bundle.bytes.size)
        }
        return rpc.request("feedback/upload", params).thenApply { it.error == null }
    }
}
