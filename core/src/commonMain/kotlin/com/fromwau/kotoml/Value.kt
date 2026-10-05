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
 * The scalar kinds keep the value together with how it was spelled and the text around it, so a parsed value
 * prints back as written. One you make with its constructor prints in the default spelling:
 *
 * ```kotlin
 * val port = Value.Integer(8080L)
 * val name = Value.String("kotoml", Decor(prefix = " ", suffix = " # the library"))
 * ```
 *
 * [Array] and [InlineTable] wrap the containers, which change in place. To replace a value with a different
 * one, assign through the container that holds it.
 *
 * `toString()` prints a value in the [TomlDocument.version] of the document it is stored in, and in TOML 1.1 when it
 * is stored in none.
 */
public sealed interface Value {

    /**
     * A string.
     *
     * @param value the string.
     * @param decor the text around the value; by default the usual spacing for where it stands.
     */
    public class String internal constructor(internal val formatted: Formatted<kotlin.String>) : Value {
        /** A value with no spelling of its own, which prints in the default one, surrounded by [decor]. */
        public constructor(
            value: kotlin.String,
            decor: Decor = Decor(),
        ) : this(Formatted.of(value)) {
            formatted.decor.set(decor)
        }

        // The slot holding this value, whose document's version its spelling can depend on.
        internal var holder: Item? = null

        /** The string. */
        public val value: kotlin.String
            get() = formatted.value

        /** The text the value was written as, such as `0x10` for 16, or null when it has none of its own. */
        public val spelling: kotlin.String?
            get() = formatted.spelling

        /** The text the value prints as: its [spelling], or the default one when it has none. */
        public val displaySpelling: kotlin.String
            get() = formatted.displaySpelling

        /** The value as TOML, without its [decor]. */
        override fun toString(): kotlin.String = display()
    }

    /**
     * A 64-bit integer.
     *
     * @param value the integer.
     * @param decor the text around the value; by default the usual spacing for where it stands.
     */
    public class Integer internal constructor(internal val formatted: Formatted<Long>) : Value {
        /** A value with no spelling of its own, which prints in the default one, surrounded by [decor]. */
        public constructor(
            value: Long,
            decor: Decor = Decor(),
        ) : this(Formatted.of(value)) {
            formatted.decor.set(decor)
        }

        /** The integer. */
        public val value: Long
            get() = formatted.value

        /** The text the value was written as, such as `0x10` for 16, or null when it has none of its own. */
        public val spelling: kotlin.String?
            get() = formatted.spelling

        /** The text the value prints as: its [spelling], or the default one when it has none. */
        public val displaySpelling: kotlin.String
            get() = formatted.displaySpelling

        /** The value as TOML, without its [decor]. */
        override fun toString(): kotlin.String = display()
    }

    /**
     * A 64-bit float. The sign of a NaN is kept and may print as `-nan`.
     *
     * @param value the float.
     * @param decor the text around the value; by default the usual spacing for where it stands.
     */
    public class Float internal constructor(internal val formatted: Formatted<Double>) : Value {
        /** A value with no spelling of its own, which prints in the default one, surrounded by [decor]. */
        public constructor(
            value: Double,
            decor: Decor = Decor(),
        ) : this(Formatted.of(value)) {
            formatted.decor.set(decor)
        }

        /** The float. */
        public val value: Double
            get() = formatted.value

        /** The text the value was written as, such as `0x10` for 16, or null when it has none of its own. */
        public val spelling: kotlin.String?
            get() = formatted.spelling

        /** The text the value prints as: its [spelling], or the default one when it has none. */
        public val displaySpelling: kotlin.String
            get() = formatted.displaySpelling

        /** The value as TOML, without its [decor]. */
        override fun toString(): kotlin.String = display()
    }

    /**
     * A boolean.
     *
     * @param value the boolean.
     * @param decor the text around the value; by default the usual spacing for where it stands.
     */
    public class Boolean internal constructor(internal val formatted: Formatted<kotlin.Boolean>) : Value {
        /** A value with no spelling of its own, which prints in the default one, surrounded by [decor]. */
        public constructor(
            value: kotlin.Boolean,
            decor: Decor = Decor(),
        ) : this(Formatted.of(value)) {
            formatted.decor.set(decor)
        }

        /** The boolean. */
        public val value: kotlin.Boolean
            get() = formatted.value

        /** The text the value was written as, such as `0x10` for 16, or null when it has none of its own. */
        public val spelling: kotlin.String?
            get() = formatted.spelling

        /** The text the value prints as: its [spelling], or the default one when it has none. */
        public val displaySpelling: kotlin.String
            get() = formatted.displaySpelling

        /** The value as TOML, without its [decor]. */
        override fun toString(): kotlin.String = display()
    }

    /**
     * An offset date-time, local date-time, local date or local time.
     *
     * @param value the date-time.
     * @param decor the text around the value; by default the usual spacing for where it stands.
     */
    public class Datetime internal constructor(internal val formatted: Formatted<TomlDatetime>) : Value {
        /** A value with no spelling of its own, which prints in the default one, surrounded by [decor]. */
        public constructor(
            value: TomlDatetime,
            decor: Decor = Decor(),
        ) : this(Formatted.of(value)) {
            formatted.decor.set(decor)
        }

        // The slot holding this value, whose document's version its spelling can depend on.
        internal var holder: Item? = null

        /** The date-time. */
        public val value: TomlDatetime
            get() = formatted.value

        /** The text the value was written as, such as `0x10` for 16, or null when it has none of its own. */
        public val spelling: kotlin.String?
            get() = formatted.spelling

        /** The text the value prints as: its [spelling], or the default one when it has none. */
        public val displaySpelling: kotlin.String
            get() = formatted.displaySpelling

        /** The value as TOML, without its [decor]. */
        override fun toString(): kotlin.String = display()
    }
    /**
     * An array of values.
     *
     * @param array the array, which this value wraps rather than copies.
     * @param decor the text around the array; by default the decor [array] already has.
     */
    public class Array internal constructor(public val array: TomlArray) : Value {
        /** Wraps [array], setting its decor to [decor]. */
        public constructor(
            array: TomlArray,
            decor: Decor = array.decor,
        ) : this(array) {
            array.decor = decor
        }

        /** The value as TOML, without its [decor]. */
        override fun toString(): kotlin.String = display()
    }

    /**
     * An inline table of key/value pairs.
     *
     * @param table the table, which this value wraps rather than copies.
     * @param decor the text around the table; by default the decor [table] already has.
     */
    public class InlineTable internal constructor(public val table: TomlInlineTable) : Value {
        /** Wraps [table], setting its decor to [decor]. */
        public constructor(
            table: TomlInlineTable,
            decor: Decor = table.decor,
        ) : this(table) {
            table.decor = decor
        }

        /** The value as TOML, without its [decor]. */
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

    /** The string, or null when this is not one. */
    public val stringOrNull: kotlin.String?
        get() = (this as? String)?.value

    /** The integer, or null when this is not one. */
    public val longOrNull: Long?
        get() = (this as? Integer)?.value

    /** The float, or null when this is not one. */
    public val doubleOrNull: Double?
        get() = (this as? Float)?.value

    /** The boolean, or null when this is not one. */
    public val booleanOrNull: kotlin.Boolean?
        get() = (this as? Boolean)?.value

    /** The date-time, or null when this is not one. */
    public val datetimeOrNull: TomlDatetime?
        get() = (this as? Datetime)?.value

    /** The array, or null when this is not one. Changing the array changes this value. */
    public val arrayOrNull: TomlArray?
        get() = (this as? Array)?.array

    /** The inline table, or null when this is not one. Changing the table changes this value. */
    public val inlineTableOrNull: TomlInlineTable?
        get() = (this as? InlineTable)?.table

    /**
     * The text around the value. Setting it changes how this value prints where it is stored:
     *
     * ```kotlin
     * val value = Value.Integer(42L)
     * value.decor = Decor(" ", " # the answer")
     * TomlDocument().apply { set("a", value) }.toString() // "a = 42 # the answer\n"
     * ```
     *
     * [toString] leaves the decor out: it is the value's text alone.
     */
    public var decor: Decor
        get() = rawDecor.toDecor()
        set(value) {
            rawDecor.set(value)
        }

    /**
     * Drops the value's own spelling so it prints in the default one. An array or inline table instead drops
     * the formatting of its elements, as [TomlArray.resetFormatting] and [TomlInlineTable.resetFormatting] do.
     */
    public fun resetFormatting() {
        when (this) {
            is String -> formatted.written = null
            is Integer -> formatted.written = null
            is Float -> formatted.written = null
            is Boolean -> formatted.written = null
            is Datetime -> formatted.written = null
            is Array -> array.resetFormatting()
            is InlineTable -> table.resetFormatting()
        }
    }

    /** A deep copy of this value, with its spelling and decor. */
    public fun copy(): Value = when (this) {
        is String -> String(formatted.copy())
        is Integer -> Integer(formatted.copy())
        is Float -> Float(formatted.copy())
        is Boolean -> Boolean(formatted.copy())
        is Datetime -> Datetime(formatted.copy())
        is Array -> Array(array.copy())
        is InlineTable -> InlineTable(table.copy())
    }

    public companion object {
        /**
         * Parses one TOML value of [version], keeping its spelling. Whitespace and line breaks around it are skipped,
         * so `Value.parse("  1\n")` reads as `1`.
         *
         * ```kotlin
         * Value.parse("'literal'") // a string that prints as 'literal'
         * ```
         */
        public fun parse(
            text: kotlin.String,
            version: TomlVersion = TomlVersion.V1_1,
        ): Result<Value, TomlParseError> {
            loneSurrogateError(text)?.let { return Err(it) }
            val source = TomlSource(text)
            val sink = TomlSink(text)
            val parsed = parseValueItem(source, sink, version)
            sink.error?.let { return Err(it) }
            val value = checkNotNull(parsed) { "a value parsed without an error must exist" }
            // Keep the spelling, not the decor: whitespace around the text is probably not intended.
            value.rawDecor.clear()
            value.resolveSpans(SourceText(text))
            return Ok(value)
        }
    }
}

internal val Value.rawDecor: RawDecor
    get() = when (this) {
        is Value.String -> formatted.decor
        is Value.Integer -> formatted.decor
        is Value.Float -> formatted.decor
        is Value.Boolean -> formatted.decor
        is Value.Datetime -> formatted.decor
        is Value.Array -> array.rawDecor
        is Value.InlineTable -> table.rawDecor
    }

internal val Value.span: Span?
    get() = when (this) {
        is Value.String -> formatted.span
        is Value.Integer -> formatted.span
        is Value.Float -> formatted.span
        is Value.Boolean -> formatted.span
        is Value.Datetime -> formatted.span
        is Value.Array -> array.span
        is Value.InlineTable -> table.span
    }

internal fun Value.decorate(prefix: String, suffix: String) {
    val decor = rawDecor
    decor.prefix = KeptText.from(prefix)
    decor.suffix = KeptText.from(suffix)
}

internal fun Value.resolveSpans(input: SourceText) {
    when (this) {
        is Value.String -> formatted.resolveSpans(input)
        is Value.Integer -> formatted.resolveSpans(input)
        is Value.Float -> formatted.resolveSpans(input)
        is Value.Boolean -> formatted.resolveSpans(input)
        is Value.Datetime -> formatted.resolveSpans(input)
        is Value.Array -> array.resolveSpans(input)
        is Value.InlineTable -> table.resolveSpans(input)
    }
}

/** The value as TOML, without its decor. */
internal fun Value.display(): String {
    val version = when (this) {
        is Value.Array -> documentVersionOf(array)
        is Value.InlineTable -> documentVersionOf(table)
        else -> documentVersionOf(this)
    }
    return buildString { encodeValue(this, this@display, "", "", RawDecor.EMPTY, PrintVersion(version)) }
}

// `key1 = value1`
internal val DEFAULT_VALUE_DECOR: kotlin.Pair<String, String> = " " to ""

// `{ key = value }`
internal val DEFAULT_TRAILING_VALUE_DECOR: kotlin.Pair<String, String> = " " to " "

// `[value1, value2]`
internal val DEFAULT_LEADING_VALUE_DECOR: kotlin.Pair<String, String> = "" to ""

/** A copy of this value whose decor fits [place]; see [RawDecor.fitFor]. */
internal fun Value.copyFor(place: ValuePlace): Value = copy().fitFor(place)

/** This value, its decor fitted to [place]. */
internal fun Value.fitFor(place: ValuePlace): Value = also { rawDecor.fitFor(place) }

/** The comment a caller gave this value to print after it, with the spacing before it; "" when there is none. */
internal fun Value.callerComment(): String {
    if (rawDecor.suffix?.isRead != false) return ""
    return commentAfter().substringBefore('\n').trimEnd('\r')
}

// An array or inline table spread over lines: one entry per line, indented like a table across lines.
internal const val LINE_BREAK: String = "\n"
internal const val INDENT: String = "  "
