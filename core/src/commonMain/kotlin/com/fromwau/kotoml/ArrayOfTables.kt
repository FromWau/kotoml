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
 *
 * In a document whose [TomlDocument.version] is TOML 1.0, a store that would add what only TOML 1.1 reads, such as a
 * string spelled with a `\e` escape, fails with [TomlEditError.NotInToml10] and stores nothing.
 */
public class ArrayOfTables() : Iterable<StandardTable> {
    internal var span: Span? = null

    /** The slot holding this array, when it is stored somewhere; see [Nesting]. */
    internal var holder: Item? = null

    /** The look of the document this was taken out of, read while it stands at the top of a tree; see [documentOf]. */
    internal var takenFrom: DocumentLook? = null

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
        array.useDefaultSpacing()
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
        removeSlots(values.toList(), takesAttached = false)
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
        storeError(this, null, Item(table))?.let { return Err(it) }
        append(copyToStore(table))
        return Ok(Unit)
    }

    // A copy from another place, in this document or another, leaves the comments of the header it was read under.
    private fun copyToStore(table: StandardTable): StandardTable = table.deepCopy().also { copy ->
        giveSecondsInToml10(this, Item(copy))
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
        storeError(this, null, Item(table))?.let { return Err(it) }
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
        storeError(this, null, Item(table))?.let { return Err(it) }
        val stored = copyToStore(table)
        if (keepDecor) {
            stored.rawDecor.assign(old.rawDecor)
            stored.placeAt(old.position, old.positionFromText)
        }
        values.set(index, Item.inArrayOfTables(stored, this)).leaveArray()
        return Ok(old)
    }

    /**
     * Removes the table at [index], with the comments that belong to it: the comment lines right above its
     * `[[header]]` with no blank line between, the comment after the header, its body, and the tables under it with
     * the comment and blank lines above their headers. Comment lines set apart from the header by a blank line stay
     * where they print.
     *
     * @param force removes the table even when comments would go with it.
     * @return the removed table; [TomlEditError.IndexOutOfBounds] when there is no table at [index]; or
     *   [TomlEditError.CommentsWouldBeLost], changing nothing, when [force] is false and comments would leave the
     *   document with the table, naming the array of tables and how many comments.
     */
    public fun removeAt(
        index: Int,
        force: Boolean = false,
    ): Result<StandardTable, TomlEditError> {
        val removed = getOrNull(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        if (!force) refusalToRemove(listOf(index))?.let { return Err(it) }
        removeSlots(listOf(values[index]), takesAttached = true)
        return Ok(removed)
    }

    /**
     * Keeps only the tables [predicate] accepts, in order, visiting each once. Each removed table takes its comments,
     * as [removeAt] says, and all of them go or none does.
     *
     * @param force removes the tables even when comments would go with them.
     * @return whether any table was removed; or [TomlEditError.CommentsWouldBeLost], removing none, when [force] is
     *   false and comments would leave the document, counting the comments of all.
     */
    public fun retainAll(
        force: Boolean = false,
        predicate: (StandardTable) -> Boolean,
    ): Result<Boolean, TomlEditError> = removeChecked(slotsWhere { !predicate(it) }, force)

    /**
     * Removes the tables [predicate] accepts, in order, visiting each once. Each removed table takes its comments, as
     * [removeAt] says, and all of them go or none does.
     *
     * @param force removes the tables even when comments would go with them.
     * @return whether any table was removed; or [TomlEditError.CommentsWouldBeLost], removing none, when [force] is
     *   false and comments would leave the document, counting the comments of all.
     */
    public fun removeAll(
        force: Boolean = false,
        predicate: (StandardTable) -> Boolean,
    ): Result<Boolean, TomlEditError> = removeChecked(slotsWhere(predicate), force)

    private fun slotsWhere(predicate: (StandardTable) -> Boolean): List<Item> =
        values.filter { item -> item.standardTableOrNull?.let(predicate) ?: false }

    private fun removeChecked(
        removed: List<Item>,
        force: Boolean,
    ): Result<Boolean, TomlEditError> {
        if (!force) refusalToRemove(removed.map { slot -> values.indexOfFirst { it === slot } })?.let { return Err(it) }
        return Ok(removeSlots(removed, takesAttached = true))
    }

    private fun refusalToRemove(indices: List<Int>): TomlEditError? =
        lostByRemoving(this, indices, keyPathTo = { keyPathOf(this) }) { copy, at ->
            copy.removeSlots(at.map { copy.values[it] }, takesAttached = true)
        }

    // A removal a caller asks for keeps the free lines above each removed header; clearing keeps the run's first only.
    private fun removeSlots(
        removed: List<Item>,
        takesAttached: Boolean,
    ): Boolean {
        val removedSlots = removed.toHashSet()
        for ((index, item) in values.withIndex()) {
            val startsRun = index == 0 || values[index - 1] !in removedSlots
            if (item in removedSlots && (startsRun || takesAttached)) {
                item.standardTableOrNull?.let {
                    keepHeaderLinesOf(it, isArrayOfTables = true, keptHeaderLines, takesAttached)
                }
            }
        }
        for (item in removed) item.leaveArray()
        values.removeAll { it in removedSlots }
        return removed.isNotEmpty()
    }

    /**
     * Appends a copy of every table of [tables], each stored as [add] stores it.
     *
     * @return [TomlEditError.TooDeep], appending none of them, when one would nest deeper than the parser reads.
     */
    public fun addAll(tables: Iterable<StandardTable>): EmptyResult<TomlEditError> = appendCopies(tables, ::copyToStore)

    /** Appends a copy of every table of [tables] that keeps its header decor and its place among the tables. */
    internal fun addAllKeepingPlaces(tables: Iterable<StandardTable>): EmptyResult<TomlEditError> =
        appendCopies(tables) { table -> table.deepCopy().also { giveSecondsInToml10(this, Item(it)) } }

    /**
     * Hands the places that copies of [old]'s tables brought along out again in array order, as printing does, and
     * gives each table the lines that stood above the header at its place, which need not be about that table. Does
     * nothing when a table holds a place [old] does not.
     *
     * @return whether the place of [old]'s first header was handed out.
     */
    internal fun takePlacesOf(old: ArrayOfTables): Boolean {
        val oldParts = old.values.flatMap(::placedParts)
        val oldPlaces = oldParts.mapNotNull { it.placement }.toSet()
        val oldTables = oldParts
            .filterIsInstance<StandardTable>()
            .filter { it.position != null }
            .associateBy { it.position }
        val parts = values.flatMap(::partsInPrintOrder)
        if (parts.isEmpty() || parts.any { it.placement !in oldPlaces }) return false
        val places = parts.mapNotNull { it.placement }.sorted()
        for ((part, place) in parts.zip(places)) {
            part.placement = place
            if (part is StandardTable) oldTables[place]?.let { part.takeLinesAboveOf(it) }
        }
        val firstPlace = old.values.firstOrNull()?.standardTableOrNull?.position
        return firstPlace != null && firstPlace in places
    }

    /** The placed parts of [element] that print among the tables: its header first, then the rest by place. */
    private fun partsInPrintOrder(element: Item): List<Any> {
        val header = element.standardTableOrNull?.takeIf { it.placement != null }
        val under = placedParts(element)
            .filter { it !== header && it.placement != null && (it as? StandardTable)?.isDotted != true }
            .sortedBy { it.placement }
        return listOfNotNull(header) + under
    }

    private fun StandardTable.takeLinesAboveOf(old: StandardTable) {
        val onHeaderLine = rawDecor.prefix?.textOrNull?.substringAfterLast('\n').orEmpty()
        rawDecor.prefix = old.rawDecor.prefix?.let { KeptText.from(linesAbove(it) + onHeaderLine) }
        opensFile = old.opensFile
    }

    private fun appendCopies(
        tables: Iterable<StandardTable>,
        copy: (StandardTable) -> StandardTable,
    ): EmptyResult<TomlEditError> {
        val added = tables.toList()
        for (table in added) storeError(this, null, Item(table))?.let { return Err(it) }
        for (table in added) append(copy(table))
        return Ok(Unit)
    }

    /**
     * Formats every table of the array, each with its header and everything under it, as [StandardTable.format]
     * does; the rest of the document stays as it is.
     */
    public fun format() {
        formatInPlace(this)
    }

    /** Writes every value under every table of the array in its default spelling, as [TomlDocument.respell] does. */
    public fun respell() {
        for (slot in values) slot.standardTableOrNull?.respell()
    }

    /**
     * A deep copy, stored nowhere: exactly what storing this array of tables would store. A copy of an array of
     * tables read from a document leaves the comment after its first header and the comment lines above that header
     * behind and takes the comments inside it, those of the later headers included. Comments a caller set through
     * an element's `decor` stay. A copy of an array of tables stored nowhere, built in code or returned by `remove`,
     * keeps all its comments.
     */
    public fun copy(): ArrayOfTables = deepCopy().also { copy ->
        copy.takenFrom = documentOf(this)
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
        copy.takenFrom = takenFrom
        for (table in this) copy.values.add(Item.inArrayOfTables(table.deepCopy(), copy))
        keptHeaderLines.mapTo(copy.keptHeaderLines) { it.copy() }
        return copy
    }

    /** The tables as an inline array; without a header there is no better way to print them. */
    override fun toString(): String = deepCopy()
        .moveToTomlArray()
        .also { it.takenFrom = documentOf(this) }
        .toString()
}

/** An array of copies of [tables], in order; see [toArrayOfTables]. */
public fun arrayOfTablesOf(vararg tables: StandardTable): Result<ArrayOfTables, TomlEditError> =
    tables.asIterable().toArrayOfTables()

/**
 * An array of copies of these tables, in order. Each copy keeps its place among the document's tables, so
 * `doc["arr"] = arr.reversed().toArrayOfTables()` reorders the tables where they print. Each table takes its header
 * comment and the tables under it along, while the comment and blank lines above each header stay where they print.
 *
 * @return [TomlEditError.TooDeep] when a table would nest deeper than the parser reads in an array of tables under a
 *   document's root.
 */
public fun Iterable<StandardTable>.toArrayOfTables(): Result<ArrayOfTables, TomlEditError> {
    val array = ArrayOfTables()
    return array.addAllKeepingPlaces(this).map { array }
}
