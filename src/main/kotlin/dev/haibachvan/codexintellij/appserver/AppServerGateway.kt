package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.session.CapabilityRegistry
import java.util.concurrent.CompletableFuture

/**
 * Sole session boundary for UI/domain callers. All traffic goes through capability + epoch transport.
 */
class AppServerGateway(
    private val lifecycle: AppServerLifecycleActor,
    private val transport: JsonRpcTransport,
    private val sequencer: ProtocolSequencer,
    private val capabilities: CapabilityRegistry,
) {
    data class Status(
        val state: AppServerLifecycleActor.State,
        val epoch: ProcessEpoch,
        val experimentalApiEnabled: Boolean,
        val userAgent: String?,
        val pendingRequests: Int,
        val sequencedBuffered: Int,
        val diagnosticCount: Int,
    )

    fun start(extraEnvKeys: Set<String> = emptySet()): ProcessEpoch {
        val result = lifecycle.start(extraEnvKeys)
        capabilities.onInitialized(result.initializeResult, experimentalOptIn = false)
        return result.epoch
    }

    fun stop() = lifecycle.stop()

    fun restart(extraEnvKeys: Set<String> = emptySet()): ProcessEpoch {
        val result = lifecycle.restart(extraEnvKeys)
        capabilities.onInitialized(result.initializeResult, experimentalOptIn = false)
        return result.epoch
    }

    fun dispose() = lifecycle.close()

    fun status(): Status {
        val snap = capabilities.snapshot()
        return Status(
            state = lifecycle.state(),
            epoch = lifecycle.epoch(),
            experimentalApiEnabled = snap.experimentalApiEnabled,
            userAgent = snap.userAgent,
            pendingRequests = transport.pendingCount(),
            sequencedBuffered = sequencer.size(),
            diagnosticCount = lifecycle.diagnosticBuffer().size(),
        )
    }

    fun request(method: String, params: JsonObject?): CompletableFuture<WireEnvelope.Response> {
        when (val decision = capabilities.require(method)) {
            is CapabilityRegistry.Decision.Allowed -> Unit
            is CapabilityRegistry.Decision.Unavailable ->
                return CompletableFuture.failedFuture(IllegalStateException(decision.reason))
        }
        return transport.request(method, params)
    }

    fun respond(key: ServerRequestKey, fingerprint: String, body: JsonObject?, error: JsonObject? = null) {
        transport.respond(key, fingerprint, body, error)
    }

    fun pollEvents(limit: Int = 100): List<SequencedEvent> = sequencer.drain(limit)

    fun redactedDiagnostics(): RedactedBundle = lifecycle.diagnosticBuffer().bundle()
}
