package dev.haibachvan.codexintellij.review

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import java.util.concurrent.CompletableFuture

data class ReviewTarget(val threadId: String, val turnId: String? = null)
enum class ReviewDelivery { INLINE, PANEL }

class ReviewController(
    private val gateway: AppServerGateway,
) {
    fun start(target: ReviewTarget, delivery: ReviewDelivery): CompletableFuture<String> {
        val params = JsonObject().apply {
            addProperty("threadId", target.threadId)
            if (target.turnId != null) addProperty("turnId", target.turnId)
            addProperty("delivery", delivery.name.lowercase())
        }
        return gateway.request("review/start", params).thenApply { response ->
            response.result?.get("reviewThreadId")?.asString
                ?: response.result?.get("id")?.asString
                ?: "review-${target.threadId}"
        }
    }
}
