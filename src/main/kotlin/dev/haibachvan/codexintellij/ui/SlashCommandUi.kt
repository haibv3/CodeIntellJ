package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.commands.CommandRouteSpec
import dev.haibachvan.codexintellij.commands.SlashCommandRegistry

/** Vietnamese titles/descriptions for slash suggestions (Codex IDE style). */
object SlashCommandUi {
    data class Meta(val title: String, val description: String)

    private val META: Map<String, Meta> = mapOf(
        "/init" to Meta("Khởi tạo", "Tạo AGENTS.md với hướng dẫn dành cho Codex"),
        "/memories" to Meta("Kỷ niệm", "Bật hoặc tắt bộ nhớ"),
        "/reasoning" to Meta("Lập luận", "Đặt mức reasoning (Nhẹ / Trung bình / Cao)"),
        "/mcp" to Meta("MCP", "Hiển thị trạng thái máy chủ MCP"),
        "/model" to Meta("Mô hình", "Chọn mô hình cho lượt tiếp theo"),
        "/goal" to Meta("Mục tiêu", "Đặt mục tiêu để tiếp tục theo đuổi"),
        "/ide-context" to Meta("Ngữ cảnh IDE", "Bật/tắt ngữ cảnh IDE tự động"),
        "/feedback" to Meta("Phản hồi", "Gửi phản hồi về nhiệm vụ này"),
        "/compact" to Meta("Thu gọn", "Thu gọn ngữ cảnh của nhiệm vụ này"),
        "/fork" to Meta("Tiếp tục trong tác vụ mới", "Tạo nhiệm vụ mới trong workspace hiện tại"),
        "/status" to Meta("Trạng thái", "Hiển thị ID nhiệm vụ, mức dùng ngữ cảnh và giới hạn"),
        "/review" to Meta("Xem xét mã", "Xem xét thay đổi chưa commit hoặc so với nhánh"),
        "/plan" to Meta("Chế độ lập kế hoạch", "Bật chế độ lập kế hoạch"),
        "/personality" to Meta("Tính cách", "Đặt personality cho lượt tiếp theo"),
        "/fast" to Meta("Nhanh", "Ưu tiên service tier nhanh hơn"),
        "/approve" to Meta("Phê duyệt", "Phê duyệt hành động bị chặn"),
        "/local" to Meta("Cục bộ", "Chọn thực thi cục bộ"),
        "/project" to Meta("Project", "Đặt cwd theo content root"),
        "/side" to Meta("Side", "Chưa hỗ trợ trên app-server công khai"),
        "/worktree" to Meta("Worktree", "Chưa hỗ trợ trên app-server công khai"),
        "/cloud" to Meta("Cloud", "Chưa hỗ trợ trên app-server công khai"),
        "/cloud-environment" to Meta("Cloud environment", "Chưa hỗ trợ trên app-server công khai"),
    )

    fun meta(spec: CommandRouteSpec): Meta =
        META[spec.name] ?: Meta(spec.name, "Lệnh ${spec.name}")

    fun matches(spec: CommandRouteSpec, query: String): Boolean {
        val q = query.trim().removePrefix("/").lowercase()
        if (q.isEmpty()) return true
        val meta = meta(spec)
        return spec.name.removePrefix("/").startsWith(q, ignoreCase = true) ||
            spec.name.contains(q, ignoreCase = true) ||
            meta.title.contains(q, ignoreCase = true) ||
            meta.description.contains(q, ignoreCase = true)
    }

    fun ordered(): List<CommandRouteSpec> {
        val preferred = listOf(
            "/init", "/memories", "/reasoning", "/mcp", "/model", "/goal",
            "/ide-context", "/feedback", "/compact", "/fork", "/status", "/review",
            "/plan", "/personality", "/fast", "/approve", "/local", "/project",
            "/side", "/worktree", "/cloud", "/cloud-environment",
        )
        val byName = SlashCommandRegistry.ALL.associateBy { it.name }
        val ordered = preferred.mapNotNull { byName[it] }
        val rest = SlashCommandRegistry.ALL.filter { it.name !in preferred }
        return ordered + rest
    }
}
