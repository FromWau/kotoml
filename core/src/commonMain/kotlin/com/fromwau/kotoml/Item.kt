package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A slot in a table or array holding nothing, a [Value], a [Table] or an [ArrayOfTables].
 *
 * An item is a place, not just a value: the tables and arrays store items, and indexing hands out the item
 * stored there, so writing through it changes the document.
 *
 * ```kotlin
 * val doc = TomlDocument()
 * doc["a"]["b"] = "c" // a = { b = "c" }
 * ```
 *
 * Storing an item stores a copy of what it holds, so two places never share one slot or one table or value:
 * after `doc["b"] = doc["a"]`, editing `doc["a"]` leaves `doc["b"]` as it was.
 *
 * A slot inside an array or an inline table holds only a value, and one inside an array of tables only a table;
 * writing anything else there fails with [TomlEditError.NotAValue] or [TomlEditError.NotATableElement].
 *
 * Indexing a key or position that is not there returns an empty item that joins its parent when it is
 * first written to; until then nothing is added. Indexing into an empty item makes it an inline table when
 * written to, as upstream does. Writing through an index that cannot hold the write, such as a key of an
 * integer or a position past the end of an array, fails with [TomlEditError]; upstream panics there. In the
 * `item[key] = x` form the failure is discarded, so call [set] to see it.
 */
public class Item private constructor(
    private var content: Content,
    private var attach: ((Item) -> EmptyResult<TomlEditError>)?,
    private val holds: Holds,
) {

    /** An empty item. */
    public constructor() : this(Content.None, null, Holds.Anything)

    /** An item holding [value], as is. */
    public constructor(value: Value) : this(Content.ValueContent(value), null, Holds.Anything)

    /** An item holding [table], as is. */
    public constructor(table: Table) : this(Content.TableContent(table), null, Holds.Anything)

    /** An item holding [arrayOfTables], as is. */
    public constructor(arrayOfTables: ArrayOfTables) :
        this(Content.ArrayOfTablesContent(arrayOfTables), null, Holds.Anything)

    /** What a slot may hold. */
    internal enum class Holds {
        Anything,
        Value,
        Table,
    }

    private sealed interface Content {
        data object None : Content

        class ValueContent(val value: Value) : Content

        class TableContent(val table: Table) : Content

        class ArrayOfTablesContent(val array: ArrayOfTables) : Content

        val type: TomlType
            get() = when (this) {
                None -> TomlType.None
                is ValueContent -> value.type
                is TableContent -> TomlType.Table
                is ArrayOfTablesContent -> TomlType.ArrayOfTables
            }

        fun deepCopy(): Content = when (this) {
            None -> None
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

    /** A deep copy that stays the same kind of slot. */
    internal fun copySlot(): Item = Item(content.deepCopy(), null, holds)

    /** This item's content, not copied, in a slot of kind [holds]; for moving entries between containers. */
    internal fun movedTo(holds: Holds): Item = Item(content, null, holds)

    /** Joins this item to its parent if it was handed out empty, before its first write. */
    private fun ensureAttached(): EmptyResult<TomlEditError> {
        val attach = attach ?: return Ok(Unit)
        val result = attach(this)
        if (result is Result.Success) this.attach = null
        return result
    }

    private fun write(content: Content): EmptyResult<TomlEditError> {
        refusal(content)?.let { return Err(it) }
        val attached = ensureAttached()
        if (attached is Result.Error) return attached
        this.content = content
        return Ok(Unit)
    }

    /**
     * Puts a copy of [item]'s content in this slot, Rust's `*item = other.clone()`. Fails when this slot is in an
     * array or an inline table and [item] holds no value, or in an array of tables and [item] holds no table.
     */
    public fun assign(item: Item): EmptyResult<TomlEditError> = write(item.content.deepCopy())

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
     * The slot at [key]: the item stored there, or an empty one that joins this table, inline table or empty
     * item under [key] when it is first written to.
     */
    public operator fun get(key: String): Item {
        when (val content = content) {
            is Content.TableContent -> content.table.items.get(key)?.let { return it }
            is Content.ValueContent -> content.value.inlineTableOrNull?.items?.get(key)?.let { return it }
            else -> {}
        }
        // Writing through a key of an empty item makes it an inline table, so the new slot holds values only.
        val childHolds = if (content is Content.TableContent) Holds.Anything else Holds.Value
        return detached(childHolds) { child -> attachChild(key, child) }
    }

    /** The slot at [index] of this array or array of tables, or an empty one that cannot be written to. */
    public operator fun get(index: Int): Item = getOrNull(index) ?: detached(Holds.Anything) {
        Err(TomlEditError.NotAnArray(index, type))
    }

    /**
     * Stores a copy of [item] at [key], as `this[key].assign(item)` does.
     *
     * @return [TomlEditError.NotATable] when this item holds neither a table nor an inline table, or
     *   [TomlEditError.NotAValue] when this is an inline table, or empty, and [item] holds no value.
     */
    public operator fun set(
        key: String,
        item: Item,
    ): EmptyResult<TomlEditError> = get(key).assign(item)

    /** Stores a copy of [value] at [key]; see [set]. */
    public operator fun set(
        key: String,
        value: Value,
    ): EmptyResult<TomlEditError> = set(key, Item(value))

    /** Stores a copy of [table] at [key]; see [set]. */
    public operator fun set(
        key: String,
        table: Table,
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
        val size = arrayOrNull?.size ?: arrayOfTablesOrNull?.size
        val slot = getOrNull(index) ?: return Err(
            if (size != null) {
                TomlEditError.IndexOutOfBounds(index, size)
            } else {
                TomlEditError.NotAnArray(index, type)
            },
        )
        return slot.assign(item)
    }

    private fun attachChild(key: String, child: Item): EmptyResult<TomlEditError> {
        when (val content = content) {
            Content.None -> {
                val table = InlineTable()
                table.items.insert(Key(key), child)
                return write(Content.ValueContent(Value.InlineTable(table)))
            }
            is Content.TableContent -> {
                val attached = ensureAttached()
                if (attached is Result.Error) return attached
                content.table.items.insert(Key(key), child)
            }
            is Content.ValueContent -> {
                val table = content.value.inlineTableOrNull ?: return Err(TomlEditError.NotATable(key, type))
                val attached = ensureAttached()
                if (attached is Result.Error) return attached
                table.items.insert(Key(key), child)
            }
            is Content.ArrayOfTablesContent -> return Err(TomlEditError.NotATable(key, type))
        }
        return Ok(Unit)
    }

    /** The value, or null when this holds none. Changing the value changes this item. */
    public val valueOrNull: Value?
        get() = (content as? Content.ValueContent)?.value

    /** The table, or null when this holds none. An inline table is a value; [tableLikeOrNull] reads both. */
    public val tableOrNull: Table?
        get() = (content as? Content.TableContent)?.table

    /** The array of tables, or null when this holds none. */
    public val arrayOfTablesOrNull: ArrayOfTables?
        get() = (content as? Content.ArrayOfTablesContent)?.array

    /** The table or inline table, or null when this holds neither. */
    public val tableLikeOrNull: TableLike?
        get() = tableOrNull ?: inlineTableOrNull

    /**
     * This item as a value: a table becomes an inline table and an array of tables an array, both moving
     * their content. Null when this item is empty.
     */
    internal fun intoValue(): Value? = when (val content = content) {
        Content.None -> null
        is Content.ValueContent -> content.value
        is Content.TableContent -> Value.InlineTable(content.table.intoInlineTable())
        is Content.ArrayOfTablesContent -> Value.Array(content.array.intoTomlArray())
    }

    /**
     * Turns a table in this slot into an inline table and an array of tables into an array of inline tables,
     * formatted anew; a value or an empty item stays as it is.
     *
     * @return [TomlEditError.NotATableElement], changing nothing, when this is a table in an array of tables.
     */
    public fun makeValue(): EmptyResult<TomlEditError> {
        if (holds == Holds.Table) return Err(TomlEditError.NotATableElement(TomlType.InlineTable))
        val value = intoValue()
        content = if (value != null) Content.ValueContent(value) else Content.None
        return Ok(Unit)
    }

    /** This item as a table, moving an inline table's entries into one; null for anything else. */
    internal fun intoTable(): Table? = when (val content = content) {
        is Content.TableContent -> content.table
        is Content.ValueContent -> content.value.inlineTableOrNull?.intoTable()
        else -> null
    }

    /**
     * This item as an array of tables, moving a non-empty array of inline tables into one; null for anything
     * else.
     */
    internal fun intoArrayOfTables(): ArrayOfTables? = when (val content = content) {
        is Content.ArrayOfTablesContent -> content.array
        is Content.ValueContent -> {
            val array = content.value.arrayOrNull
            if (array == null || array.isEmpty() || !array.all { it is Value.InlineTable }) {
                null
            } else {
                array.mapNotNull { it.inlineTableOrNull?.intoTable() }.toArrayOfTables()
            }
        }
        else -> null
    }

    /** Whether this is empty. */
    public val isNone: Boolean
        get() = content == Content.None

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

    internal fun span(): Span? = when (val content = content) {
        Content.None -> null
        is Content.ValueContent -> content.value.span()
        is Content.TableContent -> content.table.span
        is Content.ArrayOfTablesContent -> content.array.span
    }

    internal fun despan(input: SourceText) {
        when (val content = content) {
            Content.None -> {}
            is Content.ValueContent -> content.value.despan(input)
            is Content.TableContent -> content.table.despan(input)
            is Content.ArrayOfTablesContent -> content.array.despan(input)
        }
    }

    /** A deep copy of this item, free to be put anywhere. */
    public fun copy(): Item = Item(content.deepCopy(), null, Holds.Anything)

    /** The item as TOML: nothing, a value, a table's key/value pairs, or an array of tables as an array. */
    override fun toString(): String = when (val content = content) {
        Content.None -> ""
        is Content.ValueContent -> content.value.toString()
        is Content.TableContent -> content.table.toString()
        is Content.ArrayOfTablesContent -> content.array.toString()
    }

    internal companion object {
        fun detached(
            holds: Holds,
            attach: (Item) -> EmptyResult<TomlEditError>,
        ): Item = Item(Content.None, attach, holds)

        /** A slot of a [TomlArray], which only ever holds a value. */
        fun inArray(value: Value): Item = Item(Content.ValueContent(value), null, Holds.Value)

        /** A slot of an [InlineTable], which only ever holds a value. */
        fun inInlineTable(value: Value): Item = Item(Content.ValueContent(value), null, Holds.Value)

        /** A slot of an [ArrayOfTables], which only ever holds a table. */
        fun inArrayOfTables(table: Table): Item = Item(Content.TableContent(table), null, Holds.Table)
    }
}
