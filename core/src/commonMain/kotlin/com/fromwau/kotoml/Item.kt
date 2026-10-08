package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map

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
 * taken from a place in a document, this one or another, takes the comments that belong to it there: the comment
 * lines right above it with no blank line between, its same-line comment, for a table those of its header, and every
 * comment inside it. Comment lines set apart from it by a blank line stay in its source. A table copied within one
 * document prints after the tables already there. [copy] gives exactly what a store would store. An item stored
 * nowhere, built in code or returned by `remove`, keeps what it holds, its comments included. A comment a caller set
 * through `decor` travels with every store and copy.
 *
 * A slot inside an array or an inline table holds only a value, and one inside an array of tables only a table;
 * writing anything else there fails with [TomlEditError.NotAValue] or [TomlEditError.NotATableElement].
 *
 * Indexing returns a [Result]: a key that is not there is [TomlEditError.NotFound], and indexing on through an
 * error passes it on, so a chain stops at the first key it misses. Writing creates only the key it names, never
 * a missing parent; create each level first. Writing through an index that cannot hold the write, such as a key
 * of an integer or a position past the end of an array, fails with [TomlEditError]; upstream panics there. The
 * `item[key] = x` form throws [TomlEditException] with the error instead, changing nothing; call [set] for a [Result].
 */
public class Item private constructor(
    content: Content,
    private var holds: Holds,
) {

    // Content entering a slot learns the slot, and content leaving a stored one its document's look; see [Nesting].
    private var content: Content = content
        set(value) {
            owner?.let { field.keepDocument(documentOf(it)) }
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
                    is Value.String -> value.holder = slot
                    is Value.Datetime -> value.holder = slot
                    is Value.Array -> value.value.holder = slot
                    is Value.InlineTable -> value.value.holder = slot
                    is Value.Integer, is Value.Float, is Value.Boolean -> Unit
                }
                is TableContent -> table.holder = slot
                is ArrayOfTablesContent -> array.holder = slot
            }
        }

        /** The look of the document this content prints in when it stands at the top of a tree; see [documentOf]. */
        fun document(): DocumentLook? = when (this) {
            is ValueContent -> value.document()
            is TableContent -> documentOf(table)
            is ArrayOfTablesContent -> documentOf(array)
        }

        /** Makes this content, as the top of a tree stored nowhere, print as in a document of [look]. */
        fun keepDocument(look: DocumentLook?) {
            when (this) {
                is ValueContent -> value.keepDocument(look)
                is TableContent -> table.takenFrom = look
                is ArrayOfTablesContent -> array.takenFrom = look
            }
        }

        fun holds(container: Any): Boolean = when (this) {
            is ValueContent -> value === container ||
                value.arrayOrNull === container ||
                value.inlineTableOrNull === container
            is TableContent -> table === container
            is ArrayOfTablesContent -> array === container
        }

        /**
         * Keeps, of the comments this copy read with its source's lines, those the source owns, [comments]: its
         * same-line comment, and the comment lines directly above a header or dotted keys' first line, which it holds.
         * Returns the comment lines directly above any other value's line, which the slot it is stored in prints; the
         * comments inside it travel as they are.
         */
        fun keepOwnComments(comments: ItemComments): String = when (this) {
            is ValueContent -> {
                value.rawDecor.keepSameLineComment(comments.sameLine)
                value.rawDecor.keepCommaLineComment(comments.commaLine)
                // A dotted table holds the lines above its first line, which it brings as a table brings its header's.
                val dotted = value.inlineTableOrNull?.takeIf { it.isDotted }
                dotted?.keepOnlyAttachedAboveFirstLine()
                if (dotted == null) comments.attachedAbove else ""
            }
            is TableContent -> {
                if (table.isDotted) table.keepOnlyAttachedAboveFirstLine() else table.keepOnlyAttachedAbove()
                ""
            }
            is ArrayOfTablesContent -> {
                array.firstOrNull()?.keepOnlyAttachedAbove()
                ""
            }
        }

        /** Takes out the comment lines this content, stored nowhere, holds above what it prints from its source. */
        fun takeAttachedAbove(): String = when (this) {
            is ValueContent -> value.inlineTableOrNull?.takeIf { it.isDotted }?.takeAttachedAboveFirstLine().orEmpty()
            is TableContent -> when {
                table.isDotted -> table.takeAttachedAboveFirstLine()
                else -> table.takeAttachedAboveHeader()
            }
            is ArrayOfTablesContent -> array.firstOrNull()?.takeAttachedAboveHeader().orEmpty()
        }

        fun deepCopy(): Content = when (this) {
            is ValueContent -> ValueContent(value.deepCopy())
            is TableContent -> TableContent(table.deepCopy())
            is ArrayOfTablesContent -> ArrayOfTablesContent(array.deepCopy())
        }

        /** A [deepCopy] to store elsewhere; see [com.fromwau.kotoml.InlineTable.copyOutOfBraces]. */
        fun copyOutOfBraces(): Content = if (this is ValueContent) ValueContent(value.copyOutOfBraces()) else deepCopy()
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

    /** Makes what this slot holds, as the top of a tree stored nowhere, print as in [place]'s document. */
    internal fun keepDocumentOf(place: Any) {
        content.keepDocument(documentOf(place))
    }

    /** Takes this slot out of the array holding it; what it holds keeps the look of the array's document. */
    internal fun leaveArray() {
        arrayOwner?.let(::keepDocumentOf)
        arrayOwner = null
    }

    /** The look of the document this slot's content prints in: its owner's, or the one it was taken out of. */
    private val document: DocumentLook?
        get() = owner?.let(::documentOf) ?: content.document()

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
     * Replaces this slot's content with a copy of [item]'s. The slot keeps its comments, the comment lines right above
     * it and its same-line comment, unless [item] brings comments of its own, which take their place, as
     * [StandardTable.put] says. Fails when this slot is in an array or an inline table and [item] holds no value, or in
     * an array of tables and [item] holds no table, with [TomlEditError.TooDeep] when [item] would nest deeper than the
     * parser reads, and with [TomlEditError.NotInToml10] when it holds what only TOML 1.1 reads and this slot is in a
     * document whose [TomlDocument.version] is 1.0. In an inline table that cannot hold the comments [item] brings, as
     * [InlineTable.put] says, it fails with [TomlEditError.CommentsWouldBeLost] unless [force] stores it without them.
     * Each failure changes nothing.
     */
    public fun assign(
        item: Item,
        force: Boolean = false,
    ): EmptyResult<TomlEditError> {
        val within = owner?.let { copiesWithinDocument(item, it) } ?: false
        val next = item.content
            .copyOutOfBraces()
            .placedIn(holds)
        var above = carriedAboveOf(item, next) + next.takeAttachedAbove()
        owner?.let { placeStored(Item(next, Holds.Anything), within, it) }
        refusal(next)?.let { return Err(it) }
        tooDeepToHold(item)?.let { return Err(it) }
        owner?.let { notInToml10Error(it, storedKey, item) }?.let { return Err(it) }
        owner?.let { giveSecondsInToml10(it, Item(next, Holds.Anything)) }
        val nextValue = (next as? Content.ValueContent)?.value
        val inline = owner as? InlineTable
        val key = storedKey
        if (!force && inline != null && nextValue != null && key != null) {
            inline.lostComments(key, nextValue.rawDecor, above)?.let { return Err(it) }
        }
        val onTableLine = valuePlace == ValuePlace.Line || inline?.printsOnTableLines() == true
        if (onTableLine) nextValue?.let { above += it.rawDecor.takeCommentLinesBelow() }
        // In brackets or braces a comment after a value follows its comma, not its decor.
        val comment = nextValue?.takeIf { valuePlace.let { it != null && it != ValuePlace.Line } }?.broughtComment()
        valuePlace?.let { place -> nextValue?.fitFor(place) }
        if (nextValue != null) inline?.dropCommentsItCannotHold(nextValue)
        val entry = entry
        if (entry == null) {
            val oldElement = standardTableOrNull?.takeIf { arrayOwner is ArrayOfTables }
            val oldDecor = valueOrNull?.rawDecor?.copy()
            content = next
            val element = standardTableOrNull
            if (oldElement != null && element != null) {
                takePlaceOf(element, oldElement)
                element.putAttachedAbove(above)
            }
            val array = owner as? TomlArray
            if (array != null && nextValue != null && oldDecor != null) {
                // The element takes the slot's place on its line, as [TomlArray.replace] puts it.
                nextValue.rawDecor.keepAttachedAboveIn(oldDecor)
                if (!nextValue.bringsCommentLinesBelow()) nextValue.rawDecor.suffix = oldDecor.suffix
                array.takeBroughtComments(nextValue, above, comment.orEmpty())
                array.layOutCommentLinesBelow(nextValue)
            }
            return Ok(Unit)
        }
        entry.prepareFor(item)
        val linesAbove = entry.takeLinesAbove(item)
        val oldDecor = valueOrNull?.rawDecor
        val oldTable = standardTableOrNull?.takeUnless { it.isDotted }
        val oldArray = arrayOfTablesOrNull
        content = next
        entry.keepValueDecor(oldDecor)
        entry.keepHeaderComment(oldTable?.rawDecor)
        val keptPlaces = entry.keepPlacesOf(oldArray, above.takeIf { inline == null }.orEmpty())
        if (!keptPlaces) entry.keepLinesAbove(linesAbove)
        entry.keepTablePlace(oldTable)
        if (inline == null) {
            if (!keptPlaces) entry.takeAttachedAbove(above)
        } else if (nextValue != null) {
            inline.takeBroughtComments(nextValue, entry, above, comment.orEmpty())
        }
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
        // On a table's line the comment lines between a value and its comma go above its key, so they stay its own.
        if (place == ValuePlace.Line) copy.valueOrNull?.let { copy.carriedAbove += it.rawDecor.takeCommentLinesBelow() }
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
     *   [TomlEditError.NotAValue] when this is an inline table and [item] holds no value, [TomlEditError.TooDeep]
     *   when [item] would nest deeper than the parser reads, [TomlEditError.NotInToml10] when [item] holds what
     *   only TOML 1.1 reads and this item is in a document whose [TomlDocument.version] is 1.0, or
     *   [TomlEditError.CommentsWouldBeLost] when this is an inline table that cannot hold the comments [item] brings;
     *   call [InlineTable.putItem] with `force = true` to store it without them.
     */
    public fun set(
        key: String,
        item: Item,
    ): EmptyResult<TomlEditError> {
        standardTableOrNull?.let { table -> return table.set(key, item) }
        val inline = inlineTableOrNull ?: return Err(TomlEditError.NotATable(key, type))
        return inline.putItem(key, item).map { }
    }

    /** Stores a copy of [value] at [key]; see [set]. */
    public fun set(
        key: String,
        value: Value,
    ): EmptyResult<TomlEditError> = set(key, Item(value))

    /** Stores a copy of [table] at [key]; see [set]. */
    public fun set(
        key: String,
        table: StandardTable,
    ): EmptyResult<TomlEditError> = set(key, Item(table))

    /** Stores a copy of [arrayOfTables] at [key]; see [set]. */
    public fun set(
        key: String,
        arrayOfTables: ArrayOfTables,
    ): EmptyResult<TomlEditError> = set(key, Item(arrayOfTables))

    /** Stores a string at [key]; see [set]. */
    public fun set(
        key: String,
        value: String,
    ): EmptyResult<TomlEditError> = set(key, Value.String(value))

    /** Stores an integer at [key]; see [set]. */
    public fun set(
        key: String,
        value: Long,
    ): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

    /** Stores an integer at [key]; see [set]. */
    public fun set(
        key: String,
        value: Int,
    ): EmptyResult<TomlEditError> = set(key, Value.Integer(value))

    /** Stores a float at [key]; see [set]. */
    public fun set(
        key: String,
        value: Double,
    ): EmptyResult<TomlEditError> = set(key, Value.Float(value))

    /** Stores a boolean at [key]; see [set]. */
    public fun set(
        key: String,
        value: Boolean,
    ): EmptyResult<TomlEditError> = set(key, Value.Boolean(value))

    /** Stores a date-time at [key]; see [set]. */
    public fun set(
        key: String,
        value: Datetime,
    ): EmptyResult<TomlEditError> = set(key, Value.Datetime(value))

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
     * Stores a copy of [item] at [index] of this array or array of tables. Fails as [assign] does, with
     * [TomlEditError.IndexOutOfBounds] when there is no such position, and with [TomlEditError.NotAnArray] when this
     * holds neither kind of array.
     */
    public fun set(
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
     * made a header put the comment lines above their first line above the header, and the comment lines between
     * their lines go.
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
     * Blank lines between the table's entries stay wherever the new layout has lines for them: under a header, as
     * dotted keys and in braces across lines; on one line they go, and none is left at the end of the file. Comment
     * lines a blank line sets apart from the entry below them keep their place among the lines around the table.
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
        if (layout == TableLayout.MultilineInline && document?.version == TomlVersion.V1_0) {
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
        val version = document?.version ?: TomlVersion.V1_1
        val lineBreak = (table ?: tables?.firstOrNull())?.headerLineEnding ?: newLineEnding()
        val layout = InlineLayout(version, lineBreak, multiline)
        val copy = table?.deepCopy()
        // Without a header of its own, the table's first lines are those above the first header printed under it.
        val firstLines = mutableListOf<KeptLines>()
        val earlierLines = mutableListOf<KeptLines>()
        when {
            copy == null -> Unit
            copy.isDotted || !copy.printsHeader(isArrayOfTables = false) -> {
                keepHeaderLinesOf(copy, isArrayOfTables = false, firstLines)
            }
            else -> keepLinesAboveEarlierHeaders(copy, earlierLines)
        }
        val value = when {
            copy != null -> copy.toInlineValue(layout, "")
            tables != null -> Value.Array(tables.deepCopy().moveToTomlArrayKeepingComments(layout, ""))
            else -> return Ok(Unit)
        }
        tooDeepToHold(Item(value))?.let { return Err(it) }
        holdValue(
            value,
            keptInside = multiline,
            firstAbove = earlierLines.joinToString("") { it.text },
            alsoAbove = firstLines.joinToString("") { it.text },
        )
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
    private fun newLineEnding(): String = document?.lineEnding ?: LINE_BREAK

    /**
     * Turns a table or array of tables in this slot into [value], made from it, keeping the lines above; with
     * [keptInside], [value] holds the comment lines among a dotted table's lines, which stay out of the lines above;
     * [firstAbove] goes above the key before them and [alsoAbove] after them.
     */
    internal fun holdValue(
        value: Value,
        keptInside: Boolean = false,
        firstAbove: String = "",
        alsoAbove: String = "",
    ) {
        val linesAbove = entry?.takeLinesAbove(Item(value), keptInside).orEmpty()
        entry?.prepareFor(Item(value))
        content = Content.ValueContent(value)
        entry?.keepLinesAbove(firstAbove + linesAbove + alsoAbove)
    }

    /**
     * Makes a dotted inline table printing on a table's lines the dotted table holding its entries that reading its
     * text gives, nothing else changing; null when this holds no such table.
     */
    internal fun takeReadDottedTable(): StandardTable? {
        val inline = inlineTableOrNull?.takeIf { it.isDotted && owner is StandardTable } ?: return null
        val table = StandardTable(inline.items.takeAll()).also { it.markDotted(true) }
        content = Content.TableContent(table)
        return table
    }

    /** Holds [value], what reading this slot's text gives, such as `{}` for an empty dotted table, and nothing else. */
    internal fun takeReadValue(value: Value) {
        content = Content.ValueContent(value)
    }

    /**
     * This item as an array of tables, moving a non-empty array of inline tables into one; null for anything
     * else.
     */
    internal fun moveToArrayOfTables(): ArrayOfTables? = when (val content = content) {
        is Content.ArrayOfTablesContent -> content.array
        is Content.ValueContent -> inlineTablesOfArray()?.let { inlines ->
            ArrayOfTables().also { tables ->
                for (inline in inlines) tables.append(inline.moveToStandardTable())
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
     * Formats this slot's line and what it holds as [TomlDocument.format] formats a document: in a table its key, the
     * text around its value and the value, or its table's header and everything under it; in an array the text around
     * its value too. The rest of the document stays as it is. A slot stored nowhere formats what it holds.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("a   =   [ 1 ,2 ]   #c\nb=2\n").getOrElse { error(it) }
     * doc["a"].getOrNull()?.format()
     * doc.toString() // "a = [1, 2] # c\nb=2\n"
     * ```
     */
    public fun format() {
        formatInPlace(this)
    }

    /**
     * Writes what this slot holds in its default spelling, as [TomlDocument.respell] does: its value, and every value
     * inside it or under the table or array of tables it holds. Keys and the text around the value stay.
     */
    public fun respell() {
        when (val content = content) {
            is Content.ValueContent -> content.value.respell()
            is Content.TableContent -> content.table.respell()
            is Content.ArrayOfTablesContent -> content.array.respell()
        }
    }

    /**
     * A deep copy of this item, stored nowhere and free to be put anywhere: exactly what storing this item would
     * store. A copy of an item read from a place in a document takes the comments that belong to it there, the comment
     * lines right above it, its same-line comment and its header's, and the comments inside it, and leaves the comment
     * lines set apart from it by a blank line. So with `val db = src["db"].getOrElse { error(it) }`,
     * `dst.set("db", db.copy())` prints the same as `dst.set("db", db)`. Comments a caller set through `decor` stay.
     * A copy of an item stored nowhere, built in code or returned by `remove`, keeps all its comments.
     */
    public fun copy(): Item = Item(content.copyOutOfBraces(), Holds.Anything).also { copy ->
        copy.copiedWithin = outermostTableOfSource(this) ?: copiedWithin
        copy.content.keepDocument(document)
        copy.carriedAbove = carriedAboveOf(this, copy.content)
    }

    /** For a copy stored nowhere, the outermost table of the document it was read from; see [copiesWithinDocument]. */
    internal var copiedWithin: StandardTable? = null

    /**
     * For a slot stored nowhere, the comment lines directly above the line of the value it holds where it was read,
     * which go above that line where it is stored.
     */
    internal var carriedAbove: String = ""

    /** Takes out the comment lines this slot, stored nowhere, brings above what it prints; see [carriedAbove]. */
    internal fun takeCarriedAbove(): String =
        (carriedAbove + content.takeAttachedAbove()).also { carriedAbove = "" }

    /**
     * The comment lines [source] brings above what it prints, keeping in [copied], a copy of its content, only the
     * comments [source] owns where it was read; an item stored nowhere brings what it holds.
     */
    private fun carriedAboveOf(
        source: Item,
        copied: Content,
    ): String {
        val slot = source.takeIf { it.owner != null } ?: source.container?.let(::slotOf)
        if (slot != null) {
            val above = copied.keepOwnComments(ownCommentsOf(slot))
            // A container a caller wrapped brings what its own copy() holds, which has no lines above a value.
            return if (slot === source) above else ""
        }
        val removed = source.entry?.takeUnless { it.isStored } ?: return source.carriedAbove
        return copied.keepOwnComments(removed.removedComments())
    }

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
