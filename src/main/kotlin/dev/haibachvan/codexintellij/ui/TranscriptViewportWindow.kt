package dev.haibachvan.codexintellij.ui

internal object TranscriptViewportWindow {
    const val TARGET_MATERIALIZED = 40
    const val HARD_MAX_MATERIALIZED = 250

    data class Anchor(val blockId: String, val offsetPx: Int)

    data class Slice(
        val startIndex: Int,
        val endExclusive: Int,
        val blocks: List<TranscriptBlock>,
        val topSpacerHeight: Int,
        val bottomSpacerHeight: Int,
        val totalHeight: Int,
        val anchor: Anchor?,
    )

    fun compute(
        blocks: List<TranscriptBlock>,
        viewportTop: Int,
        viewportHeight: Int,
        heightOf: (TranscriptBlock) -> Int,
        maxMaterialized: Int = TARGET_MATERIALIZED,
    ): Slice {
        require(maxMaterialized in 1..HARD_MAX_MATERIALIZED)
        if (blocks.isEmpty()) return Slice(0, 0, emptyList(), 0, 0, 0, null)

        val prefix = prefixHeights(blocks, heightOf)
        val totalHeight = prefix.last()
        val top = viewportTop.coerceIn(0, totalHeight)
        val bottom = (top + viewportHeight.coerceAtLeast(1)).coerceAtMost(totalHeight)
        val visibleStart = firstBlockEndingAfter(prefix, top).coerceAtMost(blocks.lastIndex)
        val visibleEnd = firstPrefixAtLeast(prefix, bottom).coerceIn(visibleStart + 1, blocks.size)
        val visibleCount = visibleEnd - visibleStart
        val before = ((maxMaterialized - visibleCount).coerceAtLeast(0)) / 2
        var start = (visibleStart - before).coerceAtLeast(0)
        var end = (start + maxMaterialized).coerceAtMost(blocks.size)
        start = (end - maxMaterialized).coerceAtLeast(0)

        val topHeight = prefix[start]
        val bottomHeight = totalHeight - prefix[end]
        return Slice(
            startIndex = start,
            endExclusive = end,
            blocks = blocks.subList(start, end),
            topSpacerHeight = topHeight,
            bottomSpacerHeight = bottomHeight,
            totalHeight = totalHeight,
            anchor = Anchor(blocks[visibleStart].id, top - prefix[visibleStart]),
        )
    }

    fun scrollTopForAnchor(
        blocks: List<TranscriptBlock>,
        anchor: Anchor,
        heightOf: (TranscriptBlock) -> Int,
    ): Int {
        var top = 0
        for (block in blocks) {
            if (block.id == anchor.blockId) return (top + anchor.offsetPx).coerceAtLeast(0)
            top = saturatedAdd(top, heightOf(block).coerceAtLeast(1))
        }
        return top
    }

    private fun prefixHeights(
        blocks: List<TranscriptBlock>,
        heightOf: (TranscriptBlock) -> Int,
    ): IntArray {
        val prefix = IntArray(blocks.size + 1)
        blocks.forEachIndexed { index, block ->
            prefix[index + 1] = saturatedAdd(prefix[index], heightOf(block).coerceAtLeast(1))
        }
        return prefix
    }

    private fun firstBlockEndingAfter(prefix: IntArray, value: Int): Int {
        var low = 1
        var high = prefix.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefix[middle] > value) high = middle else low = middle + 1
        }
        return low - 1
    }

    private fun firstPrefixAtLeast(prefix: IntArray, value: Int): Int {
        var low = 1
        var high = prefix.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (prefix[middle] >= value) high = middle else low = middle + 1
        }
        return low
    }

    private fun saturatedAdd(left: Int, right: Int): Int =
        if (left > Int.MAX_VALUE - right) Int.MAX_VALUE else left + right
}

internal class TranscriptHeightCache(private val capacity: Int = 512) {
    data class Key(val id: String, val revision: TranscriptBlockRevision, val widthBucket: Int)

    private data class Entry(
        val revision: TranscriptBlockRevision,
        val widthBucket: Int,
        val height: Int,
    )

    private val entries = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > capacity
    }

    init {
        require(capacity > 0)
    }

    fun get(key: Key): Int? = get(key.id, key.revision, key.widthBucket)

    fun get(id: String, revision: TranscriptBlockRevision, widthBucket: Int): Int? {
        val entry = entries[id] ?: return null
        return entry.height.takeIf {
            entry.revision == revision && entry.widthBucket == widthBucket
        }
    }

    fun put(key: Key, height: Int) {
        put(key.id, key.revision, key.widthBucket, height)
    }

    fun put(id: String, revision: TranscriptBlockRevision, widthBucket: Int, height: Int) {
        entries[id] = Entry(revision, widthBucket, height.coerceAtLeast(1))
    }

    val size: Int get() = entries.size
}

internal sealed class TranscriptScrollMode {
    data object FollowLive : TranscriptScrollMode()
    data class Reading(val anchor: TranscriptViewportWindow.Anchor) : TranscriptScrollMode()
    data object Dragging : TranscriptScrollMode()
}

internal class TranscriptScrollState {
    var mode: TranscriptScrollMode = TranscriptScrollMode.FollowLive
        private set

    private var pending: List<TranscriptBlock>? = null

    fun onScroll(
        value: Int,
        extent: Int,
        maximum: Int,
        adjusting: Boolean,
        anchor: TranscriptViewportWindow.Anchor?,
    ) {
        mode = when {
            adjusting -> TranscriptScrollMode.Dragging
            maximum - (value + extent) <= NEAR_BOTTOM_PX -> TranscriptScrollMode.FollowLive
            anchor != null -> TranscriptScrollMode.Reading(anchor)
            else -> mode
        }
    }

    fun defer(blocks: List<TranscriptBlock>) {
        pending = blocks
    }

    fun consumePending(): List<TranscriptBlock>? = pending.also { pending = null }

    fun onContentAppended() = Unit

    fun shouldFollowLive(): Boolean = mode == TranscriptScrollMode.FollowLive

    private companion object {
        const val NEAR_BOTTOM_PX = 80
    }
}
