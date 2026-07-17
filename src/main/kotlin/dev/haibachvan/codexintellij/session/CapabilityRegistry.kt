package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.appserver.CapabilitySnapshot
import dev.haibachvan.codexintellij.appserver.InitializeResult
import dev.haibachvan.codexintellij.appserver.ProtocolAdapter

/**
 * Gates methods by pinned schema map class, initialize result, and experimental opt-in.
 * Absent/unavailable methods yield local errors without wire traffic.
 */
class CapabilityRegistry(
    private val adapter: ProtocolAdapter,
) {
    @Volatile
    private var experimentalOptIn: Boolean = false

    @Volatile
    private var initializeResult: InitializeResult? = null

    @Volatile
    private var probedMethods: Set<String> = emptySet()

    sealed class Decision {
        data object Allowed : Decision()
        data class Unavailable(val reason: String) : Decision()
    }

    fun onInitialized(result: InitializeResult, experimentalOptIn: Boolean) {
        this.initializeResult = result
        this.experimentalOptIn = experimentalOptIn
    }

    fun setExperimentalOptIn(enabled: Boolean) {
        experimentalOptIn = enabled
    }

    fun markProbed(methods: Set<String>) {
        probedMethods = methods
    }

    fun require(method: String): Decision {
        val apiClass = adapter.apiClass(method)
            ?: return Decision.Unavailable("unmapped method: $method")
        if (initializeResult == null) {
            return Decision.Unavailable("not initialized")
        }
        if (apiClass == "experimental" && !experimentalOptIn) {
            return Decision.Unavailable("experimental API disabled for $method")
        }
        if (probedMethods.isNotEmpty() && method !in probedMethods && apiClass == "experimental") {
            return Decision.Unavailable("experimental method not probed: $method")
        }
        return Decision.Allowed
    }

    fun snapshot(): CapabilitySnapshot =
        CapabilitySnapshot(
            experimentalApiEnabled = experimentalOptIn,
            userAgent = initializeResult?.userAgent,
            supportedMethods = adapter.knownMethods().filter { method ->
                require(method) is Decision.Allowed
            }.toSet(),
        )
}
