package dev.haibachvan.codexintellij.platform

import dev.haibachvan.codexintellij.review.DiffEvidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeDiffServiceTest {
    @Test
    fun `native evidence renders before after without warning`() {
        val view = NativeDiffService().show(
            DiffEvidence.NativeBeforeAfter(
                path = "A.kt",
                before = "a".toByteArray(),
                after = "b".toByteArray(),
                evidenceLabel = "server-explicit-before-after",
            ),
        )
        assertEquals("native-before-after", view.mode)
        assertNull(view.warning)
        assertEquals("a", view.beforeText)
        assertEquals("b", view.afterText)
    }

    @Test
    fun `warning unified never invents before after sides`() {
        val view = NativeDiffService().show(
            DiffEvidence.WarningUnifiedDiff(
                path = "A.kt",
                unifiedDiff = "@@\n+x",
                warning = "insufficient",
            ),
        )
        assertEquals("warning-unified", view.mode)
        assertNotNull(view.warning)
        assertNull(view.beforeText)
        assertNull(view.afterText)
    }
}
