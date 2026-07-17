package dev.haibachvan.codexintellij.commands

class PreferenceCommandHandler {
    data class TurnOverrides(
        var model: String? = null,
        var personality: String? = null,
        var effort: String? = null,
        var serviceTier: String? = null,
    )

    private val overrides = TurnOverrides()

    fun setModel(value: String) { overrides.model = value }
    fun setPersonality(value: String) { overrides.personality = value }
    fun setEffort(value: String) { overrides.effort = value }
    fun setFast(enabled: Boolean) { overrides.serviceTier = if (enabled) "fast" else null }
    fun snapshot(): TurnOverrides = overrides.copy()
    fun clear() {
        overrides.model = null
        overrides.personality = null
        overrides.effort = null
        overrides.serviceTier = null
    }
}
