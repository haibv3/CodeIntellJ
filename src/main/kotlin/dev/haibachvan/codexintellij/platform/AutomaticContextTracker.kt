package dev.haibachvan.codexintellij.platform

class AutomaticContextTracker {
    @Volatile
    var enabled: Boolean = false
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun onEditorChanged(
        snapshot: ContextSnapshot?,
        store: ContextAttachmentStore,
        mapper: ContextWireMapper,
    ) {
        if (!enabled || snapshot == null) return
        store.put("automatic", mapper.encode(snapshot))
    }
}
