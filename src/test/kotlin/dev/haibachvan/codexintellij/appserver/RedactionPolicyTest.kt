package dev.haibachvan.codexintellij.appserver

import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RedactionPolicyTest {
    private val policy = RedactionPolicy()
    private val corpusPath =
        Path.of("src/test/resources/fixtures/security/diagnostic-secret-corpus.json")

    @Test
    fun `redacts secret corpus before ring buffer storage`() {
        val corpus = JsonParser.parseString(corpusPath.readText()).asJsonArray
        val buffer = DiagnosticRingBuffer()
        corpus.forEach { el ->
            val obj = el.asJsonObject
            val raw = StructuredDiagnosticEvent(
                epoch = ProcessEpoch(1),
                code = obj.get("code").asString,
                severity = StructuredDiagnosticEvent.Severity.WARN,
                message = obj.get("message").asString,
                fields = obj.getAsJsonObject("fields").entrySet().associate { it.key to it.value.asString },
            )
            assertTrue(policy.containsSecret(raw.message) || raw.fields.values.any { policy.containsSecret(it) })
            val redacted = policy.redact(raw)
            assertTrue(redacted.redacted)
            assertFalse(policy.containsSecret(redacted.message))
            redacted.fields.values.forEach { value ->
                assertFalse(policy.containsSecret(value), "leaked field value: $value")
            }
            buffer.append(redacted)
        }
        val bundle = buffer.bundle()
        val text = bundle.bytes.toString(Charsets.UTF_8)
        assertFalse(text.contains("sk-"))
        assertFalse(text.contains("Bearer "))
        assertFalse(text.contains("password="))
        assertTrue(bundle.eventCount == corpus.size())
        assertTrue(bundle.sha256.length == 64)
    }

    @Test
    fun `ring buffer rejects unredacted events`() {
        val buffer = DiagnosticRingBuffer()
        assertThrows<IllegalArgumentException> {
            buffer.append(
                StructuredDiagnosticEvent(
                    epoch = null,
                    code = "x",
                    severity = StructuredDiagnosticEvent.Severity.INFO,
                    message = "plain",
                    redacted = false,
                ),
            )
        }
    }
}
