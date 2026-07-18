package dev.haibachvan.codexintellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
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
            border = BorderFactory.createLineBorder(CodexUiTheme.border, 1, true)
            horizontalAlignment = SwingConstants.CENTER
        }
        val close = closeButton(att.fileName).apply {
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
            background = CodexUiTheme.attachmentChipBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CodexUiTheme.attachmentChipBorder, 1, true),
                JBUI.Borders.empty(6, 10, 6, 6),
            )
            preferredSize = Dimension(180, 44)
            maximumSize = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = att.displayPath
            getAccessibleContext().accessibleName = "Đính kèm ${att.fileName}"
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
                font = font.deriveFont(Font.PLAIN, CodexUiFonts.SECONDARY)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(1))
            add(JBLabel(att.subtitle).apply {
                foreground = CodexUiTheme.muted
                font = font.deriveFont(Font.PLAIN, CodexUiFonts.META)
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        chip.add(icon, BorderLayout.WEST)
        chip.add(texts, BorderLayout.CENTER)
        chip.add(
            closeButton(att.fileName).also { it.addActionListener { remove(att.id) } },
            BorderLayout.EAST,
        )
        return chip
    }

    private fun closeButton(fileName: String): JButton =
        object : JButton() {
            init {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = true
                isFocusable = true
                preferredSize = Dimension(18, 18)
                minimumSize = preferredSize
                maximumSize = preferredSize
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Gỡ đính kèm"
                getAccessibleContext().accessibleName = "Gỡ đính kèm $fileName"
                getAccessibleContext().accessibleDescription = "Gỡ tệp đính kèm $fileName khỏi tin nhắn"
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val d = minOf(width, height) - 2
                val x = (width - d) / 2
                val y = (height - d) / 2
                g2.color = CodexUiTheme.attachmentCloseBg
                g2.fillOval(x, y, d, d)
                g2.color = CodexUiTheme.attachmentCloseFg
                g2.stroke = java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                val pad = (d * 0.28).toInt()
                g2.drawLine(x + pad, y + pad, x + d - pad, y + d - pad)
                g2.drawLine(x + d - pad, y + pad, x + pad, y + d - pad)
                if (hasFocus()) {
                    g2.color = CodexUiTheme.focusRing
                    g2.stroke = java.awt.BasicStroke(1.2f)
                    g2.drawOval(x - 1, y - 1, d + 2, d + 2)
                }
                g2.dispose()
            }
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
        g.color = CodexUiTheme.attachmentChipBg
        g.fill(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 6f, 6f))
        g.color = CodexUiTheme.muted
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, CodexUiFonts.META_PX)
        g.drawString("IMG", size / 2 - 10, size / 2 + 4)
        g.dispose()
        return ImageIcon(img)
    }
}
