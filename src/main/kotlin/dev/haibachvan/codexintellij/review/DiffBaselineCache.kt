package dev.haibachvan.codexintellij.review

import java.security.MessageDigest

enum class BaselineSource { DOCUMENT, DISK }

data class BaselineEntry(
    val path: String,
    val source: BaselineSource,
    val bytes: ByteArray,
    val sha256: String,
    val stamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BaselineEntry) return false
        return path == other.path && source == other.source && sha256 == other.sha256 && stamp == other.stamp &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = sha256.hashCode()
}

class DiffBaselineCache {
    private val entries = LinkedHashMap<String, BaselineEntry>()

    fun captureBeforeTurn(
        threadId: String,
        turnId: String,
        path: String,
        source: BaselineSource,
        bytes: ByteArray,
        stamp: Long,
    ): BaselineEntry {
        val key = "$threadId|$turnId|$path"
        val entry = BaselineEntry(
            path = path,
            source = source,
            bytes = bytes.copyOf(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            stamp = stamp,
        )
        entries[key] = entry
        return entry
    }

    fun get(threadId: String, turnId: String, path: String): BaselineEntry? =
        entries["$threadId|$turnId|$path"]

    fun clearTurn(threadId: String, turnId: String) {
        val prefix = "$threadId|$turnId|"
        entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) }
    }
}
