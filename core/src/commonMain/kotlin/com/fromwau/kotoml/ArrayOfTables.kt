package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map

/**
 * A sequence of tables, each under its own `[[header]]`.
 *
 * A table added here is stored as a copy, so changing the original afterwards leaves the array as it is.
 *
 * TOML has no header for an array holding no tables, so one left empty prints as `key = []` and reads back as an empty
 * array; it prints its `[[header]]`s again once it holds a table.
 *
 * A [StandardTable] has no content equality, so `contains(table)` and `indexOf(table)` find only that same table
 * object. Compare what the tables hold instead, as in
 * `tables.indexOfFirst { it.getOrNull("name")?.stringOrNull == "a" }`.
 */
public class ArrayOfTables() : Iterable<StandardTable> {
    internal var span: Span? = null

    /** The slot holding this array, when it is stored somewhere; see [Nesting]. */
    internal var holder: Item? = null

    /** For a [copy], the outermost table of the document it was read from; see [copiesWithinDocument]. */
    internal var copiedWithin: StandardTable? = null

    // Slots that refuse anything but a table; held as items so indexing an item hands out the same slots.
    internal val values: MutableList<Item> = mutableListOf()

    // Lines above the headers of tables removed from this array, printed among the tables by their position.
    internal val keptHeaderLines: MutableList<KeptLines> = mutableListOf()

    /**
     * An inline array of inline tables holding copies of these tables, formatted anew; this stays as it is.
     *
     * @return [TomlEditError.TooDeep] when its tables, nested as inline tables, would be deeper than the parser reads.
     */
    public fun toTomlArray(): Result<TomlArray, TomlEditError> = deepCopy().moveToTomlArray().refusedWhenTooDeep()

    /** Moves the tables into a new inline array of inline tables, formatted anew, leaving this empty. */
    internal fun moveToTomlArray(): TomlArray {
        val array = TomlArray()
        for (slot in values) {
            array.append(slot.moveToValue())
            slot.arrayOwner = null
        }
        values.clear()
        array.resetFormatting()
        return array
    }

    /**
     * Moves the tables into a new array of inline tables that keeps their comments, leaving this empty, for a line
     * indented by [indent]. Comments on or between the headers, or tables [layout] puts across lines, put it across
     * lines, a table a line: the comment lines above a later header go above its table, and a header comment after
     * its comma, or after `{` for a table across lines, which keeps its comments inside; a table on one line loses
     * them. With neither it goes on one line, formatted anew.
     */
    internal fun moveToTomlArrayKeepingComments(
        layout: InlineLayout,
        indent: String,
    ): TomlArray {
        val tables = toList()
        val acrossLines = layout.multiline
        val keepsComments = acrossLines ||
            tables.withIndex().any { (index, table) ->
                table.headerComment.isNotEmpty() || (index > 0 && '#' in linesAbove(table.rawDecor.prefix))
            }
        if (!keepsComments) return moveToTomlArray()
        val lineBreak = layout.lineBreak
        val elementIndent = indent + INDENT
        val array = TomlArray()
        var carried = ""
        for ((index, slot) in values.withIndex()) {
            val table = tables[index]
            val above = if (index == 0) "" else linesAbove(table.rawDecor.prefix)
            val value = if (acrossLines) {
                Value.InlineTable(table.moveToInlineTableAcrossLines(table.headerComment, layout, elementIndent))
            } else {
                slot.moveToValue()
            }
            val lines = indentedCommentLines(above, elementIndent, lineBreak)
            value.rawDecor.prefix = KeptText.from(carried + lineBreak + lines + elementIndent)
            value.rawDecor.suffix = KeptText.from("")
            carried = if (acrossLines) "" else table.headerComment
            array.append(value)
            slot.arrayOwner = null
        }
        values.clear()
        array.trailingComma = true
        array.trailing = carried + lineBreak + indent
        return array
    }

    internal fun resolveSpans(input: SourceText) {
        span = null
        for (value in values) value.resolveSpans(input)
    }

    override fun iterator(): Iterator<StandardTable> = values.mapNotNull { it.standardTableOrNull }.iterator()

    /** The number of tables. */
    public val size: Int
        get() = values.size

    /** Whether there are no tables. */
    public fun isEmpty(): Boolean = size == 0

    /** Whether there is a table. */
    public fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Removes every table. The lines above the first `[[header]]` stay, now above the `key = []` the array prints; the
     * comments between the tables go with them.
     */
    public fun clear() {
        removeSlots(values.toList())
    }

    /** The table at [index], or null when the index is out of bounds. */
    public fun getOrNull(index: Int): StandardTable? = values.getOrNull(index)?.standardTableOrNull

    /** The table at [index], or [TomlEditError.IndexOutOfBounds] when there is none. */
    public operator fun get(index: Int): Result<StandardTable, TomlEditError> =
        getOrNull(index)?.let { Ok(it) } ?: Err(TomlEditError.IndexOutOfBounds(index, size))

    /**
     * Appends a copy of [table], which prints under its own `[[header]]`, even when it was dotted.
     *
     * @return [TomlEditError.TooDeep], storing nothing, when [table] would nest deeper than the parser reads.
     */
    public fun add(table: StandardTable): EmptyResult<TomlEditError> {
        tooDeepError(this, null, Item(table))?.let { return Err(it) }
        append(copyToStore(table))
        return Ok(Unit)
    }

    // A copy from another place, in this document or another, leaves the comments of the header it was read under.
    private fun copyToStore(table: StandardTable): StandardTable = table.deepCopy().also { copy ->
        if (copiesFromElsewhere(table, this)) {
            copy.rawDecor.dropReadComments(isHeader = true)
            // It prints after the element before it, not where its source printed.
            forgetPlacement(Item(copy))
        }
    }

    /** Appends [table] itself, for a caller that built it and holds no other reference to it. */
    internal fun append(table: StandardTable) {
        values.add(Item.inArrayOfTables(table, this))
    }

    /**
     * Inserts a copy of [table] at [index], shifting later tables right.
     *
     * @return [TomlEditError.IndexOutOfBounds], with nothing inserted, when [index] is negative or greater
     *   than [size], or [TomlEditError.TooDeep] when [table] would nest deeper than the parser reads.
     */
    public fun add(
        index: Int,
        table: StandardTable,
    ): EmptyResult<TomlEditError> {
        if (index < 0 || index > values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        tooDeepError(this, null, Item(table))?.let { return Err(it) }
        values.add(index, Item.inArrayOfTables(copyToStore(table), this))
        return Ok(Unit)
    }

    /**
     * Replaces the table at [index] with [table], which takes over the old table's place among the document's tables
     * and its header decor: the comment and blank lines above its `[[header]]` and the comment after it on its line.
     * See [replace].
     *
     * In the `tables[index] = table` form the result is discarded, so an [index] past the end does nothing; call
     * `tables.set(index, table)` to see the [TomlEditError.IndexOutOfBounds].
     */
    public operator fun set(
        index: Int,
        table: StandardTable,
    ): Result<StandardTable, TomlEditError> = replace(index, table)

    /**
     * Replaces the table at [index] with a copy of [table].
     *
     * @param keepDecor whether the copy takes over the old table's [StandardTable.position] and header decor, the
     *   comment and blank lines above its `[[header]]` and the comment after it on its line; false keeps [table]'s
     *   own position and decor.
     * @return the old table; [TomlEditError.TooDeep], with nothing replaced, when [table] would nest deeper than the
     *   parser reads; or [TomlEditError.IndexOutOfBounds], with nothing replaced, when there is no table
     *   at [index].
     */
    public fun replace(
        index: Int,
        table: StandardTable,
        keepDecor: Boolean = true,
    ): Result<StandardTable, TomlEditError> {
        val old = getOrNull(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        tooDeepError(this, null, Item(table))?.let { return Err(it) }
        val stored = copyToStore(table)
        if (keepDecor) {
            stored.rawDecor.assign(old.rawDecor)
            stored.placeAt(old.position, old.positionFromText)
        }
        values.set(index, Item.inArrayOfTables(stored, this)).arrayOwner = null
        return Ok(old)
    }

    /**
     * Removes the table at [index]: its `[[header]]` line and its body, with the tables under it and the comment and
     * blank lines above their headers. The lines above its own `[[header]]` stay where they print, now above
     * whatever follows. Upstream drops those lines with the table.
     *
     * @return the removed table, or [TomlEditError.IndexOutOfBounds] when there is no table at [index].
     */
    public fun removeAt(index: Int): Result<StandardTable, TomlEditError> {
        val removed = getOrNull(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        keepHeaderLinesOf(removed, isArrayOfTables = true, keptHeaderLines)
        values.removeAt(index).arrayOwner = null
        return Ok(removed)
    }

    /**
     * Keeps only the tables [predicate] accepts, in order, visiting each once. The lines above the `[[header]]` of a
     * removed table stay, as [removeAt] keeps them, unless the table before it is removed too: the comments between
     * removed tables go with them.
     *
     * @return whether any table was removed.
     */
    public fun retainAll(predicate: (StandardTable) -> Boolean): Boolean = removeSlots(slotsWhere { !predicate(it) })

    /**
     * Removes the tables [predicate] accepts, in order, visiting each once. The lines above the `[[header]]` of a
     * removed table stay, as [removeAt] keeps them, unless the table before it is removed too: the comments between
     * removed tables go with them.
     *
     * @return whether any table was removed.
     */
    public fun removeAll(predicate: (StandardTable) -> Boolean): Boolean = removeSlots(slotsWhere(predicate))

    private fun slotsWhere(predicate: (StandardTable) -> Boolean): List<Item> =
        values.filter { item -> item.standardTableOrNull?.let(predicate) ?: false }

    private fun removeSlots(removed: List<Item>): Boolean {
        val removedSlots = removed.toHashSet()
        for ((index, item) in values.withIndex()) {
            val startsRun = index == 0 || values[index - 1] !in removedSlots
            if (item in removedSlots && startsRun) {
                item.standardTableOrNull?.let { keepHeaderLinesOf(it, isArrayOfTables = true, keptHeaderLines) }
            }
        }
        for (item in removed) item.arrayOwner = null
        values.removeAll { it in removedSlots }
        return removed.isNotEmpty()
    }

    /**
     * Appends a copy of every table of [tables].
     *
     * @return [TomlEditError.TooDeep], appending none of them, when one would nest deeper than the parser reads.
     */
    public fun addAll(tables: Iterable<StandardTable>): EmptyResult<TomlEditError> {
        val added = tables.toList()
        for (table in added) tooDeepError(this, null, Item(table))?.let { return Err(it) }
        for (table in added) append(table.deepCopy())
        return Ok(Unit)
    }

    /**
     * A deep copy, stored nowhere: exactly what storing this array of tables would store. A copy of an array of
     * tables read from a document leaves the comment after its first header and the comment lines above that header
     * behind and takes the comments inside it, those of the later headers included. Comments a caller set through
     * an element's `decor` stay. A copy of an array of tables stored nowhere, built in code or returned by `remove`,
     * keeps all its comments.
     */
    public fun copy(): ArrayOfTables = deepCopy().also { copy ->
        if (isStored(this)) {
            copy.copiedWithin = outermostTableOf(this)
            copy.firstOrNull()?.rawDecor?.dropReadComments(isHeader = true)
        } else {
            copy.copiedWithin = copiedWithin
        }
    }

    /** A deep copy that keeps every comment. */
    internal fun deepCopy(): ArrayOfTables {
        val copy = ArrayOfTables()
        copy.span = span
        for (table in this) copy.values.add(Item.inArrayOfTables(table.deepCopy(), copy))
        keptHeaderLines.mapTo(copy.keptHeaderLines) { it.copy() }
        return copy
    }

    /** The tables as an inline array; without a header there is no better way to print them. */
    override fun toString(): String = deepCopy().moveToTomlArray().toString()
}

/** An array of copies of [tables], in order; see [toArrayOfTables]. */
public fun arrayOfTablesOf(vararg tables: StandardTable): Result<ArrayOfTables, TomlEditError> =
    tables.asIterable().toArrayOfTables()

/**
 * An array of copies of these tables, in order.
 *
 * @return [TomlEditError.TooDeep] when a table would nest deeper than the parser reads in an array of tables under a
 *   document's root.
 */
public fun Iterable<StandardTable>.toArrayOfTables(): Result<ArrayOfTables, TomlEditError> {
    val array = ArrayOfTables()
    return array.addAll(this).map { array }
}
