package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptScrollContractTest {
    @Test
    fun `dragging defers apply and retains latest pending model`() {
        val state = TranscriptScrollState()
        state.onScroll(value = 300, extent = 100, maximum = 1_000, adjusting = true, anchor = null)

        state.defer(listOf(block("old")))
        state.defer(listOf(block("latest")))

        assertTrue(state.mode is TranscriptScrollMode.Dragging)
        assertEquals("latest", state.consumePending()?.single()?.id)
    }

    @Test
    fun `manual reading never changes to follow live on append`() {
        val state = TranscriptScrollState()
        val anchor = TranscriptViewportWindow.Anchor("block-10", 5)
        state.onScroll(value = 400, extent = 100, maximum = 1_000, adjusting = false, anchor = anchor)

        state.onContentAppended()

        assertEquals(TranscriptScrollMode.Reading(anchor), state.mode)
        assertFalse(state.shouldFollowLive())
    }

    @Test
    fun `near bottom remains follow live after append`() {
        val state = TranscriptScrollState()
        state.onScroll(value = 910, extent = 100, maximum = 1_000, adjusting = false, anchor = null)

        state.onContentAppended()

        assertTrue(state.shouldFollowLive())
    }

    private fun block(id: String) = TranscriptBlock.Html(
        fragment = id,
        id = id,
        revision = TranscriptBlockRevision(1),
    )
}
