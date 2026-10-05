package com.fromwau.kotoml

/**
 * One entry of a [KeyValuePairs]: the key keeps its formatting, the item is the slot it names.
 *
 * A key/value line read from text also remembers how it ended ([lineEnding]), where it stood among the lines
 * of its section ([order]), and how its dotted path was spelled on that line ([pathSpelling]), so it prints
 * back as written even where a table shares one key between lines.
 */
internal class KeyValue(var key: Key, item: Item) {
    var item: Item = item
        set(value) {
            field = value
            value.entry = this
            value.adoptContent()
        }

    var lineEnding: String? = null
    var order: Long? = null
    var pathSpelling: PathSpelling? = null

    /** The pairs holding this entry, whose table keeps the lines a replacement leaves where they print. */
    var pairs: KeyValuePairs? = null

    /** Whether this entry is still in its [pairs], rather than removed from them. */
    val isStored: Boolean
        get() = pairs?.getEntry(key.name) === this

    init {
        item.entry = this
        item.adoptContent()
    }

    /**
     * Readies the key for [next] replacing its item: moving between a `key = value` line and a header leaves the
     * key's spacing behind, since it belongs to the line it stood on; the comments above it come along, and
     * [keepLinesAbove] brings along those above a header.
     */
    fun prepareFor(next: Item) {
        if (item.isHeaderKind == next.isHeaderKind) return
        val decor = key.rawLeafDecor
        if (decor.prefix?.textOrNull.isNullOrBlank()) decor.prefix = null
        if (decor.suffix?.textOrNull.isNullOrBlank()) decor.suffix = null
    }

    /** Puts [next]'s content, not copied, in this entry's slot, keeping the lines above; returns the old content. */
    fun replace(next: Item): Item {
        val lines = takeLinesAbove(next)
        val oldDecor = item.valueOrNull?.rawDecor
        val old = item.takeContentOf(next)
        keepValueDecor(oldDecor)
        keepLinesAbove(lines)
        return old
    }

    /**
     * Gives the value now in the slot the spacing and same-line comment of the value [old] it replaced, on each side
     * the new value leaves to the default.
     */
    fun keepValueDecor(old: RawDecor?) {
        if (old != null) item.valueOrNull?.rawDecor?.fillDefaultsFrom(old)
    }

    /**
     * Readies the comment and blank lines above what the item prints for its replacement, returning those that go
     * above what replaces it: the lines above its header, or above the first of its dotted-key lines. The lines
     * above its later dotted-key lines stay where they print.
     */
    fun takeLinesAbove(next: Item): String {
        if (item.headerTable != null) return linesAboveHeader()
        val dottedTable = item.standardTableOrNull?.takeIf { it.isDotted }
        val dottedInline = item.inlineTableOrNull?.takeIf { it.isDotted }
        val lines = dottedTable?.bodyLines() ?: dottedInline?.bodyLines() ?: return ""
        val inBraces = dottedInline != null && !dottedInline.printsOnTableLines()
        val above: (KeptText?) -> String = if (inBraces) ::linesAboveElement else ::linesAbove
        // A value replacing dotted-key lines prints where the first of them did.
        if (order == null) order = lines.firstOrNull()?.order
        var carried = ""
        for ((index, line) in lines.withIndex()) {
            val text = when (line) {
                is VisualValue -> above(line.entry.key.rawLeafDecor.prefix)
                is KeptLines -> line.text
            }
            val pairs = pairs
            val order = line.order
            when {
                text.isEmpty() -> continue
                index == 0 || order == null || pairs == null -> carried += text
                else -> pairs.keptLines += KeptLines(text, order)
            }
        }
        // The key's own prefix prints below a header's default spacing, as a replaced value's comment does.
        if (!next.isHeaderKind) return carried
        if (carried.isNotEmpty()) key.rawLeafDecor.prependToPrefix(carried, DEFAULT_KEY_DECOR.first)
        return ""
    }

    /** The comment and blank lines above the header the item prints, which stay when the item is replaced. */
    fun linesAboveHeader(): String = linesAbove(item.headerTable?.rawDecor?.prefix)

    /** Puts [lines], kept from the header of the item replaced, above what the item prints now. */
    fun keepLinesAbove(lines: String) {
        if (lines.isEmpty()) return
        val header = item.headerTable
        if (header != null) {
            val onHeaderLine = header.rawDecor.prefix?.textOrNull?.substringAfterLast('\n').orEmpty()
            header.rawDecor.prefix = KeptText.from(lines + onHeaderLine)
            return
        }
        // Blank lines above a header only space it out, as the key's spacing does in prepareFor.
        if (lines.isBlank()) return
        firstLineKey()?.rawLeafDecor?.prependToPrefix(lines, DEFAULT_KEY_DECOR.first)
    }

    // A line's leading text sits in the prefix of its last key, which for dotted keys is the first line's leaf.
    private fun firstLineKey(): Key? {
        val dottedTable = item.standardTableOrNull?.takeIf { it.isDotted }
        val dottedInline = item.inlineTableOrNull?.takeIf { it.isDotted }
        return when {
            dottedTable != null -> dottedTable.visualValues().firstOrNull()?.entry?.key
            dottedInline != null -> dottedInline.visualValues().firstOrNull()?.entry?.key
            item.valueOrNull != null -> key
            else -> null
        }
    }
}

/** Whether this item prints under headers once it holds anything: a table that is not dotted, or an array of tables. */
private val Item.isHeaderKind: Boolean
    get() = standardTableOrNull?.let { !it.isDotted } ?: (arrayOfTablesOrNull != null)

/** The table whose header prints first for this item: its table, or the first of its array of tables. */
private val Item.headerTable: StandardTable?
    get() = standardTableOrNull?.takeUnless { it.isDotted } ?: arrayOfTablesOrNull?.firstOrNull()

/**
 * How one occurrence of a key path was spelled, and what the shared keys it stands for looked like then. It is
 * used only while those keys still look the same, so editing a key's formatting takes effect everywhere.
 */
internal class PathSpelling private constructor(
    private val keys: List<Key>,
    private var shared: List<Key>?,
    private var sharedLooks: List<String>,
    private val isHeader: Boolean,
) {

    /** The keys to print for [current], or [current] itself when this spelling no longer applies. */
    fun resolve(current: List<Key>): List<Key> {
        if (current.size != keys.size || sharedLooks.size != keys.size) return current
        for (index in current.indices) {
            if (current[index].name != keys[index].name || looks(current, index) != sharedLooks[index]) {
                return current
            }
        }
        return keys
    }

    // The shared keys are resolved first: a table resolves its own keys before the entries below it.
    fun resolveSpans(input: SourceText) {
        for (key in keys) key.resolveSpans(input)
        shared?.let { keys -> sharedLooks = keys.indices.map { looks(keys, it) } }
        shared = null
    }

    /** Takes [current], the keys the finished parse left on this header's path, as what this spelling stands for. */
    fun settle(current: List<Key>) {
        sharedLooks = current.indices.map { looks(current, it) }
    }

    fun copy(): PathSpelling = PathSpelling(keys.map { it.copy() }, shared, sharedLooks, isHeader)

    /** Everything about the key at [index] of [path] that printing the path uses. */
    private fun looks(path: List<Key>, index: Int): String {
        val key = path[index]
        val dotted = "${key.written?.raw}|${key.rawDottedDecor.prefix}|${key.rawDottedDecor.suffix}"
        // A header prints its last key with that key's own decor, inside the brackets.
        if (!isHeader || index != path.lastIndex) return dotted
        return "$dotted|${key.rawLeafDecor.prefix}|${key.rawLeafDecor.suffix}"
    }

    companion object {
        fun of(spelled: List<Key>, shared: List<Key>): PathSpelling =
            PathSpelling(spelled.map { it.copy() }, shared, emptyList(), isHeader = false).also { spelling ->
                spelling.sharedLooks = shared.indices.map { spelling.looks(shared, it) }
            }

        /**
         * How a header spelled its whole path, its own key included. A later header can replace the keys it passes
         * through, so what the spelling stands for is settled once the parse is done.
         */
        fun ofHeader(spelled: List<Key>): PathSpelling =
            PathSpelling(spelled.map { it.copy() }, null, emptyList(), isHeader = true)
    }
}

/**
 * An insertion-ordered map from key text to entries: putting an existing key keeps its position and its key,
 * removing shifts the rest up, and sorting is stable.
 */
internal class KeyValuePairs {
    private val byName = LinkedHashMap<String, KeyValue>()

    /** The table these are the entries of. */
    var owner: Table? = null

    /** Lines kept from removed entries, printed among the entries' lines; see [KeptLines]. */
    val keptLines: MutableList<KeptLines> = mutableListOf()

    val size: Int get() = byName.size

    fun get(key: String): Item? = byName[key]?.item

    fun getEntry(key: String): KeyValue? = byName[key]

    fun containsKey(key: String): Boolean = byName.containsKey(key)

    /**
     * Inserts or replaces the item under [key]'s text. A replaced entry keeps its own key, its position and its slot,
     * which takes [item]'s content as [KeyValue.replace] puts it, so a slot handed out for the key stays its place.
     *
     * @return the item the key held before, or null when there was no entry.
     */
    fun put(key: Key, item: Item): Item? {
        val existing = byName[key.name]
        if (existing == null) {
            byName[key.name] = KeyValue(key, item).also { it.pairs = this }
            return null
        }
        return existing.replace(item)
    }

    fun remove(key: String): KeyValue? = byName.remove(key)

    val entries: Collection<KeyValue>
        get() = byName.values

    fun snapshot(): List<KeyValue> = byName.values.toList()

    fun retainAll(keep: (KeyValue) -> Boolean) {
        val iterator = byName.values.iterator()
        while (iterator.hasNext()) {
            if (!keep(iterator.next())) iterator.remove()
        }
    }

    fun sortBy(comparator: Comparator<KeyValue>) {
        val sorted = byName.values.sortedWith(comparator)
        byName.clear()
        for (entry in sorted) byName[entry.key.name] = entry
    }

    fun deepCopy(): KeyValuePairs {
        val copy = KeyValuePairs()
        for (entry in byName.values) {
            val copied = KeyValue(entry.key.copy(), entry.item.copySlot())
            copied.lineEnding = entry.lineEnding
            copied.order = entry.order
            copied.pathSpelling = entry.pathSpelling?.copy()
            copied.pairs = copy
            copy.byName[entry.key.name] = copied
        }
        keptLines.mapTo(copy.keptLines) { it.copy() }
        return copy
    }

    /** Moves every entry out, leaving this map empty. */
    fun takeAll(): KeyValuePairs {
        val taken = KeyValuePairs()
        taken.byName.putAll(byName)
        for (entry in byName.values) entry.pairs = taken
        byName.clear()
        return taken
    }
}
