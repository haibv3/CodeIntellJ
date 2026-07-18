package dev.haibachvan.codexintellij.ui

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.CodexProjectService
import dev.haibachvan.codexintellij.agents.AgentTreeNode
import dev.haibachvan.codexintellij.agents.AgentTreeProjection
import dev.haibachvan.codexintellij.commands.ComposerSlashExecutor
import dev.haibachvan.codexintellij.session.ConversationController
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.ThreadStatus
import dev.haibachvan.codexintellij.session.TurnFact
import dev.haibachvan.codexintellij.session.TurnStatus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.ListSelectionModel
import javax.swing.Box
import javax.swing.BoxLayout

/**
 * Codex shell matching IDE chat UX: home (tasks + empty state) and active chat with model picker.
 */
class CodexWorkspacePanel(
    private val project: Project,
    private val service: CodexProjectService,
    private val model: ChatPanelModel,
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private val cards = CardLayout()
    private val stack = JPanel(cards)
    private val statusLabel = JBLabel("Chưa kết nối").also { CodexToolWindowHeader.styleStatusLabel(it) }
    private val agentStrip = JBLabel(" ").apply {
        foreground = CodexUiTheme.muted
        font = CodexUiFonts.secondary()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Xem tác nhân / subagent"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (e?.button == MouseEvent.BUTTON1) showAgentsPopup()
            }
        })
    }
    private var latestAgentNodes: List<AgentTreeNode> = emptyList()
    private val chatTitle = JBLabel("Cuộc trò chuyện mới")
    private val titleActions by lazy {
        listOf(
            CodexToolWindowHeader.statusAction(statusLabel),
            CodexToolWindowHeader.connectAction(
                isReady = { lifecycleReady() },
                isStarting = { lifecycleStarting() },
                onConnect = { connect() },
            ),
            CodexToolWindowHeader.disconnectAction(
                isReady = { lifecycleReady() },
                onDisconnect = { disconnect() },
            ),
            CodexToolWindowHeader.newChatAction(
                isReady = { lifecycleReady() },
                onNewChat = { newChat() },
            ),
        )
    }
    private val taskModel = DefaultListModel<TaskRow>()
    private val taskList = JBList(taskModel).apply {
        visibleRowCount = 6
        border = JBUI.Borders.empty(4, 8)
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TaskRowRenderer()
        fixedCellHeight = JBUI.scale(32)
    }
    private var taskPreviewLimit = 12
    /** Guards async task-open resume against out-of-order clicks. */
    private var openTaskGeneration: Long = 0L
    private val chat = CodexChatPanel(project, service, model, embedded = true)
    private val homeComposer = CodexComposerBar(
        model,
        service.modelCatalog(),
        onSend = { sendFromHome() },
        onCancel = { cancelActiveTurn() },
        project = project,
    )
    private val chatComposer = CodexComposerBar(
        model,
        service.modelCatalog(),
        onSend = { sendFromChat() },
        onCancel = { cancelActiveTurn() },
        project = project,
        placeholder = "Yêu cầu những thay đổi tiếp theo",
    )

    private val stateListener = Consumer<NormalizedServerState> { state ->
        SwingUtilities.invokeLater {
            refreshTasks(state)
            refreshAgents(state)
            syncBusyFromState(state)
            syncChatTitle(state)
            chat.renderExternal(state)
            if (model.selectedThread != null) {
                showChat()
            }
        }
    }

    init {
        border = JBUI.Borders.empty()
        stack.add(buildHome(), "home")
        stack.add(buildChatPage(), "chat")
        add(stack, BorderLayout.CENTER)
        service.serverStateStore().addListener(stateListener)
        refreshStatus()
        refreshTasks(service.serverStateStore().snapshot())
        showHome()

        taskList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = taskList.locationToIndex(e.point)
                if (index < 0) return
                val row = taskModel.getElementAt(index) ?: return
                val bounds = taskList.getCellBounds(index, index) ?: return
                val inDelete = e.x >= bounds.x + bounds.width - JBUI.scale(36)
                when {
                    e.button == MouseEvent.BUTTON3 || inDelete -> {
                        e.consume()
                        confirmAndDeleteTask(row)
                    }
                    e.button == MouseEvent.BUTTON1 -> {
                        taskList.selectedIndex = index
                        openTask(row)
                    }
                }
            }
        })
        autoConnect()
    }

    /** Install connection controls on the tool-window title row (next to Options / Hide). */
    fun installHeaderActions(toolWindow: ToolWindow) {
        toolWindow.setTitleActions(titleActions)
    }

    private fun buildHome(): JPanel {
        val page = JPanel(BorderLayout())
        val tasks = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 4, 12)
            add(
                JBLabel("Nhiệm vụ gần đây").apply {
                    font = CodexUiFonts.title()
                    foreground = CodexUiTheme.foreground
                },
                BorderLayout.WEST,
            )
            add(JButton("Xem tất cả").also {
                it.isBorderPainted = false
                it.isContentAreaFilled = false
                it.foreground = CodexUiTheme.accent
                it.font = CodexUiFonts.secondary()
                it.toolTipText = "Xem toàn bộ nhiệm vụ đã lưu"
                it.addActionListener { showAllTasks() }
            }, BorderLayout.EAST)
        }
        val north = JPanel(BorderLayout())
        north.add(tasks, BorderLayout.NORTH)
        north.add(JBScrollPane(taskList).apply { preferredSize = java.awt.Dimension(100, 120) }, BorderLayout.CENTER)
        page.add(north, BorderLayout.NORTH)

        val empty = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(32, 24)
            val mark = JLabel(
                "<html><div style='text-align:center;color:${CodexUiTheme.css(CodexUiTheme.muted)}'>" +
                    "<div style='font-size:22px;font-weight:600;color:${CodexUiTheme.css(CodexUiTheme.foreground)}'>Codex</div>" +
                    "<div style='margin-top:10px;font-size:${CodexUiFonts.BODY_PX}px'>Sẵn sàng hỗ trợ trong IDE</div>" +
                    "<div style='margin-top:6px;font-size:${CodexUiFonts.SECONDARY_PX}px'>" +
                    "Mô tả nhiệm vụ bên dưới hoặc mở một nhiệm vụ gần đây</div>" +
                    "</div></html>",
                SwingConstants.CENTER,
            )
            add(mark, BorderLayout.CENTER)
        }
        page.add(empty, BorderLayout.CENTER)
        page.add(homeComposer, BorderLayout.SOUTH)
        return page
    }

    private fun buildChatPage(): JPanel {
        val page = JPanel(BorderLayout())
        val header = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, CodexUiTheme.cardDivider),
                JBUI.Borders.empty(6, 8, 6, 8),
            )
            add(JButton("←").also {
                it.isBorderPainted = false
                it.isContentAreaFilled = false
                it.font = CodexUiFonts.body()
                it.toolTipText = "Về danh sách nhiệm vụ"
                it.addActionListener {
                    model.selectThread(null)
                    taskList.clearSelection()
                    showHome()
                    refreshTasks(service.serverStateStore().snapshot())
                }
            }, BorderLayout.WEST)
            chatTitle.font = CodexUiFonts.title()
            chatTitle.foreground = CodexUiTheme.foreground
            chatTitle.border = JBUI.Borders.empty(0, 8)
            add(chatTitle, BorderLayout.CENTER)
            add(
                JButton("Copy").also { btn ->
                    btn.isBorderPainted = false
                    btn.isContentAreaFilled = false
                    btn.foreground = CodexUiTheme.muted
                    btn.font = CodexUiFonts.secondary()
                    btn.toolTipText = "Sao chép phản hồi Codex gần nhất"
                    btn.addActionListener {
                        if (!chat.copyLastAgentReply()) {
                            Messages.showInfoMessage(
                                project,
                                "Chưa có phản hồi Codex để sao chép.",
                                "Copy",
                            )
                        } else {
                            btn.text = "Đã copy"
                            btn.foreground = CodexUiTheme.success
                            javax.swing.Timer(1200) {
                                btn.text = "Copy"
                                btn.foreground = CodexUiTheme.muted
                            }.also {
                                it.isRepeats = false
                                it.start()
                            }
                        }
                    }
                },
                BorderLayout.EAST,
            )
        }
        page.add(header, BorderLayout.NORTH)
        page.add(chat, BorderLayout.CENTER)
        val south = JPanel(BorderLayout())
        agentStrip.border = JBUI.Borders.empty(4, 12, 2, 12)
        south.add(agentStrip, BorderLayout.NORTH)
        south.add(chatComposer, BorderLayout.CENTER)
        page.add(south, BorderLayout.SOUTH)
        return page
    }

    private fun showHome() = cards.show(stack, "home")

    private fun showChat() = cards.show(stack, "chat")

    private fun sendFromHome() {
        send(homeComposer) { title ->
            chatTitle.text = title
            showChat()
        }
    }

    private fun sendFromChat() {
        send(chatComposer)
    }

    private fun send(composer: CodexComposerBar, afterStart: ((String) -> Unit)? = null) {
        if (model.isBusy) return
        if (!composer.canSend()) return
        val text = composer.text().trim()
        val attachments = composer.attachments()
        val slash = ComposerSlashExecutor.parseBuiltin(text)
        if (slash != null && attachments.isEmpty()) {
            composer.clear()
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    ensureConnected()
                } catch (_: Exception) {
                    // Status can still render partial local info when offline.
                }
                val result = runCatching {
                    ComposerSlashExecutor.execute(slash, slashContext())
                }.getOrElse {
                    ComposerSlashExecutor.Result.Unavailable(it.message ?: "Slash command failed")
                }
                if (result is ComposerSlashExecutor.Result.StartTurn) {
                    ApplicationManager.getApplication().invokeLater {
                        composer.setText(result.prompt)
                        send(composer, afterStart)
                    }
                    return@executeOnPooledThread
                }
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is ComposerSlashExecutor.Result.Notice ->
                            model.addNotice(result.title, result.bodyMarkdown)
                        is ComposerSlashExecutor.Result.Unavailable ->
                            model.addNotice(slash.name, "Không khả dụng: ${result.reason}")
                        is ComposerSlashExecutor.Result.StartTurn -> Unit
                    }
                    val title = when (result) {
                        is ComposerSlashExecutor.Result.Notice -> result.title
                        is ComposerSlashExecutor.Result.Unavailable -> slash.name
                        is ComposerSlashExecutor.Result.StartTurn -> result.title
                    }
                    afterStart?.invoke(title)
                    homeComposer.syncFromModel()
                    chatComposer.syncFromModel()
                    syncChatTitle(service.serverStateStore().snapshot())
                    showChat()
                    chat.renderExternal(service.serverStateStore().snapshot())
                    refreshTasks(service.serverStateStore().snapshot())
                    refreshStatus()
                    chatComposer.requestFocusComposer()
                }
            }
            return
        }
        val titleSeed = ChatTitles.shortTitle(
            text.ifBlank { attachments.firstOrNull()?.fileName ?: "Tin nhắn" },
        )
        composer.clear()
        setComposersBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureConnected()
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                val cwd = service.projectCwd()?.toString()
                val isNew = model.selectedThread == null
                val thread = model.selectedThread
                    ?: controller.startThread(titleSeed, cwd = cwd).get()
                model.selectThread(thread)
                if (isNew) {
                    model.rememberThreadTitle(thread, titleSeed)
                }
                val turnId = controller.startTurn(
                    thread,
                    text,
                    ConversationController.TurnStartOptions(
                        model = model.selectedModel,
                        effort = model.selectedEffort,
                        approvalPolicy = model.approvalMode.wire,
                        serviceTier = model.selectedServiceTier,
                        personality = model.selectedPersonality,
                        cwd = cwd,
                    ),
                    attachments = attachments.map { it.toWireInput() },
                ).get()
                model.beginTurn(turnId)
                ApplicationManager.getApplication().invokeLater {
                    // Re-read busy: a fast turn may already have completed before this runs.
                    setComposersBusy(model.isBusy)
                    afterStart?.invoke(titleSeed)
                    syncChatTitle(service.serverStateStore().snapshot())
                    showChat()
                    chat.renderExternal(service.serverStateStore().snapshot())
                    refreshTasks(service.serverStateStore().snapshot())
                    chatComposer.requestFocusComposer()
                }
            } catch (ex: Exception) {
                model.endTurn()
                ApplicationManager.getApplication().invokeLater {
                    setComposersBusy(false)
                    Messages.showErrorDialog(project, ex.message ?: "Send failed", "Codex")
                    refreshStatus()
                }
            }
        }
    }

    private fun slashContext() =
        ComposerSlashExecutor.Context(
            service = service,
            threadId = model.selectedThread,
            model = model.selectedModel,
            effort = model.selectedEffort,
            cwd = service.projectCwd()?.toString(),
            state = service.serverStateStore().snapshot(),
            ideContextEnabled = model.ideContextEnabled,
            setIdeContext = { enabled -> model.setIdeContextEnabled(enabled) },
            setModel = { value -> model.setSelectedModel(value) },
            setEffort = { value -> model.setSelectedEffort(value) },
            setPersonality = { value -> model.setSelectedPersonality(value) },
            toggleFast = { model.toggleFastServiceTier() },
            pendingApprovalCount = { service.approvalStateMachine().pending().size },
            approvePending = {
                val req = service.approvalStateMachine().pending().firstOrNull() ?: return@Context false
                runCatching {
                    service.conversationController()?.decideApproval(req, "accept")
                    true
                }.getOrDefault(false)
            },
            onThreadForked = { forked ->
                model.selectThread(forked)
            },
        )

    private fun cancelActiveTurn() {
        val thread = model.selectedThread
        val turn = model.activeTurnId
        if (thread == null || turn == null) {
            model.endTurn()
            setComposersBusy(false)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                controller.interrupt(thread, turn).get()
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, ex.message ?: "Cancel failed", "Codex")
                }
            } finally {
                model.endTurn(turn)
                ApplicationManager.getApplication().invokeLater {
                    setComposersBusy(false)
                    chat.renderExternal(service.serverStateStore().snapshot())
                }
            }
        }
    }

    private fun setComposersBusy(busy: Boolean) {
        homeComposer.setBusy(busy)
        chatComposer.setBusy(busy)
    }

    /** Keep send/cancel button in sync with the active turn (or clear when idle). */
    private fun syncBusyFromState(state: NormalizedServerState) {
        val active = model.activeTurnId
        if (active == null) {
            setComposersBusy(false)
            return
        }
        val fact = state.turns[active]
        if (isTerminalTurn(fact)) {
            model.endTurn(active)
            setComposersBusy(false)
        } else {
            setComposersBusy(true)
        }
    }

    private fun isTerminalTurn(fact: TurnFact?): Boolean =
        fact != null && (
            fact.status == TurnStatus.COMPLETED ||
                fact.status == TurnStatus.FAILED ||
                fact.status == TurnStatus.INTERRUPTED
            )

    private fun openTask(row: TaskRow) {
        val threadId = ThreadId(row.threadId)
        val generation = ++openTaskGeneration
        model.selectThread(threadId)
        model.rememberThreadTitle(threadId, row.title)
        val snapshot = service.serverStateStore().snapshot()
        syncChatTitle(snapshot)
        showChat()
        // Show whatever we already have, then refresh from app-server.
        chat.renderExternal(snapshot)
        val hadLocal = snapshot.items.values.any { it.threadId == threadId }
        if (!hadLocal) {
            chatTitle.text = "Đang tải… · ${row.title}"
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            var loadError: Exception? = null
            try {
                ensureConnected()
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                controller.loadThreadHistory(threadId).get()
            } catch (ex: Exception) {
                loadError = ex
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != openTaskGeneration || model.selectedThread != threadId) {
                    return@invokeLater
                }
                val state = service.serverStateStore().snapshot()
                syncChatTitle(state)
                chat.renderExternal(state)
                refreshTasks(state)
                val loaded = state.items.values.count { it.threadId == threadId }
                when {
                    loaded > 0 -> Unit
                    loadError != null && !hadLocal -> {
                        Messages.showErrorDialog(
                            project,
                            loadError.message ?: "Không mở lại được nhiệm vụ",
                            "Codex",
                        )
                        refreshStatus()
                    }
                    loaded == 0 -> {
                        model.addNotice(
                            "Nhiệm vụ",
                            "Đã mở `${threadId.value}` nhưng chưa có nội dung transcript " +
                                "(thread trống hoặc lịch sử không đọc được).",
                        )
                        chat.renderExternal(service.serverStateStore().snapshot())
                    }
                }
            }
        }
    }

    private fun confirmAndDeleteTask(row: TaskRow) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Xóa nhiệm vụ “${row.title}”? Thao tác không hoàn tác được.",
            "Xóa nhiệm vụ",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        deleteTask(row)
    }

    private fun deleteTask(row: TaskRow) {
        val threadId = ThreadId(row.threadId)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureConnected()
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                controller.deleteThread(threadId).get()
                ApplicationManager.getApplication().invokeLater {
                    if (model.selectedThread == threadId) {
                        model.selectThread(null)
                        showHome()
                    }
                    refreshTasks(service.serverStateStore().snapshot())
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        ex.message ?: "Không xóa được nhiệm vụ",
                        "Codex",
                    )
                }
            }
        }
    }

    private fun showAllTasks() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ensureConnected()
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                val cwd = service.projectCwd()?.toString()
                controller.listThreads(cwd = cwd, limit = 100).get()
                ApplicationManager.getApplication().invokeLater {
                    val state = service.serverStateStore().snapshot()
                    refreshTasks(state, limit = Int.MAX_VALUE)
                    val rows = collectTaskRows(state, limit = Int.MAX_VALUE)
                    TasksBrowserDialog(
                        project = project,
                        rows = rows,
                        onOpen = { openTask(it) },
                        onDelete = { deleteTask(it) },
                    ).show()
                    refreshTasks(service.serverStateStore().snapshot())
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        ex.message ?: "Không tải được danh sách nhiệm vụ",
                        "Codex",
                    )
                }
            }
        }
    }

    private fun newChat() {
        model.selectThread(null)
        taskList.clearSelection()
        homeComposer.clear()
        chatComposer.clear()
        showHome()
        homeComposer.requestFocusComposer()
    }

    private fun ensureConnected() {
        if (service.lifecycleStateName() != "Ready") {
            service.connectAppServer()
        }
        val models = service.modelCatalog().refresh().get()
        SwingUtilities.invokeLater {
            homeComposer.setModels(models)
            chatComposer.setModels(models)
            refreshStatus()
        }
    }

    private fun collectTaskRows(state: NormalizedServerState, limit: Int = taskPreviewLimit): List<TaskRow> =
        state.threads.values
            .filter { it.status != ThreadStatus.ARCHIVED }
            .sortedByDescending { it.arrivalSeq }
            .take(limit)
            .map { thread ->
                TaskRow(
                    threadId = thread.id.value,
                    title = ChatTitles.resolve(state, thread.id, model.rememberedTitle(thread.id)),
                )
            }

    private fun refreshTasks(state: NormalizedServerState, limit: Int = taskPreviewLimit) {
        val selectedId = taskList.selectedValue?.threadId
        taskModel.clear()
        collectTaskRows(state, limit).forEach { taskModel.addElement(it) }
        // Preserve highlight only while browsing home; never block re-open.
        if (model.selectedThread == null && selectedId != null) {
            val idx = (0 until taskModel.size()).firstOrNull {
                taskModel.getElementAt(it).threadId == selectedId
            }
            if (idx != null) {
                taskList.selectedIndex = idx
            }
        } else {
            taskList.clearSelection()
        }
    }

    private fun syncChatTitle(state: NormalizedServerState) {
        val thread = model.selectedThread ?: return
        chatTitle.text = ChatTitles.resolve(state, thread, model.rememberedTitle(thread))
        chatTitle.toolTipText = chatTitle.text
    }

    private fun refreshAgents(state: NormalizedServerState) {
        val nodes = AgentTreeProjection.from(state, model.selectedThread)
        latestAgentNodes = nodes
        agentStrip.text = when {
            nodes.isEmpty() -> " "
            else -> {
                val names = flattenAgentNames(nodes).distinct().take(4)
                val extra = (flattenAgentNames(nodes).distinct().size - names.size).coerceAtLeast(0)
                buildString {
                    append(names.joinToString(" · "))
                    if (extra > 0) append(" · +$extra")
                    append("  ▾")
                }
            }
        }
        agentStrip.isVisible = nodes.isNotEmpty()
        agentStrip.toolTipText = if (nodes.isEmpty()) {
            null
        } else {
            flattenAgentLines(nodes).joinToString("\n") + "\n(nhấn để xem chi tiết)"
        }
    }

    private fun flattenAgentNames(nodes: List<AgentTreeNode>): List<String> =
        nodes.flatMap { listOf(it.agentId) + flattenAgentNames(it.children) }

    private fun flattenAgentLines(nodes: List<AgentTreeNode>, depth: Int = 0): List<String> =
        nodes.flatMap { node ->
            val pad = "  ".repeat(depth)
            val line = buildString {
                append(pad)
                append(node.agentId)
                append(" · ")
                append(node.status)
                node.summary?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(80)) }
            }
            listOf(line) + flattenAgentLines(node.children, depth + 1)
        }

    private fun showAgentsPopup() {
        val nodes = latestAgentNodes
        if (nodes.isEmpty()) return
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10, 12, 12, 12)
            preferredSize = Dimension(JBUI.scale(360), JBUI.scale(220))
            add(JBLabel("Tác nhân / subagent").apply {
                font = CodexUiFonts.body(Font.BOLD)
                border = JBUI.Borders.emptyBottom(8)
            }, BorderLayout.NORTH)
            val body = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                flattenAgentLines(nodes).forEach { line ->
                    add(JBLabel(line).apply {
                        alignmentX = LEFT_ALIGNMENT
                        border = JBUI.Borders.empty(3, 0)
                        foreground = JBColor.foreground()
                    })
                }
                add(Box.createVerticalGlue())
            }
            add(JBScrollPane(body), BorderLayout.CENTER)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setRequestFocus(true)
            .setResizable(true)
            .setTitle("Codex Agents")
            .createPopup()
            .show(RelativePoint.getCenterOf(agentStrip))
    }

    fun refreshStatus() {
        // Keep this EDT-safe: never call compatibilitySnapshot()/revalidate (spawns processes).
        val state = service.lifecycleStateName()
        val cwdName = service.projectCwd()?.fileName?.toString()
        val binaryVersion = service.confirmedBinaryVersion()
        statusLabel.text = when (state) {
            "Ready" -> buildString {
                append("Đã kết nối")
                if (!cwdName.isNullOrBlank()) append(" · ").append(cwdName)
                binaryVersion?.let { append(" · ").append(it) }
            }
            "Starting" -> "Đang kết nối…"
            null, "Stopped" -> "Chưa kết nối"
            else -> state
        }
        statusLabel.toolTipText = service.projectCwd()?.toString()
            ?: "Không xác định được thư mục project"
        // Refresh title-bar action visibility (Kết nối / Ngắt).
        ActivityTracker.getInstance().inc()
    }

    private fun lifecycleReady(): Boolean =
        service.lifecycleStateName() == "Ready"

    private fun lifecycleStarting(): Boolean =
        service.lifecycleStateName() == "Starting"

    private fun autoConnect() {
        statusLabel.text = "Đang kết nối…"
        connect(showErrors = true)
    }

    private fun connect(showErrors: Boolean = true) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.connectAppServer()
                val models = service.modelCatalog().refresh().get()
                val controller = service.conversationController()
                val cwd = service.projectCwd()?.toString()
                controller?.listThreads(cwd = cwd, limit = 24)?.get()
                ApplicationManager.getApplication().invokeLater {
                    homeComposer.setModels(models)
                    chatComposer.setModels(models)
                    refreshStatus()
                    refreshTasks(service.serverStateStore().snapshot())
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    refreshStatus()
                    if (showErrors) {
                        Messages.showErrorDialog(
                            project,
                            ex.message ?: "Connect failed",
                            "Codex",
                        )
                    }
                }
            }
        }
    }

    private fun disconnect() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.disconnectAppServer()
            } finally {
                ApplicationManager.getApplication().invokeLater { refreshStatus() }
            }
        }
    }

    override fun dispose() {
        service.serverStateStore().removeListener(stateListener)
        chat.dispose()
    }

    data class TaskRow(val threadId: String, val title: String) {
        override fun toString(): String = title
    }
}
