package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatTitlesTest {
    @Test
    fun `shortTitle trims and truncates`() {
        assertEquals("Hello world", ChatTitles.shortTitle("  Hello   world  "))
        assertTrue(ChatTitles.shortTitle("x".repeat(80)).endsWith("…"))
        assertTrue(ChatTitles.shortTitle("x".repeat(80)).length <= 52)
    }

    @Test
    fun `detects thread ids`() {
        assertTrue(ChatTitles.looksLikeThreadId("019f6de1-777e-7693-b53a-e6387d2eb8df"))
        assertTrue(ChatTitles.looksLikeThreadId("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(ChatTitles.looksLikeThreadId("Tạo plugin Codex cho IntelliJ"))
        assertFalse(ChatTitles.looksLikeThreadId("Hiện tại project này làm gì"))
    }
}
