package dev.haibachvan.codexintellij.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.AppServerGateway
import dev.haibachvan.codexintellij.appserver.SequencedEvent
import dev.haibachvan.codexintellij.appserver.SequencedEventKind
import dev.haibachvan.codexintellij.appserver.ServerRequestKey
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Owns RPC issuance and snapshot/recovery merge. Does not own gateway epochs or panel drafts.
 */
class ConversationController(
    private val gateway: AppServerGateway,
    private val store: ServerStateStore,
    private val approvals: ApprovalStateMachine = ApprovalStateMachine(),
) {
    private val pump = Executors.newSingleThreadExecutor { r ->
        Thread(r, "codex-conversation-pump").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    fun startEventPump() {
        if (running) return
        running = true
        pump.execute {
            while (running && !Thread.currentThread().isInterrupted) {
                val events = gateway.pollEvents(50)
                if (events.isEmpty()) {
                    Thread.sleep(15)
                    continue
                }
                events.forEach { onSequencedEvent(it) }
            }
        }
    }

    fun stopEventPump() {
        running = false
    }

    fun onSequencedEvent(event: SequencedEvent) {
        if (event.kind == SequencedEventKind.SERVER_REQUEST) {
            val payload = event.payload
            if (payload is WireEnvelope.ServerRequest) {
                approvals.onRequest(
                    ServerRequestKey(event.epoch, payload.id),
                    payload.fingerprint,
                    payload.method,
                    payload.params ?: JsonObject(),
                )
            }
        }
        store.dispatch(event)
    }

    fun approvals(): ApprovalStateMachine = approvals

    fun decideApproval(request: ApprovalRequest, decision: String) {
        val body = JsonObject().apply { addProperty("decision", decision) }
        approvals.choose(request.key, request.fingerprint, ApprovalDecision(decision))
        approvals.sending(request.key, request.fingerprint)
        gateway.respond(request.key, request.fingerprint, body)
        approvals.sent(request.key, request.fingerprint)
        approvals.resolve(request.key, request.fingerprint, decision)
    }

    fun startThread(title: String? = null, cwd: String? = null): CompletableFuture<ThreadId> {
        val params = JsonObject().apply {
            if (title != null) addProperty("title", title)
            if (!cwd.isNullOrBlank()) addProperty("cwd", cwd)
        }
        return gateway.request("thread/start", params).thenApply { response ->
            val result = response.result ?: error("thread/start empty result")
            val id = ThreadId(result.get("thread")?.asJsonObject?.get("id")?.asString
                ?: result.get("id")?.asString
                ?: error("thread/start missing id"))
            store.applySnapshot(
                SnapshotEnvelope(
                    epoch = gateway.status().epoch,
                    requestWatermark = 0L,
                    threads = listOf(
                        ThreadFact(id, ThreadStatus.ACTIVE, title, gateway.status().epoch, 0L),
                    ),
                ),
            )
            id
        }
    }

    data class TurnStartOptions(
        val model: String? = null,
        val effort: String? = null,
        val approvalPolicy: String? = null,
        val serviceTier: String? = null,
        val personality: String? = null,
        val cwd: String? = null,
    )

    fun startTurn(
        threadId: ThreadId,
        text: String,
        options: TurnStartOptions = TurnStartOptions(),
        attachments: List<JsonObject> = emptyList(),
    ): CompletableFuture<TurnId> {
        val params = JsonObject().apply {
            addProperty("threadId", threadId.value)
            add(
                "input",
                JsonArray().apply {
                    attachments.forEach { add(it) }
                    if (text.isNotBlank()) {
                        add(
                            JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", text)
                            },
                        )
                    }
                },
            )
            options.model?.takeIf { it.isNotBlank() }?.let { addProperty("model", it) }
            options.effort?.takeIf { it.isNotBlank() }?.let { addProperty("effort", it) }
            options.approvalPolicy?.takeIf { it.isNotBlank() }?.let { addProperty("approvalPolicy", it) }
            options.serviceTier?.takeIf { it.isNotBlank() }?.let { addProperty("serviceTier", it) }
            options.personality?.takeIf { it.isNotBlank() }?.let { addProperty("personality", it) }
            options.cwd?.takeIf { it.isNotBlank() }?.let { addProperty("cwd", it) }
        }
        return gateway.request("turn/start", params).thenApply { response ->
            val result = response.result ?: error("turn/start empty")
            TurnId(result.get("turn")?.asJsonObject?.get("id")?.asString
                ?: result.get("id")?.asString
                ?: error("turn/start missing id"))
        }
    }

    fun interrupt(threadId: ThreadId, turnId: TurnId): CompletableFuture<WireEnvelope.Response> {
        val params = JsonObject().apply {
            addProperty("threadId", threadId.value)
            addProperty("turnId", turnId.value)
        }
        return gateway.request("turn/interrupt", params)
    }

    fun steer(threadId: ThreadId, turnId: TurnId, text: String): CompletableFuture<WireEnvelope.Response> {
        val params = JsonObject().apply {
            addProperty("threadId", threadId.value)
            addProperty("turnId", turnId.value)
            addProperty("text", text)
        }
        return gateway.request("turn/steer", params)
    }

    fun archive(threadId: ThreadId): CompletableFuture<WireEnvelope.Response> {
        val params = JsonObject().apply { addProperty("threadId", threadId.value) }
        return gateway.request("thread/archive", params)
    }

    fun deleteThread(threadId: ThreadId): CompletableFuture<WireEnvelope.Response> {
        val params = JsonObject().apply { addProperty("threadId", threadId.value) }
        return gateway.request("thread/delete", params).thenApply { response ->
            removeThreadLocally(threadId)
            response
        }
    }

    fun removeThreadLocally(threadId: ThreadId) {
        store.replace(ConversationReducer.purgeThread(store.snapshot(), threadId))
    }

    /** Lists persisted threads (newest first) and merges them into the local store. */
    fun listThreads(cwd: String? = null, limit: Int = 24): CompletableFuture<List<ThreadId>> {
        val params = JsonObject().apply {
            addProperty("limit", limit)
            addProperty("sortKey", "updated_at")
            addProperty("sortDirection", "desc")
            if (!cwd.isNullOrBlank()) {
                addProperty("cwd", cwd)
            }
        }
        return gateway.request("thread/list", params).thenApply { response ->
            val data = response.result?.getAsJsonArray("data") ?: JsonArray()
            val epoch = gateway.status().epoch
            val envelope = ThreadSnapshotImport.fromListData(data, epoch)
            store.applySnapshot(envelope)
            envelope.threads.map { it.id }
        }
    }

    /**
     * Resumes a thread from disk and imports turns/items so the transcript can render.
     * Uses replace (not watermark merge) so reopen refreshes when full items are present.
     */
    fun resumeThread(threadId: ThreadId): CompletableFuture<ThreadId> {
        val params = JsonObject().apply { addProperty("threadId", threadId.value) }
        return gateway.request("thread/resume", params).thenApply { response ->
            val result = response.result ?: error("thread/resume empty result")
            val epoch = gateway.status().epoch
            store.replaceThreadHistory(threadId, ThreadSnapshotImport.fromResumeResult(result, epoch))
            threadId
        }
    }

    /**
     * Loads authoritative transcript history for a task open.
     * Order: resume(+full page) → turns/list(full) → thread/read.
     * Never clears existing items when the server returns an empty payload.
     */
    fun loadThreadHistory(threadId: ThreadId): CompletableFuture<ThreadId> {
        val resume = runCatching { resumeThread(threadId) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        return resume.handle { _, resumeError ->
            if (hasThreadItems(threadId)) {
                return@handle CompletableFuture.completedFuture(threadId)
            }
            listTurnsFull(threadId).handle { _, listError ->
                if (hasThreadItems(threadId)) {
                    return@handle CompletableFuture.completedFuture(threadId)
                }
                val params = JsonObject().apply {
                    addProperty("threadId", threadId.value)
                    addProperty("includeTurns", true)
                }
                gateway.request("thread/read", params).thenApply { response ->
                    val thread = response.result?.getAsJsonObject("thread")
                        ?: response.result
                    if (thread != null) {
                        store.replaceThreadHistory(
                            threadId,
                            ThreadSnapshotImport.fromThread(thread, gateway.status().epoch),
                        )
                    }
                    threadId
                }.exceptionally { readError ->
                    throw resumeError ?: listError ?: readError
                }
            }.thenCompose { it }
        }.thenCompose { it }
    }

    private fun hasThreadItems(threadId: ThreadId): Boolean =
        store.snapshot().items.values.any { it.threadId == threadId }

    private fun listTurnsFull(threadId: ThreadId): CompletableFuture<ThreadId> {
        val params = JsonObject().apply {
            addProperty("threadId", threadId.value)
            addProperty("itemsView", "full")
            addProperty("limit", 200)
            addProperty("sortDirection", "asc")
        }
        return gateway.request("thread/turns/list", params).thenApply { response ->
            val page = response.result ?: return@thenApply threadId
            store.replaceThreadHistory(
                threadId,
                ThreadSnapshotImport.fromTurnsPage(threadId, page, gateway.status().epoch),
            )
            threadId
        }
    }

    fun resume(threadId: ThreadId): CompletableFuture<WireEnvelope.Response> {
        val params = JsonObject().apply { addProperty("threadId", threadId.value) }
        return gateway.request("thread/resume", params)
    }

    fun fork(threadId: ThreadId): CompletableFuture<WireEnvelope.Response> {
        val params = JsonObject().apply { addProperty("threadId", threadId.value) }
        return gateway.request("thread/fork", params)
    }

    fun mergeSnapshot(envelope: SnapshotEnvelope) {
        store.applySnapshot(envelope)
    }

    fun state(): NormalizedServerState = store.snapshot()

    fun close() {
        stopEventPump()
        pump.shutdownNow()
    }
}
