package com.fromwau.kotoml

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kotoml.parser.TomlSource
import com.fromwau.kotoml.Datetime as TomlDatetime
import com.fromwau.kotoml.InlineTable as TomlInlineTable

/**
 * A value: on the right of a key/value pair, or inside an array or inline table.
 *
 * The scalar kinds wrap a [Formatted], which keeps the value's spelling and decor; [Array] and [InlineTable]
 * wrap the containers. Values are mutable in place through their parts; to replace a value with a
 * different one, assign through the container that holds it.
 */
public sealed interface Value {

    /** A string. */
    public class String(public val formatted: Formatted<kotlin.String>) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** A 64-bit integer. */
    public class Integer(public val formatted: Formatted<Long>) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** A 64-bit float. */
    public class Float(public val formatted: Formatted<Double>) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** A boolean. */
    public class Boolean(public val formatted: Formatted<kotlin.Boolean>) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** An offset date-time, local date-time, local date or local time. */
    public class Datetime(public val formatted: Formatted<TomlDatetime>) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** An array of values. */
    public class Array(public val array: TomlArray) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** An inline table of key/value pairs. */
    public class InlineTable(public val table: TomlInlineTable) : Value {
        /** The value as TOML, with its decor. */
        override fun toString(): kotlin.String = display()
    }

    /** The kind of value. */
    public val type: TomlType
        get() = when (this) {
            is String -> TomlType.String
            is Integer -> TomlType.Integer
            is Float -> TomlType.Float
            is Boolean -> TomlType.Boolean
            is Datetime -> TomlType.Datetime
            is Array -> TomlType.Array
            is InlineTable -> TomlType.InlineTable
        }

    /** The string, if this is one. */
    public fun asStr(): kotlin.String? = (this as? String)?.formatted?.value()

    /** Whether this is a string. */
    public fun isStr(): kotlin.Boolean = asStr() != null

    /** The integer, if this is one. */
    public fun asInteger(): Long? = (this as? Integer)?.formatted?.value()

    /** Whether this is an integer. */
    public fun isInteger(): kotlin.Boolean = asInteger() != null

    /** The float, if this is one. */
    public fun asFloat(): Double? = (this as? Float)?.formatted?.value()

    /** Whether this is a float. */
    public fun isFloat(): kotlin.Boolean = asFloat() != null

    /** The boolean, if this is one. */
    public fun asBool(): kotlin.Boolean? = (this as? Boolean)?.formatted?.value()

    /** Whether this is a boolean. */
    public fun isBool(): kotlin.Boolean = asBool() != null

    /** The date-time, if this is one. */
    public fun asDatetime(): TomlDatetime? = (this as? Datetime)?.formatted?.value()

    /** Whether this is a date-time. */
    public fun isDatetime(): kotlin.Boolean = asDatetime() != null

    /** The array, if this is one. */
    public fun asArray(): TomlArray? = (this as? Array)?.array

    /** The array, if this is one, to change. */
    public fun asArrayMut(): TomlArray? = asArray()

    /** Whether this is an array. */
    public fun isArray(): kotlin.Boolean = asArray() != null

    /** The inline table, if this is one. */
    public fun asInlineTable(): TomlInlineTable? = (this as? InlineTable)?.table

    /** The inline table, if this is one, to change. */
    public fun asInlineTableMut(): TomlInlineTable? = asInlineTable()

    /** Whether this is an inline table. */
    public fun isInlineTable(): kotlin.Boolean = asInlineTable() != null

    /** The text around the value, to change. */
    public fun decorMut(): Decor = when (this) {
        is String -> formatted.decorMut()
        is Integer -> formatted.decorMut()
        is Float -> formatted.decorMut()
        is Boolean -> formatted.decorMut()
        is Datetime -> formatted.decorMut()
        is Array -> array.decorMut()
        is InlineTable -> table.decorMut()
    }

    /** The text around the value. */
    public fun decor(): Decor = decorMut()

    /**
     * Sets the text before and after the value, and returns this value.
     *
     * ```kotlin
     * Value.from(42L).decorated(" ", " ").toString() // " 42 "
     * ```
     */
    public fun decorated(prefix: kotlin.String, suffix: kotlin.String): Value {
        decorate(RawString.from(prefix), RawString.from(suffix))
        return this
    }

    /** Sets the text before and after the value, and returns this value. */
    public fun decorated(prefix: RawString, suffix: RawString): Value {
        decorate(prefix, suffix)
        return this
    }

    /** Where the value lies in the original document; see [RawString.span]. */
    public fun span(): Span? = when (this) {
        is String -> formatted.span()
        is Integer -> formatted.span()
        is Float -> formatted.span()
        is Boolean -> formatted.span()
        is Datetime -> formatted.span()
        is Array -> array.span()
        is InlineTable -> table.span()
    }

    /** A deep copy of this value. */
    public fun clone(): Value = when (this) {
        is String -> String(formatted.clone())
        is Integer -> Integer(formatted.clone())
        is Float -> Float(formatted.clone())
        is Boolean -> Boolean(formatted.clone())
        is Datetime -> Datetime(formatted.clone())
        is Array -> Array(array.clone())
        is InlineTable -> InlineTable(table.clone())
    }

    public companion object {
        /**
         * Parses one TOML value of [version], keeping its spelling but not the whitespace around it.
         *
         * ```kotlin
         * Value.parse("'literal'") // a string that prints as 'literal'
         * ```
         */
        public fun parse(text: kotlin.String, version: TomlVersion = TomlVersion.V1_1): Result<Value, TomlParseError> {
            val source = TomlSource(text)
            val sink = TomlSink(text)
            val value = parseValueItem(source, sink, version)
            sink.error()?.let { return Err(it) }
            // Keep the spelling, not the decor: whitespace around the text is probably not intended.
            value.decorMut().clear()
            value.despan(SourceText(text))
            return Ok(value)
        }

        /** A string in its default spelling. */
        public fun from(value: kotlin.String): Value = String(Formatted.of(value))

        /** An integer in its default spelling. */
        public fun from(value: Long): Value = Integer(Formatted.of(value))

        /** A float in its default spelling; the sign of a NaN is kept and may print as `-nan`. */
        public fun from(value: Double): Value = Float(Formatted.of(value))

        /** A boolean. */
        public fun from(value: kotlin.Boolean): Value = Boolean(Formatted.of(value))

        /** A date-time in its default spelling. */
        public fun from(value: TomlDatetime): Value = Datetime(Formatted.of(value))

        /** A local date. */
        public fun from(value: Date): Value = from(TomlDatetime.from(value))

        /** A local time. */
        public fun from(value: Time): Value = from(TomlDatetime.from(value))

        /** An array. */
        public fun from(value: TomlArray): Value = Array(value)

        /** An inline table. */
        public fun from(value: TomlInlineTable): Value = InlineTable(value)

        /** A copy of [value], upstream's `From<&Value>`. */
        public fun from(value: Value): Value = value.clone()

        /** An array of [values], each in its default formatting. */
        public fun fromIter(values: Iterable<Value>): Value = Array(TomlArray.fromIter(values))

        /** An inline table of [pairs], each in its default formatting. */
        public fun fromPairs(pairs: Iterable<kotlin.Pair<kotlin.String, Value>>): Value =
            InlineTable(TomlInlineTable.fromIter(pairs))
    }
}

internal fun Value.decorate(prefix: RawString, suffix: RawString) {
    val decor = decorMut()
    decor.assign(Decor(prefix, suffix))
}

internal fun Value.despan(input: SourceText) {
    when (this) {
        is Value.String -> formatted.despan(input)
        is Value.Integer -> formatted.despan(input)
        is Value.Float -> formatted.despan(input)
        is Value.Boolean -> formatted.despan(input)
        is Value.Datetime -> formatted.despan(input)
        is Value.Array -> array.despan(input)
        is Value.InlineTable -> table.despan(input)
    }
}

/** The value as TOML, with its decor. */
internal fun Value.display(): String = buildString { encodeValue(this, this@display, "", "") }

// `key1 = value1`
internal val DEFAULT_VALUE_DECOR: kotlin.Pair<String, String> = " " to ""

// `{ key = value }`
internal val DEFAULT_TRAILING_VALUE_DECOR: kotlin.Pair<String, String> = " " to " "

// `[value1, value2]`
internal val DEFAULT_LEADING_VALUE_DECOR: kotlin.Pair<String, String> = "" to ""
