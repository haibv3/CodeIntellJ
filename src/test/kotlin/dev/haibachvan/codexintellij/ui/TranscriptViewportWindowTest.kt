package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptViewportWindowTest {
    @Test
    fun `2000 blocks materialize no more than 250 around viewport`() {
        val blocks = blocks(2_000)

        val slice = TranscriptViewportWindow.compute(blocks, 20_000, 800, { 40 })

        assertTrue(slice.blocks.size <= TranscriptViewportWindow.HARD_MAX_MATERIALIZED)
        assertEquals(TranscriptViewportWindow.TARGET_MATERIALIZED, slice.blocks.size)
        assertTrue(slice.startIndex > 0)
        assertTrue(slice.endExclusive < blocks.size)
    }

    @Test
    fun `top materialized and bottom heights equal total estimated height`() {
        val blocks = blocks(2_000)
        val slice = TranscriptViewportWindow.compute(blocks, 40_000, 800, { 40 })
        val materializedHeight = slice.blocks.sumOf { 40 }

        assertEquals(
            slice.totalHeight,
            slice.topSpacerHeight + materializedHeight + slice.bottomSpacerHeight,
        )
    }

    @Test
    fun `prepend and updates above anchor preserve visible content`() {
        val original = blocks(300)
        val slice = TranscriptViewportWindow.compute(original, 4_050, 600, { 40 })
        val anchor = requireNotNull(slice.anchor)
        val prepended = listOf(block("prepended")) + original

        val corrected = TranscriptViewportWindow.scrollTopForAnchor(prepended, anchor) { 40 }

        assertEquals(4_090, corrected)
        assertEquals(anchor.blockId, prepended[corrected / 40].id)
    }

    @Test
    fun `follow live computes a tail window`() {
        val blocks = blocks(2_000)
        val totalHeight = blocks.size * 40

        val slice = TranscriptViewportWindow.compute(blocks, totalHeight - 800, 800, { 40 })

        assertEquals(blocks.size, slice.endExclusive)
        assertEquals(0, slice.bottomSpacerHeight)
    }

    @Test
    fun `height cache is bounded and invalidates by width bucket or revision`() {
        val cache = TranscriptHeightCache(capacity = 2)
        val revision = TranscriptBlockRevision(1)
        val first = TranscriptHeightCache.Key("a", revision, 320)
        cache.put(first, 40)
        cache.put(TranscriptHeightCache.Key("b", revision, 320), 50)
        assertEquals(40, cache.get(first))

        cache.put(TranscriptHeightCache.Key("c", revision, 320), 60)

        assertEquals(2, cache.size)
        assertNull(cache.get(TranscriptHeightCache.Key("b", revision, 320)))
        assertNull(cache.get(TranscriptHeightCache.Key("a", revision, 640)))
        assertNull(cache.get(TranscriptHeightCache.Key("a", TranscriptBlockRevision(2), 320)))
    }

    @Test
    fun `height cache hot path accepts key fields and preserves invalidation`() {
        val cache = TranscriptHeightCache(capacity = 2)
        val revision = TranscriptBlockRevision(1)

        cache.put("a", revision, 320, 40)

        assertEquals(40, cache.get("a", revision, 320))
        assertNull(cache.get("a", revision, 640))
        assertNull(cache.get("a", TranscriptBlockRevision(2), 320))
    }

    @Test
    fun `large transcript keeps an interactive viewport target after first paint`() {
        val slice = TranscriptViewportWindow.compute(blocks(2_000), 20_000, 800, { 40 })

        assertEquals(40, slice.blocks.size)
        assertEquals(40, TranscriptViewportWindow.TARGET_MATERIALIZED)
    }

    @Test
    fun `sanitized html cache is bounded by semantic revision`() {
        val cache = TranscriptHtmlArtifactCache(capacity = 2)
        val first = block("a")

        val firstDocument = cache.document(first)
        assertEquals(firstDocument, cache.document(first))
        cache.document(block("b"))
        cache.document(block("c"))

        assertEquals(2, cache.size)
        assertTrue(cache.document(first).contains("a"))
        assertEquals(2, cache.size)
    }

    private fun blocks(count: Int) = (0 until count).map { block("block-$it") }

    private fun block(id: String) = TranscriptBlock.Html(
        fragment = id,
        id = id,
        revision = TranscriptBlockRevision(1),
    )
}
