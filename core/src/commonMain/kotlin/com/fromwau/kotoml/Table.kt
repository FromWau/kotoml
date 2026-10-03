package com.fromwau.kotoml

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A standard table: the key/value pairs under a `[header]`, and the tables nested in it.
 *
 * A table that only exists because a deeper header names it, such as `a` in `[a.b]`, is implicit and is
 * not printed while it holds no values of its own. A table that stands for the parts of a dotted key, such as
 * `a` in `a.b = 1`, is dotted.
 *
 * Indexing reads and writes slots: `table["key"]` returns the [Item] stored there, or an empty one that
 * joins the table when it is written to; `table["key"] = item` stores a copy of `item`, keeping the key's
 * formatting.
 *
 * ```kotlin
 * val table = tableOf("name" to Item(Value.String("kotoml")))
 * table["version"] = "0.1.0"
 * table.toString() // "name = \"kotoml\"\nversion = \"0.1.0\"\n"
 * ```
 */
public class Table() : TableLike, Iterable<Pair<String, Item>> {
    internal val rawDecor: RawDecor = RawDecor()

    /**
     * Whether the table is hidden while it holds no values. Its sub-tables still print.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("[a]\n[a.b]\n").getOrNull()!!
     * doc["a"].tableOrNull?.isImplicit = true
     * doc.toString() // "[a.b]\n"
     * ```
     */
    public var isImplicit: Boolean = false

    /** Whether this table stands for the parts of a dotted key rather than for a header. */
    override var isDotted: Boolean = false

    /**
     * Where the table prints among the document's tables: tables print in ascending order, and a table with
     * none follows the table printed before it.
     */
    public var position: Long? = null

    internal var span: Span? = null
    internal var items: KeyValuePairs = KeyValuePairs()

    // Lines above the headers of tables removed from this one, printed among the tables by their position.
    internal val keptHeaderLines: MutableList<KeptLines> = mutableListOf()

    // How the header line ended and how its path was spelled, when the table came from text.
    internal var headerLineEnding: String? = null
    internal var headerSpelling: PathSpelling? = null

    internal constructor(items: KeyValuePairs) : this() {
        for (entry in items.entries()) entry.item = entry.item.movedTo(Item.Holds.Anything)
        this.items = items
    }

    /** An inline table holding copies of this table's values, formatted anew; the table stays as it is. */
    public fun toInlineTable(): InlineTable = copy().intoInlineTable()

    /** Moves the entries into a new inline table, formatted anew, leaving this table empty. */
    internal fun intoInlineTable(): InlineTable {
        for (entry in items.entries()) entry.item.makeValue()
        val table = InlineTable(items.takeAll())
        table.resetFormatting()
        return table
    }

    /** The values visually under this table's header, with the key path that reaches each: dotted keys. */
    override fun valuesWithPaths(): List<Pair<List<Key>, Value>> = visualValues().map { it.path to it.value }

    /** The values under this header in the order they print: as read from text, new ones after their sibling. */
    internal fun visualValues(): List<VisualValue> = bodyLines().filterIsInstance<VisualValue>()

    /** The lines under this header in the order they print, the lines kept from removed values included. */
    internal fun bodyLines(): List<BodyLine> {
        val lines = mutableListOf<BodyLine>()
        appendValues(mutableListOf(), lines)
        return inPrintOrder(lines)
    }

    /**
     * Whether the table prints a header. An implicit table with no values of its own is hidden, so deleting every
     * table under it hides it too.
     */
    internal fun printsHeader(isArrayOfTables: Boolean): Boolean =
        isArrayOfTables || !(isImplicit && visualValues().isEmpty())

    private fun appendValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries()) {
            path.add(entry.key)
            val table = entry.item.tableOrNull
            val value = entry.item.valueOrNull
            when {
                table != null && table.isDotted -> table.appendValues(path, values)
                value is Value.InlineTable && value.table.isDotted -> value.table.appendValues(path, values)
                value != null -> values.add(VisualValue(path.toList(), value, entry))
            }
            path.removeAt(path.size - 1)
        }
        values += items.keptLines
    }

    internal fun appendAllValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries()) {
            path.add(entry.key)
            val table = entry.item.tableOrNull
            val value = entry.item.valueOrNull
            when {
                table != null -> table.appendAllValues(path, values)
                value is Value.InlineTable && value.table.isDotted -> value.table.appendValues(path, values)
                value != null -> values.add(VisualValue(path.toList(), value, entry))
            }
            path.removeAt(path.size - 1)
        }
    }

    // A sort sets the order, so the order lines were read in no longer applies.
    private fun forgetOrder() {
        for (kept in items.keptLines) kept.order = null
        for (entry in items.entries()) {
            entry.order = null
            val table = entry.item.tableOrNull
            if (table != null && table.isDotted) table.forgetOrder()
        }
    }

    /** Drops the formatting of the keys and values directly in this table. */
    override fun resetFormatting() {
        for (entry in items.entries()) {
            val value = entry.item.valueOrNull ?: continue
            entry.key.rawLeafDecor.clear()
            entry.key.rawDottedDecor.clear()
            value.rawDecor().clear()
        }
    }

    /**
     * Sorts the key/value lines under this header in the natural order of what [selector] returns for each pair of
     * key and item.
     *
     * Only the key/value lines move, each with its comments. Sub-tables and arrays of tables keep their order, since
     * their position decides where their headers print. The parts of a dotted key are sorted the same way, and their
     * order replaces the one they were read in. The sort is stable, and nulls sort first.
     *
     * ```kotlin
     * table.sortBy { (key, _) -> key }
     * ```
     */
    public fun <R : Comparable<R>> sortBy(selector: (Pair<Key, Item>) -> R?) {
        sortWith(compareBy(selector))
    }

    /**
     * Sorts the key/value lines under this header with [comparator], given each pair of key and item.
     *
     * Only the key/value lines move, each with its comments. Sub-tables and arrays of tables keep their order, since
     * their position decides where their headers print. The parts of a dotted key are sorted the same way, and their
     * order replaces the one they were read in. The sort is stable.
     */
    public fun sortWith(comparator: Comparator<Pair<Key, Item>>) {
        forgetOrder()
        sortEntriesWith(comparator)
    }

    private fun sortEntriesWith(comparator: Comparator<Pair<Key, Item>>) {
        items.sortBy { left, right -> comparator.compare(left.key to left.item, right.key to right.item) }
        for (entry in items.entries()) {
            val table = entry.item.tableOrNull
            if (table != null && table.isDotted) table.sortEntriesWith(comparator)
        }
    }

    /**
     * The text around the header: comments and blank lines before `[header]`, and what follows it on its line.
     * For a document's root table, the text before the first line and after the last.
     */
    public var decor: Decor
        get() = rawDecor.toDecor()
        set(value) {
            rawDecor.set(value)
        }

    /** The key [name] as stored, whose formatting can be changed in place; null when there is none. */
    override fun key(name: String): Key? = items.getEntry(name)?.key

    internal fun despan(input: SourceText) {
        span = null
        rawDecor.despan(input)
        headerSpelling?.despan(input)
        for (entry in items.entries()) {
            entry.key.despan(input)
            entry.pathSpelling?.despan(input)
            entry.item.despan(input)
        }
    }

    /** The key/item pairs with an item, in order. */
    override fun items(): List<Pair<String, Item>> = items.entries()
        .filter { !it.item.isNone }
        .map { it.key.name to it.item }

    override fun iterator(): Iterator<Pair<String, Item>> = items().iterator()

    /** The number of keys holding an item. */
    override val size: Int
        get() = items.entries().count { !it.item.isNone }

    /** Whether no key holds an item. */
    override fun isEmpty(): Boolean = size == 0

    /** Removes every entry, keeping the lines above each as [remove] does. */
    override fun clear() {
        retainAll { _, _ -> false }
    }

    /** The item at [key], or null when there is none. */
    public fun getOrNull(key: String): Item? = items.get(key)?.takeIf { !it.isNone }

    override fun item(key: String): Item? = getOrNull(key)

    /**
     * The slot at [key]: the item stored there, or an empty item that joins this table under [key] when it
     * is first written to. Reading never adds a key.
     */
    public operator fun get(key: String): Item = items.get(key) ?: Item.detached(Item.Holds.Anything) { child ->
        items.insert(Key(key), child)
        Ok(Unit)
    }

    /** Stores a copy of [item] at [key], keeping the key's position and formatting when it is already there. */
    public operator fun set(
        key: String,
        item: Item,
    ) {
        val existing = items.getEntry(key)
        if (existing != null) existing.item = item.copy() else items.insert(Key(key), item.copy())
    }

    /** Stores a copy of [value] at [key], as `table[key] = Item(value)` does. */
    public operator fun set(
        key: String,
        value: Value,
    ) {
        set(key, Item(value))
    }

    /** Stores a copy of [table] at [key], as `table[key] = Item(table)` does. */
    public operator fun set(
        key: String,
        table: Table,
    ) {
        set(key, Item(table))
    }

    /** Stores a copy of [arrayOfTables] at [key], as `table[key] = Item(arrayOfTables)` does. */
    public operator fun set(
        key: String,
        arrayOfTables: ArrayOfTables,
    ) {
        set(key, Item(arrayOfTables))
    }

    /** Whether [key] holds an item. */
    override fun containsKey(key: String): Boolean = items.get(key)?.let { !it.isNone } ?: false

    /** Whether [key] holds an item, for `key in table`. */
    public operator fun contains(key: String): Boolean = containsKey(key)

    /**
     * Sets [key] to a copy of [item]. A key already there keeps its position but loses its formatting; pass a
     * [Key] to choose it.
     *
     * @return the item [key] held before, or null when it held none.
     */
    public fun put(
        key: String,
        item: Item,
    ): Item? = put(Key(key), item)

    /**
     * Sets [key], with its spelling and decor, to a copy of [item]. A key already there keeps its position.
     *
     * @return the item the key held before, or null when it held none.
     */
    public fun put(
        key: Key,
        item: Item,
    ): Item? {
        val existing = items.getEntry(key.name)
        if (existing != null) {
            existing.key = key.copy()
            val old = existing.item
            existing.item = item.copy()
            return old.takeIf { !it.isNone }
        }
        items.insert(key.copy(), item.copy())
        return null
    }

    override fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError> = Ok(put(key, item))

    /**
     * The item at [key], first storing a copy of what [default] makes when the key holds none. [default] is
     * called only then.
     *
     * @return the stored item, to change in place.
     */
    public fun getOrPut(
        key: String,
        default: () -> Item,
    ): Item = getOrPut(Key(key), default)

    /** As [getOrPut], an inserted key keeping the spelling and decor of [key]. */
    public fun getOrPut(
        key: Key,
        default: () -> Item,
    ): Item {
        getOrNull(key.name)?.let { return it }
        val stored = default().copy()
        items.insert(key.copy(), stored)
        return stored
    }

    /**
     * Removes [key] and returns its item, or null when it held none.
     *
     * The comment and blank lines above the key stay where they print, now above whatever follows: the next key,
     * the next table header, or the end of the document. What belongs to the key goes with it: its line, from the
     * key to the end of its value and the comment after it, with every comment inside a multi-line value. For a
     * table or an array of tables that is everything after its first header: its headers, its bodies, and the
     * comment and blank lines above the headers of the tables under it and of its later elements. Upstream drops
     * the lines above the key too.
     */
    public fun remove(key: String): Item? {
        val entry = items.getEntry(key) ?: return null
        keepLinesAbove(listOf(entry))
        items.shiftRemove(key)
        return entry.item.takeIf { !it.isNone }
    }

    /** Removes [key] and returns its item, or null when it held none; the lines above it stay, as [remove] says. */
    override fun removeItem(key: String): Item? = remove(key)

    /**
     * Keeps only the pairs [predicate] accepts, visiting them in order. The lines above a removed pair stay, as
     * [remove] keeps them.
     */
    public fun retainAll(predicate: (String, Item) -> Boolean) {
        val removed = items.entries().filterNot { predicate(it.key.name, it.item) }
        keepLinesAbove(removed)
        val names = removed.mapTo(HashSet()) { it.key.name }
        items.retain { it.key.name !in names }
    }

    private fun keepLinesAbove(removed: Collection<KeyValue>) {
        if (removed.isEmpty()) return
        keepLinesAbove(
            lines = bodyLines(),
            removed = RemovedLines(removed),
            isDotted = isDotted,
            linesAbove = { linesAbove(it.entry.key.rawLeafDecor.prefix) },
            keyDefault = DEFAULT_KEY_DECOR.first,
            keep = { items.keptLines += it },
        )
        for (entry in removed) keepHeaderLinesOf(entry.item, keptHeaderLines)
    }

    /** Stores a copy of every pair of [pairs], in order, as `table[key] = item` does. */
    public fun putAll(pairs: Iterable<Pair<String, Item>>) {
        for ((key, item) in pairs) set(key, item)
    }

    /** A deep copy of this table. */
    public fun copy(): Table {
        val copy = Table(items.deepClone())
        copy.rawDecor.assign(rawDecor)
        copy.isImplicit = isImplicit
        copy.isDotted = isDotted
        copy.position = position
        copy.span = span
        copy.headerLineEnding = headerLineEnding
        copy.headerSpelling = headerSpelling?.copy()
        keptHeaderLines.mapTo(copy.keptHeaderLines) { it.copy() }
        return copy
    }

    /** The key/value pairs under this table, one per line, without its header or sub-tables. */
    override fun toString(): String = buildString { encodeTableBody(this, this@Table) }

    internal companion object {
        fun withPosition(position: Long?): Table = Table().also { it.position = position }
    }
}

/** A table holding copies of [pairs], in order. */
public fun tableOf(vararg pairs: Pair<String, Item>): Table = pairs.asIterable().toTable()

/** A table holding copies of these pairs, in order; a later pair for the same key replaces an earlier one. */
public fun Iterable<Pair<String, Item>>.toTable(): Table {
    val table = Table()
    table.putAll(this)
    return table
}

/** A value as it prints under a header: the key path that reaches it, and the entry that holds it. */
internal class VisualValue(val path: List<Key>, val value: Value, val entry: KeyValue) : BodyLine {
    override val order: Long?
        get() = entry.order

    /** The path's keys as spelled on this value's own line. */
    fun spelledPath(): List<Key> {
        val spelling = entry.pathSpelling ?: return path
        val parents = path.subList(0, path.size - 1)
        return spelling.resolve(parents) + path.last()
    }
}

/**
 * Orders [lines] as they were read, a line without a recorded place staying right after the one before it. Lines
 * read in order are left as they are, so this only moves interleaved dotted keys back into place.
 */
internal fun inPrintOrder(lines: List<BodyLine>): List<BodyLine> {
    if (lines.none { it.order != null }) return lines
    var previous = Long.MIN_VALUE
    val keyed = lines.map { line ->
        val order = line.order ?: previous
        previous = order
        order to line
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
 * ```kotlin
 * fun names(table: TableLike): List<String> = table.items().map { it.first }
 * ```
 */
public sealed interface TableLike {
    /** The key/item pairs, in order. */
    public fun items(): List<Pair<String, Item>>

    /** The item at [key], or null when there is none. */
    public fun item(key: String): Item?

    /**
     * Sets [key] to a copy of [item]. An inline table holds only values, so it stores a table as an inline table
     * and an array of tables as an array of inline tables.
     *
     * @return the item [key] held before, or null when it held none; or [TomlEditError.NotAValue], with nothing
     *   written, when this is an inline table and [item] is empty.
     */
    public fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError>

    /**
     * Removes [key] and returns its item, or null when it held none. Only the key's own text goes; the comment and
     * blank lines above it stay where they print, as [Table.remove] and [InlineTable.remove] keep them.
     */
    public fun removeItem(key: String): Item?

    /** The number of keys holding an item. */
    public val size: Int

    /** Whether no key holds an item. */
    public fun isEmpty(): Boolean

    /** Removes every entry, keeping the comment and blank lines above each. */
    public fun clear()

    /** Whether [key] holds an item. */
    public fun containsKey(key: String): Boolean

    /** The values visually under this table, with the key path that reaches each. */
    public fun valuesWithPaths(): List<Pair<List<Key>, Value>>

    /** Drops the formatting of the keys and values in this table. */
    public fun resetFormatting()

    /** Whether this table stands for the parts of a dotted key. */
    public var isDotted: Boolean

    /** The key [name] as stored, whose formatting can be changed in place; null when there is none. */
    public fun key(name: String): Key?
}
