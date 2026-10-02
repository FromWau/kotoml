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
        val value = item.asValue() ?: continue
        if (index == 0) {
            encodeValue(buf, value, DEFAULT_LEADING_VALUE_DECOR.first, DEFAULT_LEADING_VALUE_DECOR.second)
        } else {
            buf.append(',')
            encodeValue(buf, value, DEFAULT_VALUE_DECOR.first, DEFAULT_VALUE_DECOR.second)
        }
        index++
    }
    if (array.trailingComma() && !array.isEmpty()) buf.append(',')

    encodeRaw(buf, array.rawTrailing.toStrWithDefault(""))
    buf.append(']')
    decor.suffixEncode(buf, defaultSuffix)
}

internal fun encodeInlineTable(buf: StringBuilder, table: InlineTable, defaultPrefix: String, defaultSuffix: String) {
    val decor = table.rawDecor
    decor.prefixEncode(buf, defaultPrefix)
    buf.append('{')

    val children = table.visualValues()
    for ((index, child) in children.withIndex()) {
        val keyPath = child.spelledPath()
        val value = child.value
        if (index != 0) buf.append(',')
        val inner = if (index == children.size - 1) DEFAULT_TRAILING_VALUE_DECOR else DEFAULT_VALUE_DECOR
        val leafDecor = keyPath.last().rawLeafDecor
        encodeKeyPath(buf, keyPath, DEFAULT_INLINE_KEY_DECOR.first, DEFAULT_INLINE_KEY_DECOR.second, leafDecor)
        buf.append('=')
        encodeValue(buf, value, inner.first, inner.second)
    }
    if (table.trailingComma() && !table.isEmpty()) buf.append(',')

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

/** A table's own key/value pairs, one per line: upstream's `Display for Table`. */
internal fun encodeTableBody(buf: StringBuilder, table: Table) {
    val writer = LineWriter(buf, "\n")
    for (visual in table.visualValues()) encodeKeyValueLine(writer, visual)
}

private class TableToVisit(val position: Long, val table: Table, val path: List<Key>, val isArray: Boolean)

/** A whole document, tables in their original order: upstream's `Display for DocumentMut`. */
internal fun encodeDocument(buf: StringBuilder, document: DocumentMut) {
    val root = document.asTable()
    val decor = root.rawDecor
    decor.prefixEncode(buf, DEFAULT_ROOT_DECOR.first)
    val writer = LineWriter(buf, document.lineEnding)

    var lastPosition = 0L
    val tables = mutableListOf<TableToVisit>()
    visitNestedTables(root, mutableListOf(), false) { table, path, isArray ->
        table.position()?.let { lastPosition = it }
        tables += TableToVisit(lastPosition, table, path.toList(), isArray)
    }

    val ordered = tables.sortedWith(compareBy({ it.path.isNotEmpty() }, { it.position }))
    var firstTable = true
    for (visit in ordered) {
        firstTable = visitTable(writer, visit.table, visit.path, visit.isArray, firstTable)
    }
    decor.suffixEncode(buf, DEFAULT_ROOT_DECOR.second)
    encodeRaw(buf, document.rawTrailing.toStrWithDefault(""))
}

private fun visitNestedTables(
    table: Table,
    path: MutableList<Key>,
    isArrayOfTables: Boolean,
    callback: (Table, List<Key>, Boolean) -> Unit,
) {
    if (!table.isDotted()) callback(table, path, isArrayOfTables)

    for (entry in table.items.entries()) {
        val child = entry.item.asTable()
        val array = entry.item.asArrayOfTables()
        if (child != null) {
            path.add(entry.key)
            visitNestedTables(child, path, false, callback)
            path.removeAt(path.size - 1)
        } else if (array != null) {
            for (element in array.values) {
                val tableElement = element.asTable() ?: continue
                path.add(entry.key)
                visitNestedTables(tableElement, path, true, callback)
                path.removeAt(path.size - 1)
            }
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
    val children = table.visualValues()
    // An implicit table with no values of its own is hidden, so deleting every table under it hides it too.
    val isVisibleStdTable = !(table.implicit && children.isEmpty())

    if (path.isEmpty()) {
        if (children.isNotEmpty()) first = false
    } else if (isArrayOfTables || isVisibleStdTable) {
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
    for (visual in children) encodeKeyValueLine(writer, visual)
    return first
}
