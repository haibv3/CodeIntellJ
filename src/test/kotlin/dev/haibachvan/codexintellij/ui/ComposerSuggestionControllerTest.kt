package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposerSuggestionControllerTest {
    @Test
    fun `tokenAt detects slash and at triggers`() {
        val slash = ComposerSuggestionController.tokenAt("/pla", 4)
        assertEquals('/', slash?.trigger)
        assertEquals("/pla", slash?.query)

        val at = ComposerSuggestionController.tokenAt("see @src/ma", 11)
        assertEquals('@', at?.trigger)
        assertEquals("@src/ma", at?.query)

        assertNull(ComposerSuggestionController.tokenAt("hello", 5))
        assertNull(ComposerSuggestionController.tokenAt("https://x", 9))
        assertNull(ComposerSuggestionController.tokenAt("foo/bar", 7))
    }

    @Test
    fun `slash ui matches vietnamese titles and command names`() {
        assertTrue(SlashCommandUi.matches(SlashCommandUi.ordered().first { it.name == "/model" }, "/mo"))
        assertTrue(SlashCommandUi.matches(SlashCommandUi.ordered().first { it.name == "/init" }, "khởi"))
        assertEquals("Khởi tạo", SlashCommandUi.meta(SlashCommandUi.ordered().first { it.name == "/init" }).title)
        assertFalse(SlashCommandUi.matches(SlashCommandUi.ordered().first { it.name == "/model" }, "zzzz"))
    }

    @Test
    fun `slash completion filters by prefix`() {
        val names = SlashCompletionPopup(experimentalOptIn = true, signedIn = true).visibleNames("/mo")
        assertTrue(names.any { it == "/model" })
        assertTrue(SlashCompletionPopup().visibleNames("/zzzz").isEmpty())
    }
}
