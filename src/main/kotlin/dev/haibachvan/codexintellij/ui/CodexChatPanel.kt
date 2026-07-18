package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBHtmlPane
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
import java.awt.KeyboardFocusManager
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private val topSpacer = verticalSpacer()
    private val bottomSpacer = verticalSpacer()
    private val transcriptGlue = Box.createVerticalGlue()
    private val transcriptScroll = JBScrollPane(transcriptHost).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var lastAppliedVersion: List<Pair<String, TranscriptBlockRevision>> = emptyList()
    private data class AppliedSlot(val block: TranscriptBlock, val component: JComponent)
    private val appliedSlots = ArrayList<AppliedSlot>()
    private val heightCache = TranscriptHeightCache()
    private val htmlArtifactCache = TranscriptHtmlArtifactCache()
    private val scrollState = TranscriptScrollState()
    private var latestTranscriptBlocks: List<TranscriptBlock> = emptyList()
    private var currentWindow: TranscriptViewportWindow.Slice? = null
    private var currentWindowSignature: List<Any> = emptyList()
    private var applyingWindow = false
    private var topSpacerHeight = 0
    private var bottomSpacerHeight = 0
    private var disposed = false
    private val newUpdatesLabel = com.intellij.ui.components.JBLabel("Có cập nhật mới").apply {
        foreground = CodexUiTheme.muted
        font = CodexUiFonts.secondary()
        border = JBUI.Borders.empty(2, 12)
        isVisible = false
    }
    /** Coalesce high-frequency streaming updates (~40ms). */
    private var coalesceTimer: Timer? = null

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
    private var stateBridge: UiStateBridge? = null

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

    init {
        border = JBUI.Borders.empty()
        transcriptHost.add(topSpacer)
        transcriptHost.add(bottomSpacer)
        transcriptHost.add(transcriptGlue)
        val north = JPanel(BorderLayout()).apply {
            add(approvalBanner, BorderLayout.NORTH)
            add(contextChips, BorderLayout.CENTER)
            add(newUpdatesLabel, BorderLayout.SOUTH)
        }
        add(north, BorderLayout.NORTH)
        add(transcriptScroll, BorderLayout.CENTER)
        transcriptScroll.verticalScrollBar.addAdjustmentListener { event ->
            val bar = transcriptScroll.verticalScrollBar
            scrollState.onScroll(bar.value, bar.visibleAmount, bar.maximum, event.valueIsAdjusting, currentWindow?.anchor)
            if (disposed || event.valueIsAdjusting || applyingWindow) return@addAdjustmentListener
            val pending = scrollState.consumePending()
            if (pending != null) {
                newUpdatesLabel.isVisible = false
                applyTranscriptBlocks(pending, followLive = scrollState.shouldFollowLive())
            } else if (latestTranscriptBlocks.size > TranscriptViewportWindow.TARGET_MATERIALIZED) {
                applyTranscriptBlocks(latestTranscriptBlocks, followLive = false)
            }
        }
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
            stateBridge = UiStateBridge(
                store = service.serverStateStore(),
                selectedThread = { model.selectedThread },
                activeTurnId = { model.activeTurnId?.value },
                enabledSurfaces = setOf(UiSurface.TRANSCRIPT, UiSurface.BUSY),
            ) { delivery ->
                if (UiSurface.TRANSCRIPT in delivery.surfaces) renderExternal(delivery.state)
                if (UiSurface.BUSY in delivery.surfaces) syncBusy(delivery.state)
            }.also { bridge ->
                bridge.offer(service.serverStateStore().snapshot())
            }
        }
        renderExternal(service.serverStateStore().snapshot())
    }

    fun renderExternal(state: NormalizedServerState) {
        if (disposed) return
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
                                    id = "error:render",
                                    revision = TranscriptBlockRevision(request.generation),
                                ),
                            ),
                            version = listOf("error:render" to TranscriptBlockRevision(request.generation)),
                            runningSectionKeys = emptySet(),
                            sectionKeys = emptySet(),
                        )
                    }
                    val stickToBottom = request.stickToBottom
                    val generation = request.generation
                    SwingUtilities.invokeLater {
                        if (disposed || generation != renderGeneration) return@invokeLater
                        if (prepared.version == lastAppliedVersion) return@invokeLater
                        lastAppliedVersion = prepared.version
                        applyExpandCollapse(prepared.runningSectionKeys)
                        if (transcriptScroll.verticalScrollBar.valueIsAdjusting) {
                            scrollState.defer(prepared.blocks)
                            newUpdatesLabel.isVisible = true
                            approvalBanner.render(service.approvalStateMachine().pending().firstOrNull())
                            contextChips.refresh()
                            return@invokeLater
                        }
                        applyTranscriptBlocks(
                            prepared.blocks,
                            followLive = stickToBottom && isScrolledNearBottom(),
                        )
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
        val version: List<Pair<String, TranscriptBlockRevision>>,
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
            version = blocks.map { it.id to it.revision },
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

    private fun applyTranscriptBlocks(
        blocks: List<TranscriptBlock>,
        followLive: Boolean,
        correctionPass: Int = 0,
    ) {
        if (disposed) return
        val previousBlocks = latestTranscriptBlocks
        val width = CodexUiTheme.transcriptContentWidth(transcriptScroll.viewport.width)
        val widthBucket = (width / HEIGHT_WIDTH_BUCKET_PX) * HEIGHT_WIDTH_BUCKET_PX
        fun estimatedHeight(block: TranscriptBlock): Int =
            heightCache.get(block.id, block.revision, widthBucket) ?: defaultBlockHeight(block)
        val bar = transcriptScroll.verticalScrollBar
        val liveViewportHeight = transcriptScroll.viewport.height.takeIf { it > 0 }
            ?: DEFAULT_VIEWPORT_HEIGHT_PX
        val previousAnchor = if (!followLive && previousBlocks.isNotEmpty()) {
            TranscriptViewportWindow.compute(
                blocks = previousBlocks,
                viewportTop = bar.value,
                viewportHeight = liveViewportHeight,
                heightOf = ::estimatedHeight,
            ).anchor
        } else {
            null
        }
        latestTranscriptBlocks = blocks
        val totalHeight = blocks.fold(0) { total, block ->
            (total.toLong() + estimatedHeight(block)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val viewportHeight = liveViewportHeight
        val viewportTop = if (followLive) {
            (totalHeight - viewportHeight).coerceAtLeast(0)
        } else {
            previousAnchor?.let { anchor ->
                TranscriptViewportWindow.scrollTopForAnchor(blocks, anchor, ::estimatedHeight)
            } ?: bar.value
        }
        val window = TranscriptViewportWindow.compute(
            blocks = blocks,
            viewportTop = viewportTop,
            viewportHeight = viewportHeight,
            heightOf = ::estimatedHeight,
        )
        val signature = buildList<Any> {
            add(window.startIndex)
            add(window.topSpacerHeight)
            add(window.bottomSpacerHeight)
            window.blocks.forEach { add(it.id); add(it.revision) }
        }
        if (signature == currentWindowSignature) return

        applyingWindow = true
        var heightChanged = false
        try {
            val materialized = window.blocks
            val plan = TranscriptBlockReconciler.plan(appliedSlots.map { it.block }, materialized)
            val oldById = appliedSlots.associateBy { it.block.id }
            val reusablePlainHosts = TranscriptComponentHostReconciler.collectReusableEvictions(
                previous = appliedSlots.map { it.block.id to it.component },
                nextIds = materialized.mapTo(HashSet()) { it.id },
                canReuse = {
                    it is TranscriptPlainAgentBlockHost && it.canRecycle(
                        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner,
                    )
                },
            )
            val nextSlots = ArrayList<AppliedSlot>(materialized.size)
            plan.entries.forEach { entry ->
                val prior = oldById[entry.block.id]
                val slot = when (entry.change) {
                    TranscriptBlockReconciler.Change.KEEP -> {
                        checkNotNull(prior)
                        when (val component = prior.component) {
                            is TranscriptHtmlBlockHost -> component.ensureWidth(width)
                            is TranscriptPlainAgentBlockHost -> component.ensureWidth(width)
                        }
                        AppliedSlot(entry.block, prior.component)
                    }
                    TranscriptBlockReconciler.Change.UPDATE -> updateSlot(prior, entry.block, width)
                    TranscriptBlockReconciler.Change.INSERT -> {
                        val recycled = if (
                            entry.block is TranscriptBlock.PlainAgentMessage && reusablePlainHosts.isNotEmpty()
                        ) {
                            reusablePlainHosts.removeFirst() as TranscriptPlainAgentBlockHost
                        } else {
                            null
                        }
                        if (recycled != null) {
                            recycled.updateContent(entry.block as TranscriptBlock.PlainAgentMessage, width)
                            AppliedSlot(entry.block, recycled)
                        } else {
                            AppliedSlot(entry.block, createBlockComponent(entry.block, width))
                        }
                    }
                }
                nextSlots += slot
            }

            TranscriptComponentHostReconciler.apply(
                host = transcriptHost,
                previous = appliedSlots.map { it.component },
                next = nextSlots.map { it.component },
                prefixCount = 1,
                onRemove = ::disposeComponent,
            )
            updateSpacer(topSpacer, window.topSpacerHeight)
            updateSpacer(bottomSpacer, window.bottomSpacerHeight)
            appliedSlots.clear()
            appliedSlots.addAll(nextSlots)
            nextSlots.forEach { slot ->
                val measuredHeight = slot.component.preferredSize.height
                if (measuredHeight != estimatedHeight(slot.block)) {
                    heightCache.put(
                        slot.block.id,
                        slot.block.revision,
                        widthBucket,
                        measuredHeight,
                    )
                    heightChanged = true
                }
            }
            currentWindow = window
            currentWindowSignature = signature
            topSpacerHeight = window.topSpacerHeight
            bottomSpacerHeight = window.bottomSpacerHeight
            transcriptHost.revalidate()
            transcriptHost.repaint()
            transcriptScroll.validate()
            bar.value = if (followLive) bar.maximum else viewportTop
        } finally {
            applyingWindow = false
        }
        if (heightChanged && correctionPass < MAX_HEIGHT_CORRECTION_PASSES) {
            applyTranscriptBlocks(blocks, followLive, correctionPass + 1)
        }
    }

    private fun defaultBlockHeight(block: TranscriptBlock): Int = when (block) {
        is TranscriptBlock.Html -> 72
        is TranscriptBlock.PlainAgentMessage -> CodexUiMetrics.control28 + CodexUiMetrics.space8 * 2
        is TranscriptBlock.CodeFence -> 220
        is TranscriptBlock.ModifiedFiles -> 128
        is TranscriptBlock.AgentChip -> 36
    }

    private fun updateSlot(
        prior: AppliedSlot?,
        block: TranscriptBlock,
        width: Int,
    ): AppliedSlot {
        val component = prior?.component
        if (block is TranscriptBlock.Html && component is TranscriptHtmlBlockHost) {
            component.updateContent(block, width)
            return AppliedSlot(block, component)
        }
        if (block is TranscriptBlock.PlainAgentMessage && component is TranscriptPlainAgentBlockHost) {
            component.updateContent(block, width)
            return AppliedSlot(block, component)
        }

        val replacement = createBlockComponent(block, width)
        return AppliedSlot(block, replacement)
    }

    private fun disposeSlotsFrom(from: Int) {
        while (appliedSlots.size > from) {
            val component = appliedSlots.removeAt(appliedSlots.lastIndex).component
            if (component.parent === transcriptHost) transcriptHost.remove(component)
            disposeComponent(component)
        }
    }

    private fun updateSpacer(spacer: Box.Filler, height: Int) {
        val fixed = Dimension(0, height.coerceAtLeast(0))
        spacer.changeShape(fixed, fixed, Dimension(Integer.MAX_VALUE, fixed.height))
    }

    private fun disposeComponent(component: java.awt.Component) {
        when (component) {
            is CodeFenceCardPanel -> component.dispose()
            is TranscriptHtmlBlockHost -> component.dispose()
            is JBHtmlPane -> component.dispose()
        }
    }

    private fun createBlockComponent(block: TranscriptBlock, width: Int): JComponent =
        when (block) {
            is TranscriptBlock.Html -> {
                TranscriptHtmlBlockHost(
                    artifactCache = htmlArtifactCache,
                    onLink = ::handleTranscriptLink,
                    onRemeasured = {
                        transcriptHost.revalidate()
                        transcriptHost.repaint()
                    },
                ).also { it.updateContent(block, width) }
            }
            is TranscriptBlock.PlainAgentMessage -> {
                TranscriptPlainAgentBlockHost(
                    onCopy = ::copyItemById,
                    onRemeasured = {
                        transcriptHost.revalidate()
                        transcriptHost.repaint()
                    },
                ).also { it.updateContent(block, width) }
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
        disposed = true
        stateBridge?.dispose()
        stateBridge = null
        coalesceTimer?.stop()
        coalesceTimer = null
        pendingRender.set(null)
        latestTranscriptBlocks = emptyList()
        currentWindow = null
        currentWindowSignature = emptyList()
        disposeSlotsFrom(0)
    }

    companion object {
        private const val RENDER_COALESCE_MS = 40
        private const val HEIGHT_WIDTH_BUCKET_PX = 64
        private const val DEFAULT_VIEWPORT_HEIGHT_PX = 600
        private const val MAX_HEIGHT_CORRECTION_PASSES = 2

        private fun verticalSpacer(): Box.Filler = Box.Filler(
            Dimension(0, 0),
            Dimension(0, 0),
            Dimension(Integer.MAX_VALUE, 0),
        )
    }
}
