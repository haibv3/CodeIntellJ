package dev.haibachvan.codexintellij.ui

/**
 * Splits markdown into prose and fenced code so fences can render as native Swing cards.
 */
object MarkdownFenceSplitter {
    private val fence = Regex(
        """(?m)^```([^\n`]*)\r?\n([\s\S]*?)^```[ \t]*$""",
    )

    sealed class Part {
        data class Text(val text: String) : Part()
        data class Fence(val language: String?, val code: String) : Part()
    }

    fun split(markdown: String): List<Part> {
        if (markdown.isEmpty()) return emptyList()
        if (!markdown.contains("```")) return listOf(Part.Text(markdown))
        val parts = ArrayList<Part>()
        var cursor = 0
        for (match in fence.findAll(markdown)) {
            if (match.range.first > cursor) {
                val text = markdown.substring(cursor, match.range.first)
                if (text.isNotBlank()) parts += Part.Text(text.trimEnd() + "\n")
            }
            val lang = match.groupValues[1].trim().ifBlank { null }
            val code = match.groupValues[2].trimEnd('\n')
            parts += Part.Fence(lang, code)
            cursor = match.range.last + 1
        }
        if (cursor < markdown.length) {
            val text = markdown.substring(cursor)
            if (text.isNotBlank()) parts += Part.Text(text.trimStart())
        }
        return parts.ifEmpty { listOf(Part.Text(markdown)) }
    }
}
