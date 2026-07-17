package dev.haibachvan.codexintellij.review

sealed class DiffEvidence {
    data class NativeBeforeAfter(
        val path: String,
        val before: ByteArray,
        val after: ByteArray,
        val evidenceLabel: String,
    ) : DiffEvidence()

    data class WarningUnifiedDiff(
        val path: String,
        val unifiedDiff: String,
        val warning: String,
    ) : DiffEvidence()
}

class DiffEvidenceResolver {
    fun resolve(
        patch: ProjectedPatch,
        baseline: BaselineEntry?,
        serverBefore: ByteArray? = null,
        serverAfter: ByteArray? = null,
    ): DiffEvidence {
        val path = patch.fact.path
        if (serverBefore != null && serverAfter != null) {
            return DiffEvidence.NativeBeforeAfter(
                path = path,
                before = serverBefore,
                after = serverAfter,
                evidenceLabel = "server-explicit-before-after",
            )
        }
        val after = serverAfter
        if (baseline != null && after != null) {
            val label = when (baseline.source) {
                BaselineSource.DOCUMENT -> "pre-turn-unsaved-document"
                BaselineSource.DISK -> "pre-turn-disk"
            }
            return DiffEvidence.NativeBeforeAfter(
                path = path,
                before = baseline.bytes,
                after = after,
                evidenceLabel = label,
            )
        }
        val unified = patch.fact.unifiedDiff ?: ""
        return DiffEvidence.WarningUnifiedDiff(
            path = path,
            unifiedDiff = unified,
            warning = "Insufficient authoritative before/after evidence; showing server unified diff only",
        )
    }
}
