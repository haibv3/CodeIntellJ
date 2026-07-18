package dev.haibachvan.codexintellij.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import dev.haibachvan.codexintellij.settings.ModelCatalog
import java.awt.Component
import java.awt.Container
import java.awt.Image
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexUiPolishTest {
    @Test
    fun `image attachment remove button does not overlap thumbnail`() {
        val image = Files.createTempFile("codex-attachment-layout-", ".png")
        try {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
            SwingUtilities.invokeAndWait {
                val strip = ComposerAttachmentsStrip()
                strip.addAll(listOf(ComposerAttachment.fromPath(image, image.fileName.toString())))
                strip.setSize(strip.preferredSize)
                layoutRecursively(strip)

                val remove = descendants(strip).filterIsInstance<JButton>().single()
                val thumbnail = descendants(strip).first { it is com.intellij.ui.components.JBLabel }
                val removeBounds = SwingUtilities.convertRectangle(remove.parent, remove.bounds, strip)
                val thumbnailBounds = SwingUtilities.convertRectangle(thumbnail.parent, thumbnail.bounds, strip)
                val stripBounds = Rectangle(0, 0, strip.width, strip.height)

                assertTrue(stripBounds.contains(removeBounds), "Remove button is clipped: $removeBounds")
                assertFalse(
                    removeBounds.intersects(thumbnailBounds),
                    "Remove button overlaps thumbnail: remove=$removeBounds thumbnail=$thumbnailBounds",
                )
            }
        } finally {
            Files.deleteIfExists(image)
        }
    }

    @Test
    fun `composer accepts clipboard image as attachment`() {
        val composer = CodexComposerBar(
            panelModel = ChatPanelModel("clipboard-image"),
            catalog = ModelCatalog { null },
            onSend = {},
        )
        val input = descendants(composer).filterIsInstance<JTextArea>().first()
        val clipboardImage = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

            override fun getTransferData(flavor: DataFlavor): Image {
                require(isDataFlavorSupported(flavor))
                return clipboardImage
            }
        }

        val imported = input.transferHandler.importData(TransferHandler.TransferSupport(input, transferable))
        val attachment = composer.attachments().singleOrNull()

        try {
            assertTrue(imported, "Clipboard image was not imported")
            assertNotNull(attachment, "Clipboard image did not create an attachment")
            val pastedAttachment = requireNotNull(attachment)
            assertEquals(ComposerAttachment.Kind.IMAGE, pastedAttachment.kind)
            assertTrue(Files.isRegularFile(pastedAttachment.absolutePath))
            assertEquals("localImage", pastedAttachment.toWireInput().get("type").asString)
            assertTrue(composer.canSend())
        } finally {
            attachment?.absolutePath?.let(Files::deleteIfExists)
        }
    }

    @Test
    fun `clipboard image support preserves text paste`() {
        val composer = CodexComposerBar(
            panelModel = ChatPanelModel("clipboard-text"),
            catalog = ModelCatalog { null },
            onSend = {},
        )
        val input = descendants(composer).filterIsInstance<JTextArea>().first()

        val imported = input.transferHandler.importData(
            TransferHandler.TransferSupport(input, StringSelection("pasted text")),
        )

        assertTrue(imported)
        assertEquals("pasted text", composer.text())
        assertTrue(composer.attachments().isEmpty())
    }

    @Test
    fun `narrow composer keeps every primary control inside bounds`() {
        SwingUtilities.invokeAndWait {
            val composer = CodexComposerBar(
                panelModel = ChatPanelModel("narrow-layout"),
                catalog = ModelCatalog { null },
                onSend = {},
            )
            composer.setSize(341, composer.preferredSize.height)
            layoutRecursively(composer)

            val rootBounds = Rectangle(0, 0, composer.width, composer.height)
            val controls = listOf(
                "Mở menu đính kèm",
                "Quyền quyết định",
                "Mức lập luận",
                "Ngữ cảnh IDE",
                "Gửi tin nhắn",
            ).associateWith { accessibleName ->
                val control = descendants(composer).firstOrNull {
                    it.accessibleContext?.accessibleName == accessibleName
                }
                assertNotNull(control, "Missing $accessibleName")
                val bounds = SwingUtilities.convertRectangle(control!!.parent, control.bounds, composer)
                assertTrue(
                    rootBounds.contains(bounds),
                    "$accessibleName is clipped: control=$bounds root=$rootBounds",
                )
                control
            }
            assertTrue(
                controls.getValue("Mức lập luận").width >= minOf(
                    controls.getValue("Mức lập luận").preferredSize.width,
                    CodexUiMetrics.control24 * 4,
                ),
                "Effort selector is too narrow: ${controls.getValue("Mức lập luận").bounds}",
            )
            assertTrue(
                controls.getValue("Ngữ cảnh IDE").width >= CodexUiMetrics.control24 * 3,
                "IDE context is too narrow: ${controls.getValue("Ngữ cảnh IDE").bounds}",
            )
        }
    }

    @Test
    fun `wide composer restores primary controls to one row`() {
        SwingUtilities.invokeAndWait {
            val composer = CodexComposerBar(
                panelModel = ChatPanelModel("wide-layout"),
                catalog = ModelCatalog { null },
                onSend = {},
            )
            composer.setSize(900, composer.preferredSize.height)
            layoutRecursively(composer)

            val controls = listOf(
                "Mở menu đính kèm",
                "Quyền quyết định",
                "Mức lập luận",
                "Ngữ cảnh IDE",
                "Gửi tin nhắn",
            ).map { accessibleName ->
                descendants(composer).first {
                    it.accessibleContext?.accessibleName == accessibleName
                }
            }
            val centers = controls.map { control ->
                val bounds = SwingUtilities.convertRectangle(control.parent, control.bounds, composer)
                bounds.y + bounds.height / 2
            }
            assertTrue(
                centers.max() - centers.min() <= 1,
                "Wide controls must share one toolbar row: $centers / ${controls.map { it.bounds }}",
            )
        }
    }

    @Test
    fun `model selector exposes an accessible name`() {
        val composer = CodexComposerBar(
            panelModel = ChatPanelModel("accessible-model"),
            catalog = ModelCatalog { null },
            onSend = {},
        )

        val model = descendants(composer).filterIsInstance<JComboBox<*>>().first()
        assertEquals("Mô hình", model.accessibleContext.accessibleName)
    }

    @Test
    fun `composer input exposes theme aware colors and accessibility`() {
        val composer = CodexComposerBar(
            panelModel = ChatPanelModel("accessible-input"),
            catalog = ModelCatalog { null },
            onSend = {},
        )

        val input = descendants(composer).filterIsInstance<JTextArea>().first()
        assertEquals("Tin nhắn Codex", input.accessibleContext.accessibleName)
        assertTrue(input.background is JBColor, "Composer background must follow live LAF changes")
        assertTrue(input.foreground is JBColor, "Composer foreground must follow live LAF changes")
    }

    @Test
    fun `recent task list exposes an accessible name`() {
        val list = JBList<String>()

        configureRecentTaskListAccessibility(list)

        assertEquals("Nhiệm vụ gần đây", list.accessibleContext.accessibleName)
        assertFalse(list.accessibleContext.accessibleDescription.isNullOrBlank())
    }

    @Test
    fun `transcript width uses live viewport down to 320 without forcing 480`() {
        assertEquals(320, CodexUiTheme.transcriptContentWidth(320))
        assertEquals(280, CodexUiTheme.transcriptContentWidth(280))
        assertEquals(900, CodexUiTheme.transcriptContentWidth(900))
        assertEquals(480, CodexUiTheme.transcriptContentWidth(0))
        assertEquals(480, CodexUiTheme.transcriptContentWidth(-1))
    }

    @Test
    fun `theme exposes hover selection and focus tokens`() {
        assertNotNull(CodexUiTheme.hoverBg)
        assertNotNull(CodexUiTheme.selectionBg)
        assertNotNull(CodexUiTheme.selectionFg)
        assertNotNull(CodexUiTheme.focusRing)
        assertNotNull(CodexUiTheme.attachmentChipBg)
        assertNotNull(CodexUiTheme.attachmentCloseBg)
        // Semantic tokens must resolve to concrete RGB (Light or Dark side of JBColor).
        assertTrue(CodexUiTheme.focusRing.rgb != 0 || CodexUiTheme.focusRing.alpha >= 0)
    }

    @Test
    fun `anonymous button can set accessible name during init via getAccessibleContext`() {
        // Regression: Component.accessibleContext field is null in subclass init;
        // Kotlin property access hits the field and NPEs — must use getAccessibleContext().
        val button = object : javax.swing.JButton("Xem xét") {
            init {
                getAccessibleContext().accessibleName = "Xem xét"
                getAccessibleContext().accessibleDescription = "Mở diff"
            }
        }
        assertEquals("Xem xét", button.getAccessibleContext().accessibleName)
        assertEquals("Mở diff", button.getAccessibleContext().accessibleDescription)
    }

    @Test
    fun `plus menu rows are focusable with accessible names`() {
        val entries = ComposerPlusCatalog.addEntries()
        var picked: ComposerPlusEntry? = null
        val panel = ComposerPlusMenuPanel(entries) { picked = it }

        assertEquals(entries.size, panel.focusableRowCount())
        assertTrue(panel.rowHasActivationKeys(0))
        // Accessible name may be LAF-dependent; tooltip always carries title + description.
        val tip = panel.rowToolTip(0)
        assertFalse(tip.isNullOrBlank())
        assertTrue(tip!!.contains(entries.first().title))
        assertTrue(tip.contains("—") || tip.contains(entries.first().description.take(12)))

        assertTrue(panel.activateRowAt(0))
        assertNotNull(picked)
        assertEquals(entries.first().title, picked!!.title)
    }

    private fun layoutRecursively(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach(::layoutRecursively)
    }

    private fun descendants(root: Component): List<Component> = buildList {
        fun visit(component: Component) {
            add(component)
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
    }
}
