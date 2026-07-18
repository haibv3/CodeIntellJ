package dev.haibachvan.codexintellij.ui

import java.awt.Dimension
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TranscriptComponentHostReconcilerTest {
    private class TrackingPanel : JPanel() {
        var zOrderChanges = 0
        var zOrderLookups = 0

        override fun getComponentZOrder(component: java.awt.Component): Int {
            zOrderLookups += 1
            return super.getComponentZOrder(component)
        }

        override fun setComponentZOrder(component: java.awt.Component, index: Int) {
            zOrderChanges += 1
            super.setComponentZOrder(component, index)
        }
    }

    @Test
    fun `append keeps existing components attached and inserts only the new tail`() {
        val host = JPanel()
        val topSpacer = Box.createRigidArea(Dimension(0, 10))
        val bottomSpacer = Box.createRigidArea(Dimension(0, 20))
        val glue = Box.createVerticalGlue()
        val first = JLabel("first")
        val second = JLabel("second")
        val appended = JLabel("appended")
        listOf(topSpacer, first, second, bottomSpacer, glue).forEach(host::add)

        val detached = mutableListOf<java.awt.Component>()
        TranscriptComponentHostReconciler.apply(
            host = host,
            previous = listOf(first, second),
            next = listOf(first, second, appended),
            prefixCount = 1,
            onRemove = detached::add,
        )

        assertEquals(6, host.componentCount)
        assertSame(first, host.getComponent(1))
        assertSame(second, host.getComponent(2))
        assertSame(appended, host.getComponent(3))
        assertSame(bottomSpacer, host.getComponent(4))
        assertSame(glue, host.getComponent(5))
        assertEquals(emptyList<java.awt.Component>(), detached)
    }

    @Test
    fun `eviction removes and disposes a component exactly once`() {
        val host = JPanel()
        val topSpacer = Box.createRigidArea(Dimension(0, 10))
        val bottomSpacer = Box.createRigidArea(Dimension(0, 20))
        val glue = Box.createVerticalGlue()
        val evicted = JLabel("evicted")
        val kept = JLabel("kept")
        listOf(topSpacer, evicted, kept, bottomSpacer, glue).forEach(host::add)
        var disposeCount = 0

        TranscriptComponentHostReconciler.apply(
            host = host,
            previous = listOf(evicted, kept),
            next = listOf(kept),
            prefixCount = 1,
            onRemove = { component ->
                if (component === evicted) disposeCount += 1
            },
        )
        TranscriptComponentHostReconciler.apply(
            host = host,
            previous = listOf(kept),
            next = listOf(kept),
            prefixCount = 1,
            onRemove = { component ->
                if (component === evicted) disposeCount += 1
            },
        )

        assertEquals(1, disposeCount)
        assertEquals(-1, host.getComponentZOrder(evicted))
        assertSame(kept, host.getComponent(1))
        assertSame(bottomSpacer, host.getComponent(2))
        assertSame(glue, host.getComponent(3))
    }

    @Test
    fun `recyclable eviction can be rebound without detach or disposal`() {
        val host = JPanel()
        val topSpacer = Box.createRigidArea(Dimension(0, 10))
        val bottomSpacer = Box.createRigidArea(Dimension(0, 20))
        val glue = Box.createVerticalGlue()
        val leaving = JLabel("leaving")
        val kept = JLabel("kept")
        listOf(topSpacer, leaving, kept, bottomSpacer, glue).forEach(host::add)

        val recyclable = TranscriptComponentHostReconciler.collectReusableEvictions(
            previous = listOf("old" to leaving, "kept" to kept),
            nextIds = setOf("kept", "new"),
            canReuse = { it === leaving },
        )
        val rebound = recyclable.removeFirst().also { it.text = "new" }
        val disposed = mutableListOf<java.awt.Component>()
        TranscriptComponentHostReconciler.apply(
            host = host,
            previous = listOf(leaving, kept),
            next = listOf(kept, rebound),
            prefixCount = 1,
            onRemove = disposed::add,
        )

        assertSame(leaving, rebound)
        assertEquals(emptyList<java.awt.Component>(), disposed)
        assertSame(kept, host.getComponent(1))
        assertSame(leaving, host.getComponent(2))
    }

    @Test
    fun `recycled prefix rotation moves only the rebound component`() {
        val host = TrackingPanel()
        val topSpacer = Box.createRigidArea(Dimension(0, 10))
        val bottomSpacer = Box.createRigidArea(Dimension(0, 20))
        val glue = Box.createVerticalGlue()
        val leaving = JLabel("leaving")
        val first = JLabel("first")
        val second = JLabel("second")
        val third = JLabel("third")
        listOf(topSpacer, leaving, first, second, third, bottomSpacer, glue).forEach(host::add)

        TranscriptComponentHostReconciler.apply(
            host = host,
            previous = listOf(leaving, first, second, third),
            next = listOf(first, second, third, leaving),
            prefixCount = 1,
            onRemove = {},
        )

        assertEquals(1, host.zOrderChanges)
        org.junit.jupiter.api.Assertions.assertTrue(
            host.zOrderLookups <= 2,
            "Only the one actual move may perform z-order lookups; got ${host.zOrderLookups}",
        )
        assertSame(first, host.getComponent(1))
        assertSame(leaving, host.getComponent(4))
    }

    @Test
    fun `large replacement inserts new components before fixed suffix`() {
        val host = JPanel()
        val topSpacer = Box.createRigidArea(Dimension(0, 10))
        val old = JLabel("old")
        val bottomSpacer = Box.createRigidArea(Dimension(0, 20))
        val glue = Box.createVerticalGlue()
        listOf(topSpacer, old, bottomSpacer, glue).forEach(host::add)
        val next = List(40) { JLabel("new-$it") }

        TranscriptComponentHostReconciler.apply(
            host = host,
            previous = listOf(old),
            next = next,
            prefixCount = 1,
            onRemove = {},
        )

        assertEquals(43, host.componentCount)
        next.forEachIndexed { index, component -> assertSame(component, host.getComponent(index + 1)) }
        assertSame(bottomSpacer, host.getComponent(41))
        assertSame(glue, host.getComponent(42))
    }
}
