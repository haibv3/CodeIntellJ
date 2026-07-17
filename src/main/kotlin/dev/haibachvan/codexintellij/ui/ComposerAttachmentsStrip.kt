package dev.haibachvan.codexintellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Horizontal strip of composer attachments: image thumbnails + file chips (with remove).
 */
class ComposerAttachmentsStrip(
    private val onChanged: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val items = LinkedHashMap<String, ComposerAttachment>()
    private val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        isOpaque = false
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8, 2, 8)
        add(row, BorderLayout.CENTER)
        isVisible = false
    }

    fun attachments(): List<ComposerAttachment> = items.values.toList()

    fun isEmpty(): Boolean = items.isEmpty()

    fun addAll(list: List<ComposerAttachment>) {
        list.forEach { att -> items.putIfAbsent(att.absolutePath.toString(), att) }
        rebuild()
    }

    fun clear() {
        items.clear()
        rebuild()
    }

    private fun remove(id: String) {
        items.entries.removeIf { it.value.id == id }
        rebuild()
    }

    private fun rebuild() {
        row.removeAll()
        items.values.forEach { att ->
            row.add(
                when (att.kind) {
                    ComposerAttachment.Kind.IMAGE -> imageChip(att)
                    ComposerAttachment.Kind.FILE, ComposerAttachment.Kind.FOLDER -> fileChip(att)
                },
            )
        }
        isVisible = items.isNotEmpty()
        revalidate()
        repaint()
        onChanged()
    }

    private fun imageChip(att: ComposerAttachment): JPanel {
        val size = 52
        val thumb = loadThumbnail(att, size)
        val panel = JPanel(null).apply {
            isOpaque = false
            preferredSize = Dimension(size + 6, size + 6)
            maximumSize = preferredSize
            toolTipText = att.displayPath
        }
        val imageLabel = JBLabel(thumb).apply {
            setBounds(0, 6, size, size)
            border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
            horizontalAlignment = SwingConstants.CENTER
        }
        val close = closeButton().apply {
            setBounds(size - 10, 0, 18, 18)
            addActionListener { remove(att.id) }
        }
        panel.add(imageLabel)
        panel.add(close)
        return panel
    }

    private fun fileChip(att: ComposerAttachment): JPanel {
        val chip = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = true
            background = JBColor(Color(0x3A3A3A), Color(0x3A3A3A))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0x4A4A4A), Color(0x4A4A4A)), 1, true),
                JBUI.Borders.empty(6, 10, 6, 6),
            )
            preferredSize = Dimension(180, 44)
            maximumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = att.displayPath
        }
        val icon = JBLabel(
            if (att.kind == ComposerAttachment.Kind.FOLDER) {
                AllIcons.Nodes.Folder
            } else {
                AllIcons.FileTypes.Any_type
            },
        )
        val texts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(att.fileName).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(1))
            add(JBLabel(att.subtitle).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 10f)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        chip.add(icon, BorderLayout.WEST)
        chip.add(texts, BorderLayout.CENTER)
        chip.add(closeButton().also { it.addActionListener { remove(att.id) } }, BorderLayout.EAST)
        return chip
    }

    private fun closeButton(): JButton =
        object : JButton() {
            init {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                preferredSize = Dimension(18, 18)
                minimumSize = preferredSize
                maximumSize = preferredSize
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Gỡ đính kèm"
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val d = minOf(width, height) - 2
                val x = (width - d) / 2
                val y = (height - d) / 2
                g2.color = JBColor(Color(0x555555), Color(0x555555))
                g2.fillOval(x, y, d, d)
                g2.color = JBColor(Color(0xE0E0E0), Color(0xE0E0E0))
                g2.stroke = java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                val pad = (d * 0.28).toInt()
                g2.drawLine(x + pad, y + pad, x + d - pad, y + d - pad)
                g2.drawLine(x + d - pad, y + pad, x + pad, y + d - pad)
                g2.dispose()
            }
        }.also { btn ->
            btn.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = btn.repaint()
                override fun mouseExited(e: MouseEvent) = btn.repaint()
            })
        }

    private fun loadThumbnail(att: ComposerAttachment, size: Int): ImageIcon {
        return try {
            Files.newInputStream(att.absolutePath).use { stream ->
                val img = ImageIO.read(stream) ?: return placeholderIcon(size)
                val scaled = img.getScaledInstance(size, size, Image.SCALE_SMOOTH)
                ImageIcon(scaled)
            }
        } catch (_: Exception) {
            placeholderIcon(size)
        }
    }

    private fun placeholderIcon(size: Int): ImageIcon {
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0x4A4A4A)
        g.fill(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 6f, 6f))
        g.color = Color(0xAAAAAA)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
        g.drawString("IMG", size / 2 - 10, size / 2 + 4)
        g.dispose()
        return ImageIcon(img)
    }
}
