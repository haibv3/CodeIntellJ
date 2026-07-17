package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ServerRequestKey

enum class ApprovalOutcomeStatus {
    Pending,
    Chosen,
    Sending,
    Sent,
    Resolved,
    OutcomeUnknown,
}

data class ApprovalDecision(
    val decision: String,
    val amendments: JsonObject? = null,
)

data class ApprovalRequest(
    val key: ServerRequestKey,
    val fingerprint: String,
    val method: String,
    val payload: JsonObject,
    val status: ApprovalOutcomeStatus = ApprovalOutcomeStatus.Pending,
    val chosen: ApprovalDecision? = null,
    val resolvedOutcome: String? = null,
)
