package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * An inline table value, `{ a = 1, b.c = 2 }`, with its formatting.
 *
 * Its entries are values. A dotted key such as `b.c` is held as a nested inline table marked dotted, so it
 * prints back as a dotted key rather than as `b = { c = 2 }`.
 */
public class InlineTable() : TableLike, Iterable<Pair<String, Value>> {
    private var trailing: RawString = RawString()
    private var trailingComma: Boolean = false
    private var implicit: Boolean = false
    private val decor: Decor = Decor()
    internal var span: Span? = null
    private var dotted: Boolean = false
    internal var items: KeyValuePairs = KeyValuePairs()

    internal constructor(items: KeyValuePairs) : this() {
        this.items = items
    }

    /** Turns this inline table into a standard table, moving its entries there and formatting it anew. */
    public fun intoTable(): Table {
        val table = Table(items.takeAll())
        table.fmt()
        return table
    }

    /** The values visually under this table with the key path that reaches each, dotted keys followed. */
    override fun getValues(): List<Pair<List<Key>, Value>> = visualValues().map { it.path to it.value }

    /** The values in the order they print: as read from text, new ones after their sibling. */
    internal fun visualValues(): List<VisualValue> {
        val values = mutableListOf<VisualValue>()
        appendValues(mutableListOf(), values)
        return inPrintOrder(values)
    }

    internal fun appendValues(path: MutableList<Key>, values: MutableList<VisualValue>) {
        for (entry in items.entries()) {
            path.add(entry.key)
            val item = entry.item
            val value = item.asValue()
            val table = item.asTable()
            when {
                value is Value.InlineTable && value.table.isDotted() -> value.table.appendValues(path, values)
                value != null -> values.add(VisualValue(path.toList(), value, entry))
                table != null -> table.appendAllValues(path, values)
            }
            path.removeAt(path.size - 1)
        }
    }

    // Sorting asks for key order, so the order entries were read in no longer applies.
    private fun forgetOrder() {
        for (entry in items.entries()) {
            entry.order = null
            val table = entry.item.asInlineTable()
            if (table != null && table.isDotted()) table.forgetOrder()
        }
    }

    /** Drops the formatting of every key and value, for the default `{ a = 1, b = 2 }` layout. */
    override fun fmt() {
        for (entry in items.entries()) {
            val value = entry.item.asValue() ?: continue
            entry.key.leafDecorMut().clear()
            entry.key.dottedDecorMut().clear()
            value.decorMut().clear()
        }
    }

    /** Sets whether the table ends with a comma after its last entry. */
    public fun setTrailingComma(yes: Boolean) {
        trailingComma = yes
    }

    /** Whether the table ends with a comma after its last entry. */
    public fun trailingComma(): Boolean = trailingComma

    /** Sets the text after the last entry. */
    public fun setTrailing(trailing: RawString) {
        this.trailing = trailing
    }

    /** Sets the text after the last entry. */
    public fun setTrailing(trailing: String) {
        this.trailing = RawString.from(trailing)
    }

    /** The text after the last entry. */
    public fun trailing(): RawString = trailing

    /** Sorts the entries by key, dotted sub-tables included. The sort is stable and not recursive. */
    override fun sortValues() {
        forgetOrder()
        items.sortBy { left, right -> left.key.compareTo(right.key) }
        for (entry in items.entries()) {
            val table = entry.item.asInlineTable()
            if (table != null && table.isDotted()) table.sortValues()
        }
    }

    /**
     * Sorts the entries with [compare], given two keys and their values; dotted sub-tables are sorted the same
     * way. The sort is stable and not recursive otherwise.
     */
    public fun sortValuesBy(compare: (Key, Value, Key, Value) -> Int) {
        forgetOrder()
        items.sortBy { left, right ->
            val leftValue = left.item.asValue()
            val rightValue = right.item.asValue()
            when {
                leftValue != null && rightValue != null -> compare(left.key, leftValue, right.key, rightValue)
                leftValue != null -> 1
                rightValue != null -> -1
                else -> 0
            }
        }
        for (entry in items.entries()) {
            val table = entry.item.asInlineTable()
            if (table != null && table.isDotted()) table.sortValuesBy(compare)
        }
    }

    internal fun setImplicit(implicit: Boolean) {
        this.implicit = implicit
    }

    internal fun isImplicit(): Boolean = implicit

    /** Marks this table as standing for the parts of a dotted key, or not. */
    override fun setDotted(yes: Boolean) {
        dotted = yes
    }

    /** Whether this table stands for the parts of a dotted key rather than for `{ ... }`. */
    override fun isDotted(): Boolean = dotted

    /** The text around the table, to change. */
    public fun decorMut(): Decor = decor

    /** The text around the table. */
    public fun decor(): Decor = decor

    /** The key [key] as stored, with its formatting. */
    override fun key(key: String): Key? = items.getEntry(key)?.key

    /** A view of the key [key] that can change its formatting. */
    override fun keyMut(key: String): KeyMut? = key(key)?.asMut()

    /** Where the table lies in the original document; see [RawString.span]. */
    public fun span(): Span? = span

    internal fun despan(input: SourceText) {
        span = null
        decor.despan(input)
        trailing = trailing.despan(input)
        for (entry in items.entries()) {
            entry.key.despan(input)
            entry.pathSpelling?.despan(input)
            entry.item.despan(input)
        }
    }

    /** The key/value pairs, in order. */
    public fun iter(): Iterator<Pair<String, Value>> = items.snapshot()
        .mapNotNull { entry -> entry.item.asValue()?.let { entry.key.get() to it } }
        .iterator()

    /** The key/value pairs, in order, with the keys' formatting open to change. */
    public fun iterMut(): Iterator<Pair<KeyMut, Value>> = items.snapshot()
        .mapNotNull { entry -> entry.item.asValue()?.let { entry.key.asMut() to it } }
        .iterator()

    override fun iterator(): Iterator<Pair<String, Value>> = iter()

    /** The number of key/value pairs. */
    override fun len(): Int = items.entries().count { it.item.isValue() }

    /** Whether the table has no key/value pairs. */
    override fun isEmpty(): Boolean = len() == 0

    /** Removes every entry. */
    override fun clear() {
        items.clear()
    }

    /** The entry for [key], to read, insert or change in place. */
    public fun entry(key: String): InlineEntry = entryFor(Key(key))

    /** The entry for [key], keeping the key's formatting if it is inserted. */
    public fun entryFormat(key: Key): InlineEntry = entryFor(key.clone())

    private fun entryFor(key: Key): InlineEntry {
        val existing = items.getEntry(key.get()) ?: return InlineVacantEntry(this, key)
        // An occupied entry always holds a value, which keeps the occupied entry simple.
        if (!existing.item.isValue()) {
            val value = existing.item.intoValue() ?: Value.InlineTable(InlineTable())
            existing.item = Item.from(value)
        }
        return InlineOccupiedEntry(this, existing)
    }

    /** The value at [key], or null when there is none. */
    public operator fun get(key: String): Value? = items.get(key)?.asValue()

    /** The value at [key] to change in place, or null when there is none. */
    public fun getMut(key: String): Value? = get(key)

    /**
     * Replaces the value at an existing [key], keeping the key's formatting: upstream's `table[key] = value`.
     * Fails when the key is not there, where upstream panics; use [insert] to add one.
     */
    public operator fun set(key: String, value: Value): EmptyResult<TomlEditError> {
        val entry = items.getEntry(key)
        if (entry == null || !entry.item.isValue()) return Err(TomlEditError.KeyNotFound(key))
        entry.item = Item.from(value)
        return Ok(Unit)
    }

    /** The key and item at [key], unless the slot is empty. */
    override fun getKeyValue(key: String): Pair<Key, Item>? =
        items.getEntry(key)?.takeIf { !it.item.isNone() }?.let { it.key to it.item }

    /** The key, open to formatting changes, and item at [key], unless the slot is empty. */
    override fun getKeyValueMut(key: String): Pair<KeyMut, Item>? =
        items.getEntry(key)?.takeIf { !it.item.isNone() }?.let { it.key.asMut() to it.item }

    /** Whether there is a value at [key]. */
    override fun containsKey(key: String): Boolean = items.get(key)?.isValue() ?: false

    /** The value at [key], inserting [value] first when there is none. */
    public fun getOrInsert(key: String, value: Value): Value {
        val existing = items.get(key)
        if (existing != null) return existing.asValue() ?: value
        items.insert(Key(key), Item.from(value))
        return value
    }

    /**
     * Sets [key] to [value], returning the value it replaces. A replaced key loses its formatting; use
     * [insertFormatted] to keep a key's spelling.
     */
    public fun insert(key: String, value: Value): Value? {
        val existing = items.getEntry(key)
        if (existing != null) {
            existing.key.fmt()
            val old = existing.item
            existing.item = Item.from(value)
            return old.intoValue()
        }
        items.insert(Key(key), Item.from(value))
        return null
    }

    /** Sets [key], with its formatting, to [value], returning the value it replaces. */
    public fun insertFormatted(key: Key, value: Value): Value? {
        val existing = items.getEntry(key.get())
        if (existing != null) {
            existing.key = key.clone()
            val old = existing.item
            existing.item = Item.from(value)
            return old.intoValue()
        }
        items.insert(key.clone(), Item.from(value))
        return null
    }

    /** Removes [key] and returns its value. */
    public fun remove(key: String): Value? = items.shiftRemove(key)?.item?.intoValue()

    /** Removes [key] and returns it, with its formatting, and its value. */
    public fun removeEntry(key: String): Pair<Key, Value>? {
        val removed = items.shiftRemove(key) ?: return null
        val value = removed.item.intoValue() ?: return null
        return removed.key to value
    }

    /** Keeps only the pairs [keep] accepts, visiting them in order. */
    public fun retain(keep: (String, Value) -> Boolean) {
        items.retain { entry -> entry.item.asValue()?.let { keep(entry.key.get(), it) } ?: false }
    }

    /** Adds every pair of [pairs], keeping the values' formatting. */
    public fun extend(pairs: Iterable<Pair<String, Value>>) {
        for ((key, value) in pairs) items.insert(Key(key), Item.from(value))
    }

    /** A deep copy of this table. */
    public fun clone(): InlineTable {
        val copy = InlineTable(items.deepClone())
        copy.trailing = trailing
        copy.trailingComma = trailingComma
        copy.implicit = implicit
        copy.decor.assign(decor)
        copy.span = span
        copy.dotted = dotted
        return copy
    }

    /** The table as TOML, with its decor. */
    override fun toString(): String = buildString { encodeInlineTable(this, this@InlineTable, "", "") }

    // TableLike, over items rather than values. An inline table's slots may be empty here.

    override fun iterItems(): Iterator<Pair<String, Item>> = items.snapshot().map { it.key.get() to it.item }.iterator()

    override fun iterItemsMut(): Iterator<Pair<KeyMut, Item>> =
        items.snapshot().map { it.key.asMut() to it.item }.iterator()

    override fun itemEntry(key: String): Entry = tableEntry(items, Key(key))

    override fun itemEntryFormat(key: Key): Entry = tableEntry(items, Key(key.get()))

    override fun getItem(key: String): Item? = items.get(key)

    override fun insertItem(key: String, item: Item): Result<Item?, TomlEditError> {
        val value = item.intoValue() ?: return Err(TomlEditError.NotAValue(item.type))
        return Ok(insert(key, value)?.let { Item.from(it) })
    }

    override fun removeItem(key: String): Item? = remove(key)?.let { Item.from(it) }

    public companion object {
        /** An inline table of [pairs], keeping the values' formatting. */
        public fun fromIter(pairs: Iterable<Pair<String, Value>>): InlineTable {
            val table = InlineTable()
            table.extend(pairs)
            return table
        }
    }
}

// `{ key1 = value1, ... }`
internal val DEFAULT_INLINE_KEY_DECOR: Pair<String, String> = " " to " "

/** An entry of an [InlineTable], from [InlineTable.entry]: either occupied or vacant. */
public sealed interface InlineEntry {
    /** The entry's key. */
    public fun key(): String

    /** The value in the entry, inserting [default] first when it is vacant. */
    public fun orInsert(default: Value): Value = when (this) {
        is InlineOccupiedEntry -> intoMut()
        is InlineVacantEntry -> insert(default)
    }

    /** The value in the entry, inserting what [default] makes first when it is vacant. */
    public fun orInsertWith(default: () -> Value): Value = when (this) {
        is InlineOccupiedEntry -> intoMut()
        is InlineVacantEntry -> insert(default())
    }
}

/** An [InlineEntry] that holds a value. */
public class InlineOccupiedEntry internal constructor(
    private val table: InlineTable,
    private val entry: KeyValue,
) : InlineEntry {

    override fun key(): String = entry.key.get()

    /** A view of the key that can change its formatting. */
    public fun keyMut(): KeyMut = entry.key.asMut()

    /** The value. */
    public fun get(): Value = entry.item.asValue() ?: Value.InlineTable(InlineTable())

    /** The value, to change in place. */
    public fun getMut(): Value = get()

    /** The value, to change in place, upstream's `into_mut`. */
    public fun intoMut(): Value = get()

    /** Replaces the value, returning the old one. */
    public fun insert(value: Value): Value {
        val old = get()
        entry.item = Item.from(value)
        return old
    }

    /** Removes the entry from its table and returns its value. */
    public fun remove(): Value {
        table.items.shiftRemove(entry.key.get())
        return get()
    }
}

/** An [InlineEntry] with no value yet. */
public class InlineVacantEntry internal constructor(
    private val table: InlineTable,
    private val key: Key,
) : InlineEntry {

    override fun key(): String = key.get()

    /** Inserts [value] under the entry's key and returns it. */
    public fun insert(value: Value): Value {
        table.items.insert(key, Item.from(value))
        return value
    }
}
