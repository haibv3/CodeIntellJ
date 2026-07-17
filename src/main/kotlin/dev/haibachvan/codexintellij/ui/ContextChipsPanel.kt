package dev.haibachvan.codexintellij.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.haibachvan.codexintellij.platform.ContextAttachmentStore
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class ContextChipsPanel(
    private val store: ContextAttachmentStore,
) : JPanel(BorderLayout()) {
    private val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        border = JBUI.Borders.empty(4)
        add(list, BorderLayout.CENTER)
        refresh()
    }

    fun refresh() {
        list.removeAll()
        store.all().forEachIndexed { idx, enc ->
            val row = JPanel(BorderLayout())
            val warn = if (enc.preview.diskReadDeferredWarning) " (server may re-read disk)" else ""
            row.add(JBLabel("context#$idx sha=${enc.sha256.take(12)}$warn"), BorderLayout.CENTER)
            row.add(
                JButton("Remove").also {
                    it.addActionListener {
                        val all = store.all()
                        store.clear()
                        all.forEachIndexed { i, v -> if (i != idx) store.put("c$i", v) }
                        refresh()
                    }
                },
                BorderLayout.EAST,
            )
            list.add(row)
        }
        revalidate()
        repaint()
    }
}
