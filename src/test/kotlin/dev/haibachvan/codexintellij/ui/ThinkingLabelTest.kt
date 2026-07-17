package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThinkingLabelTest {
    @Test
    fun `labels match collapsed thinking UX`() {
        assertEquals("Đang suy nghĩ…", TranscriptRenderer.thinkingLabel(null, stillRunning = true))
        assertEquals("Đang hoạt động · 5s", TranscriptRenderer.thinkingLabel(5, stillRunning = true))
        assertEquals("Đã hoạt động trong 43s", TranscriptRenderer.thinkingLabel(43, stillRunning = false))
        assertEquals("Thinking", TranscriptRenderer.thinkingLabel(null, stillRunning = false))
    }
}
