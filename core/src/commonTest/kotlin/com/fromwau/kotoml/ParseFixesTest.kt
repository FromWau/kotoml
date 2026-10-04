package com.fromwau.kotoml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where upstream misplaces, misnames or accepts a problem, and how kotoml reports it instead. */
class ParseFixesTest {

    private val escapesV10 = listOf("b", "f", "n", "r", "t", "\\", "\"", "u", "U").map { Expected.Literal(it) }

    private fun syntax(reason: ParseErrorReason) = TomlParseError.Reason.Syntax(reason)

    private val invalidDecimal = syntax(ParseErrorReason.InvalidScalar(ScalarKind.Integer(IntegerRadix.Dec)))

    @Test
    fun `bytes that are not UTF-8 after non-ASCII text are placed by the characters before them`() {
        val bytes = "a = \"é\"".encodeToByteArray() + byteArrayOf(0xFF.toByte())
        val error = TomlDocument.parse(bytes).errorOrFail()
        assertEquals(TextPosition(1, 8), error.position)
        assertSnapshot(
            """
TOML parse error at line 1, column 8
  |
1 | a = "é"
  |        ^
invalid utf-8 sequence of 1 bytes from index 8

""",
            error.render(bytes.decodeToString()),
        )
    }

    @Test
    fun `an error at a non-ASCII character after non-ASCII text is placed by the characters before it`() {
        val error = TomlDocument.parse("\"é\" = ü\n").errorOrFail()
        assertEquals(TextPosition(1, 7), error.position)
    }

    @Test
    fun `rendering against text without the error's line gives only the message`() {
        val error = TomlDocument.parse("a = 1\n\nb = \n").errorOrFail()
        assertEquals(3, error.position?.line)
        assertEquals(error.copy(span = null, position = null).render(""), error.render(""))
        assertFalse(error.render("a = 1\n").startsWith("TOML parse error at line"))
    }

    @Test
    fun `V1_0 reports a malformed x escape as an unknown escape at its letter`() {
        val error = TomlDocument.parse("a = \"\\xZ1\"\n", TomlVersion.V1_0).errorOrFail()
        assertEquals(syntax(ParseErrorReason.MissingEscapedValue), error.reason)
        assertEquals(escapesV10, error.expected)
        assertEquals(TextPosition(1, 7), error.position)
    }

    @Test
    fun `V1_0 reports an x escape with one hex digit as an unknown escape at its letter`() {
        val error = TomlDocument.parse("a = \"\\x4Z\"\n", TomlVersion.V1_0).errorOrFail()
        assertEquals(syntax(ParseErrorReason.MissingEscapedValue), error.reason)
        assertEquals(TextPosition(1, 7), error.position)
    }

    @Test
    fun `V1_0 still reports a short u escape as too few digits`() {
        val error = TomlDocument.parse("a = \"\\uZ\"\n", TomlVersion.V1_0).errorOrFail()
        assertEquals(syntax(ParseErrorReason.TooFewUnicodeDigits), error.reason)
    }

    @Test
    fun `V1_1 reports a malformed x escape as too few digits where the digits stop`() {
        val error = TomlDocument.parse("a = \"\\xZ1\"\n").errorOrFail()
        assertEquals(syntax(ParseErrorReason.TooFewUnicodeDigits), error.reason)
        assertEquals(TextPosition(1, 8), error.position)
    }

    @Test
    fun `a syntax error carries the value it was found in as its context`() {
        val error = TomlDocument.parse("a = 0xg\n").errorOrFail()
        assertEquals(Span(4, 7), error.context)
    }

    @Test
    fun `a syntax error's context indexes the parsed String`() {
        val error = TomlDocument.parse("\"é\" = ü\n").errorOrFail()
        assertEquals(Span(6, 7), error.span)
        assertEquals(Span(6, 7), error.context)
    }

    @Test
    fun `a letter after an underscore in an integer is an invalid integer at the letter`() {
        val error = TomlDocument.parse("a = 1_0z\n").errorOrFail()
        assertEquals(invalidDecimal, error.reason)
        assertEquals(TextPosition(1, 8), error.position)
    }

    @Test
    fun `a letter right after an underscore is an invalid integer at the letter`() {
        val error = TomlDocument.parse("a = 1_a\n").errorOrFail()
        assertEquals(invalidDecimal, error.reason)
        assertEquals(TextPosition(1, 7), error.position)
    }

    @Test
    fun `a dash inside an underscored integer is an invalid integer at the dash`() {
        val error = TomlDocument.parse("a = 12_3-4\n").errorOrFail()
        assertEquals(invalidDecimal, error.reason)
        assertEquals(TextPosition(1, 9), error.position)
    }

    @Test
    fun `letters after an underscored integer are an invalid integer at the first letter`() {
        val error = TomlDocument.parse("a = 1_000abc\n").errorOrFail()
        assertEquals(invalidDecimal, error.reason)
        assertEquals(TextPosition(1, 10), error.position)
    }

    @Test
    fun `a second sign is a redundant sign at the second sign`() {
        val error = TomlDocument.parse("a = +-1\n").errorOrFail()
        assertEquals(syntax(ParseErrorReason.RedundantSign), error.reason)
        assertEquals(TextPosition(1, 6), error.position)
    }

    @Test
    fun `an underscored decimal integer too large reports an overflow`() {
        val error = TomlDocument.parse("a = 9_223_372_036_854_775_808\n").errorOrFail()
        assertEquals(TomlParseError.Reason.IntegerOverflow, error.reason)
    }

    @Test
    fun `an exponent too long to keep is weighed against the mantissa's digits`() {
        assertEquals(1.0, parseTomlFloat("0." + "0".repeat(999_999) + "1e1000000"))
    }

    @Test
    fun `an exponent too long to keep still gives infinity or zero for a short mantissa`() {
        assertEquals(Double.POSITIVE_INFINITY, parseTomlFloat("1e1000000"))
        assertEquals(0.0, parseTomlFloat("1e-1000000"))
        assertEquals(Double.POSITIVE_INFINITY, parseTomlFloat("1e" + "9".repeat(30)))
    }

    @Test
    fun `a date that does not exist cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Date(year = 2021, month = 2, day = 29) }
        assertFailsWith<IllegalArgumentException> { Date(year = 1900, month = 2, day = 29) }
        assertFailsWith<IllegalArgumentException> { Date(year = 2021, month = 4, day = 31) }
        assertFailsWith<IllegalArgumentException> { Date(year = 2021, month = 1, day = 0) }
        assertFailsWith<IllegalArgumentException> { Date(year = 2021, month = 13, day = 1) }
        assertFailsWith<IllegalArgumentException> { Date(year = 2021, month = 0, day = 1) }
        assertFailsWith<IllegalArgumentException> { Date(year = 10000, month = 1, day = 1) }
        assertFailsWith<IllegalArgumentException> { Date(year = -1, month = 1, day = 1) }
    }

    @Test
    fun `a leap day and the ends of the year range can be constructed`() {
        assertEquals("2020-02-29", Date(year = 2020, month = 2, day = 29).toString())
        assertEquals("2000-02-29", Date(year = 2000, month = 2, day = 29).toString())
        assertEquals("0000-01-01", Date(year = 0, month = 1, day = 1).toString())
        assertEquals("9999-12-31", Date(year = 9999, month = 12, day = 31).toString())
    }

    @Test
    fun `a time with a field out of range cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Time(hour = 24, minute = 0, second = null, nanosecond = null) }
        assertFailsWith<IllegalArgumentException> { Time(hour = -1, minute = 0, second = null, nanosecond = null) }
        assertFailsWith<IllegalArgumentException> { Time(hour = 0, minute = 60, second = null, nanosecond = null) }
        assertFailsWith<IllegalArgumentException> { Time(hour = 0, minute = 0, second = 61, nanosecond = null) }
        assertFailsWith<IllegalArgumentException> { Time(hour = 0, minute = 0, second = -1, nanosecond = null) }
        assertFailsWith<IllegalArgumentException> { Time(hour = 0, minute = 0, second = 0, nanosecond = 1_000_000_000) }
        assertFailsWith<IllegalArgumentException> { Time(hour = 0, minute = 0, second = 0, nanosecond = -1) }
    }

    @Test
    fun `a time with a nanosecond but no second cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Time(hour = 13, minute = 37, second = null, nanosecond = 5) }
    }

    @Test
    fun `a leap second with the largest fraction can be constructed`() {
        val time = Time(hour = 23, minute = 59, second = 60, nanosecond = 999_999_999)
        assertEquals("23:59:60.999999999", time.toString())
    }

    @Test
    fun `an offset of a day or more cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { Offset.Custom(1440) }
        assertFailsWith<IllegalArgumentException> { Offset.Custom(-1440) }
    }

    @Test
    fun `an offset just short of a day either way can be constructed`() {
        assertEquals("+23:59", Offset.Custom(1439).toString())
        assertEquals("-23:59", Offset.Custom(-1439).toString())
    }

    @Test
    fun `parsing a day the month does not have gives a typed error`() {
        assertEquals(
            DatetimeParseError(DatetimePart.Date, DatetimeExpected.Range(DatetimeField.Day, 1, 28)),
            Datetime.parse("2021-02-29").errorOrFail(),
        )
        assertEquals(
            DatetimeParseError(DatetimePart.Date, DatetimeExpected.Range(DatetimeField.Day, 1, 30)),
            Datetime.parse("2021-04-31").errorOrFail(),
        )
        assertEquals(
            DatetimeParseError(DatetimePart.Date, DatetimeExpected.Range(DatetimeField.Month, 1, 12)),
            Datetime.parse("2021-00-01").errorOrFail(),
        )
    }

    @Test
    fun `parsing a time or offset out of range gives a typed error`() {
        assertEquals(
            DatetimeParseError(DatetimePart.Time, DatetimeExpected.Range(DatetimeField.Hour, 0, 23)),
            Datetime.parse("24:00:00").errorOrFail(),
        )
        assertEquals(
            DatetimeParseError(DatetimePart.Time, DatetimeExpected.Range(DatetimeField.Second, 0, 60)),
            Datetime.parse("23:59:61").errorOrFail(),
        )
        assertEquals(
            DatetimeParseError(DatetimePart.Offset, DatetimeExpected.Range(DatetimeField.Hour, 0, 23)),
            Datetime.parse("1979-05-27T07:32:00+24:00").errorOrFail(),
        )
    }

    @Test
    fun `a leap day and a leap second and the widest offsets parse`() {
        assertEquals("2020-02-29", Datetime.parse("2020-02-29").getOrFail().toString())
        assertEquals("23:59:60.5", Datetime.parse("23:59:60.5").getOrFail().toString())
        assertEquals(
            Offset.Custom(1439),
            Datetime.parse("1979-05-27T07:32:00+23:59").getOrFail().offset,
        )
        assertEquals(
            Offset.Custom(-1439),
            Datetime.parse("1979-05-27T07:32:00-23:59").getOrFail().offset,
        )
    }

    @Test
    fun `offset date-times order by their fields and not by the instant`() {
        val laterFields = Datetime.parse("1979-05-27T07:00:00+02:00").getOrFail()
        val earlierFields = Datetime.parse("1979-05-27T06:00:00Z").getOrFail()
        assertTrue(laterFields > earlierFields)
    }
}
