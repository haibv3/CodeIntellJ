package dev.haibachvan.codexintellij.mcp

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class McpConsentTest {
    @Test
    fun `preview hash is immutable and one-shot grant`() {
        val consent = McpConsent()
        val preview = consent.preview(ProcessEpoch(1), "server", "tool", """{"x":1}""")
        assertEquals(64, preview.sha256.length)
        assertFalse(consent.isGranted(preview.key))
        consent.grant(preview)
        assertTrue(consent.isGranted(preview.key))
        consent.assertUnchanged(preview, """{"x":1}""")
        assertThrows<IllegalArgumentException> {
            consent.assertUnchanged(preview, """{"x":2}""")
        }
    }
}
