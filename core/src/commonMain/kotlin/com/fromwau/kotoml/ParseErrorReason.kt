package com.fromwau.kotoml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What is wrong where a [TomlParseError] points. */
@Serializable
public sealed interface ParseErrorReason {
    /** A number, boolean, date-time or bare word that is not a valid value of [kind]. */
    @Serializable
    @SerialName("InvalidScalar")
    public data class InvalidScalar(val kind: ScalarKind) : ParseErrorReason

    /** A float whose [part] has no digits or misplaced underscores. */
    @Serializable
    @SerialName("InvalidFloatPart")
    public data class InvalidFloatPart(val part: FloatPart) : ParseErrorReason

    /** A quoted string in the form [encoding] that is not valid. */
    @Serializable
    @SerialName("InvalidString")
    public data class InvalidString(val encoding: Encoding) : ParseErrorReason

    /** A key written as a multi-line string in the form [encoding], which keys cannot be. */
    @Serializable
    @SerialName("MultilineKey")
    public data class MultilineKey(val encoding: Encoding) : ParseErrorReason

    /** A key that is not a bare, basic or literal key. */
    @Serializable
    @SerialName("InvalidKey")
    public data object InvalidKey : ParseErrorReason

    /** A table header whose key is not valid. */
    @Serializable
    @SerialName("InvalidTable")
    public data object InvalidTable : ParseErrorReason

    /** A value that is not a string, number, boolean, date-time, array or inline table. */
    @Serializable
    @SerialName("InvalidValue")
    public data object InvalidValue : ParseErrorReason

    /** A bare key holding a character other than letters, digits, `-` and `_`. */
    @Serializable
    @SerialName("InvalidUnquotedKey")
    public data object InvalidUnquotedKey : ParseErrorReason

    /** A line that starts like a key/value pair but is not one. */
    @Serializable
    @SerialName("InvalidKeyValuePair")
    public data object InvalidKeyValuePair : ParseErrorReason

    /** Something inside `{ }` that is not a key/value pair. */
    @Serializable
    @SerialName("InvalidInlineTableElement")
    public data object InvalidInlineTableElement : ParseErrorReason

    /** A character a comment may not hold, such as a control character. */
    @Serializable
    @SerialName("InvalidCommentCharacter")
    public data object InvalidCommentCharacter : ParseErrorReason

    /** A bare word where a value belongs; strings need quotes. */
    @Serializable
    @SerialName("UnquotedString")
    public data object UnquotedString : ParseErrorReason

    /** An empty bare key, as in `a..b`; an empty key must be quoted, `""`. */
    @Serializable
    @SerialName("EmptyUnquotedKey")
    public data object EmptyUnquotedKey : ParseErrorReason

    /** A backslash in a basic string not followed by a valid escape. */
    @Serializable
    @SerialName("MissingEscapedValue")
    public data object MissingEscapedValue : ParseErrorReason

    /** `\u` or `\U` followed by fewer hexadecimal digits than it needs. */
    @Serializable
    @SerialName("TooFewUnicodeDigits")
    public data object TooFewUnicodeDigits : ParseErrorReason

    /** A carriage return not followed by a line feed. */
    @Serializable
    @SerialName("LoneCarriageReturn")
    public data object LoneCarriageReturn : ParseErrorReason

    /** A decimal number with a leading zero, such as `01`. */
    @Serializable
    @SerialName("UnexpectedLeadingZero")
    public data object UnexpectedLeadingZero : ParseErrorReason

    /** A sign where a number cannot have one. */
    @Serializable
    @SerialName("UnexpectedSign")
    public data object UnexpectedSign : ParseErrorReason

    /** A second sign, as in `+-1`. */
    @Serializable
    @SerialName("RedundantSign")
    public data object RedundantSign : ParseErrorReason

    /** A second radix prefix, as in `0x0x1`. */
    @Serializable
    @SerialName("RedundantRadixPrefix")
    public data object RedundantRadixPrefix : ParseErrorReason

    /** A radix prefix written in upper case, as in `0X1`. */
    @Serializable
    @SerialName("UppercaseRadix")
    public data object UppercaseRadix : ParseErrorReason

    /** A sign on a hexadecimal, octal or binary integer. */
    @Serializable
    @SerialName("SignedRadixInteger")
    public data object SignedRadixInteger : ParseErrorReason

    /** An `_` that is not between two digits. */
    @Serializable
    @SerialName("MisplacedUnderscore")
    public data object MisplacedUnderscore : ParseErrorReason

    /** Two key/value pairs on one line, with no `=` between the key and its value. */
    @Serializable
    @SerialName("MissingAssignment")
    public data object MissingAssignment : ParseErrorReason

    /** A second `=` in one key/value pair. */
    @Serializable
    @SerialName("ExtraAssignment")
    public data object ExtraAssignment : ParseErrorReason

    /** `=` where none belongs. */
    @Serializable
    @SerialName("ExtraEquals")
    public data object ExtraEquals : ParseErrorReason

    /** A key with no `=` after it. */
    @Serializable
    @SerialName("KeyWithoutValue")
    public data object KeyWithoutValue : ParseErrorReason

    /** A key whose value is missing. */
    @Serializable
    @SerialName("MissingValueForKey")
    public data object MissingValueForKey : ParseErrorReason

    /** A place the grammar needs a value holds none. */
    @Serializable
    @SerialName("MissingValue")
    public data object MissingValue : ParseErrorReason

    /** A key or value where neither may stand. */
    @Serializable
    @SerialName("UnexpectedKeyOrValue")
    public data object UnexpectedKeyOrValue : ParseErrorReason

    /** An `=` inside an array, which holds values, not key/value pairs. */
    @Serializable
    @SerialName("UnexpectedEqualsInArray")
    public data object UnexpectedEqualsInArray : ParseErrorReason

    /** Text that fits nowhere in the grammar. */
    @Serializable
    @SerialName("UnexpectedContent")
    public data object UnexpectedContent : ParseErrorReason

    /** Text after a comment and before the end of its line. */
    @Serializable
    @SerialName("ContentAfterComment")
    public data object ContentAfterComment : ParseErrorReason

    /** A table header with no `[` before it. */
    @Serializable
    @SerialName("MissingTableOpen")
    public data object MissingTableOpen : ParseErrorReason

    /** An array with no `[` before it. */
    @Serializable
    @SerialName("MissingArrayOpening")
    public data object MissingArrayOpening : ParseErrorReason

    /** An inline table with no `{` before it. */
    @Serializable
    @SerialName("MissingInlineTableOpening")
    public data object MissingInlineTableOpening : ParseErrorReason

    /** A string with no quote before it. */
    @Serializable
    @SerialName("MissingOpeningQuote")
    public data object MissingOpeningQuote : ParseErrorReason

    /** A comment with no `#` before it. */
    @Serializable
    @SerialName("MissingCommentStart")
    public data object MissingCommentStart : ParseErrorReason

    /** An element of an inline table with a value but no key. */
    @Serializable
    @SerialName("MissingInlineTableKey")
    public data object MissingInlineTableKey : ParseErrorReason

    /** A `[table]` header with no `]`. */
    @Serializable
    @SerialName("UnclosedTable")
    public data object UnclosedTable : ParseErrorReason

    /** A `[[table]]` header with no `]]`. */
    @Serializable
    @SerialName("UnclosedArrayTable")
    public data object UnclosedArrayTable : ParseErrorReason

    /** An array with no `]`. */
    @Serializable
    @SerialName("UnclosedArray")
    public data object UnclosedArray : ParseErrorReason

    /** An inline table with no `}`. */
    @Serializable
    @SerialName("UnclosedInlineTable")
    public data object UnclosedInlineTable : ParseErrorReason

    /** Two array elements with no comma between them. */
    @Serializable
    @SerialName("MissingArrayComma")
    public data object MissingArrayComma : ParseErrorReason

    /** Two inline table entries with no comma between them. */
    @Serializable
    @SerialName("MissingInlineTableComma")
    public data object MissingInlineTableComma : ParseErrorReason

    /** A comma in an array with no element before it. */
    @Serializable
    @SerialName("ExtraArrayComma")
    public data object ExtraArrayComma : ParseErrorReason

    /** A comma in an inline table with no entry before it. */
    @Serializable
    @SerialName("ExtraInlineTableComma")
    public data object ExtraInlineTableComma : ParseErrorReason

    /** Arrays and inline tables nested deeper than the parser's depth limit. */
    @Serializable
    @SerialName("RecursionLimit")
    public data object RecursionLimit : ParseErrorReason
}

/** A part of a float, as named by [ParseErrorReason.InvalidFloatPart]. */
public enum class FloatPart {
    /** The digits before the decimal point. */
    Mantissa,

    /** The digits after the decimal point. */
    Fraction,

    /** The digits after `e`. */
    Exponent,
}

/** Something that would have been accepted where a [TomlParseError] points. */
@Serializable
public sealed interface Expected {
    /** Exact text, such as `=` or `]]`. */
    @Serializable
    @SerialName("Literal")
    public data class Literal(val literal: String) : Expected

    /** A kind of token. */
    @Serializable
    @SerialName("Token")
    public data class Token(val token: ExpectedToken) : Expected
}

/** A kind of token a [TomlParseError] says would have been accepted. */
public enum class ExpectedToken {
    BasicString,
    Characters,
    Digits,
    Key,
    Letters,
    LiteralString,
    NonDoubleQuoteVisibleCharacters,
    NonSingleQuoteCharacters,
    NonSingleQuoteVisibleCharacters,
    Numbers,
    PrintableCharacters,
    UnicodeHexadecimalValue,
    UnquotedString,
    Value,
}

/**
 * Which kind of scalar a value spells, as [ParseErrorReason.InvalidScalar] names it.
 */
@Serializable
public sealed interface ScalarKind {
    /** A string, quoted or not. */
    @Serializable
    @SerialName("String")
    public data object String : ScalarKind

    /** `true` or `false`, carrying which. */
    @Serializable
    @SerialName("Boolean")
    public data class Boolean(val value: kotlin.Boolean) : ScalarKind

    /** A date, time or date-time. */
    @Serializable
    @SerialName("DateTime")
    public data object DateTime : ScalarKind

    /** A float, including `inf` and `nan`. */
    @Serializable
    @SerialName("Float")
    public data object Float : ScalarKind

    /** An integer written in [radix]. */
    @Serializable
    @SerialName("Integer")
    public data class Integer(val radix: IntegerRadix) : ScalarKind
}

/** The base an integer is written in. */
public enum class IntegerRadix(
    /** The base as a number: 10, 16, 8 or 2. */
    public val base: Int,
) {
    /** Base 10, the default. */
    Dec(10),

    /** Base 16, `0x`. */
    Hex(16),

    /** Base 8, `0o`. */
    Oct(8),

    /** Base 2, `0b`. */
    Bin(2),
}

/**
 * How a quoted string was written: the four TOML string forms.
 *
 * They are declared in upstream's order, which numbers the single-line forms by their quote byte.
 */
public enum class Encoding {
    /** `'''multi-line literal'''` */
    MultilineLiteralString,

    /** `"""multi-line basic"""` */
    MultilineBasicString,

    /** `"basic"` */
    BasicString,

    /** `'literal'` */
    LiteralString,
}
