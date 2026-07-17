package dev.haibachvan.codexintellij.session

/** +/- line counts derived from a unified diff. */
data class DiffLineCounts(val added: Int, val removed: Int) {
    operator fun plus(other: DiffLineCounts): DiffLineCounts =
        DiffLineCounts(added + other.added, removed + other.removed)

    companion object {
        val ZERO = DiffLineCounts(0, 0)
    }
}

object DiffLineStats {
    fun of(unifiedDiff: String?): DiffLineCounts {
        if (unifiedDiff.isNullOrBlank()) return DiffLineCounts.ZERO
        var added = 0
        var removed = 0
        for (raw in unifiedDiff.lineSequence()) {
            val line = raw
            when {
                line.startsWith("+++") ||
                    line.startsWith("---") ||
                    line.startsWith("@@") ||
                    line.startsWith("diff ") ||
                    line.startsWith("index ") ||
                    line.startsWith("new file") ||
                    line.startsWith("deleted file") ||
                    line.startsWith("similarity") ||
                    line.startsWith("rename") -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return DiffLineCounts(added, removed)
    }
}
