package dev.haibachvan.codexintellij.appserver

import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Bounded storage for already-redacted structured diagnostics only.
 */
class DiagnosticRingBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val events = ArrayDeque<StructuredDiagnosticEvent>(capacity)
    private val lock = Any()

    fun append(event: StructuredDiagnosticEvent) {
        require(event.redacted) {
            "DiagnosticRingBuffer accepts only redacted StructuredDiagnosticEvent instances"
        }
        synchronized(lock) {
            if (events.size >= capacity) {
                events.removeFirst()
            }
            events.addLast(event)
        }
    }

    fun snapshot(): List<StructuredDiagnosticEvent> =
        synchronized(lock) { events.toList() }

    fun size(): Int = synchronized(lock) { events.size }

    fun clear() {
        synchronized(lock) { events.clear() }
    }

    fun bundle(fromInclusive: Int = 0, toExclusive: Int = size()): RedactedBundle {
        val slice = synchronized(lock) {
            val list = events.toList()
            val from = fromInclusive.coerceIn(0, list.size)
            val to = toExclusive.coerceIn(from, list.size)
            list.subList(from, to)
        }
        val text = buildString {
            slice.forEach { event ->
                append(event.severity.name).append('|')
                append(event.code).append('|')
                append(event.message)
                if (event.fields.isNotEmpty()) {
                    append('|')
                    event.fields.entries.sortedBy { it.key }.forEachIndexed { idx, (k, v) ->
                        if (idx > 0) append(',')
                        append(k).append('=').append(v)
                    }
                }
                append('\n')
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        return RedactedBundle(
            bytes = bytes,
            sha256 = sha256Hex(bytes),
            eventCount = slice.size,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
