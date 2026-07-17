package dev.haibachvan.codexintellij.commands

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.AppServerLifecycleActor
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ThreadId
import java.util.concurrent.TimeUnit

/**
 * Aggregates local thread/token facts with account RPCs for `/status`.
 */
class StatusCommandHandler(
    private val gateway: AppServerGateway?,
) {
    data class Context(
        val threadId: ThreadId?,
        val model: String?,
        val effort: String?,
        val cwd: String?,
        val state: NormalizedServerState,
        val lifecycleState: String?,
    )

    fun render(ctx: Context): String {
        val lines = mutableListOf<String>()
        lines += "**Trạng thái**"
        lines += ""
        lines += "- **Nhiệm vụ:** `${ctx.threadId?.value ?: "(chưa có)"}`"
        lines += "- **Mô hình:** ${ctx.model?.takeIf { it.isNotBlank() } ?: "mặc định"}"
        lines += "- **Reasoning:** ${ctx.effort ?: "—"}"
        lines += "- **CWD:** `${ctx.cwd ?: "—"}`"
        lines += "- **Kết nối:** ${ctx.lifecycleState ?: "không rõ"}"

        val usage = ctx.threadId?.let { ctx.state.threadTokenUsage[it] }
        if (usage != null) {
            val window = usage.modelContextWindow
            val pct = if (window != null && window > 0) {
                " (${usage.totalTokens * 100 / window}%)"
            } else {
                ""
            }
            val windowLabel = window?.let { " / ${formatCount(it)}" } ?: ""
            lines += "- **Ngữ cảnh:** ${formatCount(usage.totalTokens)}$windowLabel$pct"
            lines += "- **Lượt gần nhất:** ${formatCount(usage.lastTokens)} token"
        } else {
            lines += "- **Ngữ cảnh:** chưa có dữ liệu token cho nhiệm vụ này"
        }

        lines += ""
        lines += accountSection()
        lines += ""
        lines += rateLimitSection()
        return lines.joinToString("\n")
    }

    private fun accountSection(): String {
        val gw = gateway ?: return "- **Tài khoản:** chưa kết nối app-server"
        return try {
            val result = gw.request("account/read", JsonObject())
                .get(4, TimeUnit.SECONDS)
                .result
            if (result == null) {
                return "- **Tài khoản:** không đọc được"
            }
            val requiresAuth = result.get("requiresOpenaiAuth")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            val account = result.getAsJsonObject("account")
            when {
                account == null && requiresAuth ->
                    "- **Tài khoản:** chưa đăng nhập"
                account == null ->
                    "- **Tài khoản:** không yêu cầu đăng nhập / không có thông tin"
                else -> {
                    val type = account.get("type")?.asString ?: "unknown"
                    val email = account.get("email")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                    val plan = account.get("planType")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
                    buildString {
                        append("- **Tài khoản:** đã đăng nhập (`$type`)")
                        if (!email.isNullOrBlank()) append(" · $email")
                        if (!plan.isNullOrBlank()) append(" · gói `$plan`")
                    }
                }
            }
        } catch (ex: Exception) {
            "- **Tài khoản:** ${ex.message ?: "lỗi đọc"}"
        }
    }

    private fun rateLimitSection(): String {
        val gw = gateway ?: return "- **Giới hạn:** —"
        return try {
            val result = gw.request("account/rateLimits/read", JsonObject())
                .get(4, TimeUnit.SECONDS)
                .result
            val snap = result?.getAsJsonObject("rateLimits")
                ?: return "- **Giới hạn:** không có dữ liệu (có thể chưa đăng nhập)"
            val primary = snap.getAsJsonObject("primary")
            val secondary = snap.getAsJsonObject("secondary")
            val plan = snap.get("planType")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
            buildList {
                add("- **Giới hạn tốc độ:**")
                if (!plan.isNullOrBlank()) add("  - Gói: `$plan`")
                add("  - Cửa sổ chính: ${windowLabel(primary)}")
                add("  - Cửa sổ phụ: ${windowLabel(secondary)}")
            }.joinToString("\n")
        } catch (ex: Exception) {
            "- **Giới hạn:** ${ex.message ?: "không đọc được"}"
        }
    }

    private fun windowLabel(window: JsonObject?): String {
        if (window == null) return "không có"
        val used = window.get("usedPercent")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: return "không rõ"
        val mins = window.get("windowDurationMins")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong
        val resets = window.get("resetsAt")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong
        val parts = mutableListOf("đã dùng $used%")
        if (mins != null) parts += "cửa sổ ${mins}p"
        if (resets != null) {
            val remainSec = resets - (System.currentTimeMillis() / 1000)
            if (remainSec > 0) {
                val h = remainSec / 3600
                val m = (remainSec % 3600) / 60
                parts += if (h > 0) "reset sau ${h}g ${m}p" else "reset sau ${m}p"
            }
        }
        return parts.joinToString(" · ")
    }

    companion object {
        fun lifecycleLabel(state: AppServerLifecycleActor.State?): String =
            when (state) {
                null -> "chưa kết nối"
                AppServerLifecycleActor.State.Ready -> "đã kết nối"
                AppServerLifecycleActor.State.Starting -> "đang kết nối"
                AppServerLifecycleActor.State.Stopping -> "đang dừng"
                AppServerLifecycleActor.State.Stopped -> "đã dừng"
                AppServerLifecycleActor.State.Disposed -> "đã đóng"
            }

        fun formatCount(n: Long): String =
            "%,d".format(n)
    }
}
