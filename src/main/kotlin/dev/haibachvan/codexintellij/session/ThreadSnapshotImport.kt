package dev.haibachvan.codexintellij.session

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch

/**
 * Imports thread/list and thread/resume payloads into [SnapshotEnvelope] facts.
 */
object ThreadSnapshotImport {
    fun fromListData(data: JsonArray, epoch: ProcessEpoch): SnapshotEnvelope {
        val threads = data.mapIndexedNotNull { index, el ->
            if (!el.isJsonObject) return@mapIndexedNotNull null
            val obj = el.asJsonObject
            // Prefer server timestamps so newest tasks sort first in the UI.
            val stamp = obj.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: obj.get("recencyAt")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong
                ?: obj.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: (data.size() - index).toLong()
            threadFact(obj, epoch, arrivalSeq = stamp)
        }
        return SnapshotEnvelope(epoch = epoch, requestWatermark = 0L, threads = threads)
    }

    /**
     * Imports a `thread/resume` result. Prefers `initialTurnsPage.data` when
     * `thread.turns` is empty or has not-loaded items (paginated history mode).
     */
    fun fromResumeResult(result: JsonObject, epoch: ProcessEpoch): SnapshotEnvelope {
        val thread = result.getAsJsonObject("thread") ?: result
        val pageTurns = result.getAsJsonObject("initialTurnsPage")?.getAsJsonArray("data")
        val embedded = thread.getAsJsonArray("turns")
        val usePage = !hasLoadedItems(embedded) && pageTurns != null && pageTurns.size() > 0
        val root = if (usePage) {
            thread.deepCopy().also { it.add("turns", pageTurns) }
        } else {
            thread
        }
        return fromThread(root, epoch)
    }

    /** Imports turns from a `thread/turns/list` page into an existing thread id. */
    fun fromTurnsPage(threadId: ThreadId, page: JsonObject, epoch: ProcessEpoch): SnapshotEnvelope {
        val data = page.getAsJsonArray("data") ?: JsonArray()
        val synthetic = JsonObject().apply {
            addProperty("id", threadId.value)
            add("turns", data)
        }
        return fromThread(synthetic, epoch)
    }

    fun fromThread(thread: JsonObject, epoch: ProcessEpoch): SnapshotEnvelope {
        // Accept either a bare Thread object or a resume/read result wrapper { thread: {...} }.
        val root = thread.getAsJsonObject("thread")?.takeIf { it.has("id") || it.has("turns") }
            ?: thread
        val threadId = root.string("id")?.let(::ThreadId) ?: return SnapshotEnvelope(epoch, 0L)
        val threadFact = threadFact(root, epoch, arrivalSeq = 0L) ?: return SnapshotEnvelope(epoch, 0L)
        val turnsJson = root.getAsJsonArray("turns") ?: JsonArray()
        val turns = mutableListOf<TurnFact>()
        val items = mutableListOf<ItemFact>()
        var seq = 1L
        turnsJson.forEach { turnEl ->
            if (!turnEl.isJsonObject) return@forEach
            val turnObj = turnEl.asJsonObject
            val turnId = turnObj.string("id")?.let(::TurnId) ?: return@forEach
            val status = turnStatus(turnObj.string("status"))
            turns += TurnFact(
                id = turnId,
                threadId = threadId,
                status = status,
                terminalRank = turnRank(status),
                epoch = epoch,
                arrivalSeq = seq++,
            )
            val turnItems = turnObj.getAsJsonArray("items") ?: JsonArray()
            turnItems.forEach { itemEl ->
                if (!itemEl.isJsonObject) return@forEach
                parseItem(itemEl.asJsonObject, threadId, turnId, epoch, seq++)?.let { items += it }
            }
        }
        val agents = items.filterIsInstance<ItemFact.Subagent>().map { it.fact }
        return SnapshotEnvelope(
            epoch = epoch,
            requestWatermark = 0L,
            threads = listOf(threadFact),
            turns = turns,
            items = items,
            agents = agents,
        )
    }

    private fun hasLoadedItems(turns: JsonArray?): Boolean {
        if (turns == null || turns.size() == 0) return false
        return turns.any { el ->
            if (!el.isJsonObject) return@any false
            val turn = el.asJsonObject
            val view = turn.string("itemsView")
            if (view.equals("notLoaded", ignoreCase = true)) return@any false
            (turn.getAsJsonArray("items")?.size() ?: 0) > 0
        }
    }

    private fun threadFact(thread: JsonObject, epoch: ProcessEpoch, arrivalSeq: Long): ThreadFact? {
        val id = thread.string("id")?.let(::ThreadId) ?: return null
        val title = thread.string("name")?.takeIf { it.isNotBlank() }
            ?: thread.string("preview")?.takeIf { it.isNotBlank() }
        val archived = thread.string("status").equals("archived", ignoreCase = true)
        return ThreadFact(
            id = id,
            status = if (archived) ThreadStatus.ARCHIVED else ThreadStatus.ACTIVE,
            title = title,
            epoch = epoch,
            arrivalSeq = arrivalSeq,
        )
    }

    private fun parseItem(
        item: JsonObject,
        threadId: ThreadId,
        turnId: TurnId,
        epoch: ProcessEpoch,
        arrivalSeq: Long,
    ): ItemFact? {
        val itemId = item.string("id")?.let(::ItemId) ?: return null
        val type = item.string("type") ?: return null
        val status = ItemStatus.COMPLETED
        val rank = TerminalRank.COMPLETED
        return when (type) {
            "userMessage", "user" -> ItemFact.UserMessage(
                itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                text = extractUserText(item),
            )
            "agentMessage", "agent", "assistant" -> ItemFact.AgentMessage(
                itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                text = item.string("text")?.takeIf { it.isNotBlank() } ?: extractUserText(item),
                textSealed = item.string("text")?.isNotBlank() == true,
            )
            "plan" -> ItemFact.AgentMessage(
                itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                text = item.string("text").orEmpty(),
                textSealed = item.string("text")?.isNotBlank() == true,
            )
            "reasoning" -> ItemFact.Reasoning(
                itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                text = item.string("text")
                    ?: item.string("summary")
                    ?: extractReasoningSummary(item),
            )
            "commandExecution", "command" -> ItemFact.Command(
                itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                command = item.string("command").orEmpty(),
                output = item.string("aggregatedOutput")
                    ?: item.string("output")
                    ?: item.string("text").orEmpty(),
            )
            "fileChange", "patch" -> {
                val changes = parseFileChangeEntries(item, null)
                val path = changes.firstOrNull()?.path
                    ?: item.string("path")
                    ?: ""
                val diff = when {
                    changes.size == 1 -> changes[0].unifiedDiff
                    else -> item.string("diff")
                }
                ItemFact.Patch(
                    itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                    PatchFact(itemId, path, diff, status, changes),
                )
            }
            "subagent", "subAgentActivity", "collaboration", "collabAgentToolCall" -> {
                val agentPath = item.string("agentPath").orEmpty()
                val agentThreadId = item.string("agentThreadId")
                val kind = item.string("kind")
                val tool = item.string("tool")
                val prompt = item.string("prompt")
                val agentId = item.string("agentId")
                    ?: tool
                    ?: agentPath.substringAfterLast('/').ifBlank { null }
                    ?: agentThreadId
                    ?: itemId.value
                val summary = prompt?.take(160)
                    ?: kind?.let { k ->
                        buildString {
                            append(k)
                            if (agentPath.isNotBlank()) append(" · ").append(agentPath)
                        }
                    }
                    ?: item.string("text")
                ItemFact.Subagent(
                    itemId, threadId, turnId, status, rank, epoch, arrivalSeq,
                    AgentFact(
                        itemId = itemId,
                        agentId = agentId,
                        parentItemId = item.string("parentItemId")?.let(::ItemId),
                        status = status,
                        summary = summary,
                    ),
                )
            }
            else -> null
        }
    }

    private fun extractUserText(item: JsonObject): String {
        item.string("text")?.takeIf { it.isNotBlank() }?.let { return it }
        val content = item.get("content")
        if (content !is JsonArray) return ""
        return buildString {
            content.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val obj = el.asJsonObject
                obj.string("text")?.let { append(it) }
            }
        }
    }

    private fun extractReasoningSummary(item: JsonObject): String {
        fun joinTextArray(arr: JsonArray?): String {
            if (arr == null) return ""
            return buildString {
                arr.forEach { el ->
                    when {
                        el.isJsonPrimitive && el.asJsonPrimitive.isString -> {
                            if (isNotEmpty()) append("\n")
                            append(el.asString)
                        }
                        el.isJsonObject -> el.asJsonObject.string("text")?.let {
                            if (isNotEmpty()) append("\n")
                            append(it)
                        }
                    }
                }
            }
        }
        return joinTextArray(item.getAsJsonArray("summary"))
            .ifBlank { joinTextArray(item.getAsJsonArray("content")) }
    }

    private fun turnStatus(raw: String?): TurnStatus =
        when (raw?.lowercase()) {
            "completed", "complete", "done" -> TurnStatus.COMPLETED
            "failed", "error" -> TurnStatus.FAILED
            "interrupted", "cancelled", "canceled" -> TurnStatus.INTERRUPTED
            "active", "inprogress", "in_progress" -> TurnStatus.ACTIVE
            else -> TurnStatus.COMPLETED
        }

    private fun turnRank(status: TurnStatus): TerminalRank =
        when (status) {
            TurnStatus.ACTIVE -> TerminalRank.ACTIVE
            TurnStatus.INTERRUPTED -> TerminalRank.INTERRUPTED
            TurnStatus.FAILED -> TerminalRank.FAILED
            TurnStatus.COMPLETED -> TerminalRank.COMPLETED
            TurnStatus.UNKNOWN -> TerminalRank.NONE
        }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
