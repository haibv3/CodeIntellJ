package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Single sequencing lane for all RPC responses, notifications, server requests, and snapshots.
 * Assigns arrival sequence and request watermark; coalesces only keyed deltas.
 * Text/output deltas concatenate chunks on coalesce so streaming text stays lossless.
 */
class ProtocolSequencer(
    private val policy: BackpressurePolicy = BackpressurePolicy(),
    private val maxBufferedEvents: Int = 10_000,
) {
    private val arrival = AtomicLong(0L)
    private val requestWatermark = AtomicLong(0L)
    private val queue = ArrayDeque<SequencedEvent>()
    private val coalesceIndex = LinkedHashMap<CoalesceKey, SequencedEvent>()
    private val lock = Any()

    fun nextRequestWatermark(): Long = requestWatermark.incrementAndGet()

    fun currentRequestWatermark(): Long = requestWatermark.get()

    fun enqueue(
        epoch: ProcessEpoch,
        kind: SequencedEventKind,
        payload: WireEnvelope,
        requestWatermark: Long = currentRequestWatermark(),
        coalesceKey: CoalesceKey? = null,
    ): SequencedEvent {
        var event = SequencedEvent(
            epoch = epoch,
            arrivalSeq = arrival.incrementAndGet(),
            requestWatermark = requestWatermark,
            kind = kind,
            coalesceKey = coalesceKey,
            payload = payload,
        )
        synchronized(lock) {
            val key = policy.coalesceKeyFor(event)
            if (key != null) {
                val previous = coalesceIndex.put(key, event)
                if (previous != null) {
                    queue.remove(previous)
                    event = concatenateDeltaPayload(previous, event)
                    coalesceIndex[key] = event
                }
                queue.addLast(event)
            } else {
                queue.addLast(event)
            }
            trimIfNeeded()
        }
        return event
    }

    /**
     * Latest-wins coalesce must not drop earlier text chunks. Agent streaming sends
     * incremental deltas; concatenating preserved chunks keeps the store lossless under backlog.
     */
    private fun concatenateDeltaPayload(previous: SequencedEvent, next: SequencedEvent): SequencedEvent {
        val prevNote = previous.payload as? WireEnvelope.Notification ?: return next
        val nextNote = next.payload as? WireEnvelope.Notification ?: return next
        val prevChunk = deltaChunk(prevNote.params) ?: return next
        val nextChunk = deltaChunk(nextNote.params) ?: return next
        val mergedParams = (nextNote.params ?: JsonObject()).deepCopy()
        writeDeltaChunk(mergedParams, prevChunk + nextChunk)
        return next.copy(payload = nextNote.copy(params = mergedParams))
    }

    private fun deltaChunk(params: JsonObject?): String? {
        if (params == null) return null
        val delta = params.get("delta")
        when {
            delta != null && delta.isJsonPrimitive -> return delta.asString
            delta != null && delta.isJsonObject -> {
                val nested = delta.asJsonObject.get("text")
                if (nested != null && nested.isJsonPrimitive) return nested.asString
            }
        }
        val text = params.get("text")
        return text?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun writeDeltaChunk(params: JsonObject, chunk: String) {
        val delta = params.get("delta")
        when {
            delta != null && delta.isJsonPrimitive -> params.addProperty("delta", chunk)
            delta != null && delta.isJsonObject -> delta.asJsonObject.addProperty("text", chunk)
            params.has("text") -> params.addProperty("text", chunk)
            else -> params.addProperty("delta", chunk)
        }
    }

    fun poll(): SequencedEvent? =
        synchronized(lock) {
            val next = queue.pollFirst() ?: return null
            next.coalesceKey?.let { key ->
                if (coalesceIndex[key] === next) {
                    coalesceIndex.remove(key)
                }
            }
            next
        }

    fun drain(limit: Int = Int.MAX_VALUE): List<SequencedEvent> {
        val out = ArrayList<SequencedEvent>()
        while (out.size < limit) {
            val next = poll() ?: break
            out += next
        }
        return out
    }

    fun size(): Int = synchronized(lock) { queue.size }

    fun clearEpoch(epoch: ProcessEpoch) {
        synchronized(lock) {
            val retained = queue.filter { it.epoch != epoch }
            queue.clear()
            coalesceIndex.clear()
            retained.forEach { event ->
                queue.addLast(event)
                event.coalesceKey?.let { coalesceIndex[it] = event }
            }
        }
    }

    private fun trimIfNeeded() {
        // Never drop non-droppable events. If the queue is unbounded by control storm,
        // callers must fail the process; we only coalesce deltas above.
        while (queue.size > maxBufferedEvents) {
            val candidate = queue.firstOrNull { policy.canCoalesce(it.kind) } ?: break
            queue.remove(candidate)
            candidate.coalesceKey?.let { key ->
                if (coalesceIndex[key] === candidate) {
                    coalesceIndex.remove(key)
                }
            }
        }
    }
}
