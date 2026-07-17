package dev.haibachvan.codexintellij.ui

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Short-lived clipboard registry for fenced code-block copy actions. */
object CodeBlockClipboard {
    private val seq = AtomicInteger()
    private val store = ConcurrentHashMap<String, String>()

    fun put(code: String): String {
        val id = "cb${seq.incrementAndGet()}-${Integer.toHexString(code.hashCode())}"
        store[id] = code
        if (store.size > 200) {
            store.keys.take(store.size - 160).forEach { store.remove(it) }
        }
        return id
    }

    fun get(id: String): String? = store[id]
}
