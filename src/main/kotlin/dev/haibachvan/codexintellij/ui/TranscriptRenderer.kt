package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.project.Project
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.resolvedChanges
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** UI options for transcript HTML (expand/collapse, timing labels). */
data class TranscriptRenderOptions(
    /** Expanded turn-activity / thinking sections (section keys). */
    val expandedReasoningIds: Set<String> = emptySet(),
    /** Expanded nested activity rows (command/patch item ids). */
    val expandedActivityIds: Set<String> = emptySet(),
    /** Elapsed seconds keyed by section key or reasoning item id. */
    val reasoningElapsedSeconds: Map<String, Long?> = emptyMap(),
    /** Active turn id — while set, agent progress notes stay inside the thinking section. */
    val activeTurnId: String? = null,
    /** Client-only slash-command / status notices appended after server items. */
    val localNotices: List<ChatPanelModel.LocalNotice> = emptyList(),
    /** Needed for IDE lexer-based fence coloring. */
    val project: Project? = null,
    /**
     * Skip expensive IDE lexer fence coloring while the active agent message streams.
     * Completed messages keep full fidelity.
     */
    val lightweightStreaming: Boolean = false,
)

/** Precomputed thread classification for O(n) transcript projection and EDT queries. */
data class TranscriptProjectionIndex(
    val items: List<ItemFact>,
    val interimAgentIds: Set<String>,
    val activityByKey: Map<String, List<ItemFact>>,
    val sectionKeys: Set<String>,
    val runningSectionKeys: Set<String>,
) {
    fun isInterim(item: ItemFact.AgentMessage): Boolean = item.id.value in interimAgentIds

    fun activityItems(key: String): List<ItemFact> = activityByKey[key].orEmpty()

    fun isSectionRunning(key: String): Boolean = key in runningSectionKeys
}

/** Renders normalized server state as themed HTML matching Codex IDE chat layout. */
object TranscriptRenderer {
    /** Must be a regular (non-raw) string — `"""\u29C9"""` stays literal in Kotlin. */
    private const val COPY_GLYPH = "\u29C9"

    /** Item visits during the last [projectionIndex] build (tests assert near-linear work). */
    @Volatile
    var lastProjectionItemVisits: Int = 0
        internal set

    private val markdownFlavour = GFMFlavourDescriptor()

    private val FILE_EXT = Regex(
        """(?i)\.(md|txt|kt|kts|java|xml|gradle|properties|json|ya?ml|toml|rs|go|py|ts|tsx|js|jsx|css|html|sh|c|cpp|h|hpp)$""",
    )

    fun render(
        state: NormalizedServerState,
        threadId: ThreadId?,
        options: TranscriptRenderOptions = TranscriptRenderOptions(),
    ): String {
        if (threadId == null && options.localNotices.isEmpty()) {
            return document(
                """
                <div class="empty">
                  <p>Bắt đầu cuộc trò chuyện — nhập tin nhắn bên dưới.</p>
                </div>
                """.trimIndent(),
            )
        }
        val blocks = renderBlocks(state, threadId, options)
        val htmlBody = blocks.joinToString("") { block ->
            when (block) {
                is TranscriptBlock.Html -> block.fragment
                is TranscriptBlock.PlainAgentMessage -> agentBlock(block.itemId, block.text, options)
                else -> ""
            }
        }
        return document(htmlBody.ifBlank { """<div class="empty"><p class="muted">Chưa có tin nhắn.</p></div>""" })
    }

    /** Ordered blocks for Swing host (native cards + HTML fragments). */
    fun renderBlocks(
        state: NormalizedServerState,
        threadId: ThreadId?,
        options: TranscriptRenderOptions = TranscriptRenderOptions(),
    ): List<TranscriptBlock> {
        if (threadId == null && options.localNotices.isEmpty()) {
            return listOf(
                TranscriptBlock.Html(
                    """
                    <div class="empty">
                      <p>Bắt đầu cuộc trò chuyện — nhập tin nhắn bên dưới.</p>
                    </div>
                    """.trimIndent(),
                    id = "empty:new-conversation",
                    revision = TranscriptBlockRevision(),
                ),
            )
        }
        val index = projectionIndex(state, threadId, options.activeTurnId)
        val items = index.items
        if (items.isEmpty() && options.localNotices.isEmpty()) {
            return listOf(
                TranscriptBlock.Html(
                    """
                    <div class="empty">
                      <p class="muted">Chưa có tin nhắn.</p>
                    </div>
                    """.trimIndent(),
                    id = "empty:${threadId?.value ?: "none"}",
                    revision = TranscriptBlockRevision(state.lastArrivalSeq),
                ),
            )
        }
        val blocks = ArrayList<TranscriptBlock>()
        fun emitHtml(id: String, revision: TranscriptBlockRevision, fragment: String) {
            blocks += TranscriptBlock.Html(fragment, id, revision)
        }
        val emittedActivityKeys = HashSet<String>()
        val emittedPatchKeys = HashSet<String>()
        val emittedAgentChipIds = HashSet<String>()
        fun emitModifiedFiles(sectionItems: List<ItemFact>) {
            val key = activitySectionKey(sectionItems)
            if (!emittedPatchKeys.add(key)) return
            modifiedFilesPayload(sectionItems)?.let { payload ->
                blocks += TranscriptBlock.ModifiedFiles(
                    payload = payload,
                    id = "files:$key",
                    revision = sectionRevision(sectionItems, options, "files"),
                )
            }
        }
        fun emitAgentChips(sectionItems: List<ItemFact>) {
            for (sub in sectionItems.filterIsInstance<ItemFact.Subagent>()) {
                if (!emittedAgentChipIds.add(sub.id.value)) continue
                blocks += TranscriptBlock.AgentChip(
                    agentId = sub.fact.agentId,
                    statusLabel = agentStatusLabel(sub.fact.status),
                    summary = sub.fact.summary,
                    id = "agent:${sub.threadId.value}:${sub.id.value}",
                    revision = TranscriptBlockRevision(
                        sourceVersion = sub.arrivalSeq,
                        viewVersion = "${sub.fact.status}:${sub.fact.summary.orEmpty()}",
                    ),
                )
            }
        }
        for (item in items) {
            when (item) {
                is ItemFact.UserMessage -> emitHtml(
                    id = "item:${item.threadId.value}:${item.id.value}:user",
                    revision = TranscriptBlockRevision(
                        sourceVersion = item.arrivalSeq,
                        viewVersion = item.text,
                    ),
                    fragment = userBlock(item.text),
                )
                is ItemFact.AgentMessage -> {
                    if (index.isInterim(item)) {
                        val key = activitySectionKey(listOf(item))
                        if (emittedActivityKeys.add(key)) {
                            val sectionItems = index.activityItems(key)
                            emitHtml(
                                id = "activity:$key",
                                revision = sectionRevision(sectionItems, options, "activity"),
                                fragment = activitySection(sectionItems, options),
                            )
                            emitAgentChips(sectionItems)
                        }
                    } else {
                        for (block in expandAgentMessage(item, options)) {
                            blocks += block
                        }
                        val key = activitySectionKey(listOf(item))
                        val sectionItems = index.activityItems(key)
                        emitModifiedFiles(sectionItems)
                        emitAgentChips(sectionItems)
                    }
                }
                is ItemFact.ApprovalReference -> emitHtml(
                    id = "item:${item.threadId.value}:${item.id.value}:approval",
                    revision = TranscriptBlockRevision(item.arrivalSeq),
                    fragment = metaBlock(
                        "Approval",
                        "<p><code>${escape(item.requestId)}</code> · ${escape(item.status.name)}</p>",
                    ),
                )
                is ItemFact.Unknown -> emitHtml(
                    id = "item:${item.threadId.value}:${item.id.value}:unknown",
                    revision = TranscriptBlockRevision(item.arrivalSeq),
                    fragment = metaBlock(
                        "Unknown (${escape(item.type)})",
                        "<p class=\"muted\">${escape(item.status.name)}</p>",
                    ),
                )
                is ItemFact.Reasoning,
                is ItemFact.Command,
                is ItemFact.Patch,
                is ItemFact.Subagent,
                -> {
                    val key = activitySectionKey(listOf(item))
                    if (!emittedActivityKeys.add(key)) continue
                    val sectionItems = index.activityItems(key)
                    emitHtml(
                        id = "activity:$key",
                        revision = sectionRevision(sectionItems, options, "activity"),
                        fragment = activitySection(sectionItems, options),
                    )
                    emitAgentChips(sectionItems)
                }
            }
        }
        for (notice in options.localNotices) {
            emitHtml(
                id = "notice:${notice.id}",
                revision = TranscriptBlockRevision(
                    viewVersion = "${notice.title}\u0000${notice.bodyMarkdown}",
                ),
                fragment = metaBlock(
                    notice.title,
                    markdownBody(notice.bodyMarkdown, options),
                ),
            )
        }
        for (key in index.sectionKeys) {
            if (key in emittedPatchKeys) continue
            emitModifiedFiles(index.activityItems(key))
        }
        // Any subagent not yet shown (edge ordering).
        for (sub in items.filterIsInstance<ItemFact.Subagent>()) {
            if (!emittedAgentChipIds.add(sub.id.value)) continue
            blocks += TranscriptBlock.AgentChip(
                agentId = sub.fact.agentId,
                statusLabel = agentStatusLabel(sub.fact.status),
                summary = sub.fact.summary,
                id = "agent:${sub.threadId.value}:${sub.id.value}",
                revision = TranscriptBlockRevision(
                    sourceVersion = sub.arrivalSeq,
                    viewVersion = "${sub.fact.status}:${sub.fact.summary.orEmpty()}",
                ),
            )
        }
        require(blocks.map { it.id }.toSet().size == blocks.size) {
            "Transcript renderer emitted duplicate semantic block ids"
        }
        return blocks
    }

    private fun sectionRevision(
        items: List<ItemFact>,
        options: TranscriptRenderOptions,
        kind: String,
    ): TranscriptBlockRevision {
        val key = activitySectionKey(items)
        val sourceVersion = items.maxOfOrNull { it.arrivalSeq } ?: 0L
        val expandedActivities = items.map { it.id.value }
            .filter { it in options.expandedActivityIds }
            .sorted()
        val viewVersion = buildString {
            append(kind).append('|')
            append(key in options.expandedReasoningIds).append('|')
            append(options.reasoningElapsedSeconds[key]).append('|')
            append(expandedActivities.joinToString(",")).append('|')
            items.forEach { item ->
                append(item.id.value).append(':').append(item.status).append(':')
                // Text must participate: status/arrival alone can miss in-place delta updates
                // when coalescing reuses watermarks, leaving a blank/stale thinking body.
                append(sectionItemContentFingerprint(item)).append(';')
            }
        }
        return TranscriptBlockRevision(sourceVersion, viewVersion)
    }

    private fun sectionItemContentFingerprint(item: ItemFact): Int = when (item) {
        is ItemFact.AgentMessage -> item.text.hashCode()
        is ItemFact.Reasoning -> item.text.hashCode()
        is ItemFact.Command -> (31 * item.command.hashCode()) + item.output.hashCode()
        is ItemFact.Patch -> item.fact.resolvedChanges()
            .joinToString { "${it.path}:${it.kind}:${it.unifiedDiff.orEmpty().hashCode()}" }
            .hashCode()
        is ItemFact.Subagent -> (31 * item.fact.status.hashCode()) + item.fact.summary.orEmpty().hashCode()
        else -> 0
    }

    /**
     * One-pass classification/grouping for a thread. O(n log n) sort + O(n) scans.
     * Prefer this over calling [isInterimAgentMessage] in a loop on the EDT.
     */
    fun projectionIndex(
        state: NormalizedServerState,
        threadId: ThreadId?,
        activeTurnId: String?,
    ): TranscriptProjectionIndex {
        val items = if (threadId == null) {
            emptyList()
        } else {
            state.items.values
                .filter { it.threadId == threadId }
                .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        }
        return projectionIndex(items, activeTurnId)
    }

    fun projectionIndex(
        items: List<ItemFact>,
        activeTurnId: String?,
    ): TranscriptProjectionIndex {
        var visits = 0
        val byTurn = LinkedHashMap<String, MutableList<ItemFact>>()
        for (item in items) {
            visits++
            byTurn.getOrPut(turnGroupKey(item)) { ArrayList() }.add(item)
        }
        val interimAgentIds = HashSet<String>()
        for (turnItems in byTurn.values) {
            visits += turnItems.size
            interimAgentIds += classifyInterimAgentIds(turnItems, activeTurnId)
        }
        val activityByKey = LinkedHashMap<String, MutableList<ItemFact>>()
        for (item in items) {
            visits++
            val activity = isNonAgentActivity(item) ||
                (item is ItemFact.AgentMessage && item.id.value in interimAgentIds)
            if (!activity) continue
            val key = activitySectionKey(listOf(item))
            activityByKey.getOrPut(key) { ArrayList() }.add(item)
        }
        val running = HashSet<String>()
        if (activeTurnId != null) {
            val threadForTurn = items.firstOrNull { it.turnId?.value == activeTurnId }?.threadId?.value
                ?: items.firstOrNull()?.threadId?.value
            if (threadForTurn != null) {
                running += "$threadForTurn/turn-$activeTurnId"
            }
        }
        for ((key, sectionItems) in activityByKey) {
            visits += sectionItems.size
            if (sectionItems.any {
                    it.status == ItemStatus.STARTED || it.status == ItemStatus.ACTIVE
                }
            ) {
                running += key
            }
        }
        lastProjectionItemVisits = visits
        return TranscriptProjectionIndex(
            items = items,
            interimAgentIds = interimAgentIds,
            activityByKey = activityByKey,
            sectionKeys = activityByKey.keys.toSet(),
            runningSectionKeys = running,
        )
    }

    private fun agentStatusLabel(status: ItemStatus): String =
        when (status) {
            ItemStatus.COMPLETED -> "hoàn tất"
            ItemStatus.FAILED -> "lỗi"
            ItemStatus.INTERRUPTED -> "đã dừng"
            ItemStatus.STARTED, ItemStatus.ACTIVE -> "đang chạy"
            ItemStatus.UNKNOWN -> "…"
        }

    fun lastAgentText(state: NormalizedServerState, threadId: ThreadId?): String? {
        if (threadId == null) return null
        val index = projectionIndex(state, threadId, activeTurnId = null)
        return index.items
            .asSequence()
            .filterIsInstance<ItemFact.AgentMessage>()
            .filter { !index.isInterim(it) }
            .maxByOrNull { it.arrivalSeq }
            ?.text
            ?.takeIf { it.isNotBlank() }
    }

    fun plainTextForCopy(item: ItemFact): String? =
        when (item) {
            is ItemFact.AgentMessage -> item.text.takeIf { it.isNotBlank() }
            is ItemFact.Command -> buildString {
                if (item.command.isNotBlank()) append("$ ${item.command}")
                if (item.output.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(item.output)
                }
            }.takeIf { it.isNotBlank() }
            is ItemFact.UserMessage -> item.text.takeIf { it.isNotBlank() }
            is ItemFact.Reasoning -> item.text.takeIf { it.isNotBlank() }
            else -> null
        }

    fun renderPlain(state: NormalizedServerState, threadId: ThreadId?): String {
        if (threadId == null) return "Start a conversation"
        val items = state.items.values
            .filter { it.threadId == threadId }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        if (items.isEmpty()) return "Thread ${threadId.value}"
        return items.joinToString("\n") { item ->
            when (item) {
                is ItemFact.UserMessage -> "You\n${item.text}"
                is ItemFact.AgentMessage -> "Codex\n${item.text}"
                is ItemFact.Reasoning -> "Thinking\n${item.text}"
                is ItemFact.Command -> "Command\n$ ${item.command}\n${item.output}".trimEnd()
                is ItemFact.Patch -> "Patch\n${item.fact.path}"
                is ItemFact.Subagent -> "Agent ${item.fact.agentId}"
                is ItemFact.ApprovalReference -> "Approval\n${item.requestId}"
                is ItemFact.Unknown -> "Unknown (${item.type})"
            }
        }
    }

    /** Section key used for expand/collapse of a turn activity group. */
    fun activitySectionKey(items: List<ItemFact>): String {
        val thread = items.firstOrNull()?.threadId?.value ?: "thread"
        val turn = items.firstNotNullOfOrNull { it.turnId?.value }
        val first = items.firstOrNull()?.id?.value ?: "x"
        return if (turn != null) "$thread/turn-$turn" else "$thread/act-$first"
    }

    /**
     * Earlier progress / plan agent messages fold into the thinking section (no Copy).
     * The latest agent message for the turn is always the visible reply — while it
     * streams, after it completes, and after the turn ends — so content does not jump
     * between the thinking block and the main bubble.
     */
    fun isInterimAgentMessage(
        item: ItemFact.AgentMessage,
        threadItems: List<ItemFact>,
        activeTurnId: String? = null,
    ): Boolean {
        val turnItems = threadItems.filter { sameTurn(it, item) }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        return item.id.value in classifyInterimAgentIds(turnItems, activeTurnId)
    }

    internal fun thinkingLabel(elapsedSeconds: Long?, stillRunning: Boolean): String =
        when {
            stillRunning && (elapsedSeconds == null || elapsedSeconds <= 0L) -> "Đang suy nghĩ…"
            stillRunning -> "Đang hoạt động · ${elapsedSeconds}s"
            elapsedSeconds == null -> "Thinking"
            else -> "Đã hoạt động trong ${elapsedSeconds}s"
        }

    private fun turnGroupKey(item: ItemFact): String {
        val turn = item.turnId
        return if (turn != null) {
            "th:${item.threadId.value}/t:${turn.value}"
        } else {
            "th:${item.threadId.value}/i:${item.id.value}"
        }
    }

    private fun sameTurn(a: ItemFact, b: ItemFact): Boolean {
        val turnA = a.turnId
        val turnB = b.turnId
        return if (turnA != null && turnB != null) turnA == turnB else a.id == b.id
    }

    private fun isNonAgentActivity(item: ItemFact): Boolean =
        item is ItemFact.Reasoning ||
            item is ItemFact.Command ||
            item is ItemFact.Patch ||
            item is ItemFact.Subagent

    /**
     * Classify interim agent messages for one already-sorted turn group in O(group size).
     * [activeTurnId] is retained for call-site compatibility; visibility no longer depends on it.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun classifyInterimAgentIds(
        turnItems: List<ItemFact>,
        activeTurnId: String?,
    ): Set<String> {
        val agents = turnItems.filterIsInstance<ItemFact.AgentMessage>()
        if (agents.isEmpty()) return emptySet()
        val lastAgentId = agents.last().id
        // Only earlier progress notes fold away. The latest reply stays put so streaming
        // and post-complete tool activity cannot yank text into/out of the thinking section.
        return agents.mapNotNullTo(HashSet()) { agent ->
            agent.id.value.takeIf { agent.id != lastAgentId }
        }
    }

    private fun activitySection(items: List<ItemFact>, options: TranscriptRenderOptions): String {
        val key = activitySectionKey(items)
        val stillRunning = items.any {
            it.status == ItemStatus.STARTED || it.status == ItemStatus.ACTIVE
        }
        // Expanded when panel says so (auto while running, manual after done).
        val expanded = key in options.expandedReasoningIds
        val elapsed = options.reasoningElapsedSeconds[key]
            ?: items.filterIsInstance<ItemFact.Reasoning>()
                .mapNotNull { options.reasoningElapsedSeconds[it.id.value] }
                .maxOrNull()
        val label = thinkingLabel(elapsed, stillRunning)
        val chevron = if (expanded) "▾" else "›"
        val details = if (expanded) {
            buildString {
                // Progress agent notes + reasoning narrative (no Copy).
                val narrative = buildString {
                    items.filterIsInstance<ItemFact.AgentMessage>()
                        .map { it.text.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { if (isNotEmpty()) append("\n\n"); append(it) }
                    val reasoningText = items.filterIsInstance<ItemFact.Reasoning>()
                        .map { it.text.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n")
                    if (reasoningText.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append(reasoningText)
                    }
                }
                if (narrative.isNotBlank()) {
                    append("""<div class="think-body md">${markdownBody(narrative, options)}</div>""")
                }
                items.forEach { item ->
                    when (item) {
                        is ItemFact.Command -> append(commandActivity(item, options))
                        // Patches → modified-files card; subagents → AgentChipPanel.
                        is ItemFact.Patch, is ItemFact.Subagent -> Unit
                        else -> Unit
                    }
                }
            }
        } else {
            ""
        }
        return """
        <div class="row thinking">
          <a class="think-toggle" href="codex-toggle-reasoning:${escapeAttr(key)}">
            <span class="think-label">${escape(label)}</span>
            <span class="think-chevron">${escape(chevron)}</span>
          </a>
          $details
        </div>
        """.trimIndent()
    }

    private fun commandActivity(item: ItemFact.Command, options: TranscriptRenderOptions): String {
        val open = item.id.value in options.expandedActivityIds
        val chevron = if (open) "▾" else "›"
        val detail = if (open) {
            val code = buildString {
                if (item.command.isNotBlank()) append(escape(item.command))
                if (item.output.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(escape(item.output))
                }
            }
            """<pre class="act-code"><code>$code</code></pre>"""
        } else {
            ""
        }
        return """
        <div class="act">
          <a class="act-toggle" href="codex-toggle-activity:${escapeAttr(item.id.value)}">
            <span class="act-ico">⌘</span>
            <span class="act-title">Đã chạy các lệnh</span>
            <span class="think-chevron">${escape(chevron)}</span>
          </a>
          $detail
        </div>
        """.trimIndent()
    }

    private fun modifiedFilesPayload(sectionItems: List<ItemFact>): ModifiedFilesActions.Payload? {
        val patches = sectionItems.filterIsInstance<ItemFact.Patch>()
        if (patches.isEmpty()) return null
        val threadId = patches.first().threadId.value
        val turnId = patches.first().turnId?.value
        val entries = patches.flatMap { it.fact.resolvedChanges() }
        if (entries.isEmpty()) return null
        val payload = ModifiedFilesActions.fromEntries(threadId, turnId, entries)
        return payload.takeIf { it.files.isNotEmpty() }
    }

    private fun activityRow(
        id: String,
        icon: String,
        title: String,
        detail: String?,
        options: TranscriptRenderOptions,
    ): String {
        val open = id in options.expandedActivityIds
        val chevron = if (open) "▾" else "›"
        val body = if (open && !detail.isNullOrBlank()) {
            """<div class="think-body">${escapeWithBreaks(detail)}</div>"""
        } else {
            ""
        }
        return """
        <div class="act">
          <a class="act-toggle" href="codex-toggle-activity:${escapeAttr(id)}">
            <span class="act-ico">${escape(icon)}</span>
            <span class="act-title">${escape(title)}</span>
            <span class="think-chevron">${escape(chevron)}</span>
          </a>
          $body
        </div>
        """.trimIndent()
    }

    private fun userBlock(text: String): String =
        """
        <div class="row user">
          <div class="bubble">${escapeWithBreaks(text)}</div>
        </div>
        <table class="row-gap" width="100%" cellpadding="0" cellspacing="0"><tr><td height="12"></td></tr></table>
        """.trimIndent()

    private fun agentBlock(itemId: String, text: String, options: TranscriptRenderOptions): String =
        """
        <div class="row agent">
          <div class="agent-main">
            <div class="md">${markdownBody(text, options)}</div>
            <div class="agent-actions"><a class="action" href="codex-copy:${escapeAttr(itemId)}" title="Copy">$COPY_GLYPH</a></div>
          </div>
        </div>
        """.trimIndent()

    /**
     * Split agent markdown so fenced code becomes native [TranscriptBlock.CodeFence] cards
     * (rounded Swing chrome like the modified-files card). Prose stays HTML.
     */
    private fun expandAgentMessage(
        item: ItemFact.AgentMessage,
        options: TranscriptRenderOptions,
    ): List<TranscriptBlock> {
        val itemId = item.id.value
        val blockPrefix = "item:${item.threadId.value}:$itemId"
        val text = item.text
        // While streaming, keep one Html host. Plain↔Html↔CodeFence remounts dispose
        // Swing components mid-flight and leave blank/flickering bands in the transcript.
        if (options.lightweightStreaming) {
            return listOf(
                TranscriptBlock.Html(
                    fragment = agentBlock(itemId, text, options),
                    id = "$blockPrefix:prose:0",
                    revision = TranscriptBlockRevision(
                        viewVersion = "$text\u0000light=true",
                    ),
                ),
            )
        }
        val parts = MarkdownFenceSplitter.split(text)
        if (parts.none { it is MarkdownFenceSplitter.Part.Fence }) {
            if (isPlainAgentText(text)) {
                return listOf(
                    TranscriptBlock.PlainAgentMessage(
                        itemId = itemId,
                        text = text,
                        id = "$blockPrefix:prose:0",
                        revision = TranscriptBlockRevision(viewVersion = text),
                    ),
                )
            }
            return listOf(
                TranscriptBlock.Html(
                    fragment = agentBlock(itemId, text, options),
                    id = "$blockPrefix:prose:0",
                    revision = TranscriptBlockRevision(
                        viewVersion = "$text\u0000light=false",
                    ),
                ),
            )
        }
        val out = ArrayList<TranscriptBlock>()
        val prose = StringBuilder()
        var proseIndex = 0
        var fenceIndex = 0
        fun flushProse(withCopy: Boolean = false) {
            val chunk = prose.toString().trim()
            prose.clear()
            if (chunk.isEmpty() && !withCopy) return
            val md = if (chunk.isEmpty()) "" else """<div class="md">${markdownBody(chunk, options)}</div>"""
            val copy = if (withCopy) {
                """<div class="agent-actions"><a class="action" href="codex-copy:${escapeAttr(itemId)}" title="Copy">$COPY_GLYPH</a></div>"""
            } else {
                ""
            }
            out += TranscriptBlock.Html(
                fragment = """
                <div class="row agent-part">
                  $md
                  $copy
                </div>
                """.trimIndent(),
                id = "$blockPrefix:prose:${proseIndex++}",
                revision = TranscriptBlockRevision(
                    viewVersion = "$chunk\u0000copy=$withCopy;light=${options.lightweightStreaming}",
                ),
            )
        }
        for (part in parts) {
            when (part) {
                is MarkdownFenceSplitter.Part.Text -> prose.append(part.text)
                is MarkdownFenceSplitter.Part.Fence -> {
                    flushProse(withCopy = false)
                    out += TranscriptBlock.CodeFence(
                        language = part.language,
                        code = part.code,
                        id = "$blockPrefix:fence:${fenceIndex++}",
                        revision = TranscriptBlockRevision(
                            viewVersion = "${part.language.orEmpty()}\u0000${part.code}",
                        ),
                    )
                }
            }
        }
        // Keep copy with the last prose chunk (not a separate distant HTML pane).
        flushProse(withCopy = true)
        return out
    }

    private fun isPlainAgentText(text: String): Boolean {
        if (text.isBlank() || '\n' in text || '\r' in text) return false
        if (text.contains("://")) return false
        return text.none { it in "`*_~[]<>#" }
    }

    private fun metaBlock(title: String, bodyHtml: String, muted: Boolean = false): String {
        val mutedClass = if (muted) " muted" else ""
        return """
        <div class="row meta$mutedClass">
          <div class="role">${escape(title)}</div>
          <div class="body">$bodyHtml</div>
        </div>
        <table class="row-gap" width="100%" cellpadding="0" cellspacing="0"><tr><td height="10"></td></tr></table>
        """.trimIndent()
    }

    private fun markdownBody(text: String, options: TranscriptRenderOptions): String {
        if (text.isBlank()) return ""
        return try {
            // GFM fences → <pre><code class="language-…"> then color via IDE lexer.
            val html = try {
                val tree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(text)
                HtmlGenerator(text, tree, markdownFlavour).generateHtml()
            } catch (_: Throwable) {
                "<p>${escapeWithBreaks(text)}</p>"
            }
            val cleaned = html
                .replace(Regex("""(?i)(\s*<p>\s*(?:&nbsp;|\s|<br\s*/?\s*>)*\s*</p>)+\s*$"""), "")
                .trim()
            linkifyInlineCode(
                rewriteMarkdownFileAnchors(
                    colorizeFencedCode(cleaned, options.project, options.lightweightStreaming),
                ),
            ).let(::rewriteInlineCodeSpans)
        } catch (_: Throwable) {
            "<p>${escapeWithBreaks(text)}</p>"
        }
    }

    /**
     * Color fenced code inside native `<pre><code>`, and add a small toolbar
     * *above* the pre (lang + copy). Never wrap `<pre>` inside `<table>` —
     * that caused JBHtmlPane Bidi crashes.
     *
     * When [lightweight] is true (active stream), skip IDE lexer highlighting.
     */
    private fun colorizeFencedCode(html: String, project: Project?, lightweight: Boolean): String {
        if (!html.contains("<pre", ignoreCase = true)) return html
        return try {
            Regex(
                """<pre(\s[^>]*)?>\s*<code(\s[^>]*)?>([\s\S]*?)</code>\s*</pre>""",
                RegexOption.IGNORE_CASE,
            ).replace(html) { match ->
                val preAttrs = match.groupValues[1]
                val codeAttrs = match.groupValues[2]
                val code = decodeHtml(match.groupValues[3]).trimEnd('\n')
                val clipped = if (code.length > 48_000) code.take(48_000) + "\n...(truncated)" else code
                val langAttr = Regex(
                    """(?:class|data-lang)\s*=\s*["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE,
                ).find(codeAttrs)?.groupValues?.get(1)
                    ?: Regex(
                        """(?:class|data-lang)\s*=\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(preAttrs)?.groupValues?.get(1)
                    .orEmpty()
                val langToken = langAttr
                    .split(Regex("\\s+"))
                    .map { it.removePrefix("language-").removePrefix("lang-") }
                    .firstOrNull { it.isNotBlank() && it != "hljs" && it != "act-code" }
                val colored = if (lightweight) {
                    escape(clipped)
                } else {
                    CodeFenceHighlighter.toHtml(project, clipped, langToken)
                }
                if ("act-code" in preAttrs || "act-code" in codeAttrs) {
                    return@replace """<pre$preAttrs><code$codeAttrs>$colored</code></pre>"""
                }
                val display = (langToken ?: "code").lowercase()
                val copyId = CodeBlockClipboard.put(code)
                val mutedCss = CodexUiTheme.css(CodexUiTheme.muted)
                // Toolbar is a sibling above <pre>, not a wrapper around it.
                """
                <table class="cb-bar" width="100%" cellpadding="0" cellspacing="0">
                  <tr>
                    <td align="left"><font color="$mutedCss" size="2">${escape(display)}</font></td>
                    <td align="right"><a class="cb-copy" href="codex-copy-code:${escapeAttr(copyId)}" title="Copy">$COPY_GLYPH</a></td>
                  </tr>
                </table>
                <pre$preAttrs><code$codeAttrs>$colored</code></pre>
                """.trimIndent()
            }
        } catch (_: Throwable) {
            html
        }
    }

    /**
     * Converts markdown file anchors like
     * `[File.java](/abs/path/File.java:369)` into `codex-open:` links.
     * Label must be `[^<]*` only — never DOT_MATCHES_ALL (EDT freeze risk).
     */
    private fun rewriteMarkdownFileAnchors(html: String): String =
        Regex(
            """<a\s+([^>]*?)href\s*=\s*["']([^"']+)["']([^>]*)>([^<]*)</a>""",
            RegexOption.IGNORE_CASE,
        ).replace(html) { match ->
            val href = decodeHtml(match.groupValues[2])
            if (!FileLinkSupport.looksLikeLocalFileHref(href)) {
                return@replace match.value
            }
            val target = FileLinkSupport.parse(href)
            val label = decodeHtml(match.groupValues[4]).ifBlank {
                target.path.substringAfterLast('/').substringAfterLast('\\')
            }
            """<a class="file" href="codex-open:${escapeAttr(encodeOpenTarget(target))}">${escape(label)}</a>"""
        }

    private fun encodeOpenTarget(target: FileLinkSupport.Target): String =
        buildString {
            append(target.path)
            if (target.line != null) {
                append(':').append(target.line)
                if (target.column != null) append(':').append(target.column)
            }
        }

    /**
     * JBHtmlPane styles native `<code>` with the *editor* monospace size, which is often
     * larger than the UI label font used for body text. Rewrite inline code to `.icode`
     * spans so we own the size (same as body). Leave `<pre><code>` alone.
     */
    private fun rewriteInlineCodeSpans(html: String): String {
        if (!html.contains("<code", ignoreCase = true)) return html
        val preRanges = Regex("""(?is)<pre\b[^>]*>.*?</pre>""")
            .findAll(html)
            .map { it.range }
            .toList()
        return Regex(
            """<code(?:\s[^>]*)?>([\s\S]*?)</code>""",
            RegexOption.IGNORE_CASE,
        ).replace(html) { match ->
            if (preRanges.any { match.range.first in it }) {
                return@replace match.value
            }
            """<span class="icode">${match.groupValues[1]}</span>"""
        }
    }

    /** Linkify file paths in inline `<code>` only — never rewrite fenced `<pre><code>` bodies. */
    private fun linkifyInlineCode(html: String): String {
        if (!html.contains("<code>", ignoreCase = true) && !html.contains("<code ", ignoreCase = true)) {
            return html
        }
        val preRanges = Regex("""(?is)<pre\b[^>]*>.*?</pre>""")
            .findAll(html)
            .map { it.range }
            .toList()
        return Regex("""<code>([^<]{1,400})</code>""", RegexOption.IGNORE_CASE).replace(html) { match ->
            if (preRanges.any { match.range.first in it }) {
                return@replace match.value
            }
            val raw = decodeHtml(match.groupValues[1])
            if (looksLikeFilePath(raw) || FileLinkSupport.looksLikeLocalFileHref(raw)) {
                val target = FileLinkSupport.parse(raw)
                val name = target.path.substringAfterLast('/').substringAfterLast('\\')
                """<a class="file" href="codex-open:${escapeAttr(encodeOpenTarget(target))}">${escape(name)}</a>"""
            } else {
                match.value
            }
        }
    }

    private fun decodeHtml(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun looksLikeFilePath(value: String): Boolean {
        val v = value.trim()
        if (v.length < 3 || v.length > 240) return false
        if (v.contains(' ') && !v.contains('/')) return false
        return FILE_EXT.containsMatchIn(v) || v.contains('/') || v.contains('\\')
    }

    private fun document(body: String): String = wrapDocument(body)

    fun wrapDocument(body: String): String {
        val fg = CodexUiTheme.css(CodexUiTheme.foreground)
        val muted = CodexUiTheme.css(CodexUiTheme.muted)
        val bubble = CodexUiTheme.css(CodexUiTheme.bubbleBg)
        val bubbleFg = CodexUiTheme.css(CodexUiTheme.bubbleFg)
        val codeBg = CodexUiTheme.css(CodexUiTheme.codeBg)
        val codeFg = CodexUiTheme.css(CodexUiTheme.codeFg)
        val border = CodexUiTheme.css(CodexUiTheme.border)
        val cardBg = CodexUiTheme.css(CodexUiTheme.cardBg)
        val cardBorder = CodexUiTheme.css(CodexUiTheme.cardBorder)
        val divider = CodexUiTheme.css(CodexUiTheme.cardDivider)
        val accent = CodexUiTheme.css(CodexUiTheme.accent)
        return """
        <html>
        <head>
        <style type="text/css">
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            font-size: ${CodexUiFonts.BODY_PX}px;
            color: $fg;
            margin: 8px 12px 16px 12px;
            line-height: 1.5;
          }
          .empty { color: $muted; margin-top: 24px; text-align: center; }
          .muted { color: $muted; }
          .row { margin: 0 0 16px 0; }
          .row.user {
            display: flex;
            justify-content: flex-end;
            margin: 0 0 14px 0;
            padding: 0;
          }
          .row.user + .row.user { margin-top: 10px; }
          .bubble {
            background: $bubble;
            color: $bubbleFg;
            padding: 8px 12px;
            border-radius: 12px;
            max-width: 88%;
            text-align: left;
            white-space: pre-wrap;
            line-height: 1.45;
          }
          .row.agent { margin-top: 10px; margin-bottom: 12px; }
          .row.agent-part { margin: 2px 0 8px 0; }
          .agent-main {
            display: block;
          }
          .agent-actions {
            margin: 2px 0 0 0;
            padding: 0;
            line-height: 1.2;
            text-align: right;
          }
          a.action {
            font-size: ${CodexUiFonts.BODY_PX}px;
            color: $muted;
            text-decoration: none;
            padding: 0 2px;
          }
          a.action:hover { color: $fg; }
          table.cb-bar {
            width: 100%;
            margin: 10px 0 0 0;
            border-collapse: collapse;
          }
          a.cb-copy {
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
            color: $muted;
            text-decoration: none;
            padding: 0 2px;
          }
          a.cb-copy:hover { color: $fg; }
          .role {
            font-size: ${CodexUiFonts.META_PX}px;
            font-weight: 600;
            color: $muted;
            margin: 0 0 4px 0;
          }
          .row.meta {
            margin: 0 0 12px 0;
            padding: 10px 12px;
            background: $codeBg;
            border: 1px solid $border;
            border-radius: 8px;
          }
          .row.meta + .row.meta { margin-top: 10px; }
          .row.thinking {
            margin: 8px 0 14px 0;
            padding: 2px 0 4px 0;
          }
          .row.files-card {
            margin: 10px 0 20px 0;
            padding: 12px 14px 8px 14px;
            background: $cardBg;
            border: 1px solid $cardBorder;
            border-radius: 8px;
          }
          table.files-head, table.files-list {
            width: 100%;
            border-collapse: collapse;
          }
          td.files-info { padding: 0 8px 8px 0; }
          td.files-actions {
            padding: 0 0 8px 8px;
            white-space: nowrap;
          }
          .files-title {
            color: $fg;
            font-size: ${CodexUiFonts.BODY_PX}px;
            font-weight: 600;
            margin: 0 0 4px 0;
            letter-spacing: 0.01em;
          }
          .files-ico {
            color: $muted;
            margin-right: 8px;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: ${CodexUiFonts.META_PX}px;
          }
          .files-stats {
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
            font-family: "JetBrains Mono", Consolas, monospace;
            margin: 0;
          }
          a.files-undo {
            color: $muted;
            text-decoration: none;
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
            margin-right: 8px;
            padding: 4px 8px;
          }
          a.files-undo:hover { color: $fg; }
          a.files-review {
            color: $fg;
            text-decoration: none;
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
            font-weight: 500;
            background: $codeBg;
            border: 1px solid $cardBorder;
            padding: 4px 12px;
            border-radius: 6px;
          }
          a.files-review:hover {
            border-color: $muted;
          }
          table.files-list {
            margin-top: 2px;
            border-top: 1px solid $divider;
          }
          table.files-list td {
            padding: 8px 0;
            border-bottom: 1px solid $divider;
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
            vertical-align: middle;
          }
          table.files-list tr:last-child td { border-bottom: none; }
          td.files-path a.file {
            color: $fg;
            text-decoration: none;
          }
          td.files-path a.file:hover { color: $accent; }
          .files-dir {
            color: $muted;
            font-size: ${CodexUiFonts.META_PX}px;
          }
          .files-name {
            color: $fg;
            font-weight: 500;
          }
          td.files-delta {
            white-space: nowrap;
            padding-left: 16px;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: ${CodexUiFonts.META_PX}px;
          }
          a.think-toggle {
            display: inline-block;
            color: $muted;
            text-decoration: none;
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
            padding: 2px 0 4px 0;
          }
          a.think-toggle:hover { color: $fg; }
          .think-label { margin-right: 6px; }
          .think-chevron { font-size: ${CodexUiFonts.META_PX}px; }
          .think-body {
            margin: 4px 0 8px 0;
            color: $muted;
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
          }
          .think-body p { margin: 0 0 6px 0; }
          .act {
            margin: 3px 0 5px 2px;
            color: $muted;
            font-size: ${CodexUiFonts.SECONDARY_PX}px;
          }
          a.act-toggle {
            color: $muted;
            text-decoration: none;
            display: inline-block;
            padding: 2px 0;
          }
          a.act-toggle:hover { color: $fg; }
          .act-ico { margin-right: 6px; }
          .act-title { margin-right: 6px; }
          .act-file { margin: 2px 0 4px 22px; }
          pre.act-code {
            background: $codeBg;
            border: 1px solid $border;
            border-radius: 8px;
            padding: 8px 10px;
            margin: 4px 0 8px 18px;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: ${CodexUiFonts.BODY_PX}px;
            white-space: pre-wrap;
            color: $fg;
          }
          a.file {
            color: $accent;
            text-decoration: none;
          }
          a.file:hover { text-decoration: underline; }
          .file-ico { margin-right: 4px; font-size: ${CodexUiFonts.META_PX}px; }
          .md h1, .md h2, .md h3, .md h4 {
            color: $fg;
            font-weight: 700;
            margin: 10px 0 6px 0;
            line-height: 1.3;
            font-size: ${CodexUiFonts.BODY_PX}px;
          }
          .md, .md p, .md li, .md td, .md th, .md span, .md a, .md strong, .md em, .md b, .md i {
            font-size: ${CodexUiFonts.BODY_PX}px;
          }
          .md p { margin: 0 0 8px 0; }
          .md > :last-child { margin-bottom: 0 !important; }
          .md ul, .md ol { margin: 0 0 8px 18px; padding: 0; }
          .md li { margin: 3px 0; }
          /* Native code tags — keep in sync; prefer .icode for inline (see rewriteInlineCodeSpans). */
          code, tt, samp {
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: ${CodexUiFonts.BODY_PX}px;
          }
          .icode, .md code {
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: ${CodexUiFonts.BODY_PX}px;
            background: $codeBg;
            padding: 1px 4px;
            border-radius: 3px;
          }
          .md pre {
            background: $codeBg;
            border: 1px solid $border;
            border-radius: 10px;
            padding: 12px 14px;
            margin: 2px 0 14px 0;
            line-height: 1.45;
            white-space: pre;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: ${CodexUiFonts.BODY_PX}px;
            color: $codeFg;
          }
          table.cb-bar + pre {
            margin-top: 2px;
          }
          .md pre code {
            background: $codeBg;
            padding: 0;
            margin: 0;
            border: none;
            border-radius: 0;
            font-family: inherit;
            font-size: inherit;
            white-space: inherit;
            color: inherit;
          }
          .md pre font {
            font-family: inherit;
            font-size: inherit;
          }
          .md a { color: $accent; }
          .md strong { font-weight: 700; color: $fg; }
          .md blockquote {
            border-left: 3px solid $border;
            margin: 6px 0;
            padding: 2px 0 2px 10px;
            color: $muted;
          }
          .row.meta.muted .body { color: $muted; font-style: italic; }
        </style>
        </head>
        <body>$body</body>
        </html>
        """.trimIndent()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun escapeAttr(text: String): String =
        escape(text).replace("'", "&#39;")

    private fun escapeWithBreaks(text: String): String =
        escape(text).replace("\n", "<br/>")
}
