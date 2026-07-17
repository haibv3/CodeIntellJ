package dev.haibachvan.codexintellij.ui

import java.util.UUID

/**
 * Panel-owned serialized follow-up queue FSM.
 * OutcomeUnknown is never auto-dispatched.
 */
class FollowUpQueueActor {
    enum class Status {
        Draft,
        Dispatching,
        Acknowledged,
        Consumed,
        OutcomeUnknown,
    }

    data class Entry(
        val id: String,
        val text: String,
        val status: Status,
    )

    sealed class Command {
        data class Enqueue(val text: String, val id: String = UUID.randomUUID().toString()) : Command()
        data class Edit(val id: String, val text: String) : Command()
        data class Move(val id: String, val toIndex: Int) : Command()
        data class Delete(val id: String) : Command()
        data class Dispatch(val id: String) : Command()
        data class Ack(val id: String) : Command()
        data class Consume(val id: String) : Command()
        data class MarkOutcomeUnknown(val id: String) : Command()
        data class Reconcile(val id: String, val toDraft: Boolean) : Command()
        data class Retry(val id: String) : Command()
    }

    private val entries = ArrayList<Entry>()
    private val lock = Any()

    fun entries(): List<Entry> = synchronized(lock) { entries.toList() }

    fun handle(command: Command): List<Entry> =
        synchronized(lock) {
            when (command) {
                is Command.Enqueue -> {
                    entries += Entry(command.id, command.text, Status.Draft)
                }
                is Command.Edit -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.Draft) { "Only Draft entries are editable" }
                    entries[idx] = entry.copy(text = command.text)
                }
                is Command.Move -> {
                    val idx = indexOf(command.id)
                    val entry = entries.removeAt(idx)
                    val target = command.toIndex.coerceIn(0, entries.size)
                    entries.add(target, entry)
                }
                is Command.Delete -> {
                    val idx = indexOf(command.id)
                    require(entries[idx].status == Status.Draft || entries[idx].status == Status.OutcomeUnknown) {
                        "Cannot delete in-flight entry"
                    }
                    entries.removeAt(idx)
                }
                is Command.Dispatch -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.Draft) { "Only Draft can dispatch" }
                    entries[idx] = entry.copy(status = Status.Dispatching)
                }
                is Command.Ack -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.Dispatching) { "Ack requires Dispatching" }
                    entries[idx] = entry.copy(status = Status.Acknowledged)
                }
                is Command.Consume -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.Acknowledged) { "Consume requires Acknowledged" }
                    entries[idx] = entry.copy(status = Status.Consumed)
                }
                is Command.MarkOutcomeUnknown -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.Dispatching || entry.status == Status.Acknowledged) {
                        "OutcomeUnknown only from Dispatching/Acknowledged"
                    }
                    entries[idx] = entry.copy(status = Status.OutcomeUnknown)
                }
                is Command.Reconcile -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.OutcomeUnknown) { "Reconcile requires OutcomeUnknown" }
                    entries[idx] = entry.copy(
                        status = if (command.toDraft) Status.Draft else Status.Consumed,
                    )
                }
                is Command.Retry -> {
                    val idx = indexOf(command.id)
                    val entry = entries[idx]
                    require(entry.status == Status.Draft) {
                        "Retry requires prior Reconcile to Draft; OutcomeUnknown is never auto-dispatched"
                    }
                    entries[idx] = entry.copy(status = Status.Dispatching)
                }
            }
            entries.toList()
        }

    fun nextDispatchable(): Entry? =
        synchronized(lock) { entries.firstOrNull { it.status == Status.Draft } }

    private fun indexOf(id: String): Int {
        val idx = entries.indexOfFirst { it.id == id }
        require(idx >= 0) { "Unknown queue entry: $id" }
        return idx
    }
}
