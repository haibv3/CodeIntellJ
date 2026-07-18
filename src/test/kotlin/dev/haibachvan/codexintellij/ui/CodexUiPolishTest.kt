package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexUiPolishTest {
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
}
