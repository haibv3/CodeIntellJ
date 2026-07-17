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
}
