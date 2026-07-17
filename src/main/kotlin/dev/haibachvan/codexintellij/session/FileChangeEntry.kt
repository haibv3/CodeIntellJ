package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject

data class FileChangeEntry(
    val path: String,
    val unifiedDiff: String?,
    /** add | delete | update */
    val kind: String = "update",
)

fun PatchFact.resolvedChanges(): List<FileChangeEntry> {
    if (changes.isNotEmpty()) return changes
    if (path.isBlank() && unifiedDiff.isNullOrBlank()) return emptyList()
    return listOf(FileChangeEntry(path = path, unifiedDiff = unifiedDiff, kind = "update"))
}

fun parseFileChangeEntries(params: JsonObject, nested: JsonObject?): List<FileChangeEntry> {
    val fromParams = readChangesArray(params.getAsJsonArray("changes"))
    if (fromParams.isNotEmpty()) return fromParams
    val fromNested = nested?.let { readChangesArray(it.getAsJsonArray("changes")) }.orEmpty()
    if (fromNested.isNotEmpty()) return fromNested
    val path = params.stringOrNull("path")
        ?: nested?.stringOrNull("path")
        ?: return emptyList()
    val diff = params.stringOrNull("diff") ?: nested?.stringOrNull("diff")
    if (path.isBlank() && diff.isNullOrBlank()) return emptyList()
    return listOf(FileChangeEntry(path = path, unifiedDiff = diff, kind = "update"))
}

private fun readChangesArray(arr: com.google.gson.JsonArray?): List<FileChangeEntry> {
    if (arr == null || arr.size() == 0) return emptyList()
    val out = ArrayList<FileChangeEntry>(arr.size())
    arr.forEach { el ->
        if (!el.isJsonObject) return@forEach
        val obj = el.asJsonObject
        val path = obj.stringOrNull("path") ?: return@forEach
        val diff = obj.stringOrNull("diff")
        val kind = obj.get("kind")?.let { kindEl ->
            when {
                kindEl.isJsonObject -> kindEl.asJsonObject.stringOrNull("type")
                kindEl.isJsonPrimitive -> kindEl.asString
                else -> null
            }
        } ?: "update"
        out += FileChangeEntry(path = path, unifiedDiff = diff, kind = kind)
    }
    return out
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
