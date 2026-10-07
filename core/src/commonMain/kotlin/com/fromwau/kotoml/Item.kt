package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A slot in a table or array holding a [Value], a [StandardTable] or an [ArrayOfTables].
 *
 * An item is a place, not just a value: the tables and arrays store items, and indexing hands out the item
 * stored there, so writing through it changes the document. Once its key is removed, or replaced by `set` or `put`
 * on the table, the item keeps what it held and no longer belongs to the document. Replacing what the item
 * itself holds, with [assign], keeps it in the document.
 *
 * ```kotlin
 * val doc = TomlDocument.parse("[server]\nport = 8080\n").getOrElse { error(it) }
 * doc["server"]["port"] = 9090L
 * doc["server"]["port"].getOrNull()?.longOrNull // 9090
 * doc["database"]["url"] // Err(NotFound("database"))
 * ```
 *
 * Storing an item stores a copy of what it holds, so two places never share one slot or one table or value:
 * after `doc["b"] = doc["a"].getOrElse { error(it) }`, editing `doc["a"]` leaves `doc["b"]` as it was. A copy
 * taken from a place in a document, this one or another, leaves its source's own comments behind (its same-line
 * comment, its header comment and the comment lines above its header) and takes the comments inside it; a table
 * copied within one document prints after the tables already there. [copy] gives exactly what a store would store.
 * An item stored nowhere, built in code or returned by `remove`, keeps what it holds, its comments included;
 * `remove` leaves the lines above in the document, so storing the item again prints them once. A comment a caller
 * set through `decor` travels with every store and copy.
 *
 * A slot inside an array or an inline table holds only a value, and one inside an array of tables only a table;
 * writing anything else there fails with [TomlEditError.NotAValue] or [TomlEditError.NotATableElement].
 *
 * Indexing returns a [Result]: a key that is not there is [TomlEditError.NotFound], and indexing on through an
 * error passes it on, so a chain stops at the first key it misses. Writing creates only the key it names, never
 * a missing parent; create each level first. Writing through an index that cannot hold the write, such as a key
 * of an integer or a position past the end of an array, fails with [TomlEditError]; upstream panics there. In the
 * `item[key] = x` form the failure is discarded, so call [set] to see it.
 */
public class Item private constructor(
    content: Content,
    private var holds: Holds,
) {

    // Content entering a slot learns the slot, so a table or array can tell where it stands; see [Nesting].
    private var content: Content = content
        set(value) {
            field = value
            value.adoptedBy(this)
        }

    /** An item holding [value], as is. */
    public constructor(value: Value) : this(Content.ValueContent(value), Holds.Anything)

    /** An item holding [table], as is. */
    public constructor(table: StandardTable) : this(Content.TableContent(table), Holds.Anything)

    /** An item holding [arrayOfTables], as is. */
    public constructor(arrayOfTables: ArrayOfTables) : this(Content.ArrayOfTablesContent(arrayOfTables), Holds.Anything)

    /** What a slot may hold. */
    internal enum class Holds {
        Anything,
        Value,
        Table,
    }

    private sealed interface Content {
        class ValueContent(val value: Value) : Content

        class TableContent(val table: StandardTable) : Content

        class ArrayOfTablesContent(val array: ArrayOfTables) : Content

        val type: TomlType
            get() = when (this) {
                is ValueContent -> value.type
                is TableContent -> TomlType.Table
                is ArrayOfTablesContent -> TomlType.ArrayOfTables
            }

        // An element of an array of tables prints its values under its own header, so it never stands for a dotted key.
        fun placedIn(holds: Holds): Content {
            if (holds == Holds.Table && this is TableContent) table.markDotted(false)
            return this
        }

        fun adoptedBy(slot: Item) {
            when (this) {
                is ValueContent -> when (value) {
                    is Value.Datetime -> value.holder = slot
                    is Value.Array -> value.value.holder = slot
                    is Value.InlineTable -> value.value.holder = slot
                    is Value.String, is Value.Integer, is Value.Float, is Value.Boolean -> Unit
                }
                is TableContent -> table.holder = slot
                is ArrayOfTablesContent -> array.holder = slot
            }
        }

        fun holds(container: Any): Boolean = when (this) {
            is ValueContent -> value === container ||
                value.arrayOrNull === container ||
                value.inlineTableOrNull === container
            is TableContent -> table === container
            is ArrayOfTablesContent -> array === container
        }

        /** Drops the comments a copy read with its source's own lines; the comments inside it travel. */
        fun leaveSource() {
            when (this) {
                is ValueContent -> value.rawDecor.dropReadComments(isHeader = false)
                is TableContent -> table.rawDecor.dropReadComments(isHeader = true)
                is ArrayOfTablesContent -> array.firstOrNull()?.rawDecor?.dropReadComments(isHeader = true)
            }
        }

        fun deepCopy(): Content = when (this) {
            is ValueContent -> ValueContent(value.deepCopy())
            is TableContent -> TableContent(table.deepCopy())
            is ArrayOfTablesContent -> ArrayOfTablesContent(array.deepCopy())
        }
    }

    private fun refusal(content: Content): TomlEditError? = when (holds) {
        Holds.Anything -> null
        Holds.Value -> TomlEditError.NotAValue(content.type).takeUnless { content is Content.ValueContent }
        Holds.Table -> TomlEditError.NotATableElement(content.type).takeUnless { content is Content.TableContent }
    }

    /** The table entry this slot is stored in, if any; its key's spacing depends on what the slot holds. */
    internal var entry: KeyValue? = null

    /** The array or array of tables this slot is an element of, while it is one. */
    internal var arrayOwner: Any? = null

    /** The table or array this slot is stored in, or null when it is stored nowhere. */
    internal val owner: Any?
        get() = entry?.takeIf { it.isStored }?.pairs?.owner ?: arrayOwner

    /** Makes the table or array this slot holds know it as its slot; a wrapper made by a caller never does. */
    internal fun adoptContent() {
        content.adoptedBy(this)
    }

    /** Whether this slot holds [container] itself. */
    internal fun holdsContainer(container: Any): Boolean = content.holds(container)

    /** A deep copy that stays the same kind of slot. */
    internal fun copySlot(): Item = Item(content.deepCopy(), holds)

    /** This slot, made a slot of kind [holds] for the container it moves into. */
    internal fun movedTo(holds: Holds): Item {
        this.holds = holds
        return this
    }

    /**
     * Replaces this slot's content with a copy of [item]'s; a key keeps the lines above it, as [StandardTable.put]
     * says. Fails when this slot is in an array or an inline table and [item] holds no value, or in an array of
     * tables and [item] holds no table, with [TomlEditError.TooDeep] when [item] would nest deeper than the parser
     * reads, and with [TomlEditError.NotInToml10] when it holds what only TOML 1.1 reads and this slot is in a document
     * whose [TomlDocument.version] is 1.0.
     */
    public fun assign(item: Item): EmptyResult<TomlEditError> {
        val within = owner?.let { copiesWithinDocument(item, it) } ?: false
        val next = item.content.deepCopy().placedIn(holds).also { if (copiesFromDocument(item)) it.leaveSource() }
        owner?.let { placeStored(Item(next, Holds.Anything), within, it) }
        refusal(next)?.let { return Err(it) }
        tooDeepToHold(item)?.let { return Err(it) }
        owner?.let { notInToml10Error(it, storedKey, item) }?.let { return Err(it) }
        owner?.let { giveSecondsInToml10(it, Item(next, Holds.Anything)) }
        valuePlace?.let { place -> (next as? Content.ValueContent)?.value?.fitFor(place) }
        val entry = entry
        if (entry == null) {
            val oldElement = standardTableOrNull?.takeIf { arrayOwner is ArrayOfTables }
            content = next
            val element = standardTableOrNull
            if (oldElement != null && element != null) takePlaceOf(element, oldElement)
            return Ok(Unit)
        }
        entry.prepareFor(item)
        val linesAbove = entry.takeLinesAbove(item)
        val oldDecor = valueOrNull?.rawDecor
        val oldTable = standardTableOrNull?.takeUnless { it.isDotted }
        content = next
        entry.keepValueDecor(oldDecor)
        entry.keepHeaderComment(oldTable?.rawDecor)
        entry.keepLinesAbove(linesAbove)
        entry.keepTablePlace(oldTable)
        return Ok(Unit)
    }

    /** Where a value in this slot prints, or null for a slot stored nowhere. */
    private val valuePlace: ValuePlace?
        get() = when (owner) {
            null -> null
            is InlineTable -> ValuePlace.InlineTable
            is StandardTable -> ValuePlace.Line
            else -> ValuePlace.Array
        }

    /** A [copy] of what this item holds for storing at [place]; see [Value.fitFor]. */
    internal fun copyFor(place: ValuePlace): Item = copy().also { copy ->
        copy.copiedWithin = null
        copy.valueOrNull?.fitFor(place)
    }

    /** Why this slot cannot hold [item] for nesting too deep, or null when it can. */
    private fun tooDeepToHold(item: Item): TomlEditError.TooDeep? {
        val owner = owner ?: return tooDeepAtTop(item)
        return tooDeepError(owner, storedKey, item)
    }

    // The key this slot is stored at, or null for an element of an array or a slot stored nowhere.
    private val storedKey: String?
        get() = entry?.takeIf { it.isStored }?.key?.name

    /** The kind of item. */
    public val type: TomlType
        get() = content.type

    /** The item at [key] of this table or inline table, or null when there is none or this is not one. */
    public fun getOrNull(key: String): Item? = when (val content = content) {
        is Content.TableContent -> content.table.getOrNull(key)
        is Content.ValueContent -> content.value.inlineTableOrNull?.getItem(key)
        else -> null
    }

    /** The item at [index] of this array or array of tables, or null when there is none or this is not one. */
    public fun getOrNull(index: Int): Item? = when (val content = content) {
        is Content.ArrayOfTablesContent -> content.array.values.getOrNull(index)
        is Content.ValueContent -> content.value.arrayOrNull?.values?.getOrNull(index)
        else -> null
    }

    /**
     * The item stored at [key] of this table or inline table.
     *
     * @return [TomlEditError.NotFound] when there is none, or [TomlEditError.NotATable] when this holds neither
     *   kind of table.
     */
    public operator fun get(key: String): Result<Item, TomlEditError> {
        if (tableOrNull == null) return Err(TomlEditError.NotATable(key, type))
        return getOrNull(key)?.let { Ok(it) } ?: Err(TomlEditError.NotFound(key))
    }

    /**
     * The item at [index] of this array or array of tables.
     *
     * @return [TomlEditError.IndexOutOfBounds] when there is none, or [TomlEditError.NotAnArray] when this holds
     *   neither kind of array.
     */
    public operator fun get(index: Int): Result<Item, TomlEditError> =
        getOrNull(index)?.let { Ok(it) } ?: Err(missingIndex(index))

    private fun missingIndex(index: Int): TomlEditError {
        val size = arrayOrNull?.size ?: arrayOfTablesOrNull?.size
        return if (size != null) TomlEditError.IndexOutOfBounds(index, size) else TomlEditError.NotAnArray(index, type)
    }

    /**
     * Stores a copy of [item] at [key], as [StandardTable.set] or [InlineTable.set] does.
     *
     * @return [TomlEditError.NotATable] when this item holds neither a table nor an inline table,
     *   [TomlEditError.NotAValue] when this is an inline table and [item] holds no value, or [TomlEditError.TooDeep]
     *   when [item] would nest deeper than the parser reads.
     */
    public operator fun set(
        key: String,
        item: Item,
    ): EmptyResult<TomlEditError> {
        standardTableOrNull?.let { table -> return table.set(key, item) }
        val inline = inlineTableOrNull ?: return Err(TomlEditError.NotATable(key, type))
        val value = item.valueOrNull ?: return Err(TomlEditError.NotAValue(item.type))
        return inline.set(key, value)
    }

    /** Stores a copy of [value] at [key]; see [set]. */
    public operator fun set(
        key: String,
        value: Value,
    ): EmptyResult<TomlEditError> = set(key, Item(value))

    /** Stores a copy of [table] at [key]; see [set]. */
    public operator fun set(
        key: String,
        table: StandardTable,
    ): EmptyResult<TomlEditError> = set(key, Item(table))

    /** Stores a copy of [arrayOfTables] at [key]; see [set]. */
    public operator fun set(
        key: String,
        arrayOfTables: ArrayOfTables,
    ): EmptyResult<TomlEditError> = set(key, Item(arrayOfTables))

    /**
     * Renames the key [old] of this table or inline table to [new], as [StandardTable.rename] or [InlineTable.rename]
     * does.
     *
     * @return [TomlEditError.NotATable] when this item holds neither a table nor an inline table,
     *   [TomlEditError.NotFound] when there is no [old], or [TomlEditError.KeyTaken] when [new] is another key already
     *   there; each changes nothing.
     */
    public fun rename(
        old: String,
        new: String,
    ): EmptyResult<TomlEditError> = tableOrNull?.rename(old, new) ?: Err(TomlEditError.NotATable(old, type))

    /**
     * Stores a copy of [item] at [index] of this array or array of tables. Fails when there is no such position,
     * when [item] does not hold a value for an array or a table for an array of tables, or when it would nest
     * deeper than the parser reads.
     */
    public operator fun set(
        index: Int,
        item: Item,
    ): EmptyResult<TomlEditError> {
        val slot = getOrNull(index) ?: return Err(missingIndex(index))
        return slot.assign(item)
    }

    /** The value, or null when this holds none. Changing the value changes this item. */
    public val valueOrNull: Value?
        get() = (content as? Content.ValueContent)?.value

    /** The standard table, or null when this holds none. An inline table is a value; [tableOrNull] reads both. */
    public val standardTableOrNull: StandardTable?
        get() = (content as? Content.TableContent)?.table

    /** The array of tables, or null when this holds none. */
    public val arrayOfTablesOrNull: ArrayOfTables?
        get() = (content as? Content.ArrayOfTablesContent)?.array

    /** The table of either kind, standard or inline, or null when this holds neither. */
    public val tableOrNull: Table?
        get() = standardTableOrNull ?: inlineTableOrNull

    /**
     * This item as a value: a table becomes an inline table and an array of tables an array, both moving
     * their content.
     */
    internal fun moveToValue(): Value = when (val content = content) {
        is Content.ValueContent -> content.value
        is Content.TableContent -> Value.InlineTable(content.table.moveToInlineTable())
        is Content.ArrayOfTablesContent -> Value.Array(content.array.moveToTomlArray())
    }

    /**
     * How the table this slot holds prints, as [Table.layout] reads it, or null when the slot holds no table. An array
     * of tables holding a table reads [TableLayout.Header]. A non-empty array holding only inline tables reads
     * [TableLayout.MultilineInline] when each of them prints across lines, and [TableLayout.Inline] otherwise. An
     * empty array of tables, and an array holding anything but inline tables, holds no table.
     */
    public val layout: TableLayout?
        get() {
            standardTableOrNull?.let { return it.layout }
            inlineTableOrNull?.let { return it.layout }
            if (arrayOfTablesOrNull?.isNotEmpty() == true) return TableLayout.Header
            val tables = inlineTablesOfArray() ?: return null
            val acrossLines = tables.all { it.layout == TableLayout.MultilineInline }
            return if (acrossLines) TableLayout.MultilineInline else TableLayout.Inline
        }

    /**
     * Gives the table this slot holds the new [layout]; a table already laid out so stays as it is. The table stays
     * the same [StandardTable] or [InlineTable] object while it changes between [TableLayout.Header] and
     * [TableLayout.Dotted], or between the inline layouts; a change between the two kinds puts a new table of the
     * other kind in the slot, and the table taken from it earlier keeps its content and no longer belongs to the
     * document.
     *
     * [TableLayout.Header] puts the table under a header and reaches the tables inside it: every inline table inside,
     * at any depth, becomes a table under its own header, `[t.sub]`, and every array holding only inline tables an
     * array of tables, `[[t.list]]`. An array holding anything else stays a value with all it holds, and keys written
     * dotted stay dotted keys, though tables under them get headers. The table prints where a table stored at this key
     * would: after its parent's values and the tables stored before its key, and a table holding only tables prints
     * no header of its own. The comment lines above the key go above the header, and each table keeps its comments as
     * [InlineTable.toStandardTable] does: the comment after `{` becomes the header comment, each value keeps its
     * same-line comment, and the comment lines inside stay above the key below them. The comment after `}` goes on the
     * header too, or below the table's lines when the one after `{` is there. In an array the comment after an
     * element's comma goes on its header the same way, the comment after `[` and the comment lines above an element go
     * above its header, and the comment lines before `]` and the comment after it below the last table. Dotted keys
     * made a header put the comment lines above their first line above the header.
     *
     * [TableLayout.MultilineInline] puts the table in braces across lines and reaches the tables inside it the same
     * way: each table and array of tables under it becomes a value across lines, and each inline table inside goes
     * across lines. Every comment stays: the header comment after `{`, each value's same-line comment after its comma,
     * the comment lines above a key or above a header inside above the key, and the comment lines above the header or
     * above dotted keys' first line above the key; keys written dotted stay dotted. An array of tables becomes an array
     * across lines, a table a line, each header comment after `{`, and the comment lines above a later header above
     * its table. The last entry of each table takes a comma. Only TOML 1.1 reads an inline table across lines, so a
     * document whose [TomlDocument.version] is 1.0 refuses this layout.
     *
     * [TableLayout.Inline] puts the table in braces on one line, formatted anew, and every table inside with it: the
     * comments inside the braces go, while the comment lines above the key stay and the header comment, the one after
     * the header or after `{`, prints after `}`; when a comment stands after `}` already, it stays and the header
     * comment goes. A table in an array or in an inline table across lines takes its header comment after its comma,
     * when its line ends there. The header comments of the tables inside go with the other comments inside the braces.
     * An array of tables keeps the comments on and between its headers as an array across lines, each header comment
     * after its table's comma. A value inside written across lines, such as an array, still spans lines.
     *
     * [TableLayout.Dotted] makes the table stand for the parts of dotted keys, `t.a = 1`: the lines above its key stay
     * and each value keeps the comment on its line, while its header comment, the comment after its braces and the
     * comment lines inside it go. The tables under a dotted table keep their own layouts.
     *
     * A non-empty array holding only inline tables takes [TableLayout.Header] as a whole, becoming an array of tables,
     * and the inline layouts table by table. An array of tables takes the inline layouts as a whole, becoming an array
     * of inline tables.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("[t] # t\na = 1 # one\n").getOrElse { error(it) }
     * val slot = doc["t"].getOrElse { error(it) }
     * slot.setLayout(TableLayout.MultilineInline)
     * doc.toString() // "t = { # t\n  a = 1, # one\n}\n"
     * slot.setLayout(TableLayout.Inline) // fails with CommentsWouldBeLost(["t"], 1): "# one" is inside the braces
     * slot.setLayout(TableLayout.Header)
     * doc.toString() // "[t] # t\na = 1 # one\n"
     * ```
     *
     * @return [TomlEditError.CommentsWouldBeLost] when the new layout would drop a comment and [force] is false; with
     *   [force] true the comments go. [TomlEditError.NotExpandable] when this slot holds no table, or when it is an
     *   element of an array or an array of tables, or holds an array, and [layout] is [TableLayout.Dotted];
     *   [TomlEditError.NotATableElement] when this is a table in an array of tables and [layout] is an inline one;
     *   [TomlEditError.NotAValue] when this is a slot in an array or an inline table, which holds only values, and
     *   [layout] is [TableLayout.Header]; [TomlEditError.TooDeep] when the result would nest deeper than the parser
     *   reads, as headers with too many parts though the inline tables fit on their line;
     *   [TomlEditError.NotInToml10] when [layout] is [TableLayout.MultilineInline] and this slot is in a document whose
     *   [TomlDocument.version] is 1.0. Each changes nothing.
     */
    public fun setLayout(
        layout: TableLayout,
        force: Boolean = false,
    ): EmptyResult<TomlEditError> {
        if (this.layout == null) return Err(TomlEditError.NotExpandable(type))
        if (!changesLayout(layout)) return Ok(Unit)
        layoutRefusal(layout)?.let { return Err(it) }
        if (layout == TableLayout.MultilineInline && owner?.let(::documentVersionOf) == TomlVersion.V1_0) {
            return Err(TomlEditError.NotInToml10(layoutKeyPathOf(this), Toml11Feature.MultilineInlineTable))
        }
        return changeLayout(this, force) { slot -> slot.layOut(layout) }
    }

    /** The inline tables of a non-empty array holding only inline tables, or null when this holds no such array. */
    private fun inlineTablesOfArray(): List<InlineTable>? {
        val array = arrayOrNull ?: return null
        if (array.isEmpty() || !array.all { it is Value.InlineTable }) return null
        return array.mapNotNull { it.inlineTableOrNull }
    }

    // An array of inline tables takes an inline layout table by table, so one of them laid out otherwise changes.
    private fun changesLayout(layout: TableLayout): Boolean {
        val tables = inlineTablesOfArray()
        val inBraces = layout == TableLayout.Inline || layout == TableLayout.MultilineInline
        if (tables == null || !inBraces) return this.layout != layout
        return tables.any { it.layout != layout }
    }

    private fun layoutRefusal(layout: TableLayout): TomlEditError? {
        val holdsArray = arrayOfTablesOrNull != null || arrayOrNull != null
        return when {
            layout == TableLayout.Dotted && (holdsArray || arrayOwner != null) -> TomlEditError.NotExpandable(type)
            holds == Holds.Table -> TomlEditError.NotATableElement(TomlType.InlineTable)
            holds == Holds.Value && layout == TableLayout.Header -> {
                TomlEditError.NotAValue(if (holdsArray) TomlType.ArrayOfTables else TomlType.Table)
            }
            else -> null
        }
    }

    private fun layOut(layout: TableLayout): EmptyResult<TomlEditError> {
        val table = standardTableOrNull
        val inline = inlineTableOrNull
        val multiline = layout == TableLayout.MultilineInline
        when {
            table != null && layout == TableLayout.Dotted -> table.layOutDotted(true)
            table != null && layout == TableLayout.Header -> return layOutUnderHeader(table)
            table != null || arrayOfTablesOrNull != null -> return inline(multiline)
            inline != null -> return layOutInline(inline, layout)
            layout == TableLayout.Header -> return expand()
            else -> for (element in arrayOrNull?.values.orEmpty()) {
                element.inlineTableOrNull?.let { element.layOutInBraces(it, layout) }
            }
        }
        return Ok(Unit)
    }

    // Dotted keys made a header reach the inline tables under them, as an inline table made a header does.
    private fun layOutUnderHeader(table: StandardTable): EmptyResult<TomlEditError> {
        val lineBreak = newLineEnding()
        val probe = table.deepCopy().also { it.markDotted(false) }
        if (expandWithin(probe, lineBreak)) tooDeepToHold(Item(probe))?.let { return Err(it) }
        table.layOutDotted(false)
        expandWithin(table, lineBreak)
        return Ok(Unit)
    }

    private fun layOutInline(
        inline: InlineTable,
        layout: TableLayout,
    ): EmptyResult<TomlEditError> {
        when {
            layout == TableLayout.Header -> return expand()
            layout == TableLayout.Dotted -> inline.layOutDotted(true)
            // Its lines print on a table's lines, which hold their comments as a dotted table's do.
            inline.isDotted && owner is StandardTable -> {
                return inline(layout == TableLayout.MultilineInline, inline.deepCopy().moveToDottedTable())
            }
            else -> layOutInBraces(inline, layout)
        }
        return Ok(Unit)
    }

    private fun layOutInBraces(
        inline: InlineTable,
        layout: TableLayout,
    ) {
        inline.layOutDotted(false)
        if (layout == TableLayout.MultilineInline) return inline.layOutAcrossLinesWithin()
        val headerComment = inline.commentAfterOpeningBrace()
        inline.layOutOnOneLineWithin()
        if (headerComment.isNotEmpty()) keepCommentAfterValue(headerComment)
    }

    // A comment already after the value stays; inside braces or brackets on one line no comment fits after it.
    private fun keepCommentAfterValue(comment: String) {
        val value = valueOrNull ?: return
        when (val owner = owner) {
            is InlineTable -> owner.keepCommentAfter(value, comment)
            is TomlArray -> owner.keepCommentAfter(value, comment)
            else -> if (value.commentAfter().isEmpty()) value.rawDecor.suffix = KeptText.from(comment)
        }
    }

    /** Puts [table], or the array of tables in this slot, in braces in place of what this slot holds. */
    private fun inline(
        multiline: Boolean,
        table: StandardTable? = standardTableOrNull,
    ): EmptyResult<TomlEditError> {
        val tables = arrayOfTablesOrNull
        val version = owner?.let(::documentVersionOf) ?: TomlVersion.V1_1
        val lineBreak = (table ?: tables?.firstOrNull())?.headerLineEnding ?: newLineEnding()
        val layout = InlineLayout(version, lineBreak, multiline)
        val copy = table?.deepCopy()
        // Without a header of its own, the table's first lines are those above the first header printed under it.
        val firstLines = mutableListOf<KeptLines>()
        if (copy != null && (copy.isDotted || !copy.printsHeader(isArrayOfTables = false))) {
            keepHeaderLinesOf(copy, isArrayOfTables = false, firstLines)
        }
        val value = when {
            copy != null -> copy.toInlineValue(layout, "")
            tables != null -> Value.Array(tables.deepCopy().moveToTomlArrayKeepingComments(layout, ""))
            else -> return Ok(Unit)
        }
        tooDeepToHold(Item(value))?.let { return Err(it) }
        holdValue(value, keptInside = multiline, alsoAbove = firstLines.joinToString("") { it.text })
        return Ok(Unit)
    }

    private fun expand(): EmptyResult<TomlEditError> {
        val value = valueOrNull ?: return Ok(Unit)
        val expanded = expandedOrNull(value.deepCopy(), newLineEnding())
            ?: return Err(TomlEditError.NotExpandable(value.type))
        if (holds == Holds.Value) return Err(TomlEditError.NotAValue(expanded.type))
        tooDeepToHold(expanded)?.let { return Err(it) }
        takeExpanded(expanded)
        return Ok(Unit)
    }

    /** Turns this slot's value into [expanded], made from it, keeping the lines above. */
    private fun takeExpanded(expanded: Item) {
        val entry = entry
        val linesAbove = entry?.takeLinesAbove(expanded).orEmpty()
        entry?.prepareFor(expanded)
        content = expanded.content
        entry?.keepLinesAbove(linesAbove)
        // The comment lines above the first header join the key's, so the default spacing goes above both.
        val first = expanded.arrayOfTablesOrNull?.firstOrNull()
        val firstLines = first?.rawDecor?.prefix?.textOrNull
        if (entry != null && first != null && firstLines != null && '#' in firstLines) {
            val keyDecor = entry.key.rawLeafDecor
            keyDecor.prefix = KeptText.from(keyDecor.prefix?.textOrNull.orEmpty() + firstLines)
            first.rawDecor.prefix = null
        }
    }

    /** [value], a copy, as a table or an array of tables keeping its comments, expanded inside; see [setLayout]. */
    private fun expandedOrNull(
        value: Value,
        lineBreak: String,
    ): Item? {
        val inline = value.inlineTableOrNull
        if (inline != null) {
            // Braces first, so the dotted lines' comments are read where a table across lines holds them.
            inline.layOutDotted(false)
            val table = inline.moveToStandardTable()
            table.keepCommentAfterBraces(value.commentAfter(), lineBreak)
            expandWithin(table, lineBreak)
            return Item(table)
        }
        val array = value.arrayOrNull ?: return null
        val tables = array.moveToArrayOfTablesKeepingComments(value.commentAfter(), lineBreak) ?: return null
        for (table in tables) expandWithin(table, lineBreak)
        return Item(tables)
    }

    /**
     * Expands each inline table and array of inline tables in [table]'s slots, and those under its dotted keys,
     * which stay dotted keys.
     */
    private fun expandWithin(
        table: StandardTable,
        lineBreak: String,
    ): Boolean {
        var expandedAny = false
        for (entry in table.items.entries) {
            val slot = entry.item
            val dottedTable = slot.standardTableOrNull?.takeIf { it.isDotted }
            if (dottedTable != null && expandWithin(dottedTable, lineBreak)) expandedAny = true
            val value = slot.valueOrNull ?: continue
            val dotted = value.inlineTableOrNull?.takeIf { it.isDotted }
            val expanded = when {
                dotted == null -> expandedOrNull(value, lineBreak)
                dotted.holdsExpandable() -> Item(dotted.moveToDottedTable().also { expandWithin(it, lineBreak) })
                else -> null
            } ?: continue
            slot.takeExpanded(expanded)
            expandedAny = true
        }
        return expandedAny
    }

    // The line ending of the document this slot is in, for lines a table built in code has none of its own for.
    private fun newLineEnding(): String = owner?.let(::lineEndingOf) ?: LINE_BREAK

    /**
     * Turns a table or array of tables in this slot into [value], made from it, keeping the lines above; with
     * [keptInside], [value] holds the comment lines among a dotted table's lines, which stay out of the lines above;
     * [alsoAbove] goes above the key after them.
     */
    internal fun holdValue(
        value: Value,
        keptInside: Boolean = false,
        alsoAbove: String = "",
    ) {
        val linesAbove = entry?.takeLinesAbove(Item(value), keptInside).orEmpty()
        entry?.prepareFor(Item(value))
        content = Content.ValueContent(value)
        entry?.keepLinesAbove(linesAbove + alsoAbove)
    }

    /** This item as a table, moving an inline table's entries into one; null for anything else. */
    internal fun moveToStandardTable(): StandardTable? = when (val content = content) {
        is Content.TableContent -> content.table
        is Content.ValueContent -> content.value.inlineTableOrNull?.moveToStandardTable()
        else -> null
    }

    /**
     * This item as an array of tables, moving a non-empty array of inline tables into one; null for anything
     * else.
     */
    internal fun moveToArrayOfTables(): ArrayOfTables? = when (val content = content) {
        is Content.ArrayOfTablesContent -> content.array
        is Content.ValueContent -> {
            val array = content.value.arrayOrNull
            if (array == null || array.isEmpty() || !array.all { it is Value.InlineTable }) {
                null
            } else {
                ArrayOfTables().also { tables ->
                    for (value in array) value.inlineTableOrNull?.moveToStandardTable()?.let(tables::append)
                }
            }
        }
        else -> null
    }

    /** The string, or null when this holds none. */
    public val stringOrNull: String?
        get() = valueOrNull?.stringOrNull

    /** The integer, or null when this holds none. */
    public val longOrNull: Long?
        get() = valueOrNull?.longOrNull

    /** The float, or null when this holds none. */
    public val doubleOrNull: Double?
        get() = valueOrNull?.doubleOrNull

    /** The boolean, or null when this holds none. */
    public val booleanOrNull: Boolean?
        get() = valueOrNull?.booleanOrNull

    /** The date-time, or null when this holds none. */
    public val datetimeOrNull: Datetime?
        get() = valueOrNull?.datetimeOrNull

    /** The array, or null when this holds none. Changing the array changes this item. */
    public val arrayOrNull: TomlArray?
        get() = valueOrNull?.arrayOrNull

    /** The inline table, or null when this holds none. Changing the table changes this item. */
    public val inlineTableOrNull: InlineTable?
        get() = valueOrNull?.inlineTableOrNull

    internal val span: Span?
        get() = when (val content = content) {
            is Content.ValueContent -> content.value.span
            is Content.TableContent -> content.table.span
            is Content.ArrayOfTablesContent -> content.array.span
        }

    internal fun resolveSpans(input: SourceText) {
        when (val content = content) {
            is Content.ValueContent -> content.value.resolveSpans(input)
            is Content.TableContent -> content.table.resolveSpans(input)
            is Content.ArrayOfTablesContent -> content.array.resolveSpans(input)
        }
    }

    /**
     * A deep copy of this item, stored nowhere and free to be put anywhere: exactly what storing this item would
     * store. A copy of an item read from a place in a document leaves that place's own comments behind, its same-line
     * comment, its header comment and the comment lines above its header, and takes the comments inside it, so with
     * `val db = src["db"].getOrElse { error(it) }`, `dst.set("db", db.copy())` prints the same as `dst.set("db", db)`.
     * Comments a caller set through `decor` stay. A copy of an item stored nowhere, built in code or returned by
     * `remove`, keeps all its comments.
     */
    public fun copy(): Item = copyWithComments().also { copy ->
        copy.copiedWithin = outermostTableOfSource(this) ?: copiedWithin
        if (copiesFromDocument(this)) copy.content.leaveSource()
    }

    /** For a copy stored nowhere, the outermost table of the document it was read from; see [copiesWithinDocument]. */
    internal var copiedWithin: StandardTable? = null

    /** A deep copy that keeps every comment, for turning this slot's content into another kind in place. */
    internal fun copyWithComments(): Item = Item(content.deepCopy(), Holds.Anything)

    /** The item as TOML: a value, a table's key/value pairs, or an array of tables as an array. */
    override fun toString(): String = when (val content = content) {
        is Content.ValueContent -> content.value.toString()
        is Content.TableContent -> content.table.toString()
        is Content.ArrayOfTablesContent -> content.array.toString()
    }

    internal companion object {
        /** A slot of [array], which only ever holds a value. */
        fun inArray(
            value: Value,
            array: TomlArray,
        ): Item = Item(Content.ValueContent(value), Holds.Value).ownedBy(array)

        /** A slot of an [InlineTable], which only ever holds a value. */
        fun inInlineTable(value: Value): Item = Item(Content.ValueContent(value), Holds.Value)

        /** A slot of [array], which only ever holds a table. */
        fun inArrayOfTables(
            table: StandardTable,
            array: ArrayOfTables,
        ): Item = Item(Content.TableContent(table).placedIn(Holds.Table), Holds.Table).ownedBy(array)

        private fun Item.ownedBy(array: Any): Item {
            arrayOwner = array
            adoptContent()
            return this
        }
    }
}
