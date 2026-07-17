package dev.haibachvan.codexintellij.appserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonlFramerTest {
    private val epoch = ProcessEpoch(1)

    @Test
    fun `splits across arbitrary byte chunks including CRLF and multiple lines`() {
        val framer = JsonlFramer()
        val payload = "{\"a\":1}\r\n{\"b\":2}\n{\"c\":3}\n"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val frames = ArrayList<String>()
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + 3).coerceAtMost(bytes.size)
            val result = framer.accept(epoch, bytes.copyOfRange(offset, end))
            frames += result.frames.map { it.line }
            offset = end
        }
        assertEquals(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}"""), frames)
    }

    @Test
    fun `oversized record discards until newline then recovers`() {
        val framer = JsonlFramer(maxRecordBytes = 16)
        val huge = "x".repeat(40)
        val first = framer.accept(epoch, "$huge\n{\"ok\":true}\n".toByteArray())
        assertEquals(1, first.diagnostics.size)
        assertEquals("oversize_record", first.diagnostics[0].code)
        assertEquals(listOf("""{"ok":true}"""), first.frames.map { it.line })
        assertEquals(JsonlFramer.State.Normal, framer.state())
    }

    @Test
    fun `epoch reset clears partial buffer`() {
        val framer = JsonlFramer()
        framer.accept(epoch, "{\"partial".toByteArray())
        val next = ProcessEpoch(2)
        val result = framer.accept(next, "{\"ready\":true}\n".toByteArray())
        assertEquals(listOf("""{"ready":true}"""), result.frames.map { it.line })
    }

    @Test
    fun `finish never emits partial JSON and reports truncated frame`() {
        val framer = JsonlFramer()
        framer.accept(epoch, "{\"partial\":".toByteArray())
        val finished = framer.finish(epoch)
        assertTrue(finished.frames.isEmpty())
        assertEquals(listOf("truncated_frame"), finished.diagnostics.map { it.code })
    }

    @Test
    fun `utf8 multi-byte character split across chunks still frames`() {
        val framer = JsonlFramer()
        val line = "{\"msg\":\"café\"}\n".toByteArray(Charsets.UTF_8)
        val mid = 12
        val a = framer.accept(epoch, line.copyOfRange(0, mid))
        val b = framer.accept(epoch, line.copyOfRange(mid, line.size))
        assertTrue(a.frames.isEmpty())
        assertEquals(1, b.frames.size)
        assertTrue(b.frames[0].line.contains("café"))
    }
}
