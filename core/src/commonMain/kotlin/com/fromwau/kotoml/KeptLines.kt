package com.fromwau.kotoml

/** A line printed in a table body: a key/value pair, or the lines kept from a removed one. */
internal sealed interface BodyLine {
    /** Where the line stands among the lines read from text, or null for one made by hand. */
    val order: Long?
}

/**
 * Comment and blank lines that stay where they printed after the key or table below them was removed. In a
 * table body [order] places them among the key/value lines; among the tables it is a table position.
 */
internal class KeptLines(
    var text: String,
    override var order: Long?,
) : BodyLine {
    fun copy(): KeptLines = KeptLines(text, order)
}

/** The whole lines [prefix] holds above the line it ends on, line breaks included: everything to its last break. */
internal fun linesAbove(prefix: KeptText?): String {
    val text = prefix?.textOrNull ?: return ""
    return text.substring(0, text.lastIndexOf('\n') + 1)
}

/**
 * The whole lines above an inline table's entry whose [prefix] was spaced for braces, as they print on lines of a
 * table: the prefix's first break ends the line before, and spacing after its last break goes.
 */
internal fun linesAboveOnTableLines(prefix: KeptText?): String {
    val text = prefix?.textOrNull ?: return ""
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return ""
    return text.substring(firstBreak + 1, text.lastIndexOf('\n') + 1)
}

/**
 * The comment lines that lines kept in braces from removed keys, [kept], print as on lines of a table: those after
 * its first break, which ends the line before, unindented and without blank lines.
 */
internal fun keptLinesOnTableLines(kept: String): String =
    indentedCommentLines(kept.substringAfter('\n', ""), "", kept.lineBreak())

// Between the elements of an inline table or an array the break ending a line opens the next prefix, so the
// lines above an element end before its prefix's last break.
internal fun linesAboveElement(prefix: KeptText?): String {
    val text = prefix?.textOrNull ?: return ""
    return text.substring(0, text.lastIndexOf('\n').coerceAtLeast(0))
}

/**
 * The text that takes the place of [next] once an element of an array or inline table is removed, or null when
 * the element shares its line with what comes before it and only its own prefix, value, suffix and comma go.
 * An element on a line of its own leaves the lines above it and takes the rest of its line, its comment included.
 *
 * @param prefix the removed element's prefix; its last line break ends the lines above it.
 * @param suffix the removed element's suffix, which holds its comment when no comma follows it.
 * @param next the prefix of the element after it, or the trailing text when it was the last.
 * @param nextIsElement whether [next] is an element's prefix.
 */
internal fun textAfterRemovedElement(
    prefix: String,
    suffix: String,
    next: String,
    nextIsElement: Boolean,
): String? {
    val lastBreak = prefix.lastIndexOf('\n')
    if (lastBreak < 0) return null
    val rest = when {
        '\n' in suffix -> suffix.substringAfter('\n') + next
        '\n' in next -> next.substringAfter('\n')
        nextIsElement -> prefix.substring(lastBreak + 1)
        else -> next
    }
    return prefix.substring(0, lastBreak + 1) + rest
}

/**
 * Gives [survivor], now the last element on one line, the space before the closing bracket that the removed last
 * element [removed] held, unless [survivor] has its own.
 */
internal fun passClosingSpace(
    removed: Value,
    survivor: Value,
) {
    val space = removed.rawDecor.suffix ?: return
    val text = space.textOrNull ?: return
    if ('\n' in text || '#' in text || !survivor.rawDecor.suffix?.textOrNull.isNullOrEmpty()) return
    survivor.rawDecor.suffix = space
}

/**
 * This text after an entry, starting the next entry's prefix or the text before the closing bracket, with [comment]
 * ending the entry's line in place of what stood there; with no line break in it, [comment] and [lineBreak] come
 * before [lineStart].
 */
internal fun String.withCommentEndingLine(
    comment: String,
    lineBreak: String,
    lineStart: String,
): String {
    val firstBreak = indexOf('\n')
    if (firstBreak < 0) return comment + lineBreak + lineStart
    return comment + substring(lineEndBefore(firstBreak))
}

/** Whether this text, after a value, ends the value's line with no comment on it. */
internal fun String.endsLineWithoutComment(): Boolean = '\n' in this && '#' !in substringBefore('\n')

/**
 * This text between elements without its comment lines: the comment ending the line before stays when
 * [keepFirstLineComment], then the line break and the indentation of the line after.
 */
internal fun String.withoutCommentLines(keepFirstLineComment: Boolean): String {
    val firstBreak = indexOf('\n')
    if (firstBreak < 0) return if (keepFirstLineComment || '#' !in this) this else ""
    val lineEnd = lineEndBefore(firstBreak)
    val head = substring(0, lineEnd).let { if (keepFirstLineComment || '#' !in it) it else "" }
    val lineBreak = substring(lineEnd, firstBreak + 1)
    return head + lineBreak + substringAfterLast('\n')
}

/** Where the line ended by the `\n` at [lineBreak] ends, before the `\r` of a CRLF. */
internal fun String.lineEndBefore(lineBreak: Int): Int =
    if (lineBreak > 0 && this[lineBreak - 1] == '\r') lineBreak - 1 else lineBreak

/** The line break this text uses: CRLF when it holds one, LF otherwise. */
internal fun String.lineBreak(): String = if ("\r\n" in this) "\r\n" else "\n"

/** Puts [text] in front of the prefix, which printed [default] while it had none of its own. */
internal fun RawDecor.prependToPrefix(
    text: String,
    default: String,
) {
    prefix = KeptText.from(text + (prefix?.textOr(default) ?: default))
}

/** The body lines that go with removing [removed]: their values' lines and the lines kept among them. */
internal class RemovedLines(removed: Collection<KeyValue>) {
    private val names = removed.mapTo(HashSet()) { it.key.name }
    private val keptAmong = HashSet<KeptLines>()

    init {
        for (entry in removed) collectKeptBodyLines(entry.item)
    }

    // Only a dotted table prints in its parent's body, so only its kept lines go with it.
    private fun collectKeptBodyLines(item: Item) {
        val pairs = item.standardTableOrNull?.takeIf { it.isDotted }?.items
            ?: item.inlineTableOrNull?.takeIf { it.isDotted }?.items
            ?: return
        keptAmong += pairs.keptLines
        for (entry in pairs.entries) collectKeptBodyLines(entry.item)
    }

    operator fun contains(line: BodyLine): Boolean = when (line) {
        is VisualValue -> line.path.first().name in names
        is KeptLines -> line in keptAmong
    }
}

/**
 * Keeps the lines above the [removed] lines of a body printed as [lines], before they are removed. A run of them
 * goes in front of the line printed next, or to [keep] when none follows. In a dotted table, lines read from text
 * each stay at their own place in the reading order instead: it cannot see the lines of the table it prints in. A
 * removed key that prints several lines in a row, a dotted table, keeps only the lines above the first of them: those
 * between them go with it.
 *
 * @param linesAbove the lines above a value's own line.
 * @param keyDefault the prefix a key prints while it has none of its own.
 */
internal fun keepLinesAbove(
    lines: List<BodyLine>,
    removed: RemovedLines,
    isDotted: Boolean,
    linesAbove: (VisualValue) -> String,
    keyDefault: String,
    keep: (KeptLines) -> Unit,
) {
    val inPlace = isDotted && lines.any { it in removed && it.order != null }
    var pending = ""
    var pendingOrder: Long? = null
    for ((index, line) in lines.withIndex()) {
        if (line in removed) {
            val before = lines.getOrNull(index - 1)
            val continues = line is VisualValue && before is VisualValue && before in removed &&
                before.path.first().name == line.path.first().name
            val text = when (line) {
                is VisualValue -> if (continues) "" else linesAbove(line)
                is KeptLines -> line.text
            }
            if (inPlace) {
                if (text.isNotEmpty()) keep(KeptLines(text, line.order))
            } else {
                pending += text
                pendingOrder = line.order ?: pendingOrder
            }
        } else if (pending.isNotEmpty()) {
            when (line) {
                is VisualValue -> line.entry.key.rawLeafDecor.prependToPrefix(pending, keyDefault)
                is KeptLines -> line.text = pending + line.text
            }
            pending = ""
        }
    }
    if (pending.isNotEmpty()) keep(KeptLines(pending, pendingOrder))
}

/**
 * Keeps the lines above the first header that removing [item] takes out of the document, in [kept], and takes them
 * from [item], so storing it again does not print them twice. The lines above its later headers, those of the tables
 * under it and of later array elements, belong to it and go too.
 */
internal fun keepHeaderLinesOf(
    item: Item,
    kept: MutableList<KeptLines>,
) {
    val parts = mutableListOf<HeaderPart>()
    collectHeaderParts(item, parts)
    keepAboveFirstHeader(parts, kept)
}

/** Keeps the lines above the header of [table], removed alone, in [kept]; see the [Item] overload. */
internal fun keepHeaderLinesOf(
    table: StandardTable,
    isArrayOfTables: Boolean,
    kept: MutableList<KeptLines>,
) {
    val parts = mutableListOf<HeaderPart>()
    collectHeaderParts(table, isArrayOfTables, parts)
    keepAboveFirstHeader(parts, kept)
}

/**
 * Keeps in [kept], and takes from [table], the lines removing it keeps when a header under it prints before its own:
 * those above the first header it prints, which a layout without headers puts above its key.
 */
internal fun keepLinesAboveEarlierHeaders(
    table: StandardTable,
    kept: MutableList<KeptLines>,
) {
    val own = table.position ?: return
    val parts = mutableListOf<HeaderPart>()
    collectHeaderParts(table, isArrayOfTables = false, parts)
    keepAboveFirstHeader(parts, kept, printedBefore = own)
}

/**
 * A header a removal takes out of the document with the lines above it, or lines kept from an earlier removal;
 * [leave] takes those lines from the removed item once the document keeps them.
 */
private class HeaderPart(
    val position: Long?,
    val linesAbove: String,
    val isHeader: Boolean,
    val leave: () -> Unit,
)

private fun collectHeaderParts(
    item: Item,
    parts: MutableList<HeaderPart>,
) {
    item.standardTableOrNull?.let { collectHeaderParts(it, isArrayOfTables = false, parts) }
    item.arrayOfTablesOrNull?.let { array ->
        for (table in array) collectHeaderParts(table, isArrayOfTables = true, parts)
        collectKeptParts(array.keptHeaderLines, parts)
    }
}

private fun collectHeaderParts(
    table: StandardTable,
    isArrayOfTables: Boolean,
    parts: MutableList<HeaderPart>,
) {
    if (!table.printsDotted && table.printsHeader(isArrayOfTables)) {
        val decor = table.rawDecor
        val above = linesAbove(decor.prefix)
        parts += HeaderPart(table.position, above, isHeader = true) {
            decor.prefix = KeptText.from(decor.prefix?.textOrNull.orEmpty().removePrefix(above))
        }
    }
    collectKeptParts(table.keptHeaderLines, parts)
    for (entry in table.items.entries) collectHeaderParts(entry.item, parts)
}

private fun collectKeptParts(
    keptHeaderLines: MutableList<KeptLines>,
    parts: MutableList<HeaderPart>,
) {
    for (lines in keptHeaderLines) {
        parts += HeaderPart(lines.order, lines.text, isHeader = false) { keptHeaderLines.remove(lines) }
    }
}

// Parts made by hand have no position and print right after the part before them, as the document prints them.
private fun keepAboveFirstHeader(
    parts: List<HeaderPart>,
    kept: MutableList<KeptLines>,
    printedBefore: Long = Long.MAX_VALUE,
) {
    var previous = Long.MIN_VALUE
    val printed = parts
        .map { part -> (part.position ?: previous).also { previous = it } to part }
        .filter { it.first < printedBefore }
        .sortedBy { it.first }
        .map { it.second }
    val firstHeader = printed.indexOfFirst { it.isHeader }
    val above = if (firstHeader < 0) printed else printed.subList(0, firstHeader + 1)
    for (part in above) {
        if (part.linesAbove.isEmpty()) continue
        kept += KeptLines(part.linesAbove, part.position)
        part.leave()
    }
}
