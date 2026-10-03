package com.fromwau.kotoml

// Display writes text as it would appear in a document: a span into the original input is never resolved
// here, so a decor still held as a span prints its default and a repr held as a span prints the default
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
    buf.append(formatted.displayRepr())
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
    if (array.trailingComma && !array.isEmpty()) buf.append(',')

    encodeRaw(buf, array.rawTrailing.toStrWithDefault(""))
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
    if (table.trailingComma && !table.isEmpty()) buf.append(',')
    buf.append(kept)

    encodeRaw(buf, table.rawTrailing.toStrWithDefault(""))
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

private fun encodeBodyLines(writer: LineWriter, table: Table) {
    for (line in table.bodyLines()) {
        when (line) {
            is VisualValue -> encodeKeyValueLine(writer, line)
            is KeptLines -> writer.buf.append(line.text)
        }
    }
}

/** A table's own key/value pairs, one per line: upstream's `Display for Table`. */
internal fun encodeTableBody(buf: StringBuilder, table: Table) {
    encodeBodyLines(LineWriter(buf, "\n"), table)
}

/** What prints among a document's tables, in the order of [position]. */
private sealed interface DocumentPart {
    val position: Long
}

private class TableToVisit(
    override val position: Long,
    val table: Table,
    val path: List<Key>,
    val isArray: Boolean,
) : DocumentPart

private class KeptHeaderLines(
    override val position: Long,
    val text: String,
) : DocumentPart

/** A whole document, tables in their original order: upstream's `Display for DocumentMut`. */
internal fun encodeDocument(buf: StringBuilder, document: TomlDocument) {
    val root = document.root
    val decor = root.rawDecor
    decor.prefixEncode(buf, DEFAULT_ROOT_DECOR.first)
    val writer = LineWriter(buf, document.lineEnding)

    var lastPosition = 0L
    val parts = mutableListOf<DocumentPart>()
    visitNestedTables(
        table = root,
        path = mutableListOf(),
        isArrayOfTables = false,
        onTable = { table, path, isArray ->
            table.position?.let { lastPosition = it }
            parts += TableToVisit(lastPosition, table, path.toList(), isArray)
        },
        onKeptLines = { kept -> parts += KeptHeaderLines(kept.order ?: lastPosition, kept.text) },
    )

    val ordered = parts.sortedWith(compareBy({ it !is TableToVisit || it.path.isNotEmpty() }, { it.position }))
    var firstTable = true
    for (part in ordered) {
        when (part) {
            is TableToVisit -> firstTable = visitTable(writer, part.table, part.path, part.isArray, firstTable)
            is KeptHeaderLines -> buf.append(part.text)
        }
    }
    decor.suffixEncode(buf, DEFAULT_ROOT_DECOR.second)
    encodeRaw(buf, document.rawTrailing.toStrWithDefault(""))
}

private fun visitNestedTables(
    table: Table,
    path: MutableList<Key>,
    isArrayOfTables: Boolean,
    onTable: (Table, List<Key>, Boolean) -> Unit,
    onKeptLines: (KeptLines) -> Unit,
) {
    if (!table.isDotted) onTable(table, path, isArrayOfTables)
    table.keptHeaderLines.forEach(onKeptLines)

    for (entry in table.items.entries()) {
        val child = entry.item.tableOrNull
        val array = entry.item.arrayOfTablesOrNull
        if (child != null) {
            path.add(entry.key)
            visitNestedTables(child, path, false, onTable, onKeptLines)
            path.removeAt(path.size - 1)
        } else if (array != null) {
            for (element in array.values) {
                val tableElement = element.tableOrNull ?: continue
                path.add(entry.key)
                visitNestedTables(tableElement, path, true, onTable, onKeptLines)
                path.removeAt(path.size - 1)
            }
            array.keptHeaderLines.forEach(onKeptLines)
        }
    }
}

/**
 * Splits the leaf decor of a header's last key into the part written before `[` and the part inside it:
 * the spec allows only spaces and tabs inside the brackets, so a prefix holding a newline goes before them.
 */
private fun leafDecorBeforeBracket(path: List<Key>): Pair<RawDecor?, RawDecor> {
    val lastKey = path.lastOrNull() ?: return null to RawDecor.EMPTY
    val leafDecor = lastKey.rawLeafDecor
    val needsExtraction = leafDecor.prefix?.toStrWithDefault(DEFAULT_KEY_PATH_DECOR.first)?.contains('\n') ?: false
    return if (needsExtraction) leafDecor to RawDecor.EMPTY else null to leafDecor
}

/** Writes one table's header and body; returns whether no table has been written yet. */
private fun visitTable(
    writer: LineWriter,
    table: Table,
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
            DEFAULT_TABLE_DECOR
        }
        val spelledPath = table.headerSpelling?.resolve(path.subList(0, path.size - 1))?.plus(path.last()) ?: path
        val (beforeBracket, keyDecor) = leafDecorBeforeBracket(spelledPath)
        beforeBracket?.prefixEncode(buf, DEFAULT_KEY_PATH_DECOR.first)
        table.rawDecor.prefixEncode(buf, defaultDecor.first)
        buf.append(if (isArrayOfTables) "[[" else "[")
        encodeKeyPath(buf, spelledPath, DEFAULT_KEY_PATH_DECOR.first, DEFAULT_KEY_PATH_DECOR.second, keyDecor)
        buf.append(if (isArrayOfTables) "]]" else "]")
        table.rawDecor.suffixEncode(buf, defaultDecor.second)
        writer.endLine(table.headerLineEnding)
    }
    encodeBodyLines(writer, table)
    return first
}
