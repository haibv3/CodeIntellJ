package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.agents.AgentConfigDiscovery
import dev.haibachvan.codexintellij.agents.AgentDescriptor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.io.path.isRegularFile

data class ComposerPlusEntry(
    val title: String,
    val description: String,
    val insertText: String = "",
    val section: Section,
    val action: Action = Action.INSERT,
) {
    enum class Section { ADD, AGENT }
    enum class Action { INSERT, ATTACH_FILES, ATTACH_FOLDER }
}

object ComposerPlusCatalog {
    fun addEntries(): List<ComposerPlusEntry> = listOf(
        ComposerPlusEntry(
            title = "Đính kèm tệp",
            description = "Chọn một hoặc nhiều tệp để gắn vào tin nhắn",
            section = ComposerPlusEntry.Section.ADD,
            action = ComposerPlusEntry.Action.ATTACH_FILES,
        ),
        ComposerPlusEntry(
            title = "Đính kèm thư mục",
            description = "Chọn một thư mục để gắn vào tin nhắn",
            section = ComposerPlusEntry.Section.ADD,
            action = ComposerPlusEntry.Action.ATTACH_FOLDER,
        ),
        ComposerPlusEntry(
            title = "Mục tiêu",
            description = "Đặt mục tiêu để tiếp tục theo đuổi",
            insertText = "/goal ",
            section = ComposerPlusEntry.Section.ADD,
        ),
        ComposerPlusEntry(
            title = "Chế độ lập kế hoạch",
            description = "Bật chế độ lập kế hoạch",
            insertText = "/plan ",
            section = ComposerPlusEntry.Section.ADD,
        ),
    )

    fun agentEntries(project: Project?): List<ComposerPlusEntry> {
        val roots = buildList {
            project?.basePath?.let { add(Path.of(it)) }
            System.getProperty("user.home")?.let { home ->
                add(Path.of(home))
                add(Path.of(home, ".cursor"))
                add(Path.of(home, ".agents"))
                add(Path.of(home, ".codex"))
            }
        }
        val discovered = AgentConfigDiscovery().discover(roots)
            .ifEmpty { discoverMarkdownAgents(roots) }
            .distinctBy { it.id.lowercase() }
        if (discovered.isNotEmpty()) {
            return discovered.map { desc ->
                ComposerPlusEntry(
                    title = prettyName(desc.name),
                    description = readDescription(Path.of(desc.sourcePath))
                        ?: "Gắn tác nhân @${desc.name} vào tin nhắn",
                    insertText = "@${desc.name} ",
                    section = ComposerPlusEntry.Section.AGENT,
                )
            }
        }
        return FALLBACK_AGENTS
    }

    private fun discoverMarkdownAgents(roots: List<Path>): List<AgentDescriptor> {
        val out = ArrayList<AgentDescriptor>()
        val relativeDirs = listOf(
            Path.of(".codex", "agents"),
            Path.of("agents"),
            Path.of(".agents", "agents"),
        )
        for (root in roots) {
            for (rel in relativeDirs) {
                val dir = root.resolve(rel)
                if (!Files.isDirectory(dir)) continue
                Files.list(dir).use { stream ->
                    stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".md") }
                        .forEach { path ->
                            val id = path.fileName.toString().substringBeforeLast('.')
                            if (id.equals("MIGRATION", ignoreCase = true) || id.equals("README", ignoreCase = true)) {
                                return@forEach
                            }
                            out += AgentDescriptor(
                                id = id,
                                name = id,
                                sourcePath = path.toAbsolutePath().normalize().toString(),
                            )
                        }
                }
            }
        }
        return out.sortedBy { it.id }
    }

    private fun prettyName(raw: String): String =
        raw.split('-', '_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }

    private fun readDescription(path: Path): String? {
        if (!path.isRegularFile()) return null
        return try {
            val lines = Files.readAllLines(path).map { it.trimEnd() }
            var inFront = false
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line == "---") {
                    inFront = !inFront
                    continue
                }
                if (inFront) {
                    if (line.startsWith("description:")) {
                        return summarizeDescription(
                            line.removePrefix("description:").trim().trim('"', '\''),
                        )
                    }
                    continue
                }
                if (line.startsWith("#") || line.isEmpty()) continue
                return summarizeDescription(line.trimStart('>', '-', '*', ' '))
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** One short subtitle line — no raw \\n, no long example blocks. */
    internal fun summarizeDescription(raw: String): String {
        val unescaped = raw
            .replace("\\n", "\n")
            .replace("\\t", " ")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
        val first = unescaped
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return ""
        val cutExamples = first
            .let { s ->
                val idx = listOf("Examples:", "<example>", "examples:")
                    .map { s.indexOf(it, ignoreCase = true) }
                    .filter { it >= 0 }
                    .minOrNull()
                if (idx != null) s.take(idx) else s
            }
            .trim()
            .trimEnd('.', ',', ';', ':')
        val compact = cutExamples.replace(Regex("\\s+"), " ")
        return if (compact.length <= 110) compact else compact.take(109).trimEnd() + "…"
    }

    private val FALLBACK_AGENTS = listOf(
        ComposerPlusEntry("Advisor", "Interview-driven advisory workflow that reframes fuzzy ideas into requirements and goals.", "@advisor ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Brainstormer", "Challenge assumptions, compare architectural options, and document trade-offs.", "@brainstormer ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Code Reviewer", "Review code for correctness, regressions, security, and maintainability.", "@code-reviewer ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Code Simplifier", "Simplify code for clarity while preserving behavior.", "@code-simplifier ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Debugger", "Investigate failures through evidence-driven root cause analysis.", "@debugger ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Docs Manager", "Create and maintain technical documentation verified against the codebase.", "@docs-manager ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Explore", "Fast read-only codebase scanner for locating files and summarizing context.", "@explore ", ComposerPlusEntry.Section.AGENT),
        ComposerPlusEntry("Fullstack Developer", "Implement backend, frontend, or infrastructure phases within file boundaries.", "@fullstack-developer ", ComposerPlusEntry.Section.AGENT),
    )
}

class ComposerPlusMenuPanel(
    entries: List<ComposerPlusEntry>,
    private val onPick: (ComposerPlusEntry) -> Unit,
) : JPanel(BorderLayout()) {
    private val focusableRows = ArrayList<JPanel>()
    private val rowEntries = ArrayList<ComposerPlusEntry>()

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CodexUiTheme.border, 1, true),
            JBUI.Borders.empty(8, 0),
        )
        background = CodexUiTheme.background
        preferredSize = Dimension(380, 440)
        isFocusCycleRoot = true

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 4)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val addItems = entries.filter { it.section == ComposerPlusEntry.Section.ADD }
        val agentItems = entries.filter { it.section == ComposerPlusEntry.Section.AGENT }
        if (addItems.isNotEmpty()) {
            body.add(sectionLabel("Thêm"))
            addItems.forEach { body.add(row(it)) }
            body.add(Box.createVerticalStrut(8))
        }
        if (agentItems.isNotEmpty()) {
            body.add(sectionLabel("Tác nhân"))
            agentItems.forEach { body.add(row(it)) }
        }
        add(JBScrollPane(body).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)

        // Arrow-key navigation between rows when the panel holds focus.
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0), "row-next")
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0), "row-prev")
        actionMap.put(
            "row-next",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = moveRow(1)
            },
        )
        actionMap.put(
            "row-prev",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = moveRow(-1)
            },
        )
        focusableRows.firstOrNull()?.requestFocusInWindow()
    }

    /** Test seam: rows that can receive keyboard focus. */
    fun focusableRowCount(): Int = focusableRows.size

    fun requestFocusFirstRow(): Boolean =
        focusableRows.firstOrNull()?.requestFocusInWindow() == true

    fun rowAccessibleName(index: Int): String? =
        focusableRows.getOrNull(index)?.accessibleContext?.accessibleName

    fun rowToolTip(index: Int): String? =
        focusableRows.getOrNull(index)?.toolTipText

    fun activateRowAt(index: Int): Boolean {
        val entry = rowEntries.getOrNull(index) ?: return false
        onPick(entry)
        return true
    }

    fun dispatchEnterOnRow(index: Int): Boolean {
        val row = focusableRows.getOrNull(index) ?: return false
        row.dispatchEvent(
            java.awt.event.KeyEvent(
                row,
                java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                java.awt.event.KeyEvent.VK_ENTER,
                '\n',
            ),
        )
        return true
    }

    fun rowHasActivationKeys(index: Int): Boolean {
        val row = focusableRows.getOrNull(index) ?: return false
        return row.keyListeners.isNotEmpty() && row.isFocusable
    }

    private fun moveRow(delta: Int) {
        if (focusableRows.isEmpty()) return
        val current = focusableRows.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
        val next = (current + delta).floorMod(focusableRows.size)
        focusableRows[next].requestFocusInWindow()
    }

    private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m

    private fun sectionLabel(text: String): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 12, 2, 12)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            add(JBLabel(text).apply {
                foreground = CodexUiTheme.muted
                font = font.deriveFont(Font.BOLD, CodexUiFonts.SECONDARY)
            })
            add(Box.createHorizontalGlue())
        }

    private fun row(entry: ComposerPlusEntry): JPanel {
        val title = JBLabel(entry.title).apply {
            font = font.deriveFont(Font.PLAIN, CodexUiFonts.BODY)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val desc = JBLabel(entry.description).apply {
            foreground = CodexUiTheme.muted
            font = font.deriveFont(Font.PLAIN, CodexUiFonts.SECONDARY)
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = entry.description
        }
        val textCol = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(title)
            add(Box.createVerticalStrut(2))
            add(desc)
        }
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = CodexUiTheme.background
            border = JBUI.Borders.empty(8, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            isFocusable = true
            toolTipText = "${entry.title} — ${entry.description}"
            getAccessibleContext().accessibleName = entry.title
            getAccessibleContext().accessibleDescription = entry.description
            add(textCol, BorderLayout.CENTER)
        }
        // Keep title + subtitle packed; do not let BoxLayout stretch the row.
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)

        fun paintState(hovered: Boolean, focused: Boolean) {
            panel.background = when {
                focused -> CodexUiTheme.selectionBg
                hovered -> CodexUiTheme.hoverBg
                else -> CodexUiTheme.background
            }
            title.foreground = if (focused) CodexUiTheme.selectionFg else CodexUiTheme.foreground
            desc.foreground = if (focused) CodexUiTheme.selectionFg else CodexUiTheme.muted
            panel.repaint()
        }

        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (!panel.hasFocus()) paintState(hovered = true, focused = false)
            }

            override fun mouseExited(e: MouseEvent) {
                if (!panel.hasFocus()) paintState(hovered = false, focused = false)
            }

            override fun mouseClicked(e: MouseEvent) {
                onPick(entry)
            }
        })
        panel.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                paintState(hovered = false, focused = true)
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                paintState(hovered = false, focused = false)
            }
        })
        panel.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ENTER,
                    java.awt.event.KeyEvent.VK_SPACE,
                    -> {
                        e.consume()
                        onPick(entry)
                    }
                }
            }
        })
        focusableRows += panel
        rowEntries += entry
        return panel
    }
}
