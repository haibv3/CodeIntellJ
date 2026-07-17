package dev.haibachvan.codexintellij.platform

enum class ContextKind { SELECTION, UNSAVED_BUFFER, SAVED_FILE, OPEN_FILE, AUTOMATIC }

data class ContextSnapshot(
    val kind: ContextKind,
    val canonicalPath: String,
    val relativePath: String,
    val displayName: String,
    val text: String?,
    val utf16Start: Int?,
    val utf16End: Int?,
    val lineStart: Int?,
    val lineEnd: Int?,
    val utf8Start: Int?,
    val utf8End: Int?,
    val modificationStamp: Long,
    val languageHint: String?,
    val truncated: Boolean,
    val unsaved: Boolean,
    val contentSha256: String,
    val projectId: String,
    val contentRootId: String,
)

data class EncodedContextInput(
    val jsonBytes: ByteArray,
    val sha256: String,
    val preview: PreviewModel,
) {
    data class PreviewModel(
        val wireBytes: ByteArray,
        val hash: String,
        val uiOnlyMetadata: Map<String, String>,
        val diskReadDeferredWarning: Boolean,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedContextInput) return false
        return sha256 == other.sha256 && jsonBytes.contentEquals(other.jsonBytes)
    }

    override fun hashCode(): Int = 31 * sha256.hashCode() + jsonBytes.contentHashCode()
}
