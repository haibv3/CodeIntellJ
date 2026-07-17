package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.commands.CommandRouteSpec
import dev.haibachvan.codexintellij.commands.SkillSlashDiscovery
import dev.haibachvan.codexintellij.commands.SlashCommandRegistry
import dev.haibachvan.codexintellij.settings.CodexSettingsState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Inline `/` and `@` suggestions for the composer (sectioned, Codex-like).
 *
 * `/` → built-in slash commands + skills from `~/.agents/skills` (and peers)
 * `@` → Thêm · Tệp/thư mục · Tác nhân
 */
class ComposerSuggestionController(
    private val project: Project?,
    private val composer: JTextComponent,
    private val onAttachPath: (ComposerAttachment) -> Unit,
    private val onPlusAction: (ComposerPlusEntry) -> Unit = {},
) {
    sealed class Row {
        data class Header(val title: String) : Row()
        data class Slash(val spec: CommandRouteSpec, val enabled: Boolean) : Row()
        data class Skill(val skill: SkillSlashDiscovery.SkillSlash) : Row()
        data class Path(val hit: PathMentionIndex.Hit) : Row()
        data class Plus(val entry: ComposerPlusEntry) : Row()
        data class Agent(val entry: ComposerPlusEntry) : Row()
    }

    private val listModel = DefaultListModel<Row>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 10
        // Variable row heights: title+subtitle need ~52px; section headers stay compact.
        cellRenderer = SuggestionRenderer()
    }
    private var popup: JBPopup? = null
    private var popupPanel: JPanel? = null
    private var activeTokenStart: Int = -1
    private var activeTokenEnd: Int = -1
    private var debounceSeq: Long = 0L
    private var mode: Char? = null

    @Volatile
    private var cachedSkills: List<SkillSlashDiscovery.SkillSlash>? = null

    init {
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 1) acceptSelected()
            }
        })
    }

    fun install() {
        composer.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleRefresh()
            override fun removeUpdate(e: DocumentEvent?) = scheduleRefresh()
            override fun changedUpdate(e: DocumentEvent?) = scheduleRefresh()
        })
        composer.addCaretListener { scheduleRefresh() }
        composer.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (popup?.isVisible != true) return
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        moveSelection(1); e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        moveSelection(-1); e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        hide(); e.consume()
                    }
                    KeyEvent.VK_TAB -> {
                        if (acceptSelected()) e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        if (acceptSelected()) e.consume()
                    }
                }
            }
        })
    }

    fun acceptSelected(): Boolean {
        if (popup?.isVisible != true) return false
        val row = selectedSelectable() ?: return true
        apply(row)
        return true
    }

    fun isShowing(): Boolean = popup?.isVisible == true

    fun hide() {
        popup?.cancel()
        popup = null
        popupPanel = null
        listModel.clear()
        activeTokenStart = -1
        activeTokenEnd = -1
        mode = null
    }

    fun invalidateSkillCache() {
        cachedSkills = null
    }

    private fun skills(): List<SkillSlashDiscovery.SkillSlash> {
        cachedSkills?.let { return it }
        val discovered = SkillSlashDiscovery.discover(project?.basePath)
        cachedSkills = discovered
        return discovered
    }

    private fun scheduleRefresh() {
        val seq = ++debounceSeq
        ApplicationManager.getApplication().invokeLater {
            if (seq != debounceSeq) return@invokeLater
            javax.swing.Timer(40) {
                if (seq != debounceSeq) return@Timer
                refresh()
            }.also {
                it.isRepeats = false
                it.start()
            }
        }
    }

    private fun refresh() {
        if (!composer.isShowing || !composer.isEnabled) {
            hide()
            return
        }
        val text = composer.text ?: ""
        val caret = composer.caretPosition.coerceIn(0, text.length)
        val token = tokenAt(text, caret)
        if (token == null) {
            hide()
            return
        }
        activeTokenStart = token.start
        activeTokenEnd = token.end
        mode = token.trigger
        when (token.trigger) {
            '/' -> showSlash(token.query)
            '@' -> showAt(token.query)
            else -> hide()
        }
    }

    private fun showSlash(query: String) {
        val settings = runCatching { service<CodexSettingsState>().state }.getOrNull()
        val experimental = settings?.experimentalApiOptIn == true
        val available = SlashCommandRegistry.available(experimental, signedIn = false)
            .map { it.name }
            .toSet()
        val q = query.trim().removePrefix("/").lowercase()
        val builtin = SlashCommandUi.ordered()
            .filter { SlashCommandUi.matches(it, query) }
        val skillRows = skills().filter { skill ->
            q.isEmpty() ||
                skill.name.contains(q, ignoreCase = true) ||
                skill.description.contains(q, ignoreCase = true)
        }
        val rows = buildList {
            if (builtin.isNotEmpty()) {
                add(Row.Header("Lệnh"))
                builtin.forEach { spec ->
                    // Still selectable so the token is inserted; unavailable RPCs stay gray.
                    add(Row.Slash(spec, enabled = spec.name in available))
                }
            }
            if (skillRows.isNotEmpty()) {
                add(Row.Header("Skills"))
                skillRows.forEach { add(Row.Skill(it)) }
            }
        }
        present(rows)
    }

    private fun showAt(query: String) {
        val q = query.removePrefix("@")
        val addRows = ComposerPlusCatalog.addEntries()
            .filter { entryMatches(it, q) }
            .map { Row.Plus(it) }
        val agentRows = ComposerPlusCatalog.agentEntries(project)
            .filter { entryMatches(it, q) }
            .take(20)
            .map { Row.Agent(it) }

        // Show Thêm + agents immediately; fill files as soon as search returns.
        val immediate = buildList {
            if (addRows.isNotEmpty()) {
                add(Row.Header("Thêm"))
                addAll(addRows)
            }
            if (agentRows.isNotEmpty()) {
                add(Row.Header("Tác nhân"))
                addAll(agentRows)
            }
        }
        present(immediate)

        val proj = project
        if (proj == null) return
        val seq = debounceSeq
        ApplicationManager.getApplication().executeOnPooledThread {
            val paths = runCatching { PathMentionIndex.search(proj, q, limit = 35) }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                if (seq != debounceSeq || mode != '@') return@invokeLater
                val pathRows = paths.map { Row.Path(it) }
                val merged = buildList {
                    if (addRows.isNotEmpty()) {
                        add(Row.Header("Thêm"))
                        addAll(addRows)
                    }
                    if (pathRows.isNotEmpty()) {
                        add(Row.Header("Tệp"))
                        addAll(pathRows)
                    }
                    if (agentRows.isNotEmpty()) {
                        add(Row.Header("Tác nhân"))
                        addAll(agentRows)
                    }
                }
                present(merged)
            }
        }
    }

    private fun entryMatches(entry: ComposerPlusEntry, q: String): Boolean {
        if (q.isBlank()) return true
        return entry.title.contains(q, ignoreCase = true) ||
            entry.description.contains(q, ignoreCase = true) ||
            entry.insertText.contains(q, ignoreCase = true)
    }

    private fun present(rows: List<Row>) {
        val selectable = rows.filter { it !is Row.Header }
        if (selectable.isEmpty()) {
            hide()
            return
        }
        val previous = list.selectedValue
        listModel.clear()
        rows.forEach { listModel.addElement(it) }
        val keep = previous?.takeIf { row ->
            row !is Row.Header && (0 until listModel.size()).any { listModel.getElementAt(it) == row }
        }
        if (keep != null) {
            list.setSelectedValue(keep, true)
        } else {
            list.selectedIndex = rows.indexOfFirst { it !is Row.Header }.coerceAtLeast(0)
        }

        val visible = rows.take(10)
        val height = JBUI.scale(
            visible.sumOf { if (it is Row.Header) HEADER_HEIGHT else ROW_HEIGHT } + 8,
        )
        val width = JBUI.scale(440)
        if (popup?.isVisible == true) {
            popupPanel?.preferredSize = Dimension(width, height)
            popup?.pack(true, true)
            return
        }
        val panel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(4, 0),
            )
            preferredSize = Dimension(width, height)
            add(JScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }
        popupPanel = panel
        val next = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, list)
            .setRequestFocus(false)
            .setFocusable(false)
            .setMovable(false)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(false)
            .createPopup()
        popup = next
        next.show(anchorPoint(panel.preferredSize.height))
    }

    private fun anchorPoint(popupHeight: Int): RelativePoint {
        // Anchor to the composer component (not raw screen coords).
        val y = -(popupHeight + JBUI.scale(6))
        return RelativePoint(composer, Point(JBUI.scale(4), y))
    }

    private fun selectedSelectable(): Row? {
        var idx = list.selectedIndex
        if (idx < 0) idx = listModel.size() - 1
        if (idx < 0) return null
        val cur = listModel.getElementAt(idx)
        if (cur !is Row.Header) return cur
        // Advance to next selectable
        for (i in idx until listModel.size()) {
            val row = listModel.getElementAt(i)
            if (row !is Row.Header) return row
        }
        return null
    }

    private fun moveSelection(delta: Int) {
        if (listModel.isEmpty) return
        var idx = list.selectedIndex
        if (idx < 0) idx = 0
        repeat(listModel.size()) {
            idx = (idx + delta).mod(listModel.size())
            if (listModel.getElementAt(idx) !is Row.Header) {
                list.selectedIndex = idx
                list.ensureIndexIsVisible(idx)
                return
            }
        }
    }

    private fun toNioPathOrNull(file: VirtualFile): Path? =
        try {
            file.toNioPath()
        } catch (_: Exception) {
            try {
                file.fileSystem.getNioPath(file)
            } catch (_: Exception) {
                null
            }
        }

    private fun apply(row: Row) {
        val start = activeTokenStart
        val end = activeTokenEnd.coerceAtLeast(start)
        if (start < 0) return
        when (row) {
            is Row.Header -> return
            is Row.Slash -> {
                composer.select(start, end)
                composer.replaceSelection(row.spec.name + " ")
            }
            is Row.Skill -> {
                composer.select(start, end)
                composer.replaceSelection(row.skill.insertText)
            }
            is Row.Path -> {
                val mention = "@${row.hit.relativePath} "
                composer.select(start, end)
                composer.replaceSelection(mention)
                toNioPathOrNull(row.hit.file)?.let { nio ->
                    onAttachPath(ComposerAttachment.fromPath(nio, row.hit.relativePath))
                }
            }
            is Row.Plus -> {
                when (row.entry.action) {
                    ComposerPlusEntry.Action.INSERT -> {
                        composer.select(start, end)
                        composer.replaceSelection(row.entry.insertText)
                    }
                    ComposerPlusEntry.Action.ATTACH_FILES,
                    ComposerPlusEntry.Action.ATTACH_FOLDER,
                    -> {
                        composer.select(start, end)
                        composer.replaceSelection("")
                        onPlusAction(row.entry)
                    }
                }
            }
            is Row.Agent -> {
                composer.select(start, end)
                composer.replaceSelection(row.entry.insertText.ifBlank { "@${row.entry.title} " })
            }
        }
        hide()
        composer.requestFocusInWindow()
    }

    private class SuggestionRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val row = value as? Row
            val width = (list?.width?.takeIf { it > 0 } ?: JBUI.scale(440))
            if (row is Row.Header) {
                return JLabel(row.title).apply {
                    border = JBUI.Borders.empty(8, 12, 2, 12)
                    font = font.deriveFont(Font.BOLD, CodexUiFonts.SECONDARY)
                    foreground = JBColor.GRAY
                    isOpaque = true
                    background = list?.background
                    preferredSize = Dimension(width, JBUI.scale(HEADER_HEIGHT))
                    verticalAlignment = CENTER
                }
            }
            val title: String
            val subtitle: String
            val enabled: Boolean
            when (row) {
                is Row.Slash -> {
                    val meta = SlashCommandUi.meta(row.spec)
                    title = meta.title
                    subtitle = meta.description
                    enabled = row.enabled
                }
                is Row.Skill -> {
                    title = row.skill.name
                    subtitle = row.skill.description
                    enabled = true
                }
                is Row.Path -> {
                    title = row.hit.relativePath
                    subtitle = if (row.hit.isDirectory) "Thư mục" else "Tệp"
                    enabled = true
                }
                is Row.Plus -> {
                    title = row.entry.title
                    subtitle = row.entry.description
                    enabled = true
                }
                is Row.Agent -> {
                    title = row.entry.title
                    subtitle = row.entry.description
                    enabled = true
                }
                else -> {
                    title = ""
                    subtitle = ""
                    enabled = true
                }
            }
            val panel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(8, 12, 8, 12)
                isOpaque = true
                background = when {
                    isSelected -> list?.selectionBackground ?: background
                    else -> list?.background ?: background
                }
                alignmentX = LEFT_ALIGNMENT
            }
            val titleLabel = JLabel(title).apply {
                font = font.deriveFont(Font.PLAIN, CodexUiFonts.BODY)
                foreground = when {
                    !enabled -> JBUI.CurrentTheme.Label.disabledForeground()
                    isSelected -> list?.selectionForeground
                    else -> list?.foreground
                }
                alignmentX = LEFT_ALIGNMENT
            }
            val subLabel = JLabel(subtitle).apply {
                font = font.deriveFont(Font.PLAIN, CodexUiFonts.SECONDARY)
                foreground = if (isSelected) {
                    list?.selectionForeground ?: JBColor.GRAY
                } else {
                    JBColor.GRAY
                }
                alignmentX = LEFT_ALIGNMENT
            }
            panel.add(titleLabel)
            panel.add(Box.createVerticalStrut(JBUI.scale(3)))
            panel.add(subLabel)
            panel.preferredSize = Dimension(width, JBUI.scale(ROW_HEIGHT))
            panel.minimumSize = panel.preferredSize
            panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))
            return panel
        }
    }

    data class Token(val trigger: Char, val query: String, val start: Int, val end: Int)

    companion object {
        private const val ROW_HEIGHT = 52
        private const val HEADER_HEIGHT = 28

        fun tokenAt(text: String, caret: Int): Token? {
            if (caret < 0 || caret > text.length) return null
            var i = caret - 1
            while (i >= 0 && !text[i].isWhitespace() && text[i] != '\n') {
                i--
            }
            val start = i + 1
            if (start >= caret) return null
            val token = text.substring(start, caret)
            if (token.startsWith("/")) {
                if (token.length >= 2 && token[1] == '/') return null
                if (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] != '\n') return null
                return Token('/', token, start, caret)
            }
            if (token.startsWith("@")) {
                return Token('@', token, start, caret)
            }
            return null
        }
    }
}
