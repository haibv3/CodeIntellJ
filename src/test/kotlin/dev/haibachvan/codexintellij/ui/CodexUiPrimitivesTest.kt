package dev.haibachvan.codexintellij.ui

import com.intellij.util.ui.EmptyIcon
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.KeyStroke
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodexUiPrimitivesTest {
    @Test
    fun `ui metrics expose scaled spacing control radius and icon sizes`() {
        assertTrue(CodexUiMetrics.space4 > 0)
        assertTrue(CodexUiMetrics.space4 < CodexUiMetrics.space8)
        assertTrue(CodexUiMetrics.space8 < CodexUiMetrics.space12)
        assertTrue(CodexUiMetrics.space12 < CodexUiMetrics.space16)
        assertTrue(CodexUiMetrics.space16 < CodexUiMetrics.space24)
        assertTrue(CodexUiMetrics.control24 < CodexUiMetrics.control28)
        assertTrue(CodexUiMetrics.control28 < CodexUiMetrics.control32)
        assertTrue(CodexUiMetrics.radiusControl < CodexUiMetrics.radiusCard)
        assertTrue(CodexUiMetrics.iconAction < CodexUiMetrics.iconToolWindow)
    }

    @Test
    fun `icon button requires tooltip and accessible name`() {
        val icon = EmptyIcon.create(16)
        assertThrows(IllegalArgumentException::class.java) {
            CodexIconButton(icon, "", "Action", {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodexIconButton(icon, "Action", "", {})
        }
    }

    @Test
    fun `icon button exposes enter space focus and accessibility contract`() {
        var clicks = 0
        val button = CodexIconButton(EmptyIcon.create(16), "Copy", "Sao chép") { clicks += 1 }

        val enterKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"))
        val spaceKey = button.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("SPACE"))
        assertNotNull(enterKey)
        assertNotNull(spaceKey)
        button.actionMap.get(enterKey).actionPerformed(ActionEvent(button, 0, "enter"))
        button.actionMap.get(spaceKey).actionPerformed(ActionEvent(button, 0, "space"))

        assertEquals(2, clicks)
        assertEquals("Sao chép", button.getAccessibleContext().accessibleName)
        assertEquals("Copy", button.toolTipText)
        assertTrue(button.isFocusable)
        assertTrue(button.isFocusPainted)
    }

    @Test
    fun `main swing icon actions do not use replaceable unicode glyphs`() {
        val sources = listOf(
            "CodexComposerBar.kt",
            "CodexWorkspacePanel.kt",
            "CodeFenceCardPanel.kt",
        ).joinToString("\n") { name ->
            Files.readString(Path.of("src/main/kotlin/dev/haibachvan/codexintellij/ui/$name"))
        }

        assertTrue(!sources.contains("JButton(\"+\")"))
        assertTrue(!sources.contains("JButton(\"←\")"))
        assertTrue(!sources.contains("\\u29C9"))
    }
}
