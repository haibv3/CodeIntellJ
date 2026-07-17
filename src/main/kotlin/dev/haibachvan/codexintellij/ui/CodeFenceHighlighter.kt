package dev.haibachvan.codexintellij.ui

import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.awt.Color

/**
 * Colors fenced markdown for Swing [JBHtmlPane] using `<font color>` tags.
 *
 * Why not HtmlSyntaxInfoUtil / CSS spans? HTMLEditorKit largely ignores
 * `style="color:…"` inside transcript HTML, so output looked monochrome.
 */
object CodeFenceHighlighter {
    private const val MAX_CHARS = 48_000

    // Darcula-like palette — distinct from default text so Swing always shows contrast.
    private val KW = Color(0xCC, 0x78, 0x32)
    private val STR = Color(0x6A, 0x87, 0x59)
    private val CMT = Color(0x80, 0x80, 0x80)
    private val NUM = Color(0x68, 0x97, 0xBB)
    private val TYPE = Color(0xE0, 0x6C, 0x75) // pink/magenta like Codex preview
    private val FN = Color(0xD4, 0xD4, 0xD4)
    private val ANN2 = Color(0xBB, 0xB5, 0x29)
    private val IDENT = Color(0xE0, 0x6C, 0x75)

    fun toHtml(project: Project?, code: String, languageHint: String?): String {
        val trimmed = code.trimEnd('\n')
        if (trimmed.isEmpty()) return ""
        if (trimmed.length > MAX_CHARS) return escapeXml(trimmed)
        // Even without a resolvable Language, still apply the C-like keyword pass.
        val language = resolveLanguage(languageHint)
        return try {
            if (language != null) {
                colorizeWithLexer(project?.takeUnless { it.isDisposed }, language, trimmed)
            } else {
                fallbackKeywordColor(trimmed)
            }
        } catch (_: Throwable) {
            runCatching { fallbackKeywordColor(trimmed) }.getOrElse { escapeXml(trimmed) }
        }
    }

    private fun colorizeWithLexer(project: Project?, language: Language, code: String): String {
        return try {
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, null)
                ?: return fallbackKeywordColor(code)
            val scheme = EditorColorsManager.getInstance().globalScheme
            val lexer = highlighter.highlightingLexer
            lexer.start(code)
            val out = StringBuilder(code.length * 2)
            var emittedFont = false
            while (true) {
                val type = lexer.tokenType ?: break
                val start = lexer.tokenStart
                val end = lexer.tokenEnd.coerceAtMost(code.length)
                if (start in 0 until end) {
                    val text = code.substring(start, end)
                    val keys = highlighter.getTokenHighlights(type)
                    val color = colorFor(keys, text, scheme)
                    if (color != null) emittedFont = true
                    appendColored(out, text, color)
                }
                lexer.advance()
            }
            val result = out.toString()
            if (!emittedFont || !result.contains("<font color=\"")) {
                return fallbackKeywordColor(code)
            }
            result.ifBlank { fallbackKeywordColor(code) }
        } catch (_: Throwable) {
            fallbackKeywordColor(code)
        }
    }

    private fun colorFor(
        keys: Array<TextAttributesKey>,
        text: String,
        scheme: com.intellij.openapi.editor.colors.EditorColorsScheme,
    ): Color? {
        paletteFromKeys(keys)?.let { return it }
        // Scheme color only when it is not the plain default text color.
        val defaultFg = scheme.defaultForeground
        keys.forEach { key ->
            val fg = scheme.getAttributes(key)?.foregroundColor ?: return@forEach
            if (fg.rgb != defaultFg.rgb) return fg
        }
        // Last resort: classify the token text itself for C-like languages.
        return heuristicTokenColor(text)
    }

    private fun paletteFromKeys(keys: Array<TextAttributesKey>): Color? {
        if (keys.isEmpty()) return null
        val names = keys.joinToString(" ") { it.externalName.lowercase() }
        return when {
            "comment" in names -> CMT
            "string" in names || "char" in names -> STR
            "number" in names || "numeric" in names -> NUM
            "keyword" in names || "modifier" in names || "java_keyword" in names -> KW
            "annotation" in names || "meta" in names -> ANN2
            "class" in names || "type" in names || "interface" in names || "enum" in names -> TYPE
            "method" in names || "function" in names || "static_method" in names -> FN
            "variable" in names || "local" in names || "parameter" in names || "field" in names -> IDENT
            else -> null
        }
    }

    private fun heuristicTokenColor(text: String): Color? {
        if (text.isEmpty()) return null
        if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("*") || text.startsWith("#")) {
            return CMT
        }
        if ((text.startsWith("\"") && text.endsWith("\"")) ||
            (text.startsWith("'") && text.endsWith("'")) ||
            (text.startsWith("`") && text.endsWith("`"))
        ) {
            return STR
        }
        if (text[0].isDigit()) return NUM
        if (text[0].isUpperCase() && text.all { it.isLetterOrDigit() || it == '_' }) return TYPE
        if (text in CLIKE_KEYWORDS) return KW
        return null
    }

    private fun fallbackKeywordColor(code: String): String {
        // Very small scanner when the platform highlighter is inert in tests / headless.
        val out = StringBuilder(code.length * 2)
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                    appendColored(out, code.substring(i, end), CMT)
                    i = end
                }
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    appendColored(out, code.substring(i, end), CMT)
                    i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val q = code[i]
                    var j = i + 1
                    while (j < code.length) {
                        when {
                            code[j] == '\\' && j + 1 < code.length -> j += 2
                            code[j] == q -> {
                                j++
                                break
                            }
                            else -> j++
                        }
                    }
                    appendColored(out, code.substring(i, j), STR)
                    i = j
                }
                code[i].isDigit() -> {
                    var j = i + 1
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    appendColored(out, code.substring(i, j), NUM)
                    i = j
                }
                code[i].isLetter() || code[i] == '_' -> {
                    var j = i + 1
                    while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val word = code.substring(i, j)
                    // Match Codex preview: types pink; leave methods/vars for lexer when available.
                    val color = when {
                        word in CLIKE_KEYWORDS -> KW
                        word.first().isUpperCase() -> TYPE
                        else -> null
                    }
                    appendColored(out, word, color)
                    i = j
                }
                code[i] == '\n' -> {
                    out.append('\n')
                    i++
                }
                else -> {
                    out.append(escapeXml(code[i].toString()))
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Never nest newlines inside `<font>` — Swing Bidi/HTML breaks on that. */
    private fun appendColored(out: StringBuilder, text: String, color: Color?) {
        var i = 0
        while (i < text.length) {
            val nl = text.indexOf('\n', i)
            val chunk = if (nl < 0) text.substring(i) else text.substring(i, nl)
            if (chunk.isNotEmpty()) {
                if (color != null) {
                    out.append("<font color=\"").append(hex(color)).append("\">")
                    out.append(escapeXml(chunk))
                    out.append("</font>")
                } else {
                    out.append(escapeXml(chunk))
                }
            }
            if (nl < 0) break
            out.append('\n')
            i = nl + 1
        }
    }

    fun resolveLanguage(hint: String?): Language? {
        val raw = hint?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty() || raw == "text" || raw == "plaintext" || raw == "plain") return null
        val ext = when (raw) {
            "kt", "kts", "kotlin" -> "kt"
            "java" -> "java"
            "js", "javascript", "jsx" -> "js"
            "ts", "typescript", "tsx" -> "ts"
            "py", "python" -> "py"
            "rs", "rust" -> "rs"
            "go", "golang" -> "go"
            "sh", "bash", "zsh", "shell" -> "sh"
            "xml", "html", "htm" -> "xml"
            "json" -> "json"
            "yaml", "yml" -> "yml"
            "sql" -> "sql"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp", "c++" -> "cpp"
            "cs", "csharp" -> "cs"
            "rb", "ruby" -> "rb"
            "swift" -> "swift"
            "gradle", "groovy" -> "groovy"
            "md", "markdown" -> "md"
            "properties" -> "properties"
            else -> raw
        }
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
        LanguageUtil.getFileTypeLanguage(fileType)?.let { return it }
        val idCandidates = when (ext) {
            "java" -> listOf("JAVA")
            "kt" -> listOf("kotlin", "Kotlin")
            "js" -> listOf("JavaScript", "JS")
            "ts" -> listOf("TypeScript")
            "py" -> listOf("Python", "PythonCore")
            "xml" -> listOf("XML", "HTML")
            "json" -> listOf("JSON")
            "yml" -> listOf("yaml", "YAML")
            "sh" -> listOf("Shell Script", "Bash")
            "sql" -> listOf("SQL")
            "go" -> listOf("go", "Go")
            "rs" -> listOf("Rust")
            "groovy" -> listOf("Groovy")
            else -> listOf(raw, raw.uppercase())
        }
        for (id in idCandidates) {
            Language.findLanguageByID(id)?.let { return it }
        }
        return Language.findLanguageByID(raw)
            ?: Language.getRegisteredLanguages().firstOrNull {
                it.id.equals(raw, ignoreCase = true) ||
                    it.displayName.equals(raw, ignoreCase = true)
            }
    }

    private fun hex(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    private fun escapeXml(text: String): String = StringUtil.escapeXmlEntities(text)

    private val CLIKE_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "var", "record", "sealed", "permits", "when", "yield",
        "fun", "val", "object", "companion", "data", "lateinit", "override", "open", "internal",
        "actual", "expect", "suspend", "typealias", "in", "is", "as", "out",
        "function", "let", "typeof", "await", "async", "export", "from", "of",
        "def", "elif", "except", "lambda", "pass", "raise", "with", "None", "True", "False",
        "fn", "mut", "pub", "impl", "struct", "trait", "use", "mod", "match", "loop",
        "func", "defer", "go", "chan", "range", "select", "type", "nil",
    )
}
