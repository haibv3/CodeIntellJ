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
        val key = CoalesceKey(epoch, "t1", "turn1", "item1", SequencedEventKind.TEXT_DELTA)
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

    private fun response(id: String) =
        WireEnvelope.Response(id, JsonObject(), null)
}
