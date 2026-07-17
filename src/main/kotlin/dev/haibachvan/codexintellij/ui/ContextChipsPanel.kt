package dev.haibachvan.codexintellij.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.platform.ContextAttachmentStore
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class ContextChipsPanel(
    private val store: ContextAttachmentStore,
) : JPanel(BorderLayout()) {
    private val list = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        add(list, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        list.removeAll()
        store.all().forEachIndexed { idx, enc ->
            val row = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
            }
            val warn = if (enc.preview.diskReadDeferredWarning) " (server may re-read disk)" else ""
            row.add(
                JBLabel("context#$idx · ${enc.sha256.take(12)}$warn").apply {
                    foreground = CodexUiTheme.muted
                    font = CodexUiFonts.meta()
                },
                BorderLayout.CENTER,
            )
            row.add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(
                        JButton("Remove").also {
                            it.isBorderPainted = false
                            it.isContentAreaFilled = false
                            it.foreground = CodexUiTheme.muted
                            it.font = CodexUiFonts.meta()
                            it.addActionListener {
                                val all = store.all()
                                store.clear()
                                all.forEachIndexed { i, v -> if (i != idx) store.put("c$i", v) }
                                refresh()
                            }
                        },
                    )
                },
                BorderLayout.EAST,
            )
            list.add(row)
        }
        isVisible = store.all().isNotEmpty()
        revalidate()
        repaint()
    }
}
