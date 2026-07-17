package dev.haibachvan.codexintellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Title-bar actions for the Codex tool window (same row as Options / Hide).
 */
internal object CodexToolWindowHeader {
    fun statusAction(label: JLabel): AnAction =
        object : AnAction(), CustomComponentAction, DumbAware {
            override fun actionPerformed(e: AnActionEvent) = Unit

            override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
                label

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        }

    fun connectAction(isReady: () -> Boolean, isStarting: () -> Boolean, onConnect: () -> Unit): AnAction =
        object : DumbAwareAction("Kết nối") {
            override fun actionPerformed(e: AnActionEvent) = onConnect()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = !isReady()
                e.presentation.isEnabled = !isStarting()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    fun disconnectAction(isReady: () -> Boolean, onDisconnect: () -> Unit): AnAction =
        object : DumbAwareAction("Ngắt") {
            init {
                templatePresentation.description = "Ngắt kết nối app-server"
            }

            override fun actionPerformed(e: AnActionEvent) = onDisconnect()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = isReady()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    fun newChatAction(isReady: () -> Boolean, onNewChat: () -> Unit): AnAction =
        object : DumbAwareAction("Chat mới", "Tạo cuộc trò chuyện mới", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = onNewChat()

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isReady()
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    fun styleStatusLabel(label: JLabel) {
        label.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        label.font = CodexUiFonts.secondary()
        label.border = JBUI.Borders.empty(0, 6, 0, 8)
    }
}
