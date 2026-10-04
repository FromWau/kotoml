package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * A standard table: the key/value pairs under a `[header]`, and the tables nested in it.
 *
 * A table that only exists because a deeper header names it, such as `a` in `[a.b]`, is implicit and is
 * not printed while it holds no values of its own. A table that stands for the parts of a dotted key, such as
 * `a` in `a.b = 1`, is dotted.
 *
 * Indexing reads and writes slots: `table["key"]` returns the [Item] stored there, or
 * [TomlEditError.NotFound]; `table["key"] = item` stores a copy of `item`, keeping the key's formatting.
 *
 * ```kotlin
 * val table = standardTableOf("name" to Item(Value.String("kotoml")))
 * table["version"] = "0.1.0"
 * table.toString() // "name = \"kotoml\"\nversion = \"0.1.0\"\n"
 * ```
 */
public class StandardTable() : Table, Iterable<Pair<String, Item>> {
    internal val rawDecor: RawDecor = RawDecor()

    /**
     * Whether the table's header is hidden while it holds no values of its own and tables under it print theirs. A
     * table left holding nothing prints its header, so its key stays in the text.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("[a]\n[a.b]\n").getOrElse { error(it) }
     * doc["a"].getOrNull()?.standardTableOrNull?.isImplicit = true
     * doc.toString() // "[a.b]\n"
     * ```
     */
    public var isImplicit: Boolean = false

    /**
     * Whether this table stands for the parts of a dotted key rather than for a header. A document's root and an
     * element of an array of tables are never dotted: storing a table as either clears this.
     */
    override var isDotted: Boolean = false

    /**
     * Where the table prints among the document's tables: tables print in ascending order, and a table with
     * none follows the table printed before it. The elements of an array of tables print in array order, sharing
     * out the positions they and the tables under them hold; elements with none at the front print just before the
     * first that has one.
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
        for (entry in items.entries) entry.item = entry.item.movedTo(Item.Holds.Anything)
        this.items = items
    }

    /** An inline table holding copies of this table's values, formatted anew; the table stays as it is. */
    public fun toInlineTable(): InlineTable = copy().moveToInlineTable()

    /** Moves the entries into a new inline table, formatted anew, leaving this table empty. */
    internal fun moveToInlineTable(): InlineTable {
        for (entry in items.entries) entry.item.makeValue()
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
     * Whether the table prints a header. An implicit table with no values of its own is hidden while tables under it
     * print their own headers; one left holding nothing prints its header, so its key stays in the text.
     */
    internal fun printsHeader(isArrayOfTables: Boolean): Boolean = isArrayOfTables ||
        !(isImplicit && visualValues().isEmpty() && items.entries.any { it.item.printsOwnHeader() })

    private fun appendValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries) {
            path.add(entry.key)
            val table = entry.item.standardTableOrNull
            val value = entry.item.valueOrNull
            when {
                table != null && table.isDotted -> {
                    table.appendValues(path, values)
                    if (table.items.size == 0) values.add(emptyTableLine(path, entry, table.items.keptLines))
                }
                value is Value.InlineTable && value.table.isDotted -> {
                    value.table.appendDottedValues(path, values, entry)
                }
                value != null -> values.add(VisualValue(path.toList(), value, entry))
            }
            path.removeAt(path.size - 1)
        }
        values += items.keptLines
    }

    internal fun appendAllValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries) {
            path.add(entry.key)
            val table = entry.item.standardTableOrNull
            val value = entry.item.valueOrNull
            when {
                table != null -> table.appendAllValues(path, values)
                value is Value.InlineTable && value.table.isDotted -> {
                    value.table.appendDottedValues(path, values, entry)
                }
                value != null -> values.add(VisualValue(path.toList(), value, entry))
            }
            path.removeAt(path.size - 1)
        }
    }

    // A sort sets the order, so the order lines were read in no longer applies.
    private fun forgetOrder() {
        for (kept in items.keptLines) kept.order = null
        for (entry in items.entries) {
            entry.order = null
            val table = entry.item.standardTableOrNull
            if (table != null && table.isDotted) table.forgetOrder()
        }
    }

    /** Drops the formatting of the keys and values directly in this table. */
    override fun resetFormatting() {
        for (entry in items.entries) {
            val value = entry.item.valueOrNull ?: continue
            entry.key.rawLeafDecor.clear()
            entry.key.rawDottedDecor.clear()
            value.rawDecor.clear()
        }
    }

    /**
     * Sorts the key/value lines under this header in the natural order of what [selector] returns for each pair of
     * key and item.
     *
     * Only the key/value lines move, each with its comments. Sub-tables and arrays of tables stay where their headers
     * print, those made by hand included. The parts of a dotted key are sorted the same way, and their
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
     * Only the key/value lines move, each with its comments. Sub-tables and arrays of tables stay where their headers
     * print, those made by hand included. The parts of a dotted key are sorted the same way, and their
     * order replaces the one they were read in. The sort is stable.
     */
    public fun sortWith(comparator: Comparator<Pair<Key, Item>>) {
        forgetOrder()
        sortEntriesWith(comparator)
    }

    private fun sortEntriesWith(comparator: Comparator<Pair<Key, Item>>) {
        val pairs = items.snapshot()
        val sortedLines = pairs
            .filter { !it.item.printsOwnHeader() }
            .sortedWith { left, right -> comparator.compare(left.key to left.item, right.key to right.item) }
            .iterator()
        // A table under its own header keeps its place among the entries, so hand-made tables keep their order too.
        val slots = pairs
            .map { if (it.item.printsOwnHeader()) it else sortedLines.next() }
            .withIndex()
            .associate { (index, entry) -> entry to index }
        items.sortBy(compareBy { slots.getValue(it) })
        for (entry in items.entries) {
            val table = entry.item.standardTableOrNull
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
    override fun keyOrNull(name: String): Key? = items.getEntry(name)?.key

    internal fun resolveSpans(input: SourceText) {
        span = null
        rawDecor.resolveSpans(input)
        headerSpelling?.resolveSpans(input)
        for (entry in items.entries) {
            entry.key.resolveSpans(input)
            entry.pathSpelling?.resolveSpans(input)
            entry.item.resolveSpans(input)
        }
    }

    /** The key/item pairs, in order. */
    override val entries: List<Pair<String, Item>>
        get() = items.entries.map { it.key.name to it.item }

    override fun iterator(): Iterator<Pair<String, Item>> = entries.iterator()

    /** The keys, in order, as a copy that later changes to the table leave as it is. */
    override val keys: Set<String>
        get() = entries.mapTo(LinkedHashSet()) { it.first }

    /** The number of keys. */
    override val size: Int
        get() = items.size

    /** Whether the table has no keys. */
    override fun isEmpty(): Boolean = size == 0

    /** Whether the table has a key. */
    override fun isNotEmpty(): Boolean = !isEmpty()

    /** Removes every entry, keeping the lines above each as [remove] does. */
    override fun clear() {
        removeEntries(items.snapshot())
    }

    /** The item at [key], or null when there is none. */
    public fun getOrNull(key: String): Item? = items.get(key)

    override fun getItem(key: String): Item? = getOrNull(key)

    /** The item stored at [key], or [TomlEditError.NotFound] when there is none. */
    public operator fun get(key: String): Result<Item, TomlEditError> =
        items.get(key)?.let { Ok(it) } ?: Err(TomlEditError.NotFound(key))

    /** Stores a copy of [item] at [key], keeping the key's position and formatting when it is already there. */
    public operator fun set(
        key: String,
        item: Item,
    ) {
        put(key, item)
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
        table: StandardTable,
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
    override fun containsKey(key: String): Boolean = items.containsKey(key)

    /** Whether [key] holds an item, for `key in table`. */
    override operator fun contains(key: String): Boolean = containsKey(key)

    /**
     * Sets [key] to a copy of [item], as `table[key] = item` does: a key already there keeps its position, its
     * spelling and the comments above it. It keeps its spacing too, unless [item] moves it between a `key = value`
     * line and a `[header]` or `[[header]]`, where it takes the default spacing. Pass a [Key] to replace all of
     * its formatting.
     *
     * @return the item [key] held before, or null when it held none.
     */
    public fun put(
        key: String,
        item: Item,
    ): Item? {
        items.getEntry(key)?.prepareFor(item)
        return items.put(Key(key), item.copy())
    }

    /**
     * Sets [key], with its spelling and decor, to a copy of [item]. A key already there keeps its position but
     * takes the spelling and decor of [key].
     *
     * @return the item the key held before, or null when it held none.
     */
    public fun put(
        key: Key,
        item: Item,
    ): Item? {
        items.getEntry(key.name)?.key = key.copy()
        return items.put(key.copy(), item.copy())
    }

    override fun putItem(
        key: String,
        item: Item,
    ): Item? = put(key, item)

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
        items.put(key.copy(), stored)
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
        items.remove(key)
        return entry.item
    }

    /** Removes [key] and returns its item, or null when it held none; the lines above it stay, as [remove] says. */
    override fun removeItem(key: String): Item? = remove(key)

    /**
     * Keeps only the pairs [predicate] accepts, visiting each pair of key and item once, in order. The lines above
     * a removed pair stay, as [remove] keeps them.
     *
     * ```kotlin
     * table.retainAll { (key, _) -> key != "debug" }
     * ```
     *
     * @return whether any pair was removed.
     */
    public fun retainAll(predicate: (Pair<String, Item>) -> Boolean): Boolean =
        removeEntries(entriesWhere { !predicate(it) })

    /**
     * Removes the pairs [predicate] accepts, visiting each pair of key and item once, in order. The lines above a
     * removed pair stay, as [remove] keeps them.
     *
     * @return whether any pair was removed.
     */
    public fun removeAll(predicate: (Pair<String, Item>) -> Boolean): Boolean = removeEntries(entriesWhere(predicate))

    private fun entriesWhere(predicate: (Pair<String, Item>) -> Boolean): List<KeyValue> = items.entries
        .filter { predicate(it.key.name to it.item) }

    private fun removeEntries(removed: List<KeyValue>): Boolean {
        if (removed.isEmpty()) return false
        keepLinesAbove(removed)
        val names = removed.mapTo(HashSet()) { it.key.name }
        items.retainAll { it.key.name !in names }
        return true
    }

    private fun keepLinesAbove(removed: Collection<KeyValue>) {
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
    public fun copy(): StandardTable {
        val copy = StandardTable(items.deepCopy())
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

    /** Settles the spelling of every header below this table, whose path from the root is [path]. */
    internal fun settleHeaderSpellings(path: List<Key>) {
        for (entry in items.entries) {
            val childPath = path + entry.key
            val tables = entry.item.standardTableOrNull?.let(::listOf)
                ?: entry.item.arrayOfTablesOrNull?.toList().orEmpty()
            for (table in tables) {
                table.headerSpelling?.settle(childPath)
                table.settleHeaderSpellings(childPath)
            }
        }
    }

    /** Takes [other]'s whole state, its entries moved rather than copied, leaving [other] not to be used again. */
    internal fun takeStateOf(other: StandardTable) {
        items = other.items
        rawDecor.assign(other.rawDecor)
        isImplicit = other.isImplicit
        isDotted = other.isDotted
        position = other.position
        span = other.span
        keptHeaderLines.clear()
        keptHeaderLines += other.keptHeaderLines
        headerLineEnding = other.headerLineEnding
        headerSpelling = other.headerSpelling
    }

    /** The key/value pairs under this table, one per line, without its header or sub-tables. */
    override fun toString(): String = buildString { encodeTableBody(this, this@StandardTable) }

    internal companion object {
        fun withPosition(position: Long?): StandardTable = StandardTable().also { it.position = position }
    }
}

/** A table holding copies of [pairs], in order. */
public fun standardTableOf(vararg pairs: Pair<String, Item>): StandardTable = pairs.asIterable().toStandardTable()

/** A table holding copies of these pairs, in order; a later pair for the same key replaces an earlier one. */
public fun Iterable<Pair<String, Item>>.toStandardTable(): StandardTable {
    val table = StandardTable()
    table.putAll(this)
    return table
}

private fun Item.printsOwnHeader(): Boolean = standardTableOrNull?.let { !it.isDotted } ?: (arrayOfTablesOrNull != null)

/** A value as it prints under a header: the key path that reaches it, and the entry that holds it. */
internal class VisualValue(
    val path: List<Key>,
    val value: Value,
    val entry: KeyValue,
    private val placedAt: Long? = null,
) : BodyLine {
    override val order: Long?
        get() = placedAt ?: entry.order

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
 * The line a dotted table left holding nothing prints, as `{}` under its key, below the lines [kept] from its removed
 * keys so that a comment above them stays above it.
 */
internal fun emptyTableLine(
    path: List<Key>,
    entry: KeyValue,
    kept: List<KeptLines>,
): VisualValue {
    val belowKept = kept.mapNotNull { it.order }.maxOrNull()
    return VisualValue(path.toList(), Value.InlineTable(InlineTable()), entry, belowKept)
}
