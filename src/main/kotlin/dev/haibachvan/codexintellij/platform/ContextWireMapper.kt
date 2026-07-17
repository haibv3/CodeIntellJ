package dev.haibachvan.codexintellij.platform

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest

class ContextWireMapper {
    fun encode(snapshot: ContextSnapshot): EncodedContextInput {
        val obj = when {
            snapshot.kind == ContextKind.SAVED_FILE && !snapshot.unsaved -> mention(snapshot)
            else -> textInput(snapshot)
        }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        val hash = sha256(bytes)
        return EncodedContextInput(
            jsonBytes = bytes,
            sha256 = hash,
            preview = EncodedContextInput.PreviewModel(
                wireBytes = bytes,
                hash = hash,
                uiOnlyMetadata = mapOf(
                    "modificationStamp" to snapshot.modificationStamp.toString(),
                    "languageHint" to (snapshot.languageHint ?: ""),
                    "unsaved" to snapshot.unsaved.toString(),
                    "contentRootId" to snapshot.contentRootId,
                    "utf16Start" to (snapshot.utf16Start?.toString() ?: ""),
                    "utf16End" to (snapshot.utf16End?.toString() ?: ""),
                ),
                diskReadDeferredWarning = snapshot.kind == ContextKind.SAVED_FILE && !snapshot.unsaved,
            ),
        )
    }

    private fun mention(snapshot: ContextSnapshot): JsonObject =
        JsonObject().apply {
            addProperty("type", "mention")
            addProperty("name", snapshot.displayName)
            addProperty("path", snapshot.relativePath)
        }

    private fun textInput(snapshot: ContextSnapshot): JsonObject {
        val text = snapshot.text ?: ""
        val header = buildString {
            append("path=").append(snapshot.relativePath)
            if (snapshot.utf16Start != null && snapshot.utf16End != null) {
                append(" range=").append(snapshot.utf16Start).append('-').append(snapshot.utf16End)
            }
            append(" sha256=").append(snapshot.contentSha256)
            append('\n')
        }
        val body = header + text
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        return JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", body)
            add(
                "text_elements",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("type", "text_element")
                            addProperty("placeholder", snapshot.relativePath)
                            add(
                                "byteRange",
                                JsonObject().apply {
                                    addProperty("start", 0)
                                    addProperty("end", headerBytes.size)
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
