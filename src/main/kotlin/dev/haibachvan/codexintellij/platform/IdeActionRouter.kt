package dev.haibachvan.codexintellij.platform

sealed class IdeAction {
    data object AddToThread : IdeAction()
    data object AddFileToThread : IdeAction()
    data object NewChat : IdeAction()
    data object NewCodexPanel : IdeAction()
    data object OpenCommandMenu : IdeAction()
    data object OpenSidebar : IdeAction()
}

class IdeActionRouter(
    private val onAction: (IdeAction) -> Unit = {},
) {
    fun route(action: IdeAction) = onAction(action)
}
