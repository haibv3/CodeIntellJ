package dev.haibachvan.codexintellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Native Swing card matching Codex "Đã chỉnh sửa N tệp" — rounded corners,
 * horizontal actions, readable type. HTML cards cannot do this in JBHtmlPane.
 */
class ModifiedFilesCardPanel(
    private val project: Project,
    private val payload: ModifiedFilesActions.Payload,
    private val onUndo: (ModifiedFilesActions.Payload) -> Unit,
    private val onReview: (ModifiedFilesActions.Payload) -> Unit,
    private val onOpenFile: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val arc = CodexUiMetrics.radiusCard * 2f
    private val cardBg = CodexUiTheme.cardBg
    private val borderColor = CodexUiTheme.cardBorder
    private val divider = CodexUiTheme.cardDivider
    private val muted = CodexUiTheme.muted
    private val fg = CodexUiTheme.foreground
    private val green = CodexUiTheme.success
    private val red = CodexUiTheme.danger

    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 12, 14, 12)
        alignmentX = LEFT_ALIGNMENT
        add(buildInner(), BorderLayout.CENTER)
    }

    private fun buildInner(): JPanel {
        val inner = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(16, 18, 12, 18)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = cardBg
                g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.color = borderColor
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                g2.dispose()
                super.paintComponent(g)
            }
        }

        val n = payload.files.size
        val titleText = if (n == 1) "Đã chỉnh sửa 1 tệp" else "Đã chỉnh sửa $n tệp"
        val total = payload.total

        val header = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyBottom(4)
        }

        val titleCol = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentY = CENTER_ALIGNMENT
            val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(badgeLabel())
                add(JBLabel(titleText).apply {
                    foreground = fg
                    font = CodexUiFonts.title()
                })
            }
            val stats = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(6, 32, 0, 0)
                add(diffDelta(total.added, total.removed, barWidth = 52))
            }
            add(titleRow)
            add(stats)
            // Let the title shrink so action buttons keep full width.
            minimumSize = Dimension(0, preferredSize.height)
            maximumSize = Dimension(Integer.MAX_VALUE, preferredSize.height)
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            alignmentY = CENTER_ALIGNMENT
            add(linkButton("Hoàn tác", AllIcons.Actions.Rollback) { onUndo(payload) })
            add(reviewButton { onReview(payload) })
            val pref = preferredSize
            minimumSize = pref
            preferredSize = pref
            maximumSize = pref
        }

        header.add(titleCol)
        header.add(Box.createHorizontalStrut(8))
        header.add(Box.createHorizontalGlue())
        header.add(actions)
        inner.add(header, BorderLayout.NORTH)

        val list = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            payload.files.forEach { file ->
                add(fileRow(file))
            }
        }
        inner.add(list, BorderLayout.CENTER)
        return inner
    }

    private fun fileRow(file: ModifiedFilesActions.FileRow): JPanel {
        val display = CodexNativeDiff.relativizePath(file.path, project)
        return JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, divider),
                JBUI.Borders.empty(12, 0, 12, 0),
            )
            val pathButton = JButton(display).apply {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = true
                isFocusable = true
                horizontalAlignment = SwingConstants.LEFT
                foreground = muted
                font = CodexUiFonts.body()
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = file.path
                margin = JBUI.emptyInsets()
                getAccessibleContext().accessibleName = "Mở tệp $display"
                getAccessibleContext().accessibleDescription = "Mở ${file.path} trong trình soạn thảo"
                addActionListener { onOpenFile(file.path) }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent?) {
                        foreground = CodexUiTheme.accent
                    }
                    override fun mouseExited(e: MouseEvent?) {
                        foreground = muted
                    }
                })
            }
            add(pathButton, BorderLayout.CENTER)
            add(diffDelta(file.counts.added, file.counts.removed, barWidth = 40), BorderLayout.EAST)
        }
    }

    private fun diffDelta(added: Int, removed: Int, barWidth: Int): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(DiffSparkline(added, removed, barWidth))
            add(JLabel("+$added").apply {
                foreground = green
                font = CodexUiFonts.secondary()
            })
            add(JLabel("−$removed").apply {
                foreground = red
                font = CodexUiFonts.secondary()
            })
        }
        return panel
    }

    private fun badgeLabel(): JComponent {
        return object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(CodexUiMetrics.control24, CodexUiMetrics.control24)
                minimumSize = preferredSize
                maximumSize = preferredSize
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = CodexUiTheme.codeBg
                val arc = CodexUiMetrics.radiusControl * 2f
                g2.fill(RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, arc, arc))
                g2.color = CodexUiTheme.cardBorder
                g2.draw(RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, arc, arc))
                g2.color = CodexUiTheme.foreground
                g2.font = CodexUiFonts.secondary(Font.BOLD)
                val fm = g2.fontMetrics
                val s = "+"
                g2.drawString(s, (width - fm.stringWidth(s)) / 2, (height + fm.ascent - fm.descent) / 2)
                g2.dispose()
            }
        }
    }

    private fun linkButton(text: String, icon: javax.swing.Icon, onClick: () -> Unit): JButton =
        JButton(text, icon).apply {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = true
            isFocusable = true
            foreground = muted
            font = CodexUiFonts.body()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(4, 6)
            horizontalTextPosition = SwingConstants.LEFT
            iconTextGap = 4
            getAccessibleContext().accessibleName = text
            addActionListener { onClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    foreground = fg
                }
                override fun mouseExited(e: MouseEvent?) {
                    foreground = muted
                }
            })
        }

    private fun reviewButton(onClick: () -> Unit): JButton =
        object : JButton("Xem xét") {
            init {
                isOpaque = false
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = true
                isFocusable = true
                foreground = fg
                font = CodexUiFonts.body(Font.BOLD)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = JBUI.insets(0)
                border = JBUI.Borders.empty(7, 16)
                horizontalAlignment = SwingConstants.CENTER
                getAccessibleContext().accessibleName = "Xem xét"
                getAccessibleContext().accessibleDescription = "Mở diff xem xét các tệp đã chỉnh sửa"
                addActionListener { onClick() }
                // Lock size to full label — IntelliJ button UI otherwise ellipsizes in tight headers.
                val fm = getFontMetrics(font)
                val size = Dimension(fm.stringWidth(text) + 32, fm.height + 14)
                preferredSize = size
                minimumSize = size
                maximumSize = size
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = when {
                    hasFocus() -> CodexUiTheme.selectionBg
                    model.isRollover -> CodexUiTheme.hoverBg
                    else -> CodexUiTheme.codeBg
                }
                g2.color = bg
                g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 10f, 10f))
                g2.color = if (hasFocus()) CodexUiTheme.focusRing else CodexUiTheme.cardBorder
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 10f, 10f))
                g2.dispose()
                super.paintComponent(g)
            }
        }

    override fun getMaximumSize(): Dimension =
        Dimension(Integer.MAX_VALUE, preferredSize.height)
}

/** Mini green/red proportion bar like GitHub / Codex file lists. */
private class DiffSparkline(
    private val added: Int,
    private val removed: Int,
    private val barWidth: Int,
) : JComponent() {
    private val green = CodexUiTheme.success
    private val red = CodexUiTheme.danger
    private val empty = CodexUiTheme.cardDivider

    init {
        preferredSize = Dimension(barWidth, 10)
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = "+$added / −$removed"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val h = 6f
        val y = (height - h) / 2f
        val total = (added + removed).coerceAtLeast(1)
        val w = width.toFloat()
        if (added == 0 && removed == 0) {
            g2.color = empty
            g2.fill(RoundRectangle2D.Float(0f, y, w, h, 3f, 3f))
        } else {
            val addW = w * added / total
            val remW = w * removed / total
            var x = 0f
            if (added > 0) {
                g2.color = green
                g2.fill(RoundRectangle2D.Float(x, y, addW.coerceAtLeast(2f), h, 3f, 3f))
                x += addW
            }
            if (removed > 0) {
                g2.color = red
                g2.fill(RoundRectangle2D.Float(x, y, remW.coerceAtLeast(2f), h, 3f, 3f))
            }
        }
        g2.dispose()
    }
}
