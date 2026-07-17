package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.haibachvan.codexintellij.CodexProjectService

class CodexToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<CodexProjectService>()
        val workspace = CodexWorkspacePanel(project, service, ChatPanelModel("panel-a"))

        // Single content — suppress the content-tab strip.
        toolWindow.component.putClientProperty("HideIdLabel", "true")
        // Status + connect controls sit on the title row next to Options / Hide.
        workspace.installHeaderActions(toolWindow)

        val content = ContentFactory.getInstance().createContent(workspace, "", false).apply {
            isCloseable = false
            setDisposer(workspace)
        }
        toolWindow.contentManager.addContent(content)
    }
}
