package dev.haibachvan.codexintellij.platform

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

object IdeActionIds {
    const val ADD_TO_THREAD = "codex.addToThread"
    const val ADD_FILE_TO_THREAD = "codex.addFileToThread"
    const val NEW_CHAT = "codex.newChat"
    const val NEW_CODEX_PANEL = "codex.newCodexPanel"
    const val OPEN_COMMAND_MENU = "codex.openCommandMenu"
    const val OPEN_SIDEBAR = "codex.openSidebar"
    val ALL = listOf(
        ADD_TO_THREAD,
        ADD_FILE_TO_THREAD,
        NEW_CHAT,
        NEW_CODEX_PANEL,
        OPEN_COMMAND_MENU,
        OPEN_SIDEBAR,
    )
}

abstract class CodexIdeAction(private val action: IdeAction) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (action is IdeAction.OpenSidebar) {
            ToolWindowManager.getInstance(project).getToolWindow("Codex")?.activate(null)
        }
        IdeActionBus.publish(action)
    }
}

object IdeActionBus {
    @Volatile
    private var listener: ((IdeAction) -> Unit)? = null

    fun setListener(l: ((IdeAction) -> Unit)?) {
        listener = l
    }

    fun publish(action: IdeAction) {
        listener?.invoke(action)
    }
}

class AddToThreadAction : CodexIdeAction(IdeAction.AddToThread)
class AddFileToThreadAction : CodexIdeAction(IdeAction.AddFileToThread)
class NewChatAction : CodexIdeAction(IdeAction.NewChat)
class NewCodexPanelAction : CodexIdeAction(IdeAction.NewCodexPanel)
class OpenCommandMenuAction : CodexIdeAction(IdeAction.OpenCommandMenu)
class OpenSidebarAction : CodexIdeAction(IdeAction.OpenSidebar)
