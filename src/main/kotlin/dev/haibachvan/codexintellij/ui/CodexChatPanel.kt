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
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.TurnId
import dev.haibachvan.codexintellij.session.TurnStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
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
    private data class AppliedSlot(val fingerprint: String, val component: JComponent)
    private val appliedSlots = ArrayList<AppliedSlot>()
    /** Coalesce high-frequency streaming updates (~40ms). */
    private var coalesceTimer: Timer? = null

    private fun newHtmlPane(): JBHtmlPane =
        JBHtmlPane(
            JBHtmlPaneStyleConfiguration.builder()
                .enableInlineCodeBackground(false)
                .enableCodeBlocksBackground(false)
                // Empty = do not bump <code> / <pre> above body size.
                .largeCodeFontSizeSelectors(emptyList())
                .build(),
            JBHtmlPaneConfiguration(),
        ).apply {
            // Drives JBHtmlPane baseFontSize for body/p/li (CSS alone is often overridden).
            font = CodexUiFonts.body()
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

    /** Hosts [JBHtmlPane] with a locked height so BoxLayout cannot overlap following chips. */
    private inner class HtmlBlockHost : JPanel(BorderLayout()) {
        val pane: JBHtmlPane = newHtmlPane()
        private var lockedWidth: Int = 480
        private var lockedHeight: Int = 24
        private var lastLaidOutWidth: Int = -1
        private var remasureScheduled: Boolean = false

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty()
            add(pane, BorderLayout.CENTER)
        }

        fun updateContent(fragment: String, width: Int) {
            val html = HtmlSwingSafe.sanitize(TranscriptRenderer.wrapDocument(fragment))
            HtmlSwingSafe.disableBidi(pane)
            try {
                pane.text = html
            } catch (_: Throwable) {
                pane.text = html.replace(Regex("""</?font\b[^>]*>""", RegexOption.IGNORE_CASE), "")
            }
            HtmlSwingSafe.applyUniformContentFont(pane)
            applyMeasuredWidth(width.coerceAtLeast(120))
        }

        /** Remeasure when the live layout width differs from the width used for HTML wrap. */
        fun ensureWidth(width: Int) {
            val w = width.coerceAtLeast(120)
            if (w == lockedWidth && lockedHeight > 24) return
            applyMeasuredWidth(w)
        }

        private fun applyMeasuredWidth(w: Int) {
            val size = HtmlSwingSafe.applyMeasuredSize(pane, w)
            lockedWidth = w
            lockedHeight = size.height
            lastLaidOutWidth = w
            revalidate()
        }

        override fun doLayout() {
            super.doLayout()
            val w = width
            if (w >= 120 && w != lastLaidOutWidth && !remasureScheduled) {
                remasureScheduled = true
                SwingUtilities.invokeLater {
                    remasureScheduled = false
                    if (!isShowing && parent == null) return@invokeLater
                    val live = width.takeIf { it >= 120 } ?: return@invokeLater
                    if (live == lastLaidOutWidth) return@invokeLater
                    applyMeasuredWidth(live)
                    transcriptHost.revalidate()
                    transcriptHost.repaint()
                }
            }
        }

        override fun getPreferredSize(): Dimension = Dimension(lockedWidth, lockedHeight)

        override fun getMaximumSize(): Dimension = Dimension(Integer.MAX_VALUE, lockedHeight)

        override fun getMinimumSize(): Dimension = Dimension(0, lockedHeight)

        override fun paintChildren(g: java.awt.Graphics) {
            val clip = g.create(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
            try {
                super.paintChildren(clip)
            } finally {
                clip.dispose()
            }
        }

        fun dispose() {
            pane.dispose()
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
    /** Newest pending projection; older requests are dropped while one flight runs. */
    private val pendingRender = AtomicReference<RenderRequest?>(null)
    private val renderInFlight = AtomicBoolean(false)

    private data class RenderRequest(
        val state: NormalizedServerState,
        val thread: ThreadId?,
        val activeTurnId: String?,
        val expandedWhenIdle: Set<String>,
        val collapsedWhileRunning: Set<String>,
        val expandedActivityIds: Set<String>,
        val localNotices: List<ChatPanelModel.LocalNotice>,
        val stickToBottom: Boolean,
        val generation: Long,
        /** Elapsed map built cheaply on EDT from existing timing maps + index on worker. */
        val timingSeedMs: Long,
    )

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
            // Standalone owns its store subscription; embedded chat is fed by the parent panel.
            service.serverStateStore().addListener(stateListener)
        }
        renderExternal(service.serverStateStore().snapshot())
    }

    fun renderExternal(state: NormalizedServerState) {
        latestState = state
        // Keep EDT light: no projectionIndex / markdown here. Coalesce while any turn/item is active
        // (multi-agent floods otherwise freeze the IDE with HTML applies).
        if (needsRenderCoalesce(state)) {
            if (coalesceTimer == null) {
                coalesceTimer = Timer(RENDER_COALESCE_MS) {
                    coalesceTimer = null
                    enqueueTranscriptRender(latestState)
                }.also {
                    it.isRepeats = false
                    it.start()
                }
            }
        } else {
            coalesceTimer?.stop()
            coalesceTimer = null
            enqueueTranscriptRender(state)
        }
    }

    private fun needsRenderCoalesce(state: NormalizedServerState): Boolean {
        if (isStreamingActive(state)) return true
        val thread = model.selectedThread ?: return false
        return state.items.values.any { item ->
            item.threadId == thread &&
                (item.status == ItemStatus.STARTED || item.status == ItemStatus.ACTIVE)
        }
    }

    private fun isStreamingActive(state: NormalizedServerState): Boolean {
        val active = model.activeTurnId ?: return false
        val fact = state.turns[active] ?: return true
        return fact.status != TurnStatus.COMPLETED &&
            fact.status != TurnStatus.FAILED &&
            fact.status != TurnStatus.INTERRUPTED
    }

    private fun enqueueTranscriptRender(state: NormalizedServerState) {
        pendingRender.set(
            RenderRequest(
                state = state,
                thread = model.selectedThread,
                activeTurnId = model.activeTurnId?.value,
                expandedWhenIdle = userExpandedWhenIdle.toSet(),
                collapsedWhileRunning = userCollapsedWhileRunning.toSet(),
                expandedActivityIds = expandedActivityIds.toSet(),
                localNotices = model.notices(),
                stickToBottom = isScrolledNearBottom(),
                generation = ++renderGeneration,
                timingSeedMs = System.currentTimeMillis(),
            ),
        )
        pumpRenderQueue()
    }

    private fun pumpRenderQueue() {
        if (!renderInFlight.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                while (true) {
                    val request = pendingRender.getAndSet(null) ?: break
                    val prepared = try {
                        prepareTranscriptApply(request)
                    } catch (ex: Throwable) {
                        PreparedTranscript(
                            blocks = listOf(
                                TranscriptBlock.Html(
                                    "<p>Không render được transcript: ${ex.message ?: ex.javaClass.simpleName}</p>",
                                ),
                            ),
                            fingerprint = "err-${request.generation}",
                            runningSectionKeys = emptySet(),
                            sectionKeys = emptySet(),
                        )
                    }
                    val stickToBottom = request.stickToBottom
                    val generation = request.generation
                    SwingUtilities.invokeLater {
                        if (generation != renderGeneration) return@invokeLater
                        if (prepared.fingerprint == lastAppliedFingerprint) return@invokeLater
                        lastAppliedFingerprint = prepared.fingerprint
                        applyExpandCollapse(prepared.runningSectionKeys)
                        applyTranscriptBlocks(prepared.blocks)
                        if (stickToBottom) scrollTranscriptToBottom()
                        approvalBanner.render(service.approvalStateMachine().pending().firstOrNull())
                        contextChips.refresh()
                    }
                }
            } finally {
                renderInFlight.set(false)
                if (pendingRender.get() != null) {
                    pumpRenderQueue()
                }
            }
        }
    }

    private data class PreparedTranscript(
        val blocks: List<TranscriptBlock>,
        val fingerprint: String,
        val runningSectionKeys: Set<String>,
        val sectionKeys: Set<String>,
    )

    private fun prepareTranscriptApply(request: RenderRequest): PreparedTranscript {
        val index = TranscriptRenderer.projectionIndex(
            request.state,
            request.thread,
            request.activeTurnId,
        )
        trackReasoningTiming(request.state, index, request.activeTurnId, request.timingSeedMs)
        val expanded = index.sectionKeys.filter { key ->
            if (index.isSectionRunning(key)) {
                key !in request.collapsedWhileRunning
            } else {
                key in request.expandedWhenIdle
            }
        }.toSet()
        val options = TranscriptRenderOptions(
            expandedReasoningIds = expanded,
            expandedActivityIds = request.expandedActivityIds,
            reasoningElapsedSeconds = buildElapsedMap(request.state, index, request.timingSeedMs),
            activeTurnId = request.activeTurnId,
            localNotices = request.localNotices,
            project = project,
            lightweightStreaming = request.activeTurnId != null &&
                request.state.turns[TurnId(request.activeTurnId)].let { fact ->
                    fact == null || (
                        fact.status != TurnStatus.COMPLETED &&
                            fact.status != TurnStatus.FAILED &&
                            fact.status != TurnStatus.INTERRUPTED
                        )
                },
        )
        val blocks = TranscriptRenderer.renderBlocks(request.state, request.thread, options)
        return PreparedTranscript(
            blocks = blocks,
            fingerprint = TranscriptBlock.fingerprints(blocks).joinToString("|"),
            runningSectionKeys = index.runningSectionKeys,
            sectionKeys = index.sectionKeys,
        )
    }

    private fun applyExpandCollapse(runningNow: Set<String>) {
        previouslyRunningSections
            .filter { it !in runningNow }
            .forEach { key ->
                userExpandedWhenIdle.remove(key)
                userCollapsedWhileRunning.remove(key)
            }
        previouslyRunningSections.clear()
        previouslyRunningSections.addAll(runningNow)
    }

    private fun applyTranscriptBlocks(blocks: List<TranscriptBlock>) {
        val width = CodexUiTheme.transcriptContentWidth(transcriptScroll.viewport.width)
        val prints = TranscriptBlock.fingerprints(blocks)

        var shared = 0
        while (shared < appliedSlots.size &&
            shared < blocks.size &&
            appliedSlots[shared].fingerprint == prints[shared]
        ) {
            shared++
        }

        // Prefer in-place HTML update for the first differing slot (typical stream growth).
        if (shared < blocks.size &&
            shared < appliedSlots.size &&
            blocks[shared] is TranscriptBlock.Html &&
            appliedSlots[shared].component is HtmlBlockHost
        ) {
            val host = appliedSlots[shared].component as HtmlBlockHost
            host.updateContent((blocks[shared] as TranscriptBlock.Html).fragment, width)
            appliedSlots[shared] = AppliedSlot(prints[shared], host)
            shared++
            while (shared < appliedSlots.size &&
                shared < blocks.size &&
                appliedSlots[shared].fingerprint == prints[shared]
            ) {
                shared++
            }
        }

        // Shared HTML slots keep fingerprints across width changes — remasure wrap height.
        for (i in 0 until shared) {
            val host = appliedSlots[i].component as? HtmlBlockHost ?: continue
            host.ensureWidth(width)
        }

        disposeSlotsFrom(shared)

        for (i in shared until blocks.size) {
            val component = createBlockComponent(blocks[i], width)
            transcriptHost.add(component)
            appliedSlots += AppliedSlot(prints[i], component)
        }
        transcriptHost.add(Box.createVerticalGlue())
        transcriptHost.revalidate()
        transcriptHost.repaint()
    }

    private fun disposeSlotsFrom(from: Int) {
        while (transcriptHost.componentCount > from) {
            val idx = transcriptHost.componentCount - 1
            val component = transcriptHost.getComponent(idx)
            transcriptHost.remove(idx)
            when (component) {
                is CodeFenceCardPanel -> component.dispose()
                is HtmlBlockHost -> component.dispose()
                is JBHtmlPane -> component.dispose()
            }
        }
        while (appliedSlots.size > from) {
            appliedSlots.removeAt(appliedSlots.lastIndex)
        }
    }

    private fun createBlockComponent(block: TranscriptBlock, width: Int): JComponent =
        when (block) {
            is TranscriptBlock.Html -> {
                HtmlBlockHost().also { it.updateContent(block.fragment, width) }
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
                card
            }
            is TranscriptBlock.CodeFence -> {
                val card = CodeFenceCardPanel(project, block.language, block.code)
                card.doLayout()
                card.maximumSize = Dimension(Integer.MAX_VALUE, card.preferredSize.height)
                card
            }
            is TranscriptBlock.AgentChip -> {
                val chip = AgentChipPanel(block.agentId, block.statusLabel, block.summary)
                chip.doLayout()
                chip.maximumSize = Dimension(Integer.MAX_VALUE, chip.preferredSize.height)
                chip
            }
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
        val index = TranscriptRenderer.projectionIndex(
            latestState,
            model.selectedThread,
            model.activeTurnId?.value,
        )
        val running = index.isSectionRunning(sectionKey)
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

    private fun trackReasoningTiming(
        state: NormalizedServerState,
        index: TranscriptProjectionIndex,
        activeTurnId: String?,
        now: Long,
    ) {
        val active = activeTurnId
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
        index.sectionKeys.forEach { key ->
            if (index.isSectionRunning(key)) {
                reasoningDoneAtMs.remove(key)
            } else {
                reasoningDoneAtMs.putIfAbsent(key, now)
            }
        }
    }

    private fun buildElapsedMap(
        state: NormalizedServerState,
        index: TranscriptProjectionIndex,
        now: Long,
    ): Map<String, Long?> {
        val out = LinkedHashMap<String, Long?>()
        // Per-item elapsed
        index.items.forEach { item ->
            if (item is ItemFact.Reasoning || item is ItemFact.Command || item is ItemFact.Patch) {
                val id = item.id.value
                val start = reasoningSeenAtMs[id] ?: turnStartedAtMs[item.turnId?.value]
                val end = reasoningDoneAtMs[id] ?: now
                out[id] = start?.let { ((end - it) / 1000L).coerceAtLeast(0L) }
            }
        }
        // Per-turn section elapsed
        index.sectionKeys.forEach { key ->
            val start = reasoningSeenAtMs[key]
                ?: key.removePrefix("turn-").takeIf { key.startsWith("turn-") }?.let { turnStartedAtMs[it] }
            val running = index.isSectionRunning(key)
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
        coalesceTimer?.stop()
        coalesceTimer = null
        pendingRender.set(null)
        if (!embedded) {
            service.serverStateStore().removeListener(stateListener)
        }
        disposeSlotsFrom(0)
    }

    companion object {
        private const val RENDER_COALESCE_MS = 40
    }
}
