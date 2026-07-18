package dev.haibachvan.codexintellij.ui

import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.text.JTextComponent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptPlainAgentBlockHostTest {
    @Test
    fun `plain host paints without text document and exposes accessible content`() {
        val host = TranscriptPlainAgentBlockHost(onCopy = {})
        val block = TranscriptBlock.PlainAgentMessage(
            itemId = "agent-1",
            text = "Kết quả agent đã sẵn sàng để tổng hợp",
            id = "item:agent-1:prose:0",
        )

        host.updateContent(block, 480)
        val wideHeight = host.preferredSize.height
        host.updateContent(block, 160)

        assertTrue(host.descendants().none { it is JTextComponent })
        assertEquals(block.text, host.getAccessibleContext().accessibleDescription)
        assertTrue(host.preferredSize.height > wideHeight)
    }

    @Test
    fun `long plain token wraps instead of clipping in narrow viewport`() {
        val host = TranscriptPlainAgentBlockHost(onCopy = {})
        val block = TranscriptBlock.PlainAgentMessage(
            itemId = "agent-long",
            text = "x".repeat(120),
            id = "item:agent-long:prose:0",
        )

        host.updateContent(block, 480)
        val wideHeight = host.preferredSize.height
        host.updateContent(block, 160)

        assertTrue(host.preferredSize.height > wideHeight)
    }

    @Test
    fun `long supplementary token wraps with linear measurement and intact code points`() {
        val token = ("a😀").repeat(5_000)
        var textMeasurements = 0
        var codePointMeasurements = 0

        val lines = PlainTextLineWrapper.wrap(
            text = token,
            contentWidth = 10,
            textWidth = {
                textMeasurements += 1
                it.codePointCount(0, it.length) * 5
            },
            codePointWidth = {
                codePointMeasurements += 1
                5
            },
        )

        assertEquals(token, lines.joinToString(""))
        assertTrue(lines.all { it.codePointCount(0, it.length) <= 2 })
        assertTrue(lines.none(::hasUnpairedSurrogate))
        assertTrue(textMeasurements <= 1, "Long word must not be remeasured by prefix")
        assertEquals(token.codePointCount(0, token.length) + 1, codePointMeasurements)
    }

    @Test
    fun `plain host rewraps from live layout width without content update`() {
        val host = TranscriptPlainAgentBlockHost(onCopy = {})
        val block = TranscriptBlock.PlainAgentMessage(
            itemId = "agent-resize",
            text = "Kết quả dài cần tự động xuống dòng khi tool window được thu hẹp mà state không đổi",
            id = "item:agent-resize:prose:0",
        )

        host.updateContent(block, 480)
        val wideHeight = host.preferredSize.height
        JPanel().add(host)
        host.setSize(160, wideHeight)
        host.doLayout()
        javax.swing.SwingUtilities.invokeAndWait {}

        assertTrue(host.preferredSize.height > wideHeight)
    }

    @Test
    fun `plain host containing focus owner is not recyclable`() {
        val host = TranscriptPlainAgentBlockHost(onCopy = {})
        val copyButton = host.descendants().first { it is CodexIconButton }

        assertFalse(host.canRecycle(host))
        assertFalse(host.canRecycle(copyButton))
        assertTrue(host.canRecycle(JLabel("outside")))
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(character) -> return true
                else -> index += 1
            }
        }
        return false
    }

    private fun Component.descendants(): Sequence<Component> = sequence {
        yield(this@descendants)
        if (this@descendants is Container) {
            this@descendants.components.forEach { yieldAll(it.descendants()) }
        }
    }
}
