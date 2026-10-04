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
) {
    val decor = formatted.decor
    decor.prefixEncode(buf, defaultPrefix)
    buf.append(formatted.displaySpelling)
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeArray(buf: StringBuilder, array: TomlArray, defaultPrefix: String, defaultSuffix: String) {
    val decor = array.rawDecor
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

internal fun encodeInlineTable(buf: StringBuilder, table: InlineTable, defaultPrefix: String, defaultSuffix: String) {
    val decor = table.rawDecor
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
        val leafDecor = keyPath.last().rawLeafDecor
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

internal fun encodeValue(buf: StringBuilder, value: Value, defaultPrefix: String, defaultSuffix: String) {
    when (value) {
        is Value.String -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix)
        is Value.Integer -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix)
        is Value.Float -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix)
        is Value.Boolean -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix)
        is Value.Datetime -> encodeFormatted(buf, value.formatted, defaultPrefix, defaultSuffix)
        is Value.Array -> encodeArray(buf, value.array, defaultPrefix, defaultSuffix)
        is Value.InlineTable -> encodeInlineTable(buf, value.table, defaultPrefix, defaultSuffix)
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
    val leafDecor = keyPath.last().rawLeafDecor
    encodeKeyPath(buf, keyPath, DEFAULT_KEY_DECOR.first, DEFAULT_KEY_DECOR.second, leafDecor)
    buf.append('=')
    encodeValue(buf, visual.value, DEFAULT_VALUE_DECOR.first, DEFAULT_VALUE_DECOR.second)
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
    // The table a part made by hand prints next to, before it when walked before it and after it otherwise.
    var anchor: DocumentPart? = null

    val position: Long
        get() = slot ?: anchor?.slot ?: 0L
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
    val writer = LineWriter(buf, document.lineEnding)

    val collected = DocumentParts()
    collected.visitTable(root, mutableListOf(), isArrayOfTables = false)
    val ordered = collected.parts.sortedWith(
        compareBy({ it !is TableToVisit || it.path.isNotEmpty() }, { it.position }),
    )
    var firstTable = true
    for (part in ordered) {
        when (part) {
            is TableToVisit -> firstTable = visitTable(writer, part.table, part.path, part.isArray, firstTable)
            is KeptHeaderLines -> buf.append(part.text)
        }
    }
    decor.suffixEncode(buf, DEFAULT_ROOT_DECOR.second)
    encodeRaw(buf, document.rawTrailing.textOr(""))
}

/**
 * The parts of a document in the order its tables nest. A part made by hand follows the last table walked that
 * has a slot, and the elements of an array of tables print in array order.
 */
private class DocumentParts {
    val parts = mutableListOf<DocumentPart>()
    private var lastTable: DocumentPart? = null

    fun visitTable(
        table: StandardTable,
        path: MutableList<Key>,
        isArrayOfTables: Boolean,
    ) {
        // A root or an array-of-tables element has nowhere to print its values but under its own header.
        if (path.isEmpty() || isArrayOfTables || !table.isDotted) {
            val part = TableToVisit(table.position, table, path.toList(), isArrayOfTables)
            add(part)
            if (part.slot != null) lastTable = part
        }
        for (kept in table.keptHeaderLines) add(KeptHeaderLines(kept.order, kept.text))

        for (entry in table.items.entries) {
            val child = entry.item.standardTableOrNull
            val array = entry.item.arrayOfTablesOrNull
            path.add(entry.key)
            if (child != null) visitTable(child, path, isArrayOfTables = false)
            if (array != null) visitArray(array, path)
            path.removeAt(path.size - 1)
        }
    }

    private fun visitArray(
        array: ArrayOfTables,
        path: MutableList<Key>,
    ) {
        val blocks = array.map { element ->
            val start = parts.size
            visitTable(element, path, isArrayOfTables = true)
            parts.subList(start, parts.size).toList()
        }
        placeInArrayOrder(blocks)
        for (kept in array.keptHeaderLines) add(KeptHeaderLines(kept.order, kept.text))
    }

    private fun add(part: DocumentPart) {
        if (part.slot == null) part.anchor = lastTable
        parts += part
    }
}

/**
 * Hands the slots the [blocks] of one array of tables hold out again in array order, each element keeping the parts
 * under it right after it in their order. Parts made by hand ahead of every slotted one print just before the first
 * element that has a slot, so a new first element prints first.
 */
private fun placeInArrayOrder(blocks: List<List<DocumentPart>>) {
    val slotted = blocks.flatMap { block -> block.filter { it.slot != null }.sortedBy { it.slot } }
    val first = slotted.firstOrNull() ?: return
    val slots = slotted
        .mapNotNull { it.slot }
        .sorted()
        .iterator()
    for (part in slotted) part.slot = slots.next()
    for (part in blocks.flatten().takeWhile { it.slot == null }) part.anchor = first
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

/** Writes one table's header and body; returns whether no table has been written yet. */
private fun visitTable(
    writer: LineWriter,
    table: StandardTable,
    path: List<Key>,
    isArrayOfTables: Boolean,
    firstTable: Boolean,
): Boolean {
    val buf = writer.buf
    var first = firstTable
    if (path.isEmpty()) {
        if (table.visualValues().isNotEmpty()) first = false
    } else if (table.printsHeader(isArrayOfTables)) {
        val defaultDecor = if (first) {
            first = false
            "" to DEFAULT_TABLE_DECOR.second
        } else {
            // The blank line above a new header is a line made by hand, so it ends like the line before.
            writer.ending to DEFAULT_TABLE_DECOR.second
        }
        val spelledPath = table.headerSpelling?.resolve(path.subList(0, path.size - 1))?.plus(path.last()) ?: path
        val (beforeBracket, keyDecor) = leafDecorBeforeBracket(spelledPath)
        val keyLines = beforeBracket?.prefix?.textOrNull.orEmpty()
        if (table.rawDecor.prefix == null && '#' in keyLines) {
            // The default spacer goes above the key's comments, so they stay right above the header.
            if (keyLines.substringBefore('\n').isNotBlank()) buf.append(defaultDecor.first)
            beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
        } else {
            beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
            table.rawDecor.prefixEncode(buf, defaultDecor.first)
        }
        buf.append(if (isArrayOfTables) "[[" else "[")
        encodeKeyPath(buf, spelledPath, DEFAULT_KEY_PATH_DECOR.first, DEFAULT_KEY_PATH_DECOR.second, keyDecor)
        buf.append(if (isArrayOfTables) "]]" else "]")
        table.rawDecor.suffixEncode(buf, defaultDecor.second)
        writer.endLine(table.headerLineEnding)
    }
    encodeBodyLines(writer, table)
    return first
}
