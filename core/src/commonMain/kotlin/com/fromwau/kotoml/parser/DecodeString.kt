package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Encoding
import com.fromwau.kotoml.Expected
import com.fromwau.kotoml.ExpectedToken
import com.fromwau.kotoml.ParseErrorReason
import com.fromwau.kotoml.Span

// Every offset in this file is a byte offset relative to the start of the Raw being decoded.

private const val REPLACEMENT_CHARACTER = 0xFFFD

private val EXPECTED_ESCAPES = listOf(
    Expected.Literal("b"),
    Expected.Literal("e"),
    Expected.Literal("f"),
    Expected.Literal("n"),
    Expected.Literal("r"),
    Expected.Literal("t"),
    Expected.Literal("\\"),
    Expected.Literal("\""),
    Expected.Literal("x"),
    Expected.Literal("u"),
    Expected.Literal("U"),
)

private val EXPECTED_UNQUOTED_KEY = listOf(
    Expected.Token(ExpectedToken.Letters),
    Expected.Token(ExpectedToken.Numbers),
    Expected.Literal("-"),
    Expected.Literal("_"),
)

/** `literal-char = %x09 / %x20-26 / %x28-7E / non-ascii` */
private fun isLiteralChar(byte: Int): Boolean =
    byte == 0x09 || byte in 0x20..0x26 || byte in 0x28..0x7E || byte >= 0x80

/** `basic-unescaped = wschar / %x21 / %x23-5B / %x5D-7E / non-ascii` */
private fun isBasicUnescaped(byte: Int): Boolean =
    isWsChar(byte) || byte == 0x21 || byte in 0x23..0x5B || byte in 0x5D..0x7E || byte >= 0x80

private fun isHexDigit(byte: Int): Boolean =
    byte in '0'.code..'9'.code || byte in 'A'.code..'F'.code || byte in 'a'.code..'f'.code

private fun isUnquotedChar(byte: Int): Boolean = byte in 'A'.code..'Z'.code ||
    byte in 'a'.code..'z'.code ||
    byte in '0'.code..'9'.code ||
    byte == '-'.code ||
    byte == '_'.code

private fun Raw.startsWithBytes(at: Int, prefix: ByteArray): Boolean = startsWith(bytes, from + at, prefix) &&
    at + prefix.size <= len()

private fun Raw.pushText(output: DecodedStringBuilder, start: Int, end: Int): Boolean = output.pushStr(text(start, end))

private fun Raw.reportAllocation(error: ErrorSink, start: Int, end: Int) {
    error.reportError(ParseError(ParseErrorReason.AllocationFailed, unexpected = Span(start, end)))
}

/** The number of bytes in the UTF-8 sequence starting with [lead]. */
internal fun utf8Length(lead: Int): Int = when {
    lead < 0x80 -> 1
    lead < 0xE0 -> 2
    lead < 0xF0 -> 3
    else -> 4
}

private fun Raw.codePointAt(offset: Int): Int {
    val lead = byteAt(offset)
    return when (utf8Length(lead)) {
        1 -> lead
        2 -> ((lead and 0x1F) shl 6) or (byteAt(offset + 1) and 0x3F)
        3 -> ((lead and 0x0F) shl 12) or ((byteAt(offset + 1) and 0x3F) shl 6) or (byteAt(offset + 2) and 0x3F)
        else -> ((lead and 0x07) shl 18) or
            ((byteAt(offset + 1) and 0x3F) shl 12) or
            ((byteAt(offset + 2) and 0x3F) shl 6) or
            (byteAt(offset + 3) and 0x3F)
    }
}

/** `literal-string = apostrophe *literal-char apostrophe` */
internal fun decodeLiteralString(raw: Raw, output: DecodedStringBuilder, error: ErrorSink) {
    val invalidString = ParseErrorReason.InvalidString(Encoding.LiteralString)
    val length = raw.len()

    output.clear()

    var start = 0
    var end = length
    if (start < end && raw.byteAt(start) == APOSTROPHE) {
        start += 1
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("'")),
                unexpected = Span(0, 0),
            ),
        )
    }
    if (start < end && raw.byteAt(end - 1) == APOSTROPHE) {
        end -= 1
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("'")),
                unexpected = Span(length, length),
            ),
        )
    }

    for (offset in start until end) {
        if (!isLiteralChar(raw.byteAt(offset))) {
            error.reportError(
                ParseError(
                    invalidString,
                    context = Span(0, length),
                    expected = listOf(Expected.Token(ExpectedToken.NonSingleQuoteVisibleCharacters)),
                    unexpected = Span(offset, offset),
                ),
            )
        }
    }

    if (!raw.pushText(output, start, end)) raw.reportAllocation(error, 0, length)
}

private fun Raw.stripStartNewline(start: Int, end: Int): Int = when {
    start < end && byteAt(start) == '\n'.code -> start + 1
    start + 1 < end && byteAt(start) == '\r'.code && byteAt(start + 1) == '\n'.code -> start + 2
    else -> start
}

/** `ml-literal-string = ml-literal-string-delim [ newline ] ml-literal-body ml-literal-string-delim` */
internal fun decodeMlLiteralString(raw: Raw, output: DecodedStringBuilder, error: ErrorSink) {
    val invalidString = ParseErrorReason.InvalidString(Encoding.MlLiteralString)
    val length = raw.len()

    output.clear()

    var start = 0
    var end = length
    if (raw.startsWithBytes(0, ML_LITERAL_STRING_DELIM)) {
        start += ML_LITERAL_STRING_DELIM.size
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("'")),
                unexpected = Span(0, 0),
            ),
        )
    }
    start = raw.stripStartNewline(start, end)
    if (end - start >= ML_LITERAL_STRING_DELIM.size && raw.startsWithBytes(end - 3, ML_LITERAL_STRING_DELIM)) {
        end -= ML_LITERAL_STRING_DELIM.size
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("'")),
                unexpected = Span(length, length),
            ),
        )
        while (end > start && raw.byteAt(end - 1) == APOSTROPHE) end -= 1
    }

    for (offset in start until end) {
        val byte = raw.byteAt(offset)
        if (byte == APOSTROPHE || byte == '\n'.code) {
            continue
        } else if (byte == '\r'.code) {
            if (offset + 1 >= end || raw.byteAt(offset + 1) != '\n'.code) {
                error.reportError(
                    ParseError(
                        ParseErrorReason.LoneCarriageReturn,
                        context = Span(0, length),
                        expected = listOf(Expected.Literal("\n")),
                        unexpected = Span(offset + 1, offset + 1),
                    ),
                )
            }
        } else if (!isLiteralChar(byte)) {
            error.reportError(
                ParseError(
                    invalidString,
                    context = Span(0, length),
                    expected = listOf(Expected.Token(ExpectedToken.NonSingleQuoteCharacters)),
                    unexpected = Span(offset, offset),
                ),
            )
        }
    }

    if (!raw.pushText(output, start, end)) raw.reportAllocation(error, 0, length)
}

/** A cursor over the bytes of a [Raw] that are still to be decoded. */
private class Stream(val raw: Raw, var position: Int, val end: Int) {
    fun isEmpty(): Boolean = position >= end

    fun peek(): Int = if (position < end) raw.byteAt(position) else -1

    fun startsWith(byte: Int): Boolean = peek() == byte

    /** Advances past the run of bytes matching [predicate] and returns where the run started. */
    inline fun takeWhile(predicate: (Int) -> Boolean): Int {
        val start = position
        while (position < end && predicate(raw.byteAt(position))) position++
        return start
    }
}

/** `basic-string = quotation-mark *basic-char quotation-mark` */
internal fun decodeBasicString(raw: Raw, output: DecodedStringBuilder, error: ErrorSink) {
    val invalidString = ParseErrorReason.InvalidString(Encoding.BasicString)
    val length = raw.len()

    output.clear()

    var start = 0
    var end = length
    if (start < end && raw.byteAt(start) == QUOTATION_MARK) {
        start += 1
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("\"")),
                unexpected = Span(0, 0),
            ),
        )
    }
    if (start < end && raw.byteAt(end - 1) == QUOTATION_MARK) {
        end -= 1
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("\"")),
                unexpected = Span(length, length),
            ),
        )
    }

    val stream = Stream(raw, start, end)
    val first = stream.takeWhile(::isBasicUnescaped)
    if (!raw.pushText(output, first, stream.position)) raw.reportAllocation(error, 0, length)
    while (!stream.isEmpty()) {
        if (stream.startsWith(ESCAPE)) {
            stream.position += 1

            val char = escapeSeqChar(stream, error)
            if (!output.pushChar(char)) raw.reportAllocation(error, 0, length)
        } else {
            val invalidStart = stream.takeWhile { !isBasicUnescaped(it) && it != ESCAPE }
            error.reportError(
                ParseError(
                    invalidString,
                    context = Span(0, length),
                    expected = 
                        listOf(
                            Expected.Token(ExpectedToken.NonDoubleQuoteVisibleCharacters),
                            Expected.Literal("\\"),
                        ),
                    unexpected = Span(invalidStart, stream.position),
                ),
            )
            raw.pushText(output, invalidStart, stream.position)
        }

        val segment = stream.takeWhile(::isBasicUnescaped)
        if (!raw.pushText(output, segment, stream.position)) raw.reportAllocation(error, segment, stream.position)
    }
}

/** `escape-seq-char`: the character after a `\`, which [stream] is positioned at. */
private fun escapeSeqChar(stream: Stream, error: ErrorSink): Int {
    val length = stream.raw.len()
    val start = stream.position
    if (stream.isEmpty()) {
        error.reportError(
            ParseError(
                ParseErrorReason.MissingEscapedValue,
                context = Span(0, length),
                expected = EXPECTED_ESCAPES,
                unexpected = Span(start, start),
            ),
        )
        return '\\'.code
    }
    val id = stream.raw.codePointAt(start)
    stream.position += utf8Length(stream.peek())
    return when (id) {
        'b'.code -> 0x8
        'e'.code -> 0x1B
        'f'.code -> 0xC
        'n'.code -> '\n'.code
        'r'.code -> '\r'.code
        't'.code -> '\t'.code
        'x'.code -> hexEscape(stream, 2, error)
        'u'.code -> hexEscape(stream, 4, error)
        'U'.code -> hexEscape(stream, 8, error)
        '\\'.code -> '\\'.code
        '"'.code -> '"'.code
        else -> {
            stream.position = start
            error.reportError(
                ParseError(
                    ParseErrorReason.MissingEscapedValue,
                    context = Span(0, length),
                    expected = EXPECTED_ESCAPES,
                    unexpected = Span(start, start),
                ),
            )
            '\\'.code
        }
    }
}

private fun hexEscape(stream: Stream, digits: Int, error: ErrorSink): Int {
    val length = stream.raw.len()
    val start = stream.position
    while (stream.position < stream.end && stream.position - start < digits && isHexDigit(stream.peek())) {
        stream.position++
    }
    if (stream.position - start != digits) {
        error.reportError(
            ParseError(
                ParseErrorReason.TooFewUnicodeDigits,
                context = Span(0, length),
                expected = listOf(Expected.Token(ExpectedToken.UnicodeHexadecimalValue)),
                unexpected = Span(stream.position, stream.position),
            ),
        )
        return REPLACEMENT_CHARACTER
    }

    var value = 0L
    for (offset in start until stream.position) {
        val byte = stream.raw.byteAt(offset)
        val digit = when (byte) {
            in '0'.code..'9'.code -> byte - '0'.code
            in 'a'.code..'f'.code -> byte - 'a'.code + 10
            else -> byte - 'A'.code + 10
        }
        value = value * 16 + digit
    }
    if (value > 0x10FFFF || value in 0xD800L..0xDFFFL) {
        error.reportError(
            ParseError(
                ParseErrorReason.InvalidValue,
                context = Span(0, length),
                expected = listOf(Expected.Token(ExpectedToken.UnicodeHexadecimalValue)),
                unexpected = Span(start, start),
            ),
        )
        return REPLACEMENT_CHARACTER
    }
    return value.toInt()
}

/** `ml-basic-string = ml-basic-string-delim [ newline ] ml-basic-body ml-basic-string-delim` */
internal fun decodeMlBasicString(raw: Raw, output: DecodedStringBuilder, error: ErrorSink) {
    val invalidString = ParseErrorReason.InvalidString(Encoding.MlBasicString)
    val length = raw.len()

    var start = 0
    var end = length
    if (raw.startsWithBytes(0, ML_BASIC_STRING_DELIM)) {
        start += ML_BASIC_STRING_DELIM.size
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("\"")),
                unexpected = Span(0, 0),
            ),
        )
    }
    start = raw.stripStartNewline(start, end)
    if (end - start >= ML_BASIC_STRING_DELIM.size && raw.startsWithBytes(end - 3, ML_BASIC_STRING_DELIM)) {
        end -= ML_BASIC_STRING_DELIM.size
    } else {
        error.reportError(
            ParseError(
                invalidString,
                context = Span(0, length),
                expected = listOf(Expected.Literal("\"")),
                unexpected = Span(length, length),
            ),
        )
    }

    val stream = Stream(raw, start, end)
    val first = stream.takeWhile(::isMlbUnescaped)
    if (!raw.pushText(output, first, stream.position)) raw.reportAllocation(error, 0, length)
    while (!stream.isEmpty()) {
        if (stream.startsWith(ESCAPE)) {
            stream.position += 1

            val next = stream.peek()
            if (isWsChar(next) || next == '\r'.code || next == '\n'.code) {
                mlbEscapedNl(stream, error)
            } else {
                val char = escapeSeqChar(stream, error)
                if (!output.pushChar(char)) raw.reportAllocation(error, 0, length)
            }
        } else if (stream.startsWith('\r'.code)) {
            val newlineStart = stream.position
            val newlineLength = if (stream.position + 1 < stream.end && raw.byteAt(stream.position + 1) == '\n'.code) {
                2
            } else {
                error.reportError(
                    ParseError(
                        ParseErrorReason.LoneCarriageReturn,
                        context = Span(0, length),
                        expected = listOf(Expected.Literal("\n")),
                        unexpected = Span(newlineStart + 1, newlineStart + 1),
                    ),
                )
                1
            }
            stream.position += newlineLength
            if (!raw.pushText(output, newlineStart, stream.position)) {
                raw.reportAllocation(error, newlineStart, stream.position)
            }
        } else {
            val invalidStart = stream.takeWhile {
                !(isBasicUnescaped(it) || it == '"'.code || it == '\n'.code || it == ESCAPE || it == '\r'.code)
            }
            error.reportError(
                ParseError(
                    invalidString,
                    context = Span(0, length),
                    expected = listOf(Expected.Literal("\\"), Expected.Token(ExpectedToken.Characters)),
                    unexpected = Span(invalidStart, stream.position),
                ),
            )
            raw.pushText(output, invalidStart, stream.position)
        }

        val segment = stream.takeWhile(::isMlbUnescaped)
        if (!raw.pushText(output, segment, stream.position)) raw.reportAllocation(error, segment, stream.position)
    }
}

/** `mlb-unescaped` widened with `mlb-quotes` and LF, whose CRLF partner is validated separately. */
private fun isMlbUnescaped(byte: Int): Boolean = isBasicUnescaped(byte) || byte == '"'.code || byte == '\n'.code

/** `mlb-escaped-nl = escape ws newline *( wschar / newline )`, with [stream] just past the escape. */
private fun mlbEscapedNl(stream: Stream, error: ErrorSink) {
    val invalidString = ParseErrorReason.InvalidString(Encoding.MlBasicString)
    val length = stream.raw.len()
    stream.takeWhile(::isWsChar)

    val start = stream.position
    when (stream.peek()) {
        '\n'.code -> stream.position += 1
        '\r'.code -> {
            stream.position += 1
            if (stream.peek() == '\n'.code) {
                stream.position += 1
            } else {
                error.reportError(
                    ParseError(
                        ParseErrorReason.LoneCarriageReturn,
                        context = Span(0, length),
                        expected = listOf(Expected.Literal("\n")),
                        unexpected = Span(stream.position, stream.position),
                    ),
                )
            }
        }
        else -> {
            stream.position = start
            error.reportError(
                ParseError(
                    invalidString,
                    context = Span(0, length),
                    expected = listOf(Expected.Literal("\n")),
                    unexpected = Span(start, start),
                ),
            )
        }
    }

    while (true) {
        val startOffset = stream.position

        stream.takeWhile { isWsChar(it) || it == '\n'.code }

        if (stream.startsWith('\r'.code)) {
            if (stream.position + 1 < stream.end && stream.raw.byteAt(stream.position + 1) == '\n'.code) {
                stream.position += 2
            } else {
                val at = stream.position + 1
                error.reportError(
                    ParseError(
                        ParseErrorReason.LoneCarriageReturn,
                        context = Span(0, length),
                        expected = listOf(Expected.Literal("\n")),
                        unexpected = Span(at, at),
                    ),
                )
                stream.position += 1
            }
        }

        if (startOffset == stream.position) break
    }
}

/** `unquoted-key = 1*( ALPHA / DIGIT / %x2D / %x5F )` */
internal fun decodeUnquotedKey(raw: Raw, output: DecodedStringBuilder, error: ErrorSink) {
    val length = raw.len()

    if (length == 0) {
        error.reportError(
            ParseError(
                ParseErrorReason.EmptyUnquotedKey,
                context = Span(0, length),
                expected = EXPECTED_UNQUOTED_KEY,
                unexpected = Span(0, length),
            ),
        )
    }

    var runStart = -1
    var runEnd = -1
    for (offset in 0 until length) {
        if (isUnquotedChar(raw.byteAt(offset))) continue
        if (runStart >= 0 && offset == runEnd) {
            runEnd = offset + 1
        } else {
            if (runStart >= 0) reportInvalidUnquotedKey(error, length, runStart, runEnd)
            runStart = offset
            runEnd = offset + 1
        }
    }
    if (runStart >= 0) reportInvalidUnquotedKey(error, length, runStart, runEnd)

    if (!raw.pushText(output, 0, length)) raw.reportAllocation(error, 0, length)
}

private fun reportInvalidUnquotedKey(error: ErrorSink, length: Int, start: Int, end: Int) {
    error.reportError(
        ParseError(
            ParseErrorReason.InvalidUnquotedKey,
            context = Span(0, length),
            expected = EXPECTED_UNQUOTED_KEY,
            unexpected = Span(start, end),
        ),
    )
}

/** `comment = comment-start-symbol *non-eol` */
internal fun decodeCommentText(raw: Raw, error: ErrorSink) {
    val length = raw.len()
    if (length == 0 || raw.byteAt(0) != COMMENT_START_SYMBOL) {
        error.reportError(
            ParseError(
                ParseErrorReason.MissingCommentStart,
                context = Span(0, length),
                expected = listOf(Expected.Literal("#")),
                unexpected = Span(0, 0),
            ),
        )
    }

    for (offset in 0 until length) {
        val byte = raw.byteAt(offset)
        val isNonEol = byte == 0x09 || byte in 0x20..0x7E || byte >= 0x80
        if (!isNonEol) {
            error.reportError(
                ParseError(
                    ParseErrorReason.InvalidCommentCharacter,
                    context = Span(0, length),
                    expected = listOf(Expected.Token(ExpectedToken.PrintableCharacters)),
                    unexpected = Span(offset, offset),
                ),
            )
        }
    }
}

/** `newline = %x0A / %x0D.0A` */
internal fun decodeNewlineText(raw: Raw, error: ErrorSink) {
    val length = raw.len()
    if (length == 1 && raw.byteAt(0) == '\r'.code) {
        error.reportError(
            ParseError(
                ParseErrorReason.LoneCarriageReturn,
                context = Span(0, length),
                expected = listOf(Expected.Literal("\n")),
                unexpected = Span(length, length),
            ),
        )
    }
}
