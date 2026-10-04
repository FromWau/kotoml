package com.fromwau.kotoml

import com.fromwau.kotoml.TomlParseError.Reason
import com.fromwau.kotoml.parser.ParseError

/**
 * The error as a person reads it, pointing at the offending text of [source], the text that was parsed:
 *
 * ```text
 * TOML parse error at line 2, column 1
 *   |
 * 2 | a = 2
 *   | ^
 * duplicate key
 * ```
 *
 * The wording is `toml_edit`'s, in English.
 */
public fun TomlParseError.render(source: String): String = buildString {
    val span = span
    val position = position
    if (span != null && position != null) {
        val lineNum = position.line
        val column = position.column - 1
        val gutter = lineNum.toString().length
        val line = source.split('\n')[lineNum - 1]
        // Platforms decode bytes that are not UTF-8 differently, so the line shows only what comes before them.
        val content = if (reason is TomlParseError.Reason.InvalidUtf8) line.take(column) else line
        val contentBytes = content.encodeToByteArray().size
        // Allow the highlight to reach one past the line.
        val highlightLength = minOf(span.end - span.start, maxOf(contentBytes - column, 0))

        append("TOML parse error at line $lineNum, column ${position.column}\n")
        repeat(gutter + 1) { append(' ') }
        append("|\n")

        append("$lineNum | ")
        append(content)
        append('\n')

        repeat(gutter + 1) { append(' ') }
        append('|')
        repeat(column + 1) { append(' ') }
        // The span is empty at the end of input; always show at least one caret.
        append('^')
        for (index in 1 until highlightLength) append('^')
        append('\n')
    }
    append(describe(reason.describe(), expected))
    append('\n')
}

internal fun ParseError.describe(): String = describe(reason.describe(), expected)

internal fun ParseErrorReason.describe(): String = when (this) {
    is ParseErrorReason.InvalidScalar -> when (val kind = kind) {
        ScalarKind.String -> "invalid string"
        is ScalarKind.Boolean -> "invalid boolean"
        ScalarKind.DateTime -> "invalid date-time"
        ScalarKind.Float -> "invalid float"
        is ScalarKind.Integer -> when (kind.radix) {
            IntegerRadix.Dec -> "invalid integer number"
            IntegerRadix.Hex -> "invalid hexadecimal number"
            IntegerRadix.Oct -> "invalid octal number"
            IntegerRadix.Bin -> "invalid binary number"
        }
    }
    is ParseErrorReason.InvalidFloatPart -> when (part) {
        FloatPart.Mantissa -> "invalid mantissa"
        FloatPart.Fraction -> "invalid fraction"
        FloatPart.Exponent -> "invalid exponent"
    }
    is ParseErrorReason.InvalidString -> "invalid ${encoding.describe()}"
    is ParseErrorReason.MultilineKey -> "keys cannot be ${encoding.describe()}s"
    ParseErrorReason.InvalidKey -> "invalid key"
    ParseErrorReason.InvalidTable -> "invalid table"
    ParseErrorReason.InvalidValue -> "invalid value"
    ParseErrorReason.InvalidUnquotedKey -> "invalid unquoted key"
    ParseErrorReason.InvalidKeyValuePair -> "invalid key-value pair"
    ParseErrorReason.InvalidInlineTableElement -> "invalid inline table element"
    ParseErrorReason.InvalidCommentCharacter -> "invalid comment character"
    ParseErrorReason.UnquotedString -> "string values must be quoted"
    ParseErrorReason.EmptyUnquotedKey -> "unquoted keys cannot be empty"
    ParseErrorReason.MissingEscapedValue -> "missing escaped value"
    ParseErrorReason.TooFewUnicodeDigits -> "too few unicode value digits"
    ParseErrorReason.LoneCarriageReturn -> "carriage return must be followed by newline"
    ParseErrorReason.UnexpectedLeadingZero -> "unexpected leading zero"
    ParseErrorReason.UnexpectedSign -> "unexpected sign"
    ParseErrorReason.RedundantSign -> "redundant numeric sign"
    ParseErrorReason.RedundantRadixPrefix -> "redundant integer number prefix"
    ParseErrorReason.UppercaseRadix -> "radix must be lowercase"
    ParseErrorReason.SignedRadixInteger -> "integers with a radix cannot be signed"
    ParseErrorReason.MisplacedUnderscore -> "`_` may only go between digits"
    ParseErrorReason.MissingAssignment -> "missing assignment between key-value pairs"
    ParseErrorReason.ExtraAssignment -> "extra assignment between key-value pairs"
    ParseErrorReason.ExtraEquals -> "extra `=`"
    ParseErrorReason.KeyWithoutValue -> "key with no value"
    ParseErrorReason.MissingValueForKey -> "missing value for key"
    ParseErrorReason.MissingValue -> "missing value"
    ParseErrorReason.UnexpectedKeyOrValue -> "unexpected key or value"
    ParseErrorReason.UnexpectedEqualsInArray -> "unexpected `=` in array"
    ParseErrorReason.UnexpectedContent -> "unexpected content"
    ParseErrorReason.ContentAfterComment -> "unexpected content between comment and newline"
    ParseErrorReason.MissingTableOpen -> "missing table open"
    ParseErrorReason.MissingArrayOpening -> "missing array opening"
    ParseErrorReason.MissingInlineTableOpening -> "missing inline table opening"
    ParseErrorReason.MissingOpeningQuote -> "missing opening quote"
    ParseErrorReason.MissingCommentStart -> "missing comment start"
    ParseErrorReason.MissingInlineTableKey -> "missing key for inline table element"
    ParseErrorReason.UnclosedTable -> "unclosed table"
    ParseErrorReason.UnclosedArrayTable -> "unclosed array table"
    ParseErrorReason.UnclosedArray -> "unclosed array"
    ParseErrorReason.UnclosedInlineTable -> "unclosed inline table"
    ParseErrorReason.MissingArrayComma -> "missing comma between array elements"
    ParseErrorReason.MissingInlineTableComma -> "missing comma between key-value pairs"
    ParseErrorReason.ExtraArrayComma -> "extra comma in array"
    ParseErrorReason.ExtraInlineTableComma -> "extra comma in inline table"
    ParseErrorReason.RecursionLimit -> "cannot recurse further; max recursion depth met"
    ParseErrorReason.AllocationFailed -> "could not allocate for string"
}

internal fun Reason.describe(): String = when (this) {
    is Reason.Syntax -> reason.describe()
    is Reason.DuplicateKey -> "duplicate key `${Key(key)}`"
    is Reason.DottedKeyExtendsValue -> "cannot extend value of type ${valueType.describe()} with a dotted key"
    is Reason.InvalidDatetime -> error.describe()
    is Reason.MissingDigits -> when (radix) {
        IntegerRadix.Dec -> "expected digits"
        IntegerRadix.Hex -> "expected hexadecimal digits after `0x`"
        IntegerRadix.Oct -> "expected octal digits after `0o`"
        IntegerRadix.Bin -> "expected binary digits after `0b`"
    }
    Reason.IntegerOverflow -> "integer number overflowed"
    Reason.FloatOverflow -> "floating-point number overflowed"
    Reason.KeyTooDeep -> "recursion limit"
    is Reason.InvalidUtf8 -> if (errorLength == null) {
        "incomplete utf-8 byte sequence from index $validUpTo"
    } else {
        "invalid utf-8 sequence of $errorLength bytes from index $validUpTo"
    }
    is Reason.NotInToml10 -> when (feature) {
        Toml11Feature.MultilineInlineTable -> "inline tables cannot span lines in TOML 1.0"
        Toml11Feature.InlineTableTrailingComma -> "inline tables cannot end with a comma in TOML 1.0"
        Toml11Feature.TimeWithoutSeconds -> "times need seconds in TOML 1.0"
    }
}

internal fun TomlType.describe(): String = when (this) {
    TomlType.Missing -> "missing"
    TomlType.String -> "string"
    TomlType.Integer -> "integer"
    TomlType.Float -> "float"
    TomlType.Boolean -> "boolean"
    TomlType.Datetime -> "datetime"
    TomlType.Array -> "array"
    TomlType.InlineTable -> "inline table"
    TomlType.Table -> "table"
    TomlType.ArrayOfTables -> "array of tables"
}

internal fun DatetimeParseError.describe(): String = buildString {
    append("invalid ")
    append(part?.describe() ?: "datetime")
    val expected = expected
    if (expected != null) {
        append(", expected ")
        append(expected.describe(part))
    }
}

internal fun DatetimePart.describe(): String = when (this) {
    DatetimePart.Date -> "date"
    DatetimePart.DateTime -> "date-time"
    DatetimePart.Time -> "time"
    DatetimePart.Offset -> "offset"
}

internal fun DatetimeExpected.describe(part: DatetimePart?): String = when (this) {
    DatetimeExpected.YearOrHour -> "year or hour"
    DatetimeExpected.DateOrTimeSeparator -> "`-` (YYYY-MM) or `:` (HH:MM)"
    DatetimeExpected.DaySeparator -> "`-` (MM-DD)"
    DatetimeExpected.TimeStart -> "`T` between date and time"
    DatetimeExpected.MinuteSeparator -> "`:` (HH:MM)"
    DatetimeExpected.OffsetStart -> "`Z`, +OFFSET, -OFFSET"
    is DatetimeExpected.Digits -> field.describe()
    is DatetimeExpected.Width -> {
        val digits = if (width == 4) "four" else "two"
        "a $digits-digit ${field.describe()} (${field.placeholder()})"
    }
    is DatetimeExpected.Range -> {
        // An offset's hours and minutes are counts, so toml_datetime names them in the plural.
        val name = if (part == DatetimePart.Offset) "${field.describe()}s" else field.describe()
        "$name between ${min.toString().padStart(2, '0')} and ${max.toString().padStart(2, '0')}"
    }
}

private fun DatetimeField.describe(): String = name.lowercase()

private fun DatetimeField.placeholder(): String = when (this) {
    DatetimeField.Year -> "YYYY"
    DatetimeField.Month, DatetimeField.Minute -> "MM"
    DatetimeField.Day -> "DD"
    DatetimeField.Hour -> "HH"
    DatetimeField.Second -> "SS"
    DatetimeField.Nanosecond -> "N"
}

private fun Encoding.describe(): String = when (this) {
    Encoding.LiteralString -> "literal string"
    Encoding.BasicString -> "basic string"
    Encoding.MultilineLiteralString -> "multi-line literal string"
    Encoding.MultilineBasicString -> "multi-line basic string"
}

internal fun ExpectedToken.describe(): String = when (this) {
    ExpectedToken.BasicString -> "basic string"
    ExpectedToken.Characters -> "characters"
    ExpectedToken.Digits -> "digits"
    ExpectedToken.Key -> "key"
    ExpectedToken.Letters -> "letters"
    ExpectedToken.LiteralString -> "literal string"
    ExpectedToken.NonDoubleQuoteVisibleCharacters -> "non-double-quote visible characters"
    ExpectedToken.NonSingleQuoteCharacters -> "non-single-quote characters"
    ExpectedToken.NonSingleQuoteVisibleCharacters -> "non-single-quote visible characters"
    ExpectedToken.Numbers -> "numbers"
    ExpectedToken.PrintableCharacters -> "printable characters"
    ExpectedToken.UnicodeHexadecimalValue -> "unicode hexadecimal value"
    ExpectedToken.UnquotedString -> "unquoted string"
    ExpectedToken.Value -> "value"
}

private fun describe(
    description: String,
    expected: List<Expected>?,
): String = buildString {
    append(description)
    if (expected.isNullOrEmpty()) return@buildString
    append(", expected ")
    expected.forEachIndexed { index, item ->
        if (index != 0) append(", ")
        when (item) {
            is Expected.Literal -> append(renderLiteral(item.literal))
            is Expected.Token -> append(item.token.describe())
        }
    }
}

private fun renderLiteral(literal: String): String = when {
    literal == "\n" -> "newline"
    literal == "`" -> "'`'"
    literal.all { it.code < 0x20 || it.code == 0x7F } -> "`${escapeDebug(literal)}`"
    else -> "`$literal`"
}

// Rust's `str::escape_debug` for the ASCII control characters this is reached with.
private fun escapeDebug(text: String): String = buildString {
    for (char in text) {
        when (char) {
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            else -> append("\\u{${char.code.toString(16)}}")
        }
    }
}
