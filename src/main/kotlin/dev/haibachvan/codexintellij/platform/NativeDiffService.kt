package dev.haibachvan.codexintellij.platform

import dev.haibachvan.codexintellij.review.DiffEvidence

data class DiffViewModel(
    val path: String,
    val mode: String,
    val warning: String?,
    val beforeText: String?,
    val afterText: String?,
    val unifiedDiff: String?,
    val evidenceLabel: String?,
)

class NativeDiffService {
    fun show(evidence: DiffEvidence): DiffViewModel =
        when (evidence) {
            is DiffEvidence.NativeBeforeAfter -> DiffViewModel(
                path = evidence.path,
                mode = "native-before-after",
                warning = null,
                beforeText = evidence.before.toString(Charsets.UTF_8),
                afterText = evidence.after.toString(Charsets.UTF_8),
                unifiedDiff = null,
                evidenceLabel = evidence.evidenceLabel,
            )
            is DiffEvidence.WarningUnifiedDiff -> DiffViewModel(
                path = evidence.path,
                mode = "warning-unified",
                warning = evidence.warning,
                beforeText = null,
                afterText = null,
                unifiedDiff = evidence.unifiedDiff,
                evidenceLabel = null,
            )
        }
}
