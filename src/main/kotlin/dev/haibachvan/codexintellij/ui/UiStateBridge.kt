package dev.haibachvan.codexintellij.ui

import com.intellij.openapi.Disposable
import dev.haibachvan.codexintellij.session.ItemFact
import dev.haibachvan.codexintellij.session.NormalizedServerState
import dev.haibachvan.codexintellij.session.ServerStateStore
import dev.haibachvan.codexintellij.session.ThreadId
import dev.haibachvan.codexintellij.session.TurnId
import java.util.EnumMap
import java.util.function.Consumer
import javax.swing.SwingUtilities
import javax.swing.Timer

internal enum class UiSurface {
    TRANSCRIPT,
    BUSY,
    TASKS,
    AGENTS,
    TITLE,
}

internal data class UiStateDelivery(
    val state: NormalizedServerState,
    val surfaces: Set<UiSurface>,
)

internal data class UiStateBridgeMetrics(
    val offered: Long,
    val delivered: Long,
    val merged: Long,
    val pendingHighWater: Int,
)

internal fun interface UiStateScheduler {
    fun schedule(delayMs: Int, task: () -> Unit)
}

internal fun interface UiStateClock {
    fun nowMs(): Long
}

private object SwingUiStateScheduler : UiStateScheduler {
    override fun schedule(delayMs: Int, task: () -> Unit) {
        SwingUtilities.invokeLater {
            Timer(delayMs) { task() }.also {
                it.isRepeats = false
                it.start()
            }
        }
    }
}

private object SystemUiStateClock : UiStateClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

internal class UiStateBridge(
    private val store: ServerStateStore,
    private val selectedThread: () -> ThreadId?,
    private val activeTurnId: () -> String?,
    private val enabledSurfaces: Set<UiSurface> = UiSurface.entries.toSet(),
    private val scheduler: UiStateScheduler = SwingUiStateScheduler,
    private val clock: UiStateClock = SystemUiStateClock,
    private val onDelivery: (UiStateDelivery) -> Unit,
) : Disposable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val deliveredKeys = EnumMap<UiSurface, Any?>(UiSurface::class.java)
    private val forcedSurfaces = mutableSetOf<UiSurface>()
    private var latestState: NormalizedServerState? = null
    private var scheduled = false
    private var disposed = false
    private var lastChromeDeliveryMs: Long? = null
    private var offeredCount = 0L
    private var deliveredCount = 0L
    private var mergedCount = 0L
    private var pendingHighWater = 0
    private var callbacksInProgress = 0
    private var callbackThread: Thread? = null
    private val stateListener = Consumer<NormalizedServerState>(::offer)

    init {
        store.addListener(stateListener)
    }

    fun offer(
        state: NormalizedServerState,
        force: Set<UiSurface> = emptySet(),
    ) {
        val shouldSchedule = synchronized(lock) {
            if (disposed) return
            latestState = state
            forcedSurfaces += force.intersect(enabledSurfaces)
            offeredCount += 1
            if (scheduled) {
                mergedCount += 1
                false
            } else {
                scheduled = true
                pendingHighWater = maxOf(pendingHighWater, 1)
                true
            }
        }
        if (shouldSchedule) schedule(TRANSCRIPT_CADENCE_MS)
    }

    fun metrics(): UiStateBridgeMetrics = synchronized(lock) {
        UiStateBridgeMetrics(offeredCount, deliveredCount, mergedCount, pendingHighWater)
    }

    private fun schedule(delayMs: Int) {
        scheduler.schedule(delayMs, ::drain)
    }

    private fun drain() {
        val action = synchronized(lock) {
            if (disposed) {
                scheduled = false
                return
            }
            val state = latestState ?: run {
                scheduled = false
                return
            }
            val now = clock.nowMs()
            val keys = projectionKeys(state)
            val dirty = enabledSurfaces.filterTo(mutableSetOf()) { surface ->
                surface in forcedSurfaces || deliveredKeys[surface] != keys[surface]
            }
            val chromeDueIn = lastChromeDeliveryMs
                ?.let { (CHROME_CADENCE_MS - (now - it)).coerceAtLeast(0L) }
                ?: 0L
            val deliverNow = dirty.filterTo(mutableSetOf()) { surface ->
                surface !in CHROME_SURFACES || chromeDueIn == 0L
            }

            deliverNow.forEach { surface ->
                deliveredKeys[surface] = keys[surface]
                forcedSurfaces.remove(surface)
            }
            if (deliverNow.any { it in CHROME_SURFACES }) {
                lastChromeDeliveryMs = now
            }

            val deferred = dirty - deliverNow
            scheduled = deferred.isNotEmpty()
            DrainAction(
                delivery = deliverNow.takeIf { it.isNotEmpty() }
                    ?.let { UiStateDelivery(state, it) },
                nextDelayMs = chromeDueIn.toInt().takeIf { deferred.isNotEmpty() },
            )
        }

        action.nextDelayMs?.let { schedule(maxOf(it, 1)) }
        val delivery = action.delivery ?: return
        val shouldDeliver = synchronized(lock) {
            if (disposed) false else {
                deliveredCount += 1
                callbacksInProgress += 1
                callbackThread = Thread.currentThread()
                true
            }
        }
        if (shouldDeliver) {
            try {
                onDelivery(delivery)
            } finally {
                synchronized(lock) {
                    callbacksInProgress -= 1
                    if (callbacksInProgress == 0) callbackThread = null
                    lock.notifyAll()
                }
            }
        }
    }

    private fun projectionKeys(state: NormalizedServerState): Map<UiSurface, Any?> {
        val thread = selectedThread()
        val threadItems = state.items.values.filter { it.threadId == thread }
        val itemIds = threadItems.mapTo(mutableSetOf()) { it.id }
        val threadTurns = state.turns.values.filter { it.threadId == thread }
        val visibleAgents = state.agents.filter { (itemId, _) ->
            val item = state.items[itemId]
            item == null || thread == null || item.threadId == thread
        }
        val firstUserMessage = threadItems
            .filterIsInstance<ItemFact.UserMessage>()
            .minByOrNull { it.arrivalSeq }
        val activeTurn = activeTurnId()?.let(::TurnId)

        return mapOf(
            UiSurface.TRANSCRIPT to listOf(
                thread,
                threadItems,
                threadTurns,
                state.patches.filterKeys { it in itemIds },
                visibleAgents,
                thread?.let(state.threadTokenUsage::get),
            ),
            UiSurface.BUSY to listOf(activeTurn, activeTurn?.let(state.turns::get)),
            UiSurface.TASKS to state.threads,
            UiSurface.AGENTS to listOf(thread, visibleAgents),
            UiSurface.TITLE to listOf(thread, thread?.let(state.threads::get), firstUserMessage?.text),
        )
    }

    override fun dispose() {
        val shouldRemove = synchronized(lock) {
            if (disposed) false else {
                disposed = true
                latestState = null
                forcedSurfaces.clear()
                scheduled = false
                while (callbacksInProgress > 0 && callbackThread != Thread.currentThread()) {
                    lock.wait()
                }
                true
            }
        }
        if (shouldRemove) store.removeListener(stateListener)
    }

    private data class DrainAction(
        val delivery: UiStateDelivery?,
        val nextDelayMs: Int?,
    )

    private companion object {
        const val TRANSCRIPT_CADENCE_MS = 50
        const val CHROME_CADENCE_MS = 100L
        val CHROME_SURFACES = setOf(UiSurface.TASKS, UiSurface.AGENTS, UiSurface.TITLE)
    }
}
