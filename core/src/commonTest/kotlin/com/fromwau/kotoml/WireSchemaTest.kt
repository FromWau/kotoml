package com.fromwau.kotoml

import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The names every error and the types inside one put on the `--json` wire. A change here is a breaking change for
 * programs that read them: rename on purpose, and update this test with it.
 */
class WireSchemaTest {

    @Test
    fun `every error type keeps its wire names and fields`() {
        val expected = listOf(
            TomlParseError.serializer() to "TomlParseError(reason, span, position, context, expected)",
            Span.serializer() to "Span(start, end)",
            TextPosition.serializer() to "TextPosition(line, column)",
            TomlParseError.Reason.serializer() to """
                DottedKeyExtendsValue(key, valueType)
                DuplicateKey(key)
                FloatOverflow()
                IntegerOverflow()
                InvalidDatetime(error)
                InvalidUtf8(validUpTo, errorLength)
                KeyTooDeep()
                MissingDigits(radix)
                NotInToml10(feature)
                NotUnicode()
                Syntax(reason)
            """.trimIndent(),
            ParseErrorReason.serializer() to """
                ContentAfterComment()
                EmptyUnquotedKey()
                ExtraArrayComma()
                ExtraAssignment()
                ExtraEquals()
                ExtraInlineTableComma()
                InvalidCommentCharacter()
                InvalidFloatPart(part)
                InvalidInlineTableElement()
                InvalidKey()
                InvalidKeyValuePair()
                InvalidScalar(kind)
                InvalidString(encoding)
                InvalidTable()
                InvalidUnquotedKey()
                InvalidValue()
                KeyWithoutValue()
                LoneCarriageReturn()
                MisplacedUnderscore()
                MissingArrayComma()
                MissingArrayOpening()
                MissingAssignment()
                MissingCommentStart()
                MissingEscapedValue()
                MissingInlineTableComma()
                MissingInlineTableKey()
                MissingInlineTableOpening()
                MissingOpeningQuote()
                MissingTableOpen()
                MissingValue()
                MissingValueForKey()
                MultilineKey(encoding)
                RecursionLimit()
                RedundantRadixPrefix()
                RedundantSign()
                SignedRadixInteger()
                TooFewUnicodeDigits()
                UnclosedArray()
                UnclosedArrayTable()
                UnclosedInlineTable()
                UnclosedTable()
                UnexpectedContent()
                UnexpectedEqualsInArray()
                UnexpectedKeyOrValue()
                UnexpectedLeadingZero()
                UnexpectedSign()
                UnquotedString()
                UppercaseRadix()
            """.trimIndent(),
            Expected.serializer() to """
                Literal(literal)
                Token(token)
            """.trimIndent(),
            ScalarKind.serializer() to """
                Boolean(value)
                DateTime()
                Float()
                Integer(radix)
                String()
            """.trimIndent(),
            TomlEditError.serializer() to """
                IndexOutOfBounds(index, length)
                NotATable(key, itemType)
                NotATableElement(itemType)
                NotAValue(itemType)
                NotAnArray(index, itemType)
                NotFound(key)
                TypeMismatch(key, expected, found)
            """.trimIndent(),
            TomlReadError.serializer() to """
                Io(detail)
                Parse(error)
            """.trimIndent(),
            TomlWriteError.serializer() to """
                Io(detail)
                NotUnicode(keyPath)
                TooDeep(keyPath)
            """.trimIndent(),
            TomlIoError.serializer() to "Io(detail)",
            DatetimeParseError.serializer() to "DatetimeParseError(part, expected)",
            DatetimeExpected.serializer() to """
                DateOrTimeSeparator()
                DaySeparator()
                Digits(field)
                MinuteSeparator()
                OffsetStart()
                Range(field, min, max)
                SecondBeforeFraction()
                TimeStart()
                Width(field, width)
                YearOrHour()
            """.trimIndent(),
            serializer<TomlType>() to """
                Missing
                String
                Integer
                Float
                Boolean
                Datetime
                Array
                InlineTable
                Table
                ArrayOfTables
            """.trimIndent(),
            serializer<IntegerRadix>() to """
                Dec
                Hex
                Oct
                Bin
            """.trimIndent(),
            serializer<Encoding>() to """
                MultilineLiteralString
                MultilineBasicString
                BasicString
                LiteralString
            """.trimIndent(),
            serializer<ExpectedToken>() to """
                BasicString
                Characters
                Digits
                Key
                Letters
                LiteralString
                NonDoubleQuoteVisibleCharacters
                NonSingleQuoteCharacters
                NonSingleQuoteVisibleCharacters
                Numbers
                PrintableCharacters
                UnicodeHexadecimalValue
                UnquotedString
                Value
            """.trimIndent(),
            serializer<DatetimePart>() to """
                Date
                DateTime
                Time
                Offset
            """.trimIndent(),
            serializer<DatetimeField>() to """
                Year
                Month
                Day
                Hour
                Minute
                Second
                Nanosecond
            """.trimIndent(),
            serializer<FloatPart>() to """
                Mantissa
                Fraction
                Exponent
            """.trimIndent(),
            serializer<Toml11Feature>() to """
                MultilineInlineTable
                InlineTableTrailingComma
                TimeWithoutSeconds
            """.trimIndent(),
            serializer<TomlVersion>() to """
                V1_0
                V1_1
            """.trimIndent(),
        )
        for ((serializer, schema) in expected) {
            assertEquals(schema, serializer.wireSchema(), serializer.descriptor.serialName)
        }
    }
}
