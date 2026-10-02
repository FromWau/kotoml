package com.fromwau.kotoml

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.Span

/**
 * A standard table: the key/value pairs under a `[header]`, and the tables nested in it.
 *
 * A table that only exists because a deeper header names it, such as `a` in `[a.b]`, is implicit and is
 * not printed while it holds no values of its own. A table that stands for the parts of a dotted key, such as
 * `a` in `a.b = 1`, is dotted.
 *
 * Indexing reads and writes slots: `table["key"]` returns the [Item] stored there, or an empty one that
 * joins the table when it is written to; `table["key"] = item` stores [item], keeping the key's formatting.
 */
public class Table() : TableLike, Iterable<Pair<String, Item>> {
    // Comments and whitespace before and after the header.
    internal val decor: Decor = Decor()
    internal var implicit: Boolean = false
    internal var dotted: Boolean = false

    // Puts tables back in their original order when printing; null for tables made by hand.
    private var docPosition: Long? = null
    internal var span: Span? = null
    internal var items: KeyValuePairs = KeyValuePairs()

    // How the header line ended and how its path was spelled, when the table came from text.
    internal var headerLineEnding: String? = null
    internal var headerSpelling: PathSpelling? = null

    internal constructor(items: KeyValuePairs) : this() {
        this.items = items
    }

    /** Turns this table into an inline table, moving its entries there and formatting it anew. */
    public fun intoInlineTable(): InlineTable {
        for (entry in items.entries()) entry.item.makeValue()
        val table = InlineTable(items.takeAll())
        table.fmt()
        return table
    }

    /** The values visually under this table's header, with the key path that reaches each: dotted keys. */
    override fun getValues(): List<Pair<List<Key>, Value>> = visualValues().map { it.path to it.value }

    /** The values under this header in the order they print: as read from text, new ones after their sibling. */
    internal fun visualValues(): List<VisualValue> {
        val values = mutableListOf<VisualValue>()
        appendValues(mutableListOf(), values)
        return inPrintOrder(values)
    }

    private fun appendValues(path: MutableList<Key>, values: MutableList<VisualValue>) {
        for (entry in items.entries()) {
            path.add(entry.key)
            val table = entry.item.asTable()
            val value = entry.item.asValue()
            when {
                table != null && table.isDotted() -> table.appendValues(path, values)
                value is Value.InlineTable && value.table.isDotted() -> value.table.appendValues(path, values)
                value != null -> values.add(VisualValue(path.toList(), value, entry))
            }
            path.removeAt(path.size - 1)
        }
    }

    internal fun appendAllValues(path: MutableList<Key>, values: MutableList<VisualValue>) {
        for (entry in items.entries()) {
            path.add(entry.key)
            val table = entry.item.asTable()
            val value = entry.item.asValue()
            when {
                table != null -> table.appendAllValues(path, values)
                value is Value.InlineTable && value.table.isDotted() -> value.table.appendValues(path, values)
                value != null -> values.add(VisualValue(path.toList(), value, entry))
            }
            path.removeAt(path.size - 1)
        }
    }

    // Sorting asks for key order, so the order lines were read in no longer applies.
    private fun forgetOrder() {
        for (entry in items.entries()) {
            entry.order = null
            val table = entry.item.asTable()
            if (table != null && table.isDotted()) table.forgetOrder()
        }
    }

    /** Drops the formatting of the keys and values directly in this table. */
    override fun fmt() {
        for (entry in items.entries()) {
            val value = entry.item.asValue() ?: continue
            entry.key.leafDecorMut().clear()
            entry.key.dottedDecorMut().clear()
            value.decorMut().clear()
        }
    }

    /**
     * Sorts the key/value pairs under this header by key, dotted keys included. Sub-tables and arrays of
     * tables keep their order, as their position decides where they print. The sort is stable and not
     * recursive.
     */
    override fun sortValues() {
        forgetOrder()
        items.sortBy { left, right -> left.key.compareTo(right.key) }
        for (entry in items.entries()) {
            val table = entry.item.asTable()
            if (table != null && table.isDotted()) table.sortValues()
        }
    }

    /** Sorts the pairs under this header with [compare], given two keys and their items. Stable, not recursive. */
    public fun sortValuesBy(compare: (Key, Item, Key, Item) -> Int) {
        forgetOrder()
        items.sortBy { left, right -> compare(left.key, left.item, right.key, right.item) }
        for (entry in items.entries()) {
            val table = entry.item.asTable()
            if (table != null && table.isDotted()) table.sortValuesBy(compare)
        }
    }

    /**
     * Marks the table implicit, so it is not printed while it holds no values. Its sub-tables still print.
     *
     * ```kotlin
     * val doc = DocumentMut.parse("[a]\n[a.b]\n").getOrNull()!!
     * doc["a"].asTableMut()?.setImplicit(true)
     * doc.toString() // "[a.b]\n"
     * ```
     */
    public fun setImplicit(implicit: Boolean) {
        this.implicit = implicit
    }

    /** Whether the table is hidden while it holds no values. */
    public fun isImplicit(): Boolean = implicit

    /** Marks this table as standing for the parts of a dotted key, or not. */
    override fun setDotted(yes: Boolean) {
        dotted = yes
    }

    /** Whether this table stands for the parts of a dotted key rather than for a header. */
    override fun isDotted(): Boolean = dotted

    /** Sets where the table prints among the document's tables; null leaves it to follow its parent. */
    public fun setPosition(docPosition: Long?) {
        this.docPosition = docPosition
    }

    /** Where the table prints among the document's tables, or null for a table made by hand. */
    public fun position(): Long? = docPosition

    /** The text around the header, to change. */
    public fun decorMut(): Decor = decor

    /** The text around the header. */
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
        headerSpelling?.despan(input)
        for (entry in items.entries()) {
            entry.key.despan(input)
            entry.pathSpelling?.despan(input)
            entry.item.despan(input)
        }
    }

    /** The key/item pairs with an item, in order. */
    public fun iter(): Iterator<Pair<String, Item>> = items.snapshot()
        .filter { !it.item.isNone() }
        .map { it.key.get() to it.item }
        .iterator()

    /** The key/item pairs with an item, in order, with the keys' formatting open to change. */
    public fun iterMut(): Iterator<Pair<KeyMut, Item>> = items.snapshot()
        .filter { !it.item.isNone() }
        .map { it.key.asMut() to it.item }
        .iterator()

    override fun iterator(): Iterator<Pair<String, Item>> = iter()

    /** The number of keys holding an item. */
    override fun len(): Int = items.entries().count { !it.item.isNone() }

    /** Whether no key holds an item. */
    override fun isEmpty(): Boolean = len() == 0

    /** Removes every entry. */
    override fun clear() {
        items.clear()
    }

    /** The entry for [key], to read, insert or change in place. */
    public fun entry(key: String): Entry = tableEntry(items, Key(key))

    /** The entry for [key], keeping the key's formatting if it is inserted. */
    public fun entryFormat(key: Key): Entry = tableEntry(items, key.clone())

    /** The item at [key], or null when there is none: upstream's `get`, renamed for the index operator. */
    public fun getOrNull(key: String): Item? = items.get(key)?.takeIf { !it.isNone() }

    /** The item at [key] to change in place, or null when there is none. */
    public fun getMut(key: String): Item? = getOrNull(key)

    /**
     * The slot at [key]: the item stored there, or an empty item that joins this table under [key] when it
     * is first written to. Reading never adds a key.
     */
    public operator fun get(key: String): Item = items.get(key) ?: Item.detached { child ->
        items.insert(Key(key), child)
        Ok(Unit)
    }

    /** Stores [item] at [key], keeping the key's position and formatting when it is already there. */
    public operator fun set(key: String, item: Item) {
        val existing = items.getEntry(key)
        if (existing != null) existing.item = item else items.insert(Key(key), item)
    }

    /** The key and item at [key], unless it holds no item. */
    override fun getKeyValue(key: String): Pair<Key, Item>? =
        items.getEntry(key)?.takeIf { !it.item.isNone() }?.let { it.key to it.item }

    /** The key, open to formatting changes, and item at [key], unless it holds no item. */
    override fun getKeyValueMut(key: String): Pair<KeyMut, Item>? =
        items.getEntry(key)?.takeIf { !it.item.isNone() }?.let { it.key.asMut() to it.item }

    /** Whether [key] holds an item. */
    override fun containsKey(key: String): Boolean = items.get(key)?.let { !it.isNone() } ?: false

    /** Whether [key] holds a table. */
    public fun containsTable(key: String): Boolean = items.get(key)?.isTable() ?: false

    /** Whether [key] holds a value. */
    public fun containsValue(key: String): Boolean = items.get(key)?.isValue() ?: false

    /** Whether [key] holds an array of tables. */
    public fun containsArrayOfTables(key: String): Boolean = items.get(key)?.isArrayOfTables() ?: false

    /**
     * Sets [key] to [item], returning the item it replaces. A replaced key loses its formatting; use
     * [insertFormatted] to keep a key's spelling.
     */
    public fun insert(key: String, item: Item): Item? {
        val existing = items.getEntry(key)
        if (existing != null) {
            existing.key.fmt()
            val old = existing.item
            existing.item = item
            return old
        }
        items.insert(Key(key), item)
        return null
    }

    /** Sets [key], with its formatting, to [item], returning the item it replaces. */
    public fun insertFormatted(key: Key, item: Item): Item? {
        val existing = items.getEntry(key.get())
        if (existing != null) {
            existing.key = key.clone()
            val old = existing.item
            existing.item = item
            return old
        }
        items.insert(key.clone(), item)
        return null
    }

    /** Removes [key] and returns its item. */
    public fun remove(key: String): Item? = items.shiftRemove(key)?.item

    /** Removes [key] and returns it, with its formatting, and its item. */
    public fun removeEntry(key: String): Pair<Key, Item>? = items.shiftRemove(key)?.let { it.key to it.item }

    /** Keeps only the pairs [keep] accepts, visiting them in order. */
    public fun retain(keep: (String, Item) -> Boolean) {
        items.retain { keep(it.key.get(), it.item) }
    }

    /** Adds every pair of [pairs]. */
    public fun extend(pairs: Iterable<Pair<String, Item>>) {
        for ((key, item) in pairs) items.insert(Key(key), item)
    }

    /** A deep copy of this table. */
    public fun clone(): Table {
        val copy = Table(items.deepClone())
        copy.decor.assign(decor)
        copy.implicit = implicit
        copy.dotted = dotted
        copy.docPosition = docPosition
        copy.span = span
        copy.headerLineEnding = headerLineEnding
        copy.headerSpelling = headerSpelling?.clone()
        return copy
    }

    /** The key/value pairs under this table, one per line, without its header or sub-tables. */
    override fun toString(): String = buildString { encodeTableBody(this, this@Table) }

    // TableLike

    override fun iterItems(): Iterator<Pair<String, Item>> = iter()

    override fun iterItemsMut(): Iterator<Pair<KeyMut, Item>> = iterMut()

    override fun itemEntry(key: String): Entry = entry(key)

    override fun itemEntryFormat(key: Key): Entry = entryFormat(key)

    override fun getItem(key: String): Item? = getOrNull(key)

    override fun insertItem(key: String, item: Item): Result<Item?, TomlEditError> = Ok(insert(key, item))

    override fun removeItem(key: String): Item? = remove(key)

    public companion object {
        /** A table of [pairs]. */
        public fun fromIter(pairs: Iterable<Pair<String, Item>>): Table {
            val table = Table()
            table.extend(pairs)
            return table
        }

        internal fun withPosition(position: Long?): Table = Table().also { it.docPosition = position }
    }
}

/** A value as it prints under a header: the key path that reaches it, and the entry that holds it. */
internal class VisualValue(val path: List<Key>, val value: Value, val entry: KeyValue) {
    /** The path's keys as spelled on this value's own line. */
    fun spelledPath(): List<Key> {
        val spelling = entry.pathSpelling ?: return path
        val parents = path.subList(0, path.size - 1)
        return spelling.resolve(parents) + path.last()
    }
}

/**
 * Orders [values] as their lines were read, a value without a recorded place staying right after the one before
 * it. Values read in order are left as they are, so this only moves interleaved dotted keys back into place.
 */
internal fun inPrintOrder(values: List<VisualValue>): List<VisualValue> {
    if (values.none { it.entry.order != null }) return values
    var previous = Long.MIN_VALUE
    val keyed = values.map { value ->
        val order = value.entry.order ?: previous
        previous = order
        order to value
    }
    return keyed.sortedBy { it.first }.map { it.second }
}

// `key1 = value1`
internal val DEFAULT_ROOT_DECOR: Pair<String, String> = "" to ""
internal val DEFAULT_KEY_DECOR: Pair<String, String> = "" to " "
internal val DEFAULT_TABLE_DECOR: Pair<String, String> = "\n" to ""
internal val DEFAULT_KEY_PATH_DECOR: Pair<String, String> = "" to ""

/**
 * A [Table] or an [InlineTable], seen as key/item pairs.
 *
 * Upstream's trait gives some of these functions the names an inline table already uses for values; here
 * the item-based ones say so: [iterItems], [itemEntry], [getItem], [insertItem], [removeItem].
 */
public interface TableLike {
    /** The key/item pairs, in order. An inline table also lists keys whose slot is empty. */
    public fun iterItems(): Iterator<Pair<String, Item>>

    /** The key/item pairs with the keys' formatting open to change. */
    public fun iterItemsMut(): Iterator<Pair<KeyMut, Item>>

    /** The number of keys holding an item. */
    public fun len(): Int

    /** Whether no key holds an item. */
    public fun isEmpty(): Boolean

    /** Removes every entry. */
    public fun clear()

    /** The entry for [key]. */
    public fun itemEntry(key: String): Entry

    /** The entry for [key], keeping the key's formatting if it is inserted. */
    public fun itemEntryFormat(key: Key): Entry

    /** The item at [key]. */
    public fun getItem(key: String): Item?

    /** The key and item at [key]. */
    public fun getKeyValue(key: String): Pair<Key, Item>?

    /** The key, open to formatting changes, and item at [key]. */
    public fun getKeyValueMut(key: String): Pair<KeyMut, Item>?

    /** Whether [key] holds an item. */
    public fun containsKey(key: String): Boolean

    /**
     * Sets [key] to [item], returning the item it replaces. An inline table holds only values, so giving it
     * anything else fails; upstream panics there.
     */
    public fun insertItem(key: String, item: Item): Result<Item?, TomlEditError>

    /** Removes [key] and returns its item. */
    public fun removeItem(key: String): Item?

    /** The values visually under this table, with the key path that reaches each. */
    public fun getValues(): List<Pair<List<Key>, Value>>

    /** Drops the formatting of the keys and values in this table. */
    public fun fmt()

    /** Sorts the pairs by key, dotted keys included. The sort is stable and not recursive. */
    public fun sortValues()

    /** Marks this table as standing for the parts of a dotted key, or not. */
    public fun setDotted(yes: Boolean)

    /** Whether this table stands for the parts of a dotted key. */
    public fun isDotted(): Boolean

    /** The key [key] as stored, with its formatting. */
    public fun key(key: String): Key?

    /** A view of the key [key] that can change its formatting. */
    public fun keyMut(key: String): KeyMut?
}

/** An entry of a table, from [Table.entry]: either occupied or vacant. */
public sealed interface Entry {
    /** The entry's key. */
    public fun key(): String

    /** The item in the entry, inserting [default] first when it is vacant. */
    public fun orInsert(default: Item): Item = when (this) {
        is OccupiedEntry -> intoMut()
        is VacantEntry -> insert(default)
    }

    /** The item in the entry, inserting what [default] makes first when it is vacant. */
    public fun orInsertWith(default: () -> Item): Item = when (this) {
        is OccupiedEntry -> intoMut()
        is VacantEntry -> insert(default())
    }
}

/** An [Entry] whose key is in the table. */
public class OccupiedEntry internal constructor(
    private val items: KeyValuePairs,
    private val entry: KeyValue,
) : Entry {

    override fun key(): String = entry.key.get()

    /** A view of the key that can change its formatting. */
    public fun keyMut(): KeyMut = entry.key.asMut()

    /** The item. */
    public fun get(): Item = entry.item

    /** The item, to change in place. */
    public fun getMut(): Item = entry.item

    /** The item, to change in place, upstream's `into_mut`. */
    public fun intoMut(): Item = entry.item

    /** Replaces the item, returning the old one. */
    public fun insert(value: Item): Item {
        val old = entry.item
        entry.item = value
        return old
    }

    /** Removes the entry from its table and returns its item. */
    public fun remove(): Item {
        items.shiftRemove(entry.key.get())
        return entry.item
    }
}

/** An [Entry] whose key is not in the table yet. */
public class VacantEntry internal constructor(
    private val items: KeyValuePairs,
    private val key: Key,
) : Entry {

    override fun key(): String = key.get()

    /** Inserts [value] under the entry's key and returns it. */
    public fun insert(value: Item): Item {
        items.insert(key, value)
        return value
    }
}

internal fun tableEntry(items: KeyValuePairs, key: Key): Entry {
    val existing = items.getEntry(key.get())
    return if (existing != null) OccupiedEntry(items, existing) else VacantEntry(items, key)
}
