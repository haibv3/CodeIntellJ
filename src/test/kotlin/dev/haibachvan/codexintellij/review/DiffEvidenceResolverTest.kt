package dev.haibachvan.codexintellij.review

import dev.haibachvan.codexintellij.session.ItemStatus
import dev.haibachvan.codexintellij.session.PatchFact
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

class DiffEvidenceResolverTest {
    @Test
    fun `precedence server explicit then document then warning`() {
        val resolver = DiffEvidenceResolver()
        val patch = ProjectedPatch(
            fact = PatchFact(dev.haibachvan.codexintellij.session.ItemId("p1"), "A.kt", "@@\n+x", ItemStatus.COMPLETED),
            provenance = "server",
            arrivalHint = 1,
        )
        val server = resolver.resolve(patch, baseline = null, serverBefore = "old".toByteArray(), serverAfter = "new".toByteArray())
        assertTrue(server is DiffEvidence.NativeBeforeAfter)
        assertEquals("server-explicit-before-after", (server as DiffEvidence.NativeBeforeAfter).evidenceLabel)

        val baseline = BaselineEntry("A.kt", BaselineSource.DOCUMENT, "doc".toByteArray(), "h", 1)
        val doc = resolver.resolve(patch, baseline, serverBefore = null, serverAfter = "new".toByteArray())
        assertEquals("pre-turn-unsaved-document", (doc as DiffEvidence.NativeBeforeAfter).evidenceLabel)

        val warn = resolver.resolve(patch, baseline = null, serverBefore = null, serverAfter = null)
        assertTrue(warn is DiffEvidence.WarningUnifiedDiff)
    }

    @Test
    fun `trace fixture documents selected precedence rules`() {
        val trace = Path.of("src/test/resources/fixtures/appserver/0.144.5/diff-source-precedence-trace.jsonl")
        assertTrue(Files.exists(trace))
        val text = trace.readText()
        assertTrue(text.contains("server-explicit"))
        assertTrue(text.contains("pre-turn-document"))
        assertTrue(text.contains("warning-unified"))
    }
}
