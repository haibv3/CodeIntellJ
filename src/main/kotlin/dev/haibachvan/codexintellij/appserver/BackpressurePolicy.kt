package dev.haibachvan.codexintellij.appserver

/**
 * Drain-first backpressure: non-droppable control/terminal/response events are retained;
 * only keyed text/output/diff deltas may coalesce to the latest value.
 * Coalesced text deltas concatenate chunks (see [ProtocolSequencer]) so content is not lost.
 */
class BackpressurePolicy {
    fun isNonDroppable(kind: SequencedEventKind): Boolean =
        kind in NON_DROPPABLE

    fun canCoalesce(kind: SequencedEventKind): Boolean =
        kind in COALESCEABLE

    fun coalesceKeyFor(event: SequencedEvent): CoalesceKey? {
        if (!canCoalesce(event.kind)) {
            return null
        }
        return event.coalesceKey
    }

    companion object {
        val NON_DROPPABLE: Set<SequencedEventKind> = setOf(
            SequencedEventKind.RESPONSE,
            SequencedEventKind.SERVER_REQUEST,
            SequencedEventKind.CONTROL,
            SequencedEventKind.TURN_TERMINAL,
            SequencedEventKind.ITEM_TERMINAL,
            SequencedEventKind.DIAGNOSTIC,
            SequencedEventKind.NOTIFICATION,
            SequencedEventKind.UNKNOWN,
        )

        val COALESCEABLE: Set<SequencedEventKind> = setOf(
            SequencedEventKind.TEXT_DELTA,
            SequencedEventKind.OUTPUT_DELTA,
            SequencedEventKind.DIFF_DELTA,
        )
    }
}
