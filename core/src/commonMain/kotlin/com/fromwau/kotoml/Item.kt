package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map

/**
 * A slot in a table or array holding nothing, a [Value], a [Table] or an [ArrayOfTables].
 *
 * An item is a place, not just a value: the tables and arrays store items, and indexing hands out the item
 * stored there, so writing through it changes the document.
 *
 * ```kotlin
 * val doc = DocumentMut()
 * doc["a"]["b"] = value("c") // a = { b = "c" }
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
            is TableContent -> TableContent(table.clone())
            is ArrayOfTablesContent -> ArrayOfTablesContent(array.clone())
        }
    }

    private fun refusal(content: Content): TomlEditError? = when (holds) {
        Holds.Anything -> null
        Holds.Value -> TomlEditError.NotAValue(content.type).takeUnless { content is Content.ValueContent }
        Holds.Table -> TomlEditError.NotATableElement(content.type).takeUnless { content is Content.TableContent }
    }

    /** A slot of kind [holds] holding a copy of this item's content, or the refusal when it cannot hold it. */
    internal fun copyAsSlot(holds: Holds): Result<Item, TomlEditError> {
        val slot = Item(Content.None, null, holds)
        return slot.write(content.deepCopy()).map { slot }
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

    /** Puts a copy of [item]'s content in this slot if it is empty, and returns this item. */
    public fun orInsert(item: Item): Result<Item, TomlEditError> {
        if (isNone()) {
            val written = assign(item)
            if (written is Result.Error) return written
        }
        return Ok(this)
    }

    /** The kind of item. */
    public val type: TomlType
        get() = content.type

    /**
     * The item at [key] of this table or inline table, or null when there is none or this is not one:
     * upstream's `get`, renamed for the index operator.
     */
    public fun getOrNull(key: String): Item? = when (val content = content) {
        is Content.TableContent -> content.table.getOrNull(key)
        is Content.ValueContent -> content.value.inlineTableOrNull?.items?.get(key)?.takeIf { !it.isNone() }
        else -> null
    }

    /** The item at [index] of this array or array of tables, or null when there is none or this is not one. */
    public fun getOrNull(index: Int): Item? = when (val content = content) {
        is Content.ArrayOfTablesContent -> content.array.values.getOrNull(index)
        is Content.ValueContent -> content.value.arrayOrNull?.values?.getOrNull(index)
        else -> null
    }

    /** As [getOrNull], to change in place. */
    public fun getMut(key: String): Item? = getOrNull(key)

    /** As [getOrNull], to change in place. */
    public fun getMut(index: Int): Item? = getOrNull(index)

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

    /** Stores a copy of [item] at [key], as `this[key].assign(item)` does. */
    public operator fun set(key: String, item: Item): EmptyResult<TomlEditError> = get(key).assign(item)

    /**
     * Stores a copy of [item] at [index] of this array or array of tables. Fails when there is no such position,
     * or when [item] does not hold a value for an array or a table for an array of tables.
     */
    public operator fun set(index: Int, item: Item): EmptyResult<TomlEditError> {
        val slot = getOrNull(index) ?: return Err(
            if (arrayOrNull != null || isArrayOfTables()) {
                TomlEditError.IndexOutOfBounds(index, arrayOrNull?.len() ?: asArrayOfTables()?.len() ?: 0)
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

    internal fun holdTable(table: Table) {
        content = Content.TableContent(table)
    }

    /** The value, if this holds one. */
    public fun asValue(): Value? = (content as? Content.ValueContent)?.value

    /**
     * The table, if this holds one. An inline table is a value; see [asTableLike] to handle both.
     */
    public fun asTable(): Table? = (content as? Content.TableContent)?.table

    /** The array of tables, if this holds one. */
    public fun asArrayOfTables(): ArrayOfTables? = (content as? Content.ArrayOfTablesContent)?.array

    /** The value, to change in place. */
    public fun asValueMut(): Value? = asValue()

    /** The table, to change in place. */
    public fun asTableMut(): Table? = asTable()

    /** The array of tables, to change in place. */
    public fun asArrayOfTablesMut(): ArrayOfTables? = asArrayOfTables()

    /**
     * This item as a value: a table becomes an inline table and an array of tables an array, both moving
     * their content. Null when this item is empty. Upstream returns the item back as the error.
     */
    public fun intoValue(): Value? = when (val content = content) {
        Content.None -> null
        is Content.ValueContent -> content.value
        is Content.TableContent -> Value.InlineTable(content.table.intoInlineTable())
        is Content.ArrayOfTablesContent -> Value.Array(content.array.intoArray())
    }

    /**
     * Turns this item into a value in place, as [intoValue] does; an empty item stays empty. Fails, changing
     * nothing, when this is a table in an array of tables.
     */
    public fun makeValue(): EmptyResult<TomlEditError> {
        if (holds == Holds.Table) return Err(TomlEditError.NotATableElement(TomlType.InlineTable))
        val value = intoValue()
        content = if (value != null) Content.ValueContent(value) else Content.None
        return Ok(Unit)
    }

    /** This item as a table, an inline table being turned into one; null for anything else. */
    public fun intoTable(): Table? = when (val content = content) {
        is Content.TableContent -> content.table
        is Content.ValueContent -> content.value.inlineTableOrNull?.intoTable()
        else -> null
    }

    /**
     * This item as an array of tables, a non-empty array of inline tables being turned into one; null for
     * anything else.
     */
    public fun intoArrayOfTables(): ArrayOfTables? = when (val content = content) {
        is Content.ArrayOfTablesContent -> content.array
        is Content.ValueContent -> {
            val array = content.value.arrayOrNull
            if (array == null || array.isEmpty() || !array.all { it is Value.InlineTable }) {
                null
            } else {
                ArrayOfTables.fromIter(array.mapNotNull { it.inlineTableOrNull?.intoTable() })
            }
        }
        else -> null
    }

    /** Whether this holds a value. */
    public fun isValue(): Boolean = content is Content.ValueContent

    /** Whether this holds a table; an inline table is a value. */
    public fun isTable(): Boolean = content is Content.TableContent

    /** Whether this holds an array of tables. */
    public fun isArrayOfTables(): Boolean = content is Content.ArrayOfTablesContent

    /** Whether this is empty. */
    public fun isNone(): Boolean = content == Content.None

    /** The string, or null when this holds none. */
    public val stringOrNull: String?
        get() = asValue()?.stringOrNull

    /** The integer, or null when this holds none. */
    public val longOrNull: Long?
        get() = asValue()?.longOrNull

    /** The float, or null when this holds none. */
    public val doubleOrNull: Double?
        get() = asValue()?.doubleOrNull

    /** The boolean, or null when this holds none. */
    public val booleanOrNull: Boolean?
        get() = asValue()?.booleanOrNull

    /** The date-time, or null when this holds none. */
    public val datetimeOrNull: Datetime?
        get() = asValue()?.datetimeOrNull

    /** The array, or null when this holds none. Changing the array changes this item. */
    public val arrayOrNull: TomlArray?
        get() = asValue()?.arrayOrNull

    /** The inline table, or null when this holds none. Changing the table changes this item. */
    public val inlineTableOrNull: InlineTable?
        get() = asValue()?.inlineTableOrNull

    /** The table or inline table, if this holds either. */
    public fun asTableLike(): TableLike? = asTable() ?: inlineTableOrNull

    /** The table or inline table, to change in place. */
    public fun asTableLikeMut(): TableLike? = asTableLike()

    /** Whether this holds a table or an inline table. */
    public fun isTableLike(): Boolean = asTableLike() != null

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
    public fun clone(): Item = Item(content.deepCopy(), null, Holds.Anything)

    /** The item as TOML: nothing, a value, a table's key/value pairs, or an array of tables as an array. */
    override fun toString(): String = when (val content = content) {
        Content.None -> ""
        is Content.ValueContent -> content.value.toString()
        is Content.TableContent -> content.table.toString()
        is Content.ArrayOfTablesContent -> content.array.toString()
    }

    public companion object {
        /** An empty item, upstream's `Item::None`. */
        public fun none(): Item = Item(Content.None, null, Holds.Anything)

        /** An item holding [value]. */
        public fun from(value: Value): Item = Item(Content.ValueContent(value), null, Holds.Anything)

        /** An item holding [table]. */
        public fun from(table: Table): Item = Item(Content.TableContent(table), null, Holds.Anything)

        /** An item holding [array]. */
        public fun from(array: ArrayOfTables): Item =
            Item(Content.ArrayOfTablesContent(array), null, Holds.Anything)

        /** A copy of [item], upstream's `From<&Item>`. */
        public fun from(item: Item): Item = item.clone()

        /** An item holding a string. */
        public fun from(value: String): Item = from(Value.String(value))

        /** An item holding an integer. */
        public fun from(value: Long): Item = from(Value.Integer(value))

        /** An item holding a float. */
        public fun from(value: Double): Item = from(Value.Float(value))

        /** An item holding a boolean. */
        public fun from(value: Boolean): Item = from(Value.Boolean(value))

        /** An item holding a date-time. */
        public fun from(value: Datetime): Item = from(Value.Datetime(value))

        /** An item holding an array. */
        public fun from(value: TomlArray): Item = from(Value.Array(value))

        /** An item holding an inline table. */
        public fun from(value: InlineTable): Item = from(Value.InlineTable(value))

        /** Parses one TOML value of [version] into an item, keeping its spelling. */
        public fun parse(text: String, version: TomlVersion = TomlVersion.V1_1): Result<Item, TomlParseError> =
            when (val value = Value.parse(text, version)) {
                is Result.Success -> Ok(from(value.value))
                is Result.Error -> value
            }

        internal fun detached(
            holds: Holds,
            attach: (Item) -> EmptyResult<TomlEditError>,
        ): Item = Item(Content.None, attach, holds)

        /** A slot of a [TomlArray], which only ever holds a value. */
        internal fun inArray(value: Value): Item = Item(Content.ValueContent(value), null, Holds.Value)

        /** A slot of an [InlineTable], which only ever holds a value. */
        internal fun inInlineTable(value: Value): Item = Item(Content.ValueContent(value), null, Holds.Value)

        /** A slot of an [ArrayOfTables], which only ever holds a table. */
        internal fun inArrayOfTables(table: Table): Item = Item(Content.TableContent(table), null, Holds.Table)
    }
}

/**
 * An item holding [value], decorated for the right of `=` when it prints:
 *
 * ```kotlin
 * val table = Table()
 * table["key1"] = value("value1")
 * table["key2"] = value(42L)
 * table.toString() // "key1 = \"value1\"\nkey2 = 42\n"
 * ```
 */
public fun value(value: Value): Item = Item.from(value)

/** An item holding a string. */
public fun value(value: String): Item = Item.from(value)

/** An item holding an integer. */
public fun value(value: Long): Item = Item.from(value)

/** An item holding a float. */
public fun value(value: Double): Item = Item.from(value)

/** An item holding a boolean. */
public fun value(value: Boolean): Item = Item.from(value)

/** An item holding a date-time. */
public fun value(value: Datetime): Item = Item.from(value)

/** An item holding an array. */
public fun value(value: TomlArray): Item = Item.from(value)

/** An item holding an inline table. */
public fun value(value: InlineTable): Item = Item.from(value)

/** An item holding an empty table. */
public fun table(): Item = Item.from(Table())

/** An item holding an empty array of tables. */
public fun array(): Item = Item.from(ArrayOfTables())
