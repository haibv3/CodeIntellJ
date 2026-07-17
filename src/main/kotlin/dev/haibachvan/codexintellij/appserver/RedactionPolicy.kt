package dev.haibachvan.codexintellij.appserver

import java.util.Locale
import java.util.regex.Pattern

/**
 * Ingress redaction for diagnostics. Raw stdout/stderr/payload content must never reach storage.
 */
class RedactionPolicy {
    fun redact(event: StructuredDiagnosticEvent): StructuredDiagnosticEvent {
        if (event.redacted) {
            return event
        }
        return event.copy(
            message = redactText(event.message),
            fields = event.fields.mapValues { (_, v) -> redactText(v) },
            redacted = true,
        )
    }

    fun redactText(input: String): String {
        var out = input
        for (pattern in SECRET_PATTERNS) {
            out = pattern.matcher(out).replaceAll(REDACTED)
        }
        for (token in FORBIDDEN_LITERALS) {
            if (out.contains(token)) {
                out = out.replace(token, REDACTED)
            }
        }
        return out
    }

    fun containsSecret(input: String): Boolean {
        if (SECRET_PATTERNS.any { it.matcher(input).find() }) {
            return true
        }
        val upper = input.uppercase(Locale.ROOT)
        return FORBIDDEN_LITERALS.any { upper.contains(it.uppercase(Locale.ROOT)) }
    }

    companion object {
        const val REDACTED: String = "[REDACTED]"

        private val FORBIDDEN_LITERALS = listOf(
            "sk-secret",
            "OPENAI_API_KEY",
            "Authorization: Bearer",
        )

        private val SECRET_PATTERNS: List<Pattern> = listOf(
            Pattern.compile("""(?i)(api[_-]?key|token|secret|password|authorization)\s*[:=]\s*['\"]?([^\s'\"]+)"""),
            Pattern.compile("""(?i)bearer\s+[a-z0-9._\-]+=*"""),
            Pattern.compile("""sk-[A-Za-z0-9]{10,}"""),
            Pattern.compile("""(?i)(https?://)([^:@\s]+):([^@\s]+)@"""),
        )
    }
}
