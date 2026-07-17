package dev.haibachvan.codexintellij.commands

enum class CommandAvailability {
    AVAILABLE,
    EXPERIMENTAL_GATED,
    VISIBLE_DISABLED,
    ACCOUNT_GATED,
}

sealed class CommandRoute {
    data class Rpc(val method: String) : CommandRoute()
    data class ClientLocal(val behavior: String) : CommandRoute()
    data class Disabled(val reason: String) : CommandRoute()
}

data class CommandRouteSpec(
    val name: String,
    val availability: CommandAvailability,
    val route: CommandRoute,
    val neverRawPrompt: Boolean = true,
)
