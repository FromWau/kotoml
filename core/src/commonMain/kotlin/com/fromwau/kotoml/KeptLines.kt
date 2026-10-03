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
internal fun linesAbove(prefix: RawString?): String {
    val text = prefix?.asStr() ?: return ""
    return text.substring(0, text.lastIndexOf('\n') + 1)
}

// Between the elements of an inline table or an array the break ending a line opens the next prefix, so the
// lines above an element end before its prefix's last break.
internal fun linesAboveElement(prefix: RawString?): String {
    val text = prefix?.asStr() ?: return ""
    return text.substring(0, text.lastIndexOf('\n').coerceAtLeast(0))
}

/** Puts [text] in front of the prefix, which printed [default] while it had none of its own. */
internal fun RawDecor.prependToPrefix(
    text: String,
    default: String,
) {
    prefix = RawString.from(text + (prefix?.toStrWithDefault(default) ?: default))
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
        val pairs = item.tableOrNull?.takeIf { it.isDotted }?.items
            ?: item.inlineTableOrNull?.takeIf { it.isDotted }?.items
            ?: return
        keptAmong += pairs.keptLines
        for (entry in pairs.entries()) collectKeptBodyLines(entry.item)
    }

    operator fun contains(line: BodyLine): Boolean = when (line) {
        is VisualValue -> line.path.first().name in names
        is KeptLines -> line in keptAmong
    }
}

/**
 * Keeps the lines above the [removed] lines of a body printed as [lines], before they are removed. A run of them
 * goes in front of the line printed next, or to [keep] when none follows. In a dotted table, lines read from text
 * each stay at their own place in the reading order instead: it cannot see the lines of the table it prints in.
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
    for (line in lines) {
        if (line in removed) {
            val text = when (line) {
                is VisualValue -> linesAbove(line)
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

/** Keeps the lines above every header that removing [item] takes out of the document, in [kept]. */
internal fun keepHeaderLinesOf(
    item: Item,
    kept: MutableList<KeptLines>,
) {
    item.tableOrNull?.let { keepHeaderLinesOf(it, isArrayOfTables = false, kept) }
    item.arrayOfTablesOrNull?.let { keepHeaderLinesOf(it, kept) }
}

/** Keeps the lines above the headers of every table of [array], in [kept]. */
internal fun keepHeaderLinesOf(
    array: ArrayOfTables,
    kept: MutableList<KeptLines>,
) {
    for (table in array) keepHeaderLinesOf(table, isArrayOfTables = true, kept)
    kept += array.keptHeaderLines
}

/** Keeps the lines above [table]'s header and above the headers of the tables under it, in [kept]. */
internal fun keepHeaderLinesOf(
    table: Table,
    isArrayOfTables: Boolean,
    kept: MutableList<KeptLines>,
) {
    if (!table.isDotted && table.printsHeader(isArrayOfTables)) {
        val text = linesAbove(table.rawDecor.prefix)
        if (text.isNotEmpty()) kept += KeptLines(text, table.position)
    }
    kept += table.keptHeaderLines
    for (entry in table.items.entries()) keepHeaderLinesOf(entry.item, kept)
}
