package com.fromwau.kotoml.parser

import com.fromwau.kotoml.Expected
import com.fromwau.kotoml.ExpectedToken
import com.fromwau.kotoml.FloatPart
import com.fromwau.kotoml.IntegerRadix
import com.fromwau.kotoml.ParseErrorReason
import com.fromwau.kotoml.ScalarKind
import com.fromwau.kotoml.Span

// Every offset in this file is a byte offset relative to the start of the Raw being decoded. The text being
// examined is always a suffix of the raw value, so a single start offset stands for it.


private val EXPECTED_NOTHING = emptyList<Expected>()

private fun isDigit(byte: Int): Boolean = byte in '0'.code..'9'.code

private fun Raw.containsByte(from: Int, byte: Int): Boolean {
    for (offset in from until length) {
        if (byteAt(offset) == byte) return true
    }
    return false
}

private fun Raw.endsWithText(suffix: String): Boolean {
    val bytes = suffix.encodeToByteArray()
    if (bytes.size > length) return false
    for (index in bytes.indices) {
        if (byteAt(length - bytes.size + index) != (bytes[index].toInt() and 0xFF)) return false
    }
    return true
}

private fun Raw.textEquals(from: Int, expected: String): Boolean = text(from, length) == expected

private fun Raw.wholeContext(): Span = Span(0, length)

internal fun decodeUnquotedScalar(raw: Raw, output: DecodedStringBuilder, error: ErrorSink): ScalarKind {
    if (raw.isEmpty()) return decodeInvalid(raw, output, error)
    val first = raw.byteAt(0)
    if (!isDigit(first) && raw.containsByte(0, ' '.code)) {
        // Only datetimes can have a space
        return decodeInvalid(raw, output, error)
    }
    return when (first) {
        '+'.code, '-'.code -> decodeSignPrefix(raw, 1, output, error)
        // Report as if they were numbers because its most likely a typo
        '_'.code -> decodeDatetimeOrFloatOrInteger(0, raw, output, error)
        '0'.code -> decodeZeroPrefix(0, false, raw, output, error)
        in '1'.code..'9'.code -> decodeDatetimeOrFloatOrInteger(0, raw, output, error)
        '.'.code -> {
            val kind = ScalarKind.Float
            if (ensureFloat(0, raw, error)) decodeFloatOrInteger(0, raw, kind, output, error) else kind
        }
        else -> decodeKeyword(raw, 0, KEYWORDS, output, error)
    }
}

private fun decodeSignPrefix(raw: Raw, valueStart: Int, output: DecodedStringBuilder, error: ErrorSink): ScalarKind {
    var value = valueStart
    var first: Int
    while (true) {
        if (value >= raw.length) return decodeInvalid(raw, output, error)
        first = raw.byteAt(value)
        if (first != '+'.code && first != '-'.code) break
        error.reportError(
            ParseError(
                ParseErrorReason.RedundantSign,
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(value, value + 1),
            ),
        )
        value += 1
    }
    return when (first) {
        // Report as if they were numbers because its most likely a typo
        '_'.code -> decodeDatetimeOrFloatOrInteger(value, raw, output, error)
        '0'.code -> decodeZeroPrefix(value, true, raw, output, error)
        in '1'.code..'9'.code -> decodeDatetimeOrFloatOrInteger(value, raw, output, error)
        '.'.code -> {
            val kind = ScalarKind.Float
            if (ensureFloat(0, raw, error)) decodeFloatOrInteger(0, raw, kind, output, error) else kind
        }
        else -> decodeKeyword(raw, value, SIGNED_KEYWORDS, output, error)
    }
}

private fun decodeZeroPrefix(
    value: Int,
    signed: Boolean,
    raw: Raw,
    output: DecodedStringBuilder,
    error: ErrorSink,
): ScalarKind {
    if (raw.length - value == 1) {
        // No extra validation needed
        return decodeFloatOrInteger(0, raw, ScalarKind.Integer(IntegerRadix.Dec), output, error)
    }
    val radixByte = raw.byteAt(value + 1)
    val (radix, uppercase, prefix) = when (radixByte) {
        'x'.code, 'X'.code -> Triple(IntegerRadix.Hex, radixByte == 'X'.code, "0x")
        'o'.code, 'O'.code -> Triple(IntegerRadix.Oct, radixByte == 'O'.code, "0o")
        'b'.code, 'B'.code -> Triple(IntegerRadix.Bin, radixByte == 'B'.code, "0b")
        'd'.code, 'D'.code -> Triple(IntegerRadix.Dec, false, null)
        else -> return decodeDatetimeOrFloatOrInteger(value, raw, output, error)
    }
    if (raw.containsByte(value, ' '.code)) {
        // Only datetimes can have a space
        return decodeInvalid(raw, output, error)
    }
    if (signed) {
        error.reportError(
            ParseError(
                ParseErrorReason.SignedRadixInteger,
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(0, 1),
            ),
        )
    }
    if (uppercase) {
        error.reportError(
            ParseError(
                ParseErrorReason.UppercaseRadix,
                context = raw.wholeContext(),
                expected = listOf(Expected.Literal(prefix ?: "")),
                unexpected = Span(value, value + 2),
            ),
        )
    }
    val kind = ScalarKind.Integer(radix)
    val stream = value + 2
    if (prefix == null) {
        error.reportError(
            ParseError(
                ParseErrorReason.RedundantRadixPrefix,
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(0, 2),
            ),
        )
    }
    return if (ensureRadixedValue(stream, raw, radix, error)) {
        decodeFloatOrInteger(stream, raw, kind, output, error)
    } else {
        kind
    }
}

private fun decodeDatetimeOrFloatOrInteger(
    value: Int,
    raw: Raw,
    output: DecodedStringBuilder,
    error: ErrorSink,
): ScalarKind {
    var digitEnd = value
    while (digitEnd < raw.length && isDigit(raw.byteAt(digitEnd))) digitEnd++
    if (digitEnd == raw.length) {
        val kind = ScalarKind.Integer(IntegerRadix.Dec)
        return if (ensureNoLeadingZero(value, raw, error)) {
            decodeFloatOrInteger(0, raw, kind, output, error)
        } else {
            kind
        }
    }

    val rest = digitEnd
    val restFirst = raw.byteAt(rest)
    return when {
        restFirst == '-'.code || restFirst == ':'.code -> decodeAs(raw, raw.text, ScalarKind.DateTime, output, error)
        raw.containsByte(rest, ' '.code) -> decodeInvalid(raw, output, error)
        isFloat(raw, rest) -> {
            val kind = ScalarKind.Float
            if (ensureFloat(value, raw, error)) decodeFloatOrInteger(0, raw, kind, output, error) else kind
        }
        restFirst == '_'.code -> {
            val radix = IntegerRadix.Dec
            val kind = ScalarKind.Integer(radix)
            if (!ensureNoLeadingZero(value, raw, error)) return kind
            // Misplaced underscores are reported first; only an otherwise clean number is checked for stray characters.
            var reported = false
            val decoded = decodeFloatOrInteger(0, raw, kind, output) { reported = true; error.reportError(it) }
            if (!reported) ensureRadixedValue(value, raw, radix, error)
            decoded
        }
        else -> decodeInvalid(raw, output, error)
    }
}

/**
 * ```abnf
 * float = float-int-part ( exp / frac [ exp ] )
 * frac = decimal-point zero-prefixable-int
 * exp = "e" float-exp-part
 * float-exp-part = [ minus / plus ] zero-prefixable-int
 * ```
 */
private fun ensureFloat(valueStart: Int, raw: Raw, error: ErrorSink): Boolean {
    var value = valueStart
    var isValid = true

    ensureDecUint(
        value,
        raw,
        false,
        ParseErrorReason.InvalidFloatPart(FloatPart.Mantissa),
        error,
    ).let { (next, valid) ->
        value = next
        isValid = isValid and valid
    }

    if (value < raw.length && raw.byteAt(value) == '.'.code) {
        value += 1
        ensureDecUint(
            value,
            raw,
            true,
            ParseErrorReason.InvalidFloatPart(FloatPart.Fraction),
            error,
        ).let { (next, valid) ->
            value = next
            isValid = isValid and valid
        }
    }

    if (value < raw.length && (raw.byteAt(value) == 'e'.code || raw.byteAt(value) == 'E'.code)) {
        value += 1
        if (value < raw.length && (raw.byteAt(value) == '+'.code || raw.byteAt(value) == '-'.code)) value += 1
        ensureDecUint(
            value,
            raw,
            true,
            ParseErrorReason.InvalidFloatPart(FloatPart.Exponent),
            error,
        ).let { (next, valid) ->
            value = next
            isValid = isValid and valid
        }
    }

    if (value < raw.length) {
        error.reportError(
            ParseError(
                ParseErrorReason.InvalidScalar(ScalarKind.Float),
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(value, raw.length),
            ),
        )
        isValid = false
    }

    return isValid
}

/** Returns where the digits ended and whether they were valid. */
private fun ensureDecUint(
    start: Int,
    raw: Raw,
    zeroPrefix: Boolean,
    invalidReason: ParseErrorReason,
    error: ErrorSink,
): Pair<Int, Boolean> {
    var isValid = true
    var value = start
    var digitCount = 0
    while (value < raw.length) {
        val current = raw.byteAt(value)
        if (isDigit(current)) {
            digitCount += 1
        } else if (current != '_'.code) {
            break
        }
        value += 1
    }

    when {
        digitCount == 0 -> {
            error.reportError(
                ParseError(
                    invalidReason,
                    context = raw.wholeContext(),
                    expected = listOf(Expected.Token(ExpectedToken.Digits)),
                    unexpected = Span(start, start),
                ),
            )
            isValid = false
        }
        digitCount == 1 -> {}
        raw.byteAt(start) == '0'.code && !zeroPrefix -> {
            error.reportError(
                ParseError(
                    ParseErrorReason.UnexpectedLeadingZero,
                    context = raw.wholeContext(),
                    expected = EXPECTED_NOTHING,
                    unexpected = Span(start, start + 1),
                ),
            )
            isValid = false
        }
    }

    return value to isValid
}

private fun ensureNoLeadingZero(value: Int, raw: Raw, error: ErrorSink): Boolean {
    if (value < raw.length && raw.byteAt(value) == '0'.code) {
        error.reportError(
            ParseError(
                ParseErrorReason.UnexpectedLeadingZero,
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(value, value + 1),
            ),
        )
        return false
    }
    return true
}

private fun ensureRadixedValue(valueStart: Int, raw: Raw, radix: IntegerRadix, error: ErrorSink): Boolean {
    var isValid = true

    var value = valueStart
    if (value < raw.length && (raw.byteAt(value) == '+'.code || raw.byteAt(value) == '-'.code)) {
        var position = 0
        while (raw.byteAt(position) != '+'.code && raw.byteAt(position) != '-'.code) position++
        error.reportError(
            ParseError(
                ParseErrorReason.UnexpectedSign,
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(position, position + 1),
            ),
        )
        isValid = false
        value += 1
    }

    var offset = value
    while (offset < raw.length) {
        val byte = raw.byteAt(offset)
        if (!radix.isValid(byte) && byte != '_'.code) {
            error.reportError(
                ParseError(
                    ParseErrorReason.InvalidScalar(ScalarKind.Integer(radix)),
                    context = raw.wholeContext(),
                    unexpected = Span(offset, offset),
                ),
            )
            isValid = false
        }
        offset += utf8Length(byte)
    }

    return isValid
}

private fun decodeFloatOrInteger(
    streamStart: Int,
    raw: Raw,
    kind: ScalarKind,
    output: DecodedStringBuilder,
    error: ErrorSink,
): ScalarKind {
    output.clear()

    var stream = streamStart
    while (stream < raw.length) {
        var separator = stream
        while (separator < raw.length && raw.byteAt(separator) != '_'.code) separator++
        val hasSeparator = separator < raw.length

        val partStart = stream
        val partEnd = separator
        stream = partEnd

        if (hasSeparator) {
            stream += 1

            var isInvalidSeparator = false
            if (partEnd > partStart) {
                if (!isAnyDigit(raw.byteAt(partEnd - 1), kind)) isInvalidSeparator = true
            } else if (partStart == streamStart) {
                isInvalidSeparator = true
            }

            if (stream < raw.length) {
                if (!isAnyDigit(raw.byteAt(stream), kind)) isInvalidSeparator = true
            } else {
                isInvalidSeparator = true
            }

            if (isInvalidSeparator) {
                error.reportError(
                    ParseError(
                        ParseErrorReason.MisplacedUnderscore,
                        context = raw.wholeContext(),
                        expected = EXPECTED_NOTHING,
                        unexpected = Span(partEnd, partEnd + 1),
                    ),
                )
            }
        }

        if (partEnd > partStart) output.append(raw.text(partStart, partEnd))
    }

    return kind
}

private fun isAnyDigit(byte: Int, kind: ScalarKind): Boolean = if (kind == ScalarKind.Float) {
    isDigit(byte)
} else {
    isDigit(byte) || byte in 'a'.code..'f'.code || byte in 'A'.code..'F'.code
}

private fun isFloat(raw: Raw, from: Int): Boolean =
    raw.containsByte(from, '.'.code) || raw.containsByte(from, 'e'.code) || raw.containsByte(from, 'E'.code)

private fun decodeAs(
    raw: Raw,
    symbol: String,
    kind: ScalarKind,
    output: DecodedStringBuilder,
    error: ErrorSink,
): ScalarKind {
    output.clear()
    output.append(symbol)
    return kind
}

private class Keyword(val text: String, val kind: ScalarKind)

// In the order a tie between two equally near keywords is settled.
private val KEYWORDS = listOf(
    Keyword("true", ScalarKind.Boolean(true)),
    Keyword("false", ScalarKind.Boolean(false)),
    Keyword("inf", ScalarKind.Float),
    Keyword("nan", ScalarKind.Float),
)

private val SIGNED_KEYWORDS = KEYWORDS.filter { it.kind == ScalarKind.Float }

/**
 * Decodes the word from [from] as the nearest of [keywords] it may be a misspelling of, or as a string missing its
 * quotes when it is none of them.
 */
private fun decodeKeyword(
    raw: Raw,
    from: Int,
    keywords: List<Keyword>,
    output: DecodedStringBuilder,
    error: ErrorSink,
): ScalarKind {
    val controlled = keywords.firstOrNull { raw.isFollowedByControl(from, it.text) }
    if (controlled != null) {
        val control = from + controlled.text.length
        error.reportError(
            ParseError(
                ParseErrorReason.InvalidScalar(controlled.kind),
                context = raw.wholeContext(),
                expected = EXPECTED_NOTHING,
                unexpected = Span(control, control + 1),
            ),
        )
        return decodeAs(raw, controlled.text, controlled.kind, output, error)
    }
    val word = raw.text(from, raw.length).lowercase()
    if (keywords.any { word.length > it.text.length && word.startsWith(it.text) }) {
        return decodeInvalid(raw, output, error)
    }
    val keyword = keywords
        .filter { isOneEditAway(word, it.text) }
        .minByOrNull { if (word == it.text) 0 else 1 }
        ?: return decodeInvalid(raw, output, error)
    if (!raw.textEquals(from, keyword.text)) {
        error.reportError(
            ParseError(
                ParseErrorReason.InvalidScalar(keyword.kind),
                context = raw.wholeContext(),
                expected = listOf(Expected.Literal(keyword.text)),
                unexpected = Span(from, raw.length),
            ),
        )
        return decodeAs(raw, keyword.text, keyword.kind, output, error)
    }
    return decodeAs(raw, raw.text, keyword.kind, output, error)
}

/** Whether the word from [from] is [keyword], in any case, directly followed by a control character. */
private fun Raw.isFollowedByControl(from: Int, keyword: String): Boolean {
    val end = from + keyword.length
    if (end >= length) return false
    val next = byteAt(end)
    return (next < 0x20 || next == 0x7F) && text(from, end).equals(keyword, ignoreCase = true)
}

/**
 * Whether [word] is one character inserted, removed or replaced, or two neighbours swapped, away from [symbol], or
 * is [symbol] itself.
 */
private fun isOneEditAway(word: String, symbol: String): Boolean {
    if (word.length == symbol.length) {
        val differences = word.indices.filter { word[it] != symbol[it] }
        return differences.size <= 1 || differences.areSwappedNeighbours(word, symbol)
    }
    val (shorter, longer) = if (word.length < symbol.length) word to symbol else symbol to word
    if (longer.length - shorter.length != 1) return false
    val firstDifference = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: return true
    return shorter.substring(firstDifference) == longer.substring(firstDifference + 1)
}

private fun List<Int>.areSwappedNeighbours(word: String, symbol: String): Boolean {
    if (size != 2 || this[1] != this[0] + 1) return false
    return word[this[0]] == symbol[this[1]] && word[this[1]] == symbol[this[0]]
}

private fun decodeInvalid(raw: Raw, output: DecodedStringBuilder, error: ErrorSink): ScalarKind {
    val missingQuote = when {
        raw.endsWithText("'''") -> "'''"
        raw.endsWithText("\"\"\"") -> "\"\"\""
        raw.endsWithText("'") -> "'"
        raw.endsWithText("\"") -> "\""
        else -> null
    }
    if (missingQuote != null) {
        error.reportError(
            ParseError(
                ParseErrorReason.MissingOpeningQuote,
                context = raw.wholeContext(),
                expected = listOf(Expected.Literal(missingQuote)),
                unexpected = Span(0, 0),
            ),
        )
    } else {
        error.reportError(
            ParseError(
                ParseErrorReason.UnquotedString,
                context = raw.wholeContext(),
                expected = listOf(Expected.Token(ExpectedToken.LiteralString)),
                unexpected = raw.wholeContext(),
            ),
        )
    }

    output.clear()
    output.append(raw.text)
    return ScalarKind.String
}
