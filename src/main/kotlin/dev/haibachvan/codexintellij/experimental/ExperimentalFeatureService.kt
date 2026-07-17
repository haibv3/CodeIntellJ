package dev.haibachvan.codexintellij.experimental

import dev.haibachvan.codexintellij.commands.CommandAvailability
import dev.haibachvan.codexintellij.commands.SlashCommandRegistry

data class FeatureGate(
    val name: String,
    val enabled: Boolean,
    val reason: String,
)

class ExperimentalFeatureService(
    private val experimentalOptIn: () -> Boolean,
    private val sideTraceAccepted: () -> Boolean = { false },
) {
    fun gates(): List<FeatureGate> =
        SlashCommandRegistry.ALL
            .filter {
                it.availability == CommandAvailability.EXPERIMENTAL_GATED ||
                    it.availability == CommandAvailability.VISIBLE_DISABLED
            }
            .map { spec ->
                when (spec.name) {
                    "/side" -> FeatureGate(
                        spec.name,
                        enabled = sideTraceAccepted(),
                        reason = if (sideTraceAccepted()) "trace accepted" else "awaiting accepted semantic trace",
                    )
                    "/plan", "/memories" -> FeatureGate(
                        spec.name,
                        enabled = experimentalOptIn(),
                        reason = if (experimentalOptIn()) "experimental opt-in" else "experimental API disabled",
                    )
                    else -> FeatureGate(spec.name, enabled = false, reason = "visible-disabled without public contract")
                }
            }
}
