package dev.haibachvan.codexintellij.ui

/** One visual block in the chat transcript. */
sealed class TranscriptBlock {
    data class Html(val fragment: String) : TranscriptBlock()
    data class ModifiedFiles(val payload: ModifiedFilesActions.Payload) : TranscriptBlock()
    data class CodeFence(
        val language: String?,
        val code: String,
    ) : TranscriptBlock()
    /** Codex-style agent chip: pill name + trailing status. */
    data class AgentChip(
        val agentId: String,
        val statusLabel: String,
        val summary: String?,
    ) : TranscriptBlock()

    companion object {
        /** Stable fingerprint for incremental transcript reuse. */
        fun fingerprint(block: TranscriptBlock): String =
            when (block) {
                is Html -> "h:${block.fragment.hashCode()}"
                is ModifiedFiles ->
                    "f:${block.payload.threadId}:${block.payload.turnId}:${
                        block.payload.files.joinToString { "${it.path}:${it.counts.added}:${it.counts.removed}" }
                    }"
                is CodeFence -> "c:${block.language}:${block.code.hashCode()}"
                is AgentChip -> "a:${block.agentId}:${block.statusLabel}:${block.summary.hashCode()}"
            }

        fun fingerprints(blocks: List<TranscriptBlock>): List<String> =
            blocks.map { fingerprint(it) }
    }
}
