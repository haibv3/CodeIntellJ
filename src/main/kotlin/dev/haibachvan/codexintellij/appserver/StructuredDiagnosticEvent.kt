package dev.haibachvan.codexintellij.appserver

/**
 * Allow-listed structured diagnostic. Must be redacted before ring-buffer storage.
 */
data class StructuredDiagnosticEvent(
    val epoch: ProcessEpoch?,
    val code: String,
    val severity: Severity,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val redacted: Boolean = false,
) {
    enum class Severity {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    init {
        require(code.isNotBlank()) { "Diagnostic code must not be blank" }
        require(message.length <= MAX_MESSAGE_CHARS) { "Diagnostic message too long" }
        require(fields.size <= MAX_FIELDS) { "Too many diagnostic fields" }
        fields.forEach { (k, v) ->
            require(k.length <= MAX_FIELD_KEY) { "Diagnostic field key too long" }
            require(v.length <= MAX_FIELD_VALUE) { "Diagnostic field value too long" }
        }
    }

    companion object {
        const val MAX_MESSAGE_CHARS: Int = 2_000
        const val MAX_FIELDS: Int = 32
        const val MAX_FIELD_KEY: Int = 64
        const val MAX_FIELD_VALUE: Int = 512
    }
}

data class RedactedBundle(
    val bytes: ByteArray,
    val sha256: String,
    val eventCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RedactedBundle) return false
        return eventCount == other.eventCount &&
            sha256 == other.sha256 &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + eventCount
        return result
    }
}
