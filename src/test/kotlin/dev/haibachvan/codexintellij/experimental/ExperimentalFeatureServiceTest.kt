package dev.haibachvan.codexintellij.experimental

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExperimentalFeatureServiceTest {
    @Test
    fun `worktree cloud side remain disabled without contracts or traces`() {
        val service = ExperimentalFeatureService(experimentalOptIn = { false }, sideTraceAccepted = { false })
        val gates = service.gates().associateBy { it.name }
        assertFalse(gates.getValue("/cloud").enabled)
        assertFalse(gates.getValue("/worktree").enabled)
        assertFalse(gates.getValue("/side").enabled)
        assertFalse(gates.getValue("/plan").enabled)
        val withX = ExperimentalFeatureService(experimentalOptIn = { true }, sideTraceAccepted = { false })
        assertTrue(withX.gates().first { it.name == "/plan" }.enabled)
        assertFalse(withX.gates().first { it.name == "/side" }.enabled)
    }
}
