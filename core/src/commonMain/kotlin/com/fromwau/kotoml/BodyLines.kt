package com.fromwau.kotoml

/**
 * Turns the lines from [start] on, spaced for the braces of the dotted inline table [inline], into lines of a table.
 * The lines above [entry]'s key, which holds the table, print above the first; a comment that followed the opening
 * brace prints on a line of its own above it, and one that followed a comma ends the line before. The comments before
 * and after the closing brace end the last line. An emptied dotted table's text before its `}`, what was above its
 * removed keys, is read as the lines above its `{}`. The lines kept in braces from removed keys print as comment lines,
 * a comment their text starts with ending the line before.
 */
internal fun MutableList<BodyLine>.takeOutOfBraces(
    start: Int,
    entry: KeyValue,
    inline: Value.InlineTable,
) {
    val lines = (start until size).filter { this[it] is VisualValue }
    if (lines.isEmpty()) return
    var firstAbove = entry.key.rawLeafDecor.prefix?.textOrNull.orEmpty()
    val ends = MutableList(lines.size) { "" }
    // A comment joining a line that already ends in one goes on a line of its own below it.
    fun endLine(
        position: Int,
        comment: String,
        lineBreak: String,
        ownLine: Boolean = false,
    ) {
        if (comment.isEmpty()) return
        val value = (this[lines[position]] as VisualValue).value
        val hasComment = value.commentAfter().isNotEmpty() || '#' in ends[position]
        ends[position] += if (ownLine || hasComment) lineBreak + comment.trimStart() else comment
    }
    // Each key under [entry]'s prints its lines above over the first line under it, nested dotted tables' keys too.
    val seen = HashSet<Key>()
    val aboves = MutableList(lines.size) { "" }
    for ((position, index) in lines.withIndex()) {
        val line = this[index] as VisualValue
        val path = line.path
        val keys = path.subList(path.indexOfFirst { it === entry.key } + 1, path.size)
        val keyPrefixes = keys.filter { seen.add(it) }.map { it.rawLeafDecor.prefix?.textOrNull.orEmpty() }
        for (prefix in keyPrefixes + listOfNotNull(line.emptiedDottedTable()?.rawTrailing?.textOrNull)) {
            val carried = prefix.commentBeforeBreak()
            when {
                carried.isEmpty() -> {}
                position == 0 -> firstAbove += carried.trimStart() + prefix.lineBreak()
                else -> endLine(position - 1, carried, prefix.lineBreak())
            }
            val linesAbove = linesAboveOnTableLines(KeptText.from(prefix))
            aboves[position] += indentedCommentLines(linesAbove, "", prefix.lineBreak())
        }
    }
    // The comment after the last value of a dotted table inside this one, held before its `}`, ends that line.
    val lastOfNested = HashMap<InlineTable, Int>()
    for ((position, index) in lines.withIndex()) {
        val owner = (this[index] as VisualValue).entry.pairs?.owner as? InlineTable ?: continue
        if (owner !== inline.value && owner.isDotted) lastOfNested[owner] = position
    }
    for ((nested, position) in lastOfNested) {
        val text = nested.rawTrailing.textOrNull.orEmpty()
        endLine(position, text.commentBeforeBreak(), text.lineBreak())
    }
    // Kept lines sort among the values only later, so the value printed before one is found in print order.
    val positions = lines.withIndex().associate { (position, index) -> this[index] to position }
    var before: Int? = null
    for (line in inPrintOrder(subList(start, size).toList())) {
        if (line !is KeptLines) {
            before = positions[line]
            continue
        }
        val text = line.text
        val lineBreak = text.lineBreak()
        val comment = text.substringBefore('\n').trimEnd('\r').takeIf { '#' in it }.orEmpty()
        val ownLine = if (comment.isNotEmpty() && before == null) comment.trimStart() + lineBreak else ""
        before?.let { endLine(it, comment, lineBreak) }
        this[indexOf(line)] = KeptLines(ownLine + keptLinesOnTableLines(text), line.order)
    }
    val last = lines.lastIndex
    val trailing = if (inline.value.items.size == 0) "" else inline.value.rawTrailing.textOrNull.orEmpty()
    val lineBreak = trailing.lineBreak()
    endLine(last, trailing.commentBeforeBreak(), lineBreak)
    val linesBefore = linesAboveOnTableLines(KeptText.from(trailing)).removeSuffix(lineBreak)
    if (linesBefore.isNotEmpty()) ends[last] += lineBreak + linesBefore
    endLine(last, inline.commentAfter(), lineBreak, ownLine = '\n' in trailing)
    for ((position, index) in lines.withIndex()) {
        val line = this[index] as VisualValue
        val above = (if (position == 0) firstAbove else "") + aboves[position]
        this[index] = line.outOfBraces(above, ends[position])
    }
}

/** The dotted table this line prints empty as `{}`, or null when it prints a value. */
private fun VisualValue.emptiedDottedTable(): InlineTable? =
    entry.item.inlineTableOrNull?.takeIf { it.isDotted && it.items.size == 0 }

/** The comment that ends the line this text starts on, with its spacing, or "" when it holds none. */
private fun String.commentBeforeBreak(): String {
    if ('\n' !in this) return ""
    return substringBefore('\n').trimEnd('\r').takeIf { '#' in it }.orEmpty()
}

internal fun List<BodyLine>.indexOfFirstValue(from: Int): Int =
    (from until size).firstOrNull { this[it] is VisualValue } ?: -1

/** A value as it prints under a header: the key path that reaches it, and the entry that holds it. */
internal class VisualValue(
    val path: List<Key>,
    val value: Value,
    val entry: KeyValue,
    private val placedAt: Long? = null,
    // For a line printed away from where its spacing was made: the text it starts with, in place of its key's.
    val lineStart: KeptText? = null,
    // Whether the value's spacing was made for braces, so only a comment after it carries over.
    val spacedForBraces: Boolean = false,
    // Comment text ending the line, after the value's own.
    val lineEnd: String = "",
    // Whether the key takes the default spacing before `=`, its own being a header's.
    val defaultKeySpacing: Boolean = false,
    // The spacing before `=` in place of its key's.
    val keySpacing: KeptText? = null,
) : BodyLine {
    override val order: Long?
        get() = placedAt ?: entry.order

    /** This line printed on a table's lines though spaced for braces, starting with [above] and ending with [end]. */
    fun outOfBraces(
        above: String,
        end: String,
    ): VisualValue {
        val start = KeptText.from(above)
        return VisualValue(path, value, entry, placedAt, start, spacedForBraces = true, lineEnd = end)
    }

    /** This line starting with [spacing] in place of its key's own. */
    fun startingWith(spacing: KeptText): VisualValue =
        VisualValue(path, value, entry, placedAt, spacing, spacedForBraces, lineEnd, defaultKeySpacing, keySpacing)

    /** This line with [above] over its lines above, or in their place when not [ownLinesAbove], and [end] ending it. */
    fun withLines(
        above: String,
        end: String,
        ownLinesAbove: Boolean,
    ): VisualValue {
        val own = (if (ownLinesAbove) lineStart ?: path.last().rawLeafDecor.prefix else lineStart)?.textOrNull.orEmpty()
        val start = above + own
        // A line under the table's own key, made empty, has that key's header spacing.
        val defaultKey = !ownLinesAbove || defaultKeySpacing
        return VisualValue(
            path,
            value,
            entry,
            placedAt,
            KeptText.from(start),
            spacedForBraces,
            lineEnd + end,
            defaultKey,
            keySpacing,
        )
    }

    /** The path's keys as spelled on this value's own line. */
    fun spelledPath(): List<Key> {
        val spelling = entry.pathSpelling ?: return path
        val parents = path.subList(0, path.size - 1)
        return spelling.resolve(parents) + path.last()
    }
}

/**
 * Orders [lines] as they were read, a line without a recorded place staying right after the one before it. Lines
 * read in order are left as they are, so this only moves interleaved dotted keys back into place.
 */
internal fun inPrintOrder(lines: List<BodyLine>): List<BodyLine> {
    if (lines.none { it.order != null }) return lines
    var previous = Long.MIN_VALUE
    val keyed = lines.map { line ->
        val order = line.order ?: previous
        previous = order
        order to line
    }
    return keyed.sortedBy { it.first }.map { it.second }
}

/**
 * The line a dotted table left holding [pairs] empty prints, as `{}` under its key spaced by
 * [KeyValuePairs.emptyTableDecor], below the lines kept from its removed keys so that a comment above them stays above
 * it.
 */
internal fun emptyTableLine(
    path: List<Key>,
    entry: KeyValue,
    pairs: KeyValuePairs,
): VisualValue {
    val belowKept = pairs.keptLines.mapNotNull { it.order }.maxOrNull()
    // A new table on each read, so what a caller puts in it never prints; only its spacing lasts.
    val value = Value.InlineTable(InlineTable(pairs.emptyTableDecor))
    val keyDecor = pairs.emptyLineKeyDecor
    return VisualValue(path.toList(), value, entry, belowKept, keyDecor.prefix, keySpacing = keyDecor.suffix)
}
