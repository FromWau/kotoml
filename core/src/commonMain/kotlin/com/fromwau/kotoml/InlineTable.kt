package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map

/**
 * An inline table value, `{ a = 1, b.c = 2 }`, with its formatting.
 *
 * Its entries are values. A dotted key such as `b.c` is held as a nested inline table marked dotted, so it
 * prints back as a dotted key rather than as `b = { c = 2 }`.
 *
 * Storing a value stores a copy of it, formatting included, so two keys never share one value and a table never
 * holds itself: after `table["b"] = table.getOrNull("a")!!`, editing `table["a"]` leaves `table["b"]` as it was.
 *
 * ```kotlin
 * val table = inlineTableOf("a" to Value.Integer(1L)).getOrElse { error(it) }
 * table["b"] = "two"
 * table.toString() // "{ a = 1, b = \"two\" }"
 * ```
 *
 * In a document whose [TomlDocument.version] is TOML 1.0, a store that would add what only TOML 1.1 reads, such as a
 * string spelled with a `\e` escape, fails with [TomlEditError.NotInToml10] and stores nothing.
 */
public class InlineTable() : Table, Iterable<Pair<String, Value>> {
    internal var rawTrailing: KeptText = KeptText()

    /** Whether the table ends with a comma after its last entry; [setTrailingComma] changes it. */
    public var trailingComma: Boolean = false
        internal set

    /**
     * Ends the table with a comma after its last entry when [value] is true, and without one otherwise. TOML 1.0 reads
     * no such comma.
     *
     * @return [TomlEditError.NotInToml10], changing nothing, when [value] is true and the table is in a document whose
     *   [TomlDocument.version] is 1.0.
     */
    public fun setTrailingComma(value: Boolean): EmptyResult<TomlEditError> {
        if (value && documentVersionOf(this) == TomlVersion.V1_0) {
            return Err(TomlEditError.NotInToml10(keyPathOf(this), Toml11Feature.InlineTableTrailingComma))
        }
        trailingComma = value
        return Ok(Unit)
    }

    internal var isImplicit: Boolean = false
    internal var rawDecor: RawDecor = RawDecor()
        private set
    internal var span: Span? = null

    /**
     * [TableLayout.Dotted] while this table stands for the parts of a dotted key rather than for `{ ... }`;
     * otherwise [TableLayout.MultilineInline] when it prints across lines, `{`, then each entry on a line of its own,
     * then `}` on a line of its own, and [TableLayout.Inline] when it prints on one line, `{ a = 1, b = 2 }`. A table
     * read from text reads [TableLayout.MultilineInline] when it was written across lines, and one built in code
     * reads [TableLayout.Inline]. Only the text between the braces decides: a one-line table holding an array
     * written across lines reads [TableLayout.Inline].
     */
    override val layout: TableLayout
        get() = when {
            dotted -> TableLayout.Dotted
            spansLines() -> TableLayout.MultilineInline
            else -> TableLayout.Inline
        }

    /** Whether this table stands for the parts of a dotted key rather than for `{ ... }`. */
    internal val isDotted: Boolean
        get() = dotted

    /**
     * Sets [isDotted] as a new layout: each value keeps the comment on its line, and the table's other comments go,
     * after `{`, on lines of their own and after `}`.
     */
    internal fun layOutDotted(value: Boolean) {
        if (value != dotted) dropCommentsForNewLayout()
        dotted = value
    }

    private var dotted: Boolean = false

    /** Sets [isDotted] as reading or copying a table does, which gives it no new layout. */
    internal fun markDotted(value: Boolean) {
        dotted = value
    }

    /** Whether the table prints across lines; see [layout]. */
    internal val isMultiline: Boolean
        get() = !isDotted && spansLines()

    /**
     * Lays this table and every inline table inside it out across lines, those already across lines and dotted ones
     * as they are, ending the new lines as the document's lines end; the last entry takes a comma in TOML 1.1.
     */
    internal fun layOutAcrossLinesWithin() {
        if (!isDotted && !isMultiline) {
            layOutAcrossLines(lineEndingOf(this), lineIndentOf(this), documentVersionOf(this))
        }
        for (table in inlineTablesInside()) table.layOutAcrossLinesWithin()
    }

    /** Lays this table and every inline table inside it out on one line, dropping the comments inside the braces. */
    internal fun layOutOnOneLineWithin() {
        if (isMultiline) layOutOnOneLine()
        for (table in inlineTablesInside()) table.layOutOnOneLineWithin()
    }

    // The inline tables this one holds as values, through dotted keys and arrays, one level down.
    private fun inlineTablesInside(): List<InlineTable> = items.entries.flatMap { inlineTablesIn(it.item.valueOrNull) }

    // Whether a line break stands in the text between the braces outside the values.
    private fun spansLines(): Boolean = '\n' in trailing ||
        bodyLines().any { line ->
            when (line) {
                is KeptLines -> '\n' in line.text
                is VisualValue -> {
                    val start = line.lineStart ?: line.path.last().rawLeafDecor.prefix
                    '\n' in start?.textOrNull.orEmpty() || '\n' in line.value.rawDecor.suffix?.textOrNull.orEmpty()
                }
            }
        }

    /**
     * Lays the table out across lines indented by [INDENT] more than [indent], the indentation of the line holding
     * `{`, ending them in [lineBreak], with [comments] on them; the last entry takes a comma in [version] 1.1, which
     * reads one.
     */
    internal fun layOutAcrossLines(
        lineBreak: String,
        indent: String,
        version: TomlVersion,
        comments: LineComments = LineComments(),
    ) {
        val entryIndent = indent + INDENT
        forgetLineStarts()
        var carried = comments.headerComment
        for (line in visualValues()) {
            val above = indentedCommentLines(comments.above(line.path.last()), entryIndent, lineBreak)
            line.path.last().rawLeafDecor.prefix = KeptText.from(carried + lineBreak + above + entryIndent)
            line.value.rawDecor.suffix = KeptText.from("")
            carried = comments.after(line.path.last())
        }
        val end = indentedCommentLines(comments.endLines, entryIndent, lineBreak)
        trailingComma = version == TomlVersion.V1_1
        rawTrailing = KeptText.from(carried + lineBreak + end + indent)
    }

    /** The comment ending the line of `{` in a table across lines, with the spacing before it, or "" for none. */
    internal fun commentAfterOpeningBrace(): String {
        if (!isMultiline) return ""
        val text = when (val first = bodyLines().firstOrNull()) {
            is KeptLines -> first.text
            is VisualValue -> (first.lineStart ?: first.path.last().rawLeafDecor.prefix)?.textOrNull.orEmpty()
            null -> trailing
        }
        if ('\n' !in text) return ""
        return text.substringBefore('\n').removeSuffix("\r").takeIf { '#' in it }.orEmpty()
    }

    /**
     * Puts [comment] after the comma of [value], one of this table's values, when the line ends there with no comment;
     * whether it did. A table on one line, or a dotted one, holds no comment after a value.
     */
    internal fun keepCommentAfter(
        value: Value,
        comment: String,
    ): Boolean {
        if (isDotted || !isMultiline) return false
        val lines = visualValues()
        val index = lines.indexOfFirst { it.value === value }
        if (index < 0) return false
        val next = lines.getOrNull(index + 1)
        // A dotted key's first line can start with its first key's spacing, which putCommentAfter leaves alone.
        if (next?.lineStart != null) return false
        val after = when (next) {
            null -> value.rawDecor.suffix?.textOrNull?.takeIf { '\n' in it }.orEmpty() + trailing
            else -> next.path.last().rawLeafDecor.prefix?.textOrNull.orEmpty()
        }
        if (!after.endsLineWithoutComment()) return false
        putCommentAfter(value, comment)
        return true
    }

    // On one line the entries take the default spacing, and no comment fits inside.
    private fun layOutOnOneLine() {
        forgetLineStarts()
        for (line in visualValues()) {
            line.path.last().rawLeafDecor.prefix = null
            line.value.rawDecor.suffix = null
        }
        trailingComma = false
        rawTrailing = KeptText()
    }

    // Each line starts with its last key's spacing alone, and the lines kept from removed entries go.
    private fun forgetLineStarts() {
        items.keptLines.clear()
        for (entry in items.entries) {
            val dotted = entry.item.inlineTableOrNull?.takeIf { it.isDotted } ?: continue
            entry.key.rawLeafDecor.prefix = null
            dotted.forgetLineStarts()
        }
    }

    // A new layout keeps each value's comment on its line, held at the start of the next entry's prefix or of the
    // text before `}`, and drops the table's other comments: after `{`, on lines of their own, and after `}`.
    private fun dropCommentsForNewLayout() {
        if ('#' in rawDecor.suffix?.textOrNull.orEmpty()) rawDecor.suffix = null
        for ((index, entry) in items.entries.withIndex()) {
            val decor = entry.key.rawLeafDecor
            val prefix = decor.prefix?.textOrNull ?: continue
            decor.prefix = KeptText.from(prefix.withoutCommentLines(keepFirstLineComment = index > 0))
        }
        rawTrailing = KeptText.from(trailing.withoutCommentLines(keepFirstLineComment = true))
    }

    internal var items: KeyValuePairs = KeyValuePairs()
        set(value) {
            field = value
            value.owner = this
        }

    /** The slot holding this table, when it is stored somewhere; see [Nesting]. */
    internal var holder: Item? = null

    /** The look of the document this was taken out of, read while it stands at the top of a tree; see [documentOf]. */
    internal var takenFrom: DocumentLook? = null

    init {
        items.owner = this
    }

    /** An empty table spaced by [decor], which it shares rather than copies. */
    internal constructor(decor: RawDecor) : this() {
        rawDecor = decor
    }

    internal constructor(items: KeyValuePairs) : this() {
        for (entry in items.entries) entry.item = entry.item.movedTo(Item.Holds.Value)
        this.items = items
    }

    /**
     * A standard table holding copies of this table's entries, formatted anew; this table stays as it is. The
     * comments of a table written across lines are kept: the comment after `{` on its header line, each value's
     * comment after it, and the comment lines inside above the key below them. [Item.setLayout] with
     * [TableLayout.Header] turns an inline table in a document into a table in place.
     */
    public fun toStandardTable(): StandardTable = deepCopy().moveToStandardTable()

    /** Moves the entries into a new standard table, formatted anew but keeping comments, leaving this table empty. */
    internal fun moveToStandardTable(): StandardTable {
        val comments = if (isDotted) null else commentsOnTableLines()
        if (comments != null) dropTextReadIntoComments()
        val table = StandardTable(items.takeAll())
        table.useDefaultSpacing()
        comments?.applyTo(table)
        return table
    }

    /**
     * Moves the entries of this dotted table, whose lines print on a standard table's, into a new dotted standard
     * table printing the same lines: each keeps its comments, and the spacing it had in braces goes.
     */
    internal fun moveToDottedTable(): StandardTable {
        for (line in visualValues()) {
            val keyDecor = line.path.last().rawLeafDecor
            if (keyDecor.prefix?.textOrNull.isNullOrBlank()) keyDecor.prefix = null
            if (line.value.commentAfter().isEmpty()) line.value.rawDecor.suffix = null
        }
        return StandardTable(items.takeAll()).also { it.markDotted(true) }
    }

    /**
     * Whether [Item.setLayout] with [TableLayout.Header] turns something under this dotted table into a table: an
     * inline table not dotted, or a non-empty array of only inline tables.
     */
    internal fun holdsExpandable(): Boolean = items.entries.any { entry ->
        val value = entry.item.valueOrNull
        val inline = value?.inlineTableOrNull
        val array = value?.arrayOrNull
        when {
            inline != null -> !inline.isDotted || inline.holdsExpandable()
            array != null -> array.isNotEmpty() && array.all { it is Value.InlineTable }
            else -> false
        }
    }

    // In braces a value's comment ends its line after the comma, so it starts the text before the next key or `}`.
    // The lines kept from removed entries print after the comma before them, so they start the text after it.
    private fun commentsOnTableLines(): TableLineComments {
        val body = bodyLines()
        val lines = body.filterIsInstance<VisualValue>()
        val texts = lines.flatMap { listOf(it.path.last().rawLeafDecor.prefix, it.value.rawDecor.suffix) } +
            rawTrailing + body.filterIsInstance<KeptLines>().map { KeptText.from(it.text) }
        val lineBreak = if (texts.any { "\r\n" in it?.textOrNull.orEmpty() }) "\r\n" else "\n"
        val comments = TableLineComments(lines, lineBreak)
        var kept = ""
        var index = 0
        for (line in body) {
            when (line) {
                is KeptLines -> kept += line.text
                is VisualValue -> {
                    val prefix = line.path.last().rawLeafDecor.prefix.withInFront(kept)
                    comments.read(prefix, before = index - 1, linesFor = index)
                    comments.read(line.value.rawDecor.suffix, before = index, linesFor = index + 1, sameLine = true)
                    kept = ""
                    index++
                }
            }
        }
        comments.read(rawTrailing.withInFront(kept), before = lines.lastIndex, linesFor = lines.size)
        return comments
    }

    // Read into the comments of the table's lines, the kept lines and the spacing within would print them twice.
    private fun dropTextReadIntoComments() {
        items.keptLines.clear()
        useDefaultSpacing()
        for (entry in items.entries) entry.item.inlineTableOrNull?.takeIf { it.isDotted }?.dropTextReadIntoComments()
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

    /** Appends the lines this dotted table prints under [entry]'s key at [path]. */
    internal fun appendDottedValues(
        path: MutableList<Key>,
        values: MutableList<BodyLine>,
        entry: KeyValue,
    ) {
        appendValues(path, values)
        if (items.size == 0) values.add(emptyTableLine(path, entry, items))
    }

    internal fun appendValues(path: MutableList<Key>, values: MutableList<BodyLine>) {
        for (entry in items.entries) {
            path.add(entry.key)
            val item = entry.item
            val value = item.valueOrNull
            val table = item.standardTableOrNull
            when {
                value is Value.InlineTable && value.value.isDotted -> {
                    val start = values.size
                    value.value.appendDottedValues(path, values, entry)
                    // The dotted key's first line starts where the key stood, with the key's own spacing.
                    val first = values.getOrNull(start) as? VisualValue
                    val spacing = entry.key.rawLeafDecor.prefix
                    if (first != null && spacing != null) values[start] = first.startingWith(spacing)
                }
                value != null -> values.add(VisualValue(path.toList(), value, entry))
                table != null -> table.appendAllValues(path, values)
            }
            path.removeAt(path.size - 1)
        }
        values += items.keptLines
    }

    /** Forgets the order the lines were read in, which a sort or a copy stored elsewhere no longer follows. */
    internal fun forgetOrder() {
        for (kept in items.keptLines) kept.order = null
        for (entry in items.entries) {
            entry.order = null
            val table = entry.item.inlineTableOrNull
            if (table != null && table.isDotted) table.forgetOrder()
        }
    }

    /**
     * Formats what is between the braces as [TomlDocument.format] formats a document: `{ a = 1, b = 2 }` on one line,
     * and across lines each line indented two spaces more than the line holding `{` and a comma after the last entry
     * in TOML 1.1. The text around the table, before `{` and after `}`, belongs to the line holding it: format the
     * [Item] holding it for that.
     *
     * ```kotlin
     * val doc = TomlDocument.parse("t = {a=1,b=2,}\n").getOrElse { error(it) }
     * doc["t"].getOrNull()?.inlineTableOrNull?.format()
     * doc.toString() // "t = { a = 1, b = 2 }\n"
     * ```
     */
    override fun format() {
        formatInPlace(this)
    }

    /**
     * Writes every value in the braces, and in the arrays and inline tables inside them, in its default spelling, as
     * [TomlDocument.respell] does.
     */
    override fun respell() {
        for (entry in items.entries) entry.item.respell()
    }

    /** Drops the formatting of every key and value, for the default `{ a = 1, b = 2 }` layout. */
    internal fun useDefaultSpacing() {
        for (entry in items.entries) {
            val value = entry.item.valueOrNull ?: continue
            entry.key.rawLeafDecor.clear()
            entry.key.rawDottedDecor.clear()
            value.rawDecor.clear()
        }
    }

    /** The text after the last entry. */
    public var trailing: String
        get() = rawTrailing.textOr("")
        set(value) {
            rawTrailing = KeptText.from(value)
        }

    /**
     * Sorts the entries by key name in plain string order, as `sortBy { (key, _) -> key.name }` does.
     */
    public fun sort() {
        sortBy { (key, _) -> key.name }
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
        // On one line the spacing belongs to the position, not to the entry, so it stays where it was.
        val spacing = if (printsOnOneLine()) {
            visualValues().map { it.path.last().rawLeafDecor.prefix to it.value.rawDecor.suffix }
        } else {
            null
        }
        forgetOrder()
        sortEntriesWith(comparator)
        if (spacing == null) return
        for ((line, around) in visualValues().zip(spacing)) {
            line.path.last().rawLeafDecor.prefix = around.first
            line.value.rawDecor.suffix = around.second
        }
    }

    private fun printsOnOneLine(): Boolean = '\n' !in StringBuilder().also { encodeInlineTable(it, this, "", "") }

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
        for (entry in items.entries) {
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
    override fun keyOrNull(name: String): Key? = items.getEntry(name)?.key

    internal fun resolveSpans(input: SourceText) {
        span = null
        rawDecor.resolveSpans(input)
        rawTrailing = rawTrailing.resolveSpans(input)
        for (entry in items.entries) {
            entry.key.resolveSpans(input)
            entry.pathSpelling?.resolveSpans(input)
            entry.item.resolveSpans(input)
        }
    }

    override fun iterator(): Iterator<Pair<String, Value>> = items.entries
        .mapNotNull { entry -> entry.item.valueOrNull?.let { entry.key.name to it } }
        .iterator()

    /** The key/item pairs, in order; each item holds a value. */
    override val entries: List<Pair<String, Item>>
        get() = items.entries.map { it.key.name to it.item }

    /** The keys holding a value, in order, as a copy that later changes to the table leave as it is. */
    override val keys: Set<String>
        get() = entries.mapTo(LinkedHashSet()) { it.first }

    /** The number of key/value pairs. */
    override val size: Int
        get() = items.entries.count { it.item.valueOrNull != null }

    /** Whether the table has no key/value pairs. */
    override fun isEmpty(): Boolean = size == 0

    /** Whether the table has a key/value pair. */
    override fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Removes every entry, each with the comments that belong to it, as [remove] says: comment lines set apart by a
     * blank line stay where they print.
     *
     * @param force removes them even when comments would go with them.
     * @return [TomlEditError.CommentsWouldBeLost], removing none, when [force] is false and comments would leave the
     *   document, counting the comments of all.
     */
    override fun clear(force: Boolean): EmptyResult<TomlEditError> = removeChecked(items.snapshot(), force).map { }

    /** The value at [key], or null when there is none. */
    public fun getOrNull(key: String): Value? = items.get(key)?.valueOrNull

    /** The value stored at [key], or [TomlEditError.NotFound] when there is none. */
    public operator fun get(key: String): Result<Value, TomlEditError> =
        getOrNull(key)?.let { Ok(it) } ?: Err(TomlEditError.NotFound(key))

    /** The slot at [key], or null when there is none. */
    override fun getItem(key: String): Item? = items.get(key)

    /**
     * Sets [key] to a copy of [value], keeping the key's position and formatting when it is already there.
     *
     * @return [TomlEditError.TooDeep], storing nothing, when [value] would nest deeper than the parser reads.
     */
    public operator fun set(
        key: String,
        value: Value,
    ): EmptyResult<TomlEditError> = put(key, value).map { }

    /** Whether there is a value at [key]. */
    override fun containsKey(key: String): Boolean = items.get(key)?.valueOrNull != null

    /** Whether there is a value at [key], for `key in table`. */
    override operator fun contains(key: String): Boolean = containsKey(key)

    /**
     * The value at [key], first storing a copy of what [default] makes when there is none. [default] is called
     * only then.
     *
     * @return the stored value, to change in place; or [TomlEditError.TooDeep], storing nothing, when what [default]
     *   makes would nest deeper than the parser reads.
     */
    public fun getOrPut(
        key: String,
        default: () -> Value,
    ): Result<Value, TomlEditError> = getOrPut(Key(key), default)

    /** As [getOrPut], an inserted key keeping the spelling and decor of [key]. */
    public fun getOrPut(
        key: Key,
        default: () -> Value,
    ): Result<Value, TomlEditError> {
        getOrNull(key.name)?.let { return Ok(it) }
        val made = default()
        val value = made.copyFor(ValuePlace.InlineTable)
        storeError(this, key.name, Item(value), key)?.let { return Err(it) }
        putEntry(key.copy(), value, commentToKeep(made))
        return Ok(value)
    }

    /**
     * Sets [key] to a copy of [value], as `table[key] = value` does: a key already there keeps its position, its
     * spelling and its decor. Pass a [Key] to replace those. A new key is spaced like the entries already there.
     *
     * A comment in [value]'s decor suffix, read with it or given, such as `Decor(suffix = " # note")`, puts a table on
     * one line across lines, as [Item.setLayout] does for [TableLayout.MultilineInline], and prints after the value's
     * comma, in place of the comment that stood there. The lines it adds end as the document's lines do, in `\r\n` in
     * a document read with them. TOML 1.0 has no comments inside an inline table, so in a document whose
     * [TomlDocument.version] is 1.0 the comment is dropped.
     *
     * @return the value [key] held before, or null when it held none; or [TomlEditError.TooDeep], storing nothing,
     *   when [value] would nest deeper than the parser reads.
     */
    public fun put(
        key: String,
        value: Value,
    ): Result<Value?, TomlEditError> = checkedStore(key, value.copyFor(ValuePlace.InlineTable), commentToKeep(value))

    /**
     * Sets [key], with its spelling and decor, to a copy of [value]. A key already there keeps its position but
     * takes the spelling and decor of [key].
     *
     * @return the value the key held before, or null when it held none; or [TomlEditError.TooDeep], as [put] says.
     */
    public fun put(
        key: Key,
        value: Value,
    ): Result<Value?, TomlEditError> {
        storeError(this, key.name, Item(value), key)?.let { return Err(it) }
        items.getEntry(key.name)?.key = key.copy()
        return Ok(putEntry(key.copy(), value.copyFor(ValuePlace.InlineTable), commentToKeep(value)))
    }

    override fun putItem(
        key: String,
        item: Item,
    ): Result<Item?, TomlEditError> {
        if (item.valueOrNull == null) return Err(TomlEditError.NotAValue(item.type))
        val copy = item.copy()
        val above = copy.takeCarriedAbove()
        val value = copy.moveToValue()
        val comment = commentToKeep(value)
        value.fitFor(ValuePlace.InlineTable)
        storeError(this, key, Item(value))?.let { return Err(it) }
        val linesAbove = if (holdsComments()) above else ""
        return Ok(putEntry(Key(key), value, comment, linesAbove)?.let { Item(it) })
    }

    // Stores the value itself, so only for one nothing else holds.
    private fun checkedStore(
        key: String,
        value: Value,
        comment: String = "",
    ): Result<Value?, TomlEditError> {
        storeError(this, key, Item(value))?.let { return Err(it) }
        return Ok(putEntry(Key(key), value, comment))
    }

    private fun putEntry(
        key: Key,
        value: Value,
        comment: String = "",
        linesAbove: String = "",
    ): Value? {
        giveSecondsInToml10(this, Item(value))
        val isNew = !items.containsKey(key.name)
        if ((comment.isNotEmpty() || linesAbove.isNotEmpty()) && !isMultiline) {
            val lineBreak = lineEndingOf(this)
            val indent = lineIndentOf(this)
            layOutAcrossLines(lineBreak, indent, documentVersionOf(this))
            if (isNew && key.rawLeafDecor.prefix == null) {
                key.rawLeafDecor.prefix = KeptText.from(lineBreak + indent + INDENT)
            }
            value.rawDecor.suffix = KeptText.from("")
        }
        if (isNew) {
            takeClosingSpace(value)
            startOwnLine(key, value)
            takeSpacingOfLast(key, value)
        }
        val old = items.put(key, Item.inInlineTable(value), linesAbove)?.valueOrNull
        if (comment.isNotEmpty()) putCommentAfter(value, comment)
        return old
    }

    /**
     * Puts the comments [value], held by [entry], brings in place of those it has there: [linesAbove] directly above it
     * and [comment] after it; see [put].
     */
    internal fun takeBroughtComments(
        value: Value,
        entry: KeyValue,
        linesAbove: String,
        comment: String,
    ) {
        if (!holdsComments() || (comment.isEmpty() && linesAbove.isEmpty())) return
        if (!isMultiline) layOutAcrossLines(lineEndingOf(this), lineIndentOf(this), documentVersionOf(this))
        entry.takeAttachedAbove(linesAbove)
        if (comment.isNotEmpty()) putCommentAfter(value, comment)
    }

    // The comment [value] brings, which a table of key/value lines or one in TOML 1.0 cannot hold after it.
    private fun commentToKeep(value: Value): String = if (holdsComments()) value.broughtComment() else ""

    // A dotted table prints its keys among other lines, and TOML 1.0 has no comments in braces.
    private fun holdsComments(): Boolean = !isDotted && documentVersionOf(this) != TomlVersion.V1_0

    // A comment after a value holds the start of the next entry's line, or of the closing line, after the comma, in
    // place of the comment that stood there.
    private fun putCommentAfter(
        value: Value,
        comment: String,
    ) {
        val lineBreak = lineEndingOf(this)
        val lines = visualValues()
        val next = lines.getOrNull(lines.indexOfFirst { it.value === value } + 1)
        if (next == null) {
            // A last value kept from the text holds the rest of its line, the comment to replace included.
            val rest = value.rawDecor.suffix?.textOrNull?.takeIf { '\n' in it }
            if (rest != null) value.rawDecor.suffix = KeptText.from("")
            val text = rest.orEmpty() + trailing
            rawTrailing = KeptText.from(text.withCommentEndingLine(comment, lineBreak, text))
            return
        }
        val decor = next.path.last().rawLeafDecor
        val start = decor.prefix?.textOrNull.orEmpty()
        decor.prefix = KeptText.from(start.withCommentEndingLine(comment, lineBreak, lineIndentOf(this) + INDENT))
    }

    // A new entry is spaced around `=` like the last one, and on one line after its comma like it too.
    private fun takeSpacingOfLast(
        key: Key,
        value: Value,
    ) {
        if (isDotted) return
        val lines = visualValues()
        val last = lines.lastOrNull() ?: return
        val lastKey = last.path.last().rawLeafDecor
        if (key.rawLeafDecor.suffix == null) key.rawLeafDecor.suffix = lastKey.suffix
        if (value.rawDecor.prefix == null) value.rawDecor.prefix = last.value.rawDecor.prefix
        val start = lastKey.prefix
        if (key.rawLeafDecor.prefix == null && lines.size > 1 && '\n' !in start?.textOrNull.orEmpty()) {
            key.rawLeafDecor.prefix = start
        }
    }

    // Across lines, a new last entry starts a line of its own below the last one and its comment, which a comma has
    // to come before; the text after them then ends at the closing line.
    private fun startOwnLine(
        key: Key,
        value: Value,
    ) {
        if (isDotted || key.rawLeafDecor.prefix != null || printsOnOneLine()) return
        val last = visualValues().lastOrNull() ?: return
        val rest = last.value.rawDecor.suffix?.textOrNull
        if (!trailingComma && rest != null && '\n' in rest) {
            last.value.rawDecor.suffix = null
            rawTrailing = KeptText.from(rest + trailing)
        }
        val text = trailing
        val lineBreak = text.lastIndexOf('\n')
        if (lineBreak < 0) return
        val closingLine = text.lineEndBefore(lineBreak)
        val indent = last.entry.key.rawLeafDecor.prefix?.textOrNull?.takeIf { '\n' in it }?.substringAfterLast('\n')
        key.rawLeafDecor.prefix = KeptText.from(text.substring(0, lineBreak + 1) + indent.orEmpty())
        if (value.rawDecor.suffix == null) value.rawDecor.suffix = KeptText.from("")
        rawTrailing = KeptText.from(text.substring(closingLine))
    }

    // On one line the space before `}` belongs to the position, not to the entry, so a new last entry takes it.
    private fun takeClosingSpace(value: Value) {
        if (value.rawDecor.suffix != null || !printsOnOneLine()) return
        val last = visualValues().lastOrNull()?.value ?: return
        value.rawDecor.suffix = last.rawDecor.suffix
        last.rawDecor.suffix = null
    }

    /**
     * Removes [key] and returns its value, with the comments that belong to it.
     *
     * What belongs to the entry goes with it: the key, its value, its comma, the comment lines right above it with no
     * blank line between, the comment ending its line when it is the last entry on that line, the comment lines
     * between its value and a comma on a later line, and every comment inside its value. Comment lines set apart from
     * it by a blank line stay where they print, and so do those before the closing `}` with no entry below them.
     *
     * @param force removes the key even when comments would go with it.
     * @return the removed value; [TomlEditError.NotFound] when there is no [key]; or
     *   [TomlEditError.CommentsWouldBeLost], changing nothing, when [force] is false and comments would leave the
     *   document with the entry, naming the key and how many comments.
     */
    public fun remove(
        key: String,
        force: Boolean = false,
    ): Result<Value, TomlEditError> {
        val entry = items.getEntry(key) ?: return Err(TomlEditError.NotFound(key))
        val value = checkNotNull(entry.item.valueOrNull)
        if (!force) refusalToRemove(listOf(key))?.let { return Err(it) }
        removeEntries(listOf(entry))
        return Ok(value)
    }

    /** Removes [key] and returns its value as an item, as [remove] does. */
    override fun removeItem(
        key: String,
        force: Boolean,
    ): Result<Item, TomlEditError> = remove(key, force).map { Item(it) }

    /**
     * Renames the key [old] to [new]. The entry keeps its place, the comment lines above it, the comment after its
     * comma, its spacing and its value, so an [Item] taken from it before stays in the table. [new] is spelled as a
     * new key is: bare when it can be, quoted when it must be. A dotted key renames every entry it starts,
     * `{ old.a = 1, old.b = 2 }` printing as `{ new.a = 1, new.b = 2 }`.
     *
     * @return [TomlEditError.NotFound] when there is no [old], or [TomlEditError.KeyTaken] when [new] is another key
     *   already there; either changes nothing. Renaming a key to itself changes nothing.
     */
    override fun rename(
        old: String,
        new: String,
    ): EmptyResult<TomlEditError> = renameIn(this, items, old, new)

    /**
     * Keeps only the pairs [predicate] accepts, visiting each pair of key and value once, in order. Each removed pair
     * takes its comments, as [remove] says, and all of them go or none does.
     *
     * ```kotlin
     * inlineTable.retainAll { (_, value) -> value.stringOrNull != "" }
     * ```
     *
     * @param force removes the pairs even when comments would go with them.
     * @return whether any pair was removed; or [TomlEditError.CommentsWouldBeLost], removing none, when [force] is
     *   false and comments would leave the document, naming the first such key and counting the comments of all.
     */
    public fun retainAll(
        force: Boolean = false,
        predicate: (Pair<String, Value>) -> Boolean,
    ): Result<Boolean, TomlEditError> = removeChecked(entriesWhere { !predicate(it) }, force)

    /**
     * Removes the pairs [predicate] accepts, visiting each pair of key and value once, in order. Each removed pair
     * takes its comments, as [remove] says, and all of them go or none does.
     *
     * @param force removes the pairs even when comments would go with them.
     * @return whether any pair was removed; or [TomlEditError.CommentsWouldBeLost], removing none, when [force] is
     *   false and comments would leave the document, naming the first such key and counting the comments of all.
     */
    public fun removeAll(
        force: Boolean = false,
        predicate: (Pair<String, Value>) -> Boolean,
    ): Result<Boolean, TomlEditError> = removeChecked(entriesWhere(predicate), force)

    private fun removeChecked(
        removed: List<KeyValue>,
        force: Boolean,
    ): Result<Boolean, TomlEditError> {
        if (!force) refusalToRemove(removed.map { it.key.name })?.let { return Err(it) }
        return Ok(removeEntries(removed))
    }

    private fun refusalToRemove(keys: List<String>): TomlEditError? =
        lostByRemoving(this, keys, keyPathTo = { keyPathOf(this) + it }) { copy, names ->
            copy.removeEntries(names.mapNotNull { copy.items.getEntry(it) })
        }

    private fun entriesWhere(predicate: (Pair<String, Value>) -> Boolean): List<KeyValue> =
        items.entries.filter { entry ->
            entry.item.valueOrNull?.let { predicate(entry.key.name to it) } ?: false
        }

    private fun removeEntries(removed: List<KeyValue>): Boolean {
        if (removed.isEmpty()) return false
        keepLinesAbove(removed)
        val names = removed.mapTo(HashSet()) { it.key.name }
        items.retainAll { it.key.name !in names }
        return true
    }

    private fun keepLinesAbove(removed: Collection<KeyValue>) {
        // On a table's lines the text keeps its braces' shape, which printing turns into lines.
        if (!isDotted || printsOnTableLines()) {
            leaveTextAfterRemoved(bodyLines(), RemovedLines(removed))
            return
        }
        val emptied = removed.size == items.size
        if (emptied) spaceEmptyLineLike(visualValues())
        val removedLines = RemovedLines(removed)
        leaveCommentsWithLinesBefore(removedLines)
        val braces = outermostBraces().bodyLines()
        // Lines kept before the closing brace lose the blank lines ending them, as at the end of a document; an
        // emptied table still prints its `{}` line below them.
        fun endsBraces(line: VisualValue): Boolean {
            if (emptied) return false
            val at = braces.indexOfFirst { it is VisualValue && it.entry === line.entry }
            return braces.drop(at + 1).none { it is VisualValue && it !in removedLines }
        }
        keepLinesAbove(
            lines = bodyLines(),
            removed = removedLines,
            isDotted = true,
            linesAbove = { line ->
                val above = linesAboveElement(line.entry.key.rawLeafDecor.prefix)
                val free = freeLinesAboveElement(above)
                if (endsBraces(line)) free.withoutTrailingBlankElementLines() else free
            },
            keyDefault = DEFAULT_INLINE_KEY_DECOR.first,
            keep = { items.keptLines += it },
        )
    }

    /**
     * Gives the comment a [removed] key's prefix starts with to the entry printed before it in the braces when that
     * entry is not under this dotted table: it ends that entry's line, after its comma, so it stays with the entry.
     */
    private fun leaveCommentsWithLinesBefore(removed: RemovedLines) {
        val removedEntries = visualValues().filter { it in removed }.mapTo(HashSet()) { it.entry }
        for ((previous, line) in linesAfterOtherEntries()) {
            if (line.entry !in removedEntries) continue
            val pairs = previous.entry.pairs ?: continue
            val order = previous.order ?: continue
            val decor = line.entry.key.rawLeafDecor
            val prefix = decor.prefix?.textOrNull ?: continue
            val end = prefix.leadingCommentLength()
            if (end == 0) continue
            pairs.keptLines += KeptLines(prefix.substring(0, end), order)
            decor.prefix = KeptText.from(prefix.substring(end))
        }
    }

    /**
     * The lines of this dotted table printed in its braces right after the line of an entry not under it, each paired
     * with that entry's line.
     */
    private fun linesAfterOtherEntries(): List<Pair<VisualValue, VisualValue>> {
        val own = visualValues().mapTo(HashSet()) { it.entry }
        return outermostBraces().bodyLines().zipWithNext().mapNotNull { (previous, line) ->
            val afterOther = previous is VisualValue && previous.entry !in own
            if (afterOther && line is VisualValue && line.entry in own) previous to line else null
        }
    }

    /**
     * A [deepCopy] to store away from the braces this dotted table prints in, each of its lines keeping the comment
     * that ends it. In braces that comment follows the comma, at the start of the text before the next line or `}`, so
     * a line of this table printed after an entry not under it starts with that entry's comment, which stays with it.
     * A copy across lines ends with a line break before its `}`.
     */
    internal fun copyOutOfBraces(): InlineTable {
        val copy = deepCopy()
        if (!isDotted) return copy
        val copies = HashMap<KeyValue, KeyValue>()
        pairEntries(items, copy.items, copies)
        val braces = outermostBraces()
        val lines = braces.bodyLines()
        val own = visualValues().mapTo(HashSet()) { it.entry }
        val ownKept = bodyLines().filterIsInstance<KeptLines>().toSet()
        var ending = ""
        for ((index, line) in lines.withIndex()) {
            if (line !is VisualValue || line.entry !in own) continue
            val decor = copies[line.entry]?.key?.rawLeafDecor ?: continue
            decor.prefix = decor.prefix?.mapText { ending + it.substring(it.leadingCommentLength()) }
            val after = when (val next = lines.getOrNull(index + 1)) {
                is VisualValue -> next.start()
                // Lines kept in this table hold that comment themselves, and the copy holds them.
                is KeptLines -> if (next in ownKept) "" else next.text
                null -> braces.trailing
            }
            ending = after.substring(0, after.leadingCommentLength())
        }
        val firstStart = copy.visualValues().firstOrNull()?.start().orEmpty()
        if ('\n' in firstStart) {
            val closing = braces.trailing.substringAfterLast('\n', missingDelimiterValue = "")
            copy.rawTrailing = KeptText.from(ending + firstStart.lineBreak() + closing)
        }
        return copy
    }

    /** The table whose braces this one prints in: the first holding it that is not a dotted table, or this one. */
    private fun outermostBraces(): InlineTable {
        var table = this
        while (table.isDotted) {
            table = table.holder?.takeIf { it.holdsContainer(table) }?.owner as? InlineTable ?: return table
        }
        return table
    }

    /**
     * Spaces the `{}` this dotted table prints once emptied as its [lines] were: starting as the first started below
     * the lines above it, which stay where they are, and ending as the last ended without its comment, which goes.
     */
    private fun spaceEmptyLineLike(lines: List<VisualValue>) {
        val first = lines.firstOrNull() ?: return
        val key = holder?.entry?.key ?: return
        val firstKey = first.path.last().rawLeafDecor
        // The key's own spacing, when it has one, is where its first line starts already.
        if (key.rawLeafDecor.prefix == null) {
            val start = (first.lineStart ?: firstKey.prefix)?.textOrNull
            key.rawLeafDecor.prefix = start?.let { KeptText.from(it.substring(it.lastIndexOf('\n').coerceAtLeast(0))) }
        }
        key.rawLeafDecor.suffix = firstKey.suffix
        val end = lines.last().value.rawDecor.suffix?.textOrNull
        items.emptyTableDecor.prefix = first.value.rawDecor.prefix
        items.emptyTableDecor.suffix = end?.let { KeptText.from(it.withoutCommentLines(keepFirstLineComment = false)) }
    }

    /** Whether this dotted table's keys print on lines of a standard table, rather than inside braces. */
    internal fun printsOnTableLines(): Boolean {
        var table = this
        while (table.isDotted) {
            when (val owner = table.holder?.takeIf { it.holdsContainer(table) }?.owner) {
                is StandardTable -> return true
                is InlineTable -> table = owner
                else -> return false
            }
        }
        return false
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
                    prefix = line.entry.key.rawLeafDecor.prefix?.textOrNull.orEmpty(),
                    suffix = line.value.rawDecor.suffix?.textOrNull.orEmpty(),
                    next = textBefore(next),
                    nextIsElement = next is VisualValue,
                ) ?: run {
                    val before = lines.subList(0, index).lastOrNull { it is VisualValue && it !in removed }
                    if (next == null && before is VisualValue) passClosingSpace(line.value, before.value)
                    continue
                }
            }
            when (next) {
                is VisualValue -> next.entry.key.rawLeafDecor.prefix = KeptText.from(replacement)
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
        is VisualValue -> line.entry.key.rawLeafDecor.prefix?.textOrNull.orEmpty()
        is KeptLines -> line.text
        null -> trailing
    }

    /**
     * Sets the key of every pair of [pairs] to a copy of its value, in order, as `table[key] = value` does.
     *
     * @return [TomlEditError.TooDeep], storing none of them, when one would nest deeper than the parser reads.
     */
    public fun putAll(pairs: Iterable<Pair<String, Value>>): EmptyResult<TomlEditError> {
        val stored = pairs.toList()
        for ((key, value) in stored) storeError(this, key, Item(value))?.let { return Err(it) }
        for ((key, value) in stored) put(key, value)
        return Ok(Unit)
    }

    /**
     * A deep copy of this table, stored nowhere: exactly what storing this table would store. A copy of a table read
     * from a document takes the comment after it on its line and the comments inside it. Comments a caller set through
     * [decor] stay. A copy of a table stored nowhere, built in code or returned by `remove`, keeps all its comments.
     */
    public fun copy(): InlineTable = copyOutOfBraces().also { copy ->
        copy.takenFrom = documentOf(this)
        if (!isStored(this)) return@also
        copy.rawDecor.keepSameLineComment(sameLineCommentOf(this))
        if (isDotted) copy.keepOnlyAttachedAboveFirstLine()
    }

    /** A deep copy that keeps every comment. */
    internal fun deepCopy(): InlineTable {
        val copy = InlineTable(items.deepCopy())
        copy.rawTrailing = rawTrailing
        copy.trailingComma = trailingComma
        copy.isImplicit = isImplicit
        copy.rawDecor.assign(rawDecor)
        copy.span = span
        copy.takenFrom = takenFrom
        copy.markDotted(isDotted)
        return copy
    }

    /**
     * The table as TOML, without its own decor, in its document's [TomlDocument.version]; see [Value] for one stored
     * nowhere.
     */
    override fun toString(): String = buildString {
        val version = documentVersionOf(this@InlineTable)
        encodeInlineTable(this, this@InlineTable, "", "", RawDecor.EMPTY, version)
    }
}

/** An inline table of copies of the values of [pairs], in order, keeping their formatting; see [toInlineTable]. */
public fun inlineTableOf(vararg pairs: Pair<String, Value>): Result<InlineTable, TomlEditError> =
    pairs.asIterable().toInlineTable()

/**
 * An inline table of copies of the values of these pairs, in order, keeping their formatting; a later pair for the
 * same key replaces an earlier one.
 *
 * @return [TomlEditError.TooDeep] when a value would nest deeper than the parser reads in an inline table on a line
 *   of a document's root table.
 */
public fun Iterable<Pair<String, Value>>.toInlineTable(): Result<InlineTable, TomlEditError> {
    val table = InlineTable()
    return table.putAll(this).map { table }
}

/**
 * Keeps [comment], which ended the line of this table's closing brace, on its header, or as a comment line ending its
 * lines when the header has one already.
 */
internal fun StandardTable.keepCommentAfterBraces(
    comment: String,
    lineBreak: String,
) {
    if (comment.isEmpty()) return
    if (headerComment.isEmpty()) {
        rawDecor.suffix = KeptText.from(comment)
    } else {
        items.keptLines += KeptLines(indentedCommentLines(comment, "", lineBreak), null)
    }
}

// `{ key1 = value1, ... }`
internal val DEFAULT_INLINE_KEY_DECOR: Pair<String, String> = " " to " "

/**
 * The comments for the lines of an inline table across lines: [headerComment] after `{`, for each line, known by the
 * last key of its path, the comment lines above it and the comment after its comma, and [endLines] above `}`.
 */
internal class LineComments(val headerComment: String = "") {
    var endLines: String = ""
    private val keys = mutableListOf<Key>()
    private val aboveEach = mutableListOf<String>()
    private val afterEach = mutableListOf<String>()

    // By key, not value: an empty dotted table's line holds a value made anew each time its lines are read.
    fun add(
        key: Key,
        above: String,
        after: String,
    ) {
        keys += key
        aboveEach += above
        afterEach += after
    }

    fun above(key: Key): String = aboveEach.getOrNull(indexOf(key)).orEmpty()

    fun after(key: Key): String = afterEach.getOrNull(indexOf(key)).orEmpty()

    private fun indexOf(key: Key): Int = keys.indexOfFirst { it === key }
}

/**
 * The comments of an inline table across lines, gathered for the lines of a standard table: [headerComment] for its
 * header, and for each of [lines] the comment after its value and the comment lines above its key. Comment lines
 * after the last value stay at the end of its lines.
 */
private class TableLineComments(
    private val lines: List<VisualValue>,
    private val lineBreak: String,
) {
    private var headerComment = ""
    private val after = Array(lines.size) { "" }
    private val above = Array(lines.size + 1) { "" }

    /**
     * Reads [text] standing after the line [before] (-1 for `{`): the comment ending that line, then the comment lines
     * above the line [linesFor]. Text [sameLine] with a value ends that line only when it holds a line break.
     */
    fun read(
        text: KeptText?,
        before: Int,
        linesFor: Int,
        sameLine: Boolean = false,
    ) {
        val raw = text?.textOrNull ?: return
        if ('\n' !in raw) {
            if (sameLine && '#' in raw && before >= 0) after[before] = raw
            return
        }
        val first = raw.substringBefore('\n').removeSuffix("\r")
        if ('#' in first) {
            if (before < 0) headerComment = first else after[before] = first
        }
        above[linesFor] += indentedCommentLines(linesAboveOnTableLines(text), "", lineBreak)
    }

    fun applyTo(table: StandardTable) {
        if (headerComment.isNotEmpty()) table.rawDecor.suffix = KeptText.from(headerComment)
        for ((index, line) in lines.withIndex()) {
            // A key under a dotted key stays in braces, where the break ending the line before starts its prefix.
            val underDottedKey = line.path.size > 1
            if (above[index].isNotEmpty()) {
                val prefix = if (underDottedKey) lineBreak + above[index] else above[index]
                line.path.last().rawLeafDecor.prefix = KeptText.from(prefix)
            }
            if (after[index].isNotEmpty()) line.value.rawDecor.suffix = KeptText.from(after[index])
        }
        if (above[lines.size].isNotEmpty()) table.items.keptLines += KeptLines(above[lines.size], null)
    }
}

private fun KeptText?.withInFront(text: String): KeptText? =
    if (text.isEmpty()) this else KeptText.from(text + this?.textOrNull.orEmpty())

/** The inline tables [value] is or holds in its arrays, at any depth of arrays. */
private fun inlineTablesIn(value: Value?): List<InlineTable> = when (value) {
    is Value.InlineTable -> listOf(value.value)
    is Value.Array -> value.value.flatMap(::inlineTablesIn)
    else -> emptyList()
}

/** Maps each entry of [source], and of the dotted tables under it, to its own in [copy], a deep copy of [source]. */
private fun pairEntries(
    source: KeyValuePairs,
    copy: KeyValuePairs,
    copies: MutableMap<KeyValue, KeyValue>,
) {
    for ((entry, copied) in source.entries.zip(copy.entries)) {
        copies[entry] = copied
        val dotted = entry.item.inlineTableOrNull?.takeIf { it.isDotted } ?: continue
        pairEntries(dotted.items, copied.item.inlineTableOrNull?.items ?: continue, copies)
    }
}

/** The length of the comment this key prefix in braces starts with, ending the line before, or 0 when it has none. */
private fun String.leadingCommentLength(): Int {
    val firstBreak = indexOf('\n')
    if (firstBreak < 0) return 0
    val lineEnd = lineEndBefore(firstBreak)
    return if ('#' in substring(0, lineEnd)) lineEnd else 0
}
