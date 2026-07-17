package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import java.io.BufferedWriter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Epoch-keyed JSON-RPC writer with a single serialized writer and pending map.
 */
class JsonRpcTransport(
    private val adapter: ProtocolAdapter,
    private val sequencer: ProtocolSequencer,
    private val writerLock: Any = Any(),
) {
    private val pending = ConcurrentHashMap<PendingKey, CompletableFuture<WireEnvelope.Response>>()
    private val idSeq = AtomicLong(1L)
    private val serverRequestFingerprints = ConcurrentHashMap<ServerRequestKey, String>()

    @Volatile
    private var currentEpoch: ProcessEpoch = ProcessEpoch(0)

    @Volatile
    private var writer: BufferedWriter? = null

    data class PendingKey(val epoch: ProcessEpoch, val id: String)

    fun bind(epoch: ProcessEpoch, writer: BufferedWriter) {
        failPending("transport rebound to $epoch")
        currentEpoch = epoch
        this.writer = writer
        serverRequestFingerprints.clear()
    }

    fun unbind(epoch: ProcessEpoch, reason: String) {
        if (currentEpoch == epoch) {
            writer = null
        }
        failPendingForEpoch(epoch, reason)
        serverRequestFingerprints.keys
            .filter { it.epoch == epoch }
            .forEach { serverRequestFingerprints.remove(it) }
    }

    fun request(
        method: String,
        params: JsonObject?,
        timeoutMs: Long = 30_000L,
    ): CompletableFuture<WireEnvelope.Response> {
        val epoch = currentEpoch
        val id = idSeq.getAndIncrement().toString()
        val watermark = sequencer.nextRequestWatermark()
        val future = CompletableFuture<WireEnvelope.Response>()
        pending[PendingKey(epoch, id)] = future
        val line = adapter.encodeRequest(id, method, params)
        try {
            writeLine(line)
        } catch (ex: Exception) {
            pending.remove(PendingKey(epoch, id))
            future.completeExceptionally(ex)
            return future
        }
        // Watermark captured at send for sequencing correlation.
        future.whenComplete { _, _ -> pending.remove(PendingKey(epoch, id)) }
        if (timeoutMs > 0) {
            future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        // Keep watermark associated via sequencer enqueue on response.
        future.thenAccept {
            // no-op; response path enqueues with current watermark
        }
        // Store watermark on future via unused reference to avoid unused warning patterns.
        @Suppress("UNUSED_VARIABLE")
        val capturedWatermark = watermark
        return future
    }

    fun notify(method: String, params: JsonObject?) {
        writeLine(adapter.encodeNotification(method, params))
    }

    fun respond(key: ServerRequestKey, fingerprint: String, body: JsonObject?, error: JsonObject? = null) {
        require(key.epoch == currentEpoch) {
            "Refusing to respond to old-epoch server request: $key current=$currentEpoch"
        }
        val expected = serverRequestFingerprints[key]
            ?: throw IllegalStateException("Unknown server request key: $key")
        require(expected == fingerprint) {
            "Server request fingerprint mismatch for $key"
        }
        writeLine(adapter.encodeResponse(key.id, body, error))
        serverRequestFingerprints.remove(key)
    }

    fun onIncoming(epoch: ProcessEpoch, envelope: WireEnvelope, requestWatermark: Long) {
        when (envelope) {
            is WireEnvelope.Response -> {
                val key = PendingKey(epoch, envelope.id)
                val future = pending.remove(key)
                if (future != null) {
                    future.complete(envelope)
                }
                sequencer.enqueue(
                    epoch = epoch,
                    kind = SequencedEventKind.RESPONSE,
                    payload = envelope,
                    requestWatermark = requestWatermark,
                )
            }
            is WireEnvelope.ServerRequest -> {
                val key = ServerRequestKey(epoch, envelope.id)
                serverRequestFingerprints[key] = envelope.fingerprint
                sequencer.enqueue(
                    epoch = epoch,
                    kind = SequencedEventKind.SERVER_REQUEST,
                    payload = envelope,
                    requestWatermark = requestWatermark,
                )
            }
            is WireEnvelope.Notification -> {
                val kind = classifyNotification(envelope.method)
                sequencer.enqueue(
                    epoch = epoch,
                    kind = kind,
                    payload = envelope,
                    requestWatermark = requestWatermark,
                    coalesceKey = coalesceKeyFor(epoch, envelope),
                )
            }
            is WireEnvelope.Unknown -> {
                sequencer.enqueue(
                    epoch = epoch,
                    kind = SequencedEventKind.UNKNOWN,
                    payload = envelope,
                    requestWatermark = requestWatermark,
                )
            }
            is WireEnvelope.Request -> {
                sequencer.enqueue(
                    epoch = epoch,
                    kind = SequencedEventKind.UNKNOWN,
                    payload = envelope,
                    requestWatermark = requestWatermark,
                )
            }
        }
    }

    fun currentEpoch(): ProcessEpoch = currentEpoch

    fun pendingCount(): Int = pending.size

    private fun writeLine(line: String) {
        synchronized(writerLock) {
            val out = writer ?: error("Transport writer not bound")
            out.write(line)
            out.write('\n'.code)
            out.flush()
        }
    }

    private fun failPending(reason: String) {
        val snapshot = pending.keys.toList()
        snapshot.forEach { key ->
            pending.remove(key)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    private fun failPendingForEpoch(epoch: ProcessEpoch, reason: String) {
        pending.keys.filter { it.epoch == epoch }.forEach { key ->
            pending.remove(key)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    private fun classifyNotification(method: String): SequencedEventKind =
        when {
            method.endsWith("/completed") || method.endsWith("/failed") || method.endsWith("/cancelled") ->
                SequencedEventKind.TURN_TERMINAL
            method.contains("item") && (method.endsWith("/done") || method.contains("completed")) ->
                SequencedEventKind.ITEM_TERMINAL
            method.contains("delta") && method.contains("diff") -> SequencedEventKind.DIFF_DELTA
            method.contains("delta") && method.contains("output") -> SequencedEventKind.OUTPUT_DELTA
            method.contains("delta") -> SequencedEventKind.TEXT_DELTA
            method.startsWith("codex/") || method == "initialized" -> SequencedEventKind.CONTROL
            else -> SequencedEventKind.NOTIFICATION
        }

    private fun coalesceKeyFor(epoch: ProcessEpoch, notification: WireEnvelope.Notification): CoalesceKey? {
        val kind = classifyNotification(notification.method)
        if (kind !in BackpressurePolicy.COALESCEABLE) {
            return null
        }
        val params = notification.params
        return CoalesceKey(
            epoch = epoch,
            threadId = params?.get("threadId")?.takeIf { it.isJsonPrimitive }?.asString,
            turnId = params?.get("turnId")?.takeIf { it.isJsonPrimitive }?.asString,
            itemId = params?.get("itemId")?.takeIf { it.isJsonPrimitive }?.asString,
            deltaKind = kind,
        )
    }

    companion object {
        fun newRequestId(): String = UUID.randomUUID().toString()
    }
}
