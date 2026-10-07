package com.fromwau.kotoml

/**
 * The comments an item owns under the comment rule, and the free lines next to it, as stored text: a comment belongs
 * to the item directly below it, with no blank line between, or to the item on its line.
 */
internal data class ItemComments(
    /** Whole lines above [attachedAbove] up to the item before, the last of them blank: they belong to no item. */
    val freeAbove: String = "",
    /** The comment lines directly above the item's first line, with no blank line between. */
    val attachedAbove: String = "",
    /** The comment ending the line an array or inline table value opens on, after `[` or `{`. */
    val afterOpening: String = "",
    /** The comment ending the item's last line, with the spacing before it. */
    val sameLine: String = "",
    /** Whole lines between the item's value and the comma after it, which stands on a later line. */
    val attachedBelow: String = "",
    /** Whole lines between the last item in brackets or braces and the closing one, which belong to no item. */
    val freeBelow: String = "",
) {
    companion object {
        val NONE: ItemComments = ItemComments()
    }
}

/** Whole lines above an item: [free] ends with the last blank line among them, [attached] is the block after it. */
internal data class LinesAbove(
    val free: String,
    val attached: String,
)

/** Cuts whole [lines] above an item after the last blank line among them. */
internal fun splitLinesAbove(lines: String): LinesAbove {
    var cut = 0
    var start = 0
    while (start < lines.length) {
        val end = lines.indexOf('\n', start).let { if (it < 0) lines.length else it + 1 }
        if (lines.substring(start, end).isBlank()) cut = end
        start = end
    }
    return LinesAbove(lines.substring(0, cut), lines.substring(cut))
}

/** The comments the item in [this] entry's slot owns: for one printing several lines, those of its first. */
internal fun KeyValue.itemComments(): ItemComments {
    val table = item.standardTableOrNull
    val array = item.arrayOfTablesOrNull
    return when {
        table != null && !table.printsDotted -> table.headerComments()
        array != null -> array.firstOrNull()?.headerComments() ?: ItemComments.NONE
        else -> lineComments()
    }
}

/** The comments a `[t]` or `[[a]]` header owns; a table that prints no header owns none. */
internal fun StandardTable.headerComments(): ItemComments {
    val isArrayElement = holder?.arrayOwner is ArrayOfTables
    if (isDocumentRoot || printsDotted || !printsHeader(isArrayElement)) return ItemComments.NONE
    val above = splitLinesAbove(gapOf(rawDecor.prefix.text(), startsMidLine = false).lines)
    return ItemComments(
        freeAbove = above.free,
        attachedAbove = above.attached,
        sameLine = commentIn(rawDecor.suffix.text()),
    )
}

/** The comments the element at [index] owns among the elements of this array. */
internal fun TomlArray.elementComments(index: Int): ItemComments {
    val elements = values.mapNotNull { it.valueOrNull }
    val value = elements.getOrNull(index) ?: return ItemComments.NONE
    return bracedComments(bracedGaps(), index, index, value)
}

// The entry's lines stand in the nearest table or braces that is not dotted, among the lines of its other entries.
private fun KeyValue.lineComments(): ItemComments {
    var container: Any = pairs?.owner ?: return ItemComments.NONE
    while (container.isDotted()) container = parentOf(container) ?: break
    val lines = when (container) {
        is StandardTable -> container.bodyLines()
        is InlineTable -> container.bodyLines()
        else -> return ItemComments.NONE
    }
    val values = lines.filterIsInstance<VisualValue>()
    val first = values.indexOfFirst { line -> line.path.any { it === key } }
    if (first < 0) return ItemComments.NONE
    val last = values.indexOfLast { line -> line.path.any { it === key } }
    val value = item.valueOrNull?.takeUnless { it.isDottedInline() }
    if (container is InlineTable) return bracedComments(container.bracedGaps(lines), first, last, value)
    val above = splitLinesAbove(gapOf(textAbove(lines, values[first]), startsMidLine = false).lines)
    val line = values[last]
    val suffix = line.value.rawDecor.suffix.text()
    return ItemComments(
        freeAbove = above.free,
        attachedAbove = above.attached,
        afterOpening = value?.commentAfterOpening().orEmpty(),
        sameLine = if (value == null) "" else commentIn((suffix + line.lineEnd).firstLine()),
    )
}

private fun Any.isDotted(): Boolean = when (this) {
    is StandardTable -> printsDotted
    is InlineTable -> isDotted
    else -> false
}

private fun Value.isDottedInline(): Boolean = this is Value.InlineTable && value.isDotted

// On a table's lines, the lines kept from removed keys print right above the line after them.
private fun textAbove(
    lines: List<BodyLine>,
    line: VisualValue,
): String {
    val index = lines.indexOf(line)
    val start = (index - 1 downTo 0).firstOrNull { lines[it] is VisualValue }?.plus(1) ?: 0
    val kept = lines.subList(start, index).joinToString("") { (it as KeptLines).text }
    return kept + line.start()
}

private fun VisualValue.start(): String = (lineStart ?: entry.key.rawLeafDecor.prefix).text()

/**
 * The comments of the items [first] to [last], one item's lines, among items in brackets or braces whose [gaps] are
 * the text before each item and, last, the text before the closing bracket.
 */
private fun bracedComments(
    gaps: List<BracedGap>,
    first: Int,
    last: Int,
    value: Value?,
): ItemComments {
    val above = splitLinesAbove(gaps[first].lines)
    val after = gaps[last + 1]
    return ItemComments(
        freeAbove = above.free,
        attachedAbove = above.attached,
        afterOpening = value?.commentAfterOpening().orEmpty(),
        sameLine = if (value == null) "" else commentIn(gapOf(after.text, startsMidLine = true).lineEnd),
        attachedBelow = if (after.commaOnLaterLine) gapOf(after.beforeComma, startsMidLine = true).lines else "",
        freeBelow = if (last + 1 == gaps.lastIndex) after.lines else "",
    )
}

private fun Value.commentAfterOpening(): String {
    val gaps = when (this) {
        is Value.Array -> value.bracedGaps()
        is Value.InlineTable -> value.bracedGaps(value.bodyLines())
        else -> return ""
    }
    return commentIn(gapOf(gaps.first().text, startsMidLine = true).lineEnd)
}

private fun TomlArray.bracedGaps(): List<BracedGap> {
    val elements = values
        .mapNotNull { it.valueOrNull }
        .map { BracedItem(it.rawDecor.prefix.text(), it.rawDecor.suffix.text()) }
    return gapsBetween(elements, "", trailingComma, rawTrailing.text())
}

// Kept lines print after the comma ending the entry before them, as encodeInlineTable prints them.
private fun InlineTable.bracedGaps(lines: List<BodyLine>): List<BracedGap> {
    val entries = mutableListOf<BracedItem>()
    var kept = ""
    for (line in lines) {
        when (line) {
            is KeptLines -> kept += line.text
            is VisualValue -> {
                entries += BracedItem(line.start(), line.value.rawDecor.suffix.text(), kept)
                kept = ""
            }
        }
    }
    return gapsBetween(entries, kept, trailingComma, rawTrailing.text())
}

/** An item in brackets or braces as the text around it: [keptBefore] prints after the comma before [prefix]. */
private class BracedItem(
    val prefix: String,
    val suffix: String,
    val keptBefore: String = "",
)

/**
 * The text between two items, or an item and a bracket: [beforeComma], the end of the item before, then the comma
 * after it when [hasComma], then [afterComma], up to the next item or the closing bracket.
 */
private class BracedGap(
    val beforeComma: String,
    val hasComma: Boolean,
    val afterComma: String,
) {
    val text: String
        get() = beforeComma + (if (hasComma) "," else "") + afterComma

    /** Whether the comma stands on a line after the one the item before ends on. */
    val commaOnLaterLine: Boolean
        get() = hasComma && '\n' in beforeComma

    /** The whole lines that belong to no item before: those before a comma on a later line belong to that item. */
    val lines: String
        get() = gapOf(if (commaOnLaterLine) afterComma else text, startsMidLine = true).lines
}

private fun gapsBetween(
    items: List<BracedItem>,
    keptAtEnd: String,
    trailingComma: Boolean,
    trailing: String,
): List<BracedGap> {
    val gaps = mutableListOf<BracedGap>()
    var before = ""
    for ((index, item) in items.withIndex()) {
        gaps += BracedGap(before, hasComma = index > 0, afterComma = item.keptBefore + item.prefix)
        before = item.suffix
    }
    gaps += BracedGap(before, hasComma = trailingComma && items.isNotEmpty(), afterComma = keptAtEnd + trailing)
    return gaps
}

/** Text between two items: the rest of the line it starts on, and the whole lines after that. */
private class Gap(
    val lineEnd: String,
    val lines: String,
)

/** Cuts [text] into its lines; text that [startsMidLine] begins after an item or an opening bracket. */
private fun gapOf(
    text: String,
    startsMidLine: Boolean,
): Gap {
    val firstBreak = text.indexOf('\n')
    val lastBreak = text.lastIndexOf('\n')
    if (firstBreak < 0) return Gap("", "")
    if (!startsMidLine) return Gap("", text.substring(0, lastBreak + 1))
    return Gap(text.substring(0, text.lineEndBefore(firstBreak)), text.substring(firstBreak + 1, lastBreak + 1))
}

// A comma ends the item before the comment after it, so the comment starts after the comma.
private fun commentIn(lineEnd: String): String {
    val hash = lineEnd.indexOf('#')
    if (hash < 0) return ""
    return lineEnd.substring(lineEnd.lastIndexOf(',', hash) + 1)
}

private fun String.firstLine(): String {
    val firstBreak = indexOf('\n')
    return if (firstBreak < 0) this else substring(0, lineEndBefore(firstBreak))
}

private fun KeptText?.text(): String = this?.textOrNull.orEmpty()
