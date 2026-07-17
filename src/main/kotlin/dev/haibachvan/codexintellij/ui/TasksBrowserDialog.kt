package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * Full task browser opened from "Xem tất cả".
 */
class TasksBrowserDialog(
    project: Project,
    private val rows: List<CodexWorkspacePanel.TaskRow>,
    private val onOpen: (CodexWorkspacePanel.TaskRow) -> Unit,
    private val onDelete: (CodexWorkspacePanel.TaskRow) -> Unit,
) : DialogWrapper(project) {
    private val listModel = DefaultListModel<CodexWorkspacePanel.TaskRow>().apply {
        rows.forEach { addElement(it) }
    }
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 16
        cellRenderer = TaskRowRenderer()
        fixedCellHeight = JBUI.scale(32)
    }

    init {
        title = "Tất cả nhiệm vụ"
        init()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index < 0) return
                val row = listModel.getElementAt(index) ?: return
                val bounds = list.getCellBounds(index, index) ?: return
                val inDelete = e.x >= bounds.x + bounds.width - JBUI.scale(36)
                if (inDelete || e.button == MouseEvent.BUTTON3) {
                    val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                        contentPanel,
                        "Xóa nhiệm vụ “${row.title}”? Thao tác không hoàn tác được.",
                        "Xóa nhiệm vụ",
                        com.intellij.openapi.ui.Messages.getWarningIcon(),
                    )
                    if (confirm == com.intellij.openapi.ui.Messages.YES) {
                        onDelete(row)
                        listModel.removeElement(row)
                        if (listModel.isEmpty) {
                            close(CANCEL_EXIT_CODE)
                        }
                    }
                } else if (e.clickCount >= 1 && e.button == MouseEvent.BUTTON1) {
                    onOpen(row)
                    close(OK_EXIT_CODE)
                }
            }
        })
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(420), JBUI.scale(420))
            border = JBUI.Borders.empty(8)
        }
        if (rows.isEmpty()) {
            panel.add(
                JLabel("Chưa có nhiệm vụ nào.", SwingConstants.CENTER),
                BorderLayout.CENTER,
            )
        } else {
            panel.add(JBScrollPane(list), BorderLayout.CENTER)
            panel.add(
                JLabel("Click để mở · nút × để xoá").apply {
                    foreground = JBColor.GRAY
                    border = JBUI.Borders.emptyTop(8)
                },
                BorderLayout.SOUTH,
            )
        }
        return panel
    }

    override fun createActions() = arrayOf(cancelAction)
}

/** Shared list cell: title + delete affordance. */
class TaskRowRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val row = value as? CodexWorkspacePanel.TaskRow
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            isOpaque = true
            background = when {
                isSelected -> list?.selectionBackground ?: background
                else -> list?.background ?: background
            }
        }
        val title = JLabel(row?.title ?: "").apply {
            foreground = when {
                isSelected -> list?.selectionForeground
                else -> list?.foreground
            }
            font = font.deriveFont(Font.PLAIN, 13f)
        }
        val delete = JLabel("×").apply {
            foreground = JBColor.GRAY
            toolTipText = "Xóa nhiệm vụ"
            border = JBUI.Borders.empty(0, 8)
            horizontalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(JBUI.scale(28), preferredSize.height)
        }
        panel.add(title, BorderLayout.CENTER)
        panel.add(delete, BorderLayout.EAST)
        return panel
    }
}
