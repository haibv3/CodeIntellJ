package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject

/**
 * Minimal handwritten JSON-RPC / known DTO boundary for Phase 1.
 * Unknown envelopes remain [JsonObject] and are never fatal at this layer.
 */
data class JsonRpcRequest(
    val id: String,
    val method: String,
    val params: JsonObject?,
)

data class JsonRpcResponse(
    val id: String,
    val result: JsonObject?,
    val error: JsonObject?,
)

data class JsonRpcNotification(
    val method: String,
    val params: JsonObject?,
)

/** Unknown method/params/result envelope retained for forward compatibility. */
data class UnknownEnvelope(
    val raw: JsonObject,
)
