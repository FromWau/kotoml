package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/** A sequence of tables, each under its own `[[header]]`. */
public class ArrayOfTables() : Iterable<Table> {
    internal var span: Span? = null

    // Slots that refuse anything but a table; held as items so indexing an item hands out the same slots.
    internal val values: MutableList<Item> = mutableListOf()

    /** Turns this into an inline array of inline tables, formatted anew. Its tables move to the result. */
    public fun intoArray(): TomlArray {
        val array = TomlArray.fromIter(values.mapNotNull { it.intoValue() })
        values.clear()
        array.fmt()
        return array
    }

    internal fun despan(input: SourceText) {
        span = null
        for (value in values) value.despan(input)
    }

    /** The tables, in order. */
    public fun iter(): Iterator<Table> = values.mapNotNull { it.asTable() }.iterator()

    /** The tables, in order, to change in place. */
    public fun iterMut(): Iterator<Table> = iter()

    override fun iterator(): Iterator<Table> = iter()

    /** The number of tables. */
    public fun len(): Int = values.size

    /** Whether there are no elements. */
    public fun isEmpty(): Boolean = len() == 0

    /** Removes every table. */
    public fun clear() {
        values.clear()
    }

    /** The table at [index], or null when the index is out of bounds. */
    public operator fun get(index: Int): Table? = values.getOrNull(index)?.asTable()

    /** The table at [index] to change in place, or null when the index is out of bounds. */
    public fun getMut(index: Int): Table? = get(index)

    /** Appends [table]. */
    public fun push(table: Table) {
        values.add(Item.inArrayOfTables(table))
    }

    /** Inserts [table] at [index]. Fails when [index] is greater than [len]; upstream panics there. */
    public fun insert(index: Int, table: Table): EmptyResult<TomlEditError> {
        if (index < 0 || index > values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        values.add(index, Item.inArrayOfTables(table))
        return Ok(Unit)
    }

    /** Replaces the table at [index], returning the old one. Fails when [index] is out of bounds. */
    public fun replace(index: Int, table: Table): Result<Table, TomlEditError> {
        if (index < 0 || index >= values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        val old = values[index]
        values[index] = Item.inArrayOfTables(table)
        return old.asTable()?.let { Ok(it) } ?: Err(TomlEditError.NotAValue(old.type))
    }

    /** Removes and returns the table at [index]. Fails when [index] is out of bounds; upstream panics. */
    public fun remove(index: Int): Result<Table, TomlEditError> {
        if (index < 0 || index >= values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        val removed = values.removeAt(index)
        return removed.intoTable()?.let { Ok(it) } ?: Err(TomlEditError.NotAValue(removed.type))
    }

    /** Keeps only the tables [keep] accepts, in order. */
    public fun retain(keep: (Table) -> Boolean) {
        values.retainAll { item -> item.asTable()?.let(keep) ?: false }
    }

    /** Appends every table of [tables]. */
    public fun extend(tables: Iterable<Table>) {
        for (table in tables) push(table)
    }

    /** A deep copy. */
    public fun clone(): ArrayOfTables {
        val copy = ArrayOfTables()
        copy.span = span
        for (table in iter()) copy.values.add(Item.inArrayOfTables(table.clone()))
        return copy
    }

    /** The tables as an inline array; without a header there is no better way to print them. */
    override fun toString(): String = clone().intoArray().toString()

    public companion object {
        /** An array of [tables]. */
        public fun fromIter(tables: Iterable<Table>): ArrayOfTables {
            val array = ArrayOfTables()
            array.extend(tables)
            return array
        }
    }
}
