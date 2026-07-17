package dev.haibachvan.codexintellij.session

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.ProcessEpoch
import dev.haibachvan.codexintellij.appserver.ServerRequestKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApprovalStateMachineTest {
    @Test
    fun `epoch key and fingerprint prevent stale response after restart`() {
        val fsm = ApprovalStateMachine()
        val e1 = ServerRequestKey(ProcessEpoch(1), "1")
        fsm.onRequest(e1, "fp-a", "command/approval", JsonObject())
        fsm.choose(e1, "fp-a", ApprovalDecision("accept"))
        val e2 = ServerRequestKey(ProcessEpoch(2), "1")
        fsm.onRequest(e2, "fp-b", "command/approval", JsonObject())
        assertThrows<IllegalArgumentException> {
            fsm.choose(e2, "fp-a", ApprovalDecision("accept"))
        }
        assertEquals(ApprovalOutcomeStatus.Pending, fsm.get(e2)!!.status)
    }

    @Test
    fun `disconnect during sending becomes OutcomeUnknown and blocks resend`() {
        val fsm = ApprovalStateMachine()
        val key = ServerRequestKey(ProcessEpoch(1), "9")
        fsm.onRequest(key, "fp", "command/approval", JsonObject())
        fsm.choose(key, "fp", ApprovalDecision("accept"))
        fsm.sending(key, "fp")
        fsm.disconnect(ProcessEpoch(1))
        assertEquals(ApprovalOutcomeStatus.OutcomeUnknown, fsm.get(key)!!.status)
        assertThrows<IllegalArgumentException> { fsm.sent(key, "fp") }
    }

    @Test
    fun `happy path pending choose sending sent resolve`() {
        val fsm = ApprovalStateMachine()
        val key = ServerRequestKey(ProcessEpoch(1), "3")
        fsm.onRequest(key, "fp", "command/approval", JsonObject())
        fsm.choose(key, "fp", ApprovalDecision("accept"))
        fsm.sending(key, "fp")
        fsm.sent(key, "fp")
        val resolved = fsm.resolve(key, "fp", "accepted")
        assertEquals(ApprovalOutcomeStatus.Resolved, resolved.status)
        assertEquals("accepted", resolved.resolvedOutcome)
    }
}
