package com.fromwau.kotoml

import com.fromwau.kern.result.IError
import com.fromwau.kotoml.parser.ErrorSink
import com.fromwau.kotoml.parser.ParseError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why TOML text could not be parsed: the first problem found.
 *
 * [span] is the range of the offending text, as indices into the parsed `String`, and [position] where it starts;
 * both are null only when the problem has no known place. For bytes that are not UTF-8, [span] is empty where the
 * valid text ends. [context] is the range of a related construct, such as the first definition of a duplicate key or
 * the whole value holding a malformed number. [expected] lists what would have been accepted at [span], when known.
 *
 * [render] turns it into the message a person reads.
 */
@Serializable
public data class TomlParseError(
    val reason: Reason,
    val span: Span? = null,
    val position: TextPosition? = null,
    val context: Span? = null,
    val expected: List<Expected>? = null,
) : IError {

    /** What is wrong. */
    @Serializable
    public sealed interface Reason {
        /** The text does not follow TOML's grammar. */
        @Serializable
        @SerialName("Syntax")
        public data class Syntax(val reason: ParseErrorReason) : Reason

        /** [key] is defined twice, or a dotted key reopens a table already defined with a header. */
        @Serializable
        @SerialName("DuplicateKey")
        public data class DuplicateKey(val key: String) : Reason

        /** A dotted key goes through [key], which holds a [valueType] value rather than a table. */
        @Serializable
        @SerialName("DottedKeyExtendsValue")
        public data class DottedKeyExtendsValue(val key: String, val valueType: TomlType) : Reason

        /** A date-time that does not exist or is spelled wrong. */
        @Serializable
        @SerialName("InvalidDatetime")
        public data class InvalidDatetime(val error: DatetimeParseError) : Reason

        /** A radix prefix such as `0x` with no digits after it. */
        @Serializable
        @SerialName("MissingDigits")
        public data class MissingDigits(val radix: IntegerRadix) : Reason

        /** An integer outside the range of [Long]. */
        @Serializable
        @SerialName("IntegerOverflow")
        public data object IntegerOverflow : Reason

        /** A float too large for [Double]. */
        @Serializable
        @SerialName("FloatOverflow")
        public data object FloatOverflow : Reason

        /** A key with more dotted parts than the parser's depth limit. */
        @Serializable
        @SerialName("KeyTooDeep")
        public data object KeyTooDeep : Reason

        /**
         * The bytes are not UTF-8: the first [validUpTo] bytes are, and the next [errorLength] bytes do not
         * start a character. A null [errorLength] means the input ends inside a character.
         */
        @Serializable
        @SerialName("InvalidUtf8")
        public data class InvalidUtf8(val validUpTo: Int, val errorLength: Int?) : Reason

        /** Text TOML 1.1 accepts, parsed as [TomlVersion.V1_0]. */
        @Serializable
        @SerialName("NotInToml10")
        public data class NotInToml10(val feature: Toml11Feature) : Reason
    }
}

/** What TOML 1.1 accepts that 1.0 does not, beyond the escapes. */
public enum class Toml11Feature {
    /** Newlines and comments inside an inline table. */
    MultilineInlineTable,

    /** A comma after the last entry of an inline table. */
    InlineTableTrailingComma,

    /** A time without seconds, such as `07:32`. */
    TimeWithoutSeconds,
}

/** A place in text: the 1-based [line], and the 1-based [column] counted in characters. */
@Serializable
public data class TextPosition(val line: Int, val column: Int)

/**
 * The zero-based line and column of byte [index]; the column counts the characters before it, as Rust's `chars`
 * does, or its bytes when they are not UTF-8.
 */
internal fun translatePosition(input: ByteArray, index: Int): Pair<Int, Int> {
    if (input.isEmpty()) return 0 to index

    val safeIndex = minOf(index, input.size - 1)

    var lineStart = 0
    for (position in safeIndex - 1 downTo 0) {
        if (input[position] == '\n'.code.toByte()) {
            lineStart = position + 1
            break
        }
    }
    var line = 0
    for (position in 0 until lineStart) {
        if (input[position] == '\n'.code.toByte()) line++
    }

    // Only the text before the index is counted: the character at it may be cut off or not UTF-8 at all.
    val countEnd = minOf(index, input.size)
    val column = utf8CharCount(input, lineStart, countEnd) ?: (countEnd - lineStart)
    return line to column + (index - countEnd)
}

/** The number of characters in `input[from, to)`, or null when the slice is not valid UTF-8. */
private fun utf8CharCount(input: ByteArray, from: Int, to: Int): Int? {
    var count = 0
    var position = from
    while (position < to) {
        val lead = input[position].toInt() and 0xFF
        val length = when {
            lead < 0x80 -> 1
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> return null
        }
        if (position + length > to) return null
        for (continuation in 1 until length) {
            if ((input[position + continuation].toInt() and 0xC0) != 0x80) return null
        }
        position += length
        count++
    }
    return count
}

/** Why [bytes] are not UTF-8, as Rust's `str::from_utf8` decides, or null when they are. */
internal fun utf8Error(bytes: ByteArray): TomlParseError.Reason.InvalidUtf8? {
    var position = 0
    while (position < bytes.size) {
        val lead = bytes[position].toInt() and 0xFF
        val (length, minSecond, maxSecond) = when (lead) {
            in 0x00..0x7F -> Triple(1, 0, 0)
            in 0xC2..0xDF -> Triple(2, 0x80, 0xBF)
            0xE0 -> Triple(3, 0xA0, 0xBF)
            in 0xE1..0xEC, 0xEE, 0xEF -> Triple(3, 0x80, 0xBF)
            0xED -> Triple(3, 0x80, 0x9F)
            0xF0 -> Triple(4, 0x90, 0xBF)
            in 0xF1..0xF3 -> Triple(4, 0x80, 0xBF)
            0xF4 -> Triple(4, 0x80, 0x8F)
            else -> return TomlParseError.Reason.InvalidUtf8(position, 1)
        }
        for (index in 1 until length) {
            if (position + index >= bytes.size) return TomlParseError.Reason.InvalidUtf8(position, null)
            val byte = bytes[position + index].toInt() and 0xFF
            val (min, max) = if (index == 1) minSecond to maxSecond else 0x80 to 0xBF
            if (byte !in min..max) return TomlParseError.Reason.InvalidUtf8(position, index)
        }
        position += length
    }
    return null
}

/** Keeps the first error reported, from the parser or from building the document. */
internal class TomlSink(private val input: String) : ErrorSink {
    private var first: TomlParseError? = null
    private val bytes by lazy { input.encodeToByteArray() }

    val error: TomlParseError?
        get() = first

    override fun reportError(error: ParseError) {
        report(
            TomlParseError.Reason.Syntax(error.reason),
            unexpected = error.unexpected,
            context = error.context,
            expected = error.expected,
        )
    }

    fun report(
        reason: TomlParseError.Reason,
        unexpected: Span? = null,
        context: Span? = null,
        expected: List<Expected>? = null,
    ) {
        if (first != null) return
        val position = unexpected?.let {
            val (line, column) = translatePosition(bytes, it.start)
            TextPosition(line + 1, column + 1)
        }
        first = TomlParseError(
            reason = reason,
            span = unexpected?.toTextIndices(bytes),
            position = position,
            context = context?.toTextIndices(bytes),
            expected = expected,
        )
    }
}

/** This byte span of [bytes] as indices into the `String` the bytes decode to. */
internal fun Span.toTextIndices(bytes: ByteArray): Span = Span(utf16Index(bytes, start), utf16Index(bytes, end))

/** The number of UTF-16 units that `bytes[0, byteIndex)` decodes to; a four-byte sequence needs two. */
private fun utf16Index(bytes: ByteArray, byteIndex: Int): Int {
    var units = 0
    for (position in 0 until minOf(byteIndex, bytes.size)) {
        val byte = bytes[position].toInt() and 0xFF
        when {
            byte and 0xC0 == 0x80 -> {}
            byte >= 0xF0 -> units += 2
            else -> units += 1
        }
    }
    return units + maxOf(byteIndex - bytes.size, 0)
}
