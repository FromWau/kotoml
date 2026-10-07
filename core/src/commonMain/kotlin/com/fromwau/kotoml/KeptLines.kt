package com.fromwau.kotoml

/** A line printed in a table body: a key/value pair, or the lines kept from a removed one. */
internal sealed interface BodyLine {
    /** Where the line stands among the lines read from text, or null for one made by hand. */
    val order: Long?
}

/**
 * Comment and blank lines that stay where they printed after the key or table below them was removed. In a
 * table body [order] places them among the key/value lines; among the tables it is a table position. Lines that
 * [dropBlankLinesMeeting] lose the blank lines ending them when blank lines or the end of the document follow, as
 * the lines kept above a removed header do.
 */
internal class KeptLines(
    var text: String,
    override var order: Long?,
    val dropBlankLinesMeeting: Boolean = false,
) : BodyLine {
    fun copy(): KeptLines = KeptLines(text, order, dropBlankLinesMeeting)
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
 * The comment and blank lines that lines kept in braces from removed keys, [kept], print as on lines of a table: those
 * after its first break, which ends the line before, unindented.
 */
internal fun keptLinesOnTableLines(kept: String): String =
    indentedCommentLines(kept.substringAfter('\n', ""), "", kept.lineBreak(), keepBlankLines = true)

// Between the elements of an inline table or an array the break ending a line opens the next prefix, so the
// lines above an element end before its prefix's last break.
internal fun linesAboveElement(prefix: KeptText?): String {
    val text = prefix?.textOrNull ?: return ""
    return text.substring(0, text.lineEndBefore(text.lastIndexOf('\n').coerceAtLeast(0)))
}

/**
 * The part of [linesAboveElement]'s [text] that belongs to no element: the end of the line before, and the lines up
 * to the last blank one, without the comment lines right above the element, which belong to it.
 */
internal fun freeLinesAboveElement(text: String): String {
    val firstBreak = text.indexOf('\n')
    if (firstBreak < 0) return text
    var cut = text.lineEndBefore(firstBreak)
    var start = firstBreak + 1
    while (start <= text.length) {
        val lineBreak = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val end = if (lineBreak < text.length) text.lineEndBefore(lineBreak) else lineBreak
        if (text.substring(start, end).isBlank()) cut = end
        start = lineBreak + 1
    }
    return text.substring(0, cut)
}

/** This text of [linesAboveElement]'s shape, each line opened by the break before it, without blank lines ending it. */
internal fun String.withoutTrailingBlankElementLines(): String {
    var text = this
    while (true) {
        val lastBreak = text.lastIndexOf('\n')
        if (lastBreak < 0 || text.substring(lastBreak + 1).isNotBlank()) return text
        text = text.substring(0, text.lineEndBefore(lastBreak))
    }
}

/** This text of whole lines without the blank lines that end it. */
internal fun String.withoutTrailingBlankLines(): String {
    var end = length
    while (end > 0) {
        val lineStart = lastIndexOf('\n', end - 2) + 1
        if (substring(lineStart, end).isNotBlank()) break
        end = lineStart
    }
    return substring(0, end)
}

/** Whether this text starts with a blank line, one that ends in a line break. */
internal fun String.startsWithBlankLine(): Boolean {
    val lineBreak = indexOf('\n')
    return lineBreak >= 0 && substring(0, lineBreak).isBlank()
}

/** This text without the blank lines it starts with. */
internal fun String.withoutLeadingBlankLines(): String {
    var start = 0
    while (substring(start).startsWithBlankLine()) start = indexOf('\n', start) + 1
    return substring(start)
}

/**
 * The text that takes the place of [next] once an element of an array or inline table is removed, or null when
 * the element shares its line with what comes before it and only its own prefix, value, suffix and comma go.
 * An element on a line of its own leaves the lines above it up to the last blank one, and takes the rest of its line,
 * its comment included, the comment lines right above it, and those between it and a comma on a later line. The last
 * element on a line it shares takes the comment ending that line.
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
    if (lastBreak < 0) return if ('\n' !in suffix) next.withoutCommentEndingFirstLine() else null
    val rest = when {
        '\n' in next -> next.substringAfter('\n')
        '\n' in suffix -> suffix.substringAfterLast('\n') + next.trimStart(' ', '\t')
        nextIsElement -> prefix.substring(lastBreak + 1)
        else -> next
    }
    val firstBreak = prefix.indexOf('\n')
    val free = splitLinesAbove(prefix.substring(firstBreak + 1, lastBreak + 1)).free
    val meetsBlankOrEnd = rest.startsWithBlankLine() || (!nextIsElement && rest.isBlank())
    val kept = if (meetsBlankOrEnd) free.withoutTrailingBlankLines() else free
    return prefix.substring(0, firstBreak + 1) + kept + rest
}

// The comment ending the line an element shares with the one before belongs to the last element on that line.
private fun String.withoutCommentEndingFirstLine(): String? {
    val lineBreak = indexOf('\n')
    if (lineBreak < 0 || '#' !in substring(0, lineBreak)) return null
    return substring(lineEndBefore(lineBreak))
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
 * [keepFirstLineComment], then the line break, the blank lines between when [keepBlankLines], and the indentation of
 * the line after.
 */
internal fun String.withoutCommentLines(
    keepFirstLineComment: Boolean,
    keepBlankLines: Boolean = false,
): String {
    val firstBreak = indexOf('\n')
    if (firstBreak < 0) return if (keepFirstLineComment || '#' !in this) this else ""
    val lineEnd = lineEndBefore(firstBreak)
    val head = substring(0, lineEnd).let { if (keepFirstLineComment || '#' !in it) it else "" }
    val lineBreak = substring(lineEnd, firstBreak + 1)
    val between = substring(firstBreak + 1, lastIndexOf('\n') + 1)
    val blankLines = if (keepBlankLines) between.split('\n').dropLast(1).count { it.isBlank() } else 0
    return head + lineBreak + lineBreak.repeat(blankLines) + substringAfterLast('\n')
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
 * between them go with it. A removal that [setsBlankLines] leaves one blank line where the removed lines had one
 * above and one below them, and none at the start of a body that [startsDocument].
 *
 * @param linesAbove the lines above a value's own line that stay.
 * @param keyDefault the prefix a key prints while it has none of its own.
 */
internal fun keepLinesAbove(
    lines: List<BodyLine>,
    removed: RemovedLines,
    isDotted: Boolean,
    linesAbove: (VisualValue) -> String,
    keyDefault: String,
    keep: (KeptLines) -> Unit,
    setsBlankLines: Boolean = false,
    startsDocument: Boolean = false,
) {
    val inPlace = isDotted && lines.any { it in removed && it.order != null }
    val spaces = setsBlankLines && !inPlace
    var pending = ""
    var pendingOrder: Long? = null
    var runStartsDocument = false
    for ((index, line) in lines.withIndex()) {
        if (line in removed) {
            if (index == 0) runStartsDocument = startsDocument
            val before = lines.getOrNull(index - 1)
            val continues = line is VisualValue && before is VisualValue && before in removed &&
                before.path.first().name == line.path.first().name
            val text = when (line) {
                is VisualValue -> if (continues) "" else linesAbove(line)
                is KeptLines -> line.text
            }
            if (inPlace) {
                if (text.isNotEmpty()) keep(KeptLines(text, line.order, dropBlankLinesMeeting = setsBlankLines))
            } else {
                pending += text
                pendingOrder = line.order ?: pendingOrder
            }
        } else {
            if (spaces) pending = spaceAbove(line, pending, runStartsDocument)
            if (pending.isNotEmpty()) {
                when (line) {
                    is VisualValue -> line.entry.key.rawLeafDecor.prependToPrefix(pending, keyDefault)
                    is KeptLines -> line.text = pending + line.text
                }
            }
            pending = ""
            runStartsDocument = false
        }
    }
    if (pending.isNotEmpty()) keep(KeptLines(pending, pendingOrder, dropBlankLinesMeeting = spaces))
}

/**
 * The [kept] lines of removed lines that go above [line], without the blank lines ending them when [line] starts
 * with one; with nothing kept and the removed lines starting the document, [line] loses the blank lines it starts with.
 */
private fun spaceAbove(
    line: BodyLine,
    kept: String,
    removedStartedDocument: Boolean,
): String {
    val text = when (line) {
        is VisualValue -> (line.lineStart ?: line.entry.key.rawLeafDecor.prefix)?.textOrNull.orEmpty()
        is KeptLines -> line.text
    }
    if (!text.startsWithBlankLine()) return kept
    if (kept.isNotEmpty() || !removedStartedDocument) return kept.withoutTrailingBlankLines()
    when {
        line is KeptLines -> line.text = text.withoutLeadingBlankLines()
        line is VisualValue && line.lineStart == null ->
            line.entry.key.rawLeafDecor.prefix = KeptText.from(text.withoutLeadingBlankLines())
    }
    return kept
}

/**
 * Keeps the lines above the first header that removing [item] takes out of the document, in [kept], and takes them
 * from [item], so storing it again does not print them twice. The lines above its later headers, those of the tables
 * under it and of later array elements, belong to it and go too.
 */
internal fun keepHeaderLinesOf(
    item: Item,
    kept: MutableList<KeptLines>,
    takesAttached: Boolean = false,
) {
    val parts = mutableListOf<HeaderPart>()
    collectHeaderParts(item, parts)
    keepAboveFirstHeader(parts, kept, takesAttached = takesAttached)
}

/**
 * Keeps the lines above the header of [table], removed alone, in [kept]; see the [Item] overload. A removal that
 * [takesAttached] keeps only those up to the last blank line, and the table keeps the comment lines right above it.
 */
internal fun keepHeaderLinesOf(
    table: StandardTable,
    isArrayOfTables: Boolean,
    kept: MutableList<KeptLines>,
    takesAttached: Boolean = false,
) {
    val parts = mutableListOf<HeaderPart>()
    collectHeaderParts(table, isArrayOfTables, parts)
    keepAboveFirstHeader(parts, kept, takesAttached = takesAttached)
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
 * [leave] takes the lines it is given, those the document keeps, from the start of the removed item's.
 */
private class HeaderPart(
    val position: Long?,
    val linesAbove: String,
    val isHeader: Boolean,
    val leave: (String) -> Unit,
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
        parts += HeaderPart(table.position, above, isHeader = true) { left ->
            decor.prefix = KeptText.from(decor.prefix?.textOrNull.orEmpty().removePrefix(left))
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
    takesAttached: Boolean = false,
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
        val left = if (takesAttached && part.isHeader) splitLinesAbove(part.linesAbove).free else part.linesAbove
        if (left.isEmpty()) continue
        kept += KeptLines(left, part.position)
        part.leave(left)
    }
}
