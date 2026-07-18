package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolSequencerTest {
    private val epoch = ProcessEpoch(3)

    @Test
    fun `assigns monotonic arrival sequence and request watermark`() {
        val sequencer = ProtocolSequencer()
        val wm1 = sequencer.nextRequestWatermark()
        val a = sequencer.enqueue(epoch, SequencedEventKind.CONTROL, note("a"), wm1)
        val wm2 = sequencer.nextRequestWatermark()
        val b = sequencer.enqueue(epoch, SequencedEventKind.RESPONSE, response("1"), wm2)
        assertTrue(a.arrivalSeq < b.arrivalSeq)
        assertEquals(wm1, a.requestWatermark)
        assertEquals(wm2, b.requestWatermark)
    }

    @Test
    fun `coalesces keyed deltas but never drops control or response`() {
        val sequencer = ProtocolSequencer()
        val key = CoalesceKey(epoch, "t1", "turn1", "item1", SequencedEventKind.TEXT_DELTA, "d1")
        sequencer.enqueue(epoch, SequencedEventKind.TEXT_DELTA, note("d1"), coalesceKey = key)
        sequencer.enqueue(epoch, SequencedEventKind.TEXT_DELTA, note("d2"), coalesceKey = key)
        sequencer.enqueue(epoch, SequencedEventKind.CONTROL, note("control"))
        sequencer.enqueue(epoch, SequencedEventKind.RESPONSE, response("9"))
        sequencer.enqueue(epoch, SequencedEventKind.TEXT_DELTA, note("d3"), coalesceKey = key)

        val drained = sequencer.drain()
        val methods = drained.map {
            when (val p = it.payload) {
                is WireEnvelope.Notification -> p.method
                is WireEnvelope.Response -> "response:${p.id}"
                else -> p::class.simpleName
            }
        }
        assertEquals(listOf("control", "response:9", "d3"), methods)
        assertEquals(3, drained.size)
    }

    @Test
    fun `coalesced text deltas concatenate chunks instead of dropping them`() {
        val sequencer = ProtocolSequencer()
        val key = CoalesceKey(epoch, "t1", "turn1", "item1", SequencedEventKind.TEXT_DELTA, "item/agentMessage/delta")
        sequencer.enqueue(
            epoch,
            SequencedEventKind.TEXT_DELTA,
            deltaNote("item/agentMessage/delta", "Hel"),
            coalesceKey = key,
        )
        sequencer.enqueue(
            epoch,
            SequencedEventKind.TEXT_DELTA,
            deltaNote("item/agentMessage/delta", "lo "),
            coalesceKey = key,
        )
        sequencer.enqueue(
            epoch,
            SequencedEventKind.TEXT_DELTA,
            deltaNote("item/agentMessage/delta", "world"),
            coalesceKey = key,
        )

        val drained = sequencer.drain()
        assertEquals(1, drained.size)
        val params = (drained.single().payload as WireEnvelope.Notification).params!!
        assertEquals("Hello world", params.get("delta").asString)
    }

    @Test
    fun `reasoning summary and raw text deltas do not coalesce together`() {
        val sequencer = ProtocolSequencer()
        val summaryKey = CoalesceKey(
            epoch, "t1", "turn1", "r1", SequencedEventKind.TEXT_DELTA,
            method = "item/reasoning/summaryTextDelta",
            streamIndex = 0L,
        )
        val rawKey = CoalesceKey(
            epoch, "t1", "turn1", "r1", SequencedEventKind.TEXT_DELTA,
            method = "item/reasoning/textDelta",
            streamIndex = 0L,
        )
        sequencer.enqueue(
            epoch,
            SequencedEventKind.TEXT_DELTA,
            WireEnvelope.Notification(
                "item/reasoning/summaryTextDelta",
                JsonObject().apply {
                    addProperty("itemId", "r1")
                    addProperty("delta", "summary")
                    addProperty("summaryIndex", 0)
                },
            ),
            coalesceKey = summaryKey,
        )
        sequencer.enqueue(
            epoch,
            SequencedEventKind.TEXT_DELTA,
            WireEnvelope.Notification(
                "item/reasoning/textDelta",
                JsonObject().apply {
                    addProperty("itemId", "r1")
                    addProperty("delta", "raw")
                    addProperty("contentIndex", 0)
                },
            ),
            coalesceKey = rawKey,
        )

        val drained = sequencer.drain()
        assertEquals(2, drained.size)
        val texts = drained.map {
            (it.payload as WireEnvelope.Notification).params!!.get("delta").asString
        }
        assertEquals(listOf("summary", "raw"), texts)
    }

    @Test
    fun `clearEpoch removes only matching epoch events`() {
        val sequencer = ProtocolSequencer()
        val e1 = ProcessEpoch(1)
        val e2 = ProcessEpoch(2)
        sequencer.enqueue(e1, SequencedEventKind.CONTROL, note("old"))
        sequencer.enqueue(e2, SequencedEventKind.CONTROL, note("new"))
        sequencer.clearEpoch(e1)
        val drained = sequencer.drain()
        assertEquals(1, drained.size)
        assertEquals(e2, drained[0].epoch)
    }

    private fun note(method: String) =
        WireEnvelope.Notification(method, JsonObject())

    private fun deltaNote(method: String, delta: String) =
        WireEnvelope.Notification(
            method,
            JsonObject().apply {
                addProperty("threadId", "t1")
                addProperty("turnId", "turn1")
                addProperty("itemId", "item1")
                addProperty("delta", delta)
            },
        )

    private fun response(id: String) =
        WireEnvelope.Response(id, JsonObject(), null)
}
