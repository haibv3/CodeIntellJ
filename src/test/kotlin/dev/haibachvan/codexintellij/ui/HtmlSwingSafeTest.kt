package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlSwingSafeTest {
    @Test
    fun `sanitize strips emoji surrogate pairs`() {
        val raw = "hello \uD83D\uDCC4 world"
        val clean = HtmlSwingSafe.sanitize(raw)
        assertFalse(clean.contains('\uD83D'))
        assertTrue(clean.contains("hello"))
        assertTrue(clean.contains("world"))
    }
}
