package dev.haibachvan.codexintellij.commands

sealed class DispatchResult {
    data class Ok(val detail: String) : DispatchResult()
    data class Unavailable(val reason: String) : DispatchResult()
}

class SlashCommandDispatcher(
    private val experimentalOptIn: () -> Boolean = { false },
    private val signedIn: () -> Boolean = { false },
    private val rpc: (String) -> Unit = {},
    private val local: (String) -> Unit = {},
) {
    fun dispatch(name: String): DispatchResult {
        val spec = SlashCommandRegistry.find(name)
            ?: return DispatchResult.Unavailable("unknown command")
        when (spec.availability) {
            CommandAvailability.VISIBLE_DISABLED ->
                return DispatchResult.Unavailable((spec.route as CommandRoute.Disabled).reason)
            CommandAvailability.EXPERIMENTAL_GATED ->
                if (!experimentalOptIn()) return DispatchResult.Unavailable("experimental API disabled")
            CommandAvailability.ACCOUNT_GATED ->
                if (!signedIn()) return DispatchResult.Unavailable("account required")
            CommandAvailability.AVAILABLE -> Unit
        }
        return when (val route = spec.route) {
            is CommandRoute.Rpc -> {
                rpc(route.method)
                DispatchResult.Ok("rpc:${route.method}")
            }
            is CommandRoute.ClientLocal -> {
                local(route.behavior)
                DispatchResult.Ok("local:${route.behavior}")
            }
            is CommandRoute.Disabled -> DispatchResult.Unavailable(route.reason)
        }
    }
}
