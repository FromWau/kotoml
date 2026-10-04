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
        }

    var lineEnding: String? = null
    var order: Long? = null
    var pathSpelling: PathSpelling? = null

    init {
        item.entry = this
    }

    /**
     * Readies the key for [next] replacing its item: moving between a `key = value` line and a header leaves the
     * key's spacing behind, since it belongs to the line it stood on; the comments above it come along.
     */
    fun prepareFor(next: Item) {
        if (item.isMissing || item.printsHeader == next.printsHeader) return
        val decor = key.rawLeafDecor
        if (decor.prefix?.textOrNull.isNullOrBlank()) decor.prefix = null
        if (decor.suffix?.textOrNull.isNullOrBlank()) decor.suffix = null
    }
}

private val Item.printsHeader: Boolean
    get() = tableOrNull?.let { !it.isDotted } ?: (arrayOfTablesOrNull != null)

/**
 * How one occurrence of a key path was spelled, and what the shared keys it stands for looked like then. It is
 * used only while those keys still look the same, so editing a key's formatting takes effect everywhere.
 */
internal class PathSpelling private constructor(
    private val keys: List<Key>,
    private var shared: List<Key>?,
    private var sharedLooks: List<String>,
) {

    /** The keys to print for [current], or [current] itself when this spelling no longer applies. */
    fun resolve(current: List<Key>): List<Key> {
        if (current.size != keys.size) return current
        for (index in current.indices) {
            if (current[index].name != keys[index].name || looks(current[index]) != sharedLooks[index]) {
                return current
            }
        }
        return keys
    }

    // The shared keys are resolved first: a table resolves its own keys before the entries below it.
    fun resolveSpans(input: SourceText) {
        for (key in keys) key.resolveSpans(input)
        shared?.let { sharedLooks = it.map(::looks) }
        shared = null
    }

    fun copy(): PathSpelling = PathSpelling(keys.map { it.copy() }, shared, sharedLooks)

    companion object {
        fun of(spelled: List<Key>, shared: List<Key>): PathSpelling =
            PathSpelling(spelled.map { it.copy() }, shared, shared.map(::looks))

        /** Everything about a key that printing a path uses. */
        private fun looks(key: Key): String =
            "${key.written?.raw}|${key.rawDottedDecor.prefix}|${key.rawDottedDecor.suffix}"
    }
}

/**
 * An insertion-ordered map from key text to entries: putting an existing key keeps its position and its key,
 * removing shifts the rest up, and sorting is stable.
 */
internal class KeyValuePairs {
    private val byName = LinkedHashMap<String, KeyValue>()

    /** Lines kept from removed entries, printed among the entries' lines; see [KeptLines]. */
    val keptLines: MutableList<KeptLines> = mutableListOf()

    val size: Int get() = byName.size

    fun get(key: String): Item? = byName[key]?.item

    fun getEntry(key: String): KeyValue? = byName[key]

    fun containsKey(key: String): Boolean = byName.containsKey(key)

    /** Inserts or replaces the item under [key]'s text; a replaced entry keeps its own key and position. */
    fun put(key: Key, item: Item): Item? {
        val existing = byName[key.name]
        if (existing != null) {
            val old = existing.item
            existing.item = item
            return old
        }
        byName[key.name] = KeyValue(key, item)
        return null
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
            copy.byName[entry.key.name] = copied
        }
        keptLines.mapTo(copy.keptLines) { it.copy() }
        return copy
    }

    /** Moves every entry out, leaving this map empty. */
    fun takeAll(): KeyValuePairs {
        val taken = KeyValuePairs()
        taken.byName.putAll(byName)
        byName.clear()
        return taken
    }
}
