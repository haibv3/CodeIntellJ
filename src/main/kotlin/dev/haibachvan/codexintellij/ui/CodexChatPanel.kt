package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.CodexProjectService
import dev.haibachvan.codexintellij.commands.ComposerSlashExecutor
import dev.haibachvan.codexintellij.review.ReviewController
import dev.haibachvan.codexintellij.review.ReviewDelivery
import dev.haibachvan.codexintellij.review.ReviewTarget
import dev.haibachvan.codexintellij.session.ConversationController
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ItemId
import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.TurnStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.util.function.Consumer
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/**
 * Chat transcript surface. When [embedded] is true, parent owns the composer/footer.
 */
class CodexChatPanel(
    private val project: Project,
    private val service: CodexProjectService,
    private val model: ChatPanelModel,
    private val embedded: Boolean = false,
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private val transcriptHost = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
        alignmentX = LEFT_ALIGNMENT
    }
    private val transcriptScroll = JBScrollPane(transcriptHost).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var lastAppliedFingerprint: String? = null

    private fun newHtmlPane(): JBHtmlPane =
        JBHtmlPane(
            JBHtmlPaneStyleConfiguration.builder()
                .enableInlineCodeBackground(true)
                .enableCodeBlocksBackground(false)
                .build(),
            JBHtmlPaneConfiguration(),
        ).apply {
            border = JBUI.Borders.empty(0, 12)
            isEditable = false
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            addHyperlinkListener { event ->
                if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
                val ref = event.description
                    ?: event.url?.toExternalForm()
                    ?: return@addHyperlinkListener
                handleTranscriptLink(ref, event)
            }
        }

    private fun handleTranscriptLink(ref: String, event: HyperlinkEvent) {
        when {
            ref.startsWith("codex-copy:") ->
                copyItemById(ref.removePrefix("codex-copy:"))
            ref.startsWith("codex-copy-code:") ->
                copyCodeBlock(ref.removePrefix("codex-copy-code:"))
            ref.startsWith("codex-toggle-reasoning:") ->
                toggleReasoning(ref.removePrefix("codex-toggle-reasoning:"))
            ref.startsWith("codex-toggle-activity:") ->
                toggleActivity(ref.removePrefix("codex-toggle-activity:"))
            ref.startsWith("codex-open:") ->
                openFile(ref.removePrefix("codex-open:"))
            FileLinkSupport.looksLikeLocalFileHref(ref) ->
                openFile(ref)
            else -> {
                val fromUrl = event.url?.path ?: event.url?.toExternalForm()
                if (!fromUrl.isNullOrBlank() && FileLinkSupport.looksLikeLocalFileHref(fromUrl)) {
                    openFile(fromUrl)
                }
            }
        }
    }
    private val contextChips = ContextChipsPanel(service.contextAttachmentStore())
    private val approvalBanner = ApprovalBanner(
        onAccept = { req -> decide(req, "accept") },
        onReject = { req -> decide(req, "reject") },
    )
    private var composer: CodexComposerBar? = null
    private var latestState: NormalizedServerState = service.serverStateStore().snapshot()
    /** User opened a finished thinking block. */
    private val userExpandedWhenIdle = linkedSetOf<String>()
    /** User closed a currently-running thinking block. */
    private val userCollapsedWhileRunning = linkedSetOf<String>()
    private val expandedActivityIds = linkedSetOf<String>()
    private val reasoningSeenAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val reasoningDoneAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val turnStartedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var trackedActiveTurn: String? = null
    private val previouslyRunningSections = HashSet<String>()
    /** Drop stale async renders when state updates faster than HTML build. */
    private var renderGeneration: Long = 0L

    private val stateListener = Consumer<NormalizedServerState> { state ->
        SwingUtilities.invokeLater {
            renderExternal(state)
            syncBusy(state)
        }
    }

    init {
        border = JBUI.Borders.empty()
        val north = JPanel(BorderLayout()).apply {
            add(approvalBanner, BorderLayout.NORTH)
            add(contextChips, BorderLayout.CENTER)
        }
        add(north, BorderLayout.NORTH)
        add(transcriptScroll, BorderLayout.CENTER)
        if (!embedded) {
            val bar = CodexComposerBar(
                model,
                service.modelCatalog(),
                onSend = { sendStandalone() },
                onCancel = { cancelStandalone() },
                project = project,
            )
            composer = bar
            add(bar, BorderLayout.SOUTH)
        }
        service.serverStateStore().addListener(stateListener)
        renderExternal(service.serverStateStore().snapshot())
    }

    fun renderExternal(state: NormalizedServerState) {
        latestState = state
        trackReasoningTiming(state)
        syncAutoExpandCollapse(state)
        val expanded = effectiveExpandedSections(state)
        val options = TranscriptRenderOptions(
            expandedReasoningIds = expanded,
            expandedActivityIds = expandedActivityIds.toSet(),
            reasoningElapsedSeconds = buildElapsedMap(state),
            activeTurnId = model.activeTurnId?.value,
            localNotices = model.notices(),
            project = project,
        )
        val thread = model.selectedThread
        val generation = ++renderGeneration
        val stickToBottom = isScrolledNearBottom()
        ApplicationManager.getApplication().executeOnPooledThread {
            val blocks = try {
                TranscriptRenderer.renderBlocks(state, thread, options)
            } catch (ex: Throwable) {
                listOf(
                    TranscriptBlock.Html(
                        "<p>Không render được transcript: ${ex.message ?: ex.javaClass.simpleName}</p>",
                    ),
                )
            }
            val fingerprint = blocksFingerprint(blocks)
            SwingUtilities.invokeLater {
                if (generation != renderGeneration) return@invokeLater
                if (fingerprint == lastAppliedFingerprint) return@invokeLater
                lastAppliedFingerprint = fingerprint
                applyTranscriptBlocks(blocks)
                if (stickToBottom) scrollTranscriptToBottom()
                approvalBanner.render(service.approvalStateMachine().pending().firstOrNull())
                contextChips.refresh()
            }
        }
    }

    private fun blocksFingerprint(blocks: List<TranscriptBlock>): String =
        blocks.joinToString("|") { block ->
            when (block) {
                is TranscriptBlock.Html -> "h:${block.fragment.hashCode()}"
                is TranscriptBlock.ModifiedFiles ->
                    "f:${block.payload.threadId}:${block.payload.turnId}:${
                        block.payload.files.joinToString { "${it.path}:${it.counts.added}:${it.counts.removed}" }
                    }"
                is TranscriptBlock.CodeFence ->
                    "c:${block.language}:${block.code.hashCode()}"
            }
        }

    private fun applyTranscriptBlocks(blocks: List<TranscriptBlock>) {
        transcriptHost.removeAll()
        val width = transcriptScroll.viewport.width.coerceAtLeast(480)
        for (block in blocks) {
            when (block) {
                is TranscriptBlock.Html -> {
                    val pane = newHtmlPane()
                    val html = HtmlSwingSafe.sanitize(TranscriptRenderer.wrapDocument(block.fragment))
                    HtmlSwingSafe.disableBidi(pane)
                    try {
                        pane.text = html
                    } catch (_: Throwable) {
                        pane.text = html.replace(Regex("""</?font\b[^>]*>""", RegexOption.IGNORE_CASE), "")
                    }
                    pane.setSize(width, Short.MAX_VALUE.toInt())
                    val pref = pane.preferredSize
                    pane.preferredSize = Dimension(width, pref.height.coerceAtLeast(24))
                    pane.maximumSize = Dimension(Integer.MAX_VALUE, pane.preferredSize.height)
                    transcriptHost.add(pane)
                }
                is TranscriptBlock.ModifiedFiles -> {
                    val card = ModifiedFilesCardPanel(
                        project = project,
                        payload = block.payload,
                        onUndo = { undoPayload(it) },
                        onReview = { reviewPayload(it) },
                        onOpenFile = { openFile(it) },
                    )
                    card.maximumSize = Dimension(Integer.MAX_VALUE, card.preferredSize.height)
                    transcriptHost.add(card)
                }
                is TranscriptBlock.CodeFence -> {
                    val card = CodeFenceCardPanel(project, block.language, block.code)
                    transcriptHost.add(card)
                    // Measure after attach so editor lineHeight is real (avoids top-line clip).
                    card.doLayout()
                    card.maximumSize = Dimension(Integer.MAX_VALUE, card.preferredSize.height)
                }
            }
        }
        transcriptHost.add(Box.createVerticalGlue())
        transcriptHost.revalidate()
        transcriptHost.repaint()
    }

    private fun isScrolledNearBottom(): Boolean {
        val bar = transcriptScroll.verticalScrollBar
        return bar.maximum - (bar.value + bar.visibleAmount) < 80
    }

    private fun scrollTranscriptToBottom() {
        SwingUtilities.invokeLater {
            val bar = transcriptScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    fun copyLastAgentReply(): Boolean {
        val text = TranscriptRenderer.lastAgentText(latestState, model.selectedThread) ?: return false
        copyText(text)
        return true
    }

    private fun toggleReasoning(sectionKey: String) {
        val running = isSectionRunning(latestState, sectionKey)
        if (running) {
            if (!userCollapsedWhileRunning.add(sectionKey)) {
                userCollapsedWhileRunning.remove(sectionKey)
            }
        } else {
            if (!userExpandedWhenIdle.add(sectionKey)) {
                userExpandedWhenIdle.remove(sectionKey)
            }
        }
        renderExternal(latestState)
    }

    private fun toggleActivity(itemId: String) {
        if (!expandedActivityIds.add(itemId)) {
            expandedActivityIds.remove(itemId)
        }
        renderExternal(latestState)
    }

    /**
     * While thinking: expand by default (unless user collapsed).
     * After done: collapse by default (unless user expanded).
     */
    private fun effectiveExpandedSections(state: NormalizedServerState): Set<String> {
        val keys = activitySectionKeys(state)
        return keys.filter { key ->
            if (isSectionRunning(state, key)) {
                key !in userCollapsedWhileRunning
            } else {
                key in userExpandedWhenIdle
            }
        }.toSet()
    }

    private fun syncAutoExpandCollapse(state: NormalizedServerState) {
        val keys = activitySectionKeys(state)
        val runningNow = keys.filter { isSectionRunning(state, it) }.toSet()
        // Just finished → force collapse (clear idle pin + running pin).
        previouslyRunningSections
            .filter { it !in runningNow }
            .forEach { key ->
                userExpandedWhenIdle.remove(key)
                userCollapsedWhileRunning.remove(key)
            }
        previouslyRunningSections.clear()
        previouslyRunningSections.addAll(runningNow)
    }

    private fun activitySectionKeys(state: NormalizedServerState): Set<String> {
        val thread = model.selectedThread ?: return emptySet()
        val items = state.items.values
            .filter { it.threadId == thread }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        val activeTurn = model.activeTurnId?.value
        return items
            .filter { item ->
                item is ItemFact.Reasoning ||
                    item is ItemFact.Command ||
                    item is ItemFact.Patch ||
                    item is ItemFact.Subagent ||
                    (item is ItemFact.AgentMessage &&
                        TranscriptRenderer.isInterimAgentMessage(item, items, activeTurn))
            }
            .map { TranscriptRenderer.activitySectionKey(listOf(it)) }
            .toSet()
    }

    private fun isSectionRunning(state: NormalizedServerState, sectionKey: String): Boolean {
        val thread = model.selectedThread ?: return false
        val items = state.items.values
            .filter { it.threadId == thread }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        val activeTurn = model.activeTurnId?.value
        val turnId = sectionKey.removePrefix("turn-").takeIf { sectionKey.startsWith("turn-") }
        if (turnId != null && activeTurn == turnId) return true
        return items.any { item ->
            val inSection =
                (item is ItemFact.Reasoning ||
                    item is ItemFact.Command ||
                    item is ItemFact.Patch ||
                    item is ItemFact.Subagent ||
                    (item is ItemFact.AgentMessage &&
                        TranscriptRenderer.isInterimAgentMessage(item, items, activeTurn))) &&
                    TranscriptRenderer.activitySectionKey(listOf(item)) == sectionKey
            inSection && (item.status == ItemStatus.STARTED || item.status == ItemStatus.ACTIVE)
        }
    }

    private fun openFile(path: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                if (!FileLinkSupport.open(project, path)) {
                    Messages.showInfoMessage(project, "Không tìm thấy tệp:\n$path", "Codex")
                }
            } catch (ex: Exception) {
                Messages.showErrorDialog(project, ex.message ?: "Open file failed", "Codex")
            }
        }
    }

    private fun trackReasoningTiming(state: NormalizedServerState) {
        val now = System.currentTimeMillis()
        val active = model.activeTurnId?.value
        if (active != null && active != trackedActiveTurn) {
            turnStartedAtMs.putIfAbsent(active, now)
            trackedActiveTurn = active
        }
        if (active == null && trackedActiveTurn != null) {
            val endedTurn = trackedActiveTurn!!
            trackedActiveTurn = null
            reasoningDoneAtMs.putIfAbsent("turn-$endedTurn", now)
            state.items.values
                .filter { it.turnId?.value == endedTurn }
                .forEach { reasoningDoneAtMs.putIfAbsent(it.id.value, now) }
        }
        state.items.values.forEach { item ->
            if (item is ItemFact.Reasoning ||
                item is ItemFact.Command ||
                item is ItemFact.Patch ||
                item is ItemFact.Subagent
            ) {
                val id = item.id.value
                val turnKey = item.turnId?.value?.let { "turn-$it" }
                val start = turnStartedAtMs[item.turnId?.value]
                reasoningSeenAtMs.putIfAbsent(id, start ?: now)
                if (turnKey != null) {
                    reasoningSeenAtMs.putIfAbsent(turnKey, start ?: now)
                }
                val terminal = item.status == ItemStatus.COMPLETED ||
                    item.status == ItemStatus.FAILED ||
                    item.status == ItemStatus.INTERRUPTED
                if (terminal) {
                    reasoningDoneAtMs.putIfAbsent(id, now)
                }
            }
        }
        // Freeze turn elapsed when activity stops; clear if it resumes (new command/etc).
        activitySectionKeys(state).forEach { key ->
            if (isSectionRunning(state, key)) {
                reasoningDoneAtMs.remove(key)
            } else {
                reasoningDoneAtMs.putIfAbsent(key, now)
            }
        }
    }

    private fun buildElapsedMap(state: NormalizedServerState): Map<String, Long?> {
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, Long?>()
        val threadItems = state.items.values
            .filter { it.threadId == model.selectedThread }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        // Per-item elapsed
        threadItems.forEach { item ->
            if (item is ItemFact.Reasoning || item is ItemFact.Command || item is ItemFact.Patch) {
                val id = item.id.value
                val start = reasoningSeenAtMs[id] ?: turnStartedAtMs[item.turnId?.value]
                val end = reasoningDoneAtMs[id] ?: now
                out[id] = start?.let { ((end - it) / 1000L).coerceAtLeast(0L) }
            }
        }
        // Per-turn section elapsed
        activitySectionKeys(state).forEach { key ->
            val start = reasoningSeenAtMs[key]
                ?: key.removePrefix("turn-").takeIf { key.startsWith("turn-") }?.let { turnStartedAtMs[it] }
            val running = isSectionRunning(state, key)
            val end = if (running) now else (reasoningDoneAtMs[key] ?: now)
            out[key] = start?.let { ((end - it) / 1000L).coerceAtLeast(0L) }
        }
        return out
    }

    private fun copyItemById(itemId: String) {
        val item = latestState.items[ItemId(itemId)] ?: return
        val text = TranscriptRenderer.plainTextForCopy(item) ?: return
        copyText(text)
    }

    private fun copyCodeBlock(id: String) {
        val text = CodeBlockClipboard.get(id) ?: return
        copyText(text)
    }

    private fun undoPayload(payload: ModifiedFilesActions.Payload) {
        if (payload.files.isEmpty()) return
        val confirm = Messages.showYesNoDialog(
            project,
            "Hoàn tác ${payload.files.size} tệp đã chỉnh sửa trong lượt này?",
            "Codex · Hoàn tác",
            "Hoàn tác",
            "Hủy",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { PatchFileUndo.undo(project, payload.files) }
                .getOrElse {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            it.message ?: "Hoàn tác thất bại",
                            "Codex · Hoàn tác",
                        )
                    }
                    return@executeOnPooledThread
                }
            ApplicationManager.getApplication().invokeLater {
                val body = buildString {
                    if (result.reverted.isNotEmpty()) {
                        append("Đã hoàn tác ${result.reverted.size} tệp.")
                    }
                    if (result.skipped.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append("Bỏ qua ${result.skipped.size} tệp (không đủ diff/baseline).")
                    }
                    if (result.errors.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(result.errors.take(3).joinToString("\n"))
                    }
                }.ifBlank { "Không hoàn tác được tệp nào." }
                Messages.showInfoMessage(project, body, "Codex · Hoàn tác")
            }
        }
    }

    private fun reviewPayload(payload: ModifiedFilesActions.Payload) {
        if (payload.files.isEmpty()) return
        CodexNativeDiff.open(project, payload.files)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val gw = service.gateway() ?: return@executeOnPooledThread
                ReviewController(gw).start(
                    ReviewTarget(payload.threadId, payload.turnId),
                    ReviewDelivery.PANEL,
                ).get()
            }
        }
    }

    private fun copyText(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun sendStandalone() {
        val bar = composer ?: return
        if (model.isBusy) return
        if (!bar.canSend()) return
        val text = bar.text().trim()
        val attachments = bar.attachments()
        val slash = ComposerSlashExecutor.parseBuiltin(text)
        if (slash != null && attachments.isEmpty()) {
            bar.clear()
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching {
                    ComposerSlashExecutor.execute(slash, slashContext())
                }.getOrElse {
                    ComposerSlashExecutor.Result.Unavailable(it.message ?: "Slash command failed")
                }
                if (result is ComposerSlashExecutor.Result.StartTurn) {
                    ApplicationManager.getApplication().invokeLater {
                        bar.setText(result.prompt)
                        sendStandalone()
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
                    bar.syncFromModel()
                    renderExternal(service.serverStateStore().snapshot())
                }
            }
            return
        }
        val titleSeed = text.ifBlank { attachments.firstOrNull()?.fileName ?: "Tin nhắn" }
        bar.clear()
        bar.setBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                val cwd = service.projectCwd()?.toString()
                val thread = model.selectedThread
                    ?: controller.startThread(titleSeed.take(48), cwd = cwd).get()
                model.selectThread(thread)
                model.rememberThreadTitle(thread, titleSeed)
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
                    bar.setBusy(model.isBusy)
                    renderExternal(service.serverStateStore().snapshot())
                }
            } catch (ex: Exception) {
                model.endTurn()
                ApplicationManager.getApplication().invokeLater {
                    bar.setBusy(false)
                    Messages.showErrorDialog(project, ex.message ?: "Send failed", "Codex Chat")
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
            setIdeContext = { model.setIdeContextEnabled(it) },
            setModel = { model.setSelectedModel(it) },
            setEffort = { model.setSelectedEffort(it) },
            setPersonality = { model.setSelectedPersonality(it) },
            toggleFast = { model.toggleFastServiceTier() },
            pendingApprovalCount = { service.approvalStateMachine().pending().size },
            approvePending = {
                val req = service.approvalStateMachine().pending().firstOrNull() ?: return@Context false
                runCatching {
                    service.conversationController()?.decideApproval(req, "accept")
                    true
                }.getOrDefault(false)
            },
            onThreadForked = { model.selectThread(it) },
        )

    private fun cancelStandalone() {
        val bar = composer ?: return
        val thread = model.selectedThread
        val turn = model.activeTurnId
        if (thread == null || turn == null) {
            model.endTurn()
            bar.setBusy(false)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                controller.interrupt(thread, turn).get()
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, ex.message ?: "Cancel failed", "Codex Chat")
                }
            } finally {
                model.endTurn(turn)
                ApplicationManager.getApplication().invokeLater {
                    bar.setBusy(false)
                    renderExternal(service.serverStateStore().snapshot())
                }
            }
        }
    }

    private fun syncBusy(state: NormalizedServerState) {
        val active = model.activeTurnId
        if (active == null) {
            composer?.setBusy(false)
            return
        }
        val fact = state.turns[active]
        val terminal = fact != null && (
            fact.status == TurnStatus.COMPLETED ||
                fact.status == TurnStatus.FAILED ||
                fact.status == TurnStatus.INTERRUPTED
            )
        if (terminal) {
            model.endTurn(active)
            composer?.setBusy(false)
        } else {
            composer?.setBusy(true)
        }
    }

    private fun decide(request: dev.haibachvan.codexintellij.session.ApprovalRequest, decision: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val controller = service.conversationController()
                    ?: error("Connect app-server first")
                controller.decideApproval(request, decision)
                ApplicationManager.getApplication().invokeLater {
                    approvalBanner.render(service.approvalStateMachine().pending().firstOrNull())
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, ex.message ?: "Approval failed", "Codex Approval")
                }
            }
        }
    }

    override fun dispose() {
        service.serverStateStore().removeListener(stateListener)
        transcriptHost.components.filterIsInstance<JBHtmlPane>().forEach { it.dispose() }
    }
}
