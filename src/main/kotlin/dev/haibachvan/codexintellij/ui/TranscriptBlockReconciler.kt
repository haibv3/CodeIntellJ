package dev.haibachvan.codexintellij.ui

internal object TranscriptBlockReconciler {
    enum class Change { KEEP, UPDATE, INSERT }

    data class Entry(
        val block: TranscriptBlock,
        val previousIndex: Int?,
        val change: Change,
        val moved: Boolean,
    )

    data class Removal(
        val block: TranscriptBlock,
        val previousIndex: Int,
    )

    data class Plan(
        val entries: List<Entry>,
        val removals: List<Removal>,
    )

    fun plan(previous: List<TranscriptBlock>, next: List<TranscriptBlock>): Plan {
        validateIds(previous, "previous")
        validateIds(next, "next")

        val previousById = HashMap<String, IndexedValue<TranscriptBlock>>(previous.size)
        previous.forEachIndexed { index, block ->
            previousById[block.id] = IndexedValue(index, block)
        }
        val nextIds = next.mapTo(HashSet(next.size)) { it.id }
        val previousIndices = IntArray(next.size) { index ->
            previousById[next[index].id]?.index ?: -1
        }
        val moved = movedByRelativeOrder(previousIndices)
        val entries = next.mapIndexed { index, block ->
            val prior = previousById[block.id]
            Entry(
                block = block,
                previousIndex = prior?.index,
                change = when {
                    prior == null -> Change.INSERT
                    prior.value.revision == block.revision -> Change.KEEP
                    else -> Change.UPDATE
                },
                moved = moved[index],
            )
        }
        val removals = previous.mapIndexedNotNull { index, block ->
            block.takeIf { it.id !in nextIds }?.let { Removal(it, index) }
        }
        return Plan(entries, removals)
    }

    private fun validateIds(blocks: List<TranscriptBlock>, label: String) {
        val ids = HashSet<String>(blocks.size)
        blocks.forEach { block ->
            require(block.id.isNotBlank()) { "$label transcript block has a blank semantic id" }
            require(ids.add(block.id)) { "$label transcript has duplicate semantic id '${block.id}'" }
        }
    }

    private fun movedByRelativeOrder(previousIndices: IntArray): BooleanArray {
        val moved = BooleanArray(previousIndices.size)
        val prefixMax = IntArray(previousIndices.size)
        var max = -1
        for (index in previousIndices.indices) {
            prefixMax[index] = max
            max = maxOf(max, previousIndices[index])
        }
        var suffixMin = Int.MAX_VALUE
        for (index in previousIndices.indices.reversed()) {
            val previousIndex = previousIndices[index]
            if (previousIndex >= 0) {
                moved[index] = prefixMax[index] > previousIndex || suffixMin < previousIndex
                suffixMin = minOf(suffixMin, previousIndex)
            }
        }
        return moved
    }
}
