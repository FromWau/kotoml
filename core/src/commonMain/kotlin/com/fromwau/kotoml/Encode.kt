package com.fromwau.kotoml

// Display writes text as it would appear in a document: a span into the original input is never resolved
// here, so a decor still held as a span prints its default and a spelling held as a span prints the default
// spelling, as upstream's Display does.

internal fun encodeKey(buf: StringBuilder, key: Key) {
    buf.append(key.displaySpelling)
}

internal fun encodeKeyPath(
    buf: StringBuilder,
    path: List<Key>,
    defaultPrefix: String,
    defaultSuffix: String,
    leafDecor: RawDecor,
) {
    for ((index, key) in path.withIndex()) {
        val dottedDecor = key.rawDottedDecor
        val first = index == 0
        val last = index + 1 == path.size

        if (first) {
            leafDecor.prefixEncode(buf, defaultPrefix)
        } else {
            buf.append('.')
            dottedDecor.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
        }

        encodeKey(buf, key)

        if (last) {
            leafDecor.suffixEncode(buf, defaultSuffix)
        } else {
            dottedDecor.suffixEncode(buf, DEFAULT_KEY_PATH_DECOR.second)
        }
    }
}

internal fun encodeFormatted(
    buf: StringBuilder,
    formatted: Formatted<*>,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = formatted.decor,
) {
    decor.prefixEncode(buf, defaultPrefix)
    buf.append(formatted.displaySpelling)
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeArray(
    buf: StringBuilder,
    array: TomlArray,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = array.rawDecor,
) {
    decor.prefixEncode(buf, defaultPrefix)
    buf.append('[')

    var index = 0
    for (item in array.values) {
        val value = item.valueOrNull ?: continue
        if (index == 0) {
            encodeValue(buf, value, DEFAULT_LEADING_VALUE_DECOR.first, DEFAULT_LEADING_VALUE_DECOR.second)
        } else {
            buf.append(',')
            encodeValue(buf, value, DEFAULT_VALUE_DECOR.first, DEFAULT_VALUE_DECOR.second)
        }
        index++
    }
    if (array.trailingComma && array.isNotEmpty()) buf.append(',')

    encodeRaw(buf, array.rawTrailing.textOr(""))
    buf.append(']')
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeInlineTable(
    buf: StringBuilder,
    table: InlineTable,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = table.rawDecor,
) {
    decor.prefixEncode(buf, defaultPrefix)
    buf.append('{')

    val lines = table.bodyLines()
    val lastValue = lines.indexOfLast { it is VisualValue }
    // Kept lines print after the comma that ends the entry before them.
    val kept = StringBuilder()
    var first = true
    for ((index, line) in lines.withIndex()) {
        val child = when (line) {
            is KeptLines -> {
                kept.append(line.text)
                continue
            }
            is VisualValue -> line
        }
        val keyPath = child.spelledPath()
        val value = child.value
        if (!first) buf.append(',')
        first = false
        buf.append(kept)
        kept.clear()
        val inner = if (index == lastValue) DEFAULT_TRAILING_VALUE_DECOR else DEFAULT_VALUE_DECOR
        val keyDecor = keyPath.last().rawLeafDecor
        val leafDecor = child.lineStart?.let { RawDecor(it, keyDecor.suffix) } ?: keyDecor
        encodeKeyPath(buf, keyPath, DEFAULT_INLINE_KEY_DECOR.first, DEFAULT_INLINE_KEY_DECOR.second, leafDecor)
        buf.append('=')
        encodeValue(buf, value, inner.first, inner.second)
    }
    if (table.trailingComma && table.isNotEmpty()) buf.append(',')
    buf.append(kept)

    encodeRaw(buf, table.rawTrailing.textOr(""))
    buf.append('}')
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeValue(
    buf: StringBuilder,
    value: Value,
    defaultPrefix: String,
    defaultSuffix: String,
    decor: RawDecor = value.rawDecor,
) {
    when (value) {
        is Value.String -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Integer -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Float -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Boolean -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Datetime -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix, decor)
        is Value.Array -> encodeArray(buf, value.array, defaultPrefix, defaultSuffix, decor)
        is Value.InlineTable -> encodeInlineTable(buf, value.table, defaultPrefix, defaultSuffix, decor)
    }
}

/** Writes lines, each ending as it ended in the text it came from; a line made by hand ends like the one before. */
internal class LineWriter(val buf: StringBuilder, private var lastEnding: String) {
    /** How the line before ended, which a line made by hand ends with too. */
    val ending: String
        get() = lastEnding

    fun endLine(ending: String?) {
        if (ending != null) lastEnding = ending
        buf.append(lastEnding)
    }
}

private fun encodeKeyValueLine(writer: LineWriter, visual: VisualValue) {
    val buf = writer.buf
    val keyPath = visual.spelledPath()
    val value = visual.value
    var leafDecor = keyPath.last().rawLeafDecor
    var valueDecor = value.rawDecor
    visual.lineStart?.let { start ->
        leafDecor = RawDecor(start, leafDecor.suffix.takeUnless { visual.defaultKeySpacing })
    }
    if (visual.spacedForBraces) {
        // After a value in braces only a comment carries over; the space before `}` does not.
        val comment = valueDecor.suffix?.textOrNull?.takeIf { '#' in it }?.substringBefore('\n')?.trimEnd('\r')
        valueDecor = RawDecor(valueDecor.prefix, comment?.let(KeptText::from))
    }
    encodeKeyPath(buf, keyPath, DEFAULT_KEY_DECOR.first, DEFAULT_KEY_DECOR.second, leafDecor)
    buf.append('=')
    encodeValue(buf, value, DEFAULT_VALUE_DECOR.first, DEFAULT_VALUE_DECOR.second, valueDecor)
    buf.append(visual.lineEnd)
    writer.endLine(visual.entry.lineEnding)
}

private fun encodeBodyLines(writer: LineWriter, table: StandardTable) {
    for (line in table.bodyLines()) {
        when (line) {
            is VisualValue -> encodeKeyValueLine(writer, line)
            is KeptLines -> writer.buf.append(line.text)
        }
    }
}

/** A table's own key/value pairs, one per line: upstream's `Display for Table`. */
internal fun encodeTableBody(buf: StringBuilder, table: StandardTable) {
    encodeBodyLines(LineWriter(buf, "\n"), table)
}

/** What prints among a document's tables, in the order of [position]. */
private sealed class DocumentPart(
    // Where the part stands among the document's tables, or null for one made by hand.
    var slot: Long?,
) {
    // The part a part made by hand prints next to, before it when walked before it and after it otherwise.
    var anchor: DocumentPart? = null

    // Anchors point to parts walked earlier or to slotted parts, so following them ends.
    val position: Long
        get() {
            var part = this
            while (true) {
                part.slot?.let { return it }
                part = part.anchor ?: return 0L
            }
        }
}

private class TableToVisit(
    slot: Long?,
    val table: StandardTable,
    val path: List<Key>,
    val isArray: Boolean,
) : DocumentPart(slot)

private class KeptHeaderLines(
    slot: Long?,
    val text: String,
) : DocumentPart(slot)

/** A whole document, tables in their original order: upstream's `Display for DocumentMut`. */
internal fun encodeDocument(buf: StringBuilder, document: TomlDocument) {
    val root = document.root
    val decor = root.rawDecor
    decor.prefixEncode(buf, DEFAULT_ROOT_DECOR.first)
    val headEnd = buf.length
    val writer = LineWriter(buf, document.lineEnding)

    val collected = DocumentParts()
    collected.visitTable(root, mutableListOf(), isArrayOfTables = false)
    val ordered = collected.parts.sortedWith(
        compareBy({ it !is TableToVisit || it.path.isNotEmpty() }, { it.position }),
    )
    // A document's head is content the first table is set apart from, like keys.
    var firstTable = decor.prefix?.textOrNull.isNullOrEmpty()
    var headerPrinted = false
    val rootPrintsLines = root.visualValues().isNotEmpty()
    for (part in ordered) {
        when (part) {
            is TableToVisit -> {
                val firstHeader = !headerPrinted && part.path.isNotEmpty() && rootPrintsLines
                firstTable = visitTable(writer, part.table, part.path, part.isArray, firstTable, firstHeader)
                if (part.path.isNotEmpty()) headerPrinted = true
            }
            is KeptHeaderLines -> buf.append(part.text)
        }
    }
    decor.suffixEncode(buf, DEFAULT_ROOT_DECOR.second)
    encodeRaw(buf, document.rawTrailing.textOr(""))
    // A head read without a final line break needs one once anything follows it.
    val head = buf.substring(0, headEnd).removePrefix("\uFEFF")
    if (buf.length > headEnd && head.isNotEmpty() && !head.endsWith('\n')) buf.insert(headEnd, writer.ending)
}

/**
 * The parts of a document in the order its tables nest. A table made by hand follows the last table walked, or the
 * lines kept from its parent's removed tables when they stand later, and the elements of an array of tables print in
 * array order.
 */
private class DocumentParts {
    val parts = mutableListOf<DocumentPart>()
    private var lastTable: DocumentPart? = null

    // The kept lines of each table being walked, innermost last.
    private val keptOfParents = ArrayDeque<List<DocumentPart>>()

    fun visitTable(
        table: StandardTable,
        path: MutableList<Key>,
        isArrayOfTables: Boolean,
    ) {
        // A root or an array-of-tables element has nowhere to print its values but under its own header. A dotted
        // table with no lines of its own prints its header's comments where its header would stand.
        if (path.isEmpty() || isArrayOfTables || !table.isDotted || table.keepsHeaderCommentsAmongTables(path.last())) {
            val part = TableToVisit(table.position, table, path.toList(), isArrayOfTables)
            add(part)
            lastTable = part
        }
        val kept = table.keptHeaderLines.map { KeptHeaderLines(it.order, it.text) }
        kept.forEach(::add)

        keptOfParents.addLast(kept)
        for (entry in table.items.entries) {
            val child = entry.item.standardTableOrNull
            val array = entry.item.arrayOfTablesOrNull
            path.add(entry.key)
            if (child != null) visitTable(child, path, isArrayOfTables = false)
            if (array != null) visitArray(array, path)
            path.removeAt(path.size - 1)
        }
        keptOfParents.removeLast()
    }

    private fun visitArray(
        array: ArrayOfTables,
        path: MutableList<Key>,
    ) {
        // An empty array prints `key = []` among its parent's lines, under these lines.
        val kept = array.keptHeaderLines
            .takeIf { array.isNotEmpty() }
            .orEmpty()
            .map { KeptHeaderLines(it.order, it.text) }
        kept.forEach(::add)
        // The lines kept from removed elements stay where they printed, so an element made by hand comes after them.
        keptOfParents.addLast(kept)
        val blocks = array.map { element ->
            val start = parts.size
            visitTable(element, path, isArrayOfTables = true)
            parts.subList(start, parts.size).toList()
        }
        keptOfParents.removeLast()
        placeInArrayOrder(blocks)
    }

    private fun add(part: DocumentPart) {
        if (part.slot == null) {
            val candidates = listOfNotNull(lastTable) + keptOfParents.lastOrNull().orEmpty()
            part.anchor = candidates.maxByOrNull { it.position }
        }
        parts += part
    }
}

/**
 * Hands the slots the [blocks] of one array of tables hold out again in array order, each element's header first and
 * the parts under it right after it in their order, whatever slots a copied table brought along. Parts made by hand
 * ahead of every slotted one print just before the first element that has a slot, so a new first element prints
 * first. An element made by hand prints just before the earliest slotted part under it, or after the elements
 * before it when nothing under it has a slot.
 */
private fun placeInArrayOrder(blocks: List<List<DocumentPart>>) {
    val slotted = blocks.flatMap { block ->
        val header = block.first()
        val under = block
            .drop(1)
            .filter { it.slot != null }
            .sortedBy { it.slot }
        listOfNotNull(header.takeIf { it.slot != null }) + under
    }
    val first = slotted.firstOrNull() ?: return
    val slots = slotted
        .mapNotNull { it.slot }
        .sorted()
        .iterator()
    for (part in slotted) part.slot = slots.next()
    for (part in blocks.flatten().takeWhile { it.slot == null }) part.anchor = first
    var latest: DocumentPart? = null
    for (block in blocks) {
        val header = block.first()
        val earliestUnder = block.filter { it.slot != null }.minByOrNull { it.position }
        if (header.slot == null) (earliestUnder ?: latest)?.let { header.anchor = it }
        // The last of equal parts, since an outer array's pass keeps their walk order when it hands slots out again.
        for (part in block) if (part.position >= (latest?.position ?: Long.MIN_VALUE)) latest = part
    }
}

/**
 * Splits the leaf decor of a header's last key into the part written before `[` and the part inside it:
 * the spec allows only spaces and tabs inside the brackets, so a prefix holding a newline goes before them.
 */
private fun leafDecorBeforeBracket(path: List<Key>): Pair<RawDecor?, RawDecor> {
    val lastKey = path.lastOrNull() ?: return null to RawDecor.EMPTY
    val leafDecor = lastKey.rawLeafDecor
    val needsExtraction = leafDecor.prefix?.textOr(DEFAULT_KEY_PATH_DECOR.first)?.contains('\n') ?: false
    return if (needsExtraction) leafDecor to RawDecor.EMPTY else null to leafDecor
}

private fun StandardTable.isFirstElement(): Boolean =
    (holder?.arrayOwner as? ArrayOfTables)?.firstOrNull()?.let { it === this } ?: true

/** Writes one table's header and body; returns whether no table has been written yet. */
private fun visitTable(
    writer: LineWriter,
    table: StandardTable,
    path: List<Key>,
    isArrayOfTables: Boolean,
    firstTable: Boolean,
    // Whether this is the first header printed, below the root's own key/value lines.
    firstHeader: Boolean,
): Boolean {
    val buf = writer.buf
    var first = firstTable
    // A root or an array-of-tables element prints under its own header, whatever it says about being dotted.
    val dotted = table.isDotted && path.isNotEmpty() && !isArrayOfTables
    if (path.isEmpty()) {
        if (table.visualValues().isNotEmpty()) first = false
    } else if (!dotted && table.printsHeader(isArrayOfTables)) {
        val defaultDecor = if (first) {
            first = false
            "" to DEFAULT_TABLE_DECOR.second
        } else {
            // The blank line above a new header is a line made by hand, so it ends like the line before.
            writer.ending to DEFAULT_TABLE_DECOR.second
        }
        val spelledPath = table.headerSpelling?.resolve(path) ?: path
        val (keyLinesDecor, keyDecor) = leafDecorBeforeBracket(spelledPath)
        // The lines above a key print once, above the first element of the array of tables it names.
        val beforeBracket = keyLinesDecor.takeUnless { isArrayOfTables && !table.isFirstElement() }
        val keyLines = beforeBracket?.prefix?.textOrNull.orEmpty()
        if (table.rawDecor.prefix == null && '#' in keyLines) {
            // The default spacer goes above the key's comments, so they stay right above the header.
            if (keyLines.substringBefore('\n').isNotBlank()) buf.append(defaultDecor.first)
            beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
        } else {
            // The header that opened the file is set apart once key/value lines print above it.
            val opened = firstHeader && table.opensFile && table.rawDecor.prefix?.textOrNull == ""
            if (opened) buf.append(writer.ending)
            beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
            table.rawDecor.prefixEncode(buf, defaultDecor.first)
        }
        buf.append(if (isArrayOfTables) "[[" else "[")
        encodeKeyPath(buf, spelledPath, DEFAULT_KEY_PATH_DECOR.first, DEFAULT_KEY_PATH_DECOR.second, keyDecor)
        buf.append(if (isArrayOfTables) "]]" else "]")
        table.rawDecor.suffixEncode(buf, defaultDecor.second)
        writer.endLine(table.headerLineEnding)
    } else {
        // A header that does not print leaves its comments where it stood.
        buf.append(table.headerLinesAbove(path.last()))
        val comment = table.headerComment.trimStart()
        if (comment.isNotEmpty()) {
            buf.append(comment)
            writer.endLine(table.headerLineEnding)
        }
    }
    // A dotted table's lines print among its parent's.
    if (!dotted) encodeBodyLines(writer, table)
    return first
}
