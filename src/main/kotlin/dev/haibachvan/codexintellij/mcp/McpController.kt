package dev.haibachvan.codexintellij.mcp

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import java.util.concurrent.CompletableFuture

class McpController(
    private val gateway: AppServerGateway,
    private val consent: McpConsent = McpConsent(),
) {
    fun listStatus(): CompletableFuture<JsonObject?> =
        gateway.request("mcpServerStatus/list", JsonObject()).thenApply { it.result }

    fun callTool(
        epoch: ProcessEpoch,
        server: String,
        tool: String,
        argsJson: String,
        preview: McpConsentPreview,
    ): CompletableFuture<JsonObject?> {
        consent.assertUnchanged(preview, argsJson)
        require(consent.isGranted(preview.key)) { "MCP consent not granted" }
        val params = JsonObject().apply {
            addProperty("server", server)
            addProperty("tool", tool)
            addProperty("arguments", argsJson)
        }
        return gateway.request("mcpTool/call", params).thenApply { it.result }
    }

    fun consent(): McpConsent = consent
}
