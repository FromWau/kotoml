package com.fromwau.kotoml.app

/** A line of TOML text and the line break that ends it, empty for the last. */
private data class Line(
    val text: String,
    val ending: String,
    /** Whether the line starts inside a multi-line string, whose text is the string's, never a comment or spacing. */
    val inString: Boolean,
    val hadComment: Boolean,
) {
    val isBlank: Boolean get() = !inString && text.isBlank()
}

/**
 * This valid TOML text without its comments: a comment ending a line goes with the space before it, a line that
 * held only a comment goes whole, and the blank lines left side by side fold into one, those at the start and end
 * going too. What strings hold, `#` included, is kept.
 */
internal fun String.withoutComments(): String {
    val kept = mutableListOf<Line>()
    for (line in tomlLines()) {
        if (line.hadComment && line.isBlank) continue
        val text = if (line.hadComment) line.text.trimEnd(' ', '\t') else line.text
        if (line.isBlank && (kept.isEmpty() || kept.last().isBlank)) continue
        kept += line.copy(text = text)
    }
    while (kept.lastOrNull()?.isBlank == true) kept.removeAt(kept.lastIndex)
    return buildString {
        kept.forEachIndexed { index, line ->
            append(line.text)
            if (index < kept.lastIndex) append(line.ending)
        }
    }
}

private enum class Quote(val delimiter: String) {
    Basic("\""),
    Literal("'"),
    MultilineBasic("\"\"\""),
    MultilineLiteral("'''"),
    ;

    val multiline: Boolean get() = delimiter.length == 3
    val escapes: Boolean get() = this == Basic || this == MultilineBasic
}

private val QUOTES_LONGEST_FIRST = Quote.entries.sortedByDescending { it.delimiter.length }

/** This text as lines, each with its comment left out and marked, telling string contents from the rest. */
private fun String.tomlLines(): List<Line> {
    val lines = mutableListOf<Line>()
    val text = StringBuilder()
    var quote: Quote? = null
    var lineInString = false
    var hadComment = false
    var index = 0
    fun endLine(ending: String) {
        lines += Line(text.toString(), ending, lineInString, hadComment)
        text.clear()
        lineInString = quote != null
        hadComment = false
    }
    while (index < length) {
        val char = this[index]
        when {
            char == '\n' -> {
                val crlf = text.endsWith('\r')
                if (crlf) text.setLength(text.length - 1)
                endLine(if (crlf) "\r\n" else "\n")
                index++
            }
            quote == null && char == '#' -> {
                val lineEnd = indexOf('\n', index).let { if (it < 0) length else it }
                index = if (lineEnd > index && this[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
                hadComment = true
            }
            quote == null -> {
                quote = QUOTES_LONGEST_FIRST.firstOrNull { startsWith(it.delimiter, index) }
                val consumed = quote?.delimiter?.length ?: 1
                text.append(this, index, index + consumed)
                index += consumed
            }
            quote.escapes && char == '\\' && index + 1 < length && this[index + 1] in "\\\"" -> {
                text.append(this, index, index + 2)
                index += 2
            }
            startsWith(quote.delimiter, index) -> {
                // A multi-line string may end in up to two quotes of its own before its closing three.
                val quoteChar = quote.delimiter[0]
                val run = if (quote.multiline) {
                    (index until length).takeWhile { this[it] == quoteChar }.size.coerceAtMost(5)
                } else {
                    1
                }
                text.append(this, index, index + run)
                index += run
                quote = null
            }
            else -> {
                text.append(char)
                index++
            }
        }
    }
    endLine("")
    return lines
}
