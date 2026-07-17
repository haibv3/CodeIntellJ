package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.appserver.ProcessEpoch

/**
 * Stateless recovery ordering helper. Epoch ownership stays in lifecycle; merge ownership in controller.
 */
object RecoveryWorkflow {
    enum class Step { FAIL_PENDING, AWAIT_OLD_EXIT, START_NEW_EPOCH, LIST_THREADS, READ_RESUME, MERGE_SNAPSHOT }

    fun orderedSteps(): List<Step> = listOf(
        Step.FAIL_PENDING,
        Step.AWAIT_OLD_EXIT,
        Step.START_NEW_EPOCH,
        Step.LIST_THREADS,
        Step.READ_RESUME,
        Step.MERGE_SNAPSHOT,
    )

    fun shouldAcceptSnapshot(currentEpoch: ProcessEpoch, snapshotEpoch: ProcessEpoch): Boolean =
        snapshotEpoch.value >= currentEpoch.value
}
