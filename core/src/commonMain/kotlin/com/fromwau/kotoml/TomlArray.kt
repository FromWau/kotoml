package com.fromwau.kotoml

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/**
 * An array value, `[1, 2, 3]`, with its formatting: the decor around it, a trailing comma, and the text
 * after the last element.
 *
 * Upstream calls it `Array`; the Kotlin name keeps it from shadowing `kotlin.Array`.
 */
public class TomlArray() : Iterable<Value> {
    private var trailing: RawString = RawString()
    private var trailingComma: Boolean = false
    private val decor: Decor = Decor()
    internal var span: Span? = null

    // Slots that refuse anything but a value; held as items so indexing an item hands out the same slots.
    internal val values: MutableList<Item> = mutableListOf()

    /** Drops the formatting of every element and of the end, putting the array on one line. */
    public fun fmt() {
        var index = 0
        for (item in values) {
            val value = item.asValue() ?: continue
            val (prefix, suffix) = if (index == 0) DEFAULT_LEADING_VALUE_DECOR else DEFAULT_VALUE_DECOR
            value.decorate(RawString.from(prefix), RawString.from(suffix))
            index++
        }
        // Everything is on one line now, so a trailing comma and trailing text go too.
        setTrailingComma(false)
        setTrailing("")
    }

    /** Sets whether the array ends with a comma after its last element. */
    public fun setTrailingComma(yes: Boolean) {
        trailingComma = yes
    }

    /** Whether the array ends with a comma after its last element. */
    public fun trailingComma(): Boolean = trailingComma

    /** Sets the text after the last element: whitespace, comments, newlines. */
    public fun setTrailing(trailing: RawString) {
        this.trailing = trailing
    }

    /** Sets the text after the last element: whitespace, comments, newlines. */
    public fun setTrailing(trailing: String) {
        this.trailing = RawString.from(trailing)
    }

    /** The text after the last element. */
    public fun trailing(): RawString = trailing

    /** The text around the array, to change. */
    public fun decorMut(): Decor = decor

    /** The text around the array. */
    public fun decor(): Decor = decor

    /** Where the array lies in the original document; see [RawString.span]. */
    public fun span(): Span? = span

    internal fun despan(input: SourceText) {
        span = null
        decor.despan(input)
        trailing = trailing.despan(input)
        for (value in values) value.despan(input)
    }

    /** The values, in order. */
    public fun iter(): Iterator<Value> = values.mapNotNull { it.asValue() }.iterator()

    /** The values, in order, to change in place. */
    public fun iterMut(): Iterator<Value> = iter()

    override fun iterator(): Iterator<Value> = iter()

    /** The number of values. */
    public fun len(): Int = values.size

    /** Whether the array has no elements. */
    public fun isEmpty(): Boolean = len() == 0

    /** Removes every element. */
    public fun clear() {
        values.clear()
    }

    /** The value at [index], or null when the index is out of bounds. */
    public operator fun get(index: Int): Value? = values.getOrNull(index)?.asValue()

    /** The value at [index] to change in place, or null when the index is out of bounds. */
    public fun getMut(index: Int): Value? = get(index)

    /** Appends [value], applying default formatting to it. */
    public fun push(value: Value) {
        values.add(Item.inArray(value))
    }

    /** Appends a string. */
    public fun push(value: String) {
        push(Value.from(value))
    }

    /** Appends an integer. */
    public fun push(value: Long) {
        push(Value.from(value))
    }

    /** Appends a float. */
    public fun push(value: Double) {
        push(Value.from(value))
    }

    /** Appends a boolean. */
    public fun push(value: Boolean) {
        push(Value.from(value))
    }

    /** Appends a date-time. */
    public fun push(value: Datetime) {
        push(Value.from(value))
    }

    /** Appends an already formatted value, keeping its formatting. */
    public fun pushFormatted(value: Value) {
        values.add(Item.inArray(value))
    }

    /**
     * Inserts [value] at [index], shifting later elements right. Fails when [index] is greater than
     * [len]; upstream panics there.
     */
    public fun insert(index: Int, value: Value): EmptyResult<TomlEditError> {
        if (index < 0 || index > values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        values.add(index, Item.inArray(value))
        return Ok(Unit)
    }

    /** Inserts an already formatted value at [index]; fails as [insert] does. */
    public fun insertFormatted(index: Int, value: Value): EmptyResult<TomlEditError> = insert(index, value)

    /**
     * Replaces the element at [index] with [value], keeping the old element's decor, and returns the old
     * element. Fails when [index] is not less than [len]; upstream panics there.
     */
    public fun replace(index: Int, value: Value): Result<Value, TomlEditError> {
        val existing = get(index) ?: return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        value.decorMut().assign(existing.decor())
        return replaceFormatted(index, value)
    }

    /** Replaces the element at [index] with an already formatted value; fails as [replace] does. */
    public fun replaceFormatted(index: Int, value: Value): Result<Value, TomlEditError> {
        if (index < 0 || index >= values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        val old = values[index]
        values[index] = Item.inArray(value)
        return old.asValue()?.let { Ok(it) } ?: Err(TomlEditError.NotAValue(old.type))
    }

    /** Removes and returns the element at [index]. Fails when [index] is out of bounds; upstream panics. */
    public fun remove(index: Int): Result<Value, TomlEditError> {
        if (index < 0 || index >= values.size) return Err(TomlEditError.IndexOutOfBounds(index, values.size))
        val removed = values.removeAt(index)
        return removed.asValue()?.let { Ok(it) } ?: Err(TomlEditError.NotAValue(removed.type))
    }

    /** Keeps only the values [keep] accepts, in order, visiting each once. */
    public fun retain(keep: (Value) -> Boolean) {
        values.retainAll { item -> item.asValue()?.let(keep) ?: false }
    }

    /** Sorts the values with [compare]. The sort is stable. */
    public fun sortBy(compare: (Value, Value) -> Int) {
        values.sortWith { left, right ->
            val leftValue = left.asValue()
            val rightValue = right.asValue()
            when {
                leftValue == null && rightValue == null -> 0
                leftValue == null -> -1
                rightValue == null -> 1
                else -> compare(leftValue, rightValue)
            }
        }
    }

    /** Sorts the values by the key [selector] gives each. The sort is stable. */
    public fun <K : Comparable<K>> sortByKey(selector: (Value) -> K) {
        values.sortWith(compareBy(nullsFirst()) { item: Item -> item.asValue()?.let(selector) })
    }

    /** Appends every value of [values], keeping their formatting. */
    public fun extend(values: Iterable<Value>) {
        for (value in values) pushFormatted(value)
    }

    /** A deep copy of this array. */
    public fun clone(): TomlArray {
        val copy = TomlArray()
        copy.trailing = trailing
        copy.trailingComma = trailingComma
        copy.decor.assign(decor)
        copy.span = span
        for (value in iter()) copy.values.add(Item.inArray(value.clone()))
        return copy
    }

    /** The array as TOML, with its decor. */
    override fun toString(): String = buildString { encodeArray(this, this@TomlArray, "", "") }

    public companion object {
        /** An array of [values], keeping their formatting. */
        public fun fromIter(values: Iterable<Value>): TomlArray {
            val array = TomlArray()
            for (value in values) array.values.add(Item.inArray(value))
            return array
        }
    }
}
