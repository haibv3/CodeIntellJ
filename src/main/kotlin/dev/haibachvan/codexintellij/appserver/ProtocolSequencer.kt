package dev.haibachvan.codexintellij.appserver

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Single sequencing lane for all RPC responses, notifications, server requests, and snapshots.
 * Assigns arrival sequence and request watermark; coalesces only keyed deltas.
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
        val event = SequencedEvent(
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
                }
                queue.addLast(event)
            } else {
                queue.addLast(event)
            }
            trimIfNeeded()
        }
        return event
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
