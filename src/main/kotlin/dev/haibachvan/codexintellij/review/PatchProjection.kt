package dev.haibachvan.codexintellij.review

import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.PatchFact
import dev.haibachvan.codexintellij.session.ThreadId

data class ProjectedPatch(
    val fact: PatchFact,
    val provenance: String,
    val arrivalHint: Long,
)

object PatchProjection {
    fun from(state: NormalizedServerState, threadId: ThreadId?): List<ProjectedPatch> =
        state.patches.values
            .filter { patch ->
                val item = state.items[patch.itemId]
                threadId == null || item?.threadId == threadId
            }
            .map { patch ->
                val item = state.items[patch.itemId]
                ProjectedPatch(
                    fact = patch,
                    provenance = "server-patch-fact",
                    arrivalHint = item?.arrivalSeq ?: 0L,
                )
            }
            .sortedBy { it.arrivalHint }
}
