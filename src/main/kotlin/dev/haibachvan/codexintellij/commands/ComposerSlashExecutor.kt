package dev.haibachvan.codexintellij.commands

import com.google.gson.JsonObject
import com.intellij.openapi.components.service
import dev.haibachvan.codexintellij.CodexProjectService
import dev.haibachvan.codexintellij.account.AccountState
import dev.haibachvan.codexintellij.review.ReviewController
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.settings.CodexSettingsState
import java.util.concurrent.TimeUnit

/**
 * Intercepts built-in slash commands so they never become raw prompts.
 */
object ComposerSlashExecutor {
    data class Invocation(val name: String, val args: String)

    data class Context(
        val service: CodexProjectService,
        val threadId: ThreadId?,
        val model: String?,
        val effort: String?,
        val cwd: String?,
        val state: NormalizedServerState,
        val ideContextEnabled: Boolean,
        val setIdeContext: (Boolean) -> Unit,
        val setModel: (String) -> Unit,
        val setEffort: (String) -> Unit,
        val setPersonality: (String?) -> Unit = {},
        val toggleFast: () -> Boolean = { false },
        val pendingApprovalCount: () -> Int = { 0 },
        val approvePending: () -> Boolean = { false },
        val onThreadForked: (ThreadId) -> Unit = {},
    )

    sealed class Result {
        data class Notice(val title: String, val bodyMarkdown: String) : Result()
        data class Unavailable(val reason: String) : Result()
        /** Start a normal turn with [prompt] (used by `/init`). */
        data class StartTurn(val title: String, val prompt: String) : Result()
    }

    val INIT_PROMPT: String =
        "Create or update AGENTS.md in this repository with clear, Codex-oriented guidance: " +
            "project overview, build/test commands, coding conventions, and important paths. " +
            "Prefer editing an existing AGENTS.md when present."

    /** Returns a registry invocation, or null when text is a normal/skill prompt. */
    fun parseBuiltin(text: String): Invocation? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val space = trimmed.indexOf(' ')
        val name = (if (space < 0) trimmed else trimmed.substring(0, space)).lowercase()
        if (SlashCommandRegistry.find(name) == null) return null
        val args = if (space < 0) "" else trimmed.substring(space + 1).trim()
        return Invocation(name, args)
    }

    fun execute(inv: Invocation, ctx: Context): Result {
        val settings = runCatching { service<CodexSettingsState>().state }.getOrNull()
        val experimental = settings?.experimentalApiOptIn == true
        val signedIn = runCatching {
            ctx.service.accountController()?.read()?.get(3, TimeUnit.SECONDS) == AccountState.SignedIn
        }.getOrDefault(false)

        val gate = SlashCommandDispatcher(
            experimentalOptIn = { experimental },
            signedIn = { signedIn },
        ).dispatch(inv.name)
        if (gate is DispatchResult.Unavailable) {
            return Result.Unavailable(gate.reason)
        }

        return when (inv.name) {
            "/status" -> Result.Notice(
                "Trạng thái",
                StatusCommandHandler(ctx.service.gateway()).render(
                    StatusCommandHandler.Context(
                        threadId = ctx.threadId,
                        model = ctx.model,
                        effort = ctx.effort,
                        cwd = ctx.cwd,
                        state = ctx.state,
                        lifecycleState = StatusCommandHandler.lifecycleLabel(
                            ctx.service.gateway()?.status()?.state,
                        ),
                    ),
                ),
            )
            "/mcp" -> mcpStatus(ctx)
            "/compact" -> compact(ctx)
            "/fork" -> fork(ctx)
            "/ide-context" -> {
                val next = !ctx.ideContextEnabled
                ctx.setIdeContext(next)
                Result.Notice(
                    "Ngữ cảnh IDE",
                    if (next) "Đã **bật** ngữ cảnh IDE tự động cho các lượt tiếp theo."
                    else "Đã **tắt** ngữ cảnh IDE tự động.",
                )
            }
            "/model" -> {
                if (inv.args.isNotBlank()) {
                    ctx.setModel(inv.args.trim())
                    Result.Notice("Mô hình", "Đã chọn mô hình `${inv.args.trim()}` cho lượt tiếp theo.")
                } else {
                    val catalog = runCatching {
                        ctx.service.modelCatalog().cached().joinToString("\n") { "- `${it.model}`" }
                    }.getOrNull().orEmpty()
                    Result.Notice(
                        "Mô hình",
                        buildString {
                            appendLine("Chọn mô hình từ thanh dưới, hoặc gửi `/model <tên>`.")
                            if (catalog.isNotBlank()) {
                                appendLine()
                                appendLine("**Catalog:**")
                                append(catalog)
                            }
                        }.trim(),
                    )
                }
            }
            "/reasoning" -> {
                val effort = inv.args.trim().lowercase().ifBlank { null }
                if (effort != null && effort in setOf("low", "medium", "high", "minimal", "xhigh")) {
                    ctx.setEffort(effort)
                    Result.Notice("Lập luận", "Đã đặt mức reasoning thành `$effort`.")
                } else {
                    Result.Notice(
                        "Lập luận",
                        "Chọn mức từ thanh dưới, hoặc gửi `/reasoning low|medium|high`.",
                    )
                }
            }
            "/fast" -> {
                val on = ctx.toggleFast()
                Result.Notice(
                    "Nhanh",
                    if (on) "Đã bật service tier `fast` cho lượt tiếp theo."
                    else "Đã tắt service tier `fast`.",
                )
            }
            "/personality" -> {
                if (inv.args.isBlank()) {
                    Result.Notice("Tính cách", "Cú pháp: `/personality <giá trị>`.")
                } else {
                    ctx.setPersonality(inv.args.trim())
                    Result.Notice("Tính cách", "Đã đặt personality `${inv.args.trim()}` cho lượt tiếp theo.")
                }
            }
            "/goal" -> setGoal(ctx, inv.args)
            "/init" -> Result.StartTurn("Khởi tạo", INIT_PROMPT)
            "/local" -> Result.Notice(
                "Local",
                "Đang dùng thực thi local với CWD project: `${ctx.cwd ?: "—"}`.",
            )
            "/project" -> Result.Notice(
                "Project",
                "CWD content-root hiện tại: `${ctx.cwd ?: "—"}`.",
            )
            "/feedback" -> feedback(ctx)
            "/review" -> review(ctx)
            "/approve" -> approve(ctx)
            "/memories" -> memories(ctx, inv.args)
            "/plan" -> plan(ctx)
            else -> Result.Unavailable("Chưa có handler cho ${inv.name}")
        }
    }

    private fun mcpStatus(ctx: Context): Result {
        val gw = ctx.service.gateway()
            ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            val result = gw.request("mcpServerStatus/list", JsonObject())
                .get(5, TimeUnit.SECONDS)
                .result
            val servers = result?.getAsJsonArray("servers")
                ?: result?.getAsJsonArray("mcpServers")
            if (servers == null || servers.size() == 0) {
                Result.Notice("MCP", "Không có máy chủ MCP nào được báo cáo.")
            } else {
                val body = buildString {
                    appendLine("**Máy chủ MCP**")
                    appendLine()
                    servers.forEach { el ->
                        if (!el.isJsonObject) return@forEach
                        val o = el.asJsonObject
                        val name = o.get("name")?.asString
                            ?: o.get("server")?.asString
                            ?: o.get("id")?.asString
                            ?: "?"
                        val status = o.get("status")?.asString
                            ?: o.get("state")?.asString
                            ?: "—"
                        appendLine("- `$name` · $status")
                    }
                }
                Result.Notice("MCP", body.trim())
            }
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Không đọc được MCP status")
        }
    }

    private fun compact(ctx: Context): Result {
        val thread = ctx.threadId ?: return Result.Unavailable("Chưa có nhiệm vụ để thu gọn")
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            ThreadCommandHandler(gw).compact(thread.value).get(8, TimeUnit.SECONDS)
            Result.Notice("Thu gọn", "Đã gửi `thread/compact/start` cho nhiệm vụ `${thread.value}`.")
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Compact thất bại")
        }
    }

    private fun fork(ctx: Context): Result {
        val thread = ctx.threadId ?: return Result.Unavailable("Chưa có nhiệm vụ để fork")
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            val response = ThreadCommandHandler(gw).fork(thread.value).get(8, TimeUnit.SECONDS)
            val newId = response.result?.get("threadId")?.asString
                ?: response.result?.getAsJsonObject("thread")?.get("id")?.asString
            if (!newId.isNullOrBlank()) {
                ctx.onThreadForked(ThreadId(newId))
                Result.Notice("Fork", "Đã tạo nhiệm vụ mới `$newId` từ `${thread.value}`.")
            } else {
                Result.Notice("Fork", "Đã gửi `thread/fork` (không nhận được thread id trong response).")
            }
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Fork thất bại")
        }
    }

    private fun setGoal(ctx: Context, args: String): Result {
        val thread = ctx.threadId ?: return Result.Unavailable("Chưa có nhiệm vụ để đặt mục tiêu")
        if (args.isBlank()) {
            return Result.Notice("Mục tiêu", "Cú pháp: `/goal <nội dung mục tiêu>`.")
        }
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            ThreadCommandHandler(gw).setGoal(thread.value, args).get(8, TimeUnit.SECONDS)
            Result.Notice("Mục tiêu", "Đã đặt mục tiêu:\n\n$args")
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Không đặt được mục tiêu")
        }
    }

    private fun feedback(ctx: Context): Result {
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            val bundle = gw.redactedDiagnostics()
            val ok = FeedbackCommandHandler(gw).upload(bundle, consentGranted = true)
                .get(10, TimeUnit.SECONDS)
            if (ok) {
                Result.Notice(
                    "Phản hồi",
                    "Đã gửi gói chẩn đoán đã redacted (`sha256=${bundle.sha256.take(12)}…`, " +
                        "${bundle.eventCount} sự kiện, ${bundle.bytes.size} bytes).",
                )
            } else {
                Result.Unavailable("Server từ chối `feedback/upload`.")
            }
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Không gửi được feedback")
        }
    }

    private fun review(ctx: Context): Result {
        val thread = ctx.threadId ?: return Result.Unavailable("Chưa có nhiệm vụ để review")
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            val reviewId = ReviewCommandHandler(ReviewController(gw))
                .start(thread.value)
                .get(12, TimeUnit.SECONDS)
            Result.Notice("Review", "Đã bắt đầu review (`$reviewId`) cho nhiệm vụ `${thread.value}`.")
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Không bắt đầu được review")
        }
    }

    private fun approve(ctx: Context): Result {
        val pending = ctx.pendingApprovalCount()
        if (pending <= 0) {
            return Result.Notice("Approve", "Không có yêu cầu phê duyệt đang chờ.")
        }
        return if (ctx.approvePending()) {
            Result.Notice("Approve", "Đã chấp nhận yêu cầu phê duyệt đang chờ.")
        } else {
            Result.Unavailable("Không phê duyệt được yêu cầu hiện tại.")
        }
    }

    private fun memories(ctx: Context, args: String): Result {
        val thread = ctx.threadId ?: return Result.Unavailable("Chưa có nhiệm vụ")
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        val mode = when (args.trim().lowercase()) {
            "", "on", "enable", "enabled", "bật" -> "enabled"
            "off", "disable", "disabled", "tắt" -> "disabled"
            else -> return Result.Notice("Kỷ niệm", "Cú pháp: `/memories on|off`.")
        }
        return try {
            val params = JsonObject().apply {
                addProperty("threadId", thread.value)
                addProperty("mode", mode)
            }
            gw.request("thread/memoryMode/set", params).get(8, TimeUnit.SECONDS)
            Result.Notice(
                "Kỷ niệm",
                if (mode == "enabled") "Đã **bật** bộ nhớ cho nhiệm vụ này."
                else "Đã **tắt** bộ nhớ cho nhiệm vụ này.",
            )
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Không đổi được memory mode")
        }
    }

    private fun plan(ctx: Context): Result {
        val gw = ctx.service.gateway() ?: return Result.Unavailable("Chưa kết nối app-server")
        return try {
            val result = gw.request("collaborationMode/list", JsonObject())
                .get(8, TimeUnit.SECONDS)
                .result
            val data = result?.getAsJsonArray("data")
            if (data == null || data.size() == 0) {
                Result.Notice("Plan", "Không có collaboration mode nào được catalog.")
            } else {
                val body = buildString {
                    appendLine("**Collaboration modes** (chọn ở lượt tiếp theo nếu UI hỗ trợ):")
                    appendLine()
                    data.forEach { el ->
                        if (!el.isJsonObject) return@forEach
                        val o = el.asJsonObject
                        val name = o.get("name")?.asString ?: "?"
                        val mode = o.get("mode")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                        val model = o.get("model")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                        append("- `$name`")
                        if (!mode.isNullOrBlank()) append(" · mode=$mode")
                        if (!model.isNullOrBlank()) append(" · model=$model")
                        appendLine()
                    }
                }
                Result.Notice("Plan", body.trim())
            }
        } catch (ex: Exception) {
            Result.Unavailable(ex.message ?: "Không liệt kê được collaboration modes")
        }
    }
}
