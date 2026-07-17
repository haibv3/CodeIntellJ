package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FollowUpQueueActorTest {
    @Test
    fun `draft dispatch ack consume happy path`() {
        val actor = FollowUpQueueActor()
        val entries = actor.handle(FollowUpQueueActor.Command.Enqueue("hello", "e1"))
        assertEquals(FollowUpQueueActor.Status.Draft, entries[0].status)
        actor.handle(FollowUpQueueActor.Command.Dispatch("e1"))
        actor.handle(FollowUpQueueActor.Command.Ack("e1"))
        val consumed = actor.handle(FollowUpQueueActor.Command.Consume("e1"))
        assertEquals(FollowUpQueueActor.Status.Consumed, consumed[0].status)
    }

    @Test
    fun `ambiguous disconnect marks OutcomeUnknown and never auto replays`() {
        val actor = FollowUpQueueActor()
        actor.handle(FollowUpQueueActor.Command.Enqueue("pay", "e1"))
        actor.handle(FollowUpQueueActor.Command.Dispatch("e1"))
        actor.handle(FollowUpQueueActor.Command.MarkOutcomeUnknown("e1"))
        assertEquals(FollowUpQueueActor.Status.OutcomeUnknown, actor.entries()[0].status)
        assertThrows<IllegalArgumentException> {
            actor.handle(FollowUpQueueActor.Command.Dispatch("e1"))
        }
        // Explicit reconcile then dispatch/retry is required; never auto-replay.
        actor.handle(FollowUpQueueActor.Command.Reconcile("e1", toDraft = true))
        val retried = actor.handle(FollowUpQueueActor.Command.Dispatch("e1"))
        assertEquals(FollowUpQueueActor.Status.Dispatching, retried[0].status)
    }

    @Test
    fun `only draft is editable`() {
        val actor = FollowUpQueueActor()
        actor.handle(FollowUpQueueActor.Command.Enqueue("x", "e1"))
        actor.handle(FollowUpQueueActor.Command.Dispatch("e1"))
        assertThrows<IllegalArgumentException> {
            actor.handle(FollowUpQueueActor.Command.Edit("e1", "y"))
        }
        assertTrue(actor.nextDispatchable() == null)
    }
}
