package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.session.DiffLineCounts
import dev.haibachvan.codexintellij.session.DiffLineStats
import dev.haibachvan.codexintellij.session.FileChangeEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Short-lived registry for modified-files card Review / Undo actions. */
object ModifiedFilesActions {
    data class FileRow(
        val path: String,
        val unifiedDiff: String?,
        val kind: String,
        val counts: DiffLineCounts,
    )

    data class Payload(
        val threadId: String,
        val turnId: String?,
        val files: List<FileRow>,
    ) {
        val total: DiffLineCounts
            get() = files.fold(DiffLineCounts.ZERO) { acc, row -> acc + row.counts }
    }

    private val seq = AtomicInteger()
    private val store = ConcurrentHashMap<String, Payload>()

    fun put(payload: Payload): String {
        val id = "mf${seq.incrementAndGet()}"
        store[id] = payload
        if (store.size > 80) {
            store.keys.take(store.size - 60).forEach { store.remove(it) }
        }
        return id
    }

    fun get(id: String): Payload? = store[id]

    fun fromEntries(
        threadId: String,
        turnId: String?,
        entries: List<FileChangeEntry>,
    ): Payload {
        val merged = LinkedHashMap<String, FileChangeEntry>()
        entries.forEach { entry ->
            if (entry.path.isBlank()) return@forEach
            val prev = merged[entry.path]
            merged[entry.path] = if (prev == null) {
                entry
            } else {
                entry.copy(
                    unifiedDiff = listOfNotNull(prev.unifiedDiff, entry.unifiedDiff)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                        .ifBlank { entry.unifiedDiff },
                )
            }
        }
        val rows = merged.values.map { e ->
            FileRow(
                path = e.path,
                unifiedDiff = e.unifiedDiff,
                kind = e.kind,
                counts = DiffLineStats.of(e.unifiedDiff),
            )
        }.filter { row ->
            // Drop touch/metadata noise (e.g. guard-state.json with +0 −0 and no hunks).
            row.counts.added > 0 ||
                row.counts.removed > 0 ||
                !row.unifiedDiff.isNullOrBlank() && row.unifiedDiff.contains("@@")
        }
        return Payload(threadId = threadId, turnId = turnId, files = rows)
    }
}
