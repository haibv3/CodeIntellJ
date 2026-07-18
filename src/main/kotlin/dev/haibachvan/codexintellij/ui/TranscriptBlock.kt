package dev.haibachvan.codexintellij.ui

/** One visual block in the chat transcript. */
sealed class TranscriptBlock {
    abstract val id: String
    abstract val revision: TranscriptBlockRevision

    data class Html(
        val fragment: String,
        override val id: String = "",
        override val revision: TranscriptBlockRevision = TranscriptBlockRevision(),
    ) : TranscriptBlock()

    /** Plain agent prose avoids constructing a full Swing HTML document. */
    data class PlainAgentMessage(
        val itemId: String,
        val text: String,
        override val id: String = "",
        override val revision: TranscriptBlockRevision = TranscriptBlockRevision(),
    ) : TranscriptBlock()

    data class ModifiedFiles(
        val payload: ModifiedFilesActions.Payload,
        override val id: String = "",
        override val revision: TranscriptBlockRevision = TranscriptBlockRevision(),
    ) : TranscriptBlock()

    data class CodeFence(
        val language: String?,
        val code: String,
        override val id: String = "",
        override val revision: TranscriptBlockRevision = TranscriptBlockRevision(),
    ) : TranscriptBlock()

    /** Codex-style agent chip: pill name + trailing status. */
    data class AgentChip(
        val agentId: String,
        val statusLabel: String,
        val summary: String?,
        override val id: String = "",
        override val revision: TranscriptBlockRevision = TranscriptBlockRevision(),
    ) : TranscriptBlock()

    companion object {
        /** Stable fingerprint for incremental transcript reuse. */
        fun fingerprint(block: TranscriptBlock): String =
            when (block) {
                is Html -> "h:${block.fragment.hashCode()}"
                is PlainAgentMessage -> "p:${block.text.hashCode()}"
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

data class TranscriptBlockRevision(
    val sourceVersion: Long = 0L,
    val viewVersion: String = "",
)
