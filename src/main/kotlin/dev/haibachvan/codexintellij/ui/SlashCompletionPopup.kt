package dev.haibachvan.codexintellij.ui

import dev.haibachvan.codexintellij.commands.CommandAvailability
import dev.haibachvan.codexintellij.commands.CommandRouteSpec
import dev.haibachvan.codexintellij.commands.SlashCommandRegistry

class SlashCompletionPopup(
    private val experimentalOptIn: Boolean = false,
    private val signedIn: Boolean = false,
) {
    fun filter(prefix: String): List<CommandRouteSpec> {
        val normalized = if (prefix.startsWith("/")) prefix else "/$prefix"
        val available = SlashCommandRegistry.available(experimentalOptIn, signedIn).map { it.name }.toSet()
        return SlashCommandRegistry.ALL
            .filter { it.name.startsWith(normalized, ignoreCase = true) }
            .filter { it.name in available || it.availability == CommandAvailability.VISIBLE_DISABLED }
    }

    fun visibleNames(prefix: String): List<String> = filter(prefix).map { it.name }
}
