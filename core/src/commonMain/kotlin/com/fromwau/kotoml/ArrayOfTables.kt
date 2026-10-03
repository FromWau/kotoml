package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A sequence of tables, each under its own `[[header]]`.
 *
 * A table added here is stored as is, not copied: keep using it only through the array, or add its `copy()`.
 */
public class ArrayOfTables() : Iterable<Table> {
    internal var span: Span? = null

    // Slots that refuse anything but a table; held as items so indexing an item hands out the same slots.
    internal val values: MutableList<Item> = mutableListOf()

    // Lines above the headers of tables removed from this array, printed among the tables by their position.
    internal val keptHeaderLines: MutableList<KeptLines> = mutableListOf()

    /** An inline array of inline tables holding copies of these tables, formatted anew; this stays as it is. */
    public fun toArray(): TomlArray = copy().intoArray()

    /** Moves the tables into a new inline array of inline tables, formatted anew, leaving this empty. */
    internal fun intoArray(): TomlArray {
        val array = values.mapNotNull { it.intoValue() }.toTomlArray()
        values.clear()
        array.resetFormatting()
        return array
    }

    internal fun despan(input: SourceText) {
        span = null
        for (value in values) value.despan(input)
    }

    override fun iterator(): Iterator<Table> = values.mapNotNull { it.tableOrNull }.iterator()

    /** The number of tables. */
    public val size: Int
        get() = values.size

    /** Whether there are no tables. */
    public fun isEmpty(): Boolean = size == 0

    /** Removes every table, each as [removeAt] removes it, so the lines above each `[[header]]` stay. */
    public fun clear() {
        retainAll { false }
    }

    /** The table at [index], or null when the index is out of bounds. */
    public operator fun get(index: Int): Table? = values.getOrNull(index)?.tableOrNull

    /** Appends [table]. */
    public fun add(table: Table) {
        values.add(Item.inArrayOfTables(table))
    }

    /**
     * Inserts [table] at [index], shifting later tables right.
     *
     * @return [TomlEditError.IndexOutOfBounds], with nothing inserted, when [index] is negative or greater
     *   than [size].
     */
    public fun add(
        index: Int,
        table: Table,
    ): EmptyResult<TomlEditError> {
        if (index < 0 || index > values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        values.add(index, Item.inArrayOfTables(table))
        return Ok(Unit)
    }

    /**
     * Replaces the table at [index] with [table].
     *
     * @return the old table, or [TomlEditError.IndexOutOfBounds], with nothing replaced, when there is no table
     *   at [index].
     */
    public operator fun set(
        index: Int,
        table: Table,
    ): Result<Table, TomlEditError> {
        val old = get(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        values[index] = Item.inArrayOfTables(table)
        return Ok(old)
    }

    /**
     * Removes the table at [index]: its `[[header]]` line and its body, with the tables under it and the comment and
     * blank lines above their headers. The lines above its own `[[header]]` stay where they print, now above
     * whatever follows. Upstream drops those lines with the table.
     *
     * @return the removed table, or [TomlEditError.IndexOutOfBounds] when there is no table at [index].
     */
    public fun removeAt(index: Int): Result<Table, TomlEditError> {
        val removed = get(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        keepHeaderLinesOf(removed, isArrayOfTables = true, keptHeaderLines)
        values.removeAt(index)
        return Ok(removed)
    }

    /**
     * Keeps only the tables [predicate] accepts, in order. A removed table goes as [removeAt] removes it, so the
     * lines above its `[[header]]` stay.
     */
    public fun retainAll(predicate: (Table) -> Boolean) {
        val removed = values.filterNot { item -> item.tableOrNull?.let(predicate) ?: false }
        for (item in removed) item.tableOrNull?.let { keepHeaderLinesOf(it, isArrayOfTables = true, keptHeaderLines) }
        val removedSlots = removed.toHashSet()
        values.removeAll { it in removedSlots }
    }

    /** Appends every table of [tables]. */
    public fun addAll(tables: Iterable<Table>) {
        for (table in tables) add(table)
    }

    /** A deep copy. */
    public fun copy(): ArrayOfTables {
        val copy = ArrayOfTables()
        copy.span = span
        for (table in this) copy.values.add(Item.inArrayOfTables(table.copy()))
        keptHeaderLines.mapTo(copy.keptHeaderLines) { it.copy() }
        return copy
    }

    /** The tables as an inline array; without a header there is no better way to print them. */
    override fun toString(): String = toArray().toString()
}

/** An array of [tables], in order. */
public fun arrayOfTablesOf(vararg tables: Table): ArrayOfTables = tables.asIterable().toArrayOfTables()

/** An array of these tables, in order. */
public fun Iterable<Table>.toArrayOfTables(): ArrayOfTables {
    val array = ArrayOfTables()
    array.addAll(this)
    return array
}
