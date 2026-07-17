package dev.haibachvan.codexintellij.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerConfigControllerTest {
    @Test
    fun `allowlist rejects unknown and secret keys`() {
        assertTrue(WritableConfigAllowlist.contains("model"))
        assertFalse(WritableConfigAllowlist.contains("apiKey"))
        assertFalse(WritableConfigAllowlist.isSecret("model"))
        assertThrows<IllegalArgumentException> {
            // Constructing controller requires gateway; validate allowlist gate directly.
            require(WritableConfigAllowlist.contains("not-a-key")) { "Config key not allowlisted: not-a-key" }
        }
    }
}
