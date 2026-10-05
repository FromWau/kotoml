package com.fromwau.kotoml

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
            TomlParseError.serializer().descriptor to "TomlParseError(reason, span, position, context, expected)",
            Span.serializer().descriptor to "Span(start, end)",
            TextPosition.serializer().descriptor to "TextPosition(line, column)",
            TomlParseError.Reason.serializer().descriptor to """
                DottedKeyExtendsValue(key, valueType)
                DuplicateKey(keyPath)
                FloatOverflow()
                IntegerOverflow()
                InvalidDatetime(error)
                InvalidUtf8(byteOffset)
                KeyTooDeep()
                MissingDigits(radix)
                NotInToml10(feature)
                NotUnicode()
                Syntax(reason)
            """.trimIndent(),
            ParseErrorReason.serializer().descriptor to """
                ContentAfterComment()
                EmptyUnquotedKey()
                ExtraArrayComma()
                ExtraAssignment()
                ExtraEquals()
                ExtraInlineTableComma()
                InvalidCodePoint()
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
            Expected.serializer().descriptor to """
                Literal(literal)
                Token(token)
            """.trimIndent(),
            ScalarKind.serializer().descriptor to """
                Boolean(value)
                DateTime()
                Float()
                Integer(radix)
                String()
            """.trimIndent(),
            TomlEditError.serializer().descriptor to """
                IndexOutOfBounds(index, length)
                NotATable(key, itemType)
                NotATableElement(itemType)
                NotAValue(itemType)
                NotAnArray(index, itemType)
                NotFound(key)
                TooDeep(keyPath)
                TypeMismatch(key, expected, found)
            """.trimIndent(),
            TomlReadError.serializer().descriptor to """
                Io(detail)
                Parse(error)
            """.trimIndent(),
            TomlWriteError.serializer().descriptor to """
                Io(detail)
                NotInToml10(keyPath, feature)
                NotUnicode(keyPath)
                TooDeep(keyPath)
            """.trimIndent(),
            TomlIoError.serializer().descriptor to "Io(detail)",
            DatetimeParseError.serializer().descriptor to "DatetimeParseError(part, expected)",
            DatetimeExpected.serializer().descriptor to """
                DateOrTimeSeparator()
                DaySeparator()
                Digits(field)
                End()
                MinuteSeparator()
                OffsetStart()
                Range(field, min, max)
                SecondBeforeFraction()
                TimeStart()
                Width(field, width)
                YearOrHour()
            """.trimIndent(),
            TomlEditError.NotATable.serializer().descriptor.getElementDescriptor(1) to """
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
            ScalarKind.Integer.serializer().descriptor.getElementDescriptor(0) to """
                Dec
                Hex
                Oct
                Bin
            """.trimIndent(),
            ParseErrorReason.InvalidString.serializer().descriptor.getElementDescriptor(0) to """
                MultilineLiteralString
                MultilineBasicString
                BasicString
                LiteralString
            """.trimIndent(),
            Expected.Token.serializer().descriptor.getElementDescriptor(0) to """
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
            DatetimeParseError.serializer().descriptor.getElementDescriptor(0) to """
                Date
                DateTime
                Time
                Offset
            """.trimIndent(),
            DatetimeExpected.Digits.serializer().descriptor.getElementDescriptor(0) to """
                Year
                Month
                Day
                Hour
                Minute
                Second
                Nanosecond
            """.trimIndent(),
            ParseErrorReason.InvalidFloatPart.serializer().descriptor.getElementDescriptor(0) to """
                Mantissa
                Fraction
                Exponent
            """.trimIndent(),
            TomlParseError.Reason.NotInToml10.serializer().descriptor.getElementDescriptor(0) to """
                MultilineInlineTable
                InlineTableTrailingComma
                TimeWithoutSeconds
                HexEscape
                EscapeEscape
            """.trimIndent(),
        )
        for ((descriptor, schema) in expected) {
            assertEquals(schema, descriptor.wireSchema(), descriptor.serialName)
        }
    }
}
