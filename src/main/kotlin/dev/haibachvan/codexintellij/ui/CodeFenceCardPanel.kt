package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Native fenced-code card — same chrome language as [ModifiedFilesCardPanel]
 * (rounded border, header + body). Uses a read-only editor for syntax colors.
 */
class CodeFenceCardPanel(
    private val project: Project,
    language: String?,
    private val code: String,
) : JPanel(BorderLayout()), Disposable {
    private val arc = 14f
    private val cardBg = JBColor(Color(0x2B2B2B), Color(0x2B2B2B))
    private val borderColor = JBColor(Color(0x454545), Color(0x454545))
    private val muted = JBColor(Color(0xA0A0A0), Color(0xA0A0A0))
    private val fg = JBColor(Color(0xEDEDED), Color(0xEDEDED))
    private val editorField: EditorTextField
    private val copyGlyph = "\u29C9"

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 12, 12, 12)
        alignmentX = LEFT_ALIGNMENT
        editorField = buildEditor(language)
        add(buildInner(language), BorderLayout.CENTER)
    }

    private fun buildInner(language: String?): JPanel {
        val inner = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(12, 14, 12, 14)
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

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 2, 10, 2)
            add(
                JLabel((language ?: "code").lowercase()).apply {
                    foreground = muted
                    font = CodexUiFonts.secondary()
                },
                BorderLayout.WEST,
            )
            add(copyButton(), BorderLayout.EAST)
        }
        inner.add(header, BorderLayout.NORTH)
        // Wrap editor so BorderLayout cannot clip its preferred height.
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            add(editorField, BorderLayout.CENTER)
        }
        inner.add(body, BorderLayout.CENTER)
        return inner
    }

    private fun copyButton(): JButton =
        JButton(copyGlyph).apply {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            foreground = muted
            font = CodexUiFonts.body()
            toolTipText = "Copy"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.insets(2, 4)
            horizontalAlignment = SwingConstants.CENTER
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(code))
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    foreground = fg
                }
                override fun mouseExited(e: MouseEvent?) {
                    foreground = muted
                }
            })
        }

    private fun buildEditor(language: String?): EditorTextField {
        val fileType = resolveFileType(language)
        val document = EditorFactory.getInstance().createDocument(code)
        val lineCount = code.lines().size.coerceIn(1, 36)
        val field = object : EditorTextField(document, project, fileType, /*isViewer*/ true, /*oneLineMode*/ false) {
            override fun createEditor(): EditorEx {
                val editor = super.createEditor()
                editor.setCaretEnabled(false)
                editor.settings.isLineNumbersShown = false
                editor.settings.isLineMarkerAreaShown = false
                editor.settings.isFoldingOutlineShown = false
                editor.settings.isCaretRowShown = false
                editor.settings.isUseSoftWraps = true
                editor.settings.additionalLinesCount = 0
                editor.settings.additionalColumnsCount = 0
                editor.settings.isAdditionalPageAtBottom = false
                editor.setVerticalScrollbarVisible(false)
                editor.setHorizontalScrollbarVisible(false)
                editor.scrollPane.border = JBUI.Borders.empty()
                editor.scrollPane.viewportBorder = JBUI.Borders.empty()
                // Top/bottom inset so glyph ascenders/descenders are not clipped.
                editor.setBorder(JBUI.Borders.empty(6, 2, 6, 2))
                editor.contentComponent.border = JBUI.Borders.empty(2, 0, 2, 0)
                editor.backgroundColor = Color(0x2B2B2B)
                editor.colorsScheme.setColor(
                    com.intellij.openapi.editor.colors.EditorColors.READONLY_FRAGMENT_BACKGROUND_COLOR,
                    Color(0x2B2B2B),
                )
                editor.scrollingModel.scroll(0, 0)
                editor.colorsScheme.editorFontSize = CodexUiFonts.BODY_PX
                return editor
            }

            override fun getPreferredSize(): Dimension {
                val editor = editor as? EditorEx
                val lineH = (editor?.lineHeight ?: JBUI.scale(20)).coerceAtLeast(JBUI.scale(18))
                // Extra pad: editor border + ascender room (avoids top-line clip).
                val pad = JBUI.scale(24)
                return Dimension(100, lineCount * lineH + pad)
            }

            override fun getMinimumSize(): Dimension = preferredSize

            override fun getMaximumSize(): Dimension =
                Dimension(Integer.MAX_VALUE, preferredSize.height)
        }
        field.setFontInheritedFromLAF(false)
        field.border = JBUI.Borders.empty()
        field.background = cardBg
        return field
    }

    private fun resolveFileType(language: String?): FileType {
        val hint = language?.trim()?.lowercase().orEmpty()
        if (hint.isEmpty()) return PlainTextFileType.INSTANCE
        val ext = when (hint) {
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "xml" -> "xml"
            "json" -> "json"
            "js", "javascript" -> "js"
            "ts", "typescript" -> "ts"
            "py", "python" -> "py"
            "sh", "bash", "shell", "zsh" -> "sh"
            "sql" -> "sql"
            "yaml", "yml" -> "yml"
            "md", "markdown" -> "md"
            "html", "htm" -> "html"
            "css" -> "css"
            "gradle" -> "gradle"
            "properties" -> "properties"
            "c", "h" -> "c"
            "cpp", "cc", "cxx" -> "cpp"
            "go" -> "go"
            "rs", "rust" -> "rs"
            "swift" -> "swift"
            else -> hint.removePrefix("language-").takeWhile { it.isLetterOrDigit() || it == '+' }
        }
        if (ext.isBlank()) return PlainTextFileType.INSTANCE
        return FileTypeManager.getInstance().getFileTypeByExtension(ext)
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Integer.MAX_VALUE, preferredSize.height)

    override fun dispose() {
        // EditorTextField releases its editor when removed / GC'd; explicit clear helps.
        editorField.removeNotify()
    }
}
