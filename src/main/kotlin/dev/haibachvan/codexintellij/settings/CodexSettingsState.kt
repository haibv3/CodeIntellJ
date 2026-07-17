package dev.haibachvan.codexintellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class CodexSettings(
    var experimentalApiOptIn: Boolean = false,
    var automaticContext: Boolean = false,
    var binaryPathHint: String = "",
)

@Service(Service.Level.APP)
@State(name = "CodexSettings", storages = [Storage("codex-intellij.xml")])
class CodexSettingsState : PersistentStateComponent<CodexSettings> {
    private var state = CodexSettings()

    override fun getState(): CodexSettings = state

    override fun loadState(state: CodexSettings) {
        this.state = state
    }
}
