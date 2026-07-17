package dev.haibachvan.codexintellij.commands

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import java.util.concurrent.CompletableFuture

class ThreadCommandHandler(private val gateway: AppServerGateway) {
    fun compact(threadId: String) = rpc("thread/compact/start", threadId)
    fun fork(threadId: String) = rpc("thread/fork", threadId)
    fun setGoal(threadId: String, goal: String): CompletableFuture<*> {
        val params = JsonObject().apply {
            addProperty("threadId", threadId)
            addProperty("goal", goal)
        }
        return gateway.request("thread/goal/set", params)
    }

    private fun rpc(method: String, threadId: String) =
        gateway.request(method, JsonObject().apply { addProperty("threadId", threadId) })
}
