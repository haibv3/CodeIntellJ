package dev.haibachvan.codexintellij.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class CodexConfigurable : Configurable {
    private val experimental = JCheckBox("Enable experimental app-server APIs")
    private val automatic = JCheckBox("Automatic editor context for future turns")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Codex"

    override fun createComponent(): JComponent {
        val p = JPanel()
        p.add(experimental)
        p.add(automatic)
        panel = p
        reset()
        return p
    }

    override fun isModified(): Boolean {
        val state = service<CodexSettingsState>().state
        return experimental.isSelected != state.experimentalApiOptIn ||
            automatic.isSelected != state.automaticContext
    }

    override fun apply() {
        val state = service<CodexSettingsState>().state
        state.experimentalApiOptIn = experimental.isSelected
        state.automaticContext = automatic.isSelected
    }

    override fun reset() {
        val state = service<CodexSettingsState>().state
        experimental.isSelected = state.experimentalApiOptIn
        automatic.isSelected = state.automaticContext
    }
}
