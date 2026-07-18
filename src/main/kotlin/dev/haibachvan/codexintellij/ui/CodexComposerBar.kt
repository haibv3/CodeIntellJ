package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.settings.ApprovalModeOption
import dev.haibachvan.codexintellij.settings.CodexModelOption
import dev.haibachvan.codexintellij.settings.ModelCatalog
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import kotlin.io.path.relativeToOrNull

/**
 * Composer footer: model / effort / permission / IDE context / plus-menu / send-stop.
 */
class CodexComposerBar(
    private val panelModel: ChatPanelModel,
    private val catalog: ModelCatalog,
    private val onSend: () -> Unit,
    private val onCancel: () -> Unit = {},
    private val project: Project? = null,
    private val placeholder: String = "Làm bất cứ điều gì",
) : JPanel(BorderLayout()) {
    private val composer = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8, 10)
        font = JBUI.Fonts.label().deriveFont(CodexUiFonts.BODY)
        toolTipText = placeholder
    }
    private val modelCombo = ComboBox<CodexModelOption>().apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                (value as? CodexModelOption)?.displayName ?: value,
                index,
                isSelected,
                cellHasFocus,
            )
        }
    }
    private val effortButton = selectorButton(
        text = CodexModelOption.effortLabel(panelModel.selectedEffort ?: "medium"),
        accessibleName = "Mức lập luận",
        toolTip = "Chọn mức reasoning (Thấp / Trung bình / Cao)",
    )
    private val approvalButton = selectorButton(
        text = panelModel.approvalMode.label,
        accessibleName = "Quyền quyết định",
        toolTip = "Chọn chế độ phê duyệt lệnh và ghi tệp",
    )
    private val plusButton = JButton("+").apply {
        toolTipText = "Đính kèm tệp, mục tiêu, kế hoạch hoặc tác nhân"
        isFocusable = true
        isFocusPainted = true
        preferredSize = Dimension(28, 28)
        getAccessibleContext().accessibleName = "Mở menu đính kèm"
        getAccessibleContext().accessibleDescription =
            "Đính kèm tệp, thư mục, mục tiêu, kế hoạch hoặc tác nhân"
    }
    private val ideContext = JCheckBox("Ngữ cảnh IDE", panelModel.ideContextEnabled)
    private val actionButton = CircularActionButton().apply {
        preferredSize = Dimension(34, 34)
        minimumSize = preferredSize
        maximumSize = preferredSize
        getAccessibleContext().accessibleName = "Gửi tin nhắn"
        getAccessibleContext().accessibleDescription = "Gửi tin nhắn Codex hoặc dừng phản hồi đang chạy"
    }
    private val attachmentsStrip = ComposerAttachmentsStrip()
    private val hint = JBLabel(placeholder).apply {
        foreground = CodexUiTheme.muted
        font = CodexUiFonts.secondary()
        border = JBUI.Borders.empty(0, 12, 4, 12)
    }

    @Volatile
    private var busy: Boolean = false

    private var plusPopup: JBPopup? = null
    private var selectorPopup: JBPopup? = null
    private var suggestions: ComposerSuggestionController? = null
    private var availableEfforts: List<String> = listOf("low", "medium", "high")

    init {
        border = JBUI.Borders.empty(8, 10, 10, 10)
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CodexUiTheme.cardBorder, 1, true),
                JBUI.Borders.empty(6, 6, 8, 6),
            )
            background = CodexUiTheme.cardBg
        }
        card.add(attachmentsStrip, BorderLayout.NORTH)
        card.add(JScrollPane(composer).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(100, 72)
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
        card.add(buildToolbar(), BorderLayout.SOUTH)
        add(hint, BorderLayout.NORTH)
        add(card, BorderLayout.CENTER)

        suggestions = ComposerSuggestionController(
            project = project,
            composer = composer,
            onAttachPath = { attachment ->
                attachmentsStrip.addAll(listOf(attachment))
            },
            onPlusAction = { entry ->
                when (entry.action) {
                    ComposerPlusEntry.Action.ATTACH_FILES -> attachFiles()
                    ComposerPlusEntry.Action.ATTACH_FOLDER -> attachFolder()
                    ComposerPlusEntry.Action.INSERT -> insertAtCaret(entry.insertText)
                }
            },
        ).also { it.install() }

        modelCombo.addActionListener { onModelSelected() }
        effortButton.addActionListener { showEffortPopup() }
        approvalButton.addActionListener { showApprovalPopup() }
        ideContext.addActionListener { panelModel.setIdeContextEnabled(ideContext.isSelected) }
        actionButton.addActionListener {
            if (busy) onCancel() else onSend()
        }
        plusButton.addActionListener { showPlusMenu() }
        composer.inputMap.put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            "codex-send",
        )
        composer.actionMap.put(
            "codex-send",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    if (suggestions?.acceptSelected() == true) return
                    if (!busy) onSend()
                }
            },
        )
        composer.inputMap.put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
            "insert-break",
        )
        setModels(catalog.cached())
        syncApprovalButton()
        setBusy(panelModel.isBusy)
    }

    fun text(): String = composer.text

    fun attachments(): List<ComposerAttachment> = attachmentsStrip.attachments()

    fun setText(value: String) {
        composer.text = value
    }

    fun clear() {
        composer.text = ""
        attachmentsStrip.clear()
    }

    fun canSend(): Boolean = composer.text.trim().isNotEmpty() || !attachmentsStrip.isEmpty()

    fun requestFocusComposer() {
        composer.requestFocusInWindow()
    }

    /** Sync toolbar controls from [panelModel] after slash commands mutate preferences. */
    fun syncFromModel() {
        ideContext.isSelected = panelModel.ideContextEnabled
        val desiredModel = panelModel.selectedModel
        if (!desiredModel.isNullOrBlank()) {
            val match = (0 until modelCombo.itemCount)
                .mapNotNull { modelCombo.getItemAt(it) }
                .firstOrNull { it.model == desiredModel }
            if (match != null) {
                modelCombo.selectedItem = match
            }
        }
        val desiredEffort = panelModel.selectedEffort
        if (!desiredEffort.isNullOrBlank()) {
            syncEffortButton(desiredEffort)
        }
        refreshHint()
    }

    fun setBusy(value: Boolean) {
        busy = value
        actionButton.busy = value
        actionButton.toolTipText = if (value) {
            "Dừng phản hồi hiện tại"
        } else {
            "Gửi (Enter) · Xuống dòng (Shift+Enter)"
        }
        actionButton.getAccessibleContext().accessibleName = if (value) {
            "Dừng phản hồi"
        } else {
            "Gửi tin nhắn"
        }
        composer.isEnabled = !value
        modelCombo.isEnabled = !value
        effortButton.isEnabled = !value
        approvalButton.isEnabled = !value
        plusButton.isEnabled = !value
        actionButton.repaint()
    }

    fun insertAtCaret(text: String) {
        val start = composer.selectionStart
        val end = composer.selectionEnd
        composer.replaceRange(text, start, end)
        composer.caretPosition = start + text.length
        composer.requestFocusInWindow()
    }

    fun setModels(models: List<CodexModelOption>) {
        val list = models.ifEmpty { ModelCatalog.FALLBACK }
        modelCombo.model = DefaultComboBoxModel(list.toTypedArray())
        val preferred = list.firstOrNull { it.model == panelModel.selectedModel }
            ?: list.firstOrNull { it.isDefault }
            ?: list.first()
        modelCombo.selectedItem = preferred
        onModelSelected()
    }

    private fun showPlusMenu() {
        plusPopup?.cancel()
        val entries = ComposerPlusCatalog.addEntries() + ComposerPlusCatalog.agentEntries(project)
        val panel = ComposerPlusMenuPanel(entries) { entry ->
            plusPopup?.cancel()
            when (entry.action) {
                ComposerPlusEntry.Action.INSERT -> insertAtCaret(entry.insertText)
                ComposerPlusEntry.Action.ATTACH_FILES -> attachFiles()
                ComposerPlusEntry.Action.ATTACH_FOLDER -> attachFolder()
            }
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        plusPopup = popup
        val screen = plusButton.locationOnScreen
        popup.show(
            RelativePoint(
                Point(
                    screen.x,
                    screen.y - panel.preferredSize.height - 6,
                ),
            ),
        )
        // Focus first menu row so Enter/Space/arrows work immediately.
        javax.swing.SwingUtilities.invokeLater {
            panel.requestFocusFirstRow()
        }
    }

    private fun attachFiles() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withTitle("Đính kèm tệp")
            .withDescription("Chọn ảnh hoặc tệp để gắn vào tin nhắn")
        val chosen = FileChooser.chooseFiles(descriptor, project, null)
        if (chosen.isEmpty()) return
        addAttachments(chosen.toList())
    }

    private fun attachFolder() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Đính kèm thư mục")
            .withDescription("Chọn thư mục để gắn vào tin nhắn")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        addAttachments(listOf(chosen))
    }

    private fun addAttachments(files: List<VirtualFile>) {
        val list = files.mapNotNull { vf ->
            val path = vf.toNioPathOrNull() ?: return@mapNotNull null
            ComposerAttachment.fromPath(path, mentionPath(path))
        }
        if (list.isNotEmpty()) {
            attachmentsStrip.addAll(list)
            composer.requestFocusInWindow()
        }
    }

    private fun VirtualFile.toNioPathOrNull(): Path? =
        try {
            fileSystem.getNioPath(this)
        } catch (_: Exception) {
            null
        }

    private fun mentionPath(path: Path): String {
        val base = project?.basePath?.let { Path.of(it) }
        val relative = base?.let { path.relativeToOrNull(it) }
        return (relative ?: path).normalize().toString().replace('\\', '/')
    }

    private fun onModelSelected() {
        val selected = modelCombo.selectedItem as? CodexModelOption ?: return
        panelModel.setSelectedModel(selected.model)
        availableEfforts = selected.efforts.ifEmpty { listOf("low", "medium", "high") }
        val current = panelModel.selectedEffort ?: selected.defaultEffort ?: "medium"
        val wire = if (availableEfforts.any { it.equals(current, ignoreCase = true) }) {
            current
        } else {
            selected.defaultEffort ?: availableEfforts.first()
        }
        panelModel.setSelectedEffort(wire)
        syncEffortButton(wire)
        refreshHint()
    }

    private fun refreshHint() {
        val model = modelCombo.selectedItem as? CodexModelOption
        val effort = effortButton.text.takeIf { it.isNotBlank() }
        hint.text = if (model == null) {
            placeholder
        } else {
            "${model.displayName}${effort?.let { " · $it" } ?: ""} — $placeholder"
        }
    }

    private fun syncEffortButton(wire: String) {
        effortButton.text = CodexModelOption.effortLabel(wire) + " ▾"
    }

    private fun syncApprovalButton() {
        approvalButton.text = panelModel.approvalMode.label + " ▾"
    }

    private fun showEffortPopup() {
        selectorPopup?.cancel()
        val items = availableEfforts.map { CodexModelOption.effortLabel(it) }
        val selectedLabel = CodexModelOption.effortLabel(panelModel.selectedEffort ?: "medium")
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Mức lập luận")
            .setSelectedValue(selectedLabel, true)
            .setItemChosenCallback { label ->
                val wire = CodexModelOption.effortWire(label)
                panelModel.setSelectedEffort(wire)
                syncEffortButton(wire)
                refreshHint()
            }
            .setRenderer(object : ColoredListCellRenderer<String>() {
                override fun customizeCellRenderer(
                    list: JList<out String>,
                    value: String?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(value ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            })
            .setCancelOnClickOutside(true)
            .setRequestFocus(true)
            .createPopup()
        selectorPopup = popup
        popup.showUnderneathOf(effortButton)
    }

    private fun showApprovalPopup() {
        selectorPopup?.cancel()
        val items = ApprovalModeOption.entries.toList()
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Quyền quyết định")
            .setSelectedValue(panelModel.approvalMode, true)
            .setItemChosenCallback { mode ->
                panelModel.setApprovalMode(mode)
                syncApprovalButton()
            }
            .setRenderer(object : ColoredListCellRenderer<ApprovalModeOption>() {
                override fun customizeCellRenderer(
                    list: JList<out ApprovalModeOption>,
                    value: ApprovalModeOption?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    if (value == null) return
                    append(value.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(value.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setCancelOnClickOutside(true)
            .setRequestFocus(true)
            .createPopup()
        selectorPopup = popup
        popup.showUnderneathOf(approvalButton)
    }

    private fun selectorButton(text: String, accessibleName: String, toolTip: String): JButton =
        JButton("$text ▾").apply {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = true
            isFocusable = true
            foreground = CodexUiTheme.foreground
            font = CodexUiFonts.body()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(2, 6)
            toolTipText = toolTip
            getAccessibleContext().accessibleName = accessibleName
            getAccessibleContext().accessibleDescription = toolTip
        }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(GridBagLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(plusButton)
            add(approvalButton)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(modelCombo)
            add(effortButton)
            add(ideContext)
            add(actionButton)
        }
        val c = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        bar.add(left, c)
        c.gridx = 1
        c.weightx = 0.0
        c.anchor = GridBagConstraints.EAST
        bar.add(right, c)
        return bar
    }

    private class CircularActionButton : JButton() {
        var busy: Boolean = false

        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isFocusable = true
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = minOf(width, height) - 2
            val x = (width - size) / 2
            val y = (height - size) / 2
            val fill = if (model.isEnabled) {
                CodexUiTheme.sendButtonFill
            } else {
                CodexUiTheme.sendButtonDisabled
            }
            g2.color = fill
            g2.fillOval(x, y, size, size)
            g2.color = CodexUiTheme.sendButtonGlyph
            if (busy) {
                val sq = (size * 0.34).toInt().coerceAtLeast(6)
                val sx = x + (size - sq) / 2
                val sy = y + (size - sq) / 2
                g2.fillRoundRect(sx, sy, sq, sq, 3, 3)
            } else {
                val cx = x + size / 2
                val cy = y + size / 2
                val shaft = (size * 0.28).toInt()
                val head = (size * 0.18).toInt()
                g2.stroke = java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g2.drawLine(cx, cy + shaft / 2, cx, cy - shaft / 2)
                g2.drawLine(cx, cy - shaft / 2, cx - head, cy - shaft / 2 + head)
                g2.drawLine(cx, cy - shaft / 2, cx + head, cy - shaft / 2 + head)
            }
            if (hasFocus() && model.isEnabled) {
                g2.color = CodexUiTheme.focusRing
                g2.stroke = java.awt.BasicStroke(1.6f)
                g2.drawOval(x - 1, y - 1, size + 2, size + 2)
            }
            g2.dispose()
        }
    }
}
