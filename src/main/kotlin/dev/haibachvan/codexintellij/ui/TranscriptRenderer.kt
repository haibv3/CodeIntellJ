package dev.haibachvan.codexintellij.ui

import com.intellij.markdown.utils.MarkdownToHtmlConverter
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.resolvedChanges
import java.awt.Color
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

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
)

/** Renders normalized server state as themed HTML matching Codex IDE chat layout. */
object TranscriptRenderer {
    /** Must be a regular (non-raw) string — `"""\u29C9"""` stays literal in Kotlin. */
    private const val COPY_GLYPH = "\u29C9"

    private val markdown by lazy {
        MarkdownToHtmlConverter(GFMFlavourDescriptor())
    }

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
        val htmlBody = blocks.filterIsInstance<TranscriptBlock.Html>().joinToString("") { it.fragment }
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
                ),
            )
        }
        val items = if (threadId == null) {
            emptyList()
        } else {
            state.items.values
                .filter { it.threadId == threadId }
                .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        }
        if (items.isEmpty() && options.localNotices.isEmpty()) {
            return listOf(
                TranscriptBlock.Html(
                    """
                    <div class="empty">
                      <p class="muted">Chưa có tin nhắn.</p>
                    </div>
                    """.trimIndent(),
                ),
            )
        }
        val blocks = ArrayList<TranscriptBlock>()
        val html = StringBuilder()
        fun flushHtml() {
            if (html.isNotEmpty()) {
                blocks += TranscriptBlock.Html(html.toString())
                html.clear()
            }
        }
        val emittedActivityKeys = HashSet<String>()
        val emittedPatchKeys = HashSet<String>()
        fun emitModifiedFiles(sectionItems: List<ItemFact>) {
            val key = activitySectionKey(sectionItems)
            if (!emittedPatchKeys.add(key)) return
            modifiedFilesPayload(sectionItems)?.let { blocks += TranscriptBlock.ModifiedFiles(it) }
        }
        for (item in items) {
            when (item) {
                is ItemFact.UserMessage -> html.append(userBlock(item.text))
                is ItemFact.AgentMessage -> {
                    if (isInterimAgentMessage(item, items, options.activeTurnId)) {
                        val key = activitySectionKey(listOf(item))
                        if (emittedActivityKeys.add(key)) {
                            val sectionItems = activityItemsForKey(items, key, options.activeTurnId)
                            html.append(activitySection(sectionItems, options))
                            flushHtml()
                            // Card waits for the final agent reply (or end-of-turn flush).
                        }
                    } else {
                        flushHtml()
                        for (block in expandAgentMessage(item.id.value, item.text, options)) {
                            blocks += block
                        }
                        // Modified-files card belongs after the answer, not above it.
                        val key = activitySectionKey(listOf(item))
                        emitModifiedFiles(activityItemsForKey(items, key, options.activeTurnId))
                    }
                }
                is ItemFact.ApprovalReference -> html.append(
                    metaBlock(
                        "Approval",
                        "<p><code>${escape(item.requestId)}</code> · ${escape(item.status.name)}</p>",
                    ),
                )
                is ItemFact.Unknown -> html.append(
                    metaBlock(
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
                    val sectionItems = activityItemsForKey(items, key, options.activeTurnId)
                    html.append(activitySection(sectionItems, options))
                    flushHtml()
                }
            }
        }
        for (notice in options.localNotices) {
            html.append(
                metaBlock(
                    notice.title,
                    markdownBody(notice.bodyMarkdown, options),
                ),
            )
        }
        flushHtml()
        // Turns with patches but no final agent reply yet (or patches after the reply).
        for (key in items
            .filter { isActivityItem(it, items, options.activeTurnId) }
            .map { activitySectionKey(listOf(it)) }
            .distinct()) {
            if (key in emittedPatchKeys) continue
            emitModifiedFiles(activityItemsForKey(items, key, options.activeTurnId))
        }
        return blocks
    }

    fun lastAgentText(state: NormalizedServerState, threadId: ThreadId?): String? {
        if (threadId == null) return null
        val items = state.items.values
            .filter { it.threadId == threadId }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        return items
            .asSequence()
            .filterIsInstance<ItemFact.AgentMessage>()
            .filter { !isInterimAgentMessage(it, items, activeTurnId = null) }
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
        val turn = items.firstNotNullOfOrNull { it.turnId?.value }
        val first = items.firstOrNull()?.id?.value ?: "x"
        return if (turn != null) "turn-$turn" else "act-$first"
    }

    /**
     * Progress / plan agent messages fold into the thinking section (no Copy).
     * Only the last agent message is the visible result — while streaming, or after the turn ends.
     */
    fun isInterimAgentMessage(
        item: ItemFact.AgentMessage,
        threadItems: List<ItemFact>,
        activeTurnId: String? = null,
    ): Boolean {
        val turnItems = threadItems.filter { sameTurn(it, item) }
            .sortedWith(compareBy({ it.arrivalSeq }, { it.id.value }))
        val agents = turnItems.filterIsInstance<ItemFact.AgentMessage>()
        val lastAgent = agents.lastOrNull() ?: return false
        if (lastAgent.id != item.id) return true

        val othersBusy = turnItems.any { other ->
            other.id != item.id &&
                (other.status == ItemStatus.STARTED || other.status == ItemStatus.ACTIVE)
        }
        if (othersBusy) return true

        val turnId = item.turnId?.value
        val turnMarkedActive = activeTurnId != null && turnId != null && turnId == activeTurnId
        if (turnMarkedActive) {
            val streaming = item.status == ItemStatus.STARTED || item.status == ItemStatus.ACTIVE
            if (!streaming) return true // completed progress notes stay folded
            // Streaming: treat as final result only after earlier work in this turn.
            val priorWork = turnItems.any { it.id != item.id }
            return !priorWork
        }

        // Finished turn: last agent is result unless tool/reasoning activity came after it.
        return turnItems.any { other ->
            isNonAgentActivity(other) &&
                (other.arrivalSeq > item.arrivalSeq ||
                    (other.arrivalSeq == item.arrivalSeq && other.id.value > item.id.value))
        }
    }

    internal fun thinkingLabel(elapsedSeconds: Long?, stillRunning: Boolean): String =
        when {
            stillRunning && (elapsedSeconds == null || elapsedSeconds <= 0L) -> "Đang suy nghĩ…"
            stillRunning -> "Đang hoạt động · ${elapsedSeconds}s"
            elapsedSeconds == null -> "Thinking"
            else -> "Đã hoạt động trong ${elapsedSeconds}s"
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

    private fun isActivityItem(
        item: ItemFact,
        threadItems: List<ItemFact>,
        activeTurnId: String?,
    ): Boolean =
        isNonAgentActivity(item) ||
            (item is ItemFact.AgentMessage && isInterimAgentMessage(item, threadItems, activeTurnId))

    private fun activityItemsForKey(
        threadItems: List<ItemFact>,
        key: String,
        activeTurnId: String?,
    ): List<ItemFact> =
        threadItems.filter { item ->
            isActivityItem(item, threadItems, activeTurnId) && activitySectionKey(listOf(item)) == key
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
                        // Patches render in the always-visible modified-files card.
                        is ItemFact.Patch -> Unit
                        is ItemFact.Subagent -> append(
                            activityRow(
                                item.id.value,
                                "◈",
                                "Agent ${item.fact.agentId}",
                                item.fact.summary,
                                options,
                            ),
                        )
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
        itemId: String,
        text: String,
        options: TranscriptRenderOptions,
    ): List<TranscriptBlock> {
        val parts = MarkdownFenceSplitter.split(text)
        if (parts.none { it is MarkdownFenceSplitter.Part.Fence }) {
            return listOf(TranscriptBlock.Html(agentBlock(itemId, text, options)))
        }
        val out = ArrayList<TranscriptBlock>()
        val prose = StringBuilder()
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
                """
                <div class="row agent-part">
                  $md
                  $copy
                </div>
                """.trimIndent(),
            )
        }
        for (part in parts) {
            when (part) {
                is MarkdownFenceSplitter.Part.Text -> prose.append(part.text)
                is MarkdownFenceSplitter.Part.Fence -> {
                    flushProse(withCopy = false)
                    out += TranscriptBlock.CodeFence(part.language, part.code)
                }
            }
        }
        // Keep copy with the last prose chunk (not a separate distant HTML pane).
        flushProse(withCopy = true)
        return out
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
                markdown.convertMarkdownToHtml(text, null)
            } catch (_: Throwable) {
                "<p>${escapeWithBreaks(text)}</p>"
            }
            val cleaned = html
                .replace(Regex("""(?i)(\s*<p>\s*(?:&nbsp;|\s|<br\s*/?\s*>)*\s*</p>)+\s*$"""), "")
                .trim()
            linkifyInlineCode(rewriteMarkdownFileAnchors(colorizeFencedCode(cleaned, options.project)))
        } catch (_: Throwable) {
            "<p>${escapeWithBreaks(text)}</p>"
        }
    }

    /**
     * Color fenced code inside native `<pre><code>`, and add a small toolbar
     * *above* the pre (lang + copy). Never wrap `<pre>` inside `<table>` —
     * that caused JBHtmlPane Bidi crashes.
     */
    private fun colorizeFencedCode(html: String, project: Project?): String {
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
                val colored = CodeFenceHighlighter.toHtml(project, clipped, langToken)
                if ("act-code" in preAttrs || "act-code" in codeAttrs) {
                    return@replace """<pre$preAttrs><code$codeAttrs>$colored</code></pre>"""
                }
                val display = (langToken ?: "code").lowercase()
                val copyId = CodeBlockClipboard.put(code)
                // Toolbar is a sibling above <pre>, not a wrapper around it.
                """
                <table class="cb-bar" width="100%" cellpadding="0" cellspacing="0">
                  <tr>
                    <td align="left"><font color="#9aa0a6" size="2">${escape(display)}</font></td>
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
        val fg = css(JBColor.foreground())
        val muted = css(JBColor.GRAY)
        val bubble = css(JBColor(Color(0x3A3A3A), Color(0x3A3A3A)))
        val bubbleFg = css(JBColor(Color(0xE8E8E8), Color(0xE8E8E8)))
        val codeBg = css(JBColor(Color(0x2F2F2F), Color(0x2F2F2F)))
        val border = css(JBColor.border())
        return """
        <html>
        <head>
        <style type="text/css">
          body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            font-size: 13px;
            color: $fg;
            margin: 8px 12px 16px 12px;
            line-height: 1.5;
          }
          .empty { color: $muted; margin-top: 16px; }
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
            font-size: 14px;
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
            font-size: 13px;
            color: #9aa0a6;
            text-decoration: none;
            padding: 0 2px;
          }
          a.cb-copy:hover { color: $fg; }
          .role {
            font-size: 11px;
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
            background: #252526;
            border: 1px solid #3c3c3c;
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
            color: #e8e8e8;
            font-size: 13px;
            font-weight: 600;
            margin: 0 0 4px 0;
            letter-spacing: 0.01em;
          }
          .files-ico {
            color: #8b949e;
            margin-right: 8px;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: 11px;
          }
          .files-stats {
            font-size: 12px;
            font-family: "JetBrains Mono", Consolas, monospace;
            margin: 0;
          }
          a.files-undo {
            color: #9aa0a6;
            text-decoration: none;
            font-size: 12px;
            margin-right: 8px;
            padding: 4px 8px;
          }
          a.files-undo:hover { color: #e8e8e8; }
          a.files-review {
            color: #e8e8e8;
            text-decoration: none;
            font-size: 12px;
            font-weight: 500;
            background: #3d3d3d;
            border: 1px solid #555555;
            padding: 4px 12px;
            border-radius: 6px;
          }
          a.files-review:hover {
            background: #4a4a4a;
            border-color: #6a6a6a;
          }
          table.files-list {
            margin-top: 2px;
            border-top: 1px solid #333333;
          }
          table.files-list td {
            padding: 8px 0;
            border-bottom: 1px solid #2f2f2f;
            font-size: 12px;
            vertical-align: middle;
          }
          table.files-list tr:last-child td { border-bottom: none; }
          td.files-path a.file {
            color: #c9d1d9;
            text-decoration: none;
          }
          td.files-path a.file:hover { color: #58a6ff; }
          .files-dir {
            color: #8b949e;
            font-size: 11.5px;
          }
          .files-name {
            color: #e6edf3;
            font-weight: 500;
          }
          td.files-delta {
            white-space: nowrap;
            padding-left: 16px;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: 11.5px;
          }
          a.think-toggle {
            display: inline-block;
            color: $muted;
            text-decoration: none;
            font-size: 12px;
            padding: 2px 0 4px 0;
          }
          a.think-toggle:hover { color: $fg; }
          .think-label { margin-right: 6px; }
          .think-chevron { font-size: 11px; }
          .think-body {
            margin: 4px 0 8px 0;
            color: $muted;
            font-size: 12.5px;
          }
          .think-body p { margin: 0 0 6px 0; }
          .act {
            margin: 3px 0 5px 2px;
            color: $muted;
            font-size: 12px;
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
            font-size: 11.5px;
            white-space: pre-wrap;
            color: $fg;
          }
          a.file {
            color: #589DF6;
            text-decoration: none;
          }
          a.file:hover { text-decoration: underline; }
          .file-ico { margin-right: 4px; font-size: 11px; }
          .md h1, .md h2, .md h3, .md h4 {
            color: $fg;
            font-weight: 700;
            margin: 10px 0 6px 0;
            line-height: 1.3;
          }
          .md h1 { font-size: 18px; }
          .md h2 { font-size: 16px; }
          .md h3 { font-size: 14px; }
          .md p { margin: 0 0 8px 0; }
          .md > :last-child { margin-bottom: 0 !important; }
          .md ul, .md ol { margin: 0 0 8px 18px; padding: 0; }
          .md li { margin: 3px 0; }
          .md code {
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: 11px;
            background: $codeBg;
            padding: 0 4px;
            border-radius: 3px;
          }
          .md pre {
            background: #2b2b2b;
            border: 1px solid $border;
            border-radius: 10px;
            padding: 12px 14px;
            margin: 2px 0 14px 0;
            line-height: 1.45;
            white-space: pre;
            font-family: "JetBrains Mono", Consolas, monospace;
            font-size: 12px;
            color: #d4d4d4;
          }
          table.cb-bar + pre {
            margin-top: 2px;
          }
          .md pre code {
            background: #2b2b2b;
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
          .md a { color: #589DF6; }
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

    private fun css(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

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
