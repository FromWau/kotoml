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
@MustUseReturnValues
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
     * Removes every table, each with the comments that belong to it, as [removeAt] says. The array prints `key = []`
     * below the comment lines that stood above its first `[[header]]`, and comment lines set apart from a later header
     * by a blank line stay too.
     *
     * @param force removes them even when comments would go with them.
     * @return [TomlEditError.CommentsWouldBeLost], removing none, when [force] is false and comments would leave the
     *   document, counting the comments of all.
     */
    public fun clear(force: Boolean = false): EmptyResult<TomlEditError> = removeChecked(values.toList(), force).map { }

    /** The table at [index], or null when the index is out of bounds. */
    public fun getOrNull(index: Int): StandardTable? = values.getOrNull(index)?.standardTableOrNull

    /** The table at [index], or [TomlEditError.IndexOutOfBounds] when there is none. */
    public operator fun get(index: Int): Result<StandardTable, TomlEditError> =
        getOrNull(index)?.let { Ok(it) } ?: Err(TomlEditError.IndexOutOfBounds(index, size))

    /**
     * Appends a copy of [table], which prints under its own `[[header]]`, even when it was dotted. A table read from
     * another place, in this document or another, brings its header comment and the comment lines right above its
     * header, which print below a blank line; the comment lines set apart from it by a blank line stay there.
     *
     * @return [TomlEditError.TooDeep], storing nothing, when [table] would nest deeper than the parser reads.
     */
    public fun add(table: StandardTable): EmptyResult<TomlEditError> {
        storeError(this, null, Item(table))?.let { return Err(it) }
        append(copyToAdd(table))
        return Ok(Unit)
    }

    /**
     * A copy of [table] to store here. One from another place, in this document or another, brings the comment lines
     * directly above its header, which it holds read, and its header comment, and prints after the element before it,
     * not where its source printed.
     */
    private fun copyToStore(table: StandardTable): StandardTable = table.deepCopy().also { copy ->
        giveSecondsInToml10(this, Item(copy))
        if (copiesFromElsewhere(table, this)) {
            copy.keepOnlyAttachedAbove()
            forgetPlacement(Item(copy))
        }
    }

    // The comment lines a copy from elsewhere brings print below the default spacing of a new header.
    private fun copyToAdd(table: StandardTable): StandardTable = copyToStore(table).also { copy ->
        copy.putAttachedAbove(attachedAboveBroughtBy(table, copy))
        copy.settleGivenText()
    }

    /** Takes out of [copy] the comment lines that [table], copied from elsewhere, brings above its header. */
    private fun attachedAboveBroughtBy(
        table: StandardTable,
        copy: StandardTable,
    ): String = if (copiesFromElsewhere(table, this)) copy.takeAttachedAboveHeader() else ""

    /** Appends [table] itself, for a caller that built it and holds no other reference to it. */
    internal fun append(table: StandardTable) {
        values.add(Item.inArrayOfTables(table, this))
    }

    /**
     * Inserts a copy of [table] at [index], shifting later tables right; it brings its comments as [add] says.
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
        values.add(index, Item.inArrayOfTables(copyToAdd(table), this))
        return Ok(Unit)
    }

    /**
     * Replaces the table at [index] with [table], which takes over the old table's place among the document's tables
     * and its header decor: the comment and blank lines above its `[[header]]` and the comment after it on its line.
     * The comments [table] brings from where it was read fill only the places where the old table has none; a header
     * comment given in its decor takes the place of the old table's. See [replace].
     *
     * @return the table it replaced, or [TomlEditError.IndexOutOfBounds] for an [index] past the end, changing nothing.
     */
    public fun set(
        index: Int,
        table: StandardTable,
    ): Result<StandardTable, TomlEditError> = replace(index, table)

    /**
     * Replaces the table at [index] with a copy of [table].
     *
     * @param keepDecor whether the copy takes over the old table's [StandardTable.position] and header decor, the
     *   comment and blank lines above its `[[header]]` and the comment after it on its line, where a table read from
     *   another place brings its header comment and comment lines right above its header to those the old table has
     *   none in; false keeps [table]'s own position and decor.
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
        var above = attachedAboveBroughtBy(table, stored)
        if (keepDecor) {
            val own = old.headerComments()
            val takesPlace = !own.holdsOnLine || stored.rawDecor.givesComment()
            val comment = stored.rawDecor.suffix.takeIf { stored.headerComment.isNotEmpty() && takesPlace }
            stored.rawDecor.assign(old.rawDecor)
            if (comment != null) stored.rawDecor.suffix = comment
            stored.placeAt(old.position, old.positionFromText)
            above = own.fillAbove(above)
        }
        stored.putAttachedAbove(above)
        values.set(index, Item.inArrayOfTables(stored, this)).leaveArray()
        stored.settleGivenText()
        return Ok(old)
    }

    /**
     * Removes the table at [index], with the comments that belong to it: the comment lines right above its
     * `[[header]]` with no blank line between, the comment after the header, its body, and the tables under it with
     * the comment and blank lines above their headers. Comment lines set apart from the header by a blank line stay
     * where they print. Removing the last table leaves the array as `key = []`, which keeps the comment lines above
     * that table's header, so they need no force.
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
        removeSlots(listOf(values[index]))
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
        return Ok(removeSlots(removed))
    }

    private fun refusalToRemove(indices: List<Int>): TomlEditError? =
        lostByRemoving(this, indices, keyPathTo = { keyPathOf(this) }) { copy, at ->
            copy.removeSlots(at.map { copy.values[it] })
        }

    // An emptied array still prints its key, so the comment lines above its first header stay above it.
    @IgnorableReturnValue
    private fun removeSlots(removed: List<Item>): Boolean {
        val removedSlots = removed.toHashSet()
        val empties = values.all { it in removedSlots }
        for ((index, item) in values.withIndex()) {
            val table = item.takeIf { it in removedSlots }?.standardTableOrNull ?: continue
            val takesAttached = !(empties && index == 0)
            keepHeaderLinesOf(table, isArrayOfTables = true, keptHeaderLines, takesAttached)
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
    public fun addAll(tables: Iterable<StandardTable>): EmptyResult<TomlEditError> = appendCopies(tables, ::copyToAdd)

    /**
     * Appends a copy of every table of [tables] that keeps its place among the tables, its header comment and the
     * comment lines directly above its header, which the first holds read for the slot this array is stored in.
     */
    internal fun addAllKeepingPlaces(tables: Iterable<StandardTable>): EmptyResult<TomlEditError> =
        appendCopies(tables) { table ->
            table.deepCopy().also { copy ->
                giveSecondsInToml10(this, Item(copy))
                if (isStored(table)) copy.keepOnlyAttachedAbove()
                if (isNotEmpty()) copy.putAttachedAbove(copy.takeAttachedAboveHeader())
            }
        }

    /**
     * Hands the places that copies of [old]'s tables brought along out again in array order, as printing does, and
     * gives each table the lines that stood above the header at its place up to the last blank one, below which it
     * keeps the comment lines directly above its own header. Does nothing when a table holds a place [old] does not.
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
        val onHeaderLine = rawDecor.prefix?.textOrNull.orEmpty().substringAfterLast('\n')
        val oldText = old.rawDecor.prefix?.textOrNull.orEmpty()
        val lineBreak = if ('\n' in oldText) oldText.lineBreak() else old.headerLineEnding ?: LINE_BREAK
        val attached = indentedCommentLines(splitLinesAbove(linesAbove(rawDecor.prefix)).attached, "", lineBreak)
        val text = splitLinesAbove(linesAbove(old.rawDecor.prefix)).free + attached + onHeaderLine
        rawDecor.prefix = if (old.rawDecor.prefix == null && text.isEmpty()) null else KeptText.from(text)
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
     * tables read from a document takes the comment after its first header, the comment lines right above that header
     * and the comments inside it, those of the later headers included, and leaves the comment lines set apart from its
     * first header by a blank line. Comments a caller set through an element's `decor` stay. A copy of an array of
     * tables stored nowhere, built in code or returned by `remove`, keeps all its comments.
     */
    public fun copy(): ArrayOfTables = deepCopy().also { copy ->
        copy.takenFrom = documentOf(this)
        if (isStored(this)) {
            copy.copiedWithin = outermostTableOf(this)
            copy.firstOrNull()?.keepOnlyAttachedAbove()
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
 * `doc.set("arr", arr.reversed().toArrayOfTables())` reorders the tables where they print. Each table takes its
 * comments along: the comment lines directly above its header, its header comment and the tables under it. Stored in
 * place of the array its tables were read from, the blank lines above each header, and comment lines a blank line sets
 * apart from it, stay where they print; stored anywhere else, each table read from a document brings the comment lines
 * directly above its header, as [ArrayOfTables.add] says.
 *
 * ```kotlin
 * val doc = TomlDocument.parse("# first\n[[a]]\nn = 1\n\n# second\n[[a]]\nn = 2\n").getOrElse { error(it) }
 * doc.set("a", doc["a"].getOrNull()?.arrayOfTablesOrNull!!.reversed().toArrayOfTables().getOrElse { error(it) })
 * doc.toString() // "# second\n[[a]]\nn = 2\n\n# first\n[[a]]\nn = 1\n"
 * ```
 *
 * @return [TomlEditError.TooDeep] when a table would nest deeper than the parser reads in an array of tables under a
 *   document's root.
 */
public fun Iterable<StandardTable>.toArrayOfTables(): Result<ArrayOfTables, TomlEditError> {
    val array = ArrayOfTables()
    return array.addAllKeepingPlaces(this).map { array }
}
