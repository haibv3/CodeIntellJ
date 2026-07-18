package dev.haibachvan.codexintellij.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptBlockReconcilerTest {
    @Test
    fun `append keeps existing blocks and inserts one`() {
        val previous = blocks("a", "b", "c")
        val next = previous + block("d")

        val plan = TranscriptBlockReconciler.plan(previous, next)

        assertEquals(
            listOf(
                TranscriptBlockReconciler.Change.KEEP,
                TranscriptBlockReconciler.Change.KEEP,
                TranscriptBlockReconciler.Change.KEEP,
                TranscriptBlockReconciler.Change.INSERT,
            ),
            plan.entries.map { it.change },
        )
        assertTrue(plan.removals.isEmpty())
    }

    @Test
    fun `single middle revision updates one and keeps tail`() {
        val previous = blocks("a", "b", "c")
        val next = listOf(previous[0], block("b", revision = 2), previous[2])

        val plan = TranscriptBlockReconciler.plan(previous, next)

        assertEquals(1, plan.entries.count { it.change == TranscriptBlockReconciler.Change.UPDATE })
        assertEquals(2, plan.entries.count { it.change == TranscriptBlockReconciler.Change.KEEP })
        assertTrue(plan.removals.isEmpty())
    }

    @Test
    fun `move and remove preserve all remaining identities`() {
        val previous = blocks("a", "b", "c", "d")
        val next = listOf(previous[2], previous[0], previous[3])

        val plan = TranscriptBlockReconciler.plan(previous, next)

        assertEquals(listOf("b"), plan.removals.map { it.block.id })
        assertEquals(setOf("a", "c"), plan.entries.filter { it.moved }.mapTo(mutableSetOf()) { it.block.id })
        assertTrue(plan.entries.all { it.change == TranscriptBlockReconciler.Change.KEEP })
    }

    @Test
    fun `duplicate semantic id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TranscriptBlockReconciler.plan(emptyList(), listOf(block("same"), block("same")))
        }
    }

    @Test
    fun `operation planning remains linear for 2000 blocks`() {
        val previous = (0 until 2_000).map { block("block-$it") }
        val next = previous.toMutableList().also {
            it[1_000] = block("block-1000", revision = 2)
            it += block("block-2000")
        }

        val plan = TranscriptBlockReconciler.plan(previous, next)

        assertEquals(2_001, plan.entries.size)
        assertTrue(plan.entries.size + plan.removals.size <= previous.size + next.size)
    }

    private fun blocks(vararg ids: String) = ids.map(::block)

    private fun block(id: String, revision: Long = 1) = TranscriptBlock.Html(
        fragment = "<$id>",
        id = id,
        revision = TranscriptBlockRevision(revision),
    )
}
