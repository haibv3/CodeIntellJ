package dev.haibachvan.codexintellij.commands

import com.google.gson.JsonObject
import dev.haibachvan.codexintellij.appserver.RedactedBundle
import dev.haibachvan.codexintellij.appserver.WireEnvelope
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FeedbackCommandHandlerTest {
    @Test
    fun `upload requires explicit consent and preserves hash`() {
        val bundle = RedactedBundle("safe".toByteArray(), "b".repeat(64), 1)
        val handler = FeedbackCommandHandler(
            FeedbackRpc { _, params ->
                assertTrue(params!!.get("sha256").asString == bundle.sha256)
                CompletableFuture.completedFuture(WireEnvelope.Response("1", JsonObject(), null))
            },
        )
        assertThrows<IllegalArgumentException> { handler.upload(bundle, consentGranted = false).get() }
        assertTrue(handler.upload(bundle, consentGranted = true).get())
    }
}
