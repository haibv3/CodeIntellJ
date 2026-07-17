package dev.haibachvan.codexintellij.mcp

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import java.security.MessageDigest

data class McpConsentKey(
    val epoch: ProcessEpoch,
    val serverName: String,
    val toolName: String,
    val argsHash: String,
)

data class McpConsentPreview(
    val key: McpConsentKey,
    val immutablePreviewBytes: ByteArray,
    val sha256: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is McpConsentPreview) return false
        return key == other.key && sha256 == other.sha256 &&
            immutablePreviewBytes.contentEquals(other.immutablePreviewBytes)
    }

    override fun hashCode(): Int = sha256.hashCode()
}

class McpConsent {
    private val granted = HashSet<McpConsentKey>()

    fun preview(epoch: ProcessEpoch, server: String, tool: String, argsJson: String): McpConsentPreview {
        val bytes = argsJson.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return McpConsentPreview(
            key = McpConsentKey(epoch, server, tool, hash),
            immutablePreviewBytes = bytes.copyOf(),
            sha256 = hash,
        )
    }

    fun grant(preview: McpConsentPreview) {
        granted += preview.key
    }

    fun isGranted(key: McpConsentKey): Boolean = key in granted

    fun assertUnchanged(preview: McpConsentPreview, currentArgsJson: String) {
        val current = preview(preview.key.epoch, preview.key.serverName, preview.key.toolName, currentArgsJson)
        require(current.sha256 == preview.sha256) { "MCP args changed after preview; re-consent required" }
        require(current.immutablePreviewBytes.contentEquals(preview.immutablePreviewBytes)) {
            "MCP preview bytes mutated"
        }
    }
}
