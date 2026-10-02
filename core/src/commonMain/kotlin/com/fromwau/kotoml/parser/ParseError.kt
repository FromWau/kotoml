package com.fromwau.kotoml.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the lexer, parser and decoders report what they found wrong.
 *
 * Reporting never stops parsing: the parser recovers and keeps going, so a sink may receive many errors
 * for one input. Pass a lambda to collect them, `ErrorSink { errors += it }`, or [ErrorSink.IGNORE] to
 * drop them.
 */
public fun interface ErrorSink {
    /** Receives one error, in the order they are found. */
    public fun reportError(error: ParseError)

    public companion object {
        /** A sink that drops every error. */
        public val IGNORE: ErrorSink = ErrorSink { }
    }
}

/**
 * Keeps the first error reported and drops the rest, as upstream's `Option<ParseError>` sink does.
 */
public class FirstErrorSink : ErrorSink {
    private var first: ParseError? = null

    /** The first error reported, or null when none was. */
    public fun error(): ParseError? = first

    override fun reportError(error: ParseError) {
        if (first == null) first = error
    }
}

/**
 * One problem found in TOML text.
 *
 * [unexpected] points at the offending bytes, [context] at the surrounding construct, and [expected] lists
 * what would have been accepted instead; an empty list means nothing would. Spans are byte offsets into the
 * input the error was reported against.
 */
@Serializable
public data class ParseError(
    val reason: ParseErrorReason,
    val unexpected: Span? = null,
    val context: Span? = null,
    val expected: List<Expected>? = null,
) {
    internal fun rebaseSpans(offset: Int): ParseError = copy(
        unexpected = unexpected?.plus(offset),
        context = context?.plus(offset),
    )
}

/** What is wrong where a [ParseError] points. */
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

    @Serializable
    @SerialName("InvalidKey")
    public data object InvalidKey : ParseErrorReason

    /** A table header whose key is not valid. */
    @Serializable
    @SerialName("InvalidTable")
    public data object InvalidTable : ParseErrorReason

    @Serializable
    @SerialName("InvalidValue")
    public data object InvalidValue : ParseErrorReason

    @Serializable
    @SerialName("InvalidUnquotedKey")
    public data object InvalidUnquotedKey : ParseErrorReason

    @Serializable
    @SerialName("InvalidKeyValuePair")
    public data object InvalidKeyValuePair : ParseErrorReason

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

    @Serializable
    @SerialName("KeyWithoutValue")
    public data object KeyWithoutValue : ParseErrorReason

    @Serializable
    @SerialName("MissingValueForKey")
    public data object MissingValueForKey : ParseErrorReason

    @Serializable
    @SerialName("MissingValue")
    public data object MissingValue : ParseErrorReason

    @Serializable
    @SerialName("UnexpectedKeyOrValue")
    public data object UnexpectedKeyOrValue : ParseErrorReason

    @Serializable
    @SerialName("UnexpectedEqualsInArray")
    public data object UnexpectedEqualsInArray : ParseErrorReason

    @Serializable
    @SerialName("UnexpectedContent")
    public data object UnexpectedContent : ParseErrorReason

    /** Text after a comment and before the end of its line. */
    @Serializable
    @SerialName("ContentAfterComment")
    public data object ContentAfterComment : ParseErrorReason

    @Serializable
    @SerialName("MissingTableOpen")
    public data object MissingTableOpen : ParseErrorReason

    @Serializable
    @SerialName("MissingArrayOpening")
    public data object MissingArrayOpening : ParseErrorReason

    @Serializable
    @SerialName("MissingInlineTableOpening")
    public data object MissingInlineTableOpening : ParseErrorReason

    @Serializable
    @SerialName("MissingOpeningQuote")
    public data object MissingOpeningQuote : ParseErrorReason

    @Serializable
    @SerialName("MissingCommentStart")
    public data object MissingCommentStart : ParseErrorReason

    @Serializable
    @SerialName("MissingInlineTableKey")
    public data object MissingInlineTableKey : ParseErrorReason

    @Serializable
    @SerialName("UnclosedTable")
    public data object UnclosedTable : ParseErrorReason

    @Serializable
    @SerialName("UnclosedArrayTable")
    public data object UnclosedArrayTable : ParseErrorReason

    @Serializable
    @SerialName("UnclosedArray")
    public data object UnclosedArray : ParseErrorReason

    @Serializable
    @SerialName("UnclosedInlineTable")
    public data object UnclosedInlineTable : ParseErrorReason

    @Serializable
    @SerialName("MissingArrayComma")
    public data object MissingArrayComma : ParseErrorReason

    @Serializable
    @SerialName("MissingInlineTableComma")
    public data object MissingInlineTableComma : ParseErrorReason

    @Serializable
    @SerialName("ExtraArrayComma")
    public data object ExtraArrayComma : ParseErrorReason

    @Serializable
    @SerialName("ExtraInlineTableComma")
    public data object ExtraInlineTableComma : ParseErrorReason

    /** Arrays and inline tables nested deeper than the parser's depth limit. */
    @Serializable
    @SerialName("RecursionLimit")
    public data object RecursionLimit : ParseErrorReason

    /** The decoded text could not be stored. */
    @Serializable
    @SerialName("AllocationFailed")
    public data object AllocationFailed : ParseErrorReason
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

/** Something that would have been accepted where a [ParseError] points. */
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

/** A kind of token a [ParseError] says would have been accepted. */
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
