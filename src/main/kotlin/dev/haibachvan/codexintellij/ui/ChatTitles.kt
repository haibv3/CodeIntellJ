package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId

/** Short, human-readable chat titles (never raw thread UUIDs). */
object ChatTitles {
    fun shortTitle(raw: String, maxChars: Int = 52): String {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return "Cuộc trò chuyện mới"
        val compact = line.replace(Regex("\\s+"), " ")
        return if (compact.length <= maxChars) {
            compact
        } else {
            compact.take(maxChars - 1).trimEnd(',', '.', ' ', '…') + "…"
        }
    }

    fun looksLikeThreadId(value: String): Boolean {
        val v = value.trim()
        if (v.matches(Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))) {
            return true
        }
        // ULID / opaque app-server ids (e.g. 019f6de1-…)
        if (v.length >= 20 && v.count { it == '-' } >= 2 && v.all { it.isLetterOrDigit() || it == '-' }) {
            val hexish = v.replace("-", "")
            if (hexish.length >= 16 && hexish.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return true
            }
        }
        return false
    }

    fun resolve(
        state: NormalizedServerState,
        threadId: ThreadId,
        remembered: String? = null,
    ): String {
        val stored = state.threads[threadId]?.title
            ?.takeIf { it.isNotBlank() && !looksLikeThreadId(it) }
        if (stored != null) return shortTitle(stored)

        val memo = remembered?.takeIf { it.isNotBlank() && !looksLikeThreadId(it) }
        if (memo != null) return shortTitle(memo)

        val firstUser = state.items.values
            .asSequence()
            .filter { it.threadId == threadId }
            .filterIsInstance<ItemFact.UserMessage>()
            .minByOrNull { it.arrivalSeq }
            ?.text
            ?.takeIf { it.isNotBlank() }
        if (firstUser != null) return shortTitle(firstUser)

        return "Cuộc trò chuyện mới"
    }
}
