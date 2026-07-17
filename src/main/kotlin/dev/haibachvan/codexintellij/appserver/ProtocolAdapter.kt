package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Encodes/decodes handwritten minimal DTOs against the Phase 1 method-schema map.
 * Unknown fields/methods remain sanitized JsonObject envelopes.
 */
class ProtocolAdapter(
    private val schemaRoot: Path,
    private val validator: ProtocolContractValidator = ProtocolContractValidator(schemaRoot),
) {
    private val methodMap by lazy { validator.loadMethodMap().associateBy { it.method } }

    fun knownMethods(): Set<String> = methodMap.keys

    fun apiClass(method: String): String? = methodMap[method]?.apiClass

    fun encodeRequest(id: String, method: String, params: JsonObject?): String {
        require(methodMap.containsKey(method)) { "Unmapped method: $method" }
        val root = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) {
                add("params", params)
            } else {
                add("params", JsonObject())
            }
        }
        return root.toString()
    }

    fun encodeNotification(method: String, params: JsonObject?): String {
        val root = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            if (params != null) {
                add("params", params)
            }
        }
        return root.toString()
    }

    fun encodeResponse(id: String, result: JsonObject?, error: JsonObject?): String {
        val root = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            if (error != null) {
                add("error", error)
            } else {
                add("result", result ?: JsonObject())
            }
        }
        return root.toString()
    }

    fun decodeLine(line: String): WireEnvelope {
        val element = try {
            JsonParser.parseString(line)
        } catch (_: Exception) {
            return WireEnvelope.Unknown(
                raw = JsonObject().apply { addProperty("raw", truncate(line)) },
                reason = "malformed_json",
            )
        }
        if (!element.isJsonObject) {
            return WireEnvelope.Unknown(
                raw = JsonObject().apply { addProperty("raw", truncate(line)) },
                reason = "non_object",
            )
        }
        val obj = element.asJsonObject
        return when {
            obj.has("method") && obj.has("id") && !obj.has("result") && !obj.has("error") -> {
                val method = obj.get("method").asString
                val id = obj.get("id").asString
                val params = obj.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
                if (methodMap.containsKey(method) || looksLikeServerRequest(method)) {
                    WireEnvelope.ServerRequest(
                        id = id,
                        method = method,
                        params = params,
                        fingerprint = fingerprint(method, params),
                    )
                } else {
                    WireEnvelope.Unknown(sanitize(obj), "unknown_server_request")
                }
            }
            obj.has("method") && !obj.has("id") -> {
                val method = obj.get("method").asString
                val params = obj.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
                WireEnvelope.Notification(method = method, params = params)
            }
            obj.has("id") && (obj.has("result") || obj.has("error")) -> {
                WireEnvelope.Response(
                    id = obj.get("id").asString,
                    result = obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject,
                    error = obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject,
                )
            }
            else -> WireEnvelope.Unknown(sanitize(obj), "unclassified_envelope")
        }
    }

    fun parseInitializeResult(result: JsonObject): InitializeResult =
        InitializeResult(
            userAgent = result.get("userAgent")?.asString ?: "",
            platformOs = result.get("platformOs")?.asString ?: "",
            platformFamily = result.get("platformFamily")?.asString ?: "",
            codexHome = result.get("codexHome")?.asString ?: "",
            raw = result,
        )

    fun fingerprint(method: String, params: JsonObject?): String {
        val material = buildString {
            append(method)
            append('\n')
            append(params?.toString() ?: "")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun looksLikeServerRequest(method: String): Boolean =
        method.contains("approval", ignoreCase = true) ||
            method.contains("elicitation", ignoreCase = true) ||
            method.startsWith("server/")

    private fun sanitize(obj: JsonObject): JsonObject {
        val copy = obj.deepCopy()
        // Strip likely secret-bearing keys at the unknown boundary.
        val banned = listOf("token", "secret", "password", "authorization", "apiKey", "api_key")
        fun scrub(target: JsonObject) {
            val keys = target.keySet().toList()
            for (key in keys) {
                if (banned.any { key.contains(it, ignoreCase = true) }) {
                    target.addProperty(key, RedactionPolicy.REDACTED)
                } else {
                    val child = target.get(key)
                    if (child != null && child.isJsonObject) {
                        scrub(child.asJsonObject)
                    }
                }
            }
        }
        scrub(copy)
        return copy
    }

    private fun truncate(text: String, max: Int = 256): String =
        if (text.length <= max) text else text.take(max) + "…"
}
