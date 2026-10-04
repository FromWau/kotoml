package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A slot in a table or array holding a [Value], a [StandardTable] or an [ArrayOfTables].
 *
 * An item is a place, not just a value: the tables and arrays store items, and indexing hands out the item
 * stored there, so writing through it changes the document.
 *
 * ```kotlin
 * val doc = TomlDocument.parse("[server]\nport = 8080\n").getOrElse { error(it) }
 * doc["server"]["port"] = 9090L
 * doc["server"]["port"].getOrNull()?.longOrNull // 9090
 * doc["database"]["url"] // Err(NotFound("database"))
 * ```
 *
 * Storing an item stores a copy of what it holds, so two places never share one slot or one table or value:
 * after `doc["b"] = doc["a"].getOrElse { error(it) }`, editing `doc["a"]` leaves `doc["b"]` as it was.
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
    private var content: Content,
    private var holds: Holds,
) {

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

        fun deepCopy(): Content = when (this) {
            is ValueContent -> ValueContent(value.copy())
            is TableContent -> TableContent(table.copy())
            is ArrayOfTablesContent -> ArrayOfTablesContent(array.copy())
        }
    }

    private fun refusal(content: Content): TomlEditError? = when (holds) {
        Holds.Anything -> null
        Holds.Value -> TomlEditError.NotAValue(content.type).takeUnless { content is Content.ValueContent }
        Holds.Table -> TomlEditError.NotATableElement(content.type).takeUnless { content is Content.TableContent }
    }

    /** The table entry this slot is stored in, if any; its key's spacing depends on what the slot holds. */
    internal var entry: KeyValue? = null

    /** A deep copy that stays the same kind of slot. */
    internal fun copySlot(): Item = Item(content.deepCopy(), holds)

    /** This slot, made a slot of kind [holds] for the container it moves into. */
    internal fun movedTo(holds: Holds): Item {
        this.holds = holds
        return this
    }

    /** Takes [next]'s content, not copied, and returns an item holding what this slot held before. */
    internal fun takeContentOf(next: Item): Item {
        val old = Item(content, Holds.Anything)
        content = next.content
        return old
    }

    /**
     * Replaces this slot's content with a copy of [item]'s; a key keeps the lines above it, as [StandardTable.put]
     * says. Fails when this slot is in an array or an inline table and [item] holds no value, or in an array of
     * tables and [item] holds no table.
     */
    public fun assign(item: Item): EmptyResult<TomlEditError> {
        val next = item.content.deepCopy()
        refusal(next)?.let { return Err(it) }
        val entry = entry
        if (entry == null) {
            content = next
            return Ok(Unit)
        }
        entry.prepareFor(item)
        val linesAbove = entry.takeLinesAbove(item)
        val oldDecor = valueOrNull?.rawDecor
        content = next
        entry.keepValueDecor(oldDecor)
        entry.keepLinesAbove(linesAbove)
        return Ok(Unit)
    }

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
     * @return [TomlEditError.NotATable] when this item holds neither a table nor an inline table, or
     *   [TomlEditError.NotAValue] when this is an inline table and [item] holds no value.
     */
    public operator fun set(
        key: String,
        item: Item,
    ): EmptyResult<TomlEditError> {
        standardTableOrNull?.let { table ->
            table[key] = item
            return Ok(Unit)
        }
        val inline = inlineTableOrNull ?: return Err(TomlEditError.NotATable(key, type))
        val value = item.valueOrNull ?: return Err(TomlEditError.NotAValue(item.type))
        inline[key] = value
        return Ok(Unit)
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
     * Stores a copy of [item] at [index] of this array or array of tables. Fails when there is no such position,
     * or when [item] does not hold a value for an array or a table for an array of tables.
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
     * Turns a table in this slot into an inline table and an array of tables into an array of inline tables,
     * formatted anew; a value stays as it is. The comment lines above the header move above the key.
     *
     * @return [TomlEditError.NotATableElement], changing nothing, when this is a table in an array of tables.
     */
    public fun makeValue(): EmptyResult<TomlEditError> {
        if (holds == Holds.Table) return Err(TomlEditError.NotATableElement(TomlType.InlineTable))
        val linesAbove = entry?.linesAboveHeader().orEmpty()
        content = Content.ValueContent(moveToValue())
        entry?.keepLinesAbove(linesAbove)
        return Ok(Unit)
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
                array.mapNotNull { it.inlineTableOrNull?.moveToStandardTable() }.toArrayOfTables()
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

    /** A deep copy of this item, free to be put anywhere. */
    public fun copy(): Item = Item(content.deepCopy(), Holds.Anything)

    /** The item as TOML: a value, a table's key/value pairs, or an array of tables as an array. */
    override fun toString(): String = when (val content = content) {
        is Content.ValueContent -> content.value.toString()
        is Content.TableContent -> content.table.toString()
        is Content.ArrayOfTablesContent -> content.array.toString()
    }

    internal companion object {
        /** A slot of a [TomlArray], which only ever holds a value. */
        fun inArray(value: Value): Item = Item(Content.ValueContent(value), Holds.Value)

        /** A slot of an [InlineTable], which only ever holds a value. */
        fun inInlineTable(value: Value): Item = Item(Content.ValueContent(value), Holds.Value)

        /** A slot of an [ArrayOfTables], which only ever holds a table. */
        fun inArrayOfTables(table: StandardTable): Item = Item(Content.TableContent(table), Holds.Table)
    }
}
