package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryWorkflowTest {
    @Test
    fun `recovery steps are ordered and old snapshots rejected`() {
        assertEquals(
            listOf(
                RecoveryWorkflow.Step.FAIL_PENDING,
                RecoveryWorkflow.Step.AWAIT_OLD_EXIT,
                RecoveryWorkflow.Step.START_NEW_EPOCH,
                RecoveryWorkflow.Step.LIST_THREADS,
                RecoveryWorkflow.Step.READ_RESUME,
                RecoveryWorkflow.Step.MERGE_SNAPSHOT,
            ),
            RecoveryWorkflow.orderedSteps(),
        )
        assertFalse(RecoveryWorkflow.shouldAcceptSnapshot(ProcessEpoch(2), ProcessEpoch(1)))
        assertTrue(RecoveryWorkflow.shouldAcceptSnapshot(ProcessEpoch(2), ProcessEpoch(2)))
    }
}
