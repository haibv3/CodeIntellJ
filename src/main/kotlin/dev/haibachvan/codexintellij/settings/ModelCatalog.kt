package dev.haibachvan.codexintellij.settings

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import java.util.concurrent.CompletableFuture

data class CodexModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean,
    val defaultEffort: String?,
    val efforts: List<String>,
) {
    val label: String
        get() = buildString {
            append(displayName.ifBlank { model })
            if (!defaultEffort.isNullOrBlank()) {
                append(' ')
                append(effortLabel(defaultEffort))
            }
        }

    fun effortLabels(): List<String> = efforts.map { effortLabel(it) }

    companion object {
        fun effortLabel(effort: String): String =
            when (effort.lowercase()) {
                "minimal", "low" -> "Thấp"
                "medium" -> "Trung bình"
                "high" -> "Cao"
                "xhigh", "extra_high", "extra-high" -> "Rất cao"
                else -> effort.replaceFirstChar { it.uppercase() }
            }

        fun effortWire(labelOrWire: String): String =
            when (labelOrWire.lowercase()) {
                "thấp", "low", "minimal" -> "low"
                "trung bình", "medium" -> "medium"
                "cao", "high" -> "high"
                "rất cao", "xhigh", "extra_high", "extra-high" -> "xhigh"
                else -> labelOrWire
            }
    }
}

enum class ApprovalModeOption(
    val wire: String,
    val label: String,
    val description: String,
) {
    FULL_ACCESS(
        "never",
        "Toàn quyền quyết định",
        "Tự chạy lệnh và sửa tệp mà không hỏi",
    ),
    ON_REQUEST(
        "on-request",
        "Hỏi khi cần",
        "Hỏi trước khi chạy lệnh hoặc ghi tệp",
    ),
    RESTRICTED(
        "untrusted",
        "Hạn chế",
        "Chỉ cho phép thao tác đọc / an toàn",
    ),
}

class ModelCatalog(
    private val gateway: () -> AppServerGateway?,
) {
    @Volatile
    private var cached: List<CodexModelOption> = FALLBACK

    fun cached(): List<CodexModelOption> = cached

    fun refresh(): CompletableFuture<List<CodexModelOption>> {
        val g = gateway()
            ?: return CompletableFuture.completedFuture(cached)
        return g.request("model/list", JsonObject()).thenApply { response ->
            val data = response.result?.getAsJsonArray("data")
            if (data == null || data.size() == 0) {
                cached
            } else {
                val models = data.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    if (obj.get("hidden")?.asBoolean == true) return@mapNotNull null
                    val efforts = obj.getAsJsonArray("supportedReasoningEfforts")
                        ?.mapNotNull { opt ->
                            opt.asJsonObject.get("reasoningEffort")?.asString
                        }
                        .orEmpty()
                        .ifEmpty { listOf("low", "medium", "high") }
                    CodexModelOption(
                        id = obj.get("id")?.asString ?: return@mapNotNull null,
                        model = obj.get("model")?.asString ?: return@mapNotNull null,
                        displayName = obj.get("displayName")?.asString ?: obj.get("model")?.asString.orEmpty(),
                        description = obj.get("description")?.asString.orEmpty(),
                        isDefault = obj.get("isDefault")?.asBoolean == true,
                        defaultEffort = obj.get("defaultReasoningEffort")?.asString,
                        efforts = efforts,
                    )
                }
                if (models.isNotEmpty()) {
                    cached = models
                }
                cached
            }
        }.exceptionally { cached }
    }

    companion object {
        val FALLBACK: List<CodexModelOption> = listOf(
            CodexModelOption(
                id = "default",
                model = "default",
                displayName = "Default",
                description = "Server default model",
                isDefault = true,
                defaultEffort = "medium",
                efforts = listOf("low", "medium", "high"),
            ),
        )
    }
}
