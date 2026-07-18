package dev.haibachvan.codexintellij.ui

import java.awt.Component
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JPanel

/** Applies a keyed component sequence without detaching components that remain materialized. */
internal object TranscriptComponentHostReconciler {
    fun <T : Component> collectReusableEvictions(
        previous: List<Pair<String, T>>,
        nextIds: Set<String>,
        canReuse: (T) -> Boolean,
    ): java.util.ArrayDeque<T> = java.util.ArrayDeque<T>().also { reusable ->
        previous.forEach { (id, component) ->
            if (id !in nextIds && canReuse(component)) reusable.addLast(component)
        }
    }

    fun apply(
        host: JPanel,
        previous: List<Component>,
        next: List<Component>,
        prefixCount: Int,
        onRemove: (Component) -> Unit,
    ) {
        require(prefixCount in 0..host.componentCount)
        require(previous.hasUniqueIdentities())
        require(next.hasUniqueIdentities())

        val retained = next.identitySet()
        previous.forEach { component ->
            if (component !in retained) {
                if (component.parent === host) host.remove(component)
                onRemove(component)
            }
        }

        next.forEachIndexed { index, component ->
            if (component.parent !== host) {
                host.add(component, prefixCount + index)
            }
        }
        for (index in next.indices.reversed()) {
            val component = next[index]
            val targetIndex = prefixCount + index
            if (host.getComponent(targetIndex) !== component) {
                host.setComponentZOrder(component, targetIndex)
            }
        }
    }

    private fun List<Component>.hasUniqueIdentities(): Boolean = identitySet().size == size

    private fun List<Component>.identitySet(): MutableSet<Component> =
        Collections.newSetFromMap(IdentityHashMap<Component, Boolean>()).also { it.addAll(this) }
}
