package dev.haibachvan.codexintellij.commands

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.mcp.McpController
import java.util.concurrent.CompletableFuture

class McpCommandHandler(private val controller: McpController) {
    fun status(): CompletableFuture<JsonObject?> = controller.listStatus()
}
