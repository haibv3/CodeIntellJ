package dev.haibachvan.codexintellij.commands

/**
 * Exact 22 slash-command semantic contracts. Disabled routes never become raw prompts.
 */
object SlashCommandRegistry {
    val ALL: List<CommandRouteSpec> = listOf(
        CommandRouteSpec("/approve", CommandAvailability.ACCOUNT_GATED, CommandRoute.Rpc("thread/approveGuardianDeniedAction")),
        CommandRouteSpec("/compact", CommandAvailability.AVAILABLE, CommandRoute.Rpc("thread/compact/start")),
        CommandRouteSpec("/feedback", CommandAvailability.AVAILABLE, CommandRoute.Rpc("feedback/upload")),
        CommandRouteSpec("/fork", CommandAvailability.AVAILABLE, CommandRoute.Rpc("thread/fork")),
        CommandRouteSpec("/goal", CommandAvailability.AVAILABLE, CommandRoute.Rpc("thread/goal/set")),
        CommandRouteSpec("/mcp", CommandAvailability.AVAILABLE, CommandRoute.Rpc("mcpServerStatus/list")),
        CommandRouteSpec("/model", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("next-turn-model-override")),
        CommandRouteSpec("/personality", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("next-turn-personality-override")),
        CommandRouteSpec("/reasoning", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("next-turn-effort-override")),
        CommandRouteSpec("/fast", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("next-turn-service-tier")),
        CommandRouteSpec("/review", CommandAvailability.AVAILABLE, CommandRoute.Rpc("review/start")),
        CommandRouteSpec("/status", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("status-aggregate")),
        CommandRouteSpec("/side", CommandAvailability.VISIBLE_DISABLED, CommandRoute.Disabled("awaiting accepted semantic trace")),
        CommandRouteSpec("/ide-context", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("toggle-automatic-context")),
        CommandRouteSpec("/init", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("scaffold-turn")),
        CommandRouteSpec("/local", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("select-local-execution")),
        CommandRouteSpec("/project", CommandAvailability.AVAILABLE, CommandRoute.ClientLocal("content-root-cwd")),
        CommandRouteSpec(
            "/worktree",
            CommandAvailability.VISIBLE_DISABLED,
            CommandRoute.Disabled("no public worktree contract; content-root targeting only"),
        ),
        CommandRouteSpec("/memories", CommandAvailability.EXPERIMENTAL_GATED, CommandRoute.Rpc("thread/memoryMode/set")),
        CommandRouteSpec("/plan", CommandAvailability.EXPERIMENTAL_GATED, CommandRoute.Rpc("collaborationMode/list")),
        CommandRouteSpec("/cloud", CommandAvailability.VISIBLE_DISABLED, CommandRoute.Disabled("no public cloud-task contract")),
        CommandRouteSpec(
            "/cloud-environment",
            CommandAvailability.VISIBLE_DISABLED,
            CommandRoute.Disabled("no public cloud-environment contract"),
        ),
    )

    init {
        require(ALL.size == 22) { "Expected exactly 22 slash commands, got ${ALL.size}" }
        require(ALL.map { it.name }.toSet().size == 22) { "Duplicate slash command names" }
    }

    fun find(name: String): CommandRouteSpec? = ALL.find { it.name == name }

    fun available(experimentalOptIn: Boolean, signedIn: Boolean): List<CommandRouteSpec> =
        ALL.filter { spec ->
            when (spec.availability) {
                CommandAvailability.AVAILABLE -> true
                CommandAvailability.EXPERIMENTAL_GATED -> experimentalOptIn
                CommandAvailability.ACCOUNT_GATED -> signedIn
                CommandAvailability.VISIBLE_DISABLED -> false
            }
        }
}
