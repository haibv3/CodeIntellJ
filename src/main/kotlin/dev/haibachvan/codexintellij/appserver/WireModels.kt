package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject

/** Minimal handwritten wire envelopes used by the transport/adapter boundary. */
sealed class WireEnvelope {
    data class Request(
        val id: String,
        val method: String,
        val params: JsonObject?,
    ) : WireEnvelope()

    data class Response(
        val id: String,
        val result: JsonObject?,
        val error: JsonObject?,
    ) : WireEnvelope()

    data class Notification(
        val method: String,
        val params: JsonObject?,
    ) : WireEnvelope()

    data class ServerRequest(
        val id: String,
        val method: String,
        val params: JsonObject?,
        val fingerprint: String,
    ) : WireEnvelope()

    /** Unknown/malformed payload retained as sanitized JsonObject. */
    data class Unknown(
        val raw: JsonObject,
        val reason: String,
    ) : WireEnvelope()
}

data class InitializeParams(
    val clientName: String,
    val clientTitle: String,
    val clientVersion: String,
    val experimentalApi: Boolean = false,
) {
    fun toJson(): JsonObject =
        JsonObject().apply {
            add(
                "clientInfo",
                JsonObject().apply {
                    addProperty("name", clientName)
                    addProperty("title", clientTitle)
                    addProperty("version", clientVersion)
                },
            )
            add(
                "capabilities",
                JsonObject().apply {
                    addProperty("experimentalApi", experimentalApi)
                },
            )
        }
}

data class InitializeResult(
    val userAgent: String,
    val platformOs: String,
    val platformFamily: String,
    val codexHome: String,
    val raw: JsonObject,
)

data class CapabilitySnapshot(
    val experimentalApiEnabled: Boolean,
    val userAgent: String?,
    val supportedMethods: Set<String>,
)
