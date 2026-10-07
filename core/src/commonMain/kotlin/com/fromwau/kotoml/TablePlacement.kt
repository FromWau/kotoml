package com.fromwau.kotoml

/** The tables under [start] and the lines kept among their headers: what has a position among a document's tables. */
internal fun placedParts(start: Item): List<Any> {
    val parts = mutableListOf<Any>()
    val items = ArrayDeque<Item>()
    items.addLast(start)
    while (items.isNotEmpty()) {
        val item = items.removeLast()
        item.standardTableOrNull?.let { table ->
            parts.add(table)
            parts.addAll(table.keptHeaderLines)
            for (entry in table.items.entries) items.addLast(entry.item)
        }
        item.arrayOfTablesOrNull?.let { tables ->
            parts.addAll(tables.keptHeaderLines)
            tables.values.forEach(items::addLast)
        }
    }
    return parts
}

internal var Any.placement: Long?
    get() = when (this) {
        is StandardTable -> position
        is KeptLines -> order
        else -> null
    }
    set(value) {
        when (this) {
            is StandardTable -> placeAt(value, positionFromText)
            is KeptLines -> order = value
        }
    }

/** Gives the tables [copy] holds positions after every table of the document under [root], keeping their order. */
private fun placeAfterAll(
    root: StandardTable,
    copy: Item,
) {
    val last = placedParts(Item(root)).mapNotNull { it.placement }.maxOrNull() ?: 0L
    val parts = placedParts(copy)
        .filter { it.placement != null }
        .sortedBy { it.placement }
    for ((index, part) in parts.withIndex()) part.placement = last + 1 + index
}

/**
 * Places what [stored], a copy just made to store in [receiver], holds among the document's tables: a table prints
 * with the tables of its new parent, and an array of tables copied [within] its document after every table there.
 */
internal fun placeStored(
    stored: Item,
    within: Boolean,
    receiver: Any,
) {
    if (stored.standardTableOrNull != null) {
        placeUnderNewParent(stored)
    } else if (within) {
        outermostTableOf(receiver)?.let { placeAfterAll(it, stored) }
    }
}

/**
 * Makes the tables [copy] holds print with the tables of the table it is stored in, as tables made by hand do: right
 * after the tables before its key there. Blank lines above its header were spacing where it stood, so it takes the
 * default spacing; a header read right below the line before it stays right below what comes before it.
 */
private fun placeUnderNewParent(copy: Item) {
    val header = copy.standardTableOrNull?.takeUnless { it.isDotted }
    // A table that opened its file had nothing above it to be spaced from; one read close below a line stays close.
    val spacing = header?.rawDecor?.prefix?.textOrNull.orEmpty()
    if (header != null && '#' !in spacing && (header.opensFile || spacing.isNotEmpty())) header.rawDecor.prefix = null
    for (part in placedParts(copy)) {
        if (part is StandardTable) {
            part.opensFile = false
            if (!part.positionFromText) continue
        }
        part.placement = null
    }
}

/** Gives [table], stored in place of [old], the place [old] held among the document's tables and its spacing. */
internal fun takePlaceOf(
    table: StandardTable,
    old: StandardTable,
) {
    table.placeAt(old.position, old.positionFromText)
    table.opensFile = old.opensFile
    if (table.rawDecor.prefix == null) table.rawDecor.prefix = old.rawDecor.prefix?.textOrNull?.let(KeptText::from)
}

/** Drops the positions [copy] brought from where it was read, so it prints where it is stored. */
internal fun forgetPlacement(copy: Item) {
    for (part in placedParts(copy)) part.placement = null
}
