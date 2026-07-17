package dev.haibachvan.codexintellij.session

import dev.haibachvan.codexintellij.appserver.SequencedEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Serialized owner of [NormalizedServerState]. Exposes immutable snapshots to UI/projections.
 */
class ServerStateStore(
    private val reducer: ConversationReducer = ConversationReducer(),
) {
    private val lock = Any()

    @Volatile
    private var state: NormalizedServerState = NormalizedServerState()

    private val listeners = CopyOnWriteArrayList<Consumer<NormalizedServerState>>()

    fun snapshot(): NormalizedServerState = state

    fun dispatch(event: SequencedEvent): NormalizedServerState =
        synchronized(lock) {
            state = reducer.reduce(state, event)
            notifyListeners(state)
            state
        }

    fun applySnapshot(envelope: SnapshotEnvelope): NormalizedServerState =
        synchronized(lock) {
            state = reducer.applySnapshot(state, envelope)
            notifyListeners(state)
            state
        }

    fun replace(next: NormalizedServerState): NormalizedServerState =
        synchronized(lock) {
            state = next
            notifyListeners(state)
            state
        }

    /**
     * Replaces turns/items for one thread from a resume/read snapshot.
     * Avoids stale-watermark merges that skip updates when `requestWatermark` is 0.
     * If the server returns metadata-only (no turns/items), existing transcript is kept.
     */
    fun replaceThreadHistory(threadId: ThreadId, envelope: SnapshotEnvelope): NormalizedServerState =
        synchronized(lock) {
            val current = state
            val threadFact = envelope.threads.firstOrNull { it.id == threadId }
                ?: current.threads[threadId]
            val newItems = envelope.items.filter { it.threadId == threadId }
            val newTurns = envelope.turns.filter { it.threadId == threadId }
            // Incomplete payloads (metadata-only, or turns with itemsView=notLoaded) must
            // never wipe an existing transcript.
            if (newItems.isEmpty()) {
                if (threadFact != null) {
                    state = current.copy(threads = current.threads + (threadId to threadFact))
                    notifyListeners(state)
                }
                return state
            }
            val nextThreads = if (threadFact != null) {
                current.threads + (threadId to threadFact)
            } else {
                current.threads
            }
            val droppedItemIds = current.items.filterValues { it.threadId == threadId }.keys
            state = current.copy(
                threads = nextThreads,
                turns = current.turns.filterValues { it.threadId != threadId } +
                    newTurns.associateBy { it.id },
                items = current.items.filterValues { it.threadId != threadId } +
                    newItems.associateBy { it.id },
                patches = current.patches.filterKeys { it !in droppedItemIds } +
                    envelope.patches.associateBy { it.itemId },
                agents = current.agents.filterKeys { it !in droppedItemIds } +
                    envelope.agents.associateBy { it.itemId },
            )
            notifyListeners(state)
            state
        }

    fun addListener(listener: Consumer<NormalizedServerState>) {
        listeners.add(listener)
    }

    fun removeListener(listener: Consumer<NormalizedServerState>) {
        listeners.remove(listener)
    }

    private fun notifyListeners(snapshot: NormalizedServerState) {
        listeners.forEach { it.accept(snapshot) }
    }
}
