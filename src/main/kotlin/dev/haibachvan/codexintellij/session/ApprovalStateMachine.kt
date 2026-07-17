package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.ServerRequestKey

/**
 * Epoch-keyed approval FSM. OutcomeUnknown never auto-retries.
 */
class ApprovalStateMachine {
    private val requests = LinkedHashMap<ServerRequestKey, ApprovalRequest>()
    private val lock = Any()

    fun onRequest(key: ServerRequestKey, fingerprint: String, method: String, payload: JsonObject): ApprovalRequest =
        synchronized(lock) {
            val req = ApprovalRequest(key, fingerprint, method, payload)
            requests[key] = req
            req
        }

    fun choose(key: ServerRequestKey, fingerprint: String, decision: ApprovalDecision): ApprovalRequest =
        synchronized(lock) {
            val current = requireRequest(key, fingerprint)
            require(current.status == ApprovalOutcomeStatus.Pending) { "choose requires Pending" }
            val next = current.copy(status = ApprovalOutcomeStatus.Chosen, chosen = decision)
            requests[key] = next
            next
        }

    fun sending(key: ServerRequestKey, fingerprint: String): ApprovalRequest =
        transition(key, fingerprint, from = setOf(ApprovalOutcomeStatus.Chosen), to = ApprovalOutcomeStatus.Sending)

    fun sent(key: ServerRequestKey, fingerprint: String): ApprovalRequest =
        transition(key, fingerprint, from = setOf(ApprovalOutcomeStatus.Sending), to = ApprovalOutcomeStatus.Sent)

    fun resolve(key: ServerRequestKey, fingerprint: String, outcome: String): ApprovalRequest =
        synchronized(lock) {
            val current = requireRequest(key, fingerprint)
            require(
                current.status in setOf(
                    ApprovalOutcomeStatus.Sent,
                    ApprovalOutcomeStatus.Chosen,
                    ApprovalOutcomeStatus.Sending,
                ),
            ) { "resolve illegal from ${current.status}" }
            val next = current.copy(status = ApprovalOutcomeStatus.Resolved, resolvedOutcome = outcome)
            requests[key] = next
            next
        }

    fun disconnect(epoch: ProcessEpoch) {
        synchronized(lock) {
            requests.replaceAll { key, req ->
                if (key.epoch != epoch) {
                    req
                } else if (req.status == ApprovalOutcomeStatus.Sending || req.status == ApprovalOutcomeStatus.Sent) {
                    req.copy(status = ApprovalOutcomeStatus.OutcomeUnknown)
                } else {
                    req
                }
            }
        }
    }

    fun get(key: ServerRequestKey): ApprovalRequest? = synchronized(lock) { requests[key] }

    fun pending(): List<ApprovalRequest> =
        synchronized(lock) { requests.values.filter { it.status == ApprovalOutcomeStatus.Pending } }

    private fun transition(
        key: ServerRequestKey,
        fingerprint: String,
        from: Set<ApprovalOutcomeStatus>,
        to: ApprovalOutcomeStatus,
    ): ApprovalRequest =
        synchronized(lock) {
            val current = requireRequest(key, fingerprint)
            require(current.status in from) { "illegal transition ${current.status} -> $to" }
            val next = current.copy(status = to)
            requests[key] = next
            next
        }

    private fun requireRequest(key: ServerRequestKey, fingerprint: String): ApprovalRequest {
        val current = requests[key] ?: error("Unknown approval key: $key")
        require(current.fingerprint == fingerprint) { "Fingerprint mismatch for $key" }
        return current
    }
}
