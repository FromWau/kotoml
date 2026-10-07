package com.fromwau.kotoml

/**
 * One entry of a [KeyValuePairs]: the key keeps its formatting, the item is the slot it names.
 *
 * A key/value line read from text also remembers how it ended ([lineEnding]), where it stood among the lines
 * of its section ([order]), and how its dotted path was spelled on that line ([pathSpelling]), so it prints
 * back as written even where a table shares one key between lines.
 */
internal class KeyValue(key: Key, item: Item) {
    var key: Key = key
        set(value) {
            field = value
            value.entry = this
        }

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
        key.entry = this
        item.entry = this
        item.adoptContent()
    }

    /** Whether the key starts a `key = value` line among a table's lines, rather than one in braces or a header. */
    val startsTableLine: Boolean
        get() {
            if (item.valueOrNull == null) return false
            var container: Any? = pairs?.owner
            while (container is InlineTable && container.isDotted) container = slotOf(container)?.owner
            return container is StandardTable
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

    /**
     * Puts [next], not copied, in place of this entry's slot, keeping the lines above; returns the old slot, which
     * keeps its content and no longer belongs to the table, as a removed one.
     */
    fun replace(
        next: Item,
        above: String = "",
    ): Item {
        val lines = takeLinesAbove(next)
        val old = item
        val oldDecor = old.valueOrNull?.rawDecor
        val oldTable = old.standardTableOrNull?.takeUnless { it.isDotted }
        val oldArray = old.arrayOfTablesOrNull
        item = next
        old.entry = null
        keepValueDecor(oldDecor)
        keepHeaderComment(oldTable?.rawDecor)
        val keptPlaces = keepPlacesOf(oldArray)
        if (!keptPlaces) keepLinesAbove(lines)
        keepTablePlace(oldTable)
        if (!keptPlaces) takeAttachedAbove(above)
        return old
    }

    /**
     * Puts [block], the comment lines an item stored here brings from where it was read, directly above what the item
     * prints, in place of the comment lines directly above it there; the lines set apart from it by a blank line stay.
     * Above a header that takes the default spacing they print below that spacing.
     */
    fun takeAttachedAbove(block: String) {
        if (block.isEmpty()) return
        val lineBreak = pairs?.owner?.let(::lineEndingOf) ?: LINE_BREAK
        val header = item.headerTable
        if (header != null) {
            val lines = indentedCommentLines(block, "", lineBreak)
            val prefix = header.rawDecor.prefix
            if (prefix == null) {
                val keyLines = linesAbove(key.rawLeafDecor.prefix)
                key.rawLeafDecor.prefix = KeptText.from(splitLinesAbove(keyLines).free + lines)
            } else {
                header.rawDecor.prefix = prefix.withAttachedAbove(lines)
            }
            return
        }
        val lineKey = firstLineKey() ?: return
        val start = lineKey.rawLeafDecor.prefix?.textOrNull
        val inBraces = pairs?.owner is InlineTable
        // Braces on one line have no line for a comment.
        if (inBraces && '\n' !in start.orEmpty()) return
        val indent = start?.substringAfterLast('\n') ?: indentOfLineBefore()
        val lines = indentedCommentLines(block, indent, lineBreak)
        // In braces the first break of a prefix ends the line before, whose comment stays with it.
        val spacedForBraces = inBraces || item.inlineTableOrNull?.isDotted == true
        val prefix = lineKey.rawLeafDecor.prefix ?: KeptText.from(indent)
        lineKey.rawLeafDecor.prefix = prefix.withAttachedAbove(lines, spacedForBraces)
    }

    // A line made by hand prints indented like the line above it, so lines put above it take that indentation too.
    private fun indentOfLineBefore(): String {
        val table = pairs?.owner as? StandardTable ?: return ""
        val lines = table.visualValues()
        val index = lines.indexOfFirst { line -> line.path.any { it === key } }
        val start = lines.getOrNull(index - 1)?.let { it.lineStart ?: it.path.last().rawLeafDecor.prefix }
        return start?.textOrNull?.substringAfterLast('\n').orEmpty()
    }

    /**
     * The comments this entry, removed from its table, owns above a value's line, which its key's decor still holds; a
     * table holds those above its header or first line, and the comment at the end of its line went with its item.
     */
    fun removedComments(): ItemComments =
        ItemComments(attachedAbove = splitLinesAbove(linesAbove(key.rawLeafDecor.prefix)).attached)

    /**
     * Gives an array of tables now in the slot the places of [old]'s tables and the lines above them, as
     * [ArrayOfTables.takePlacesOf] says; returns whether the lines above [old]'s first header stay at its place.
     */
    fun keepPlacesOf(old: ArrayOfTables?): Boolean =
        old != null && item.arrayOfTablesOrNull?.takePlacesOf(old) == true

    /** Gives a table now in the slot the place among the document's tables of the table [old] it replaced. */
    fun keepTablePlace(old: StandardTable?) {
        val table = item.standardTableOrNull?.takeUnless { it.isDotted } ?: return
        if (old != null) takePlaceOf(table, old)
    }

    /** Gives a table now in the slot the comment on the header of the table [old] it replaced, unless it has one. */
    fun keepHeaderComment(old: RawDecor?) {
        val table = item.standardTableOrNull?.takeUnless { it.isDotted } ?: return
        if (old != null && table.rawDecor.suffix?.textOrNull.isNullOrEmpty()) table.rawDecor.suffix = old.suffix
    }

    /**
     * Gives the value now in the slot the spacing and same-line comment of the value [old] it replaced, on each side
     * the new value leaves to the default, and the same-line comment when the new value brings none.
     */
    fun keepValueDecor(old: RawDecor?) {
        if (old == null) return
        val decor = item.valueOrNull?.rawDecor ?: return
        val oldComment = '#' in old.suffix?.textOrNull.orEmpty()
        if (oldComment && '#' !in decor.suffix?.textOrNull.orEmpty()) decor.suffix = null
        decor.fillDefaultsFrom(old)
    }

    /**
     * Readies the comment and blank lines above what the item prints for its replacement, returning those that go
     * above what replaces it: the lines above its header, or above the first of its dotted-key lines. The lines
     * above its later dotted-key lines stay where they print, unless [keptInside] says [next] holds them.
     */
    fun takeLinesAbove(
        next: Item,
        keptInside: Boolean = false,
    ): String {
        if (item.headerTable != null) return linesAboveHeader()
        val dottedTable = item.standardTableOrNull?.takeIf { it.isDotted }
        val dottedInline = item.inlineTableOrNull?.takeIf { it.isDotted }
        val lines = dottedTable?.bodyLines() ?: dottedInline?.bodyLines() ?: return ""
        val onTableLines = dottedInline?.printsOnTableLines() == true
        val above: (KeptText?) -> String = when {
            dottedInline == null -> ::linesAbove
            onTableLines -> ::linesAboveOnTableLines
            else -> ::linesAboveElement
        }
        // A value replacing dotted-key lines prints where the first of them did.
        if (order == null) order = lines.firstOrNull()?.order
        // A dotted table made from a header keeps the lines that stood above it, printed above its first line.
        var carried = linesAbove(dottedTable?.rawDecor?.prefix).takeIf { '#' in it }.orEmpty()
        val firstValue = lines.firstOrNull { it is VisualValue }
        for ((index, line) in lines.withIndex()) {
            val text = when (line) {
                is VisualValue -> above(line.entry.key.rawLeafDecor.prefix)
                is KeptLines -> if (onTableLines) keptLinesOnTableLines(line.text) else line.text
            }
            val pairs = pairs
            val order = line.order
            when {
                text.isEmpty() -> continue
                keptInside && line !== firstValue -> continue
                index == 0 || order == null || pairs == null -> carried += text
                else -> pairs.keptLines += KeptLines(text, order, (line as? KeptLines)?.dropBlankLinesMeeting == true)
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
internal val Item.headerTable: StandardTable?
    get() = standardTableOrNull?.takeUnless { it.isDotted } ?: arrayOfTablesOrNull?.firstOrNull()

/**
 * How one occurrence of a key path was spelled, and what the shared keys it stands for looked like then. It is
 * used only while those keys still look the same, so editing a key's formatting takes effect everywhere.
 */
internal class PathSpelling private constructor(
    private var keys: List<Key>,
    private var shared: List<Key>?,
    private var sharedLooks: List<KeyLooks>,
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

    /**
     * Gives each key spelled here the spelling [respelled] makes of its own, expecting the keys of the path this
     * stands for to take theirs the same way, so it keeps standing for them.
     */
    fun respellKeys(respelled: (String) -> String) {
        for (key in keys) key.spelling?.let { key.written = Spelling(respelled(it)) }
        sharedLooks = sharedLooks.map { looks -> looks.copy(spelling = looks.spelling?.let(respelled)) }
    }

    /**
     * Spells the key at [index] as [renamed], the key replacing [old] in the path this stands for, keeping the decor
     * it had here; a spelling that no longer stands for [old] there is left as it is.
     */
    fun rename(
        index: Int,
        old: Key,
        renamed: Key,
    ) {
        if (index !in keys.indices || index !in sharedLooks.indices) return
        val isLast = index == keys.lastIndex
        val spelled = keys[index]
        if (spelled.name != old.name || looks(old, isLast) != sharedLooks[index]) return
        keys = keys.toMutableList().also { it[index] = spelled.renamed(renamed.name) }
        sharedLooks = sharedLooks.toMutableList().also { it[index] = looks(renamed, isLast) }
    }

    /** Everything about the key at [index] of [path] that printing the path uses. */
    private fun looks(
        path: List<Key>,
        index: Int,
    ): KeyLooks = looks(path[index], index == path.lastIndex)

    private fun looks(
        key: Key,
        isLast: Boolean,
    ): KeyLooks {
        val dotted = "${key.rawDottedDecor.prefix}|${key.rawDottedDecor.suffix}"
        val spelling = key.written?.raw?.toString()
        // A header prints its last key with that key's own decor, inside the brackets.
        if (!isHeader || !isLast) return KeyLooks(spelling, dotted)
        return KeyLooks(spelling, "$dotted|${key.rawLeafDecor.prefix}|${key.rawLeafDecor.suffix}")
    }

    private data class KeyLooks(val spelling: String?, val decor: String)

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

    /** The spacing around the `{}` these pairs print as while empty under a dotted key; see [emptyTableLine]. */
    val emptyTableDecor: RawDecor = RawDecor()

    /** The spacing the line these pairs print as while empty on a table's lines starts with and puts before `=`. */
    val emptyLineKeyDecor: RawDecor = RawDecor()

    val size: Int get() = byName.size

    fun get(key: String): Item? = byName[key]?.item

    fun getEntry(key: String): KeyValue? = byName[key]

    fun containsKey(key: String): Boolean = byName.containsKey(key)

    /**
     * Inserts or replaces the item under [key]'s text. A replaced entry keeps its own key and its position, and takes
     * [item] as its slot as [KeyValue.replace] puts it, so a slot handed out for the key before is detached. [above]
     * goes directly above what [item] prints, as [KeyValue.takeAttachedAbove] says.
     *
     * @return the item the key held before, or null when there was no entry.
     */
    fun put(
        key: Key,
        item: Item,
        above: String = "",
    ): Item? {
        val existing = byName[key.name]
        if (existing == null) {
            val entry = KeyValue(key, item).also { it.pairs = this }
            byName[key.name] = entry
            entry.takeAttachedAbove(above)
            return null
        }
        return existing.replace(item, above).also { old -> owner?.let(old::keepDocumentOf) }
    }

    fun remove(key: String): KeyValue? = byName.remove(key)?.also { entry -> owner?.let(entry.item::keepDocumentOf) }

    /** Gives the entry under [old] the key [renamed], keeping its place among the entries. */
    fun rename(
        old: String,
        renamed: Key,
    ) {
        val entries = byName.values.toList()
        byName.clear()
        for (entry in entries) {
            if (entry.key.name == old) entry.key = renamed
            byName[entry.key.name] = entry
        }
    }

    val entries: Collection<KeyValue>
        get() = byName.values

    fun snapshot(): List<KeyValue> = byName.values.toList()

    fun retainAll(keep: (KeyValue) -> Boolean) {
        val iterator = byName.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (keep(entry)) continue
            iterator.remove()
            owner?.let(entry.item::keepDocumentOf)
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
        copy.emptyTableDecor.assign(emptyTableDecor)
        copy.emptyLineKeyDecor.assign(emptyLineKeyDecor)
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
