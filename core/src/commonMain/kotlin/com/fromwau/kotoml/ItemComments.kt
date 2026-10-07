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
    /** The comment ending a line that holds only the comma after the item's value. */
    val commaLine: String = "",
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

/** The comments [slot] owns where it is stored: as an entry of a table, an element, or a table of tables. */
internal fun ownCommentsOf(slot: Item): ItemComments {
    val entry = slot.entry
    if (entry != null && entry.isStored) return entry.itemComments()
    return when (val owner = slot.arrayOwner) {
        is TomlArray -> owner.elementComments(owner.values.indexOfFirst { it === slot })
        is ArrayOfTables -> slot.standardTableOrNull?.headerComments() ?: ItemComments.NONE
        else -> ItemComments.NONE
    }
}

/**
 * The comment lines directly above a header that [prefix], a header's decor read from a document, holds; the lines
 * above them up to a blank line belong to no item.
 */
internal fun attachedAboveHeader(prefix: KeptText?): String = splitLinesAbove(linesAbove(prefix)).attached

/**
 * The comment lines a table stored nowhere brings above its header from the document it was read in, taken out of its
 * decor, which then prints the default spacing; "" for a table whose decor a caller set.
 */
internal fun StandardTable.takeAttachedAboveHeader(): String {
    val prefix = rawDecor.prefix?.takeIf { it.isRead } ?: return ""
    val block = attachedAboveHeader(prefix)
    if ('#' !in block) return ""
    rawDecor.prefix = null
    return block
}

/**
 * Keeps of the lines above this header, read from a document, only the comment lines directly above it; read spacing,
 * the empty spacing of a file's first header included, is the place's.
 */
internal fun StandardTable.keepOnlyAttachedAbove() {
    val prefix = rawDecor.prefix?.takeIf { it.isRead || it.textOrNull == "" } ?: return
    rawDecor.prefix = attachedAboveHeader(prefix).takeIf { '#' in it }?.let(KeptText::read)
}

/**
 * This text above an item, its whole lines and the spacing on the item's line, with [lines] in place of the comment
 * lines directly above the item. Text [spacedForBraces] starts with the end of the line before, which stays.
 */
internal fun KeptText.withAttachedAbove(
    lines: String,
    spacedForBraces: Boolean = false,
): KeptText {
    val text = textOrNull.orEmpty()
    val lineBefore = if (spacedForBraces) text.indexOf('\n') + 1 else 0
    val above = wholeLinesAbove(spacedForBraces)
    val onLine = text.substring(lineBefore + above.length)
    return KeptText.from(text.substring(0, lineBefore) + splitLinesAbove(above).free + lines + onLine)
}

/** Puts [block], comment lines a table stored here brings, right above its header; see [KeyValue.takeAttachedAbove]. */
internal fun StandardTable.putAttachedAbove(block: String) {
    if (block.isEmpty()) return
    val lines = indentedCommentLines(block, "", lineEndingOf(this))
    rawDecor.prefix = (rawDecor.prefix ?: KeptText.from(lineEndingOf(this))).withAttachedAbove(lines)
}

/**
 * Keeps of the lines read above this dotted table's first line only the comment lines directly above it, and prints
 * its lines together wherever it is stored, not among others by the order they were read in.
 */
internal fun StandardTable.keepOnlyAttachedAboveFirstLine() {
    keepKeptLinesInFront(bodyLines(), keptLineLists(), DEFAULT_KEY_DECOR.first) { it.path.last().rawLeafDecor }
    forgetOrder()
    firstLineDecor()?.let { it.prefix = it.prefix?.withLinesAbove(attachedOnly = true, spacedForBraces = false) }
}

/** Takes out the comment lines read directly above this dotted table's first line, for the slot it is stored in. */
internal fun StandardTable.takeAttachedAboveFirstLine(): String =
    firstLineDecor()?.takeAttachedAbove(spacedForBraces = false).orEmpty()

/** As [StandardTable.keepOnlyAttachedAboveFirstLine], for dotted keys in braces. */
internal fun InlineTable.keepOnlyAttachedAboveFirstLine() {
    keepKeptLinesInFront(bodyLines(), keptLineLists(), DEFAULT_INLINE_KEY_DECOR.first) { line ->
        val start = line.lineStart
        line.path.firstOrNull { start != null && it.rawLeafDecor.prefix === start }?.rawLeafDecor
            ?: line.entry.key.rawLeafDecor
    }
    forgetOrder()
    firstLineDecor()?.let { it.prefix = it.prefix?.withLinesAbove(attachedOnly = true, spacedForBraces = true) }
}

private fun StandardTable.keptLineLists(): List<MutableList<KeptLines>> = listOf(items.keptLines) +
    items.entries.flatMap { entry -> entry.item.standardTableOrNull?.takeIf { it.isDotted }?.keptLineLists().orEmpty() }

private fun InlineTable.keptLineLists(): List<MutableList<KeptLines>> = listOf(items.keptLines) +
    items.entries.flatMap { entry -> entry.item.inlineTableOrNull?.takeIf { it.isDotted }?.keptLineLists().orEmpty() }

/**
 * Puts the lines kept from removed keys among [lines], held in [lists], in front of the line printed after them, whose
 * decor [decorOf] gives, so they stay in place once the order the lines were read in is forgotten.
 */
private fun keepKeptLinesInFront(
    lines: List<BodyLine>,
    lists: List<MutableList<KeptLines>>,
    keyDefault: String,
    decorOf: (VisualValue) -> RawDecor,
) {
    val pending = mutableListOf<KeptLines>()
    for (line in lines) {
        if (line is KeptLines) {
            pending += line
            continue
        }
        if (pending.isEmpty()) continue
        decorOf(line as VisualValue).prependToPrefix(pending.joinToString("") { it.text }, keyDefault)
        for (list in lists) list.removeAll { kept -> pending.any { it === kept } }
        pending.clear()
    }
}

/** As [StandardTable.takeAttachedAboveFirstLine], for dotted keys in braces. */
internal fun InlineTable.takeAttachedAboveFirstLine(): String =
    firstLineDecor()?.takeAttachedAbove(spacedForBraces = true).orEmpty()

private fun StandardTable.firstLineDecor(): RawDecor? = visualValues().firstOrNull()?.entry?.key?.rawLeafDecor

private fun InlineTable.firstLineDecor(): RawDecor? = visualValues().firstOrNull()?.entry?.key?.rawLeafDecor

private fun RawDecor.takeAttachedAbove(spacedForBraces: Boolean): String {
    val start = prefix?.takeIf { it.isRead } ?: return ""
    val block = splitLinesAbove(start.wholeLinesAbove(spacedForBraces)).attached
    if ('#' !in block) return ""
    prefix = start.withLinesAbove(attachedOnly = false, spacedForBraces)
    return block
}

/** The whole lines this text before an item holds above the item's line; see [withAttachedAbove]. */
private fun KeptText.wholeLinesAbove(spacedForBraces: Boolean): String {
    val text = textOrNull.orEmpty()
    val lineBefore = if (spacedForBraces) text.indexOf('\n') + 1 else 0
    return text.substring(lineBefore, text.lastIndexOf('\n').coerceAtLeast(lineBefore - 1) + 1)
}

/**
 * This text before an item, read or given as it was, keeping of the whole lines above the item's line the comment
 * lines directly above it when [attachedOnly], or none; in braces the end of the line before goes too, since a comment
 * there belongs to what the line holds.
 */
private fun KeptText.withLinesAbove(
    attachedOnly: Boolean,
    spacedForBraces: Boolean,
): KeptText = mapText { text ->
    val above = wholeLinesAbove(spacedForBraces)
    val lineBefore = if (spacedForBraces && '\n' in text) text.lineBreak() else ""
    val kept = if (attachedOnly) splitLinesAbove(above).attached else ""
    lineBefore + kept + text.substringAfterLast('\n')
}

/**
 * Gives an element in brackets whose decor is [old], replaced by one whose decor is this, the comment lines directly
 * above the new one in place of those above the old one, when the old one starts a line; the rest stays as in [old].
 */
internal fun RawDecor.keepAttachedAboveIn(old: RawDecor) {
    val block = prefix?.let { splitLinesAbove(it.wholeLinesAbove(spacedForBraces = true)).attached }.orEmpty()
    val start = old.prefix
    prefix = start
    if ('#' !in block || start == null || '\n' !in start.textOrNull.orEmpty()) return
    val text = start.textOrNull.orEmpty()
    val lines = indentedCommentLines(block, text.substringAfterLast('\n'), text.lineBreak())
    prefix = start.withAttachedAbove(lines, spacedForBraces = true)
}

/** Puts [comment], the same-line comment this copy's source owns, after it when its decor holds no comment there. */
internal fun RawDecor.keepSameLineComment(comment: String) {
    if ('#' !in suffix?.textOrNull.orEmpty() && '#' in comment) suffix = KeptText.read(comment)
}

/**
 * Puts [comment], the comment on a line holding only the comma after this copy's source, after it: on its line when
 * no comment is there or below it, otherwise as the last of the comment lines below it.
 */
internal fun RawDecor.keepCommaLineComment(comment: String) {
    if ('#' !in comment) return
    val text = suffix?.textOrNull.orEmpty()
    if ('#' !in text) {
        suffix = KeptText.read(comment)
        return
    }
    val lineBreak = text.lineBreak()
    val below = commentLinesBelow() + comment.trim()
    suffix = KeptText.read(commentOnLine() + lineBreak + below.joinToString("") { it + lineBreak })
}

/** The same-line comment the slot holding [container] owns, or "" when it is stored nowhere. */
internal fun sameLineCommentOf(container: Any): String = slotOf(container)?.let(::ownCommentsOf)?.sameLine.orEmpty()

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

internal fun VisualValue.start(): String = (lineStart ?: entry.key.rawLeafDecor.prefix).text()

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
        commaLine = after.commaLineComment,
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

    /** The comment ending the line the comma stands on, when the comma has that line to itself. */
    val commaLineComment: String
        get() = if (commaOnLaterLine) commentIn(gapOf(afterComma, startsMidLine = true).lineEnd) else ""

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
