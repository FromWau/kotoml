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
    public operator fun get(index: Int): Value? = values.getOrNull(index)?.valueOrNull

    /**
     * Appends a copy of [value], keeping its formatting. A value without a prefix of its own starts a line of its own
     * when the array's elements do, below the last one and its comment; otherwise it gets the default spacing.
     *
     * @return [TomlEditError.TooDeep], storing nothing, when [value] would nest deeper than the parser reads.
     */
    public fun add(value: Value): EmptyResult<TomlEditError> {
        tooDeepError(this, null, Item(value))?.let { return Err(it) }
        val stored = value.copy()
        if (stored.decor.prefix == null) startOwnLine(stored)
        append(stored)
        return Ok(Unit)
    }

    /** Appends [value] itself, for a caller that built it and holds no other reference to it. */
    internal fun append(value: Value) {
        values.add(Item.inArray(value, this))
    }

    // In a multi-line array the text after the last element, its comment included, ends at the closing line.
    private fun startOwnLine(value: Value) {
        val lineBreak = trailing.lastIndexOf('\n')
        if (lineBreak < 0) return
        val indent = values.lastOrNull()
            ?.valueOrNull
            ?.decor
            ?.prefix
            ?.takeIf { '\n' in it }
            ?.substringAfterLast('\n')
            .orEmpty()
        value.decor = value.decor.copy(prefix = trailing.substring(0, lineBreak) + "\n" + indent)
        trailing = trailing.substring(lineBreak)
    }

    /** Appends a string. */
    public fun add(value: String) {
        add(Value.String(value))
    }

    /** Appends an integer. */
    public fun add(value: Long) {
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
     * Inserts a copy of [value] at [index], keeping its formatting and shifting later elements right.
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
        values.add(index, Item.inArray(value.copy(), this))
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
     * Replaces the element at [index] with a copy of [value].
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
        val old = get(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        tooDeepError(this, null, Item(value))?.let { return Err(it) }
        val stored = value.copy()
        if (keepDecor) stored.rawDecor.assign(old.rawDecor)
        values.set(index, Item.inArray(stored, this)).arrayOwner = null
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
        val removed = get(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
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
            if (replacement != null && next != null) {
                next.rawDecor.prefix = KeptText.from(replacement)
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

    /** A deep copy of this array. */
    public fun copy(): TomlArray {
        val copy = TomlArray()
        copy.rawTrailing = rawTrailing
        copy.trailingComma = trailingComma
        copy.rawDecor.assign(rawDecor)
        copy.span = span
        for (value in this) copy.values.add(Item.inArray(value.copy(), copy))
        return copy
    }

    /** The array as TOML, with its decor. */
    override fun toString(): String = buildString { encodeArray(this, this@TomlArray, "", "") }
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
