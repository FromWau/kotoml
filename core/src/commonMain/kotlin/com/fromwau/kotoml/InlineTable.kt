package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * An inline table value, `{ a = 1, b.c = 2 }`, with its formatting.
 *
 * Its entries are values. A dotted key such as `b.c` is held as a nested inline table marked dotted, so it
 * prints back as a dotted key rather than as `b = { c = 2 }`.
 *
 * ```kotlin
 * val table = inlineTableOf("a" to Value.Integer(1L))
 * table["b"] = "two"
 * table.toString() // "{ a = 1, b = \"two\" }"
 * ```
 */
public class InlineTable() : TableLike, Iterable<Pair<String, Value>> {
    internal var rawTrailing: RawString = RawString()

    /** Whether the table ends with a comma after its last entry. */
    public var trailingComma: Boolean = false

    internal var isImplicit: Boolean = false
    internal val rawDecor: RawDecor = RawDecor()
    internal var span: Span? = null

    /** Whether this table stands for the parts of a dotted key rather than for `{ ... }`. */
    override var isDotted: Boolean = false

    internal var items: KeyValuePairs = KeyValuePairs()

    internal constructor(items: KeyValuePairs) : this() {
        for (entry in items.entries()) entry.item = entry.item.movedTo(Item.Holds.Value)
        this.items = items
    }

    /** A standard table holding copies of this table's entries, formatted anew; this table stays as it is. */
    public fun toTable(): Table = copy().intoTable()

    /** Moves the entries into a new standard table, formatted anew, leaving this table empty. */
    internal fun intoTable(): Table {
        val table = Table(items.takeAll())
        table.resetFormatting()
        return table
    }

    /** The values visually under this table with the key path that reaches each, dotted keys followed. */
    override fun valuesWithPaths(): List<Pair<List<Key>, Value>> = visualValues().map { it.path to it.value }

    /** The values in the order they print: as read from text, new ones after their sibling. */
    internal fun visualValues(): List<VisualValue> = bodyLines().filterIsInstance<VisualValue>()

    /** The entries in the order they print, the lines kept from removed entries included. */
    internal fun bodyLines(): List<BodyLine> {
        val lines = mutableListOf<BodyLine>()
        appendValues(mutableListOf(), lines)
        return inPrintOrder(lines)
    }

    internal fun appendValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries()) {
            path.add(entry.key)
            val item = entry.item
            val value = item.valueOrNull
            val table = item.tableOrNull
            when {
                value is Value.InlineTable && value.table.isDotted -> value.table.appendValues(path, values)
                value != null -> values.add(VisualValue(path.toList(), value, entry))
                table != null -> table.appendAllValues(path, values)
            }
            path.removeAt(path.size - 1)
        }
        values += items.keptLines
    }

    // A sort sets the order, so the order entries were read in no longer applies.
    private fun forgetOrder() {
        for (kept in items.keptLines) kept.order = null
        for (entry in items.entries()) {
            entry.order = null
            val table = entry.item.inlineTableOrNull
            if (table != null && table.isDotted) table.forgetOrder()
        }
    }

    /** Drops the formatting of every key and value, for the default `{ a = 1, b = 2 }` layout. */
    override fun resetFormatting() {
        for (entry in items.entries()) {
            val value = entry.item.valueOrNull ?: continue
            entry.key.rawLeafDecor.clear()
            entry.key.rawDottedDecor.clear()
            value.rawDecor().clear()
        }
    }

    /** The text after the last entry. */
    public var trailing: String
        get() = rawTrailing.toStrWithDefault("")
        set(value) {
            rawTrailing = RawString.from(value)
        }

    /**
     * Sorts the entries in the natural order of what [selector] returns for each pair of key and value.
     *
     * The parts of a dotted key are sorted the same way, and their order replaces the one they were read in. The
     * sort is stable, and nulls sort first.
     *
     * ```kotlin
     * inlineTable.sortBy { (key, _) -> key }
     * ```
     */
    public fun <R : Comparable<R>> sortBy(selector: (Pair<Key, Value>) -> R?) {
        sortWith(compareBy(selector))
    }

    /**
     * Sorts the entries with [comparator], given each pair of key and value.
     *
     * The parts of a dotted key are sorted the same way, and their order replaces the one they were read in. The
     * sort is stable.
     */
    public fun sortWith(comparator: Comparator<Pair<Key, Value>>) {
        forgetOrder()
        sortEntriesWith(comparator)
    }

    private fun sortEntriesWith(comparator: Comparator<Pair<Key, Value>>) {
        items.sortBy { left, right ->
            val leftValue = left.item.valueOrNull
            val rightValue = right.item.valueOrNull
            when {
                leftValue != null && rightValue != null -> {
                    comparator.compare(left.key to leftValue, right.key to rightValue)
                }
                leftValue != null -> 1
                rightValue != null -> -1
                else -> 0
            }
        }
        for (entry in items.entries()) {
            val table = entry.item.inlineTableOrNull
            if (table != null && table.isDotted) table.sortEntriesWith(comparator)
        }
    }

    /** The text around the table, such as the whitespace between it and the `=` before it. */
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
        rawTrailing = rawTrailing.despan(input)
        for (entry in items.entries()) {
            entry.key.despan(input)
            entry.pathSpelling?.despan(input)
            entry.item.despan(input)
        }
    }

    override fun iterator(): Iterator<Pair<String, Value>> = items.entries()
        .mapNotNull { entry -> entry.item.valueOrNull?.let { entry.key.name to it } }
        .iterator()

    /** The key/item pairs, in order; each item holds a value. */
    override fun items(): List<Pair<String, Item>> = items.entries()
        .filter { !it.item.isNone }
        .map { it.key.name to it.item }

    /** The number of key/value pairs. */
    override val size: Int
        get() = items.entries().count { it.item.valueOrNull != null }

    /** Whether the table has no key/value pairs. */
    override fun isEmpty(): Boolean = size == 0

    /** Removes every entry, keeping the lines above each as [remove] does. */
    override fun clear() {
        retainAll { _, _ -> false }
    }

    /** The value at [key], or null when there is none. */
    public operator fun get(key: String): Value? = items.get(key)?.valueOrNull

    /** The slot at [key], or null when there is none. */
    override fun item(key: String): Item? = items.get(key)?.takeIf { !it.isNone }

    /** Sets [key] to [value], keeping the key's position and formatting when it is already there. */
    public operator fun set(
        key: String,
        value: Value,
    ) {
        items.insert(Key(key), Item.inInlineTable(value))
    }

    /** Whether there is a value at [key]. */
    override fun containsKey(key: String): Boolean = items.get(key)?.valueOrNull != null

    /** Whether there is a value at [key], for `key in table`. */
    public operator fun contains(key: String): Boolean = containsKey(key)

    /**
     * The value at [key], first storing what [default] makes when there is none. [default] is called only then.
     *
     * @return the stored value, to change in place.
     */
    public fun getOrPut(
        key: String,
        default: () -> Value,
    ): Value = getOrPut(Key(key), default)

    /** As [getOrPut], an inserted key keeping the spelling and decor of [key]. */
    public fun getOrPut(
        key: Key,
        default: () -> Value,
    ): Value {
        get(key.name)?.let { return it }
        val value = default()
        items.insert(key.copy(), Item.inInlineTable(value))
        return value
    }

    /**
     * Sets [key] to [value]. A key already there keeps its position but loses its formatting; pass a [Key] to
     * choose it.
     *
     * @return the value [key] held before, or null when it held none.
     */
    public fun put(
        key: String,
        value: Value,
    ): Value? = put(Key(key), value)

    /**
     * Sets [key], with its spelling and decor, to [value]. A key already there keeps its position.
     *
     * @return the value the key held before, or null when it held none.
     */
    public fun put(
        key: Key,
        value: Value,
    ): Value? {
        val existing = items.getEntry(key.name)
        if (existing != null) {
            existing.key = key.copy()
            val old = existing.item
            existing.item = Item.inInlineTable(value)
            return old.valueOrNull
        }
        items.insert(key.copy(), Item.inInlineTable(value))
        return null
    }

    override fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError> {
        val value = item.copy().intoValue() ?: return Err(TomlEditError.NotAValue(item.type))
        return Ok(put(key, value)?.let { Item(it) })
    }

    /**
     * Removes [key] and returns its value, or null when it held none.
     *
     * Only the entry's own text goes: the key, its value, its comma and, for an entry on a line of its own, the
     * rest of that line with the comment on it. The comment and blank lines above the entry stay where they print,
     * now above the next entry or before the closing `}`. Upstream drops those lines with the key.
     */
    public fun remove(key: String): Value? {
        val entry = items.getEntry(key) ?: return null
        keepLinesAbove(listOf(entry))
        items.shiftRemove(key)
        return entry.item.valueOrNull
    }

    /** Removes [key] and returns its value as an item, or null when it held none; see [remove]. */
    override fun removeItem(key: String): Item? = remove(key)?.let { Item(it) }

    /**
     * Keeps only the pairs [predicate] accepts, visiting them in order. A removed pair's own text goes and the
     * lines above it stay, as [remove] says.
     */
    public fun retainAll(predicate: (String, Value) -> Boolean) {
        val removed = items.entries().filterNot { entry ->
            entry.item.valueOrNull?.let { predicate(entry.key.name, it) } ?: false
        }
        keepLinesAbove(removed)
        val names = removed.mapTo(HashSet()) { it.key.name }
        items.retain { it.key.name !in names }
    }

    private fun keepLinesAbove(removed: Collection<KeyValue>) {
        if (removed.isEmpty()) return
        if (!isDotted) {
            leaveTextAfterRemoved(bodyLines(), RemovedLines(removed))
            return
        }
        keepLinesAbove(
            lines = bodyLines(),
            removed = RemovedLines(removed),
            isDotted = true,
            linesAbove = { linesAboveElement(it.entry.key.rawLeafDecor.prefix) },
            keyDefault = DEFAULT_INLINE_KEY_DECOR.first,
            keep = { items.keptLines += it },
        )
    }

    // Walks back from the end, so the text after a removed entry already holds what removing later ones left.
    private fun leaveTextAfterRemoved(
        lines: List<BodyLine>,
        removed: RemovedLines,
    ) {
        var next: BodyLine? = null
        for ((index, line) in lines.withIndex().reversed()) {
            if (line !in removed) {
                next = line
                continue
            }
            val replacement = when (line) {
                is KeptLines -> line.text + textBefore(next)
                is VisualValue -> textAfterRemovedElement(
                    prefix = line.entry.key.rawLeafDecor.prefix?.asStr().orEmpty(),
                    suffix = line.value.rawDecor().suffix?.asStr().orEmpty(),
                    next = textBefore(next),
                    nextIsElement = next is VisualValue,
                ) ?: continue
            }
            when (next) {
                is VisualValue -> next.entry.key.rawLeafDecor.prefix = RawString.from(replacement)
                is KeptLines -> next.text = replacement
                null -> {
                    trailing = replacement
                    // The comma before the removed entry ends its line now, ahead of the lines kept.
                    val survivorBefore = lines.subList(0, index).any { it is VisualValue && it !in removed }
                    if (line is VisualValue && survivorBefore) trailingComma = true
                }
            }
        }
    }

    private fun textBefore(line: BodyLine?): String = when (line) {
        is VisualValue -> line.entry.key.rawLeafDecor.prefix?.asStr().orEmpty()
        is KeptLines -> line.text
        null -> trailing
    }

    /** Sets the key of every pair of [pairs] to its value, in order, as `table[key] = value` does. */
    public fun putAll(pairs: Iterable<Pair<String, Value>>) {
        for ((key, value) in pairs) set(key, value)
    }

    /** A deep copy of this table. */
    public fun copy(): InlineTable {
        val copy = InlineTable(items.deepClone())
        copy.rawTrailing = rawTrailing
        copy.trailingComma = trailingComma
        copy.isImplicit = isImplicit
        copy.rawDecor.assign(rawDecor)
        copy.span = span
        copy.isDotted = isDotted
        return copy
    }

    /** The table as TOML, with its decor. */
    override fun toString(): String = buildString { encodeInlineTable(this, this@InlineTable, "", "") }
}

/** An inline table of [pairs], in order, keeping the values' formatting. */
public fun inlineTableOf(vararg pairs: Pair<String, Value>): InlineTable = pairs.asIterable().toInlineTable()

/**
 * An inline table of these pairs, in order, keeping the values' formatting; a later pair for the same key
 * replaces an earlier one.
 */
public fun Iterable<Pair<String, Value>>.toInlineTable(): InlineTable {
    val table = InlineTable()
    table.putAll(this)
    return table
}

// `{ key1 = value1, ... }`
internal val DEFAULT_INLINE_KEY_DECOR: Pair<String, String> = " " to " "
