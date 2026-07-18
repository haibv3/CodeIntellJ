package dev.haibachvan.codexintellij

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeAppServerPerformanceFixtureTest {
    @Test
    fun `fake server wrapper resolves repository root and returns version`() {
        val process = ProcessBuilder(FakeAppServerFixture.scriptPath().toString(), "--version")
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fake server --version timed out")
        val output = process.inputStream.bufferedReader().readText().trim()

        assertEquals(0, process.exitValue(), output)
        assertEquals("codex-cli 0.144.5", output)
    }

    @Test
    fun `performance scenario emits spec count and terminal event`() {
        val spec = Files.readString(FakeAppServerFixture.performanceSpecPath())
        val expectedCount = Regex(""""eventCount"\s*:\s*(\d+)""")
            .find(spec)?.groupValues?.get(1)?.toInt()
            ?: error("performance spec missing eventCount")
        val messageCount = Regex(""""messageCount"\s*:\s*(\d+)""")
            .find(spec)?.groupValues?.get(1)?.toInt()
            ?: error("performance spec missing messageCount")
        val process = ProcessBuilder(FakeAppServerFixture.scriptPath().toString(), "app-server")
            .redirectErrorStream(true)
            .start()
        val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        val reader = process.inputStream.bufferedReader()

        try {
            send(writer, request("1", "initialize"))
            assertResult(reader, "1")
            send(writer, notification("initialized"))
            send(writer, request("2", "thread/start"))
            assertResult(reader, "2")
            send(writer, request("3", "turn/start"))
            assertResult(reader, "3")

            val events = List(expectedCount) { readLine(reader) }
            assertEquals(expectedCount, events.size)
            assertTrue(events.all { !it.contains("\"id\"") && method(it) != null })
            assertEquals("thread/started", method(events.first()))
            assertEquals("turn/completed", method(events.last()))
            assertEquals(1, events.count { it.contains("\"itemId\": \"perf-progress\"") })
            assertEquals(
                messageCount - 1,
                events.count {
                    method(it) == "item/completed" && it.contains("\"itemId\": \"perf-result-")
                },
            )
        } finally {
            writer.close()
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private fun request(id: String, method: String): String =
        """{"jsonrpc":"2.0","id":"$id","method":"$method","params":{}}"""

    private fun notification(method: String): String =
        """{"jsonrpc":"2.0","method":"$method","params":{}}"""

    private fun send(writer: BufferedWriter, body: String) {
        writer.write(body)
        writer.newLine()
        writer.flush()
    }

    private fun assertResult(reader: BufferedReader, id: String) {
        val response = readLine(reader)
        assertTrue(Regex(""""id"\s*:\s*"$id"""").containsMatchIn(response), response)
        assertTrue(Regex(""""result"\s*:""").containsMatchIn(response), response)
    }

    private fun readLine(reader: BufferedReader): String =
        reader.readLine() ?: error("fake server closed stdout early")

    private fun method(line: String): String? =
        Regex(""""method"\s*:\s*"([^"]+)""").find(line)?.groupValues?.get(1)
}
