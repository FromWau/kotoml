package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map

/**
 * An array value, `[1, 2, 3]`, with its formatting: the decor around it, a trailing comma, and the text
 * after the last element.
 *
 * A value added to the array is stored as a copy, so changing the original afterwards leaves the array as it is.
 *
 * A [Value] has no content equality, so `contains(value)` and `indexOf(value)` find only that same value
 * object: `array.contains(Value.String("a"))` is false even when the array holds `"a"`. Compare what the
 * values hold instead, with `array.any { it.stringOrNull == "a" }` or `array.indexOfFirst { … }`.
 *
 * ```kotlin
 * val array = tomlArrayOf(Value.Integer(1L)).getOrElse { error(it) }
 * array.add("two")
 * array.toString() // "[1, \"two\"]"
 * ```
 */
public class TomlArray() : Iterable<Value> {
    internal var rawTrailing: KeptText = KeptText()

    /** Whether the array ends with a comma after its last element. */
    public var trailingComma: Boolean = false

    internal val rawDecor: RawDecor = RawDecor()
    internal var span: Span? = null

    /** The slot holding this array, when it is stored somewhere; see [Nesting]. */
    internal var holder: Item? = null

    // Slots that refuse anything but a value; held as items so indexing an item hands out the same slots.
    internal val values: MutableList<Item> = mutableListOf()

    /** Drops the formatting of every element and of the end, putting the array on one line. */
    public fun resetFormatting() {
        var index = 0
        for (item in values) {
            val value = item.valueOrNull ?: continue
            val (prefix, suffix) = if (index == 0) DEFAULT_LEADING_VALUE_DECOR else DEFAULT_VALUE_DECOR
            value.decorate(prefix, suffix)
            index++
        }
        // Everything is on one line now, so a trailing comma and trailing text go too.
        trailingComma = false
        trailing = ""
    }

    /** The text after the last element: whitespace, comments, newlines. */
    public var trailing: String
        get() = rawTrailing.textOr("")
        set(value) {
            rawTrailing = KeptText.from(value)
        }

    /** The text around the array, such as the whitespace between it and the `=` before it. */
    public var decor: Decor
        get() = rawDecor.toDecor()
        set(value) {
            rawDecor.set(value)
        }

    internal fun resolveSpans(input: SourceText) {
        span = null
        rawDecor.resolveSpans(input)
        rawTrailing = rawTrailing.resolveSpans(input)
        for (value in values) value.resolveSpans(input)
    }

    override fun iterator(): Iterator<Value> = values.mapNotNull { it.valueOrNull }.iterator()

    /** The number of values. */
    public val size: Int
        get() = values.size

    /** Whether the array has no elements. */
    public fun isEmpty(): Boolean = size == 0

    /** Whether the array has an element. */
    public fun isNotEmpty(): Boolean = !isEmpty()

    /** Removes every element, keeping the lines above each as [removeAt] does. */
    public fun clear() {
        removeSlots(values.indices.toList())
    }

    /** The value at [index], or null when the index is out of bounds. */
    public fun getOrNull(index: Int): Value? = values.getOrNull(index)?.valueOrNull

    /** The value at [index], or [TomlEditError.IndexOutOfBounds] when there is none. */
    public operator fun get(index: Int): Result<Value, TomlEditError> =
        getOrNull(index)?.let { Ok(it) } ?: Err(TomlEditError.IndexOutOfBounds(index, size))

    /**
     * Appends a copy of [value], keeping its formatting. A value without a prefix of its own starts a line of its own
     * when the array's elements do, below the last one and its comment; on one line it takes the spacing the
     * elements already have.
     *
     * A comment in [value]'s decor suffix, such as `Decor(suffix = " # note")`, puts an array on one line on lines of
     * its own, one element per line with a comma after each, and prints after the new element's comma. The lines it
     * adds end as the document's lines do, in `\r\n` in a document read with them.
     *
     * @return [TomlEditError.TooDeep], storing nothing, when [value] would nest deeper than the parser reads.
     */
    public fun add(value: Value): EmptyResult<TomlEditError> {
        tooDeepError(this, null, Item(value))?.let { return Err(it) }
        val comment = value.callerComment()
        val stored = value.copyFor(ValuePlace.Array)
        if (comment.isNotEmpty()) spreadForComment(stored)
        if (stored.decor.prefix == null) {
            leaveLastLineForComma()
            startOwnLine(stored)
        }
        if (stored.decor.prefix == null) takeSpacingOfLast(stored)
        append(stored)
        if (comment.isNotEmpty()) putCommentAfter(stored, comment)
        return Ok(Unit)
    }

    // A comment after an element needs one element per line; [value], going in, starts a line when it has no prefix.
    private fun spreadForComment(value: Value) {
        if (!printsOnOneLine()) return
        val lineBreak = lineEndingOf(this)
        spreadOverLines(lineBreak)
        if (value.decor.prefix == null) value.rawDecor.prefix = KeptText.from(lineBreak + INDENT)
    }

    // A comment after an element ends its line after the comma, so it starts the next element's prefix or the text
    // before `]`, in place of the comment that stood there.
    private fun putCommentAfter(
        value: Value,
        comment: String,
    ) {
        val lineBreak = lineEndingOf(this)
        val printed = printedValues()
        val next = printed.getOrNull(printed.indexOfFirst { it === value } + 1)
        if (next == null) {
            trailingComma = true
            trailing = trailing.withCommentEndingLine(comment, lineBreak, trailing)
            return
        }
        val start = next.rawDecor.prefix?.textOrNull.orEmpty()
        next.rawDecor.prefix = KeptText.from(start.withCommentEndingLine(comment, lineBreak, INDENT))
    }

    // On one line the space before `]` goes with the last element, and the spacing after a comma is the array's own.
    // An element closing a multi-line array on its line has the next one follow on a line of its own.
    private fun takeSpacingOfLast(value: Value) {
        val printed = printedValues()
        val last = printed.lastOrNull() ?: return
        val start = last.rawDecor.prefix?.textOrNull
        if (start != null && '\n' in start) {
            val lineBreak = if ("\r\n" in start) "\r\n" else "\n"
            value.rawDecor.prefix = KeptText.from(lineBreak + start.substringAfterLast('\n'))
            return
        }
        if (printed.size > 1) value.rawDecor.prefix = last.rawDecor.prefix
        val end = last.rawDecor.suffix
        if (!trailingComma && value.rawDecor.suffix == null && '\n' !in end?.textOrNull.orEmpty()) {
            value.rawDecor.suffix = end
            last.rawDecor.suffix = null
        }
    }

    // One element per line, indented, with a comma after each, as a comment after an element needs.
    private fun spreadOverLines(lineBreak: String) {
        for (element in printedValues()) {
            element.rawDecor.prefix = KeptText.from(lineBreak + INDENT)
            element.rawDecor.suffix = KeptText.from("")
        }
        trailingComma = isNotEmpty()
        trailing = lineBreak
    }

    // A last element without a comma after it holds the rest of its line, its comment included; a comma after it
    // has to come before that text, which now belongs to the closing line.
    private fun leaveLastLineForComma() {
        if (trailingComma) return
        val last = printedValues().lastOrNull() ?: return
        val rest = last.rawDecor.suffix?.textOrNull ?: return
        if ('\n' !in rest) return
        last.rawDecor.suffix = null
        trailing = rest + trailing
    }

    /** Appends [value] itself, for a caller that built it and holds no other reference to it. */
    internal fun append(value: Value) {
        values.add(Item.inArray(value, this))
    }

    /**
     * Moves the inline tables into a new array of tables that keeps their comments, leaving this empty, or null,
     * moving nothing, when this is empty or holds anything but inline tables. Each table keeps its comments as
     * [InlineTable.toStandardTable] does, and the comment after its comma as [keepCommentAfterBraces] does. The comment
     * after `[` and the comment lines above an element go above its header; the comment lines before `]` and
     * [closingComment], the one after `]`, end the last table.
     */
    internal fun moveToArrayOfTablesKeepingComments(
        closingComment: String,
        lineBreak: String,
    ): ArrayOfTables? {
        val inlineTables = map { it.inlineTableOrNull ?: return null }
        if (inlineTables.isEmpty()) return null
        // Index 0 is the line of `[`, and each later one the text after an element: its suffix, which holds the
        // comment ending its line when no comma follows, then the prefix of the next element or the trailing text.
        val texts = List(size + 1) { index ->
            val before = if (index == 0) "" else values[index - 1].valueOrNull?.rawDecor?.suffix?.textOrNull.orEmpty()
            val after = if (index == size) rawTrailing else values[index].valueOrNull?.rawDecor?.prefix
            before + after?.textOrNull.orEmpty()
        }
        val lineComments = Array(size + 1) { "" }
        val linesAbove = Array(size + 1) { "" }
        for ((index, text) in texts.withIndex()) {
            val raw = text.takeIf { '\n' in it } ?: continue
            lineComments[index] = raw.substringBefore('\n').removeSuffix("\r").takeIf { '#' in it }.orEmpty()
            val rest = raw.substringAfter('\n')
            linesAbove[index] = indentedCommentLines(rest.substring(0, rest.lastIndexOf('\n') + 1), "", lineBreak)
        }
        linesAbove[0] = indentedCommentLines(lineComments[0], "", lineBreak) + linesAbove[0]
        val closingLines = indentedCommentLines(closingComment, "", lineBreak)
        val tables = ArrayOfTables()
        for ((index, inline) in inlineTables.withIndex()) {
            // An element prints in braces even when dotted, so its comments are read where braces hold them.
            inline.setDotted(false)
            val table = inline.moveToStandardTable()
            table.keepCommentAfterBraces(lineComments[index + 1], lineBreak)
            val above = linesAbove[index]
            if (above.isNotEmpty()) table.rawDecor.prefix = KeptText.from(if (index == 0) above else lineBreak + above)
            val end = if (index == inlineTables.lastIndex) linesAbove[size] + closingLines else ""
            if (end.isNotEmpty()) table.items.keptLines += KeptLines(end, null)
            tables.append(table)
        }
        for (slot in values) slot.arrayOwner = null
        values.clear()
        return tables
    }

    /**
     * Readies [value], inserted before [next], to take [next]'s place. On one line it takes [next]'s spacing, and
     * [next] follows it after a comma. When [next]'s line starts after a comment ending the line before, [value] takes
     * that comment and the indentation, so the comment stays on its line, and [next] goes on a line of its own below.
     */
    private fun takeLineStart(
        value: Value,
        next: Value,
    ) {
        val start = next.rawDecor.prefix?.textOrNull ?: return
        val firstBreak = start.indexOf('\n')
        if (firstBreak < 0) {
            value.rawDecor.prefix = KeptText.from(start)
            next.rawDecor.prefix = null
            return
        }
        if ('#' !in start.substring(0, firstBreak)) {
            // In an array of one element per line, the new element takes a line of its own.
            val inLines = printedValues().firstOrNull()?.decor?.prefix?.contains('\n') == true
            val lineBreak = if ("\r\n" in start) "\r\n" else "\n"
            if (inLines) value.rawDecor.prefix = KeptText.from(lineBreak + start.substringAfterLast('\n'))
            return
        }
        val lineEnd = if (firstBreak > 0 && start[firstBreak - 1] == '\r') firstBreak - 1 else firstBreak
        value.rawDecor.prefix = KeptText.from(start.substring(0, firstBreak + 1) + start.substringAfterLast('\n'))
        next.rawDecor.prefix = KeptText.from(start.substring(lineEnd))
    }

    // In a multi-line array the text after the last element, its comment included, ends at the closing line.
    private fun startOwnLine(value: Value) {
        val lineBreak = trailing.lastIndexOf('\n')
        if (lineBreak < 0) return
        val closingLine = if (lineBreak > 0 && trailing[lineBreak - 1] == '\r') lineBreak - 1 else lineBreak
        // Indented like the last element, or in an array holding only comments, like the last of them.
        val indent = values.lastOrNull()
            ?.valueOrNull
            ?.decor
            ?.prefix
            ?.takeIf { '\n' in it }
            ?.substringAfterLast('\n')
            ?: trailing
                .substring(0, lineBreak)
                .lines()
                .lastOrNull { it.isNotBlank() }
                ?.takeWhile { it == ' ' || it == '\t' }
            ?: ""
        value.decor = value.decor.copy(prefix = trailing.substring(0, lineBreak + 1) + indent)
        trailing = trailing.substring(closingLine)
    }

    /** Appends a string. */
    public fun add(value: String) {
        add(Value.String(value))
    }

    /** Appends an integer. */
    public fun add(value: Long) {
        add(Value.Integer(value))
    }

    /** Appends an integer. */
    public fun add(value: Int) {
        add(Value.Integer(value))
    }

    /** Appends a float. */
    public fun add(value: Double) {
        add(Value.Float(value))
    }

    /** Appends a boolean. */
    public fun add(value: Boolean) {
        add(Value.Boolean(value))
    }

    /** Appends a date-time. */
    public fun add(value: Datetime) {
        add(Value.Datetime(value))
    }

    /**
     * Inserts a copy of [value] at [index], keeping its formatting and shifting later elements right. A comment in
     * [value]'s decor suffix prints after the new element's comma, as [add] says.
     *
     * @return [TomlEditError.IndexOutOfBounds], with nothing inserted, when [index] is negative or greater
     *   than [size], or [TomlEditError.TooDeep] when [value] would nest deeper than the parser reads.
     */
    public fun add(
        index: Int,
        value: Value,
    ): EmptyResult<TomlEditError> {
        if (index < 0 || index > values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        tooDeepError(this, null, Item(value))?.let { return Err(it) }
        if (index == values.size) return add(value)
        val comment = value.callerComment()
        val stored = value.copyFor(ValuePlace.Array)
        if (comment.isNotEmpty()) spreadForComment(stored)
        val next = values.subList(index, values.size).firstNotNullOfOrNull { it.valueOrNull }
        if (stored.rawDecor.prefix == null && next != null) takeLineStart(stored, next)
        values.add(index, Item.inArray(stored, this))
        if (comment.isNotEmpty()) putCommentAfter(stored, comment)
        return Ok(Unit)
    }

    /**
     * Replaces the element at [index] with [value], which takes over the old element's decor; see [replace].
     *
     * In the `array[index] = value` form the result is discarded, so an [index] past the end does nothing; call
     * `array.set(index, value)` to see the [TomlEditError.IndexOutOfBounds].
     */
    public operator fun set(
        index: Int,
        value: Value,
    ): Result<Value, TomlEditError> = replace(index, value)

    /**
     * Replaces the element at [index] with a copy of [value]. A comment in [value]'s decor suffix prints after the
     * element's comma, as [add] says, in place of the comment that stood there.
     *
     * @param keepDecor whether the copy takes over the old element's decor, so it sits where the old one did;
     *   false keeps [value]'s own decor.
     * @return the old element, or [TomlEditError.IndexOutOfBounds], with nothing replaced, when there is no
     *   element at [index], or [TomlEditError.TooDeep] when [value] would nest deeper than the parser reads.
     */
    public fun replace(
        index: Int,
        value: Value,
        keepDecor: Boolean = true,
    ): Result<Value, TomlEditError> {
        val old = getOrNull(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        tooDeepError(this, null, Item(value))?.let { return Err(it) }
        val comment = value.callerComment()
        val stored = value.copyFor(ValuePlace.Array)
        if (comment.isNotEmpty()) {
            spreadForComment(stored)
            if (old === printedValues().last()) leaveLastLineForComma()
        }
        if (keepDecor) stored.rawDecor.assign(old.rawDecor)
        values.set(index, Item.inArray(stored, this)).arrayOwner = null
        if (comment.isNotEmpty()) putCommentAfter(stored, comment)
        return Ok(old)
    }

    /**
     * Removes the element at [index].
     *
     * Only the element's own text goes: the value, its comma and, for an element on a line of its own, the rest of
     * that line with the comment on it. The comment and blank lines above the element stay where they print, now
     * above the next element or before the closing `]`. Upstream drops those lines with the element.
     *
     * @return the removed element, or [TomlEditError.IndexOutOfBounds] when there is no element at [index].
     */
    public fun removeAt(index: Int): Result<Value, TomlEditError> {
        val removed = getOrNull(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        removeSlot(index)
        return Ok(removed)
    }

    /**
     * Keeps only the values [predicate] accepts, in order, visiting each once. A removed value's own text goes and
     * the lines above it stay, as [removeAt] says.
     *
     * @return whether any value was removed.
     */
    public fun retainAll(predicate: (Value) -> Boolean): Boolean = removeSlots(indicesWhere { !predicate(it) })

    /**
     * Removes the values [predicate] accepts, in order, visiting each once. A removed value's own text goes and the
     * lines above it stay, as [removeAt] says.
     *
     * @return whether any value was removed.
     */
    public fun removeAll(predicate: (Value) -> Boolean): Boolean = removeSlots(indicesWhere(predicate))

    private fun indicesWhere(predicate: (Value) -> Boolean): List<Int> =
        values.indices.filter { index -> values[index].valueOrNull?.let(predicate) ?: false }

    private fun removeSlots(indices: List<Int>): Boolean {
        for (index in indices.asReversed()) removeSlot(index)
        return indices.isNotEmpty()
    }

    private fun removeSlot(index: Int) {
        val decor = values[index].valueOrNull?.rawDecor
        if (decor != null) {
            val next = values
                .subList(index + 1, values.size)
                .firstNotNullOfOrNull { it.valueOrNull }
            val replacement = textAfterRemovedElement(
                prefix = decor.prefix?.textOrNull.orEmpty(),
                suffix = decor.suffix?.textOrNull.orEmpty(),
                next = next?.rawDecor?.prefix?.textOrNull ?: trailing,
                nextIsElement = next != null,
            )
            val previous = values.subList(0, index).lastOrNull { it.valueOrNull != null }?.valueOrNull
            val isFirst = previous == null
            val removed = values[index].valueOrNull
            if (replacement == null && next == null && previous != null && removed != null && !trailingComma) {
                passClosingSpace(removed, previous)
            }
            if (replacement != null && next != null) {
                next.rawDecor.prefix = KeptText.from(replacement)
            } else if (replacement == null && next != null && isFirst && '\n' !in next.decor.prefix.orEmpty()) {
                // The next element, on the same line, becomes the first and takes its spacing after `[`.
                next.rawDecor.prefix = decor.prefix
            } else if (replacement != null) {
                trailing = replacement
                // The comma before the removed element ends its line now, ahead of the lines kept.
                if (values.subList(0, index).any { it.valueOrNull != null }) trailingComma = true
            }
        }
        values.removeAt(index).arrayOwner = null
    }

    /** Sorts the values with [comparator]. The sort is stable. */
    public fun sortWith(comparator: Comparator<Value>) {
        sortItemsWith(compareBy(nullsFirst(comparator)) { item: Item -> item.valueOrNull })
    }

    /** Sorts the values in the natural order of what [selector] returns for each. The sort is stable, nulls first. */
    public fun <R : Comparable<R>> sortBy(selector: (Value) -> R?) {
        sortItemsWith(compareBy(nullsFirst()) { item: Item -> item.valueOrNull?.let(selector) })
    }

    private fun printedValues(): List<Value> = values.mapNotNull { it.valueOrNull }

    private fun printsOnOneLine(): Boolean = '\n' !in StringBuilder().also { encodeArray(it, this, "", "") }

    private fun sortItemsWith(comparator: Comparator<Item>) {
        // On one line the spacing belongs to the position, not to the value, so it stays where it was.
        val spacing = if (printsOnOneLine()) printedValues().map { it.rawDecor.prefix to it.rawDecor.suffix } else null
        val formerLast = printedValues().lastOrNull()
        values.sortWith(comparator)
        if (spacing == null) {
            if (formerLast != null && !trailingComma) keepEndInPlace(formerLast)
            return
        }
        for ((value, around) in printedValues().zip(spacing)) {
            value.rawDecor.prefix = around.first
            value.rawDecor.suffix = around.second
        }
    }

    /**
     * Without a trailing comma the line break before `]` ends the last value's suffix. It belongs to the end, so
     * it goes to the value now last; what stood after [formerLast] on its line, such as a comment, follows its comma.
     */
    private fun keepEndInPlace(formerLast: Value) {
        val printed = printedValues()
        val newLast = printed.last()
        if (newLast === formerLast) return
        val suffix = formerLast.rawDecor.suffix?.textOrNull ?: return
        val lineBreak = suffix.indexOf('\n')
        if (lineBreak < 0) return
        val onItsLine = suffix.substring(0, lineBreak)
        formerLast.rawDecor.suffix = KeptText()
        if (onItsLine.isNotEmpty()) {
            val next = printed[printed.indexOfFirst { it === formerLast } + 1]
            next.rawDecor.prependToPrefix(onItsLine, DEFAULT_VALUE_DECOR.first)
        }
        val lastSuffix = newLast.rawDecor.suffix?.textOrNull.orEmpty()
        newLast.rawDecor.suffix = KeptText.from(lastSuffix + suffix.substring(lineBreak))
    }

    /**
     * Appends a copy of every value of [values], keeping their formatting.
     *
     * @return [TomlEditError.TooDeep], appending none of them, when one would nest deeper than the parser reads.
     */
    public fun addAll(values: Iterable<Value>): EmptyResult<TomlEditError> {
        val added = values.toList()
        for (value in added) tooDeepError(this, null, Item(value))?.let { return Err(it) }
        for (value in added) add(value)
        return Ok(Unit)
    }

    /**
     * A deep copy of this array, stored nowhere: exactly what storing this array would store. A copy of an array read
     * from a document leaves the comment after it on its line behind and takes the comments inside it, those of its
     * elements included. Comments a caller set through [decor] stay. A copy of an array stored nowhere, built in code
     * or returned by `remove`, keeps all its comments.
     */
    public fun copy(): TomlArray = deepCopy().also { copy ->
        if (isStored(this)) copy.rawDecor.dropReadComments(isHeader = false)
    }

    /** A deep copy that keeps every comment. */
    internal fun deepCopy(): TomlArray {
        val copy = TomlArray()
        copy.rawTrailing = rawTrailing
        copy.trailingComma = trailingComma
        copy.rawDecor.assign(rawDecor)
        copy.span = span
        for (value in this) copy.values.add(Item.inArray(value.deepCopy(), copy))
        return copy
    }

    /** The array as TOML, without its own decor, in its document's [TomlDocument.version] or else TOML 1.1. */
    override fun toString(): String = buildString {
        encodeArray(this, this@TomlArray, "", "", RawDecor.EMPTY, PrintVersion(documentVersionOf(this@TomlArray)))
    }
}

/** An array of copies of [values], in order, keeping their formatting; see [toTomlArray]. */
public fun tomlArrayOf(vararg values: Value): Result<TomlArray, TomlEditError> = values.asIterable().toTomlArray()

/**
 * An array of copies of these values, in order, keeping their formatting.
 *
 * @return [TomlEditError.TooDeep] when a value would nest deeper than the parser reads in an array on a line of a
 *   document's root table.
 */
public fun Iterable<Value>.toTomlArray(): Result<TomlArray, TomlEditError> {
    val array = TomlArray()
    return array.addAll(this).map { array }
}
