package dev.haibachvan.codexintellij.platform

class ContextAttachmentStore {
    private val attachments = LinkedHashMap<String, EncodedContextInput>()
    fun put(id: String, encoded: EncodedContextInput) { attachments[id] = encoded }
    fun remove(id: String) { attachments.remove(id) }
    fun get(id: String): EncodedContextInput? = attachments[id]
    fun all(): List<EncodedContextInput> = attachments.values.toList()
    fun clear() = attachments.clear()
}
