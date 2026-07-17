package dev.haibachvan.codexintellij.appserver

/**
 * Opaque, monotonic process generation for a project session.
 * All wire correlation keys include an epoch so stale callbacks cannot act after restart.
 */
@JvmInline
value class ProcessEpoch(val value: Long) {
    init {
        require(value >= 0L) { "ProcessEpoch must be non-negative" }
    }

    fun next(): ProcessEpoch = ProcessEpoch(value + 1L)

    override fun toString(): String = "epoch-$value"
}

/** Server→client request identity bound to a process epoch. */
data class ServerRequestKey(
    val epoch: ProcessEpoch,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Server request id must not be blank" }
    }
}

/** Ordered gateway event tagged with arrival sequence and request watermark. */
data class SequencedEvent(
    val epoch: ProcessEpoch,
    val arrivalSeq: Long,
    val requestWatermark: Long,
    val kind: SequencedEventKind,
    val coalesceKey: CoalesceKey?,
    val payload: WireEnvelope,
)

enum class SequencedEventKind {
    RESPONSE,
    NOTIFICATION,
    SERVER_REQUEST,
    CONTROL,
    TURN_TERMINAL,
    ITEM_TERMINAL,
    TEXT_DELTA,
    OUTPUT_DELTA,
    DIFF_DELTA,
    DIAGNOSTIC,
    UNKNOWN,
}

data class CoalesceKey(
    val epoch: ProcessEpoch,
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val deltaKind: SequencedEventKind,
)
