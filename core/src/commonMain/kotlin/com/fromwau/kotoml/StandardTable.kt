package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map

/**
 * A standard table: the key/value pairs under a `[header]`, and the tables nested in it.
 *
 * A table that only exists because a deeper header names it, such as `a` in `[a.b]`, is implicit and is
 * not printed while it holds no values of its own. A table made in code that holds only tables under headers of their
 * own prints no header either, as `[tool]` does not above `[tool.ruff]`, until it holds a value or is given a
 * [decor]. A table that stands for the parts of a dotted key, such as `a` in `a.b = 1`, is dotted.
 *
 * Indexing reads and writes slots: `table["key"]` returns the [Item] stored there, or
 * [TomlEditError.NotFound]; `table["key"] = item` stores a copy of `item`, keeping the key's formatting.
 *
 * ```kotlin
 * val table = standardTableOf("name" to Item(Value.String("kotoml"))).getOrElse { error(it) }
 * table["version"] = "0.1.0"
 * table.toString() // "name = \"kotoml\"\nversion = \"0.1.0\"\n"
 * ```
 *
 * In a document whose [TomlDocument.version] is TOML 1.0, a store that would add what only TOML 1.1 reads, such as a
 * string spelled with a `\e` escape, fails with [TomlEditError.NotInToml10] and stores nothing.
 */
public class StandardTable() : Table, Iterable<Pair<String, Item>> {
    internal val rawDecor: RawDecor = RawDecor()

    /**
     * Whether the table's header is hidden while it holds no values of its own and tables under it print theirs. A
     * table left holding nothing prints its header, so its key stays in the text. A table made in code with no
     * [decor] hides its header the same way while this is false. [setImplicit] changes it.
     */
    public var isImplicit: Boolean = false
        internal set

    /**
     * Hides the table's header while it holds no values of its own and tables under it print theirs when [value] is
     * true, as [isImplicit] says. No comment goes: those of a hidden header print again once it does.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("[a]\n[a.b]\n").getOrElse { error(it) }
     * doc["a"].getOrNull()?.standardTableOrNull?.setImplicit(true)
     * doc.toString() // "[a.b]\n"
     * ```
     */
    public fun setImplicit(value: Boolean) {
        isImplicit = value
    }

    /**
     * [TableLayout.Dotted] while this table stands for the parts of a dotted key, [TableLayout.Header] otherwise. A
     * document's root and an element of an array of tables are never dotted: storing a table as either makes it a
     * header table. A dotted table whose lines, as dotted keys, would nest deeper than [TomlDocument.NESTING_LIMIT]
     * prints under a header instead, so its text reads back.
     */
    override val layout: TableLayout
        get() = if (dotted) TableLayout.Dotted else TableLayout.Header

    /** Whether this table stands for the parts of a dotted key rather than for a header. */
    internal val isDotted: Boolean
        get() = dotted

    /**
     * Makes this table dotted or a header table as a new layout: the lines above its key stay and each value keeps the
     * comment on its line, while its header comment and the comment lines inside it go; dotted keys made a header put
     * the comment above their first line above the header.
     */
    internal fun layOutDotted(value: Boolean) {
        if (value) {
            if ('#' in rawDecor.suffix?.textOrNull.orEmpty()) rawDecor.suffix = null
            dropCommentLines(fromLine = 0)
        } else {
            takeFirstLineCommentsAboveHeader()
            dropCommentLinesWithinRuns()
        }
        dotted = value
    }

    private var dotted: Boolean = false

    /** Whether this table's header was the first header of the file it was read from, with no key/value line above. */
    internal var opensFile: Boolean = false

    /** For a document's root, the TOML version the document writes; see [TomlDocument.version]. */
    internal var version: TomlVersion = TomlVersion.V1_1

    /** For a document's root, the line ending the document's lines end in; see [lineEndingOf]. */
    internal var lineEnding: String = LINE_BREAK

    /** Whether this dotted table prints under a header, because a line of its own would nest too deep. */
    internal var headerForDepth: Boolean = false

    /** Whether this table prints as the parts of dotted keys: it is dotted and not too deep to print so. */
    internal val printsDotted: Boolean
        get() = dotted && !headerForDepth

    /** Whether this table is a document's root, whose prefix prints as the document's head. */
    internal var isDocumentRoot: Boolean = false

    /** Whether this root's head ends in the lines kept from a removed table, after the last table was removed. */
    internal var headEndsInRemovedTable: Boolean = false

    /** Sets [isDotted] as reading or copying a table does, which gives it no new layout. */
    internal fun markDotted(value: Boolean) {
        dotted = value
    }

    // The comment lines above this table's lines from [fromLine] on go; those kept from removed keys stay.
    private fun dropCommentLines(fromLine: Int) {
        for (line in visualValues().drop(fromLine)) line.dropCommentLines()
    }

    // Of this dotted table's lines, those right after another of them lose the comment lines between; one after
    // other content keeps the lines above it, as removing the table would.
    private fun dropCommentLinesWithinRuns() {
        val own = visualValues().mapTo(HashSet()) { it.entry }
        val printed = headerTableOf(this)?.bodyLines() ?: visualValues()
        for ((before, line) in printed.zipWithNext()) {
            if (before is VisualValue && line is VisualValue && before.entry in own && line.entry in own) {
                line.dropCommentLines()
            }
        }
    }

    private fun VisualValue.dropCommentLines() {
        val decor = path.last().rawLeafDecor
        val prefix = decor.prefix?.textOrNull ?: return
        if ('#' in prefix) decor.prefix = KeptText.from(prefix.substringAfterLast('\n'))
    }

    // The comment lines introducing dotted lines introduce the header that replaces them, unless it has its own.
    private fun takeFirstLineCommentsAboveHeader() {
        if ('#' in rawDecor.prefix?.textOrNull.orEmpty()) return
        val key = visualValues().firstOrNull()?.path?.lastOrNull() ?: return
        val prefix = key.rawLeafDecor.prefix?.textOrNull ?: return
        val above = linesAbove(key.rawLeafDecor.prefix)
        if ('#' !in above) return
        // A new header is set apart by a blank line, above the comment that introduces it, unless it comes first.
        val lineBreak = if ("\r\n" in above) "\r\n" else "\n"
        val top = outermostTableOf(this)
        val firstLine = top?.bodyLines()?.firstOrNull()
        val printsFirst = firstLine is VisualValue && firstLine.path.last() === key
        rawDecor.prefix = KeptText.from(if (printsFirst) above else lineBreak + above)
        key.rawLeafDecor.prefix = KeptText.from(prefix.substring(above.length))
    }

    /**
     * Where the table's header prints among the document's tables, which print in ascending order of it.
     *
     * Reading a document numbers its headers from 1 in the order they stand. A table made in code has none and prints
     * right after the table printed before it in its parent: after its parent's own lines and the tables stored under
     * the keys before its own. Setting a position moves the header and the tables under it that have none, such as
     * -1 to print it before every table read. Storing a table keeps a position set this way; a table read from text
     * and stored under another key drops the positions it and the tables under it were read with, so it prints with
     * its new parent, and one stored in place of a table takes that table's position. Sorting a table shares out
     * the positions of the tables under it again.
     *
     * The elements of an array of tables print in array order, sharing out the positions they and the tables under
     * them hold; elements with none at the front print just before the first that has one.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("[b]\n[a]\n").getOrElse { error(it) }
     * doc["a"].getOrNull()?.standardTableOrNull?.position = 0
     * doc.toString() // "[a]\n[b]\n"
     * ```
     */
    public var position: Long? = null
        set(value) {
            field = value
            positionFromText = false
        }

    // Whether [position] is where the table was read, rather than a place a caller gave it.
    internal var positionFromText: Boolean = false
        private set

    internal fun placeAt(
        position: Long?,
        fromText: Boolean,
    ) {
        this.position = position
        positionFromText = fromText
    }

    internal var span: Span? = null
    internal var items: KeyValuePairs = KeyValuePairs()
        set(value) {
            field = value
            value.owner = this
        }

    /** The slot holding this table, when it is stored somewhere; see [Nesting]. */
    internal var holder: Item? = null

    init {
        items.owner = this
    }

    // Lines above the headers of tables removed from this one, printed among the tables by their position.
    internal val keptHeaderLines: MutableList<KeptLines> = mutableListOf()

    // How the header line ended and how its path was spelled, when the table came from text.
    internal var headerLineEnding: String? = null
    internal var headerSpelling: PathSpelling? = null

    internal constructor(items: KeyValuePairs) : this() {
        for (entry in items.entries) entry.item = entry.item.movedTo(Item.Holds.Anything)
        this.items = items
    }

    /**
     * An inline table holding copies of this table's values, formatted anew; the table stays as it is.
     * [Item.setLayout] turns a table in a document into an inline table in place.
     *
     * @return [TomlEditError.TooDeep] when its tables, nested as inline tables, would be deeper than the parser reads.
     */
    public fun toInlineTable(): Result<InlineTable, TomlEditError> = deepCopy().moveToInlineTable().refusedWhenTooDeep()

    /**
     * This table, a copy to use up, as the inline table value [Item.setLayout] makes of it on a line indented by
     * [indent]: across lines keeping its comments when [layout] says so, its header comment after `{`; otherwise on
     * one line, formatted anew, its header comment after `}`.
     */
    internal fun toInlineValue(
        layout: InlineLayout,
        indent: String,
    ): Value {
        val comment = headerComment
        if (layout.multiline) return Value.InlineTable(moveToInlineTableAcrossLines(comment, layout, indent))
        val value = Value.InlineTable(moveToInlineTable())
        if (comment.isNotEmpty()) value.rawDecor.suffix = KeptText.from(comment)
        return value
    }

    /**
     * Moves the entries into a new inline table across lines that keeps their comments, which only TOML 1.1 reads:
     * [headerComment] after `{`, each value's comment after its comma, and the comment lines above a key, or above the
     * header of a table under this one, above it. Keys written dotted stay dotted, and each table and array of tables
     * under this one becomes a value as [layout] lays it out. Its lines are indented by [INDENT] more than [indent],
     * the indentation of the line holding `{`.
     */
    internal fun moveToInlineTableAcrossLines(
        headerComment: String,
        layout: InlineLayout,
        indent: String,
    ): InlineTable {
        inlineTablesUnderHeaders(layout, indent + INDENT)
        val comments = lineComments(headerComment)
        for (line in visualValues()) line.path.last().rawLeafDecor.suffix = null
        val table = moveToBraces()
        table.layOutAcrossLines(layout.lineBreak, indent, layout.version, comments)
        return table
    }

    // Every table and array of tables under a header beneath this one, through dotted keys, becomes a value on a line
    // indented by [indent]; the lines above its header go above its key.
    private fun inlineTablesUnderHeaders(
        layout: InlineLayout,
        indent: String,
    ) {
        for (entry in items.entries) {
            val item = entry.item
            val table = item.standardTableOrNull
            val tables = item.arrayOfTablesOrNull
            when {
                table != null && table.isDotted -> table.inlineTablesUnderHeaders(layout, indent)
                table != null -> item.holdValue(table.deepCopy().toInlineValue(layout, indent))
                tables != null -> {
                    val array = tables.deepCopy().moveToTomlArrayKeepingComments(layout, indent)
                    item.holdValue(Value.Array(array))
                }
            }
        }
    }

    // The comments on this table's lines, for the same lines in braces; the lines kept from removed keys go above the
    // line below them. The lines above a dotted table's first line are above its key, outside it.
    private fun lineComments(headerComment: String): LineComments {
        val comments = LineComments(headerComment)
        var kept = ""
        var first = true
        for (line in bodyLines()) {
            when (line) {
                is KeptLines -> kept += line.text
                is VisualValue -> {
                    val start = line.lineStart ?: line.path.last().rawLeafDecor.prefix
                    val above = if (isDotted && first) "" else linesAbove(start)
                    comments.add(line.value, kept + above, line.value.commentAfter() + line.lineEnd)
                    kept = ""
                    first = false
                }
            }
        }
        comments.endLines = kept
        return comments
    }

    // Moves the entries into an inline table keeping their formatting, dotted tables into dotted inline tables.
    private fun moveToBraces(): InlineTable {
        for (entry in items.entries) {
            val dotted = entry.item.standardTableOrNull ?: continue
            entry.item.holdValue(Value.InlineTable(dotted.moveToBraces().also { it.markDotted(true) }))
        }
        return InlineTable(items.takeAll())
    }

    /** Moves the entries into a new inline table, formatted anew, leaving this table empty. */
    internal fun moveToInlineTable(): InlineTable {
        for (entry in items.entries) entry.item.holdValue(entry.item.copyWithComments().moveToValue())
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
     * The comment and blank lines above this table's header under [key], whether or not the header prints, or "" when
     * they hold no comment: blank lines alone are the header's spacing.
     */
    internal fun headerLinesAbove(key: Key): String =
        (linesAbove(key.rawLeafDecor.prefix) + linesAbove(rawDecor.prefix)).takeIf { '#' in it }.orEmpty()

    /**
     * Whether this dotted table has header comments to print but no line of its own to print them on: it holds only
     * tables under headers of their own. An empty one prints `key = {}`, which carries them.
     */
    internal fun keepsHeaderCommentsAmongTables(key: Key): Boolean = printsDotted &&
        items.size > 0 &&
        (headerLinesAbove(key).isNotEmpty() || headerComment.isNotEmpty()) &&
        visualValues().isEmpty()

    /** The comment on this table's header line, with the spacing before it, or "" when there is none. */
    internal val headerComment: String
        get() = rawDecor.suffix?.textOrNull?.takeIf { '#' in it }.orEmpty()

    /**
     * Whether the table prints a header. An implicit table, or one made by hand with no decor, with no values of its
     * own is hidden while tables under it print their own headers; one left holding nothing prints its header, so its
     * key stays in the text.
     */
    internal fun printsHeader(isArrayOfTables: Boolean): Boolean = isArrayOfTables ||
        !((isImplicit || isMadeByHand) && visualValues().isEmpty() && items.entries.any { it.item.printsOwnHeader })

    // A header read from text always has a prefix, if only an empty one.
    private val isMadeByHand: Boolean
        get() = rawDecor.prefix == null && rawDecor.suffix == null

    private fun appendValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries) {
            path.add(entry.key)
            val table = entry.item.standardTableOrNull
            val value = entry.item.valueOrNull
            val emptyTables = entry.item.arrayOfTablesOrNull?.takeIf { it.isEmpty() }
            when {
                table != null && table.printsDotted -> {
                    val start = values.size
                    table.appendValues(path, values)
                    if (table.items.size == 0) values.add(emptyTableLine(path, entry, table.items.keptLines))
                    // A table made dotted prints no header: its comments go with its first line.
                    val first = values.indexOfFirstValue(start)
                    if (first >= 0) {
                        val line = values[first] as VisualValue
                        val above = table.headerLinesAbove(entry.key)
                        val comment = table.headerComment
                        // An empty table's line is under its own key, whose lines above are already in [above].
                        val own = line.path.last() !== entry.key
                        // A line with a comment of its own takes the header's on a line above it.
                        values[first] = if (line.value.commentAfter().isEmpty() && '#' !in line.lineEnd) {
                            line.withLines(above, comment, own)
                        } else {
                            val ownLine = comment.trimStart().takeIf { it.isNotEmpty() }
                            val lineBreak = table.headerLineEnding ?: "\n"
                            line.withLines(above + ownLine?.let { it + lineBreak }.orEmpty(), "", own)
                        }
                    }
                }
                value is Value.InlineTable && value.value.isDotted -> {
                    val start = values.size
                    value.value.appendDottedValues(path, values, entry)
                    values.takeOutOfBraces(start, entry, value)
                }
                value != null -> values.add(VisualValue(path.toList(), value, entry))
                emptyTables != null -> {
                    // The lines kept from its removed headers print above it; its key's spacing was a header's.
                    val above = linesAbove(entry.key.rawLeafDecor.prefix).takeIf { '#' in it }.orEmpty() +
                        emptyTables.keptHeaderLines.sortedBy { it.order ?: Long.MAX_VALUE }.joinToString("") { it.text }
                    val line = VisualValue(
                        path.toList(),
                        Value.Array(TomlArray()),
                        entry,
                        lineStart = KeptText.from(above),
                        defaultKeySpacing = true,
                    )
                    values.add(line)
                }
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
                value is Value.InlineTable && value.value.isDotted -> {
                    value.value.appendDottedValues(path, values, entry)
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
     * Sorts the entries by key name in plain string order, as `sortBy { (key, _) -> key.name }` does.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("b = 1\n[z]\n[a]\n").getOrElse { error(it) }
     * doc.root.sort()
     * doc.toString() // "b = 1\n[a]\n[z]\n"
     * ```
     */
    public fun sort() {
        sortBy { (key, _) -> key.name }
    }

    /**
     * Sorts the entries in the natural order of what [selector] returns for each pair of key and item, nulls first,
     * moving lines and headers as [sortWith] says.
     *
     * ```kotlin
     * table.sortBy { (key, _) -> key }
     * ```
     */
    public fun <R : Comparable<R>> sortBy(selector: (Pair<Key, Item>) -> R?) {
        sortWith(compareBy(selector))
    }

    /**
     * Sorts the entries with [comparator], given each pair of key and item.
     *
     * The key/value lines under this header move, each with the comment lines right above it. The sub-tables and
     * arrays of tables move among each other the same way, each header with the comment lines right above it and the
     * tables under it, those made by hand included: they share out the [position]s they held. Blank lines, and the
     * comment block heading the file, stay in place. The parts of a dotted key are sorted the same way, and their
     * order replaces the one they were read in. The sort is stable.
     */
    public fun sortWith(comparator: Comparator<Pair<Key, Item>>) {
        forgetOrder()
        sortEntriesWith(comparator)
    }

    private fun sortEntriesWith(comparator: Comparator<Pair<Key, Item>>) {
        // Blank lines, and above the first line the comment block they set apart such as a file's head, stay in place.
        val lineEntries = items.entries.filter { !it.item.printsOwnHeader && it.item.valueOrNull != null }
        val places = lineEntries.mapIndexed { index, entry -> entry.splitLinesAbove(first = index == 0) }
        val own = lineEntries.zip(places).associate { (entry, split) -> entry to split.second }
        val headersBefore = items.entries.filter { it.item.printsOwnHeader }
        items.sortBy { left, right -> comparator.compare(left.key to left.item, right.key to right.item) }
        reorderHeaders(headersBefore, items.entries.filter { it.item.printsOwnHeader })
        val linesNow = items.entries.filter { !it.item.printsOwnHeader && it.item.valueOrNull != null }
        for ((index, entry) in linesNow.withIndex()) {
            val text = places[index].first + own.getValue(entry)
            if (text != entry.key.rawLeafDecor.prefix?.textOrNull) entry.key.rawLeafDecor.prefix = KeptText.from(text)
        }
        for (entry in items.entries) {
            val table = entry.item.standardTableOrNull
            if (table != null && table.isDotted) table.sortEntriesWith(comparator)
        }
    }

    /**
     * Prints the tables under their own headers, [before] in their order before a sort, in the order [after]: they
     * share out the positions they and the tables under them held, and the blank lines above each header, with the
     * comment block heading the file, stay in place while the comment lines right above it go with it.
     */
    private fun reorderHeaders(
        before: List<KeyValue>,
        after: List<KeyValue>,
    ) {
        // A table made by hand prints right after the one before it.
        var previous = Long.MIN_VALUE
        val printed = before
            .map { entry -> (entry.item.headerTable?.position ?: previous).also { previous = it } to entry }
            .sortedBy { it.first }
            .map { it.second }
        val slots = printed
            .flatMap { placedParts(it.item) }
            .mapNotNull { it.placement }
            .sorted()
            .iterator()
        for (entry in after) {
            val parts = placedParts(entry.item)
                .filter { it.placement != null }
                .sortedBy { it.placement }
            for (part in parts) part.placement = slots.next()
        }
        shareSpacing(printed.mapNotNull { it.item.headerTable }, after.mapNotNull { it.item.headerTable })
    }

    /** Gives each of [after] the blank lines above the header that printed at its place among [printed]. */
    private fun shareSpacing(
        printed: List<StandardTable>,
        after: List<StandardTable>,
    ) {
        // A null place is a header's default spacing.
        val places = printed.map { table -> table.rawDecor.prefix?.textOrNull?.splitLinesAbove(table.opensFile) }
        val opensFile = printed.map { it.opensFile }
        val own = printed
            .zip(places)
            .associate { (table, split) -> table to split?.second.orEmpty() }
        for ((index, table) in after.withIndex()) {
            val place = places[index]?.first
            val text = own.getValue(table)
            table.rawDecor.prefix = when {
                place != null -> KeptText.from(place + text)
                text.isEmpty() -> null
                index == 0 -> KeptText.from(text)
                else -> KeptText.from((table.headerLineEnding ?: "\n") + text)
            }
            table.opensFile = opensFile[index]
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
            headEndsInRemovedTable = false
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

    /**
     * Stores a copy of [item] at [key], keeping the key's position and formatting when it is already there.
     *
     * @return [TomlEditError.TooDeep], storing nothing, when [item] would nest deeper than the parser reads.
     */
    public operator fun set(
        key: String,
        item: Item,
    ): EmptyResult<TomlEditError> = put(key, item).map { }

    /** Stores a copy of [value] at [key], as `table[key] = Item(value)` does. */
    public operator fun set(
        key: String,
        value: Value,
    ): EmptyResult<TomlEditError> = set(key, Item(value))

    /** Stores a copy of [table] at [key], as `table[key] = Item(table)` does. */
    public operator fun set(
        key: String,
        table: StandardTable,
    ): EmptyResult<TomlEditError> = set(key, Item(table))

    /** Stores a copy of [arrayOfTables] at [key], as `table[key] = Item(arrayOfTables)` does. */
    public operator fun set(
        key: String,
        arrayOfTables: ArrayOfTables,
    ): EmptyResult<TomlEditError> = set(key, Item(arrayOfTables))

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
     * @return the item [key] held before, which no longer belongs to the table, or null when it held none; or
     *   [TomlEditError.TooDeep], storing nothing, when [item] would nest deeper than the parser reads.
     */
    public fun put(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError> {
        storeError(this, key, item)?.let { return Err(it) }
        items.getEntry(key)?.prepareFor(item)
        return Ok(items.put(Key(key), copyToStore(item)))
    }

    /**
     * Sets [key], with its spelling and decor, to a copy of [item]. A key already there keeps its position but
     * takes the spelling and decor of [key].
     *
     * @return the item the key held before, or null when it held none; or [TomlEditError.TooDeep], as [put] says.
     */
    public fun put(
        key: Key,
        item: Item,
    ): Result<Item?, TomlEditError> {
        storeError(this, key.name, item, key)?.let { return Err(it) }
        items.getEntry(key.name)?.key = key.copy()
        return Ok(items.put(key.copy(), copyToStore(item)))
    }

    // A copy from a document leaves its source's comments.
    private fun copyToStore(item: Item): Item {
        val stored = item.copyFor(ValuePlace.Line)
        placeStored(stored, copiesWithinDocument(item, this), this)
        giveSecondsInToml10(this, stored)
        return stored
    }

    override fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError> = put(key, item)

    /**
     * The item at [key], first storing a copy of what [default] makes when the key holds none. [default] is
     * called only then.
     *
     * @return the stored item, to change in place; or [TomlEditError.TooDeep], storing nothing, when what [default]
     *   makes would nest deeper than the parser reads.
     */
    public fun getOrPut(
        key: String,
        default: () -> Item,
    ): Result<Item, TomlEditError> = getOrPut(Key(key), default)

    /** As [getOrPut], an inserted key keeping the spelling and decor of [key]. */
    public fun getOrPut(
        key: Key,
        default: () -> Item,
    ): Result<Item, TomlEditError> {
        getOrNull(key.name)?.let { return Ok(it) }
        val stored = copyToStore(default())
        storeError(this, key.name, stored, key)?.let { return Err(it) }
        items.put(key.copy(), stored)
        return Ok(stored)
    }

    /**
     * Removes [key] and returns its item, or null when it held none.
     *
     * The comment and blank lines above the key stay where they print, now above whatever follows: the next key,
     * the next table header, or the end of the document. What belongs to the key goes with it: its line, from the
     * key to the end of its value and the comment after it, with every comment inside a multi-line value. For a
     * table or an array of tables that is everything after its first header: its headers, its bodies, and the
     * comment and blank lines above the headers of the tables under it and of its later elements. Upstream drops
     * the lines above the key too. The returned item does not hold the lines that stay, so storing it again prints
     * them once, and a document left with only such lines keeps them at its top, above anything added later.
     */
    public fun remove(key: String): Item? {
        val entry = items.getEntry(key) ?: return null
        keepLinesAbove(listOf(entry))
        items.remove(key)
        keepOnlyLinesAsHead()
        return entry.item
    }

    /** Removes [key] and returns its item, or null when it held none; the lines above it stay, as [remove] says. */
    override fun removeItem(key: String): Item? = remove(key)

    /**
     * Renames the key [old] to [new]. The entry keeps its place, the comment lines above its key, its same-line
     * comment, its spacing and its item, so an [Item] taken from it before stays in the document. [new] is spelled
     * as a new key is: bare when it can be, quoted when it must be. A table renames the headers of the tables under
     * it, `[old.x]` printing as `[new.x]` where it printed, an array of tables every `[[old]]` header, and dotted keys
     * every line they start, `old.a = 1` printing as `new.a = 1`.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("# db\n[old] # main\nurl = 1\n[old.pool]\nsize = 2\n").getOrElse { error(it) }
     * doc.rename("old", "db")
     * doc.toString() // "# db\n[db] # main\nurl = 1\n[db.pool]\nsize = 2\n"
     * ```
     *
     * @return [TomlEditError.NotFound] when there is no [old], or [TomlEditError.KeyTaken] when [new] is another key
     *   already there; either changes nothing. Renaming a key to itself changes nothing.
     */
    override fun rename(
        old: String,
        new: String,
    ): EmptyResult<TomlEditError> = renameIn(this, items, old, new)

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
        keepOnlyLinesAsHead()
        return true
    }

    // A document left with only comment and blank lines holds them as its head, so what is added goes below them.
    private fun keepOnlyLinesAsHead() {
        if (!isDocumentRoot || items.size != 0) return
        val kept = items.keptLines.sortedBy { it.order ?: Long.MAX_VALUE } +
            keptHeaderLines.sortedBy { it.order ?: Long.MAX_VALUE }
        if (kept.isEmpty()) return
        rawDecor.prefix = KeptText.from(rawDecor.prefix?.textOrNull.orEmpty() + kept.joinToString("") { it.text })
        headEndsInRemovedTable = keptHeaderLines.isNotEmpty()
        items.keptLines.clear()
        keptHeaderLines.clear()
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

    /**
     * Stores a copy of every pair of [pairs], in order, as `table[key] = item` does.
     *
     * @return [TomlEditError.TooDeep], storing none of them, when one would nest deeper than the parser reads.
     */
    public fun putAll(pairs: Iterable<Pair<String, Item>>): EmptyResult<TomlEditError> {
        val stored = pairs.toList()
        for ((key, item) in stored) storeError(this, key, item)?.let { return Err(it) }
        for ((key, item) in stored) put(key, item)
        return Ok(Unit)
    }

    /**
     * A deep copy of this table, stored nowhere: exactly what storing this table would store. A copy of a table read
     * from a document leaves its header comment and the comment lines above its header behind and takes the comments
     * inside it, so with `val db = src["db"].getOrElse { error(it) }`, `dst.set("db", db.standardTableOrNull!!.copy())`
     * prints the same as `dst.set("db", db.copy())`. Comments a caller set through [decor] stay. A copy of a table
     * stored nowhere, built in code, returned by `remove` or a document's [TomlDocument.root], keeps all its comments.
     */
    public fun copy(): StandardTable = deepCopy().also { copy ->
        if (isStored(this)) copy.rawDecor.dropReadComments(isHeader = true)
    }

    /** A deep copy that keeps every comment. */
    internal fun deepCopy(): StandardTable {
        val copy = StandardTable(items.deepCopy())
        copy.rawDecor.assign(rawDecor)
        copy.isImplicit = isImplicit
        copy.markDotted(isDotted)
        copy.version = version
        copy.opensFile = opensFile
        copy.headEndsInRemovedTable = headEndsInRemovedTable
        copy.placeAt(position, positionFromText)
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
        dotted = other.dotted
        version = other.version
        placeAt(other.position, other.positionFromText)
        span = other.span
        keptHeaderLines.clear()
        keptHeaderLines += other.keptHeaderLines
        headerLineEnding = other.headerLineEnding
        headerSpelling = other.headerSpelling
    }

    /**
     * The key/value pairs under this table, one per line, without its header or sub-tables, in its document's
     * [TomlDocument.version] or else TOML 1.1.
     */
    override fun toString(): String = buildString {
        encodeTableBody(this, this@StandardTable, documentVersionOf(this@StandardTable))
    }

    internal companion object {
        fun withPosition(position: Long?): StandardTable = StandardTable().also { it.position = position }
    }
}

/** A table holding copies of [pairs], in order; see [toStandardTable]. */
public fun standardTableOf(vararg pairs: Pair<String, Item>): Result<StandardTable, TomlEditError> =
    pairs.asIterable().toStandardTable()

/**
 * A table holding copies of these pairs, in order; a later pair for the same key replaces an earlier one.
 *
 * @return [TomlEditError.TooDeep] when a pair would nest deeper than the parser reads in a document's root table.
 */
public fun Iterable<Pair<String, Item>>.toStandardTable(): Result<StandardTable, TomlEditError> {
    val table = StandardTable()
    return table.putAll(this).map { table }
}

/**
 * Turns the lines from [start] on, spaced for the braces of the dotted inline table [inline], into lines of a table.
 * The lines above [entry]'s key, which holds the table, print above the first; a comment that followed the opening
 * brace prints on a line of its own above it, and one that followed a comma ends the line before. The comments before
 * and after the closing brace end the last line.
 */
private fun MutableList<BodyLine>.takeOutOfBraces(
    start: Int,
    entry: KeyValue,
    inline: Value.InlineTable,
) {
    val lines = (start until size).filter { this[it] is VisualValue }
    if (lines.isEmpty()) return
    var firstAbove = entry.key.rawLeafDecor.prefix?.textOrNull.orEmpty()
    val ends = MutableList(lines.size) { "" }
    // A comment joining a line that already ends in one goes on a line of its own below it.
    fun endLine(position: Int, comment: String, lineBreak: String, ownLine: Boolean = false) {
        if (comment.isEmpty()) return
        val value = (this[lines[position]] as VisualValue).value
        val hasComment = value.commentAfter().isNotEmpty() || '#' in ends[position]
        ends[position] += if (ownLine || hasComment) lineBreak + comment.trimStart() else comment
    }
    // Each key under [entry]'s prints its lines above over the first line under it, nested dotted tables' keys too.
    val seen = HashSet<Key>()
    val aboves = MutableList(lines.size) { "" }
    for ((position, index) in lines.withIndex()) {
        val path = (this[index] as VisualValue).path
        val keys = path.subList(path.indexOfFirst { it === entry.key } + 1, path.size)
        for (key in keys) {
            if (!seen.add(key)) continue
            val prefix = key.rawLeafDecor.prefix?.textOrNull.orEmpty()
            val carried = prefix.commentBeforeBreak()
            when {
                carried.isEmpty() -> {}
                position == 0 -> firstAbove += carried.trimStart() + prefix.lineBreak()
                else -> endLine(position - 1, carried, prefix.lineBreak())
            }
            aboves[position] += linesAboveOnTableLines(KeptText.from(prefix))
        }
    }
    // The comment after the last value of a dotted table inside this one, held before its `}`, ends that line.
    val lastOfNested = HashMap<InlineTable, Int>()
    for ((position, index) in lines.withIndex()) {
        val owner = (this[index] as VisualValue).entry.pairs?.owner as? InlineTable ?: continue
        if (owner !== inline.value && owner.isDotted) lastOfNested[owner] = position
    }
    for ((nested, position) in lastOfNested) {
        val text = nested.rawTrailing.textOrNull.orEmpty()
        endLine(position, text.commentBeforeBreak(), text.lineBreak())
    }
    val last = lines.lastIndex
    val trailing = inline.value.rawTrailing.textOrNull.orEmpty()
    val lineBreak = trailing.lineBreak()
    endLine(last, trailing.commentBeforeBreak(), lineBreak)
    val linesBefore = linesAboveOnTableLines(KeptText.from(trailing)).removeSuffix(lineBreak)
    if (linesBefore.isNotEmpty()) ends[last] += lineBreak + linesBefore
    endLine(last, inline.commentAfter(), lineBreak, ownLine = '\n' in trailing)
    for ((position, index) in lines.withIndex()) {
        val line = this[index] as VisualValue
        val above = (if (position == 0) firstAbove else "") + aboves[position]
        this[index] = line.outOfBraces(above, ends[position])
    }
}

/** The comment that ends the line this text starts on, with its spacing, or "" when it holds none. */
private fun String.commentBeforeBreak(): String {
    if ('\n' !in this) return ""
    return substringBefore('\n').trimEnd('\r').takeIf { '#' in it }.orEmpty()
}

private fun String.lineBreak(): String = if ("\r\n" in this) "\r\n" else "\n"

private fun List<BodyLine>.indexOfFirstValue(from: Int): Int =
    (from until size).firstOrNull { this[it] is VisualValue } ?: -1

/**
 * The text above this entry's line split into what belongs to its place and what belongs to it: the blank lines
 * above it, and when it is the [first] line also a comment block those set apart from it, stay in place; the comment
 * lines right above it and its indentation go with it.
 */
private fun KeyValue.splitLinesAbove(first: Boolean): Pair<String, String> =
    key.rawLeafDecor.prefix?.textOrNull.orEmpty().splitLinesAbove(first)

/** As [KeyValue.splitLinesAbove] says, for the text above a line. */
private fun String.splitLinesAbove(first: Boolean): Pair<String, String> {
    val text = this
    val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
    val fullLines = lineStarts.zipWithNext()
    val blank = fullLines.filter { (start, end) -> text.substring(start, end).isBlank() }
    val placeEnd = if (first) {
        blank.lastOrNull()?.second ?: 0
    } else {
        fullLines.takeWhile { it in blank }.lastOrNull()?.second ?: 0
    }
    return text.substring(0, placeEnd) to text.substring(placeEnd)
}

/**
 * How [Item.setLayout] lays out each inline table it makes: across lines when [multiline], otherwise on one line. The
 * lines it adds end in [lineBreak], and in [version] 1.1 the last entry of a table across lines takes a comma.
 */
internal class InlineLayout(
    val version: TomlVersion,
    val lineBreak: String,
    val multiline: Boolean,
)

/** The comment lines of [lines], each indented by [indent] and ended by [lineBreak]; blank lines go. */
internal fun indentedCommentLines(
    lines: String,
    indent: String,
    lineBreak: String,
): String = lines
    .lines()
    .filter { it.isNotBlank() }
    .joinToString("") { indent + it.trim() + lineBreak }

/** The comment after this value, with the spacing before it, or "" when there is none. */
internal fun Value.commentAfter(): String = rawDecor.suffix?.textOrNull?.takeIf { '#' in it }.orEmpty()

/** Whether this item prints under a header of its own; an array of no tables prints as `key = []` instead. */
internal val Item.printsOwnHeader: Boolean
    get() = standardTableOrNull?.let { !it.printsDotted } ?: (arrayOfTablesOrNull?.isNotEmpty() == true)

/** A value as it prints under a header: the key path that reaches it, and the entry that holds it. */
internal class VisualValue(
    val path: List<Key>,
    val value: Value,
    val entry: KeyValue,
    private val placedAt: Long? = null,
    // For a line printed away from where its spacing was made: the text it starts with, in place of its key's.
    val lineStart: KeptText? = null,
    // Whether the value's spacing was made for braces, so only a comment after it carries over.
    val spacedForBraces: Boolean = false,
    // Comment text ending the line, after the value's own.
    val lineEnd: String = "",
    // Whether the key takes the default spacing before `=`, its own being a header's.
    val defaultKeySpacing: Boolean = false,
) : BodyLine {
    override val order: Long?
        get() = placedAt ?: entry.order

    /** This line printed on a table's lines though spaced for braces, starting with [above] and ending with [end]. */
    fun outOfBraces(
        above: String,
        end: String,
    ): VisualValue {
        val start = KeptText.from(above)
        return VisualValue(path, value, entry, placedAt, start, spacedForBraces = true, lineEnd = end)
    }

    /** This line starting with [spacing] in place of its key's own. */
    fun startingWith(spacing: KeptText): VisualValue =
        VisualValue(path, value, entry, placedAt, spacing, spacedForBraces, lineEnd, defaultKeySpacing)

    /** This line with [above] over its lines above, or in their place when not [ownLinesAbove], and [end] ending it. */
    fun withLines(
        above: String,
        end: String,
        ownLinesAbove: Boolean,
    ): VisualValue {
        val own = if (ownLinesAbove) (lineStart ?: path.last().rawLeafDecor.prefix)?.textOrNull.orEmpty() else ""
        val start = above + own
        // A line under the table's own key, made empty, has that key's header spacing.
        val defaultKey = !ownLinesAbove || defaultKeySpacing
        return VisualValue(
            path,
            value,
            entry,
            placedAt,
            KeptText.from(start),
            spacedForBraces,
            lineEnd + end,
            defaultKey,
        )
    }

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

/** The tables under [start] and the lines kept among their headers: what has a position among a document's tables. */
private fun placedParts(start: Item): List<Any> {
    val parts = mutableListOf<Any>()
    val items = ArrayDeque<Item>()
    items.addLast(start)
    while (items.isNotEmpty()) {
        val item = items.removeLast()
        item.standardTableOrNull?.let { table ->
            parts.add(table)
            parts.addAll(table.keptHeaderLines)
            for (entry in table.items.entries) items.addLast(entry.item)
        }
        item.arrayOfTablesOrNull?.let { tables ->
            parts.addAll(tables.keptHeaderLines)
            tables.values.forEach(items::addLast)
        }
    }
    return parts
}

private var Any.placement: Long?
    get() = when (this) {
        is StandardTable -> position
        is KeptLines -> order
        else -> null
    }
    set(value) {
        when (this) {
            is StandardTable -> placeAt(value, positionFromText)
            is KeptLines -> order = value
        }
    }

/** Gives the tables [copy] holds positions after every table of the document under [root], keeping their order. */
private fun placeAfterAll(
    root: StandardTable,
    copy: Item,
) {
    val last = placedParts(Item(root)).mapNotNull { it.placement }.maxOrNull() ?: 0L
    val parts = placedParts(copy)
        .filter { it.placement != null }
        .sortedBy { it.placement }
    for ((index, part) in parts.withIndex()) part.placement = last + 1 + index
}

/**
 * Places what [stored], a copy just made to store in [receiver], holds among the document's tables: a table prints
 * with the tables of its new parent, and an array of tables copied [within] its document after every table there.
 */
internal fun placeStored(
    stored: Item,
    within: Boolean,
    receiver: Any,
) {
    if (stored.standardTableOrNull != null) {
        placeUnderNewParent(stored)
    } else if (within) {
        outermostTableOf(receiver)?.let { placeAfterAll(it, stored) }
    }
}

/**
 * Makes the tables [copy] holds print with the tables of the table it is stored in, as tables made by hand do: right
 * after the tables before its key there. Blank lines above its header were spacing where it stood, so it takes the
 * default spacing; a header read right below the line before it stays right below what comes before it.
 */
private fun placeUnderNewParent(copy: Item) {
    val header = copy.standardTableOrNull?.takeUnless { it.isDotted }
    // A table that opened its file had nothing above it to be spaced from; one read close below a line stays close.
    val spacing = header?.rawDecor?.prefix?.textOrNull.orEmpty()
    if (header != null && '#' !in spacing && (header.opensFile || spacing.isNotEmpty())) header.rawDecor.prefix = null
    for (part in placedParts(copy)) {
        if (part is StandardTable) {
            part.opensFile = false
            if (!part.positionFromText) continue
        }
        part.placement = null
    }
}

/** Gives [table], stored in place of [old], the place [old] held among the document's tables and its spacing. */
internal fun takePlaceOf(
    table: StandardTable,
    old: StandardTable,
) {
    table.placeAt(old.position, old.positionFromText)
    table.opensFile = old.opensFile
    if (table.rawDecor.prefix == null) table.rawDecor.prefix = old.rawDecor.prefix?.textOrNull?.let(KeptText::from)
}

/** Drops the positions [copy] brought from where it was read, so it prints where it is stored. */
internal fun forgetPlacement(copy: Item) {
    for (part in placedParts(copy)) part.placement = null
}
