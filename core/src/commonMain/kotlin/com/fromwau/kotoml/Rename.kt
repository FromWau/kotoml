package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok

/**
 * Renames the key [old] of [table], whose entries are [pairs], to [new], keeping its entry where it prints, as
 * [StandardTable.rename] and [InlineTable.rename] say.
 */
internal fun renameIn(
    table: Table,
    pairs: KeyValuePairs,
    old: String,
    new: String,
): EmptyResult<TomlEditError> {
    val entry = pairs.getEntry(old) ?: return Err(TomlEditError.NotFound(old))
    if (old == new) return Ok(Unit)
    if (pairs.containsKey(new)) return Err(TomlEditError.KeyTaken(new))
    val renamed = entry.key.renamed(new)
    Respelling(entry.key, renamed, headerIndex = keyPathOf(table).size, lineIndex = dottedDepthOf(table))
        .visit(entry.item, onLines = true)
    pairs.rename(old, renamed)
    return Ok(Unit)
}

/** How many dotted tables, [table] first, stand between [table] and the nearest table that is not dotted. */
private fun dottedDepthOf(table: Table): Int {
    var depth = 0
    var current: Any? = table
    while (current is StandardTable && current.isDotted || current is InlineTable && current.isDotted) {
        depth++
        current = parentOf(current)
    }
    return depth
}

/**
 * Gives the spellings of the paths through the renamed key the key's new name: the headers under it, at
 * [headerIndex] of their paths from the root, and the lines of its dotted keys, at [lineIndex] of theirs.
 */
private class Respelling(
    private val old: Key,
    private val renamed: Key,
    private val headerIndex: Int,
    private val lineIndex: Int,
) {
    /** Visits what [item] holds; [onLines] while it prints on the lines of the renamed key's table. */
    fun visit(
        item: Item,
        onLines: Boolean,
    ) {
        item.standardTableOrNull?.let { visit(it, onLines && it.isDotted) }
        item.arrayOfTablesOrNull?.forEach { visit(it, onLines = false) }
        val dotted = item.inlineTableOrNull?.takeIf { onLines && it.isDotted } ?: return
        for (entry in dotted.items.entries) {
            entry.pathSpelling?.rename(lineIndex, old, renamed)
            visit(entry.item, onLines = true)
        }
    }

    private fun visit(
        table: StandardTable,
        onLines: Boolean,
    ) {
        table.headerSpelling?.rename(headerIndex, old, renamed)
        for (entry in table.items.entries) {
            if (onLines) entry.pathSpelling?.rename(lineIndex, old, renamed)
            visit(entry.item, onLines)
        }
    }
}
